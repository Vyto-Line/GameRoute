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
class MapRenderer(private val car: Boolean = false) {
    internal val pois = PoiLayer()
    /** The place whose card is open (gets a ring on the map). */
    @Volatile var selectedPlace: Place? = null
    /** The tappable place icon under a screen point, if any. */
    fun placeAt(x: Float, y: Float): PoiLayer.Poi? = pois.at(x, y)

    /** This screen's own view (the car never moves the phone's map, nor the other way round). */
    private val view get() = if (car) MapState.car else MapState.phone


    /** The camera used for the last frame. */
    class Camera(
        val px: Double, val py: Double, val world: Double, val scale: Float,
        val bearing: Float, val cx: Float, val cy: Float,
    )

    var camera: Camera? = null
        private set

    /** True if the last frame was mid-animation and another frame should follow. */
    var animating = false
    /** Rain or snow is falling (keeps a slow redraw going even when nothing else moves). */
    var effectsMoving = false
        private set

    private val tilePaint = Paint().apply { isFilterBitmap = true }
    private val overlayPaint = Paint().apply { isFilterBitmap = true }
    private val pixelPaint = Paint().apply { isFilterBitmap = false; isDither = false }
    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val src = Rect()
    private val dst = RectF()
    private val path = Path()
    private var paperTex: Bitmap? = null
    private val labels = Labels()
    private val tileList = ArrayList<IntArray>(64)
    private val tilePool = ArrayList<IntArray>(64)
    private val quarter = RectF()

    /** Where Athens is, until the first GPS fix arrives. */
    private val fallback = LatLng(37.9838, 23.7275)

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
        private set
    private var tileZ = -1
    var forceLite: Boolean? = null
    private var frameAvg = 0.0
    private var frames = 0

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

    private fun drawFrame(c: Canvas, w: Int, h: Int, fullArea: Rect, d: Float, hud: Boolean) {
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

        if (!locOff) drawSpeed(c, area, d, theme) else speedRect.setEmpty() // no live speed without location
        if (car && hud) drawCarEta(c, area, d, theme)
        if (hud && NavEngine.showRoadName && (NavEngine.active || LocationTracker.speedMps > 2f) && !NavEngine.planningOn(car) && !NavEngine.arrived)
            playerAt?.let { drawCurrentRoad(c, area, d, theme, it.x, it.y, weakNow) } // (map moved away from you: no name)
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
        bannerBottom = area.top.toFloat(); bannerRight = area.left.toFloat()
        if (hud && !NavEngine.planningOn(car) && NavEngine.offer == null && !carTrip) {
            // in the car: smaller, in the very top-left corner
            if (car) drawBanner(c, Rect(area.left, 0, area.right, area.bottom), d * 0.8f, theme, pad = 8 * d)
            else drawBanner(c, area, d, theme)
        }
        convoyStrip.setEmpty(); carConvoyBottom = 0f; carConvoyRect.setEmpty()
        // the convoy strip/box only on the convoy's own route (a route of my own looks like any other)
        if (hud && Convoy.active && Convoy.onConvoyRoute && !NavEngine.arrived && !NavEngine.planningOn(car)) {
            if (car) drawCarConvoy(c, area, d, theme) else drawConvoyStrip(c, area, d, theme)
        }
        if (hud && Convoy.active) drawConvoyMessage(c, area, d, theme)
        if (car && hud && carButtons && NavEngine.planningOn(car)) drawCarRoutes(c, fullArea, d, theme)
        if (carTrip) drawCarTrip(c, area, d, theme)
        else if (car && carButtons && selectedPlace != null && !NavEngine.planningOn(car)) drawCarPlace(c, area, d, theme)
        drawJamChip(c, area, d, theme, hud)
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
        if (car && hud && carButtons) InvitePop.current?.let { drawCarInvite(c, area, d, theme, it); animating = true }
        if (car && hud && LocationStatus.state != LocationStatus.State.OK && InvitePop.current == null) drawCarLocationOff(c, area, d, theme)
        drawDiscovery(c, area, d, theme)
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

    private fun drawTiles(
        c: Canvas, theme: Theme, z: Int, px: Double, py: Double, reach: Double,
        scale: Float, bearing: Float, cx: Float, cy: Float, w: Int, h: Int,
    ) {
        // only tiles that touch the (rotated) screen: about half as many as the square around it
        val ang = Math.toRadians(-bearing.toDouble())
        val ca = cos(ang); val sa = sin(ang)
        val half = TileCache.tilePx * scale * 0.7072
        fun onScreen(tx: Int, ty: Int): Boolean {
            val wx = ((tx + 0.5) * TileCache.tilePx - px) * scale
            val wy = ((ty + 0.5) * TileCache.tilePx - py) * scale
            val sx = wx * ca - wy * sa; val sy = wx * sa + wy * ca
            return sx > -cx - half && sx < w - cx + half && sy > -cy - half && sy < h - cy + half
        }
        val tilePx = TileCache.tilePx
        val n = 1 shl z
        val tx0 = floor((px - reach) / tilePx).toInt(); val tx1 = floor((px + reach) / tilePx).toInt()
        val ty0 = floor((py - reach) / tilePx).toInt().coerceAtLeast(0)
        val ty1 = floor((py + reach) / tilePx).toInt().coerceAtMost(n - 1)
        val paint = if (theme.pixel > 1 || lite) pixelPaint else tilePaint
        val showTraffic = Traffic.active && Traffic.showLayer && z >= 11 && !NavEngine.planningOn(car)

        // Ask for the visible tiles far-to-near: the loader works newest-first, so the
        // tiles nearest the player are drawn first.
        val tiles = tileList
        tiles.clear()
        var used = 0
        for (ty in ty0..ty1) for (tx in tx0..tx1) if (onScreen(tx, ty)) {
            // reuse the little arrays: no garbage every frame
            if (used == tilePool.size) tilePool += IntArray(2)
            val a = tilePool[used++]; a[0] = tx; a[1] = ty
            tiles += a
        }
        val ctx = px / tilePx - 0.5; val cty = py / tilePx - 0.5
        tiles.sortByDescending { (it[0] - ctx) * (it[0] - ctx) + (it[1] - cty) * (it[1] - cty) }

        // quietly download the map data just outside the screen
        if (theme != Theme.BLOCKY && VectorSource.busy.get() == 0) {
            val dz = VectorSource.dataZoom(z); val sh = z - dz
            val nd = 1 shl dz
            for (ty in ((ty0 shr sh) - 1)..((ty1 shr sh) + 1)) for (tx in ((tx0 shr sh) - 1)..((tx1 shr sh) + 1)) {
                if (ty in 0 until nd) VectorSource.prefetch(dz, tx.mod(nd), ty)
            }
        }
        // Driving: draw the tiles the route reaches next before they come on screen, so they're
        // ready instead of popping in. Asked for first: the loader takes newest requests first,
        // so what's on screen still wins.
        if (!interacting && NavEngine.active && view.following) prefetchAhead(theme, z, tiles)
        var missing = 0
        for (t in tiles) {
            val tx = t[0]; val ty = t[1]
            val x = tx.mod(n)
            val left = (tx * tilePx - px).toFloat(); val top = (ty * tilePx - py).toFloat()
            dst.set(left, top, left + tilePx, top + tilePx)
            val bmp = if (interacting) TileCache.peek(theme, z, x, ty) else TileCache.get(theme, z, x, ty)
            if (bmp != null) c.drawBitmap(bmp, null, dst, paint)
            else { drawStandIn(c, theme, z, x, ty, paint); missing++ }
            if (showTraffic) TrafficOverlay.get(theme, z, x, ty, !interacting)?.let { c.drawBitmap(it, null, dst, overlayPaint) }
        }
        squaresMissing = missing; squaresShown = tiles.size
    }

    /** Map squares still being drawn in the last frame (0: the whole map is in; for tests). */
    @Volatile var squaresMissing = -1
        private set
    @Volatile var squaresShown = 0
        private set

    /** While a tile loads, show a cached tile from a nearby zoom level instead of a gap. */
    private var aheadAt = 0L

    private fun prefetchAhead(theme: Theme, z: Int, onScreen: List<IntArray>) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - aheadAt < 1500) return
        aheadAt = now
        val r = NavEngine.current ?: return
        val pts = r.points
        if (pts.size < 2) return
        val from = NavEngine.passedIndex.coerceIn(0, pts.size - 1)
        val startM = r.cum[from]
        val world = TileCache.tilePx * 2.0.pow(z)
        val n = 1 shl z
        var asked = 0
        var last = -1L
        var i = from
        // about two minutes of driving ahead (1.5 to 5 km), asked for only while the workers are idle,
        // so the squares on screen always come first and the map never shows blanks at speed
        val ahead = (LocationTracker.speedMps * 120.0).coerceIn(1500.0, 5000.0)
        while (i < pts.size && r.cum[i] - startM < ahead && asked < 6) {
            val tx = floor(Geo.x(pts[i].lon, world) / TileCache.tilePx).toInt()
            val ty = floor(Geo.y(pts[i].lat, world) / TileCache.tilePx).toInt()
            val k = (tx.toLong() shl 32) or (ty.toLong() and 0xffffffffL)
            if (k != last && ty in 0 until n && onScreen.none { it[0] == tx && it[1] == ty }) {
                last = k
                if (TileCache.peek(theme, z, tx.mod(n), ty) == null) { if (!TileCache.prefetch(theme, z, tx.mod(n), ty)) break; asked++ }
            }
            i++
        }
    }

    private fun drawStandIn(c: Canvas, theme: Theme, z: Int, x: Int, y: Int, paint: Paint) {
        // Coarser levels: zoom into part of a parent tile.
        for (up in 1..5) {
            val pz = z - up
            if (pz < 1) break
            val parent = TileCache.peek(theme, pz, x shr up, y shr up) ?: continue
            val parts = 1 shl up
            val sw = parent.width / parts; val sh = parent.height / parts
            if (sw < 1 || sh < 1) break
            val sx = (x and (parts - 1)) * sw; val sy = (y and (parts - 1)) * sh
            src.set(sx, sy, sx + sw, sy + sh)
            c.drawBitmap(parent, src, dst, paint)
            return
        }
        // Finer level: four children, if we have any (zooming out).
        if (z < 19) {
            val hw = dst.width() / 2; val hh = dst.height() / 2
            val l = dst.left; val t = dst.top
            for (dy in 0..1) for (dx in 0..1) {
                val child = TileCache.peek(theme, z + 1, x * 2 + dx, y * 2 + dy) ?: continue
                quarter.set(l + dx * hw, t + dy * hh, l + (dx + 1) * hw, t + (dy + 1) * hh)
                c.drawBitmap(child, null, quarter, paint)
            }
        }
    }

    // ---------------------------------------------------------------- traffic

    /** A traffic stretch that gets direction arrows: points in world pixels around the square's corner. */
    internal class ArrowLine(val pts: FloatArray, val bothWays: Boolean, val minX: Float, val minY: Float, val maxX: Float, val maxY: Float)

    /** Traffic lines for one data square at one zoom, in world pixels around the square's corner. */
    internal class TrafficPaths(val byState: Array<Path>, val arrows: List<ArrowLine>, val ox: Double, val oy: Double)
    private val arrowShape = Path()
    private val trafficCache = LinkedHashMap<String, TrafficPaths>()
    private val trafficPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private fun drawTraffic(
        c: Canvas, theme: Theme, z: Int, px: Double, py: Double, scale: Float, d: Float, reach: Double, under: Boolean,
    ) {
        if (under || !Traffic.active || !Traffic.showLayer || z < 11) return
        val look = theme.traffic
        val st = theme.style
        // Coarser traffic data zoomed out (few squares); in the city, the data at the map's own zoom:
        // a zoom coarser, the jam lines were simplified so much they cut across the buildings
        val dz = when {
            z >= 16 -> 16
            z == 15 -> 15
            else -> (z - 1).coerceIn(Traffic.MIN_Z, Traffic.DATA_Z)
        }
        val span = TileCache.tilePx * 2.0.pow(z - dz) // one traffic square in world pixels
        val ax0 = floor((px - reach) / span).toInt(); val ax1 = floor((px + reach) / span).toInt()
        val ay0 = floor((py - reach) / span).toInt(); val ay1 = floor((py + reach) / span).toInt()
        if ((ax1 - ax0 + 1) * (ay1 - ay0 + 1) > 36) return
        trafficPaint.isAntiAlias = look.smooth
        arrowPaint.isAntiAlias = look.smooth
        val u = d / scale // one dp in world pixels
        val busy = interacting
        val nowMs = android.os.SystemClock.uptimeMillis()
        if (VectorSource.loadedCount != snapSeen && nowMs - snapEpochAt > 4000) {
            snapSeen = VectorSource.loadedCount; snapEpoch++; snapEpochAt = nowMs
        }

        for (ay in ay0..ay1) for (ax in ax0..ax1) {
            val t = Traffic.tile(dz, ax, ay) ?: continue
            // rebuilt when new map data arrives too, so lanes can lock onto the roads
            val key = "$dz/$ax/$ay/$z/${t.fetchedAt}/$snapEpoch"
            // laid onto the roads in the background (a few ms to a few tens per square): the
            // frame never waits for it; until it's ready this square's traffic waits a moment
            val tp = synchronized(trafficCache) { trafficCache[key] }
                ?: run { if (!busy) buildTrafficLater(key, t, ax, ay, span, u, z); null }
                ?: continue
            c.save()
            c.translate((tp.ox - px).toFloat(), (tp.oy - py).toFloat())

            // one thin, slightly see-through line per slow / jammed / closed stretch (cheap to draw)
            if (!under) for (state in intArrayOf(Traffic.SLOW, Traffic.JAM, Traffic.CLOSED)) {
                val p = tp.byState[state]
                if (p.isEmpty) continue
                trafficPaint.pathEffect = if (state == Traffic.CLOSED) DashPathEffect(floatArrayOf(6 * u, 4 * u), 0f) else null
                trafficPaint.color = if (state == Traffic.SLOW) st.trafficSlow else st.trafficJam
                trafficPaint.alpha = 175
                trafficPaint.strokeWidth = maxOf(2.2f, look.core * 0.65f) * u
                c.drawPath(p, trafficPaint)
            }
            trafficPaint.pathEffect = null
            c.restore()
        }
        synchronized(trafficCache) { while (trafficCache.size > 48) trafficCache.remove(trafficCache.keys.first()) }
    }

    private val trafficBuilding = java.util.Collections.synchronizedSet(HashSet<String>())

    private fun buildTrafficLater(key: String, t: Traffic.Tile, ax: Int, ay: Int, span: Double, u: Float, z: Int) {
        if (!trafficBuilding.add(key)) return
        trafficWorker.execute {
            try {
                val tp = buildTraffic(t, ax, ay, span, u, z)
                synchronized(trafficCache) { trafficCache[key] = tp }
                TileCache.loaded.fire() // draw it
            } catch (_: Throwable) {
            } finally { trafficBuilding.remove(key) }
        }
    }

    internal fun buildTraffic(t: Traffic.Tile, ax: Int, ay: Int, span: Double, u: Float, z: Int): TrafficPaths {
        val paths = Array(4) { Path() }
        val arrows = ArrayList<ArrowLine>()
        val k = (span / t.extent).toFloat()
        // Each driving direction gets its own lane on its side of the road, ~5 m from the
        // middle (about where the carriageways of a divided road are), never closer than 2.6 dp.
        val world = TileCache.tilePx * 2.0.pow(z)
        val lat = Geo.lat((ay + 0.5) * span, world) // centre of this square
        val mpp = Geo.metersPerPixel(lat, world).toFloat()
        val lane = maxOf(2.6f * u, 5f / mpp)

        fun add(pts: FloatArray, state: Int) {
            val n = pts.size / 2
            val p = paths[state]
            p.moveTo(pts[0], pts[1])
            for (i in 1 until n) p.lineTo(pts[i * 2], pts[i * 2 + 1])
            if (false) { // traffic arrows removed (performance)
                var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
                for (i in 0 until n) {
                    minX = minOf(minX, pts[i * 2]); maxX = maxOf(maxX, pts[i * 2])
                    minY = minOf(minY, pts[i * 2 + 1]); maxY = maxOf(maxY, pts[i * 2 + 1])
                }
                arrows += ArrowLine(pts, false, minX, minY, maxX, maxY)
            }
        }

        // Lanes go on the map's own roads, walked every ~3 m so they follow each bend
        // (TomTom's own lines are simplified and cut across the blocks). No map data yet:
        // nothing drawn (it comes a moment later) rather than lines through the buildings.
        val maxSnap = maxOf(20f / mpp, 10f * u)
        val roads = RoadSnap.index(z, ax * span, ay * span, span, maxSnap)
            ?: return TrafficPaths(paths, arrows, ax * span, ay * span)
        val step = maxOf(3f / mpp, 1.5f * u)
        fun lane(raw: FloatArray, side: Float) =
            RoadSnap.snapLanePieces(roads, raw, maxSnap, side, 1.8f * u, step)
        for (sg in t.segs) {
            val base = FloatArray(sg.pts.size)
            for (i in base.indices) base[i] = sg.pts[i] * k
            val side = if (sg.leftHand) -1f else 1f
            // one lane per direction the data covers, each on its own half of the road
            // (a lane against a one-way street is dropped)
            lane(base, side)?.forEach { add(it, sg.state) }
            if (!sg.oneWay) lane(reversedLine(base), side)?.forEach { add(it, sg.state) }
        }
        return TrafficPaths(paths, arrows, ax * span, ay * span)
    }

    private fun reversedLine(p: FloatArray): FloatArray {
        val out = FloatArray(p.size)
        var i = 0
        while (i < p.size) { out[p.size - 2 - i] = p[i]; out[p.size - 1 - i] = p[i + 1]; i += 2 }
        return out
    }

    /** Shifts a line sideways by [d] (positive = right of its direction, screen coordinates). */
    private fun offsetLine(p: FloatArray, d: Float) {
        val n = p.size / 2
        if (n < 2) return
        val src = p.copyOf()
        for (i in 0 until n) {
            // average of the normals of the segments before and after this point
            var nx = 0f; var ny = 0f
            if (i > 0) { val dx = src[i * 2] - src[i * 2 - 2]; val dy = src[i * 2 + 1] - src[i * 2 - 1]; val l = hypot(dx, dy); if (l > 0) { nx += -dy / l; ny += dx / l } }
            if (i < n - 1) { val dx = src[i * 2 + 2] - src[i * 2]; val dy = src[i * 2 + 3] - src[i * 2 + 1]; val l = hypot(dx, dy); if (l > 0) { nx += -dy / l; ny += dx / l } }
            val l = hypot(nx, ny)
            if (l > 0) { p[i * 2] = src[i * 2] + nx / l * d; p[i * 2 + 1] = src[i * 2 + 1] + ny / l * d }
        }
    }

    /**
     * Arrows every ~40 dp along jammed (red) stretches, always moving: forward along
     * one-direction jams, and in both directions (alternating) on two-way jams.
     */
    private fun drawArrows(c: Canvas, tp: TrafficPaths, look: TrafficLook, u: Float, vx: Double, vy: Double, reach: Double): Boolean {
        val spacing = (if (look.smooth) 40f else 44f) * u
        val size = (look.core + 2.5f) * u
        arrowShape.reset()
        arrowShape.moveTo(-size * 0.6f, -size); arrowShape.lineTo(size * 0.7f, 0f); arrowShape.lineTo(-size * 0.6f, size)
        arrowShape.lineTo(-size * 0.15f, 0f); arrowShape.close()
        arrowPaint.color = look.arrow
        arrowPaint.pathEffect = null
        // arrows flow along the jam at ~22 dp per second
        val crawl = ((android.os.SystemClock.uptimeMillis() / 1000.0 * 22 * u) % spacing).toFloat()
        val l = (vx - reach).toFloat(); val r = (vx + reach).toFloat(); val t = (vy - reach).toFloat(); val b = (vy + reach).toFloat()
        var drawn = 0
        for (al in tp.arrows) {
            if (al.maxX < l || al.minX > r || al.maxY < t || al.minY > b) continue
            val pts = al.pts
            var total = 0f
            var i = 2
            while (i < pts.size) { total += hypot(pts[i] - pts[i - 2], pts[i + 1] - pts[i - 1]); i += 2 }
            if (total < size * 2.5f) continue
            if (al.bothWays) {
                // two-way jam: arrows alternate, each set flowing its own way
                drawn += arrowRun(c, pts, total, crawl, spacing, false)
                // backward set: counted from the far end, so it moves the way it points
                drawn += arrowRun(c, pts, total, (crawl + spacing / 2) % spacing, spacing, true)
            } else {
                drawn += arrowRun(c, pts, total, crawl, spacing, false)
            }
            if (drawn > 500) return true
        }
        return drawn > 0
    }

    /** Arrows along [pts] starting at [start], every [spacing]; [backwards] points them the other way. */
    private fun arrowRun(c: Canvas, pts: FloatArray, total: Float, start: Float, spacing: Float, backwards: Boolean): Int {
        var at = if (backwards) total - start else start
        var n = 0
        // walk the line once, placing arrows as we pass their distance
        val positions = ArrayList<Float>()
        while (at >= 0 && at <= total) { positions += at; at += if (backwards) -spacing else spacing }
        if (positions.isEmpty()) return 0
        positions.sort()
        var walked = 0f
        var pi = 0
        var i = 2
        while (i < pts.size && pi < positions.size) {
            val x0 = pts[i - 2]; val y0 = pts[i - 1]; val x1 = pts[i]; val y1 = pts[i + 1]
            val len = hypot(x1 - x0, y1 - y0)
            while (pi < positions.size && positions[pi] <= walked + len && len > 0) {
                val f = (positions[pi] - walked) / len
                var ang = Math.toDegrees(kotlin.math.atan2((y1 - y0).toDouble(), (x1 - x0).toDouble())).toFloat()
                if (backwards) ang += 180f
                c.save()
                c.translate(x0 + (x1 - x0) * f, y0 + (y1 - y0) * f)
                c.rotate(ang)
                c.drawPath(arrowShape, arrowPaint)
                c.restore()
                n++; pi++
            }
            walked += len
            i += 2
        }
        return n
    }

    // ---------------------------------------------------------------- route

    private fun prof(k: String, t0: Long) {
        if (profile) times[k] = (times[k] ?: 0L) + (System.nanoTime() - t0)
    }

    companion object {
        /** Traffic lanes are laid onto the roads here, one square at a time, off the drawing thread. */
        private val trafficWorker = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "traffic-lanes").apply { priority = Thread.MIN_PRIORITY; isDaemon = true } }
        private val NAV_PLACES = setOf(PoiLayer.Kind.FUEL, PoiLayer.Kind.PARKING)
        /** Tests switch this on to see where frame time goes. */
        @JvmStatic var profile = false
        val times = HashMap<String, Long>()
    }

    /** A zoom gliding or fingers on the map: frames at full rate. */
    var interacting = false
        private set
    private var wasInteracting = false
    private var snapSeen = -1
    private var snapEpoch = 0
    private var snapEpochAt = 0L

    private class CachedPath(val path: Path, val ox: Double, val oy: Double)
    private val pathCache = HashMap<List<LatLng>, CachedPath>()
    private var pathCacheZ = -1

    private fun pathFor(pts: List<LatLng>, world: Double, z: Int): CachedPath {
        if (z != pathCacheZ || pathCache.size > 12) { pathCache.clear(); pathCacheZ = z }
        return pathCache.getOrPut(pts) {
            val ox = Geo.x(pts[0].lon, world); val oy = Geo.y(pts[0].lat, world)
            val p = Path()
            val minStep = 1.5f * (TileCache.tilePx / 256f)
            var lx = 0f; var ly = 0f
            pts.forEachIndexed { i, q ->
                val x = (Geo.x(q.lon, world) - ox).toFloat(); val y = (Geo.y(q.lat, world) - oy).toFloat()
                if (i == 0) { p.moveTo(x, y); lx = x; ly = y }
                else if (i == pts.size - 1 || kotlin.math.abs(x - lx) + kotlin.math.abs(y - ly) > minStep) { p.lineTo(x, y); lx = x; ly = y }
            }
            CachedPath(p, ox, oy)
        }
    }

    private fun strokeRoute(
        c: Canvas, pts: List<LatLng>, world: Double, z: Int, px: Double, py: Double, scale: Float, d: Float,
        color: Int, glow: Int, width: Float, dashed: Boolean, pixel: Boolean,
    ) {
        if (pts.size < 2) return
        strokePath(c, pathFor(pts, world, z), px, py, scale, d, color, glow, width, dashed, pixel)
    }

    private fun strokePath(
        c: Canvas, cp: CachedPath, px: Double, py: Double, scale: Float, d: Float,
        color: Int, glow: Int, width: Float, dashed: Boolean, pixel: Boolean, extra: Path? = null,
    ) {
        c.save()
        c.translate((cp.ox - px).toFloat(), (cp.oy - py).toFloat())
        fun both() { c.drawPath(cp.path, routePaint); if (extra != null) c.drawPath(extra, routePaint) }
        routePaint.pathEffect = null
        routePaint.isAntiAlias = !pixel
        // a contrasting outline keeps the route readable over busy, same-coloured roads
        routePaint.color = if (MapState.dark) Color.argb(200, 0, 0, 0) else Color.argb(220, 255, 255, 255)
        routePaint.strokeWidth = (width + 5) * d / scale
        both()
        routePaint.color = color
        routePaint.strokeWidth = width * d / scale
        if (dashed) routePaint.pathEffect = DashPathEffect(floatArrayOf(14 * d / scale, 10 * d / scale), 0f)
        both()
        routePaint.pathEffect = null
        c.restore()
    }

    // The part of the route still ahead: rebuilt when we pass a route point, plus a short
    // piece from the arrow to that point drawn fresh each frame, so the line ends at the arrow.
    private var aheadRoute: List<LatLng>? = null
    private var aheadIndex = -1
    private var aheadZ = -1
    private var aheadPath: CachedPath? = null
    private val headPath = Path()

    private fun aheadPathFor(route: List<LatLng>, from: Int, world: Double, z: Int): CachedPath {
        val cached = aheadPath
        if (cached != null && route === aheadRoute && from == aheadIndex && z == aheadZ) return cached
        val ox = Geo.x(route[from].lon, world); val oy = Geo.y(route[from].lat, world)
        val p = Path()
        // skip points closer than ~1.5 screen px to the last one kept: same look, far fewer points
        val minStep = 1.5f * (TileCache.tilePx / 256f)
        var lx = 0f; var ly = 0f
        for (i in from until route.size) {
            val x = (Geo.x(route[i].lon, world) - ox).toFloat(); val y = (Geo.y(route[i].lat, world) - oy).toFloat()
            if (i == from) { p.moveTo(x, y); lx = x; ly = y }
            else if (i == route.size - 1 || kotlin.math.abs(x - lx) + kotlin.math.abs(y - ly) > minStep) { p.lineTo(x, y); lx = x; ly = y }
        }
        return CachedPath(p, ox, oy).also { aheadPath = it; aheadRoute = route; aheadIndex = from; aheadZ = z }
    }

    /** The route from the arrow onwards (nothing behind you). */
    private fun drawRouteAhead(
        c: Canvas, route: List<LatLng>, world: Double, z: Int, px: Double, py: Double, scale: Float, d: Float,
        color: Int, glow: Int, width: Float, dashed: Boolean, pixel: Boolean,
    ) {
        val idx = NavEngine.passedIndex.coerceIn(0, route.size - 2)
        val next = idx + 1
        val ahead = aheadPathFor(route, next, world, z)
        // join from where the arrow is (its spot on the route) to the next route point
        val me = LocationTracker.displayPosition()
        val start = me?.let { NavEngine.snap(it)?.first } ?: route[idx]
        headPath.reset()
        val sx = (Geo.x(start.lon, world) - ahead.ox).toFloat(); val sy = (Geo.y(start.lat, world) - ahead.oy).toFloat()
        headPath.moveTo(sx, sy); headPath.lineTo(0f, 0f)
        strokePath(c, ahead, px, py, scale, d, color, glow, width, dashed, pixel, extra = headPath)
    }

    private fun withAlpha(col: Int, a: Int) = Color.argb(a, Color.red(col), Color.green(col), Color.blue(col))

    /** The map's turn this frame (flags on the map stand upright against it). */
    private var currentBearing = 0f

    private fun drawRoute(c: Canvas, theme: Theme, world: Double, px: Double, py: Double, scale: Float, d: Float) {
        val z = kotlin.math.round(kotlin.math.log2(world / TileCache.tilePx)).toInt()
        val pixel = theme.pixel != 1
        // the Garage's route colour, when it's readable on this map (else the style's own)
        val rc = Xp.routeColor(theme.look); val rg = Xp.routeGlow(theme.look)
        if (NavEngine.planningOn(car)) {
            val opts = NavEngine.options
            opts.forEachIndexed { i, o ->
                if (i != NavEngine.selected) strokeRoute(c, o.points, world, z, px, py, scale, d,
                    withAlpha(rc, 120), 0, 6f, false, pixel)
            }
            opts.getOrNull(NavEngine.selected)?.let {
                strokeRoute(c, it.points, world, z, px, py, scale, d, rc, rg, 7f, theme.dashedRoute, pixel)
            }
            NavEngine.planPlace?.let { drawDestination(c, it.pos, world, px, py, scale, d, theme) }
            return
        }
        val route = NavEngine.route
        val offer = NavEngine.offer
        if (route.size > 1) {
            if (offer != null) {
                drawRouteAhead(c, route, world, z, px, py, scale, d, withAlpha(rc, 110), 0, 6f, false, pixel)
                strokeRoute(c, offer.option.points, world, z, px, py, scale, d,
                    Color.rgb(61, 220, 132), Color.argb(90, 61, 220, 132), 8f, false, pixel)
            } else {
                drawRouteAhead(c, route, world, z, px, py, scale, d, rc, rg, 7f, theme.dashedRoute, pixel)
            }
        }
        NavEngine.destination?.let { drawDestination(c, it, world, px, py, scale, d, theme) }
    }

    private fun drawDestination(c: Canvas, dest: LatLng, world: Double, px: Double, py: Double, scale: Float, d: Float, theme: Theme) {
        val x = (Geo.x(dest.lon, world) - px).toFloat(); val y = (Geo.y(dest.lat, world) - py).toFloat()
        val u = d / scale
        fill.color = Xp.routeGlow(theme.look); c.drawCircle(x, y, 18 * u, fill)
        // the Garage's destination flag (standing on the spot, upright whatever the map's turn)
        when (Xp.chosen("flag").id) {
            "flag_checkered" -> {
                c.save(); c.rotate(currentBearing, x, y)
                stroke.color = Color.BLACK; stroke.strokeWidth = 2.5f * u; c.drawLine(x, y, x, y - 30 * u, stroke)
                val l = x + 1 * u; val t = y - 30 * u; val q = 4.5f * u
                for (i in 0 until 4) for (j in 0 until 3) { fill.color = if ((i + j) % 2 == 0) Color.WHITE else Color.BLACK; c.drawRect(l + i * q, t + j * q, l + (i + 1) * q, t + (j + 1) * q, fill) }
                stroke.strokeWidth = 1.2f * u; c.drawRect(l, t, l + 4 * q, t + 3 * q, stroke)
                fill.color = Color.BLACK; c.drawCircle(x, y, 3 * u, fill)
                c.restore()
            }
            "flag_pennant" -> {
                c.save(); c.rotate(currentBearing, x, y)
                stroke.color = Color.BLACK; stroke.strokeWidth = 2.5f * u; c.drawLine(x, y, x, y - 30 * u, stroke)
                path.reset(); path.moveTo(x + 1 * u, y - 30 * u); path.lineTo(x + 20 * u, y - 24 * u); path.lineTo(x + 1 * u, y - 17 * u); path.close()
                fill.color = Xp.routeColor(theme.look); c.drawPath(path, fill)
                stroke.strokeWidth = 1.5f * u; c.drawPath(path, stroke)
                fill.color = Color.BLACK; c.drawCircle(x, y, 3 * u, fill)
                c.restore()
            }
            "flag_trophy" -> {
                c.save(); c.rotate(currentBearing, x, y)
                val gold = Color.rgb(255, 196, 40)
                fill.color = gold; stroke.color = Color.rgb(90, 60, 0); stroke.strokeWidth = 1.6f * u
                path.reset(); path.moveTo(x - 9 * u, y - 26 * u); path.lineTo(x + 9 * u, y - 26 * u); path.lineTo(x + 6 * u, y - 14 * u)
                path.quadTo(x, y - 9 * u, x - 6 * u, y - 14 * u); path.close()
                c.drawPath(path, fill); c.drawPath(path, stroke)
                c.drawRect(x - 2 * u, y - 11 * u, x + 2 * u, y - 5 * u, fill)
                c.drawRect(x - 7 * u, y - 5 * u, x + 7 * u, y - 2 * u, fill); c.drawRect(x - 7 * u, y - 5 * u, x + 7 * u, y - 2 * u, stroke)
                c.restore()
            }
            else -> {
                fill.color = Color.rgb(255, 214, 0); c.drawCircle(x, y, 9 * u, fill)
                stroke.color = Color.BLACK; stroke.strokeWidth = 2 * u; c.drawCircle(x, y, 9 * u, stroke)
            }
        }
    }

    /** Works out the centre and zoom that fit [pts] into [area], and hands them to MapState. */
    private fun fit(pts: List<LatLng>, area: Rect, d: Float, theme: Theme) {
        if (area.width() <= 0 || area.height() <= 0) return
        var minX = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE
        var minY = Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
        for (p in pts) {
            val x = Geo.x(p.lon, 1.0); val y = Geo.y(p.lat, 1.0)
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (y < minY) minY = y; if (y > maxY) maxY = y
        }
        val pad = 48 * d
        val k = 256.0 * d * 1.15
        val dx = maxOf(maxX - minX, 1e-7); val dy = maxOf(maxY - minY, 1e-7)
        val zx = kotlin.math.log2((area.width() - 2 * pad) / (dx * k))
        val zy = kotlin.math.log2((area.height() - 2 * pad - 40 * d) / (dy * k))
        val zoom = minOf(zx, zy).coerceIn(MapState.MIN_ZOOM, 17.5)
        // the camera puts its centre at 55% of the height; shift so the routes sit in the middle
        val camY = if (theme.radar) 0.5 else 0.55
        val shift = (camY - 0.5) * area.height() / (k * 2.0.pow(zoom))
        val cx = (minX + maxX) / 2; val cy = (minY + maxY) / 2 - shift
        view.applyFit(LatLng(Geo.lat(cy, 1.0), Geo.lon(cx, 1.0)), zoom)
    }

    // ---------------------------------------------------------------- player & frames

    /** Where I was last, when location is off: a faint grey circle with a "?". */
    private fun drawLastKnown(c: Canvas, x: Float, y: Float, d: Float, t: Theme) {
        val r = 15 * d
        fill.color = Color.argb(70, 140, 146, 156); c.drawCircle(x, y, r * 1.9f, fill)
        fill.color = Color.argb(200, 122, 128, 137); c.drawCircle(x, y, r, fill)
        stroke.color = Color.argb(220, 255, 255, 255); stroke.strokeWidth = 2 * d; c.drawCircle(x, y, r, stroke)
        text.textAlign = Paint.Align.CENTER; text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 17 * d; text.color = Color.WHITE
        c.drawText("?", x, y + 6 * d, text)
        text.textAlign = Paint.Align.LEFT; text.typeface = t.typeface
    }

    /** Where the arrow was drawn last frame (tests). */
    internal var lastPlayerAt: android.graphics.PointF? = null

    private fun drawPlayer(c: Canvas, x: Float, y: Float, rotation: Float, d: Float, t: Theme) {
        val s = 15 * d
        c.save()
        c.rotate(rotation, x, y)
        path.reset()
        // the Garage's arrow: always pointing the way you drive, always outlined
        val kind = Xp.chosen("arrow")
        when (kind.id) {
            "arrow_chevron" -> {
                path.moveTo(x, y - s); path.lineTo(x + s * 0.85f, y + s * 0.55f); path.lineTo(x + s * 0.45f, y + s * 0.78f)
                path.lineTo(x, y + s * 0.08f); path.lineTo(x - s * 0.45f, y + s * 0.78f); path.lineTo(x - s * 0.85f, y + s * 0.55f)
            }
            "arrow_dart" -> { path.moveTo(x, y - s * 1.15f); path.lineTo(x + s * 0.5f, y + s * 0.85f); path.lineTo(x, y + s * 0.5f); path.lineTo(x - s * 0.5f, y + s * 0.85f) }
            "arrow_delta" -> { path.moveTo(x, y - s); path.lineTo(x + s * 0.95f, y + s * 0.85f); path.lineTo(x - s * 0.95f, y + s * 0.85f) }
            "arrow_comet" -> { // a shield
                path.moveTo(x, y - s * 1.05f); path.lineTo(x + s * 0.8f, y - s * 0.35f); path.lineTo(x + s * 0.6f, y + s * 0.55f)
                path.lineTo(x, y + s * 0.95f); path.lineTo(x - s * 0.6f, y + s * 0.55f); path.lineTo(x - s * 0.8f, y - s * 0.35f)
            }
            else -> { path.moveTo(x, y - s); path.lineTo(x + s * 0.75f, y + s * 0.8f); path.lineTo(x, y + s * 0.4f); path.lineTo(x - s * 0.75f, y + s * 0.8f) }
        }
        path.close()
        fill.isAntiAlias = t.pixel == 1
        // (no coloured circle behind it either: just the arrow on the map)
        // no disc behind the arrow (it's see-through in every style). An arrow too close to the map's own
        // colours (8-Bit: green on green roads; Neon: pink on pink) gets a thick outline in its outline
        // colour instead, so it never gets lost
        val body = if (kind.color != 0) kind.color else t.player
        val edge = if (kind.color != 0) Color.rgb(40, 28, 0) else t.playerOutline
        val st = t.style
        val faint = minOf(Ui.contrast(body, st.land), Ui.contrast(body, st.major), Ui.contrast(body, st.minor)) < 2.2
        stroke.strokeJoin = Paint.Join.ROUND
        if (faint) { stroke.color = edge; stroke.strokeWidth = 7f * d; c.drawPath(path, stroke) }
        fill.color = body; c.drawPath(path, fill)
        stroke.color = edge; stroke.strokeWidth = 2.5f * d; c.drawPath(path, stroke)
        stroke.strokeJoin = Paint.Join.MITER
        if (kind.id == "arrow_comet") { // the shield's arrow mark
            stroke.color = t.playerOutline; stroke.strokeWidth = 2.2f * d
            c.drawLine(x, y + s * 0.45f, x, y - s * 0.5f, stroke); c.drawLine(x - s * 0.32f, y - s * 0.18f, x, y - s * 0.5f, stroke); c.drawLine(x + s * 0.32f, y - s * 0.18f, x, y - s * 0.5f, stroke)
        }
        if (kind.id == "arrow_star") { // prestige: a small star in the middle
            fill.color = Color.WHITE
            val st = Path(); for (k in 0 until 10) { val a = Math.toRadians(-90.0 + k * 36); val rr = if (k % 2 == 0) s * 0.32f else s * 0.14f
                val px2 = x + (rr * cos(a)).toFloat(); val py2 = y + s * 0.1f + (rr * sin(a)).toFloat(); if (k == 0) st.moveTo(px2, py2) else st.lineTo(px2, py2) }
            st.close(); c.drawPath(st, fill)
        }
        fill.isAntiAlias = true
        c.restore()
    }

    private fun drawRadarFrame(c: Canvas, cx: Float, cy: Float, r: Float, bearing: Float, d: Float) {
        fill.shader = RadialGradient(
            cx, cy, r, intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(150, 0, 0, 0)),
            floatArrayOf(0f, 0.8f, 1f), Shader.TileMode.CLAMP
        )
        c.drawCircle(cx, cy, r, fill)
        fill.shader = null
        stroke.color = Color.argb(230, 20, 20, 20); stroke.strokeWidth = 9 * d
        c.drawCircle(cx, cy, r + 3 * d, stroke)
        stroke.color = Color.argb(200, 220, 220, 220); stroke.strokeWidth = 2 * d
        c.drawCircle(cx, cy, r - 1 * d, stroke)
        val a = Math.toRadians((-bearing - 90).toDouble())
        val nx = cx + (r * cos(a)).toFloat(); val ny = cy + (r * sin(a)).toFloat()
        fill.color = Color.argb(240, 30, 30, 30); c.drawCircle(nx, ny, 13 * d, fill)
        text.color = Color.WHITE; text.textSize = 15 * d; text.textAlign = Paint.Align.CENTER
        c.drawText("N", nx, ny + 5.5f * d, text)
    }

    /** A HUD panel in the theme's shape: rounded, cut-corner, or pixel-stepped. */
    private fun panel(c: Canvas, r: RectF, d: Float, t: Theme, outline: Boolean, outlineColor: Int? = null) {
        // slightly see-through so the map shows behind, still solid enough to read
        fill.color = Ui.panel(t).let { Color.argb(218, Color.red(it), Color.green(it), Color.blue(it)) }
        stroke.color = outlineColor ?: t.route; stroke.strokeWidth = 2 * d
        when (t.hud) {
            HudShape.ROUND -> {
                c.drawRoundRect(r, 12 * d, 12 * d, fill)
                if (outline) c.drawRoundRect(r, 12 * d, 12 * d, stroke)
            }
            HudShape.CHAMFER -> {
                val k = minOf(14 * d, r.height() / 3)
                val p = Path().apply {
                    moveTo(r.left + k, r.top); lineTo(r.right, r.top)
                    lineTo(r.right, r.bottom - k); lineTo(r.right - k, r.bottom)
                    lineTo(r.left, r.bottom); lineTo(r.left, r.top + k); close()
                }
                c.drawPath(p, fill)
                if (outline) {
                    c.drawPath(p, stroke)
                    fill.color = outlineColor ?: t.route
                    c.drawRect(r.left + k + 4 * d, r.top - 2 * d, r.left + k + 44 * d, r.top + 2 * d, fill)
                }
            }
            HudShape.PIXEL -> {
                val k = 4 * d
                c.drawRect(r.left + k, r.top, r.right - k, r.bottom, fill)
                c.drawRect(r.left, r.top + k, r.right, r.bottom - k, fill)
                if (outline) {
                    stroke.strokeWidth = k
                    stroke.isAntiAlias = false
                    c.drawRect(r.left + k / 2, r.top + k / 2, r.right - k / 2, r.bottom - k / 2, stroke)
                    stroke.isAntiAlias = true
                }
            }
        }
    }

    // ---------------------------------------------------------------- overlays

    private fun drawGrid(c: Canvas, a: Rect, d: Float) {
        stroke.color = Color.argb(45, 0, 240, 255); stroke.strokeWidth = 1 * d
        val step = 40 * d
        var x = a.left.toFloat(); while (x < a.right) { c.drawLine(x, a.top.toFloat(), x, a.bottom.toFloat(), stroke); x += step }
        var y = a.top.toFloat(); while (y < a.bottom) { c.drawLine(a.left.toFloat(), y, a.right.toFloat(), y, stroke); y += step }
        fill.shader = LinearGradient(
            0f, a.top.toFloat(), 0f, a.top + a.height() * 0.35f,
            Color.argb(160, 255, 43, 214), Color.TRANSPARENT, Shader.TileMode.CLAMP
        )
        c.drawRect(a.left.toFloat(), a.top.toFloat(), a.right.toFloat(), a.top + a.height() * 0.35f, fill)
        fill.shader = null
    }

    private fun drawScanlines(c: Canvas, a: Rect, d: Float, t: Theme) {
        stroke.color = Color.argb(50, 0, 0, 0); stroke.strokeWidth = 1.2f * d
        var y = a.top.toFloat(); while (y < a.bottom) { c.drawLine(a.left.toFloat(), y, a.right.toFloat(), y, stroke); y += 3 * d }
        return
        val r = hypot(a.width().toFloat(), a.height().toFloat()) / 2
        fill.shader = RadialGradient(
            a.exactCenterX(), a.exactCenterY(), r,
            intArrayOf(Color.TRANSPARENT, Color.argb(200, 0, 10, 0)), floatArrayOf(0.55f, 1f), Shader.TileMode.CLAMP
        )
        c.drawRect(a, fill)
        fill.shader = null
    }

    private var washTex: Bitmap? = null
    private var washDark = false

    /** Watercolour paper: fine fibres and a soft pale edge, in the light or the ink-wash night. */
    private fun drawWash(c: Canvas, a: Rect) {
        val dark = MapState.dark
        val tex = washTex?.takeIf { washDark == dark } ?: Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888).also { b ->
            val rnd = java.util.Random(11)
            for (y in 0 until 160) for (x in 0 until 160) {
                val v = rnd.nextInt(100)
                b.setPixel(x, y, when {
                    v < 5 -> if (dark) Color.argb(26, 255, 245, 230) else Color.argb(30, 120, 100, 80)
                    v > 94 -> if (dark) Color.argb(20, 0, 0, 0) else Color.argb(34, 255, 255, 255)
                    else -> Color.TRANSPARENT
                })
            }
            washTex = b; washDark = dark
        }
        fill.shader = BitmapShader(tex, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        c.drawRect(a, fill)
        val r = hypot(a.width().toFloat(), a.height().toFloat()) / 2
        val edge = if (dark) Color.argb(90, 30, 28, 36) else Color.argb(80, 255, 252, 244)
        fill.shader = RadialGradient(a.exactCenterX(), a.exactCenterY(), r, intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, edge),
            floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(a, fill)
        fill.shader = null
    }

    private fun drawPaper(c: Canvas, a: Rect) {
        val tex = paperTex ?: Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).also { b ->
            val rnd = java.util.Random(7)
            for (y in 0 until 128) for (x in 0 until 128) {
                val v = rnd.nextInt(100)
                b.setPixel(x, y, when {
                    v < 4 -> Color.argb(60, 90, 60, 30)
                    v < 12 -> Color.argb(25, 120, 90, 50)
                    v > 96 -> Color.argb(40, 255, 250, 235)
                    else -> Color.TRANSPARENT
                })
            }
            paperTex = b
        }
        fill.shader = BitmapShader(tex, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        c.drawRect(a, fill)
        val r = hypot(a.width().toFloat(), a.height().toFloat()) / 2
        fill.shader = RadialGradient(
            a.exactCenterX(), a.exactCenterY(), r,
            intArrayOf(Color.TRANSPARENT, Color.argb(70, 120, 80, 30), Color.argb(190, 80, 45, 15)),
            floatArrayOf(0.5f, 0.85f, 1f), Shader.TileMode.CLAMP
        )
        c.drawRect(a, fill)
        fill.shader = null
    }

    /** Rain streaks: fixed positions (0..1), drifting slowly down with time. */
    private val rainDrops by lazy {
        val r = java.util.Random(11)
        FloatArray(80 * 3) { i -> if (i % 3 == 2) 0.5f + r.nextFloat() else r.nextFloat() }
    }

    /**
     * Violet Rain: a purple haze at the top and thin rain streaks. Faint on purpose: they never hide
     * a road or the route (the route and HUD are drawn on top).
     */
    private fun drawRain(c: Canvas, a: Rect, d: Float) {
        val dark = MapState.dark
        val top = a.top.toFloat(); val hh = a.height() * 0.38f
        fill.shader = LinearGradient(0f, top, 0f, top + hh,
            intArrayOf(if (theme == Theme.TOKYO_NIGHT) Color.argb(110, 20, 24, 48) else if (dark) Color.argb(150, 42, 15, 74) else Color.argb(90, 150, 110, 210), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP)
        c.drawRect(a.left.toFloat(), top, a.right.toFloat(), top + hh, fill)
        fill.shader = null
        stroke.color = if (theme == Theme.TOKYO_NIGHT) Color.argb(48, 170, 190, 255) else if (dark) Color.argb(40, 217, 200, 255) else Color.argb(46, 90, 60, 150)
        stroke.strokeWidth = 1f * d
        // a drop crosses the screen in about two seconds (bigger, nearer drops a little faster),
        // falling a little sideways, and comes back in at the top somewhere else: a light rain
        val now = android.os.SystemClock.uptimeMillis()
        val w = a.width().toFloat(); val h = a.height().toFloat()
        val drops = rainDrops
        var i = 0
        while (i < drops.size) {
            val size = drops[i + 2]
            val len = size * 20 * d
            val period = (2600f - size * 700f) // ms per fall: 1550 to 2250
            val f = ((now + (drops[i + 1] * period).toLong()) % period.toLong()) / period
            val lap = (now + (drops[i + 1] * period).toLong()) / period.toLong()
            val x0 = ((drops[i] + lap * 0.618034) % 1.0).toFloat()
            val y = a.top - len + f * (h + len)
            val x = a.left + x0 * w + f * h * 0.18f - h * 0.09f
            c.drawLine(x, y, x + len * 0.18f, y + len, stroke)
            i += 3
        }
    }

    private val snowFlakes by lazy {
        val r = java.util.Random(11)
        FloatArray(70 * 4) { i -> when (i % 4) { 0, 1 -> r.nextFloat(); 2 -> 0.6f + r.nextFloat() * 0.8f; else -> r.nextFloat() * 6.28f } }
    }

    /**
     * Arctic: light snow drifting down and swaying, and a frosty haze at the top. Drawn faint and
     * under everything that matters (route, arrow, turn box come after it).
     */
    private fun drawSnow(c: Canvas, a: Rect, d: Float) {
        val dark = MapState.dark
        val top = a.top.toFloat(); val hh = a.height() * 0.3f
        fill.shader = LinearGradient(0f, top, 0f, top + hh,
            intArrayOf(if (dark) Color.argb(110, 200, 220, 240) else Color.argb(150, 235, 244, 252), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP)
        c.drawRect(a.left.toFloat(), top, a.right.toFloat(), top + hh, fill)
        fill.shader = null
        val t = (android.os.SystemClock.uptimeMillis() % 120_000L) / 1000f
        val w = a.width().toFloat(); val h = a.height().toFloat()
        val f = snowFlakes
        var i = 0
        while (i < f.size) {
            val size = f[i + 2]
            val fall = (f[i + 1] + t * (0.035f + size * 0.025f)) % 1f   // big flakes fall a little faster
            val x = a.left + ((f[i] * w + kotlin.math.sin(t * 0.8f + f[i + 3]) * 14 * d) % w + w) % w
            val y = a.top + fall * h
            // on the white map the flakes need a cool edge to show at all
            fill.color = if (dark) Color.argb(170, 235, 242, 250) else Color.argb(230, 214, 228, 242)
            c.drawCircle(x, y, (1.6f + size * 1.6f) * d, fill)
            if (!dark) { stroke.color = Color.argb(170, 92, 128, 168); stroke.strokeWidth = 1f * d; c.drawCircle(x, y, (1.6f + size * 1.6f) * d, stroke) }
            i += 4
        }
    }

    /** Blueprint: a faint drafting grid, every fifth line a little stronger. */
    private fun drawDrafting(c: Canvas, a: Rect, d: Float) {
        val dark = MapState.dark
        val step = 22 * d
        var i = 0; var x = a.left.toFloat()
        while (x < a.right) {
            stroke.color = if (dark) Color.argb(if (i % 5 == 0) 46 else 22, 207, 227, 255) else Color.argb(if (i % 5 == 0) 44 else 20, 30, 80, 166)
            stroke.strokeWidth = (if (i % 5 == 0) 1.2f else 0.8f) * d
            c.drawLine(x, a.top.toFloat(), x, a.bottom.toFloat(), stroke); x += step; i++
        }
        i = 0; var y = a.top.toFloat()
        while (y < a.bottom) {
            stroke.color = if (dark) Color.argb(if (i % 5 == 0) 46 else 22, 207, 227, 255) else Color.argb(if (i % 5 == 0) 44 else 20, 30, 80, 166)
            stroke.strokeWidth = (if (i % 5 == 0) 1.2f else 0.8f) * d
            c.drawLine(a.left.toFloat(), y, a.right.toFloat(), y, stroke); y += step; i++
        }
    }

    private fun drawSunGlow(c: Canvas, a: Rect) {
        fill.shader = LinearGradient(
            0f, a.top.toFloat(), 0f, a.top + a.height() * 0.5f,
            intArrayOf(Color.argb(150, 247, 160, 70), Color.argb(60, 232, 100, 80), Color.TRANSPARENT),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP
        )
        c.drawRect(a.left.toFloat(), a.top.toFloat(), a.right.toFloat(), a.top + a.height() * 0.5f, fill)
        fill.shader = null
    }

    // ---------------------------------------------------------------- HUD

    /** The speed box and limit sign last drawn, and its colour when over the limit (0 = not over): for tests. */
    internal val speedRect = RectF()
    @Volatile internal var speedColor = 0
    private val ORANGE_OVER = Color.rgb(255, 159, 28)
    private val RED_OVER = Color.rgb(255, 77, 94)

    private fun drawSpeed(c: Canvas, a: Rect, d: Float, t: Theme) {
        if (LocationTracker.last == null) return
        val imp = MapState.imperial
        val kmh = Math.round(LocationTracker.speedMps * (if (imp) 2.23694f else 3.6f))
        val limitKmh = SpeedLimit.now()
        val limit = limitKmh?.let { if (imp) ((it * 0.621371 / 5).roundToInt() * 5) else it }
        // over the limit: orange up to 5 km/h over, red past that (always judged in km/h)
        val level = SpeedLimit.level(Math.round(LocationTracker.speedMps * 3.6f), limitKmh)
        val over = level > 0
        val overColor = if (level == 2) RED_OVER else ORANGE_OVER
        val pad = 14 * d
        text.textAlign = Paint.Align.CENTER
        text.textSize = 30 * d
        val big = kmh.toString()
        val bw = maxOf(text.measureText(big), text.measureText("88")) + 24 * d
        val box = RectF(a.left + pad, a.bottom - pad - 58 * d, a.left + pad + bw, a.bottom - pad)
        speedRect.set(box.left, box.top, box.right + (if (limit != null) 8 * d + 42 * d else 0f), box.bottom)
        speedColor = if (over) overColor else 0
        panel(c, box, d, t, outline = over, outlineColor = if (over) overColor else null)
        text.color = if (over) overColor else Ui.ink(t)
        c.drawText(big, box.centerX(), box.top + 33 * d, text)
        text.textSize = 11 * d
        c.drawText(if (imp) "MPH" else gr.gamemap.L.t("KM/H"), box.centerX(), box.bottom - 9 * d, text)
        if (limit != null) {
            val r = 21 * d
            val sx = box.right + 8 * d + r; val sy = box.bottom - r - 4 * d
            fill.color = Color.WHITE; c.drawCircle(sx, sy, r, fill)
            stroke.color = Color.rgb(208, 2, 27); stroke.strokeWidth = 4.5f * d; c.drawCircle(sx, sy, r - 2.2f * d, stroke)
            text.color = Color.rgb(17, 20, 24); text.textSize = (if (limit >= 100) 14 else 16) * d
            val tf = text.typeface; text.typeface = Typeface.DEFAULT_BOLD
            c.drawText(limit.toString(), sx, sy + 5.5f * d, text)
            text.typeface = tf
        }
    }

    // ------------------------------------------------------------ car buttons drawn on the map

    /** Draw GameRoute's own buttons on the car screen (hosts that report taps). */
    var carButtons = false
    private var tripSeen: Any? = null
    private var tripSeenAt = 0L
    /** Where each car button is on screen, for the car's tap callback. */
    private val carHits = ArrayList<Pair<RectF, String>>()
    /** The buttons of the last finished frame (taps are read on another thread than drawing). */
    @Volatile internal var hitsShown: List<Pair<RectF, String>> = emptyList()
        private set

    /** The car's stable area top: Android Auto's own ☰ lives above it. 0 = not known. */
    @Volatile var stableTop = 0
    private var etaRight = 0f
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }

    /** The id of the car button under (x, y), if any. */
    fun carButtonAt(x: Float, y: Float): String? {
        val hits = hitsShown
        // exact hit first, then a forgiving 12 dp halo (car screens are hard to hit precisely)
        hits.lastOrNull { it.first.contains(x, y) }?.let { return it.second }
        val pad = 12 * lastDensity
        return hits.lastOrNull { RectF(it.first).apply { inset(-pad, -pad) }.contains(x, y) }?.second
    }
    @Volatile private var lastDensity = 1f

    private fun glassLight(t: Theme) = Ui.luminance(Ui.panel(t)) > 0.4

    /** Icon and text colour on a glass button. */
    private fun glassInk(t: Theme) = if (glassLight(t)) Ui.ink(t) else Color.WHITE

    private fun glassButton(c: Canvas, r: RectF, d: Float, t: Theme, fillCol: Int? = null) {
        // light styles: a more solid pane and dark icons, or white icons vanish on a bright map
        val light = glassLight(t)
        // solid: map icons must not show through the buttons
        val base = fillCol ?: Ui.panel(t).let { Color.argb(248, Color.red(it), Color.green(it), Color.blue(it)) }
        fill.color = base
        stroke.color = if (light) Ui.onPanel(t, t.route, 3.0) else withAlpha(t.route, 230)
        stroke.strokeWidth = (if (light) 2.2f else 1.8f) * d
        when (t.hud) {
            HudShape.ROUND -> {
                val rad = minOf(r.width(), r.height()) / 2
                c.drawRoundRect(r, rad, rad, fill)
                if (fillCol == null) c.drawRoundRect(r, rad, rad, stroke)
            }
            HudShape.CHAMFER -> {
                val k = minOf(10 * d, r.height() / 3)
                val p = Path().apply {
                    moveTo(r.left + k, r.top); lineTo(r.right, r.top); lineTo(r.right, r.bottom - k)
                    lineTo(r.right - k, r.bottom); lineTo(r.left, r.bottom); lineTo(r.left, r.top + k); close()
                }
                c.drawPath(p, fill); if (fillCol == null) c.drawPath(p, stroke)
            }
            HudShape.PIXEL -> {
                val k = 4 * d
                c.drawRect(r.left + k, r.top, r.right - k, r.bottom, fill)
                c.drawRect(r.left, r.top + k, r.right, r.bottom - k, fill)
                if (fillCol == null) { stroke.isAntiAlias = false; c.drawRect(r, stroke); stroke.isAntiAlias = true }
            }
        }
    }

    /** Simple white line icons. */
    private fun icon(c: Canvas, id: String, cx: Float, cy: Float, s: Float, col: Int) {
        iconPaint.color = col; iconPaint.strokeWidth = s * 0.16f
        iconPaint.style = Paint.Style.STROKE
        val h = s / 2
        val p = Path()
        when (id) {
            "style" -> { p.moveTo(cx, cy - h); p.lineTo(cx + h, cy - h * 0.4f); p.lineTo(cx, cy + h * 0.2f); p.lineTo(cx - h, cy - h * 0.4f); p.close()
                p.moveTo(cx - h, cy + h * 0.25f); p.lineTo(cx, cy + h * 0.85f); p.lineTo(cx + h, cy + h * 0.25f) }
            "friends" -> { p.addCircle(cx - h * 0.35f, cy - h * 0.35f, h * 0.34f, Path.Direction.CW)
                p.moveTo(cx - h * 0.95f, cy + h * 0.85f); p.quadTo(cx - h * 0.95f, cy + h * 0.05f, cx - h * 0.35f, cy + h * 0.05f); p.quadTo(cx + h * 0.25f, cy + h * 0.05f, cx + h * 0.25f, cy + h * 0.85f)
                p.addCircle(cx + h * 0.45f, cy - h * 0.25f, h * 0.27f, Path.Direction.CW)
                p.moveTo(cx + h * 0.45f, cy + h * 0.15f); p.quadTo(cx + h * 0.95f, cy + h * 0.15f, cx + h * 0.95f, cy + h * 0.8f) }
            "report" -> { p.moveTo(cx, cy - h); p.lineTo(cx + h, cy + h * 0.8f); p.lineTo(cx - h, cy + h * 0.8f); p.close()
                p.moveTo(cx, cy - h * 0.3f); p.lineTo(cx, cy + h * 0.25f); p.moveTo(cx, cy + h * 0.52f); p.lineTo(cx, cy + h * 0.56f) }
            "mute", "unmute" -> { p.moveTo(cx - h, cy - h * 0.35f); p.lineTo(cx - h * 0.45f, cy - h * 0.35f); p.lineTo(cx + h * 0.1f, cy - h * 0.85f)
                p.lineTo(cx + h * 0.1f, cy + h * 0.85f); p.lineTo(cx - h * 0.45f, cy + h * 0.35f); p.lineTo(cx - h, cy + h * 0.35f); p.close()
                if (id == "mute") { p.moveTo(cx + h * 0.45f, cy - h * 0.4f); p.quadTo(cx + h * 0.8f, cy, cx + h * 0.45f, cy + h * 0.4f) }
                else { p.moveTo(cx + h * 0.4f, cy - h * 0.35f); p.lineTo(cx + h, cy + h * 0.35f); p.moveTo(cx + h, cy - h * 0.35f); p.lineTo(cx + h * 0.4f, cy + h * 0.35f) } }
            "traffic" -> { val r = RectF(cx - h * 0.5f, cy - h, cx + h * 0.5f, cy + h); p.addRoundRect(r, h * 0.35f, h * 0.35f, Path.Direction.CW)
                // the three lights, filled in their own colours
                val keep = iconPaint.color
                iconPaint.style = Paint.Style.FILL
                for ((k, col) in listOf(-1 to Color.rgb(240, 62, 62), 0 to Color.rgb(250, 176, 5), 1 to Color.rgb(47, 180, 90))) {
                    iconPaint.color = col; c.drawCircle(cx, cy + k * h * 0.6f, h * 0.22f, iconPaint)
                }
                iconPaint.style = Paint.Style.STROKE; iconPaint.color = keep }
            "zoomin" -> { p.moveTo(cx - h * 0.8f, cy); p.lineTo(cx + h * 0.8f, cy); p.moveTo(cx, cy - h * 0.8f); p.lineTo(cx, cy + h * 0.8f) }
            "zoomout" -> { p.moveTo(cx - h * 0.8f, cy); p.lineTo(cx + h * 0.8f, cy) }
            "menu" -> { for (k in -1..1) { p.moveTo(cx - h * 0.8f, cy + k * h * 0.55f); p.lineTo(cx + h * 0.8f, cy + k * h * 0.55f) } }
            "overview" -> { p.moveTo(cx - h, cy - h * 0.6f); p.lineTo(cx - h * 0.35f, cy - h * 0.85f); p.lineTo(cx + h * 0.35f, cy - h * 0.6f)
                p.lineTo(cx + h, cy - h * 0.85f); p.lineTo(cx + h, cy + h * 0.6f); p.lineTo(cx + h * 0.35f, cy + h * 0.85f)
                p.lineTo(cx - h * 0.35f, cy + h * 0.6f); p.lineTo(cx - h, cy + h * 0.85f); p.close()
                p.moveTo(cx - h * 0.35f, cy - h * 0.85f); p.lineTo(cx - h * 0.35f, cy + h * 0.6f); p.moveTo(cx + h * 0.35f, cy - h * 0.6f); p.lineTo(cx + h * 0.35f, cy + h * 0.85f) }
            "recenter" -> {
                // a target: ring, centre dot, four ticks
                p.addCircle(cx, cy, h * 0.62f, Path.Direction.CW)
                p.moveTo(cx, cy - h); p.lineTo(cx, cy - h * 0.78f); p.moveTo(cx, cy + h); p.lineTo(cx, cy + h * 0.78f)
                p.moveTo(cx - h, cy); p.lineTo(cx - h * 0.78f, cy); p.moveTo(cx + h, cy); p.lineTo(cx + h * 0.78f, cy)
                val keep = iconPaint.color
                c.drawPath(p, iconPaint); p.reset()
                iconPaint.style = Paint.Style.FILL; iconPaint.color = keep
                c.drawCircle(cx, cy, h * 0.24f, iconPaint)
                iconPaint.style = Paint.Style.STROKE }
            "search" -> { p.addCircle(cx - h * 0.15f, cy - h * 0.15f, h * 0.6f, Path.Direction.CW); p.moveTo(cx + h * 0.3f, cy + h * 0.3f); p.lineTo(cx + h * 0.85f, cy + h * 0.85f) }
            "home" -> { p.moveTo(cx - h, cy - h * 0.05f); p.lineTo(cx, cy - h * 0.85f); p.lineTo(cx + h, cy - h * 0.05f)
                p.moveTo(cx - h * 0.7f, cy - h * 0.25f); p.lineTo(cx - h * 0.7f, cy + h * 0.8f); p.lineTo(cx + h * 0.7f, cy + h * 0.8f); p.lineTo(cx + h * 0.7f, cy - h * 0.25f) }
            "work" -> { p.addRect(cx - h, cy - h * 0.45f, cx + h, cy + h * 0.8f, Path.Direction.CW); p.moveTo(cx - h * 0.35f, cy - h * 0.45f)
                p.lineTo(cx - h * 0.35f, cy - h * 0.8f); p.lineTo(cx + h * 0.35f, cy - h * 0.8f); p.lineTo(cx + h * 0.35f, cy - h * 0.45f) }
        }
        c.drawPath(p, iconPaint)
        iconPaint.style = Paint.Style.STROKE
    }

    private fun drawCarButtons(c: Canvas, a: Rect, d: Float, t: Theme) {
        val size = 48 * d
        val gap = 10 * d
        val x = a.right - 14 * d - size
        val white = Color.WHITE
        val ink = glassInk(t)
        fun btn(id: String, y: Float, iconId: String = id, filled: Boolean = false) {
            val r = RectF(x, y, x + size, y + size)
            glassButton(c, r, d, t, if (filled) withAlpha(t.route, 235) else null)
            icon(c, iconId, r.centerX(), r.centerY(), size * 0.46f, if (filled) Ui.ink(t).let { if (Ui.contrast(it, t.route) < 3) Color.BLACK else it } else ink)
            carHits += r to id
        }
        // Android Auto's ☰ sits above the stable area; when the host hides it (the visible area
        // then reaches the top), GameMap shows its own lookalike in the same place.
        val stripBottom = if (stableTop > 8 * d) stableTop.toFloat() else 76 * d
        val hostShown = a.top > 8 * d
        if (!hostShown) {
            val ms = minOf(56 * d, stripBottom - 8 * d).coerceAtLeast(40 * d)
            val cy = stripBottom / 2
            val r = RectF(x + size / 2 - ms / 2, cy - ms / 2, x + size / 2 + ms / 2, cy + ms / 2)
            glassButton(c, r, d, t)
            icon(c, "menu", r.centerX(), r.centerY(), ms * 0.42f, ink)
            carHits += r to "menu"
        }
        // right: style, traffic / voice, report (below the ☰, whichever one is showing)
        var y = maxOf(stripBottom, a.top.toFloat()) + gap
        btn("style", y); y += size + gap
        // (Show traffic moved to Settings › Map; driving, the sound button takes its place)
        if (NavEngine.active) { btn(if (Voice.muted) "unmute" else "mute", y); y += size + gap }
        // Friends (where Report used to be): how many are online, in a green badge
        btn("friends", y)
        val online = if (Friends.ready && Friends.core.started) Friends.core.friends.count { Friends.core.isOnline(it.fid) } +
            Friends.core.invites.count { it.convoy != Convoy.code } else 0
        if (online > 0) {
            val bx = x + size - 4 * d; val by = y + 4 * d
            fill.color = Color.rgb(64, 200, 120); c.drawCircle(bx, by, 11 * d, fill)
            stroke.color = Color.rgb(12, 14, 20); stroke.strokeWidth = 2 * d; c.drawCircle(bx, by, 11 * d, stroke)
            text.textAlign = Paint.Align.CENTER; text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 13 * d; text.color = Color.rgb(12, 14, 20)
            c.drawText(if (online > 9) "9+" else "$online", bx, by + 4.5f * d, text)
        }
        // bottom right: zoom and recenter / overview
        var b = a.bottom - 14 * d - size
        when {
            !view.following || view.userBearing != null || view.zoomedAway -> { btn("recenter", b, filled = true); b -= size + gap }
            NavEngine.active && NavEngine.route.size > 1 -> { btn("overview", b); b -= size + gap }
        }
        btn("zoomout", b); b -= size + gap
        btn("zoomin", b)

        text.typeface = Typeface.DEFAULT_BOLD
        text.textAlign = Paint.Align.LEFT
        if (NavEngine.active && !NavEngine.arrived) {
            // End, next to the arrival panel
            text.textSize = 18 * d
            val w = text.measureText(gr.gamemap.L.t("End")) + 44 * d
            val left = if (etaRight > 0) etaRight + 10 * d else a.left + 160 * d
            val r = RectF(left, a.bottom - 14 * d - 66 * d, left + w, a.bottom - 14 * d)
            glassButton(c, r, d, t, Color.argb(225, 229, 72, 77))
            text.color = white
            c.drawText(gr.gamemap.L.t("End"), r.left + 22 * d, r.centerY() + 6.5f * d, text)
            carHits += r to "end"
        } else if (!NavEngine.active && selectedPlace == null && !carLobbyShown && carFriends == null && carPanel?.takeIf { !it.dialog } == null) {
            // "Where to?" and saved places, top left (a place's card takes the corner when open)
            val r = RectF(a.left + 14 * d, 12 * d, a.left + 14 * d + 300 * d, 12 * d + 50 * d)
            glassButton(c, r, d, t)
            icon(c, "search", r.left + 26 * d, r.centerY(), 20 * d, Ui.onPanel(t, t.route, 3.0))
            text.textSize = 18 * d; text.typeface = t.typeface; text.color = ink
            c.drawText(gr.gamemap.L.t("Where to?"), r.left + 50 * d, r.centerY() + 6.5f * d, text)
            carHits += r to "search"
            var cxp = r.left
            text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 15 * d
            for (k in listOf("home", "work")) {
                val pl = Places.saved(k) ?: continue
                val label = (if (k == "home") gr.gamemap.L.t("Home") else gr.gamemap.L.t("Work")) + (Places.cachedEta(pl)?.let { " · ${(it.seconds + 30) / 60} min" } ?: "")
                val w = text.measureText(label) + 52 * d
                val cr = RectF(cxp, r.bottom + 10 * d, cxp + w, r.bottom + 10 * d + 44 * d)
                glassButton(c, cr, d, t)
                icon(c, k, cr.left + 22 * d, cr.centerY(), 16 * d, ink)
                text.color = ink
                c.drawText(label, cr.left + 38 * d, cr.centerY() + 5.5f * d, text)
                carHits += cr to k
                cxp = cr.right + 10 * d
            }
            // your labels ("Dad", "Gym") next to them, as many as fit (the rest are in Search)
            for ((i, s) in Places.labelled().withIndex()) {
                val pl = s.place()
                val label = (s.label ?: continue) + (Places.cachedEta(pl)?.let { " · ${(it.seconds + 30) / 60} min" } ?: "")
                val w = text.measureText(label) + 30 * d + (if (s.icon != null) 22 * d else 0f)
                if (cxp + w > a.left + a.width() * 0.62f) break
                val cr = RectF(cxp, r.bottom + 10 * d, cxp + w, r.bottom + 10 * d + 44 * d)
                glassButton(c, cr, d, t)
                var tx = cr.left + 15 * d
                s.icon?.let { e -> text.textSize = 16 * d; c.drawText(e, tx, cr.centerY() + 6 * d, text); tx += 22 * d; text.textSize = 15 * d }
                text.color = ink
                c.drawText(label, tx, cr.centerY() + 5.5f * d, text)
                carHits += cr to "fav:$i"
                cxp = cr.right + 10 * d
            }
        }
        text.typeface = t.typeface
    }

    private fun carRoutesWidth(a: Rect, d: Float) = minOf(440 * d, a.width() * 0.42f)

    /**
     * Car, choosing a route: GameRoute's own cards (in the style's colours and font, like the
     * phone's) instead of Android Auto's black list. Tap a card to pick it, Start to go, ✕ to
     * close. (Older cars that can't report taps keep Android Auto's list.)
     */
    private fun drawCarRoutes(c: Canvas, a: Rect, d: Float, t: Theme) {
        val opts = NavEngine.options
        val w = carRoutesWidth(a, d)
        val left = a.left + 14 * d
        val ink = Ui.ink(t); val dim = Ui.dim(t, 200)
        val accent = Ui.onPanel(t, t.route, 3.0)
        val loading = opts.isEmpty() || opts.any { !NavEngine.timeKnown(it) }
        val note = NavEngine.endNote
        val gap = 8 * d
        val head = 64 * d
        val shown = if (loading) 0 else minOf(opts.size, 3)
        val noteH = if (note != null && !loading) 50 * d else 0f
        val startH = 60 * d
        // every route fits on the screen (no scrolling in a car): on short screens the cards get
        // shorter and drop the "via" line instead of the third route being cut off
        val room = (a.bottom - 14 * d - startH - 10 * d) - (a.top + 14 * d + head + 6 * d) - noteH
        val cardH = if (shown == 0) 84 * d else (room / shown - gap).coerceIn(50 * d, 84 * d)
        val compact = cardH < 76 * d
        val bodyH = if (loading) 56 * d else shown * (cardH + gap) + noteH
        val box = RectF(left, a.top + 14 * d, left + w, a.top + 14 * d + head + bodyH + 6 * d)
        // Start below the panel (or at the bottom, whichever is higher up)
        val sTop = minOf(box.bottom + 10 * d, a.bottom - 14 * d - startH)
        if (box.bottom > sTop - 10 * d) box.bottom = sTop - 10 * d
        panel(c, box, d, t, outline = false)
        // header: ✕, how many routes, where to
        text.textAlign = Paint.Align.LEFT
        text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 24 * d; text.color = accent
        c.drawText("✕", box.left + 16 * d, box.top + 40 * d, text)
        carHits += RectF(box.left, box.top, box.left + 58 * d, box.top + head) to "routecancel"
        text.typeface = t.typeface; text.textSize = 19 * d; text.color = ink
        val title = if (loading) gr.gamemap.L.t("Finding routes…") else gr.gamemap.L.t(if (opts.size == 1) "1 route" else "${opts.size} routes")
        c.drawText(title, box.left + 54 * d, box.top + 30 * d, text)
        text.typeface = Typeface.DEFAULT; text.textSize = 14 * d; text.color = dim
        c.drawText(ellipsize(NavEngine.planPlace?.name.orEmpty(), w - 70 * d), box.left + 54 * d, box.top + 50 * d, text)
        if (!loading) {
            val fastest = NavEngine.fastestIndex()
            val anyToll = opts.any { it.tollCost > 0 || it.hasToll }
            var y = box.top + head
            for (i in 0 until shown) {
                val o = opts[i]
                val r = RectF(box.left + 12 * d, y, box.right - 12 * d, y + cardH)
                if (r.bottom > box.bottom) break
                val on = i == NavEngine.selected
                if (on) {
                    fill.color = withAlpha(t.route, 40); c.drawRoundRect(r, 10 * d, 10 * d, fill)
                    stroke.color = accent; stroke.strokeWidth = 2.5f * d; c.drawRoundRect(r, 10 * d, 10 * d, stroke)
                } else {
                    stroke.color = withAlpha(ink, 45); stroke.strokeWidth = 1.2f * d; c.drawRoundRect(r, 10 * d, 10 * d, stroke)
                }
                // 10 min   2.9 km · 23:22 · €2.40        FASTEST / +4 min traffic
                text.typeface = t.typeface; text.textSize = (if (compact) 21 else 24) * d; text.color = ink
                val tm = NavEngine.formatDuration(o.seconds)
                val line1 = if (compact) r.top + (cardH - 12 * d) * 0.55f + 4 * d else r.top + 32 * d
                c.drawText(tm, r.left + 14 * d, line1, text)
                val tw = text.measureText(tm)
                val toll = when { o.tollCost > 0 -> Tolls.money(o.tollCost); o.hasToll -> gr.gamemap.L.t("tolls"); anyToll -> gr.gamemap.L.t("No tolls"); else -> null }
                val sub = listOfNotNull(NavEngine.formatDistance(o.distance), NavEngine.optionArrival(o), toll).joinToString(" · ")
                val badge = when {
                    o.trafficSeconds != null && i == fastest && opts.size > 1 -> gr.gamemap.L.t("FASTEST")
                    o.delaySeconds >= 60 -> gr.gamemap.L.t("+${o.delaySeconds / 60} min traffic")
                    else -> null
                }
                text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 12.5f * d
                val bw = badge?.let { text.measureText(it) } ?: 0f
                if (badge != null) {
                    text.color = if (i == fastest) accent else Color.rgb(229, 72, 77)
                    text.textAlign = Paint.Align.RIGHT
                    c.drawText(badge, r.right - 12 * d, line1 - 2 * d, text)
                    text.textAlign = Paint.Align.LEFT
                }
                text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 14.5f * d; text.color = dim
                c.drawText(ellipsize(sub, r.width() - tw - bw - 46 * d), r.left + 24 * d + tw, line1 - 1 * d, text)
                if (!compact) {
                    text.typeface = Typeface.DEFAULT; text.textSize = 14 * d
                    val via = if (o.via.isNotBlank()) gr.gamemap.L.t("via ${o.via}") else gr.gamemap.L.t("route ${i + 1}")
                    c.drawText(ellipsize(via + if (o.tolls.size > 1) " · " + gr.gamemap.L.t("${o.tolls.size} toll stations") else "", r.width() - 28 * d), r.left + 14 * d, r.top + 54 * d, text)
                }
                // traffic along it
                val bar = RectF(r.left + 14 * d, r.bottom - (if (compact) 13 else 17) * d, r.right - 14 * d, r.bottom - (if (compact) 8 else 11) * d)
                fill.color = if (o.trafficSeconds != null) Color.rgb(47, 210, 122) else withAlpha(dim, 90)
                c.drawRoundRect(bar, 3 * d, 3 * d, fill)
                if (o.length > 0) for (st in o.traffic) {
                    fill.color = when { st.value >= 4 -> Color.rgb(140, 20, 30); st.value == 3 -> Color.rgb(230, 50, 60); st.value == 2 -> Color.rgb(255, 140, 40); else -> Color.rgb(255, 200, 60) }
                    val x0 = bar.left + (st.start / o.length * bar.width()).toFloat()
                    val x1 = maxOf(bar.left + (st.end / o.length * bar.width()).toFloat(), x0 + bar.height())
                    c.drawRect(x0, bar.top, minOf(x1, bar.right), bar.bottom, fill)
                }
                carHits += r to "route:$i"
                y += cardH + gap
            }
            if (note != null && y + 40 * d <= box.bottom) {
                text.typeface = Typeface.DEFAULT; text.textSize = 13.5f * d; text.color = Color.rgb(255, 170, 60)
                c.drawText(ellipsize("⚠ $note", w - 28 * d), box.left + 14 * d, y + 20 * d, text)
            }
        }
        // Start · 10 min
        val sr = RectF(left, sTop, left + w, sTop + startH)
        val ready = !loading
        // the route's colour, unless it hardly shows on this map (8-Bit): then the panel's, outlined
        val sf = if (t.pixel <= 1 && Ui.contrast(t.route, t.background) >= 1.8) t.route else Ui.panel(t)
        glassButton(c, sr, d, t, if (ready) withAlpha(sf, 245) else withAlpha(dim, 120))
        if (ready && sf != t.route) { stroke.color = accent; stroke.strokeWidth = 2.5f * d; c.drawRoundRect(sr, 6 * d, 6 * d, stroke) }
        text.textAlign = Paint.Align.CENTER; text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 21 * d
        text.color = if (Ui.contrast(Color.WHITE, sf) >= 3) Color.WHITE else Color.BLACK
        val pick = opts.getOrNull(NavEngine.selected)
        c.drawText(if (ready && pick != null) gr.gamemap.L.t("Start") + " · " + NavEngine.formatDuration(pick.seconds) else gr.gamemap.L.t("Start"),
            sr.centerX(), sr.centerY() + 7 * d, text)
        text.textAlign = Paint.Align.LEFT
        if (ready) carHits += sr to "routestart"
        text.typeface = t.typeface
    }

    /** Car: the arrival card with trip stats, XP, and Parking / Done buttons. */
    private fun drawCarTrip(c: Canvas, a: Rect, d: Float, t: Theme) {
        val trip = NavEngine.lastTrip ?: return
        carHits.clear()
        val w = minOf(460 * d, a.width() - 28f * d)
        val h = 236 * d
        // fixed in the top-left corner (Android Auto's own bars come and go and move the visible area)
        val box = RectF(a.left + 14 * d, 12 * d, a.left + 14 * d + w, 12 * d + h)
        panel(c, box, d, t, outline = true)
        val ink = Ui.ink(t)
        CarParking.list?.let { drawParkingList(c, box, d, t, it); return }
        text.textAlign = Paint.Align.LEFT
        // ARRIVED · 13:29, the whole name (two lines when long), time and distance. No XP in the car:
        // it waits on the phone, where you look at it parked.
        text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 13 * d; text.color = Ui.onPanel(t, t.route, 3.0)
        val at = if (trip.arrivedAt > 0) " · " + NavEngine.clock(trip.arrivedAt) else ""
        c.drawText(gr.gamemap.L.t("ARRIVED") + at, box.left + 18 * d, box.top + 28 * d, text)
        text.typeface = t.typeface; text.textSize = 24 * d; text.color = ink
        val maxW = w - 36 * d
        val words = trip.place.split(' ')
        var first = ""
        var k = 0
        while (k < words.size && text.measureText((first + " " + words[k]).trim()) <= maxW) { first = (first + " " + words[k]).trim(); k++ }
        if (first.isEmpty()) { first = ellipsize(trip.place, maxW); k = words.size }
        val second = words.drop(k).joinToString(" ")
        c.drawText(first, box.left + 18 * d, box.top + 58 * d, text)
        if (second.isNotEmpty()) c.drawText(ellipsize(second, maxW), box.left + 18 * d, box.top + 88 * d, text)
        text.typeface = Typeface.DEFAULT; text.textSize = 18 * d; text.color = Ui.dim(t, 210)
        c.drawText(NavEngine.formatDuration(trip.seconds.toDouble()) + " · " + NavEngine.formatDistance(trip.meters),
            box.left + 18 * d, box.top + (if (second.isNotEmpty()) 120 else 92) * d, text)
        // buttons
        val by = box.bottom - 18 * d - 56 * d
        val half = (w - 36 * d - 12 * d) / 2
        val park = RectF(box.left + 18 * d, by, box.left + 18 * d + half, by + 56 * d)
        val done = RectF(park.right + 12 * d, by, park.right + 12 * d + half, by + 56 * d)
        glassButton(c, park, d, t)
        glassButton(c, done, d, t, withAlpha(t.route, 240))
        text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 18 * d; text.textAlign = Paint.Align.CENTER
        text.color = glassInk(t)
        c.drawText(gr.gamemap.L.t("Parking"), park.centerX(), park.centerY() + 6.5f * d, text)
        text.color = if (Ui.contrast(Color.WHITE, t.route) >= 3) Color.WHITE else Color.BLACK
        c.drawText(gr.gamemap.L.t("Done"), done.centerX(), done.centerY() + 6.5f * d, text)
        text.textAlign = Paint.Align.LEFT
        text.typeface = t.typeface
        carHits += park to "parking"
        carHits += done to "done"
    }

    /**
     * Car: the arrival card turned into a parking list (the 3 nearest car parks), drawn by
     * GameMap itself, so it opens at once instead of waiting for an Android Auto screen.
     */
    private fun drawParkingList(c: Canvas, box: RectF, d: Float, t: Theme, list: List<Place>) {
        val ink = Ui.ink(t)
        text.textAlign = Paint.Align.LEFT
        text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 15 * d; text.color = Ui.onPanel(t, t.route, 3.0)
        c.drawText("🅿  PARKING NEARBY", box.left + 18 * d, box.top + 32 * d, text)
        // ✕ close
        val x = RectF(box.right - 52 * d, box.top + 6 * d, box.right - 8 * d, box.top + 50 * d)
        text.textAlign = Paint.Align.CENTER; text.textSize = 22 * d; text.color = ink
        c.drawText("✕", x.centerX(), x.centerY() + 8 * d, text)
        carHits += x to "parkclose"
        text.textAlign = Paint.Align.LEFT
        val near = LocationTracker.position
        val rowH = 58 * d
        var y = box.top + 50 * d
        if (list.isEmpty()) {
            text.typeface = t.typeface; text.textSize = 18 * d; text.color = Ui.dim(t, 200)
            c.drawText(if (CarParking.loading) gr.gamemap.L.t("Finding car parks…") else gr.gamemap.L.t("No car parks found nearby"), box.left + 18 * d, y + 40 * d, text)
            text.typeface = t.typeface
            return
        }
        list.take(3).forEachIndexed { i, p ->
            val r = RectF(box.left + 10 * d, y, box.right - 10 * d, y + rowH - 6 * d)
            glassButton(c, r, d, t, if (i == 0) withAlpha(t.route, 60) else null)
            val dist = near?.let { NavEngine.formatDistance(Geo.distance(it, p.pos)) } ?: ""
            text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 16 * d
            val dw = text.measureText(dist)
            text.color = Ui.dim(t, 220)
            c.drawText(dist, r.right - 14 * d - dw, r.centerY() + 6 * d, text)
            text.typeface = t.typeface; text.textSize = 18 * d; text.color = ink
            c.drawText(ellipsize(p.name, r.width() - dw - 44 * d), r.left + 14 * d, r.centerY() + 6.5f * d, text)
            carHits += r to "park:$i"
            y += rowH
        }
        text.typeface = t.typeface
    }

    // ------------------------------------------------------------------ convoy

    /** The fuel list's stations as price tags on the phone map ("D €1.74"); the cheapest is green. */
    private fun drawPricePins(c: Canvas, cam: Camera, w: Int, h: Int, d: Float, t: Theme) {
        val pins = Fuel.pins
        val cheapest = pins.minByOrNull { it.second.substringAfter("€", "9").toDoubleOrNull() ?: 9.0 }
        text.textAlign = Paint.Align.CENTER; text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 12.5f * d
        for ((pos, label) in pins) {
            val (x, y) = toScreen(pos, cam)
            if (x < -60 || y < -40 || x > w + 60 || y > h + 40) continue
            val tw = text.measureText(label) + 16 * d
            val r = RectF(x - tw / 2, y - 34 * d, x + tw / 2, y - 12 * d)
            fill.color = when {
                label.startsWith("⚡") -> Color.rgb(124, 58, 237)
                cheapest != null && label == cheapest.second && pos == cheapest.first -> Color.rgb(30, 158, 90)
                else -> Color.rgb(232, 89, 12)
            }
            c.drawRoundRect(r, 10 * d, 10 * d, fill)
            path.reset(); path.moveTo(x - 5 * d, r.bottom - 1); path.lineTo(x + 5 * d, r.bottom - 1); path.lineTo(x, r.bottom + 6 * d); path.close()
            c.drawPath(path, fill)
            text.color = Color.WHITE
            c.drawText(label, x, r.centerY() + 4.5f * d, text)
        }
        text.typeface = t.typeface
    }

    /** "Toll in 800 m · €2.50" before a toll station on the route. */
    private fun drawTollChip(c: Canvas, a: Rect, d: Float, t: Theme, price: Double, dist: Double) {
        val label = gr.gamemap.L.t("Toll in ${NavEngine.formatDistance(dist)} · ${Tolls.money(price)}")
        text.textAlign = Paint.Align.LEFT; text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 13.5f * d
        val w = text.measureText(label) + 36 * d
        val x = if (car) a.left + 12 * d else a.centerX() - w / 2
        val y = maxOf(bannerBottom, a.top.toFloat()) + 8 * d
        val r = RectF(x, y, x + w, y + 32 * d)
        fill.color = Color.argb(240, 255, 244, 214); c.drawRoundRect(r, 16 * d, 16 * d, fill)
        stroke.color = Color.rgb(230, 160, 20); stroke.strokeWidth = 1.5f * d; c.drawRoundRect(r, 16 * d, 16 * d, stroke)
        text.color = Color.rgb(120, 70, 0)
        c.drawText("€", r.left + 12 * d, r.centerY() + 5 * d, text)
        c.drawText(label, r.left + 26 * d, r.centerY() + 5 * d, text)
        text.typeface = t.typeface
        bannerBottom = r.bottom; bannerRight = r.right
    }

    /** Convoy race start: "Everyone's ready", then a big 3, 2, 1 and GO in the middle of the map. */
    private fun drawCountdown(c: Canvas, a: Rect, d: Float, t: Theme) {
        val left = Convoy.goAt - System.currentTimeMillis()
        val n = Math.ceil(left / 1000.0).toInt()
        val label = when {
            n > 3 -> gr.gamemap.L.t("Everyone's ready!")
            n >= 1 -> n.toString()
            else -> gr.gamemap.L.t("GO!")
        }
        // each number pops in big and settles
        val f = if (left > 0) ((left % 1000) / 1000f) else ((1500 + left) / 1500f).coerceIn(0f, 1f)
        val big = n in 1..3 || n <= 0
        val scale = if (big) 1f + 0.35f * f * f else 1f
        val cx = a.exactCenterX(); val cy = a.top + a.height() * 0.42f
        val r = (if (car) 54f else 70f) * d
        fill.color = Color.argb(225, 20, 22, 28)
        if (big) c.drawCircle(cx, cy, r * scale, fill)
        val col = if (n <= 0) Color.rgb(47, 210, 122) else t.route
        stroke.color = col; stroke.strokeWidth = 5 * d
        if (big) c.drawCircle(cx, cy, r * scale - 2.5f * d, stroke)
        text.textAlign = Paint.Align.CENTER; text.typeface = Typeface.DEFAULT_BOLD
        text.color = if (n <= 0) col else Color.WHITE
        if (big) {
            text.textSize = (if (n <= 0) 34f else 64f) * d * (if (car) 0.8f else 1f) * scale
            c.drawText(label, cx, cy + text.textSize * 0.36f, text)
        } else {
            text.textSize = 20 * d
            val w = text.measureText(label) + 40 * d
            val rr = RectF(cx - w / 2, cy - 26 * d, cx + w / 2, cy + 26 * d)
            c.drawRoundRect(rr, 26 * d, 26 * d, fill)
            stroke.color = t.route; stroke.strokeWidth = 2.5f * d; c.drawRoundRect(rr, 26 * d, 26 * d, stroke)
            c.drawText("🏁 $label", cx, cy + 7 * d, text)
        }
        text.typeface = t.typeface
    }

    /** Car: someone in the convoy chose a destination: Go (accept) or ✕ (decline). */
    /** Car: the open panel (menu, nearby, quick messages, report, leave…), or null. */
    @Volatile var carPanel: CarPanel? = null
    /** Which page of a long list is showing. */
    @Volatile var carPanelPage = 0
    /** A short note in the style ("Sent", "Reported"), for ~2 s. */
    @Volatile var carFlash: Pair<String, Long>? = null

    private fun drawCarFlash(c: Canvas, a: Rect, d: Float, t: Theme, msg: String) {
        text.textAlign = Paint.Align.CENTER; text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 19 * d
        val w = text.measureText(msg) + 48 * d
        val r = RectF(a.centerX() - w / 2, a.bottom - 140 * d, a.centerX() + w / 2, a.bottom - 88 * d)
        panel(c, r, d, t, outline = true)
        text.color = Ui.ink(t)
        c.drawText(msg, r.centerX(), r.centerY() + 7 * d, text)
        text.textAlign = Paint.Align.LEFT
    }

    private fun drawCarPanel(c: Canvas, a: Rect, d: Float, t: Theme, p: CarPanel) {
        val ink = Ui.ink(t); val dimInk = withAlpha(ink, 170); val accentInk = Ui.onPanel(t, t.route, 3.0)
        val onRoute = if (Ui.contrast(Color.WHITE, t.route) >= 3) Color.WHITE else Color.BLACK
        fun button(rr: RectF, label: String, id: String, fillCol: Int? = null, inkCol: Int = ink, size: Float = 16f) {
            glassButton(c, rr, d, t, fillCol)
            text.textAlign = Paint.Align.CENTER; text.typeface = Typeface.DEFAULT_BOLD; text.textSize = size * d; text.color = inkCol
            c.drawText(ellipsize(label, rr.width() - 16 * d), rr.centerX(), rr.centerY() + size * 0.37f * d, text)
            carHits += rr to id
        }
        if (p.dialog) {
            // a question over the dimmed map
            fill.color = Color.argb(150, 8, 4, 16); c.drawRect(a, fill)
            val w = minOf(620 * d, a.width() - 40 * d); val h = 290 * d
            val r = RectF(a.centerX() - w / 2, a.centerY() - h / 2, a.centerX() + w / 2, a.centerY() + h / 2)
            panel(c, r, d, t, outline = true)
            carHits += RectF(a) to "cp:none" // taps beside it do nothing (it asks first)
            text.textAlign = Paint.Align.CENTER; text.typeface = t.typeface; text.textSize = 26 * d; text.color = ink
            c.drawText(ellipsize(p.title, w - 40 * d), r.centerX(), r.top + 56 * d, text)
            text.typeface = Typeface.DEFAULT; text.textSize = 17 * d; text.color = dimInk
            var ly = r.top + 98 * d
            for (line in wrap(p.message, w - 60 * d).take(3)) { c.drawText(line, r.centerX(), ly, text); ly += 24 * d }
            val bw = (w - 3 * 20 * d) / 2; val by = r.bottom - 24 * d - 64 * d
            p.rows.take(2).forEachIndexed { i, b ->
                val rr = RectF(r.left + 20 * d + i * (bw + 20 * d), by, r.left + 20 * d + i * (bw + 20 * d) + bw, by + 64 * d)
                if (b.red) button(rr, b.title, "cp:" + b.id, Color.rgb(208, 2, 27), Color.WHITE, 19f) else button(rr, b.title, "cp:" + b.id, null, ink, 19f)
            }
            text.textAlign = Paint.Align.LEFT
            return
        }
        val w = minOf(if (p.tiles.isNotEmpty()) 600 * d else 560 * d, a.width() * 0.55f)
        val top = maxOf(bannerBottom, a.top.toFloat()) + 10 * d
        val r = RectF(a.left + 12 * d, top, a.left + 12 * d + w, a.bottom - 12 * d)
        panel(c, r, d, t, outline = true)
        val pad = 14 * d
        val back = RectF(r.left + pad, r.top + pad, r.left + pad + 48 * d, r.top + pad + 48 * d)
        glassButton(c, back, d, t)
        text.textAlign = Paint.Align.CENTER; text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 26 * d; text.color = ink
        c.drawText("‹", back.centerX(), back.centerY() + 9 * d, text)
        carHits += back to "cp:back"
        var titleRight = r.right - pad
        p.head?.let { h ->
            text.textSize = 16 * d; text.typeface = Typeface.DEFAULT_BOLD
            val hw = text.measureText(h.title) + 36 * d
            val hb = RectF(r.right - pad - hw, back.top, r.right - pad, back.bottom)
            button(hb, h.title, "cp:" + h.id, t.route, onRoute)
            titleRight = hb.left - 10 * d
        }
        text.textAlign = Paint.Align.LEFT; text.typeface = t.typeface; text.textSize = 22 * d; text.color = ink
        c.drawText(ellipsize(p.title, titleRight - back.right - 14 * d), back.right + 12 * d, back.centerY() + 8 * d, text)
        var y = back.bottom + 12 * d
        val bottom = r.bottom - pad - (if (p.note != null) 24 * d else 0f)
        if (p.loading || (p.rows.isEmpty() && p.tiles.isEmpty())) {
            text.typeface = Typeface.DEFAULT; text.textSize = 17 * d; text.color = dimInk
            c.drawText(if (p.loading) gr.gamemap.L.t("Looking…") else p.empty, r.left + pad + 4 * d, y + 30 * d, text)
            if (p.loading) animating = true
        } else if (p.tiles.isNotEmpty()) {
            // big tiles: 2 across (4 or fewer) or 3 across
            val cols = if (p.tiles.size <= 4) 2 else 3
            val rowsN = (p.tiles.size + cols - 1) / cols
            val gap = 10 * d
            val tw = (r.width() - 2 * pad - (cols - 1) * gap) / cols
            val th = ((bottom - y - (rowsN - 1) * gap) / rowsN).coerceAtMost(130 * d)
            p.tiles.forEachIndexed { i, tile ->
                val x0 = r.left + pad + (i % cols) * (tw + gap); val y0 = y + (i / cols) * (th + gap)
                val rr = RectF(x0, y0, x0 + tw, y0 + th)
                fill.color = withAlpha(ink, 22); c.drawRoundRect(rr, 10 * d, 10 * d, fill)
                if (tile.highlight) { stroke.color = t.route; stroke.strokeWidth = 2 * d; c.drawRoundRect(rr, 10 * d, 10 * d, stroke) }
                text.textAlign = Paint.Align.CENTER; text.typeface = Typeface.DEFAULT; text.textSize = minOf(36 * d, th * 0.32f); text.color = ink
                c.drawText(tile.icon, rr.centerX(), rr.centerY() - 2 * d, text)
                text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 17 * d
                c.drawText(ellipsize(tile.title, tw - 12 * d), rr.centerX(), rr.centerY() + 26 * d, text)
                carHits += rr to "cp:" + tile.id
            }
        } else {
            val rowH = 62 * d
            val per = maxOf(1, ((bottom - y) / rowH).toInt())
            val pages = (p.rows.size + per - 1) / per
            val page = carPanelPage.coerceIn(0, maxOf(0, pages - 1))
            // more than fit: the last row's place shows ▼ / ▲ to page through
            val many = pages > 1
            val shown = if (!many) p.rows else p.rows.drop(page * (per - 1)).take(per - 1)
            for (row in shown) {
                val rr = RectF(r.left + pad, y, r.right - pad, y + rowH - 8 * d)
                fill.color = withAlpha(ink, 22); c.drawRoundRect(rr, 10 * d, 10 * d, fill)
                if (row.moon) { fill.color = withAlpha(PoiLayer.MOON, 40); c.drawRoundRect(rr, 10 * d, 10 * d, fill) }
                if (row.highlight || row.moon) { stroke.color = if (row.moon) PoiLayer.MOON else t.route; stroke.strokeWidth = 2 * d; c.drawRoundRect(rr, 10 * d, 10 * d, stroke) }
                carHits += rr to "cp:" + row.id
                text.textAlign = Paint.Align.CENTER; text.typeface = Typeface.DEFAULT; text.textSize = 22 * d; text.color = ink
                c.drawText(row.icon, rr.left + 26 * d, rr.centerY() + 8 * d, text)
                text.textSize = 17 * d; text.typeface = Typeface.DEFAULT_BOLD
                val rightText = row.right ?: "›"
                text.textAlign = Paint.Align.RIGHT; text.color = if (row.right != null) accentInk else dimInk
                text.textSize = if (row.right != null) 17 * d else 22 * d
                c.drawText(rightText, rr.right - 14 * d, rr.centerY() + 7 * d, text)
                val rw = text.measureText(rightText) + 24 * d
                val tx = rr.left + 52 * d; val tw = rr.right - tx - rw
                text.textAlign = Paint.Align.LEFT; text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 17 * d; text.color = if (row.red) Color.rgb(255, 99, 99) else if (row.faded) dimInk else ink
                c.drawText(ellipsize(row.title, tw), tx, if (row.sub != null) rr.centerY() - 2 * d else rr.centerY() + 6 * d, text)
                row.sub?.let { s ->
                    text.typeface = if (row.moon) Typeface.DEFAULT_BOLD else Typeface.DEFAULT; text.textSize = 14 * d
                    text.color = if (row.moon) (if (Ui.contrast(PoiLayer.MOON, Ui.panel(t)) >= 3) PoiLayer.MOON else accentInk) else dimInk
                    c.drawText(ellipsize(s, tw), tx, rr.centerY() + 17 * d, text)
                }
                y += rowH
            }
            if (many) {
                val half = (r.width() - 2 * pad - 10 * d) / 2
                val up = RectF(r.left + pad, y, r.left + pad + half, y + rowH - 8 * d)
                val down = RectF(up.right + 10 * d, y, r.right - pad, y + rowH - 8 * d)
                button(up, "▲", "cp:page-", null, if (page > 0) ink else withAlpha(ink, 70), 20f)
                button(down, "▼  ${page + 1}/$pages", "cp:page+", null, if (page < pages - 1) ink else withAlpha(ink, 70), 18f)
            }
        }
        p.note?.let { n ->
            text.textAlign = Paint.Align.LEFT; text.typeface = Typeface.DEFAULT; text.textSize = 14 * d; text.color = dimInk
            c.drawText(ellipsize(n, r.width() - 2 * pad), r.left + pad, r.bottom - pad - 4 * d, text)
        }
        text.textAlign = Paint.Align.LEFT
    }

    /** [s] in lines no wider than [w] (with the current text size). */
    private fun wrap(s: String, w: Float): List<String> {
        val out = ArrayList<String>(); var line = ""
        for (word in s.split(' ')) {
            val tryLine = if (line.isEmpty()) word else "$line $word"
            if (text.measureText(tryLine) <= w) line = tryLine else { if (line.isNotEmpty()) out += line; line = word }
        }
        if (line.isNotEmpty()) out += line
        return out
    }

    /** Car: location is off (or not allowed) on the phone: where the invite card goes, until it's back. */
    private fun drawCarLocationOff(c: Canvas, a: Rect, d: Float, t: Theme) {
        val leftEdge = maxOf(bannerRight, if (!NavEngine.active) a.left + 14 * d + 300 * d else a.left.toFloat()) + 14 * d
        val rightEdge = a.right - 84 * d
        val room = rightEdge - leftEdge
        val wide = room >= 440 * d
        val w = if (wide) minOf(620 * d, room) else minOf(620 * d, a.width() - 110 * d)
        val x0 = if (wide) leftEdge + (room - w) / 2 else a.left + 14 * d
        val y0 = if (wide) a.top + 12 * d else maxOf(bannerBottom, a.top + 12 * d + 112 * d) + 10 * d
        val r = RectF(x0, y0, x0 + w, y0 + 78 * d)
        panel(c, r, d, t, outline = true, outlineColor = Color.rgb(255, 107, 107))
        // a location mark, crossed out
        val cx = r.left + 36 * d; val cy = r.centerY()
        stroke.color = Color.rgb(255, 107, 107); stroke.strokeWidth = 3 * d
        c.drawCircle(cx, cy, 11 * d, stroke)
        c.drawLine(cx - 17 * d, cy - 17 * d, cx + 17 * d, cy + 17 * d, stroke)
        val off = LocationStatus.state == LocationStatus.State.OFF
        text.textAlign = Paint.Align.LEFT; text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 19 * d; text.color = Ui.ink(t)
        val tx = r.left + 66 * d; val tw = r.right - tx - 14 * d
        c.drawText(ellipsize(gr.gamemap.L.t(if (off) "Location is off on your phone" else "GameRoute can't use your location"), tw), tx, r.top + 33 * d, text)
        text.typeface = Typeface.DEFAULT; text.textSize = 15 * d; text.color = withAlpha(Ui.ink(t), 175)
        c.drawText(ellipsize(gr.gamemap.L.t(if (off) "Turn it on on the phone (when you're parked) to be guided" else "Allow it on the phone (when you're parked) to be guided"), tw), tx, r.top + 58 * d, text)
        text.typeface = t.typeface
    }

    /** Car: a convoy invite, centred at the top for ~10 s: who, where, big Not now and Join. */
    private fun drawCarInvite(c: Canvas, a: Rect, d: Float, t: Theme, i: FriendCore.Invite) {
        // between what's top left (the turn box, or Where to?) and the buttons on the right; if
        // that gap is too narrow, under them, across the map (it was squeezed: the name was cut to
        // two letters and Not now drew over it)
        val leftEdge = maxOf(bannerRight, if (!NavEngine.active) a.left + 14 * d + 300 * d else a.left.toFloat()) + 14 * d
        val rightEdge = a.right - 84 * d
        val room = rightEdge - leftEdge
        val wide = room >= 440 * d
        val w = if (wide) minOf(600 * d, room) else minOf(600 * d, a.width() - 110 * d)
        val x0 = if (wide) leftEdge + (room - w) / 2 else a.left + 14 * d
        val y0 = if (wide) a.top + 12 * d else maxOf(bannerBottom, a.top + 12 * d + 112 * d) + 10 * d
        // slides down from above, and back up at the end
        val since = System.currentTimeMillis() - InvitePop.shownAt
        val left = InvitePop.left()
        val slide = minOf(1f, since / 260f, (left * InvitePop.SHOW_MS) / 220f).coerceIn(0f, 1f)
        val h = 76 * d
        val top = y0 - (1f - slide) * (y0 + h - a.top)
        val r = RectF(x0, top, x0 + w, top + h)
        panel(c, r, d, t, outline = true)
        // their avatar, or their initial
        val cx = r.left + 14 * d + 24 * d; val cy = r.centerY()
        val av = try { Avatars.ofFriend(i.fid) } catch (_: Exception) { null }
        if (av != null && av.first.id.isNotBlank()) Avatars.draw(c, av.first, av.second, cx, cy, 24 * d)
        else {
            fill.color = Color.rgb(77, 171, 247); c.drawCircle(cx, cy, 24 * d, fill)
            text.textAlign = Paint.Align.CENTER; text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 20 * d; text.color = Color.WHITE
            c.drawText(i.from.trim().take(1).uppercase().ifBlank { "?" }, cx, cy + 7 * d, text)
        }
        val join = RectF(r.right - 112 * d, r.top + 12 * d, r.right - 12 * d, r.bottom - 12 * d)
        val no = RectF(join.left - 118 * d, join.top, join.left - 10 * d, join.bottom)
        text.textAlign = Paint.Align.LEFT; text.typeface = Typeface.DEFAULT_BOLD
        val tx = cx + 36 * d; val tw = no.left - tx - 10 * d
        text.textSize = 19 * d; text.color = Ui.ink(t)
        c.drawText(ellipsize(gr.gamemap.L.t("${i.from} invites you"), tw), tx, r.top + 33 * d, text)
        text.textSize = 15 * d; text.color = withAlpha(Ui.ink(t), 170); text.typeface = t.typeface
        c.drawText(ellipsize("🏁 " + (if (i.dest.isNotBlank()) gr.gamemap.L.t("Convoy to ${i.dest}") else gr.gamemap.L.t("A convoy")), tw), tx, r.top + 57 * d, text)
        // Not now / Join
        fill.color = withAlpha(Ui.ink(t), 40); c.drawRoundRect(no, 12 * d, 12 * d, fill)
        text.textAlign = Paint.Align.CENTER; text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 17 * d; text.color = Ui.ink(t)
        c.drawText(gr.gamemap.L.t("Not now"), no.centerX(), no.centerY() + 6 * d, text)
        fill.color = t.route; c.drawRoundRect(join, 12 * d, 12 * d, fill)
        text.color = if (Ui.contrast(Color.WHITE, t.route) >= 3) Color.WHITE else Color.BLACK
        c.drawText(gr.gamemap.L.t("Join"), join.centerX(), join.centerY() + 6 * d, text)
        // the 10 s running down
        fill.color = t.route
        c.drawRect(r.left + 10 * d, r.bottom - 4 * d, r.left + 10 * d + (r.width() - 20 * d) * left, r.bottom - 1.5f * d, fill)
        text.textAlign = Paint.Align.LEFT; text.typeface = t.typeface
        carHits += no to "inv:no"; carHits += join to "inv:join"
    }

    private fun drawCarProposal(c: Canvas, a: Rect, d: Float, t: Theme, p: Convoy.Proposal) {
        val w = minOf(440 * d, a.width() - 40 * d)
        val x = a.centerX() - w / 2
        val y = maxOf(bannerBottom, a.top.toFloat()) + 10 * d
        val r = RectF(x, y, x + w, y + 64 * d)
        panel(c, r, d, t, outline = true)
        text.textAlign = Paint.Align.LEFT; text.typeface = Typeface.DEFAULT_BOLD
        text.textSize = 12 * d; text.color = Ui.onPanel(t, t.route, 3.0)
        c.drawText(ellipsize(gr.gamemap.L.t(if (p.place == null) "${p.from.uppercase()} REMOVED THE DESTINATION" else "${p.from.uppercase()} CHOSE WHERE THE CONVOY GOES"), w - 170 * d), r.left + 14 * d, r.top + 22 * d, text)
        text.textSize = 17 * d; text.color = Ui.ink(t)
        c.drawText(ellipsize(p.place?.name ?: gr.gamemap.L.t("Just share where we all are"), w - 170 * d), r.left + 14 * d, r.top + 47 * d, text)
        val go = RectF(r.right - 150 * d, r.top + 10 * d, r.right - 62 * d, r.bottom - 10 * d)
        fill.color = t.route; c.drawRoundRect(go, 12 * d, 12 * d, fill)
        text.textAlign = Paint.Align.CENTER; text.textSize = 17 * d
        text.color = if (Ui.contrast(Color.WHITE, t.route) >= 3) Color.WHITE else Color.BLACK
        c.drawText(gr.gamemap.L.t(if (p.place == null) "OK" else "Go"), go.centerX(), go.centerY() + 6 * d, text)
        val no = RectF(r.right - 54 * d, r.top + 10 * d, r.right - 10 * d, r.bottom - 10 * d)
        fill.color = withAlpha(Ui.ink(t), 40); c.drawRoundRect(no, 12 * d, 12 * d, fill)
        text.color = Ui.ink(t); c.drawText("✕", no.centerX(), no.centerY() + 6 * d, text)
        text.textAlign = Paint.Align.LEFT; text.typeface = t.typeface
        carHits += go to "convoy:accept"; carHits += no to "convoy:decline"
        bannerBottom = r.bottom; bannerRight = r.right
    }

    /**
     * Car: the convoy lobby on the map. Where to, who's in (ready ✓ / joining / not ready), + Invite
     * (the car's Friends list) and the big button: START for the host (or anyone alone), READY UP
     * for the others; once ready, it shows who we're waiting for (tap to take it back).
     */
    private fun drawCarLobby(c: Canvas, a: Rect, d: Float, t: Theme) {
        val dest = Convoy.destination ?: return
        val lobby = Convoy.lobby()
        val others = Convoy.members.values.filter { it.state != "left" && !it.sharing }.sortedBy { it.name.lowercase() }.take(4)
        val w = minOf(420 * d, a.width() * 0.42f)
        val rowH = 30 * d
        val r = RectF(a.left + 12 * d, maxOf(bannerBottom, a.top.toFloat()) + 10 * d, a.left + 12 * d + w, 0f)
        r.bottom = r.top + 64 * d + (others.size + 1) * rowH + 66 * d
        panel(c, r, d, t, outline = true)
        val ink = Ui.ink(t); val accentInk = Ui.onPanel(t, t.route, 3.0)
        text.textAlign = Paint.Align.LEFT; text.typeface = Typeface.DEFAULT_BOLD
        // the steps (3 · Who lit), then where and how with Change (back to step 2)
        text.textSize = 12 * d; text.color = accentInk
        c.drawText("1 · " + gr.gamemap.L.t("WHERE") + "   2 · " + gr.gamemap.L.t("HOW") + "   3 · " + gr.gamemap.L.t("WHO") + "  ·  " + (Convoy.pretty() ?: ""), r.left + 16 * d, r.top + 24 * d, text)
        val racing = !Convoy.meAlong
        text.textSize = 18 * d; text.color = ink
        val change = gr.gamemap.L.t("Change")
        text.textSize = 15 * d; val cw = text.measureText(change)
        text.textSize = 18 * d
        c.drawText(ellipsize((if (racing) "🏁 " + gr.gamemap.L.t("Race to ${dest.name}") else "🚗 " + gr.gamemap.L.t("Together to ${dest.name}")), w - 48 * d - cw), r.left + 16 * d, r.top + 50 * d, text)
        if (!Convoy.counting) {
            text.textAlign = Paint.Align.RIGHT; text.textSize = 15 * d; text.color = accentInk
            c.drawText(change, r.right - 16 * d, r.top + 50 * d, text)
            carHits += RectF(r.right - 28 * d - cw, r.top + 28 * d, r.right - 4 * d, r.top + 62 * d) to "convoy:how"
            text.textAlign = Paint.Align.LEFT
        }
        val ok = Color.rgb(64, 200, 120); val wait = Color.rgb(255, 176, 60)
        fun row(i: Int, name: String, state: String, col: Int, mode: String = "") {
            val y = r.top + 64 * d + i * rowH + rowH / 2
            fill.color = col; c.drawCircle(r.left + 22 * d, y, 6 * d, fill)
            text.textAlign = Paint.Align.LEFT; text.textSize = 16 * d; text.color = ink
            c.drawText(ellipsize(name, w * 0.34f), r.left + 38 * d, y + 5.5f * d, text)
            // how each one drives
            if (mode.isNotEmpty()) { text.textSize = 13.5f * d; text.color = Ui.dim(t, 200); c.drawText(gr.gamemap.L.t(mode), r.left + 38 * d + w * 0.36f, y + 5 * d, text) }
            text.textAlign = Paint.Align.RIGHT; text.textSize = 14 * d; text.color = col
            c.drawText(gr.gamemap.L.t(state), r.right - 16 * d, y + 5 * d, text)
        }
        val meState = if (Convoy.meReady) "READY ✓" else if (Convoy.host) "host" else "not ready"
        row(0, gr.gamemap.L.t(if (Convoy.host) "You (host)" else "You"), meState, if (Convoy.meReady) ok else wait, if (Convoy.meAlong) "🚗 Together" else "🏁 Racing")
        others.forEachIndexed { i, m ->
            val st = when { m.stale -> "no signal"; m.ready -> "READY ✓"; m.state == "ready" && m in lobby -> "not ready"; m.state == "driving" -> "driving"; else -> "joining…" }
            row(i + 1, m.name, st, if (m.ready) ok else if (m.stale) Color.GRAY else wait, if (m.along) "🚗 Together" else "🏁 Racing")
        }
        // buttons
        val by = r.bottom - 58 * d
        val inv = RectF(r.left + 12 * d, by, r.left + 12 * d + (w - 36 * d) * 0.4f, by + 46 * d)
        stroke.color = t.route; stroke.strokeWidth = 1.6f * d; c.drawRoundRect(inv, 12 * d, 12 * d, stroke)
        text.textAlign = Paint.Align.CENTER; text.textSize = 16 * d; text.color = accentInk
        c.drawText("+ " + gr.gamemap.L.t("Invite"), inv.centerX(), inv.centerY() + 6 * d, text)
        carHits += inv to "friends"
        val go = RectF(inv.right + 12 * d, by, r.right - 12 * d, by + 46 * d)
        val waiting = lobby.filter { !it.ready }
        val label = when {
            lobby.isEmpty() -> "START"
            Convoy.meReady -> if (waiting.isEmpty()) "✓ Everyone's ready" else "✓ Waiting for ${waiting.size}"
            Convoy.host -> "START"
            else -> "READY UP"
        }
        val done = label.startsWith("✓")
        fill.color = if (done) withAlpha(ok, 60) else t.route; c.drawRoundRect(go, 12 * d, 12 * d, fill)
        text.textSize = if (done) 15 * d else 18 * d
        text.color = if (done) ink else if (Ui.contrast(Color.WHITE, t.route) >= 3) Color.WHITE else Color.BLACK
        c.drawText(ellipsize(gr.gamemap.L.t(label), go.width() - 16 * d), go.centerX(), go.centerY() + 6 * d, text)
        carHits += go to "convoy:go"
        text.textAlign = Paint.Align.LEFT; text.typeface = t.typeface
        bannerBottom = r.bottom; bannerRight = r.right
    }

    /**
     * Friends drawn on the car map in the style (instead of Android Auto's plain list): null closed,
     * "list" your friends, "convoy" the convoy you're in. ‹ goes back one step.
     */
    @Volatile var carFriends: String? = null

    private fun drawCarFriends(c: Canvas, a: Rect, d: Float, t: Theme) {
        val F = Friends; val C = Convoy; val L = gr.gamemap.L
        val ink = Ui.ink(t); val dimInk = withAlpha(ink, 170); val accentInk = Ui.onPanel(t, t.route, 3.0)
        val w = minOf(560 * d, a.width() * 0.5f)
        val top = maxOf(bannerBottom, a.top.toFloat()) + 10 * d
        val r = RectF(a.left + 12 * d, top, a.left + 12 * d + w, a.bottom - 12 * d)
        panel(c, r, d, t, outline = true)
        val pad = 14 * d
        text.textAlign = Paint.Align.LEFT
        // header: ‹, the title, one button on the right
        val back = RectF(r.left + pad, r.top + pad, r.left + pad + 48 * d, r.top + pad + 48 * d)
        glassButton(c, back, d, t)
        text.textAlign = Paint.Align.CENTER; text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 26 * d; text.color = ink
        c.drawText("‹", back.centerX(), back.centerY() + 9 * d, text)
        carHits += back to "fp:back"
        fun button(rr: RectF, label: String, id: String, fillCol: Int? = null, inkCol: Int = ink) {
            glassButton(c, rr, d, t, fillCol)
            text.textAlign = Paint.Align.CENTER; text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 16 * d; text.color = inkCol
            c.drawText(ellipsize(label, rr.width() - 16 * d), rr.centerX(), rr.centerY() + 6 * d, text)
            carHits += rr to id
        }
        val onRoute = if (Ui.contrast(Color.WHITE, t.route) >= 3) Color.WHITE else Color.BLACK
        val convoyView = carFriends == "convoy" && C.active
        val title = if (convoyView) L.t("Convoy") + (C.pretty()?.let { " · $it" } ?: "") else L.t("Friends")
        val headBtn = when {
            convoyView -> L.t("Message") to "fp:msg"
            C.active -> L.t("Convoy") + " ›" to "fp:convoy"
            else -> L.t("Start a convoy") to "fp:start"
        }
        text.textSize = 16 * d; text.typeface = Typeface.DEFAULT_BOLD
        val hbW = text.measureText(headBtn.first) + 36 * d
        val hb = RectF(r.right - pad - hbW, back.top, r.right - pad, back.bottom)
        button(hb, headBtn.first, headBtn.second, t.route, onRoute)
        text.textAlign = Paint.Align.LEFT; text.typeface = t.typeface; text.textSize = 22 * d; text.color = ink
        c.drawText(ellipsize(title, hb.left - back.right - 24 * d), back.right + 12 * d, back.centerY() + 8 * d, text)
        var y = back.bottom + 12 * d
        val rowH = 60 * d
        fun section(label: String) {
            text.textAlign = Paint.Align.LEFT; text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 12.5f * d; text.color = dimInk
            text.letterSpacing = 0.1f
            c.drawText(label.uppercase(), r.left + pad + 2 * d, y + 14 * d, text)
            text.letterSpacing = 0f
            y += 22 * d
        }
        fun row(name: String, sub: String, seed: String, btn: Pair<String, String>?, rowId: String?, btnFill: Int? = null, btnInk: Int = ink) {
            val rr = RectF(r.left + pad, y, r.right - pad, y + rowH - 8 * d)
            fill.color = withAlpha(ink, 22); c.drawRoundRect(rr, 10 * d, 10 * d, fill)
            if (rowId != null) carHits += rr to rowId
            val cx = rr.left + 30 * d; val cy = rr.centerY()
            val av = Avatars.ofFriend(seed)
            if (av != null) Avatars.draw(c, av.first, av.second, cx, cy, 19 * d)
            else {
                fill.color = Color.HSVToColor(floatArrayOf((seed.hashCode() and 0xffff) % 360f, 0.55f, 0.78f))
                c.drawCircle(cx, cy, 19 * d, fill)
                text.textAlign = Paint.Align.CENTER; text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 17 * d; text.color = Color.WHITE
                c.drawText(name.take(1).uppercase(), cx, cy + 6 * d, text)
            }
            var bw = 0f
            btn?.let { (label, id) ->
                text.textSize = 16 * d
                bw = text.measureText(label) + 30 * d
                button(RectF(rr.right - 8 * d - bw, rr.top + 6 * d, rr.right - 8 * d, rr.bottom - 6 * d), label, id, btnFill, btnInk)
            }
            val tx = cx + 30 * d; val tw = rr.right - tx - bw - 18 * d
            text.textAlign = Paint.Align.LEFT; text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 17 * d; text.color = ink
            c.drawText(ellipsize(name, tw), tx, cy - 2 * d, text)
            text.typeface = Typeface.DEFAULT; text.textSize = 14 * d; text.color = dimInk
            c.drawText(ellipsize(sub, tw), tx, cy + 17 * d, text)
            y += rowH
        }
        val room = { r.bottom - pad - y }
        if (convoyView) {
            // the two big ones: drive there, leave
            val dest = C.destination
            val bh = 50 * d
            val leaveW = (r.width() - 2 * pad) * 0.36f
            if (dest != null && !NavEngine.active) {
                button(RectF(r.left + pad, y, r.right - pad - leaveW - 10 * d, y + bh), "▶ " + L.t("Drive to ${dest.name}"), "fp:drive", t.route, onRoute)
                button(RectF(r.right - pad - leaveW, y, r.right - pad, y + bh), L.t("Leave"), "fp:leave", Color.rgb(229, 72, 77), Color.WHITE)
            } else button(RectF(r.left + pad, y, r.right - pad, y + bh), L.t("Leave the convoy"), "fp:leave", Color.rgb(229, 72, 77), Color.WHITE)
            y += bh + 12 * d
            val members = C.members.values.filter { it.state != "left" && !it.sharing }.sortedBy { it.name.lowercase() }
            section(L.t("In your convoy"))
            if (members.isEmpty()) {
                text.textAlign = Paint.Align.LEFT; text.typeface = Typeface.DEFAULT; text.textSize = 15 * d; text.color = dimInk
                c.drawText(L.t("Nobody else yet: invite friends from Friends"), r.left + pad + 2 * d, y + 18 * d, text)
            }
            val fit = (room() / rowH).toInt().coerceAtLeast(0)
            members.take(fit).forEach { m ->
                val st = when { m.stale -> L.t("no signal"); m.lostSecs > 0 -> L.t(C.lostText(m.lostSecs)); m.state == "arrived" -> L.t("arrived"); m.state == "ready" -> L.t(if (m.ready) "ready ✓" else "joined"); else -> L.t("${m.kmh} km/h") }
                row(m.name, st + " · " + L.t("tap to see on the map"), m.id, null, "fp:member:${m.id}")
            }
            text.textAlign = Paint.Align.LEFT; text.typeface = t.typeface
            return
        }
        if (!F.ready || !F.core.started) {
            text.textAlign = Paint.Align.LEFT; text.typeface = Typeface.DEFAULT; text.textSize = 15 * d; text.color = dimInk
            c.drawText(L.t("Add friends on your phone: You › Friends"), r.left + pad, y + 20 * d, text)
            text.typeface = t.typeface
            return
        }
        val core = F.core
        // invites to me, then online friends, then the rest; as many as fit, the rest behind "+N more"
        val inv = core.invites.filter { it.convoy != C.code }
        val fr = core.friends.sortedWith(compareByDescending<FriendCore.Friend> { core.isOnline(it.fid) }.thenBy { core.shownName(it).lowercase() })
        val online = fr.filter { core.isOnline(it.fid) }; val offline = fr.filter { !core.isOnline(it.fid) }
        val total = inv.size + fr.size
        if (total == 0) {
            text.textAlign = Paint.Align.LEFT; text.typeface = Typeface.DEFAULT; text.textSize = 15 * d; text.color = dimInk
            c.drawText(L.t("Add friends on your phone: You › Friends"), r.left + pad, y + 20 * d, text)
            text.typeface = t.typeface
            return
        }
        val sections = (if (inv.isNotEmpty()) 1 else 0) + (if (online.isNotEmpty()) 1 else 0) + (if (offline.isNotEmpty()) 1 else 0)
        // (the last row may use the panel's bottom padding: one more friend instead of empty space)
        var fit = ((room() - sections * 22 * d) / rowH + 0.2f).toInt()
        if (fit < total) fit = ((room() - sections * 22 * d - 44 * d) / rowH + 0.2f).toInt() // leave room for "+N more"
        fit = fit.coerceAtLeast(1)
        var shown = 0
        if (inv.isNotEmpty() && shown < fit) {
            section(L.t("Invites"))
            for (i in inv) { if (shown >= fit) break
                row(L.t("${i.from} invites you"), if (i.dest.isNotBlank()) L.t("Convoy to ${i.dest}") else L.t("No destination yet"), i.from, L.t("Join") to "fp:join:${i.convoy}", null, t.route, onRoute); shown++ }
        }
        fun friendRow(f: FriendCore.Friend) {
            val sent = C.code?.let { core.sentTo(f.fid, it) }
            val btn = if (sent != null) L.t("Invited ✓") to "fp:none" else "+ " + L.t("Invite") to "fp:inv:${f.fid}"
            row(core.shownName(f), Friends.activityText(f.fid), f.fid, btn, null)
        }
        if (online.isNotEmpty() && shown < fit) { section(L.t("Online")); for (f in online) { if (shown >= fit) break; friendRow(f); shown++ } }
        if (offline.isNotEmpty() && shown < fit) { section(L.t("Not online")); for (f in offline) { if (shown >= fit) break; friendRow(f); shown++ } }
        if (shown < total) {
            val more = RectF(r.left + pad, y + 2 * d, r.right - pad, y + 42 * d)
            button(more, L.t("+${total - shown} more"), "fp:more")
        }
        text.textAlign = Paint.Align.LEFT; text.typeface = t.typeface
    }

    /** At the convoy start after pressing Ready: who we're still waiting for. */
    private fun drawLobbyChip(c: Canvas, a: Rect, d: Float, t: Theme) {
        val lobby = Convoy.lobby()
        if (lobby.isEmpty()) return
        val waiting = lobby.filter { !it.ready }.map { it.name }
        val label = gr.gamemap.L.t("Ready ${lobby.count { it.ready } + 1}/${lobby.size + 1}") +
            (if (waiting.isEmpty()) "" else " · " + gr.gamemap.L.t("waiting for ") + waiting.joinToString(", "))
        text.textAlign = Paint.Align.LEFT; text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 13.5f * d
        val shown = ellipsize(label, a.width() - 80 * d)
        val w = text.measureText(shown) + 46 * d
        val x = if (car) a.left + 12 * d else a.centerX() - w / 2
        val y = maxOf(bannerBottom, a.top.toFloat()) + 8 * d
        val r = RectF(x, y, x + w, y + 32 * d)
        fill.color = Color.argb(235, 40, 44, 52); c.drawRoundRect(r, 16 * d, 16 * d, fill)
        stroke.color = Color.rgb(47, 210, 122); stroke.strokeWidth = 1.5f * d; c.drawRoundRect(r, 16 * d, 16 * d, stroke)
        text.color = Color.WHITE
        c.drawText("🏁", r.left + 10 * d, r.centerY() + 5 * d, text)
        c.drawText(shown, r.left + 34 * d, r.centerY() + 5 * d, text)
        text.typeface = t.typeface
        bannerBottom = r.bottom; bannerRight = r.right
    }

    /** Tunnel mode: a small chip saying the position is estimated. */
    @Volatile private var roadName = ""
    private var roadAt = 0L

    /** The road you're on, small at the bottom of the map (hidden on unnamed roads). */
    /** Where the road name was drawn last (for tests: it must never cover the speed). */
    internal val roadNameRect = RectF()

    /** The road you're on, in a small label just under your arrow (under the Weak GPS note when that shows). */
    private fun drawCurrentRoad(c: Canvas, a: Rect, d: Float, t: Theme, px: Float, py: Float, weak: Boolean) {
        val now = System.currentTimeMillis()
        if (now - roadAt > 1000) { roadAt = now; roadName = try { NavEngine.currentRoad() } catch (_: Exception) { "" } }
        val name = roadName
        if (name.isBlank()) return
        // slim, so it hides as little of the map's own street names as it can
        text.textAlign = Paint.Align.CENTER; text.textSize = (if (car) 15f else 13.5f) * d
        val tf = text.typeface; text.typeface = t.typeface
        val shown = ellipsize(name, minOf(360 * d, a.width() * 0.5f))
        val w = text.measureText(shown) + 22 * d
        val h = (if (car) 29 else 25) * d
        val top = (py + (if (weak) 72 else 32) * d).coerceAtMost(a.bottom - h - 4 * d)
        val left = (px - w / 2).coerceIn(a.left + 6 * d, a.right - 6 * d - w)
        val r = RectF(left, top, left + w, top + h)
        roadNameRect.set(r)
        panel(c, r, d, t, outline = false)
        text.color = Ui.ink(t)
        c.drawText(shown, r.centerX(), r.centerY() + text.textSize * 0.35f, text)
        text.typeface = tf; text.textAlign = Paint.Align.LEFT
    }

    /** Weak GPS: a dashed amber ring round the arrow and a small note under it. */
    private fun drawWeakGps(c: Canvas, x: Float, y: Float, d: Float, a: Rect) {
        val amber = Color.rgb(255, 179, 71)
        stroke.color = amber; stroke.strokeWidth = 1.6f * d
        stroke.pathEffect = android.graphics.DashPathEffect(floatArrayOf(6 * d, 5 * d), 0f)
        c.drawCircle(x, y, 30 * d, stroke)
        stroke.pathEffect = null
        fill.color = Color.argb(46, 255, 179, 71); c.drawCircle(x, y, 30 * d, fill)
        val label = gr.gamemap.L.t("Weak GPS · position may be off")
        val tf = text.typeface
        text.textAlign = Paint.Align.LEFT; text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 12.5f * d
        val w = text.measureText(label) + 26 * d
        val left = (x - w / 2).coerceIn(a.left + 6 * d, a.right - 6 * d - w)
        val top = y + 38 * d
        val r = RectF(left, top, left + w, top + 26 * d)
        fill.color = Color.argb(235, 30, 26, 44); c.drawRoundRect(r, 13 * d, 13 * d, fill)
        stroke.color = amber; stroke.strokeWidth = 1.2f * d; c.drawRoundRect(r, 13 * d, 13 * d, stroke)
        text.color = amber; c.drawText(label, r.left + 13 * d, r.centerY() + 4.3f * d, text)
        text.typeface = tf
    }

    private fun drawNoGps(c: Canvas, a: Rect, d: Float, t: Theme, message: String = "No GPS: estimating") {
        val label = gr.gamemap.L.t(message)
        text.textAlign = Paint.Align.LEFT; text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 13 * d
        val w = text.measureText(label) + 34 * d
        val x = if (car) a.left + 12 * d else a.centerX() - w / 2
        val y = maxOf(bannerBottom, a.top.toFloat()) + 8 * d
        val r = RectF(x, y, x + w, y + 30 * d)
        fill.color = Color.argb(235, 40, 44, 52); c.drawRoundRect(r, 15 * d, 15 * d, fill)
        fill.color = Color.rgb(250, 176, 5); c.drawCircle(r.left + 15 * d, r.centerY(), 4.5f * d, fill)
        text.color = Color.WHITE; c.drawText(label, r.left + 25 * d, r.centerY() + 4.5f * d, text)
        text.typeface = t.typeface
        bannerBottom = r.bottom; bannerRight = r.right
    }

    /** Where the phone's convoy strip is (taps open the list). */
    val convoyStrip = RectF()
    private var carConvoyBottom = 0f
    private val carConvoyRect = RectF()

    /** Where the arrow sits (a share of the height), easing to a new place instead of jumping. */
    private var shownArrowFrac = -1f
    private fun arrowFrac(target: Float): Float {
        if (shownArrowFrac < 0f || Math.abs(target - shownArrowFrac) < 0.002f) shownArrowFrac = target
        else { shownArrowFrac += (target - shownArrowFrac) * 0.06f; animating = true }
        return shownArrowFrac
    }
    internal fun carConvoyRectForTest() = RectF(carConvoyRect)
    private var carLobbyShown = false

    /** The others in the convoy: an arrow in their colour, name and speed. */
    private var friendGliding = false

    private fun drawConvoyMembers(c: Canvas, cam: Camera, a: Rect, d: Float, bearing: Float) {
        val now = System.currentTimeMillis()
        val hits = ArrayList<Pair<RectF, Convoy.Member>>()
        friendGliding = false
        for (m in Convoy.members.values) {
            if (m.state == "left") continue
            if (car && m.sharing) continue // only sharing their location: not on the car screen
            val p = m.display(now) ?: continue
            val (x, y) = toScreen(p, cam)
            if (x < -40 * d || y < -40 * d || x > a.right + 40 * d || y > a.bottom + 40 * d) continue
            if (m.moving) friendGliding = true
            c.save(); c.translate(x, y); c.rotate(m.shownHeading - bearing)
            val s = 12 * d
            path.reset()
            path.moveTo(0f, -s * 1.2f); path.lineTo(s * 0.85f, s * 0.85f); path.lineTo(0f, s * 0.4f); path.lineTo(-s * 0.85f, s * 0.85f); path.close()
            // signal lost for a while: the arrow fades (it comes back with their next update)
            val lost = m.lostSecs
            val fade = if (m.stale) 255 else if (lost > 0) 110 else 255
            fill.color = if (m.stale) Color.GRAY else m.color; fill.alpha = fade; c.drawPath(path, fill)
            stroke.color = Color.WHITE; stroke.alpha = fade; stroke.strokeWidth = 2.2f * d; c.drawPath(path, stroke)
            fill.alpha = 255; stroke.alpha = 255
            c.restore()
            // name tag
            val label = "${m.name}  ${if (m.state == "arrived") "✓" else if (lost > 0 && !m.stale) gr.gamemap.L.t(Convoy.lostText(lost)) else m.kmh.toString()}"
            text.textAlign = Paint.Align.LEFT; text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 12.5f * d
            val w = text.measureText(label) + 14 * d
            val r = RectF(x + 12 * d, y - 26 * d, x + 12 * d + w, y - 6 * d)
            // keep the tag on screen: flip to the left side near the right edge, below near the top
            if (r.right > a.right - 6 * d) r.offset(-(w + 24 * d), 0f)
            if (r.left < a.left + 6 * d) r.offset(a.left + 6 * d - r.left, 0f)
            if (r.top < a.top + 6 * d) r.offset(0f, 32 * d)
            fill.color = if (m.stale) Color.GRAY else m.color; c.drawRoundRect(r, 10 * d, 10 * d, fill)
            text.color = Color.rgb(17, 20, 24)
            c.drawText(label, r.left + 7 * d, r.centerY() + 4.5f * d, text)
            // taps: the arrow (generously) and the name tag
            hits += RectF(x - 24 * d, y - 24 * d, x + 24 * d, y + 24 * d) to m
            hits += RectF(r).apply { inset(-6 * d, -6 * d) } to m
        }
        friendHits = hits
    }

    @Volatile private var friendHits: List<Pair<RectF, Convoy.Member>> = emptyList()

    /** The convoy friend under a screen point (their arrow or name tag), if any. */
    fun friendAt(x: Float, y: Float): Convoy.Member? = friendHits.lastOrNull { it.first.contains(x, y) }?.second

    /** A quick message from the convoy ("Nikos: Need fuel"), for 8 s under the convoy box or strip. */
    private fun drawConvoyMessage(c: Canvas, a: Rect, d: Float, t: Theme) {
        val (who, key, _) = Convoy.showMessage() ?: return
        val label = "$who: ${Convoy.messageText(key)}"
        text.textAlign = Paint.Align.LEFT; text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 15 * d
        val w = text.measureText(label) + 44 * d
        val top = maxOf(bannerBottom, convoyStrip.bottom, carConvoyBottom) + 8 * d
        val left = if (car) a.left + 12 * d else a.exactCenterX() - w / 2
        val r = RectF(left, top, left + w, top + 36 * d)
        panel(c, r, d, t, outline = true)
        fill.color = t.route; c.drawCircle(r.left + 18 * d, r.centerY(), 6 * d, fill)
        text.color = Ui.ink(t); c.drawText(label, r.left + 32 * d, r.centerY() + 5 * d, text)
        bannerBottom = r.bottom; bannerRight = r.right
        animating = true // it goes away by itself after 8 s
    }

    /**
     * Phone: the convoy button under the turn card: my place in the race (or "Together"), who's
     * in it, and the nearest gap. A tap opens the convoy panel.
     */
    private fun drawConvoyStrip(c: Canvas, a: Rect, d: Float, t: Theme) {
        val rows = Convoy.rows(t.route)
        val racing = !Convoy.meAlong && !Convoy.meSharing && rows.any { !it.me && !it.along }
        val racers = rows.filter { !it.along }
        val place = racers.indexOfFirst { it.me } + 1
        val head = when {
            racing && place > 0 -> "🏁 " + gr.gamemap.L.t(when (place) { 1 -> "1st"; 2 -> "2nd"; 3 -> "3rd"; else -> "${place}th" })
            else -> "🚗 " + gr.gamemap.L.t("Together")
        }
        // the nearest one (by the gap along the route), else the first other
        val others = rows.filter { !it.me }
        // in a race: the nearest racer (the ones driving together don't count for the gap)
        val near = others.filter { it.state != "arrived" && !it.stale && (!racing || !it.along) }.minByOrNull { Convoy.gapTo(it)?.let { g -> Math.abs(g) } ?: Double.MAX_VALUE } ?: others.firstOrNull()
        val gap = near?.let { it.name + " " + gr.gamemap.L.t(Convoy.relative(it)) }.orEmpty()
        val h = 40 * d; val pad = 12 * d
        text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 14 * d
        val headW = text.measureText(head)
        val av = 24 * d; val avStep = 18 * d
        val shown = others.take(4)
        val avW = if (shown.isEmpty()) 0f else av + (shown.size - 1) * avStep
        text.typeface = Typeface.DEFAULT; text.textSize = 13 * d
        val maxW = a.width() - 24 * d
        val fixed = pad + headW + 17 * d + avW + (if (avW > 0) 8 * d else 0f) + pad
        val gapText = ellipsize(gap, (maxW - fixed).coerceAtLeast(0f))
        val w = minOf(maxW, fixed + text.measureText(gapText))
        val r = RectF(a.left + 12 * d, bannerBottom + 8 * d, a.left + 12 * d + w, bannerBottom + 8 * d + h)
        panel(c, r, d, t, outline = true)
        val cy = r.centerY()
        var x = r.left + pad
        text.textAlign = Paint.Align.LEFT; text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 14 * d
        text.color = Ui.onPanel(t, t.route, 3.0)
        c.drawText(head, x, cy + 5 * d, text); x += headW + 8 * d
        fill.color = Ui.dim(t, 70); c.drawRect(x, cy - 10 * d, x + 1 * d, cy + 10 * d, fill); x += 9 * d
        // avatars, overlapping a little
        for ((i, row) in shown.withIndex()) {
            val cx = x + av / 2 + i * avStep
            fill.color = if (row.stale) Color.GRAY else row.color
            c.drawCircle(cx, cy, av / 2, fill)
            stroke.color = Ui.panel(t); stroke.strokeWidth = 2 * d; c.drawCircle(cx, cy, av / 2, stroke)
            text.textAlign = Paint.Align.CENTER; text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 11 * d; text.color = Color.rgb(17, 20, 24)
            c.drawText(row.name.trim().split(" ").last().take(1).uppercase().ifBlank { "?" }, cx, cy + 4 * d, text)
        }
        x += avW + (if (avW > 0) 8 * d else 0f)
        text.textAlign = Paint.Align.LEFT; text.typeface = Typeface.DEFAULT; text.textSize = 13 * d; text.color = Ui.dim(t, 210)
        c.drawText(gapText, x, cy + 5 * d, text)
        text.typeface = t.typeface
        convoyStrip.set(r)
        bannerBottom = r.bottom; bannerRight = maxOf(bannerRight, r.right)
    }

    /** Car: the convoy box under the turn box (name · how far ahead, % of the race, speed, arrival). */
    private fun drawCarConvoy(c: Canvas, a: Rect, d: Float, t: Theme) {
        val rows = Convoy.rows(t.route).take(5)
        val w = minOf(400 * d, a.width() - 28f * d)
        val rowH = 26 * d
        val r = RectF(a.left + 12 * d, bannerBottom + 8 * d, a.left + 12 * d + w, bannerBottom + 8 * d + 46 * d + rows.size * rowH)
        carConvoyBottom = r.bottom; carConvoyRect.set(r)
        panel(c, r, d, t, outline = true)
        text.textAlign = Paint.Align.LEFT; text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 11 * d
        text.color = Ui.onPanel(t, t.route, 3.0)
        c.drawText(gr.gamemap.L.t("CONVOY") + " · ${Convoy.destination?.name?.uppercase()?.take(24) ?: ""}", r.left + 12 * d, r.top + 17 * d, text)
        // progress line
        val x0 = r.left + 14 * d; val x1 = r.right - 30 * d; val ly = r.top + 31 * d
        fill.color = withAlpha(Ui.ink(t), 60); c.drawRoundRect(x0, ly - 1.5f * d, x1, ly + 1.5f * d, 2 * d, 2 * d, fill)
        text.textSize = 12 * d; text.typeface = Typeface.DEFAULT; c.drawText("🏁", x1 + 6 * d, ly + 5 * d, text)
        for (row in rows.sortedBy { if (it.me) 1 else 0 }) {
            val cx = x0 + ((x1 - x0) * row.progress).toFloat()
            if (row.along) {
                stroke.color = Ui.dim(t, 190); stroke.strokeWidth = 1.5f * d
                stroke.pathEffect = android.graphics.DashPathEffect(floatArrayOf(2.5f * d, 2f * d), 0f)
                c.drawCircle(cx, ly, 5.5f * d, stroke); stroke.pathEffect = null
                continue
            }
            fill.color = if (row.stale) Color.GRAY else row.color
            c.drawCircle(cx, ly, 6.5f * d, fill)
        }
        var y = r.top + 46 * d
        // columns from the right: arrival time, speed (with its unit), how much of the race (%)
        val etaX = r.right - 12 * d; val speedX = r.right - 70 * d; val pctX = r.right - 142 * d
        for (row in rows) {
            fill.color = if (row.stale) Color.GRAY else row.color
            c.drawCircle(r.left + 18 * d, y + 13 * d, 5 * d, fill)
            text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 15 * d; text.color = if (row.along) Ui.dim(t, 200) else Ui.ink(t)
            val name = if (row.me) gr.gamemap.L.t("You") else row.name
            c.drawText(ellipsize(name, 90 * d), r.left + 30 * d, y + 18 * d, text)
            val nw = text.measureText(ellipsize(name, 90 * d))
            text.typeface = Typeface.DEFAULT; text.textSize = 13 * d; text.color = Ui.dim(t, 190)
            // the "850 m ahead" part gives way first on a narrow screen (the numbers never do)
            c.drawText(ellipsize(gr.gamemap.L.t(" · " + Convoy.relative(row)), pctX - 48 * d - (r.left + 30 * d + nw)), r.left + 30 * d + nw, y + 18 * d, text)
            text.textAlign = Paint.Align.RIGHT
            // % of the race: the race figure, in bold and the route's colour (✓ arrived, – not racing)
            val known = row.me || row.left >= 0
            val pct = (row.progress * 100).toInt().coerceIn(0, 99)
            if (row.along) {
                // driving along: no race figure
                text.typeface = Typeface.DEFAULT; text.textSize = 12 * d; text.color = Ui.dim(t, 170)
                c.drawText(gr.gamemap.L.t("along"), pctX, y + 18 * d, text)
            } else {
                text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 16 * d
                text.color = if (row.state == "arrived") Ui.onPanel(t, Color.rgb(47, 210, 122), 3.0) else Ui.onPanel(t, t.route, 3.0)
                c.drawText(when { row.state == "arrived" -> "✓"; row.state == "own" || !known -> "–"; else -> "$pct%" }, pctX, y + 18 * d, text)
            }
            // speed with a small "km/h", so the two numbers can't be mixed up
            if (row.state != "arrived") {
                text.typeface = Typeface.DEFAULT; text.textSize = 10 * d; text.color = Ui.dim(t, 170)
                val unit = if (MapState.imperial) "mph" else "km/h"
                c.drawText(unit, speedX, y + 18 * d, text)
                val uw = text.measureText(unit) + 3 * d
                text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 15 * d
                text.color = if (row.kmh == 0 && row.state == "driving") Color.rgb(255, 120, 120) else if (row.along) Ui.dim(t, 200) else Ui.ink(t)
                val v = if (MapState.imperial) (row.kmh / 1.609).toInt() else row.kmh
                c.drawText("$v", speedX - uw, y + 18 * d, text)
            }
            text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 15 * d
            text.color = Ui.onPanel(t, Color.rgb(47, 210, 122), 3.0)
            c.drawText(if (row.eta > 0) NavEngine.clock(row.eta) else "–", etaX, y + 18 * d, text)
            text.textAlign = Paint.Align.LEFT
            carHits += RectF(r.left, y, r.right, y + rowH) to "friend:${row.id}"
            y += rowH
        }
        text.typeface = t.typeface
        bannerBottom = r.bottom; bannerRight = r.right
    }

    /** Car: the card of a tapped place (top-left, under the turn box when navigating), with Go. */
    private fun drawCarPlace(c: Canvas, a: Rect, d: Float, t: Theme) {
        val pl = selectedPlace ?: return
        val w = minOf(420 * d, a.width() - 28f * d)
        val top = if (NavEngine.active) bannerBottom + 10 * d else 12 * d
        val hasExtra = pl.extra.isNotBlank()
        val h = (if (hasExtra) 186 else 160) * d
        val box = RectF(a.left + 14 * d, top, a.left + 14 * d + w, top + h)
        panel(c, box, d, t, outline = true)
        val ink = Ui.ink(t)
        text.textAlign = Paint.Align.LEFT
        text.typeface = Typeface.DEFAULT; text.textSize = 24 * d
        c.drawText(pl.icon, box.left + 18 * d, box.top + 42 * d, text)
        text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 21 * d; text.color = ink
        c.drawText(ellipsize(pl.name, w - 120 * d), box.left + 58 * d, box.top + 34 * d, text)
        val near = LocationTracker.position
        val sub = listOfNotNull(pl.address.takeIf { it.isNotBlank() }, near?.let { NavEngine.formatDistance(Geo.distance(it, pl.pos)) },
            Reviews.stars(pl.rating, pl.ratings)).joinToString(" · ")
        text.typeface = Typeface.DEFAULT; text.textSize = 15 * d; text.color = Ui.dim(t, 200)
        c.drawText(ellipsize(gr.gamemap.L.t(sub), w - 120 * d), box.left + 58 * d, box.top + 56 * d, text)
        if (hasExtra) {
            text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 15 * d; text.color = Ui.onPanel(t, t.route, 3.0)
            c.drawText(ellipsize(gr.gamemap.L.t(Fuel.brief(pl.extra)), w - 36 * d), box.left + 18 * d, box.top + 84 * d, text)
        }
        // ✕
        val x = RectF(box.right - 54 * d, box.top + 6 * d, box.right - 8 * d, box.top + 52 * d)
        text.textAlign = Paint.Align.CENTER; text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 22 * d; text.color = ink
        c.drawText("✕", x.centerX(), x.centerY() + 8 * d, text)
        carHits += x to "placeclose"
        // Go (or, while navigating: Add stop | Go instead)
        val navigating = NavEngine.active && !NavEngine.arrived
        val gy0 = box.bottom - 18 * d - 58 * d; val gy1 = box.bottom - 18 * d
        val go = if (navigating) RectF(box.left + 18 * d, gy0, box.centerX() - 6 * d, gy1) else RectF(box.left + 18 * d, gy0, box.right - 18 * d, gy1)
        glassButton(c, go, d, t, withAlpha(t.route, 240))
        text.textSize = 20 * d
        text.color = if (Ui.contrast(Color.WHITE, t.route) >= 3) Color.WHITE else Color.BLACK
        c.drawText(if (navigating) gr.gamemap.L.t("＋ Add stop") else gr.gamemap.L.t("Go"), go.centerX(), go.centerY() + 7 * d, text)
        carHits += go to if (navigating) "placestop" else "placego"
        if (navigating) {
            val alt = RectF(box.centerX() + 6 * d, gy0, box.right - 18 * d, gy1)
            glassButton(c, alt, d, t)
            text.color = glassInk(t); text.textSize = 18 * d
            c.drawText(gr.gamemap.L.t("Go instead"), alt.centerX(), alt.centerY() + 6 * d, text)
            carHits += alt to "placego"
        }
        text.textAlign = Paint.Align.LEFT
        text.typeface = t.typeface
    }

    /** Car: "17 min · 18:02 · 4.5 km" next to the speed box, in the theme's see-through panel. */
    private fun drawCarEta(c: Canvas, a: Rect, d: Float, t: Theme) {
        if (!NavEngine.active || NavEngine.arrived || NavEngine.loading || NavEngine.error != null) return
        val delay = NavEngine.trafficDelaySeconds / 60
        val big = NavEngine.formatDuration(NavEngine.remainingSeconds)
        val small = "${NavEngine.arrivalClock()} · ${NavEngine.formatDistance(NavEngine.remainingDistance)}" + (if (delay >= 1) " · +$delay min" else "")
        text.textAlign = Paint.Align.LEFT
        text.typeface = t.typeface
        text.textSize = 26 * d
        val w1 = text.measureText(big)
        val tf = text.typeface
        text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 15 * d
        val w2 = text.measureText(small)
        val w = maxOf(w1, w2) + 28 * d
        val h = 66 * d
        val left = a.left + 14 * d + 136 * d
        val box = RectF(left, a.bottom - 14 * d - h, left + w, a.bottom - 14 * d)
        etaRight = box.right
        panel(c, box, d, t, outline = false)
        text.typeface = tf; text.textSize = 26 * d
        text.color = Ui.onPanel(t, when { delay >= 10 -> Color.rgb(255, 110, 110); delay >= 3 -> Color.rgb(255, 184, 107); else -> Color.rgb(123, 227, 160) })
        c.drawText(gr.gamemap.L.t(big), box.left + 14 * d, box.top + 30 * d, text)
        text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 15 * d
        text.color = Ui.ink(t)
        c.drawText(gr.gamemap.L.t(small), box.left + 14 * d, box.bottom - 13 * d, text)
        text.typeface = t.typeface
    }

    /** Bottom of the turn banner on screen (for placing things below it). */
    private var bannerBottom = 0f
    /** The right edge of the turn box (or the convoy lobby…) this frame. */
    private var bannerRight = 0f
    val bannerBottomNow: Float get() = bannerBottom

    private fun drawBanner(c: Canvas, a: Rect, d: Float, t: Theme, pad: Float = 12 * d) {
        if (!NavEngine.active) return
        val step = NavEngine.currentStep
        // update 100: the pace-note turn box (every style), whenever there is a turn to show
        if (!NavEngine.arrived && !NavEngine.loading && NavEngine.error == null && step != null) { drawPaceNote(c, a, d, t, pad, step); return }
        val (title, sub) = when {
            NavEngine.arrived -> Explorer.completeTitle(t).uppercase() to NavEngine.destinationName
            NavEngine.error != null -> (NavEngine.error ?: "") to NavEngine.destinationName
            NavEngine.loading && NavEngine.rerouting -> gr.gamemap.L.t("New route…") to NavEngine.destinationName
            NavEngine.loading || step == null -> gr.gamemap.L.t("Plotting route…") to NavEngine.destinationName
            else -> {
                val st = step!!
                NavEngine.formatDistance(NavEngine.distanceToNext) to
                    (if (st.road.isNotBlank() && st.type != "arrive") Names.shown(st.road) else st.text)
            }
        }
        // the small line ("Turn left", "Keep right") only when it adds something: never a bare
        // "Continue" under a straight arrow
        val plain = step != null && (step.type in setOf("continue", "new name", "depart") || step.direction == "straight")
        val detail = if (!NavEngine.arrived && !NavEngine.loading && NavEngine.error == null && step != null && !plain &&
            step.road.isNotBlank() && step.type != "arrive") step.text.substringBefore(" onto ") else null
        val lanes = step?.lanes?.takeIf { NavEngine.distanceToNext < 900 && !NavEngine.loading }
        val mainH = if (detail != null) 96 * d else 80 * d
        val lanesH = if (lanes != null) 44 * d else 0f
        val box = RectF(a.left + pad, a.top + pad, minOf(a.right - pad, a.left + pad + 440 * d), a.top + pad + mainH + lanesH)
        panel(c, box, d, t, outline = true)

        val iconX = box.left + 42 * d; val iconY = box.top + mainH / 2
        when {
            NavEngine.arrived -> drawTurnArrow(c, iconX, iconY, 22 * d, "arrive", t)
            !NavEngine.loading && NavEngine.error == null && step != null -> drawTurnArrow(c, iconX, iconY, 22 * d, step.direction, t)
        }
        val tx = box.left + 82 * d
        val maxW = box.right - tx - 12 * d
        text.textAlign = Paint.Align.LEFT
        text.color = Ui.ink(t)
        // the title shrinks to fit the card (long words like "MISSION COMPLETE" in wide fonts)
        text.textSize = 30 * d
        val tw = text.measureText(title)
        if (tw > maxW) text.textSize = (30 * d * maxW / tw).coerceAtLeast(18 * d)
        c.drawText(ellipsize(title, maxW), tx, box.top + 36 * d, text)
        text.textSize = 18 * d
        text.typeface = if (t.serif) Typeface.create(Typeface.SERIF, Typeface.BOLD) else Typeface.DEFAULT_BOLD
        c.drawText(ellipsize(sub, maxW), tx, box.top + 62 * d, text)
        if (detail != null) {
            text.textSize = 14 * d
            text.typeface = if (t.serif) Typeface.create(Typeface.SERIF, Typeface.NORMAL) else Typeface.DEFAULT
            text.color = Ui.dim(t, 190)
            c.drawText(ellipsize(detail, maxW), tx, box.top + 84 * d, text)
        }
        text.typeface = t.typeface
        if (lanes != null) drawLanes(c, RectF(box.left, box.top + mainH, box.right, box.bottom), lanes, step?.direction ?: "straight", d, t)
        bannerBottom = box.bottom

        // "Then …" tab under the banner
        val then = NavEngine.followingStep
        if (then != null && !NavEngine.arrived) {
            text.textSize = 15 * d
            text.typeface = Typeface.DEFAULT_BOLD
            val label = ellipsize(if (then.road.isNotBlank() && then.type != "arrive") Names.shown(then.road) else then.text, box.width() - 110 * d)
            val w = text.measureText(label) + 82 * d
            val tab = RectF(box.left + 14 * d, box.bottom - 2 * d, box.left + 14 * d + w, box.bottom + 38 * d)
            panel(c, tab, d, t, outline = false)
            text.color = Ui.dim(t, 190)
            c.drawText(gr.gamemap.L.t("Then"), tab.left + 12 * d, tab.centerY() + 5.5f * d, text)
            drawTurnArrow(c, tab.left + 62 * d, tab.centerY(), 9 * d, then.direction, t)
            text.color = Ui.ink(t)
            c.drawText(label, tab.left + 76 * d, tab.centerY() + 5.5f * d, text)
            text.typeface = t.typeface
            bannerBottom = tab.bottom
        }
    }

    /**
     * The turn box, rally pace-note style, in the style's own panel, colours and letters:
     * [distance + trip km] [a sketch of the junction, the way out in the route colour]
     * [EXIT 2 / TURN RIGHT, then the road you turn into], and the next turn on a line underneath
     * when it comes soon after. Lanes, when known, under it.
     */
    private fun drawPaceNote(c: Canvas, a: Rect, d: Float, t: Theme, pad: Float, step: NavStep) {
        val then = NavEngine.followingStep
        val lanes = step.lanes?.takeIf { NavEngine.distanceToNext < 900 }
        val mainH = 96 * d
        val thenH = if (then != null) 30 * d else 0f
        val lanesH = if (lanes != null) 44 * d else 0f
        val box = RectF(a.left + pad, a.top + pad, minOf(a.right - pad, a.left + pad + 400 * d), a.top + pad + mainH + thenH + lanesH)
        panel(c, box, d, t, outline = true)
        val ink = Ui.ink(t)
        val accent = Ui.onPanel(t, t.route, 3.0)
        val line = withAlpha(ink, 60)
        val c1 = box.left + 80 * d; val c2 = c1 + 96 * d
        stroke.color = line; stroke.strokeWidth = 1 * d
        c.drawLine(c1, box.top + 8 * d, c1, box.top + mainH - 8 * d, stroke)
        c.drawLine(c2, box.top + 8 * d, c2, box.top + mainH - 8 * d, stroke)
        if (then != null || lanes != null) c.drawLine(box.left + 8 * d, box.top + mainH, box.right - 8 * d, box.top + mainH, stroke)
        // 1) distance and the trip counter
        val dist = NavEngine.formatDistance(NavEngine.distanceToNext)
        val num = dist.substringBeforeLast(' '); val unit = dist.substringAfterLast(' ', "")
        text.textAlign = Paint.Align.CENTER; text.typeface = t.typeface
        text.textSize = 32 * d; text.color = accent
        val nw = text.measureText(num); if (nw > 70 * d) text.textSize = 32 * d * 70 * d / nw
        c.drawText(num, (box.left + c1) / 2, box.top + 44 * d, text)
        text.textSize = 14 * d; text.color = ink
        c.drawText(unit, (box.left + c1) / 2, box.top + 62 * d, text)
        text.textSize = 12 * d; text.color = Ui.dim(t, 170)
        c.drawText(String.format(java.util.Locale.US, "%.2f km", NavEngine.tripSoFar / 1000), (box.left + c1) / 2, box.top + 84 * d, text)
        // 2) the junction
        drawJunction(c, (c1 + c2) / 2, box.top + mainH / 2, 78 * d, step, ink, accent, d)
        // 3) what to do, and the road you turn into
        val tx = c2 + 12 * d; val maxW = box.right - tx - 10 * d
        val roundabout = (step.type == "roundabout" || step.type == "rotary") && step.exit in 1..12
        val what = (if (roundabout) gr.gamemap.L.t("Exit ${step.exit}") else step.text.substringBefore(" onto ").substringBefore(" στην ")).uppercase()
        val road = step.road.takeIf { it.isNotBlank() && step.type != "arrive" }?.let { Names.shown(it) } ?: step.text
        text.textAlign = Paint.Align.LEFT
        text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 12 * d; text.color = Ui.dim(t, 190)
        c.drawText(ellipsize(what, maxW), tx, box.top + 30 * d, text)
        text.typeface = t.typeface; text.color = ink
        text.textSize = 26 * d
        val rw = text.measureText(road); if (rw > maxW) text.textSize = (26 * d * maxW / rw).coerceAtLeast(17 * d)
        c.drawText(ellipsize(road, maxW), tx, box.top + 64 * d, text)
        // 4) the next turn, soon after
        if (then != null) {
            val y = box.top + mainH + thenH / 2
            text.textSize = 14 * d; text.typeface = Typeface.DEFAULT_BOLD
            text.color = Ui.dim(t, 170)
            c.drawText(gr.gamemap.L.t("Then"), box.left + 14 * d, y + 5 * d, text)
            val gap = NavEngine.followingGap?.let { NavEngine.formatDistance(it) } ?: ""
            text.color = ink
            val gx = box.left + 14 * d + text.measureText(gr.gamemap.L.t("Then")) + 10 * d
            c.drawText(gap, gx, y + 5 * d, text)
            val sx = gx + text.measureText(gap) + 16 * d
            drawJunction(c, sx, y, 24 * d, then, ink, ink, d, mini = true)
            val label = (if (then.road.isNotBlank() && then.type != "arrive") Names.shown(then.road) else then.text)
            c.drawText(ellipsize(label, box.right - sx - 24 * d), sx + 16 * d, y + 5 * d, text)
        }
        text.typeface = t.typeface
        if (lanes != null) drawLanes(c, RectF(box.left, box.top + mainH + thenH, box.right, box.bottom), lanes, step.direction, d, t)
        bannerBottom = box.bottom
    }

    /** Compass angle (0 = straight on, + to the right) of the way out of a junction. */
    private fun exitAngle(s: NavStep): Float {
        if ((s.type == "roundabout" || s.type == "rotary") && s.exit in 1..12) return (90f - (s.exit - 1) * 90f).coerceAtLeast(-150f)
        val dir = s.direction
        return when {
            dir == "uturn" -> 180f
            dir.contains("sharp left") -> -135f
            dir.contains("slight left") -> -45f
            dir.contains("left") -> -90f
            dir.contains("sharp right") -> 135f
            dir.contains("slight right") -> 45f
            dir.contains("right") -> 90f
            else -> 0f
        }
    }

    /**
     * A rally roadbook sketch: you come in from the bottom (a dot), the junction in the middle (a
     * ring for a roundabout), the other ways faint, the way out in [accent] with an arrowhead.
     */
    private fun drawJunction(c: Canvas, cx: Float, cy: Float, size: Float, s: NavStep, ink: Int, accent: Int, d: Float, mini: Boolean = false) {
        val h = size / 2
        val roundabout = (s.type == "roundabout" || s.type == "rotary") && s.exit in 1..12
        val arrive = s.direction == "arrive"
        val jx = cx; val jy = cy - h * 0.12f
        val w = if (mini) 2.4f * d else 4f * d
        stroke.strokeCap = Paint.Cap.ROUND; stroke.strokeJoin = Paint.Join.ROUND
        // the way in
        stroke.color = ink; stroke.strokeWidth = w
        c.drawLine(cx, cy + h * 0.78f, jx, jy + (if (roundabout) h * 0.28f else 0f), stroke)
        fill.color = ink; c.drawCircle(cx, cy + h * 0.78f, w * 1.3f, fill)
        if (arrive) { fill.color = accent; c.drawCircle(jx, jy, w * 2.2f, fill); stroke.strokeCap = Paint.Cap.BUTT; return }
        val out = exitAngle(s)
        // the other ways, faint (not for a mini sketch)
        if (!mini) {
            stroke.color = withAlpha(ink, 90); stroke.strokeWidth = w * 0.6f
            for (o in if (roundabout) listOf(-90f, 0f, 90f) else listOf(-90f, 0f, 90f)) {
                if (kotlin.math.abs(o - out) < 20) continue
                val r0 = if (roundabout) h * 0.28f else 0f
                val rad = Math.toRadians(o.toDouble())
                c.drawLine(jx + (sin(rad) * r0).toFloat(), jy - (cos(rad) * r0).toFloat(), jx + (sin(rad) * h * 0.7f).toFloat(), jy - (cos(rad) * h * 0.7f).toFloat(), stroke)
            }
        }
        if (roundabout) { stroke.color = ink; stroke.strokeWidth = w * 0.8f; c.drawCircle(jx, jy, h * 0.28f, stroke) }
        // the way out
        stroke.color = accent; stroke.strokeWidth = w * 1.4f
        if (out == 180f) {
            path.reset(); path.moveTo(jx + h * 0.2f, jy); path.cubicTo(jx + h * 0.2f, jy - h * 0.6f, jx - h * 0.35f, jy - h * 0.6f, jx - h * 0.35f, jy + h * 0.3f)
            stroke.style = Paint.Style.STROKE; c.drawPath(path, stroke)
            stroke.strokeCap = Paint.Cap.BUTT; return
        }
        val rad = Math.toRadians(out.toDouble())
        val r0 = if (roundabout) h * 0.28f else 0f
        val sx = jx + (sin(rad) * r0).toFloat(); val sy = jy - (cos(rad) * r0).toFloat()
        val ex = jx + (sin(rad) * h * 0.78f).toFloat(); val ey = jy - (cos(rad) * h * 0.78f).toFloat()
        c.drawLine(sx, sy, ex, ey, stroke)
        // arrowhead
        val ah = (if (mini) 4f else 9f) * d
        val l = rad - Math.toRadians(150.0); val r = rad + Math.toRadians(150.0)
        path.reset()
        path.moveTo(ex + (sin(l) * ah).toFloat(), ey - (cos(l) * ah).toFloat()); path.lineTo(ex, ey); path.lineTo(ex + (sin(r) * ah).toFloat(), ey - (cos(r) * ah).toFloat())
        c.drawPath(path, stroke)
        stroke.strokeCap = Paint.Cap.BUTT; stroke.strokeJoin = Paint.Join.MITER
    }

    private fun ellipsize(s: String, maxW: Float): String {
        var line = s
        while (line.length > 3 && text.measureText(line) > maxW) line = line.dropLast(2) + "…"
        return line
    }

    /** One small arrow per lane: bright for lanes to use, faint for the others. */
    private fun drawLanes(c: Canvas, r: RectF, lanes: List<Lane>, maneuver: String, d: Float, t: Theme) {
        fill.color = withAlpha(Ui.ink(t), 22)
        c.drawRect(r.left + 8 * d, r.top, r.right - 8 * d, r.bottom - 6 * d, fill)
        val cell = minOf(40 * d, (r.width() - 24 * d) / lanes.size)
        var x = r.centerX() - cell * lanes.size / 2 + cell / 2
        val y = r.centerY() - 3 * d
        for (lane in lanes) {
            val dir = lane.directions.firstOrNull { lane.valid && it == maneuver }
                ?: lane.directions.firstOrNull { lane.valid && it != "none" }
                ?: lane.directions.firstOrNull() ?: "straight"
            drawLaneArrow(c, x, y, 13 * d, if (dir == "none") "straight" else dir, if (lane.valid) Ui.ink(t) else withAlpha(Ui.ink(t), 70), d)
            x += cell
        }
    }

    private fun drawLaneArrow(c: Canvas, x: Float, y: Float, s: Float, dir: String, color: Int, d: Float) {
        stroke.color = color; stroke.strokeWidth = 3 * d; stroke.strokeCap = Paint.Cap.ROUND; stroke.strokeJoin = Paint.Join.ROUND
        if (dir == "uturn") { drawUTurn(c, x, y, s, stroke); stroke.strokeCap = Paint.Cap.BUTT; stroke.strokeJoin = Paint.Join.MITER; return }
        val ang = when {
            dir.contains("sharp left") -> -135f
            dir.contains("slight left") -> -40f
            dir.contains("left") -> -90f
            dir.contains("sharp right") -> 135f
            dir.contains("slight right") -> 40f
            dir.contains("right") -> 90f
            else -> 0f
        }
        path.reset()
        path.moveTo(x, y + s)
        if (ang == 0f) {
            path.lineTo(x, y - s)
            c.drawPath(path, stroke)
            path.reset(); path.moveTo(x - s * 0.45f, y - s * 0.5f); path.lineTo(x, y - s); path.lineTo(x + s * 0.45f, y - s * 0.5f)
        } else {
            path.lineTo(x, y)
            val a = Math.toRadians((ang - 90).toDouble())
            val ex = x + (s * cos(a)).toFloat(); val ey = y + (s * sin(a)).toFloat()
            path.lineTo(ex, ey)
            c.drawPath(path, stroke)
            val back = a + Math.PI
            path.reset()
            path.moveTo(ex + (s * 0.5f * cos(back + 0.6)).toFloat(), ey + (s * 0.5f * sin(back + 0.6)).toFloat())
            path.lineTo(ex, ey)
            path.lineTo(ex + (s * 0.5f * cos(back - 0.6)).toFloat(), ey + (s * 0.5f * sin(back - 0.6)).toFloat())
        }
        c.drawPath(path, stroke)
        stroke.strokeCap = Paint.Cap.BUTT; stroke.strokeJoin = Paint.Join.MITER
    }

    private fun alertIcon(c: Canvas, x: Float, y: Float, glyph: String, ring: Int, d: Float) {
        fill.color = Color.WHITE; c.drawCircle(x, y, 14 * d, fill)
        stroke.color = ring; stroke.strokeWidth = 3 * d; c.drawCircle(x, y, 13 * d, stroke)
        text.textAlign = Paint.Align.CENTER; text.textSize = 15 * d; text.color = Color.BLACK
        c.drawText(glyph, x, y + 5.5f * d, text)
    }

    /** Speed cameras, TomTom incidents and drivers' reports as small round icons. */
    private fun drawRoadAlerts(c: Canvas, cam: Camera, center: LatLng, a: Rect, d: Float) {
        val mpp = Geo.metersPerPixel(center.lat, cam.world) / cam.scale
        val radius = kotlin.math.hypot(a.width().toDouble(), a.height().toDouble()) * mpp
        fun visit(p: LatLng, glyph: String, ring: Int) {
            if (Geo.distance(center, p) > radius) return
            val (x, y) = toScreen(p, cam)
            if (x < a.left - 20 || x > a.right + 20 || y < a.top - 20 || y > a.bottom + 20) return
            alertIcon(c, x, y, glyph, ring, d)
        }
        val tf = text.typeface
        text.typeface = Typeface.DEFAULT
        for (cm in Alerts.cameras(center)) visit(cm.pos, cm.glyph, if (cm.redLight) Color.rgb(220, 50, 50) else Color.rgb(30, 110, 230))
        for (i in Alerts.incidents) visit(i.pos, Alerts.incidentGlyph(i.category), Color.rgb(220, 50, 50))
        for (r in Alerts.reports) visit(r.pos, Alerts.typeOf(r.type).glyph, Color.rgb(255, 150, 0))
        text.typeface = tf
    }

    /** Trailblazer: landmarks to discover (glowing) and ones already found (gold). */
    private fun drawLandmarks(c: Canvas, cam: Camera, center: LatLng, a: Rect, d: Float) {
        val mpp = Geo.metersPerPixel(center.lat, cam.world) / cam.scale
        val radius = kotlin.math.hypot(a.width().toDouble(), a.height().toDouble()) * mpp
        // the nearest few, so a busy city centre doesn't turn into a sea of markers
        val list = Explorer.near(center, radius).sortedBy { Geo.distance(center, it.pos) }.take(10)
        if (list.isEmpty()) return
        val pulse = 0.35f
        text.textAlign = Paint.Align.CENTER
        val tf = text.typeface
        text.typeface = Typeface.DEFAULT_BOLD
        for (l in list) {
            val (x, y) = toScreen(l.pos, cam)
            if (x < a.left - 30 || x > a.right + 30 || y < a.top - 30 || y > a.bottom + 30) continue
            if (Explorer.isFound(l)) {
                fill.color = Color.rgb(255, 196, 0); c.drawCircle(x, y, 9 * d, fill)
                stroke.color = Color.WHITE; stroke.strokeWidth = 2 * d; c.drawCircle(x, y, 9 * d, stroke)
                stroke.color = Color.rgb(90, 60, 0); stroke.strokeWidth = 2 * d
                c.drawLine(x - 4 * d, y, x - 1 * d, y + 3 * d, stroke); c.drawLine(x - 1 * d, y + 3 * d, x + 4.5f * d, y - 3.5f * d, stroke)
            } else {
                fill.color = Color.argb((110 * (1 - pulse)).toInt(), 0, 170, 255)
                c.drawCircle(x, y, (14 + 16 * pulse) * d, fill)
                fill.color = Color.rgb(0, 120, 255); c.drawCircle(x, y, 13 * d, fill)
                stroke.color = Color.WHITE; stroke.strokeWidth = 2.5f * d; c.drawCircle(x, y, 13 * d, stroke)
                text.color = Color.WHITE; text.textSize = 15 * d
                c.drawText("?", x, y + 5.5f * d, text)
            }
        }
        text.typeface = tf
    }

    /** "Landmark discovered · +25 XP" for a few seconds after passing one. */
    private fun drawDiscovery(c: Canvas, a: Rect, d: Float, t: Theme) {
        val l = Explorer.lastFound ?: return
        val age = android.os.SystemClock.uptimeMillis() - Explorer.lastFoundAt
        if (age > 4500) return
        animating = true
        val alpha = if (age > 3800) ((4500 - age) / 700f).coerceIn(0f, 1f) else 1f
        text.textAlign = Paint.Align.LEFT
        text.typeface = Typeface.DEFAULT_BOLD
        text.textSize = 15 * d
        val title = gr.gamemap.L.t("Landmark discovered · +25 XP")
        val name = ellipsize(l.name, a.width() - 120 * d)
        val w = maxOf(text.measureText(title), text.measureText(name)) + 74 * d
        val h = 58 * d
        val left = a.exactCenterX() - w / 2
        val top = a.top + a.height() * 0.30f
        val r = RectF(left, top, left + w, top + h)
        fill.color = Color.argb((235 * alpha).toInt(), 12, 40, 90)
        c.drawRoundRect(r, 16 * d, 16 * d, fill)
        stroke.color = Color.argb((255 * alpha).toInt(), 255, 196, 0); stroke.strokeWidth = 2 * d
        c.drawRoundRect(r, 16 * d, 16 * d, stroke)
        fill.color = Color.argb((255 * alpha).toInt(), 255, 196, 0)
        c.drawCircle(r.left + 30 * d, r.centerY(), 15 * d, fill)
        text.color = Color.argb((255 * alpha).toInt(), 40, 28, 0)
        text.textAlign = Paint.Align.CENTER
        c.drawText("★", r.left + 30 * d, r.centerY() + 6 * d, text)
        text.textAlign = Paint.Align.LEFT
        text.color = Color.argb((255 * alpha).toInt(), 255, 214, 90)
        c.drawText(title, r.left + 56 * d, r.top + 24 * d, text)
        text.color = Color.argb((255 * alpha).toInt(), 255, 255, 255)
        c.drawText(name, r.left + 56 * d, r.top + 45 * d, text)
        text.typeface = t.typeface
    }

    /** "Jam in 800 m · +2 min", under the banner (phone) or at the top of the map (car). */
    private fun drawJamChip(c: Canvas, a: Rect, d: Float, t: Theme, hud: Boolean) {
        if (NavEngine.offer != null || NavEngine.arrived) return
        // speed camera / incident / report ahead
        Alerts.ahead?.let { al ->
            text.textSize = 15 * d
            text.typeface = Typeface.DEFAULT_BOLD
            text.textAlign = Paint.Align.LEFT
            val label = "${al.label} · ${NavEngine.formatDistance(al.dist)}"
            val w = text.measureText(label) + 52 * d
            val h = 40 * d
            val top = bannerBottom + 10 * d
            val left = if (hud) a.right - 12 * d - w else a.left + 12 * d
            val r = RectF(left, top, left + w, top + h)
            fill.color = Color.rgb(255, 244, 214); c.drawRoundRect(r, h / 2, h / 2, fill)
            stroke.color = Color.rgb(255, 150, 0); stroke.strokeWidth = 2 * d; c.drawRoundRect(r, h / 2, h / 2, stroke)
            text.typeface = Typeface.DEFAULT
            text.textSize = 17 * d
            text.textAlign = Paint.Align.CENTER
            c.drawText(al.glyph, r.left + 22 * d, r.centerY() + 6 * d, text)
            text.typeface = Typeface.DEFAULT_BOLD
            text.textSize = 15 * d
            text.textAlign = Paint.Align.LEFT
            text.color = Color.rgb(110, 60, 0)
            c.drawText(label, r.left + 40 * d, r.centerY() + 5.5f * d, text)
            bannerBottom = r.bottom; bannerRight = r.right
            text.typeface = t.typeface
        }
        if (!NavEngine.active) return
        val (dist, delay) = NavEngine.jamAhead ?: return
        val mins = (delay + 30) / 60
        val label = gr.gamemap.L.t((if (dist < 100) "Jam ahead" else "Jam in ${NavEngine.formatDistance(dist)}") + (if (mins >= 1) " · +$mins min" else ""))
        text.textSize = 15 * d
        text.typeface = Typeface.DEFAULT_BOLD
        text.textAlign = Paint.Align.LEFT
        val w = text.measureText(label) + 44 * d
        val h = 38 * d
        val top = bannerBottom + 10 * d
        val left = if (hud && a.width() < 700 * d) a.right - 12 * d - w else a.left + 12 * d
        val r = RectF(left, top, left + w, top + h)
        fill.color = Color.rgb(251, 227, 227)
        c.drawRoundRect(r, h / 2, h / 2, fill)
        // warning triangle
        path.reset()
        val tx = r.left + 20 * d; val ty = r.centerY()
        path.moveTo(tx, ty - 8 * d); path.lineTo(tx + 9 * d, ty + 7 * d); path.lineTo(tx - 9 * d, ty + 7 * d); path.close()
        fill.color = Color.rgb(196, 30, 36); c.drawPath(path, fill)
        text.color = Color.rgb(122, 16, 16)
        c.drawText(label, r.left + 34 * d, r.centerY() + 5.5f * d, text)
        text.typeface = t.typeface
    }

    private fun drawTurnArrow(c: Canvas, x: Float, y: Float, s: Float, dir: String, t: Theme) {
        if (dir == "arrive") {
            fill.color = Color.rgb(255, 214, 0); c.drawCircle(x, y, s * 0.6f, fill)
            stroke.color = t.route; stroke.strokeWidth = s * 0.15f; c.drawCircle(x, y, s * 0.9f, stroke)
            return
        }
        if (dir == "uturn") {
            // a real U-turn arrow (not a straight one pointing down)
            stroke.color = t.route; stroke.strokeWidth = s * 0.42f
            drawUTurn(c, x, y, s, stroke)
            return
        }
        val angle = when {
            dir.contains("sharp left") -> -135f
            dir.contains("slight left") -> -45f
            dir.contains("left") -> -90f
            dir.contains("sharp right") -> 135f
            dir.contains("slight right") -> 45f
            dir.contains("right") -> 90f
            else -> 0f
        }
        c.save()
        c.rotate(angle, x, y)
        path.reset()
        path.moveTo(x, y - s)
        path.lineTo(x + s * 0.7f, y - s * 0.2f)
        path.lineTo(x + s * 0.25f, y - s * 0.2f)
        path.lineTo(x + s * 0.25f, y + s)
        path.lineTo(x - s * 0.25f, y + s)
        path.lineTo(x - s * 0.25f, y - s * 0.2f)
        path.lineTo(x - s * 0.7f, y - s * 0.2f)
        path.close()
        fill.color = t.route; c.drawPath(path, fill)
        c.restore()
    }

    /** Where the compass button sits (the phone taps it to reset rotation). */
    val compassBounds = RectF()

    /** A dropped pin: its tip on the point. */
    private fun drawDroppedPin(c: Canvas, x: Float, y: Float, d: Float, color: Int = Color.rgb(232, 89, 12)) {
        val r = 13 * d
        path.reset()
        path.moveTo(x, y)
        path.cubicTo(x - r * 0.4f, y - r * 1.2f, x - r * 1.3f, y - r * 1.6f, x - r * 1.3f, y - r * 2.4f)
        path.arcTo(x - r * 1.3f, y - r * 3.7f, x + r * 1.3f, y - r * 1.1f, 180f, 180f, false)
        path.cubicTo(x + r * 1.3f, y - r * 1.6f, x + r * 0.4f, y - r * 1.2f, x, y)
        path.close()
        fill.color = Color.argb(70, 0, 0, 0); c.drawCircle(x, y, 4 * d, fill)
        fill.color = color; c.drawPath(path, fill)
        stroke.color = Color.WHITE; stroke.strokeWidth = 2.5f * d; c.drawPath(path, stroke)
        fill.color = Color.WHITE; c.drawCircle(x, y - r * 2.4f, r * 0.5f, fill)
    }

    private fun drawCompass(c: Canvas, a: Rect, bearing: Float, d: Float, t: Theme) {
        val r = 22 * d
        var x = a.left + 14 * d + r
        var y = maxOf(a.top + 14 * d, bannerBottom + 12 * d, carConvoyRect.bottom + 12 * d, convoyStrip.bottom + 12 * d) + r
        // a tall race box on a short car screen: beside the box rather than down into the speed box
        if (!carConvoyRect.isEmpty && y + r > a.bottom - 130 * d) { x = carConvoyRect.right + 12 * d + r; y = carConvoyRect.top + r }
        // the car: below anything drawn in its corner this frame (Where to? and the Home / Work /
        // label chips, a place's card, the lobby): it was only kept clear of the turn and race boxes
        if (car) {
            var moved = true; var guard = 0
            while (moved && guard++ < 8) {
                moved = false
                for ((hr, _) in carHits) if (hr.left < x + r && hr.right > x - r && hr.top < y + r + 6 * d && hr.bottom > y - r - 6 * d) {
                    y = hr.bottom + 12 * d + r; moved = true
                }
            }
        }
        compassBounds.set(x - r, y - r, x + r, y + r)
        fill.color = Ui.panel(t); c.drawCircle(x, y, r, fill)
        stroke.color = t.route; stroke.strokeWidth = 2 * d; c.drawCircle(x, y, r, stroke)
        c.save()
        c.rotate(-bearing, x, y)
        path.reset(); path.moveTo(x, y - r * 0.75f); path.lineTo(x + r * 0.28f, y); path.lineTo(x - r * 0.28f, y); path.close()
        fill.color = Color.rgb(230, 50, 40); c.drawPath(path, fill)
        path.reset(); path.moveTo(x, y + r * 0.75f); path.lineTo(x + r * 0.28f, y); path.lineTo(x - r * 0.28f, y); path.close()
        fill.color = Ui.ink(t); c.drawPath(path, fill)
        c.restore()
    }

    private fun drawAttribution(c: Canvas, a: Rect, d: Float, t: Theme) {
        text.textAlign = Paint.Align.RIGHT
        text.textSize = 9 * d
        text.color = Color.argb(170, Color.red(Ui.ink(t)), Color.green(Ui.ink(t)), Color.blue(Ui.ink(t)))
        c.drawText(if (Traffic.active) "© OpenStreetMap · OpenFreeMap · TomTom" else "© OpenStreetMap · OpenFreeMap", a.right - 8 * d, a.bottom - 6 * d, text)
    }
}
