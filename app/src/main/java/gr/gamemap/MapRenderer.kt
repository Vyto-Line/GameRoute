package gr.gamemap

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** Car arrival: the parking drop-down (null = closed; empty while loading or when none). */
object CarParking {
    @Volatile var list: List<Place>? = null
    @Volatile var loading = false
}

/**
 * Draws the themed map with route, player and HUD onto any Canvas (the phone's view or
 * the Android Auto surface). Also remembers the last camera so touch can be mapped back.
 */
class MapRenderer(internal val car: Boolean = false) {
    internal val pois = PoiLayer()
    /** The place whose card is open (gets a ring on the map). */
    @Volatile var selectedPlace: Place? = null
    /** The tappable place icon under a screen point, if any. */
    fun placeAt(x: Float, y: Float): PoiLayer.Poi? = pois.at(x, y)

    /** This screen's own view (the car never moves the phone's map, nor the other way round). */
    internal val view get() = if (car) MapState.car else MapState.phone

    /** The camera used for the last frame. */
    class Camera(
        val px: Double, val py: Double, val world: Double, val scale: Float,
        val bearing: Float, val cx: Float, val cy: Float,
    )

    var camera: Camera? = null
        internal set

    /** True if the last frame was mid-animation and another frame should follow. */
    var animating = false
    /** Rain or snow is falling (keeps a slow redraw going even when nothing else moves). */
    var effectsMoving = false
        internal set

    internal val tilePaint = Paint().apply { isFilterBitmap = true }
    internal val overlayPaint = Paint().apply { isFilterBitmap = true }
    internal val pixelPaint = Paint().apply { isFilterBitmap = false; isDither = false }
    internal val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    internal val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    internal val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    internal val text = Paint(Paint.ANTI_ALIAS_FLAG)
    internal val src = Rect()
    internal val dst = RectF()
    internal val path = Path()
    internal var paperTex: Bitmap? = null
    internal val labels = Labels()
    internal val tileList = ArrayList<IntArray>(64)
    internal val tilePool = ArrayList<IntArray>(64)
    internal val quarter = RectF()

    /** Where Athens is, until the first GPS fix arrives. */
    internal val fallback = LatLng(37.9838, 23.7275)

    /**
     * @param area the part of the canvas not covered by other UI
     * @param d    screen density (1 = 160 dpi)
     * @param hud  draw the turn banner ourselves (the car shows its own instead)
     */
    /**
     * Adapts to the device: when frames take too long (a slow phone, or a car screen drawn
     * without the GPU), switch to a lighter look (no tile smoothing, no style overlays)
     * until there's headroom again. Frame time is measured every frame.
     */
    @Volatile var lite = false
        internal set
    internal var tileZ = -1
    var forceLite: Boolean? = null
    internal var frameAvg = 0.0
    internal var frames = 0

    fun draw(c: Canvas, w: Int, h: Int, area: Rect, d: Float, hud: Boolean) {
        val f0 = System.nanoTime()
        try { drawFrame(c, w, h, area, d, hud) } finally {
            val ms = (System.nanoTime() - f0) / 1e6
            frames++
            if (frames > 20 && !interacting) frameAvg = if (frameAvg == 0.0) ms else frameAvg * 0.9 + ms * 0.1
            val budget = if (car) 30.0 else 24.0
            lite = forceLite ?: when {
                !lite && frameAvg > budget -> true
                lite && frameAvg < budget * 0.45 -> false
                else -> lite
            }
        }
    }

    internal fun drawFrame(c: Canvas, w: Int, h: Int, fullArea: Rect, d: Float, hud: Boolean) {
        compassBounds.setEmpty()
        val zoomAnim = view.tick()
        interacting = view.gesture || (zoomAnim && view.userZooming)
        val moveAnim = LocationTracker.tick()
        animating = zoomAnim || moveAnim || (wasInteracting && !(zoomAnim || view.gesture))
        wasInteracting = zoomAnim || view.gesture

        val theme = if (car) MapState.carTheme ?: MapState.theme else MapState.theme
        // choosing a route in the car: the routes go on the map beside the cards
        val area = if (car && hud && carButtons && NavEngine.planningOn(car))
            Rect(fullArea.left + carRoutesWidth(fullArea, d).toInt() + (28 * d).toInt(), fullArea.top, fullArea.right - (70 * d).toInt(), fullArea.bottom).takeIf { it.width() > 100 } ?: fullArea
        else fullArea
        view.fitRequest?.let { fit(it, area, d, theme) }
        c.drawColor(theme.background)
        text.typeface = theme.typeface

        val me = LocationTracker.displayPosition()
        val following = view.following
        val center = if (following) me ?: fallback else view.freeCenter ?: me ?: fallback
        val bearing = if (following) view.userBearing ?: LocationTracker.heading else view.freeBearing
        currentBearing = bearing

        val zoom = view.zoom
        // the tiles' level: going out, the level above is kept (drawn a little smaller) until 0.35
        // below it, so the automatic zoom hovering round a whole level doesn't redraw every tile
        val zf = floor(zoom).toInt().coerceIn(2, 19)
        val z = if (tileZ == zf + 1 && zoom >= tileZ - 0.35) tileZ else zf
        tileZ = z
        val tilePx = TileCache.tilePx
        val scale = (2.0.pow(zoom - z) * (d / (tilePx / 256f)) * 1.15).toFloat()
        val world = tilePx * 2.0.pow(z)
        val px = Geo.x(center.lon, world)
        val py = Geo.y(center.lat, world)

        val radius = min(area.width(), area.height()) / 2f - 14 * d
        val cx = area.exactCenterX()
        val cy = if (theme.radar) area.exactCenterY()
        else area.top + area.height() * arrowFrac(when {
            // the car, moving or on a route: the arrow low on the screen, the road ahead in view
            car && following && (NavEngine.active || LocationTracker.speedMps > 3f) -> 0.74f
            NavEngine.active && following -> 0.68f
            else -> 0.55f
        })
        camera = Camera(px, py, world, scale, bearing, cx, cy)

        c.save()
        if (theme.radar) {
            path.reset(); path.addCircle(cx, cy, radius, Path.Direction.CW); c.clipPath(path)
        } else {
            // the map fills the whole screen, also behind buttons and cards; only the
            // HUD and the camera centre keep to the free area
            c.clipRect(0, 0, w, h)
        }
        c.translate(cx, cy)
        c.rotate(-bearing)
        c.scale(scale, scale)

        var tp0 = System.nanoTime()
        if (profile) allocProbe?.let { allocMark = it() }
        drawTiles(c, theme, z, px, py, hypot(w.toDouble(), h.toDouble()) * 0.75 / scale, scale, bearing, cx, cy, w, h)
        prof("tiles", tp0); tp0 = System.nanoTime()
        val reach = hypot(w.toDouble(), h.toDouble()) * 0.75 / scale
        // traffic glow under the route, traffic colours on top of it (jams on your route stay visible)
        // while choosing a route the routes' own traffic bars say enough; the map stays calm
        prof("traffic", tp0); tp0 = System.nanoTime()
        drawRoute(c, theme, world, px, py, scale, d)
        prof("route", tp0); tp0 = System.nanoTime()
        prof("traffic", tp0)

        // accuracy halo around the player
        if (me != null) {
            val mx = (Geo.x(me.lon, world) - px).toFloat(); val my = (Geo.y(me.lat, world) - py).toFloat()
            val acc = LocationTracker.accuracy
            if (acc > 8) {
                val r = (acc / Geo.metersPerPixel(me.lat, world)).toFloat()
                fill.color = Color.argb(40, Color.red(theme.player), Color.green(theme.player), Color.blue(theme.player))
                c.drawCircle(mx, my, r, fill)
            }
        }
        c.restore()

        // names on top of the map, laid out on screen (never overlapping, always upright)
        tp0 = System.nanoTime()
        labels.draw(c, camera!!, z, area, d, theme)
        if (labels.fading) animating = true
        prof("labels", tp0); tp0 = System.nanoTime()
        // places (fuel, parking, food…) at street level; while navigating only fuel and parking
        if (MapState.showPlaces && !NavEngine.planningOn(car)) {
            pois.draw(c, camera!!, zoom, w, h, d, if (NavEngine.active) NAV_PLACES else null, selectedPlace?.pos)
        }
        // the fuel list's prices, while it's open
        if (!car && Fuel.pins.isNotEmpty()) drawPricePins(c, camera!!, w, h, d, theme)
        // your parked car (when not driving)
        if (!NavEngine.active) Parked.get()?.let { sp ->
            val (sx, sy) = toScreen(sp.pos, camera!!)
            if (sx > -40 && sy > -40 && sx < w + 40 && sy < h + 40) {
                fill.color = Color.WHITE; c.drawCircle(sx, sy, 15 * d, fill)
                fill.color = Color.rgb(84, 104, 245); c.drawCircle(sx, sy, 12.5f * d, fill)
                text.textAlign = Paint.Align.CENTER; text.textSize = 14 * d; text.color = Color.WHITE
                c.drawText("🚗", sx, sy + 5 * d, text)
            }
        }
        // stops on the way: amber pins
        for (st in NavEngine.stops) { val (sx, sy) = toScreen(st.pos, camera!!); drawDroppedPin(c, sx, sy, d, Color.rgb(245, 159, 0)) }
        selectedPlace?.takeIf { it.icon == "📍" }?.let { pin ->
            val (sx, sy) = toScreen(pin.pos, camera!!)
            drawDroppedPin(c, sx, sy, d)
        }
        if (!Convoy.active) friendHits = emptyList()
        if (Convoy.active) {
            drawConvoyMembers(c, camera!!, area, d, bearing)
            // friends on screen glide between their updates (off screen: their next update redraws)
            if (friendGliding) animating = true
        }
        prof("places", tp0); tp0 = System.nanoTime()
        if (theme == Theme.TRAILBLAZER && zoom >= 13.5) drawLandmarks(c, camera!!, center, area, d)
        if (zoom >= 13.0) drawRoadAlerts(c, camera!!, center, area, d)
        prof("alerts", tp0); tp0 = System.nanoTime()

        if (VectorSource.busy.get() > 0) {
            animating = true
            text.textAlign = Paint.Align.CENTER; text.textSize = 13 * d
            val msg = if (theme == Theme.BLOCKY) "⛏ Building the world…" else gr.gamemap.L.t("Loading map…")
            val tw = text.measureText(msg) / 2 + 12 * d
            val bx = RectF(area.exactCenterX() - tw, area.bottom - 92 * d, area.exactCenterX() + tw, area.bottom - 64 * d)
            panel(c, bx, d, theme, outline = false)
            text.color = Ui.ink(theme)
            c.drawText(msg, bx.centerX(), bx.bottom - 9 * d, text)
        }

        // ---- theme overlays ----
        // style overlays cover the whole screen, like the map (no hard edge where buttons start)
        val full = Rect(0, 0, w, h)
        effectsMoving = false
        if (!lite) {
            if (theme.grid) drawGrid(c, full, d)
            if (theme.paper) drawPaper(c, full)
            if (theme.wash) drawWash(c, full)
            // Settings › Performance › Map effects (off in Battery mode and on phones that struggle)
            if (Perf.effectsOn) {
                if (theme.scanlines) drawScanlines(c, full, d, theme)
                if (theme.sunGlow) drawSunGlow(c, full)
                val raining = theme.rain && (!theme.rainOnlyDark || MapState.dark)
                if (raining) drawRain(c, full, d)
                if (theme.snow) drawSnow(c, full, d)
                if (theme.drafting) drawDrafting(c, full, d)
                effectsMoving = raining || theme.snow
            }
        }
        if (theme.radar) drawRadarFrame(c, cx, cy, radius, bearing, d)
        prof("overlays", tp0); tp0 = System.nanoTime()

        // player: centred when following, otherwise wherever it is on the map
        var playerAt: android.graphics.PointF? = null
        val weakNow = hud && LocationTracker.weak
        val locOff = LocationStatus.state != LocationStatus.State.OK
        if (me != null && locOff) {
            // location off / not allowed: a faint "?" where I was last (not an arrow that looks live)
            val (sx, sy) = if (following) cx to cy else toScreen(me, camera!!)
            if (area.contains(sx.toInt(), sy.toInt())) drawLastKnown(c, sx, sy, d, theme)
        } else if (me != null) {
            if (following) {
                drawPlayer(c, cx, cy, 0f, d, theme)
                if (weakNow) drawWeakGps(c, cx, cy, d, area)
                playerAt = android.graphics.PointF(cx, cy)
            } else {
                val (sx, sy) = toScreen(me, camera!!)
                if (area.contains(sx.toInt(), sy.toInt())) {
                    drawPlayer(c, sx, sy, LocationTracker.heading - bearing, d, theme)
                    if (weakNow) drawWeakGps(c, sx, sy, d, area)
                    playerAt = android.graphics.PointF(sx, sy)
                }
            }
        }
        lastPlayerAt = playerAt
        prof("arrow", tp0); tp0 = System.nanoTime()

        if (!locOff) drawSpeed(c, area, d, theme) else speedRect.setEmpty() // no live speed without location
        prof("speed", tp0); tp0 = System.nanoTime()
        if (car && hud) drawCarEta(c, area, d, theme)
        if (hud && NavEngine.showRoadName && (NavEngine.active || LocationTracker.speedMps > 2f) && !NavEngine.planningOn(car) && !NavEngine.arrived)
            playerAt?.let { drawCurrentRoad(c, area, d, theme, it.x, it.y, weakNow) } // (map moved away from you: no name)
        prof("roadname", tp0); tp0 = System.nanoTime()
        carHits.clear()
        // the car's trip card steps aside after 30 s, or as soon as a place is tapped
        val trip = NavEngine.lastTrip
        if (trip !== tripSeen) { tripSeen = trip; tripSeenAt = System.currentTimeMillis() }
        val carTrip = car && carButtons && trip != null && selectedPlace == null && System.currentTimeMillis() - tripSeenAt < 30_000
        // the convoy lobby takes the top-left corner (instead of Where to? and the saved places)
        carLobbyShown = car && hud && carButtons && carFriends == null && Convoy.active && Convoy.destination != null && !NavEngine.active && !NavEngine.planningOn(car) &&
            selectedPlace == null && Convoy.proposal == null && !Convoy.meSharing && !Convoy.underway && !carTrip &&
            carPanel == null // a convoy step (or any panel) open: the lobby waits behind it
        if (car && hud && carButtons && !NavEngine.planningOn(car) && !carTrip) {
            // Some Android Auto versions report a much narrower free area for a moment (after the
            // knob/touchpad focus or pan mode) and don't always widen it again: the buttons then
            // sat in the middle of the map. A real strip or panel never takes 30% of the width,
            // so then the buttons stay at the screen's edge.
            val ra = if (area.right < w * 0.7f) Rect(area.left, area.top, w, area.bottom) else area
            drawCarButtons(c, ra, d, theme)
        }
        prof("buttons", tp0); tp0 = System.nanoTime()
        bannerBottom = area.top.toFloat(); bannerRight = area.left.toFloat()
        if (hud && !NavEngine.planningOn(car) && NavEngine.offer == null && !carTrip) {
            // in the car: smaller, in the very top-left corner
            if (car) drawBanner(c, Rect(area.left, 0, area.right, area.bottom), d * 0.8f, theme, pad = 8 * d)
            else drawBanner(c, area, d, theme)
        }
        prof("banner", tp0); tp0 = System.nanoTime()
        convoyStrip.setEmpty(); carConvoyBottom = 0f; carConvoyRect.setEmpty(); etaAt.setEmpty()
        // the convoy strip/box only on the convoy's own route (a route of my own looks like any other)
        if (hud && Convoy.active && Convoy.onConvoyRoute && !NavEngine.arrived && !NavEngine.planningOn(car)) {
            if (car) drawCarConvoy(c, area, d, theme) else drawConvoyStrip(c, area, d, theme)
        }
        messageAt.setEmpty(); if (hud && Convoy.active) drawConvoyMessage(c, area, d, theme)
        prof("convoy", tp0); tp0 = System.nanoTime()
        if (car && hud && carButtons && NavEngine.planningOn(car)) drawCarRoutes(c, fullArea, d, theme)
        if (carTrip) drawCarTrip(c, area, d, theme)
        else if (car && carButtons && selectedPlace != null && !NavEngine.planningOn(car)) drawCarPlace(c, area, d, theme)
        drawJamChip(c, area, d, theme, hud)
        prof("chips", tp0); tp0 = System.nanoTime()
        if (hud && NavEngine.estimating) drawNoGps(c, area, d, theme)
        else if (hud && NavEngine.noSignal && NavEngine.active && !NavEngine.arrived) drawNoGps(c, area, d, theme, NavEngine.noSignalText())
        if (hud && !NavEngine.arrived) NavEngine.tollAhead?.let { (t, dist) -> drawTollChip(c, area, d, theme, t.price, dist) }
        if (car && hud && carButtons) Convoy.proposal?.let { drawCarProposal(c, area, d, theme, it) }
        val carLobby = carLobbyShown
        if (hud && Convoy.counting) { drawCountdown(c, area, d, theme); animating = true }
        else if (carLobby) drawCarLobby(c, area, d, theme)
        else if (hud && Convoy.active && Convoy.meReady && !NavEngine.active) drawLobbyChip(c, area, d, theme)
        if (car && hud && carButtons && carFriends != null && !NavEngine.planningOn(car) && !carTrip) drawCarFriends(c, area, d, theme)
        if (car && hud && carButtons && carFriends == null) carPanel?.let { drawCarPanel(c, area, d, theme, it) }
        if (car && hud) carFlash?.let { (msg, at) -> if (System.currentTimeMillis() - at < 2200) { drawCarFlash(c, area, d, theme, msg); animating = true } else carFlash = null }
        if (car && hud && carButtons) InvitePop.current?.let { drawCarInvite(c, area, d, theme, it); animating = true } ?: inviteAt.setEmpty()
        if (car && hud && LocationStatus.state != LocationStatus.State.OK && InvitePop.current == null) drawCarLocationOff(c, area, d, theme)
        drawDiscovery(c, area, d, theme)
        prof("panels", tp0); tp0 = System.nanoTime()
        if (hud && (view.userBearing != null || !following)) drawCompass(c, area, bearing, d, theme)
        drawAttribution(c, area, d, theme)
        prof("hud", tp0)
        if (car) { hitsShown = ArrayList(carHits); lastDensity = d }
    }

    fun toScreen(p: LatLng, cam: Camera): Pair<Float, Float> {
        val wx = (Geo.x(p.lon, cam.world) - cam.px) * cam.scale
        val wy = (Geo.y(p.lat, cam.world) - cam.py) * cam.scale
        val a = Math.toRadians(-cam.bearing.toDouble())
        return (cam.cx + wx * cos(a) - wy * sin(a)).toFloat() to (cam.cy + wx * sin(a) + wy * cos(a)).toFloat()
    }

    /** The map point under a screen pixel. */
    fun toMap(sx: Float, sy: Float, cam: Camera): LatLng {
        val dx = (sx - cam.cx).toDouble(); val dy = (sy - cam.cy).toDouble()
        val a = Math.toRadians(cam.bearing.toDouble())
        val wx = (dx * cos(a) - dy * sin(a)) / cam.scale + cam.px
        val wy = (dx * sin(a) + dy * cos(a)) / cam.scale + cam.py
        return LatLng(Geo.lat(wy, cam.world), Geo.lon(wx, cam.world))
    }

    // ---------------------------------------------------------------- tiles

    /** Map squares still being drawn in the last frame (0: the whole map is in; for tests). */
    @Volatile var squaresMissing = -1
        internal set
    @Volatile var squaresShown = 0
        internal set

    /** While a tile loads, show a cached tile from a nearby zoom level instead of a gap. */
    internal var aheadAt = 0L

    // ---------------------------------------------------------------- traffic

    /** A traffic stretch that gets direction arrows: points in world pixels around the square's corner. */
    internal class ArrowLine(val pts: FloatArray, val bothWays: Boolean, val minX: Float, val minY: Float, val maxX: Float, val maxY: Float)

    /** Traffic lines for one data square at one zoom, in world pixels around the square's corner. */
    internal class TrafficPaths(val byState: Array<Path>, val arrows: List<ArrowLine>, val ox: Double, val oy: Double)
    internal val arrowShape = Path()
    internal val trafficCache = LinkedHashMap<String, TrafficPaths>()
    internal val trafficPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    internal val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    internal val trafficBuilding = java.util.Collections.synchronizedSet(HashSet<String>())

    // ---------------------------------------------------------------- route

    companion object {
        /** Traffic lanes are laid onto the roads here, one square at a time, off the drawing thread. */
        internal val trafficWorker = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "traffic-lanes").apply { priority = Thread.MIN_PRIORITY; isDaemon = true } }
        internal val NAV_PLACES = setOf(PoiLayer.Kind.FUEL, PoiLayer.Kind.PARKING)
        /** Tests switch this on to see where frame time goes. */
        @JvmStatic var profile = false
        /** Tests: bytes allocated so far on this thread, to find per-frame garbage (null on phones). */
        @JvmStatic var allocProbe: (() -> Long)? = null
        @JvmStatic val allocs = HashMap<String, Long>()
        private var allocMark = 0L
        val times = HashMap<String, Long>()
    }

    /** A zoom gliding or fingers on the map: frames at full rate. */
    var interacting = false
        internal set
    internal var wasInteracting = false
    internal var snapSeen = -1
    internal var snapEpoch = 0
    internal var snapEpochAt = 0L

    internal class CachedPath(val path: Path, val ox: Double, val oy: Double)
    internal val pathCache = HashMap<List<LatLng>, CachedPath>()
    internal var pathCacheZ = -1

    // The part of the route still ahead: rebuilt when we pass a route point, plus a short
    // piece from the arrow to that point drawn fresh each frame, so the line ends at the arrow.
    internal var aheadRoute: List<LatLng>? = null
    internal var aheadIndex = -1
    internal var aheadZ = -1
    internal var aheadPath: CachedPath? = null
    internal val headPath = Path()

    /** The map's turn this frame (flags on the map stand upright against it). */
    internal var currentBearing = 0f

    // ---------------------------------------------------------------- player & frames

    /** Where the arrow was drawn last frame (tests). */
    internal var lastPlayerAt: android.graphics.PointF? = null

    // ---------------------------------------------------------------- overlays

    internal var washTex: Bitmap? = null
    internal var washDark = false

    /** Watercolour paper: fine fibres and a soft pale edge, in the light or the ink-wash night. */
    /** Full-screen style shaders, made once per screen size (they were remade every frame). */
    internal val overlayShaders = HashMap<String, Shader>()

    /** Rain streaks: fixed positions (0..1), drifting slowly down with time. */
    internal val rainDrops by lazy {
        val r = java.util.Random(11)
        FloatArray(80 * 3) { i -> if (i % 3 == 2) 0.5f + r.nextFloat() else r.nextFloat() }
    }

    internal val snowFlakes by lazy {
        val r = java.util.Random(11)
        FloatArray(70 * 4) { i -> when (i % 4) { 0, 1 -> r.nextFloat(); 2 -> 0.6f + r.nextFloat() * 0.8f; else -> r.nextFloat() * 6.28f } }
    }

    // ---------------------------------------------------------------- HUD

    /** The speed box and limit sign last drawn, and its colour when over the limit (0 = not over): for tests. */
    internal val speedRect = RectF()
    @Volatile internal var speedColor = 0
    internal val ORANGE_OVER = Color.rgb(255, 159, 28)
    internal val RED_OVER = Color.rgb(255, 77, 94)

    // ------------------------------------------------------------ car buttons drawn on the map

    /** Draw GameRoute's own buttons on the car screen (hosts that report taps). */
    var carButtons = false
    internal var tripSeen: Any? = null
    internal var tripSeenAt = 0L
    /** Where each car button is on screen, for the car's tap callback. */
    internal val carHits = ArrayList<Pair<RectF, String>>()
    /** The buttons of the last finished frame (taps are read on another thread than drawing). */
    @Volatile internal var hitsShown: List<Pair<RectF, String>> = emptyList()
        internal set

    /** The car's stable area top: Android Auto's own ☰ lives above it. 0 = not known. */
    @Volatile var stableTop = 0
    internal var etaRight = 0f
    internal val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }

    @Volatile internal var lastDensity = 1f

    // ------------------------------------------------------------------ convoy

    /** Car: someone in the convoy chose a destination: Go (accept) or ✕ (decline). */
    /** Car: the open panel (menu, nearby, quick messages, report, leave…), or null. */
    @Volatile var carPanel: CarPanel? = null
    /** Which page of a long list is showing. */
    @Volatile var carPanelPage = 0
    /** A short note in the style ("Sent", "Reported"), for ~2 s. */
    @Volatile var carFlash: Pair<String, Long>? = null

    /** Car: a convoy invite, centred at the top for ~10 s: who, where, big Not now and Join. */
    /** End and the arrival box (last frame), so the street name under the arrow keeps clear of them. */
    internal val endAt = RectF()
    internal val etaAt = RectF()

    /** Where the invite card is (last frame), so the compass keeps clear of it. */
    internal val inviteAt = RectF()

    /**
     * Friends drawn on the car map in the style (instead of Android Auto's plain list): null closed,
     * "list" your friends, "convoy" the convoy you're in. ‹ goes back one step.
     */
    @Volatile var carFriends: String? = null

    /** Tunnel mode: a small chip saying the position is estimated. */
    @Volatile internal var roadName = ""
    internal var roadAt = 0L

    /** The road you're on, small at the bottom of the map (hidden on unnamed roads). */
    /** Where the road name was drawn last (for tests: it must never cover the speed). */
    internal val roadNameRect = RectF()

    /** Where the phone's convoy strip is (taps open the list). */
    val convoyStrip = RectF()
    internal var carConvoyBottom = 0f
    internal val carConvoyRect = RectF()

    /** Where the arrow sits (a share of the height), easing to a new place instead of jumping. */
    internal var shownArrowFrac = -1f
    internal var carLobbyShown = false

    /** The others in the convoy: an arrow in their colour, name and speed. */
    internal var friendGliding = false

    /** Where the convoy's quick message sits (tests). */
    internal val messageAt = RectF()

    @Volatile internal var friendHits: List<Pair<RectF, Convoy.Member>> = emptyList()

    /** Bottom of the turn banner on screen (for placing things below it). */
    internal var bannerBottom = 0f
    /** The right edge of the turn box (or the convoy lobby…) this frame. */
    internal var bannerRight = 0f
    val bannerBottomNow: Float get() = bannerBottom

    internal data class EllKey(val s: String, val w: Int, val size: Float, val face: android.graphics.Typeface?)
    internal val ellMemo = HashMap<EllKey, String>()
    internal var tripText = ""; private var tripTextM = -1L

    /** "Jam in 800 m · +2 min", under the banner (phone) or at the top of the map (car). */
    /** Where the warning chip was drawn last (tests). */
    internal val alertChipAt = RectF()

    /** Where the compass button sits (the phone taps it to reset rotation). */
    val compassBounds = RectF()

}
