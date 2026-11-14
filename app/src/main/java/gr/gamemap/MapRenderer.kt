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

    private fun drawFrame(c: Canvas, w: Int, h: Int, area: Rect, d: Float, hud: Boolean) {
        compassBounds.setEmpty()
        val zoomAnim = view.tick()
        interacting = zoomAnim || view.gesture
        val moveAnim = LocationTracker.tick()
        animating = zoomAnim || moveAnim || (wasInteracting && !(zoomAnim || view.gesture))
        wasInteracting = zoomAnim || view.gesture

        val theme = if (car) MapState.carTheme ?: MapState.theme else MapState.theme
        view.fitRequest?.let { fit(it, area, d, theme) }
        c.drawColor(theme.background)
        text.typeface = theme.typeface

        val me = LocationTracker.displayPosition()
        val following = view.following
        val center = if (following) me ?: fallback else view.freeCenter ?: me ?: fallback
        val bearing = if (following) view.userBearing ?: LocationTracker.heading else view.freeBearing

        val zoom = view.zoom
        val z = floor(zoom).toInt().coerceIn(2, 19)
        val tilePx = TileCache.tilePx
        val scale = (2.0.pow(zoom - z) * (d / (tilePx / 256f)) * 1.15).toFloat()
        val world = tilePx * 2.0.pow(z)
        val px = Geo.x(center.lon, world)
        val py = Geo.y(center.lat, world)

        val radius = min(area.width(), area.height()) / 2f - 14 * d
        val cx = area.exactCenterX()
        val cy = if (theme.radar) area.exactCenterY()
        else area.top + area.height() * (if (NavEngine.active && following) 0.68f else 0.55f)
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
        prof("labels", tp0); tp0 = System.nanoTime()
        // places (fuel, parking, food…) at street level; while navigating only fuel and parking
        if (MapState.showPlaces && !NavEngine.planning) {
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
            if (Convoy.anyMoving) animating = true // friends glide between their updates
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
        if (!lite) {
            if (theme.grid) drawGrid(c, full, d)
            if (theme.scanlines) drawScanlines(c, full, d, theme)
            if (theme.paper) drawPaper(c, full)
            if (theme.sunGlow) drawSunGlow(c, full)
        }
        if (theme.radar) drawRadarFrame(c, cx, cy, radius, bearing, d)
        prof("overlays", tp0); tp0 = System.nanoTime()

        // player: centred when following, otherwise wherever it is on the map
        if (me != null) {
            if (following) {
                drawPlayer(c, cx, cy, 0f, d, theme)
            } else {
                val (sx, sy) = toScreen(me, camera!!)
                if (area.contains(sx.toInt(), sy.toInt())) drawPlayer(c, sx, sy, LocationTracker.heading - bearing, d, theme)
            }
        }

        drawSpeed(c, area, d, theme)
        if (car && hud) drawCarEta(c, area, d, theme)
        carHits.clear()
        // the car's trip card steps aside after 30 s, or as soon as a place is tapped
        val trip = NavEngine.lastTrip
        if (trip !== tripSeen) { tripSeen = trip; tripSeenAt = System.currentTimeMillis() }
        val carTrip = car && carButtons && trip != null && selectedPlace == null && System.currentTimeMillis() - tripSeenAt < 30_000
        if (car && hud && carButtons && !NavEngine.planning && !carTrip) drawCarButtons(c, area, d, theme)
        bannerBottom = area.top.toFloat()
        if (hud && !NavEngine.planning && NavEngine.offer == null && !carTrip) {
            // in the car: smaller, in the very top-left corner
            if (car) drawBanner(c, Rect(area.left, 0, area.right, area.bottom), d * 0.8f, theme, pad = 8 * d)
            else drawBanner(c, area, d, theme)
        }
        convoyStrip.setEmpty()
        // the convoy strip/box only on the convoy's own route (a route of my own looks like any other)
        if (hud && Convoy.active && Convoy.onConvoyRoute && !NavEngine.arrived && !NavEngine.planning) {
            if (car) drawCarConvoy(c, area, d, theme) else drawConvoyStrip(c, area, d, theme)
        }
        if (carTrip) drawCarTrip(c, area, d, theme)
        else if (car && carButtons && selectedPlace != null && !NavEngine.planning) drawCarPlace(c, area, d, theme)
        drawJamChip(c, area, d, theme, hud)
        if (hud && NavEngine.estimating) drawNoGps(c, area, d, theme)
        if (hud && !NavEngine.arrived) NavEngine.tollAhead?.let { (t, dist) -> drawTollChip(c, area, d, theme, t.price, dist) }
        if (car && hud && carButtons) Convoy.proposal?.let { drawCarProposal(c, area, d, theme, it) }
        if (hud && Convoy.counting) { drawCountdown(c, area, d, theme); animating = true }
        else if (hud && Convoy.active && Convoy.meReady && !NavEngine.active) drawLobbyChip(c, area, d, theme)
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
        val showTraffic = Traffic.active && Traffic.showLayer && z >= 11 && !NavEngine.planning

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
        for (t in tiles) {
            val tx = t[0]; val ty = t[1]
            val x = tx.mod(n)
            val left = (tx * tilePx - px).toFloat(); val top = (ty * tilePx - py).toFloat()
            dst.set(left, top, left + tilePx, top + tilePx)
            val bmp = if (interacting) TileCache.peek(theme, z, x, ty) else TileCache.get(theme, z, x, ty)
            if (bmp != null) c.drawBitmap(bmp, null, dst, paint)
            else drawStandIn(c, theme, z, x, ty, paint)
            if (showTraffic) TrafficOverlay.get(theme, z, x, ty, !interacting)?.let { c.drawBitmap(it, null, dst, overlayPaint) }
        }

    }

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
        while (i < pts.size && r.cum[i] - startM < 1500 && asked < 6) {
            val tx = floor(Geo.x(pts[i].lon, world) / TileCache.tilePx).toInt()
            val ty = floor(Geo.y(pts[i].lat, world) / TileCache.tilePx).toInt()
            val k = (tx.toLong() shl 32) or (ty.toLong() and 0xffffffffL)
            if (k != last && ty in 0 until n && onScreen.none { it[0] == tx && it[1] == ty }) {
                last = k
                if (TileCache.peek(theme, z, tx.mod(n), ty) == null) { TileCache.get(theme, z, tx.mod(n), ty); asked++ }
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
        // Coarser traffic data zoomed out (few squares); finer geometry close up.
        val dz = when {
            z >= 17 -> 16
            z == 16 -> 15
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
            val tp = trafficCache[key] ?: if (busy) continue else buildTraffic(t, ax, ay, span, u, z).also { trafficCache[key] = it }
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
        while (trafficCache.size > 48) trafficCache.remove(trafficCache.keys.first())
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

        // Lock lanes onto the map's roads (within ~25 m, same direction only).
        val maxSnap = maxOf(25f / mpp, 12f * u)
        val roads = RoadSnap.index(z, ax * span, ay * span, span, maxSnap)
        fun lane(raw: FloatArray, side: Float): FloatArray? {
            val fallback = raw.copyOf().also { offsetLine(it, side * lane) }
            return if (roads == null) fallback else RoadSnap.snapLane(roads, raw, fallback, maxSnap, side, 1.8f * u)
        }
        for (sg in t.segs) {
            val base = FloatArray(sg.pts.size)
            for (i in base.indices) base[i] = sg.pts[i] * k
            val side = if (sg.leftHand) -1f else 1f
            if (sg.oneWay) {
                // data for one direction: points run the way the traffic goes
                lane(base, side)?.let { add(it, sg.state) }
            } else {
                // data for both directions: one lane each way, each pointing its own way
                // (a lane against a one-way street is dropped)
                lane(base, side)?.let { add(it, sg.state) }
                lane(reversedLine(base), side)?.let { add(it, sg.state) }
            }
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
        private val NAV_PLACES = setOf(PoiLayer.Kind.FUEL, PoiLayer.Kind.PARKING)
        /** Tests switch this on to see where frame time goes. */
        @JvmStatic var profile = false
        val times = HashMap<String, Long>()
    }

    /** True while zooming or touching: draw what's cached, render new detail once it settles. */
    private var interacting = false
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

    private fun drawRoute(c: Canvas, theme: Theme, world: Double, px: Double, py: Double, scale: Float, d: Float) {
        val z = kotlin.math.round(kotlin.math.log2(world / TileCache.tilePx)).toInt()
        val pixel = theme.pixel != 1
        if (NavEngine.planning) {
            val opts = NavEngine.options
            opts.forEachIndexed { i, o ->
                if (i != NavEngine.selected) strokeRoute(c, o.points, world, z, px, py, scale, d,
                    withAlpha(theme.route, 120), 0, 6f, false, pixel)
            }
            opts.getOrNull(NavEngine.selected)?.let {
                strokeRoute(c, it.points, world, z, px, py, scale, d, theme.route, theme.routeGlow, 7f, theme.dashedRoute, pixel)
            }
            NavEngine.planPlace?.let { drawDestination(c, it.pos, world, px, py, scale, d, theme) }
            return
        }
        val route = NavEngine.route
        val offer = NavEngine.offer
        if (route.size > 1) {
            if (offer != null) {
                drawRouteAhead(c, route, world, z, px, py, scale, d, withAlpha(theme.route, 110), 0, 6f, false, pixel)
                strokeRoute(c, offer.option.points, world, z, px, py, scale, d,
                    Color.rgb(61, 220, 132), Color.argb(90, 61, 220, 132), 8f, false, pixel)
            } else {
                drawRouteAhead(c, route, world, z, px, py, scale, d, theme.route, theme.routeGlow, 7f, theme.dashedRoute, pixel)
            }
        }
        NavEngine.destination?.let { drawDestination(c, it, world, px, py, scale, d, theme) }
    }

    private fun drawDestination(c: Canvas, dest: LatLng, world: Double, px: Double, py: Double, scale: Float, d: Float, theme: Theme) {
        val x = (Geo.x(dest.lon, world) - px).toFloat(); val y = (Geo.y(dest.lat, world) - py).toFloat()
        fill.color = theme.routeGlow; c.drawCircle(x, y, 18 * d / scale, fill)
        fill.color = Color.rgb(255, 214, 0); c.drawCircle(x, y, 9 * d / scale, fill)
        stroke.color = Color.BLACK; stroke.strokeWidth = 2 * d / scale; c.drawCircle(x, y, 9 * d / scale, stroke)
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

    private fun drawPlayer(c: Canvas, x: Float, y: Float, rotation: Float, d: Float, t: Theme) {
        val s = 15 * d
        c.save()
        c.rotate(rotation, x, y)
        path.reset()
        path.moveTo(x, y - s)
        path.lineTo(x + s * 0.75f, y + s * 0.8f)
        path.lineTo(x, y + s * 0.4f)
        path.lineTo(x - s * 0.75f, y + s * 0.8f)
        path.close()
        fill.isAntiAlias = t.pixel == 1
        fill.color = t.routeGlow; c.drawCircle(x, y, s * 1.5f, fill)
        fill.color = t.player; c.drawPath(path, fill)
        stroke.color = t.playerOutline; stroke.strokeWidth = 2.5f * d; c.drawPath(path, stroke)
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
    private fun panel(c: Canvas, r: RectF, d: Float, t: Theme, outline: Boolean) {
        // slightly see-through so the map shows behind, still solid enough to read
        fill.color = Ui.panel(t).let { Color.argb(218, Color.red(it), Color.green(it), Color.blue(it)) }
        stroke.color = t.route; stroke.strokeWidth = 2 * d
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
                    fill.color = t.route
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

    private fun drawSpeed(c: Canvas, a: Rect, d: Float, t: Theme) {
        if (LocationTracker.last == null) return
        val imp = MapState.imperial
        val kmh = Math.round(LocationTracker.speedMps * (if (imp) 2.23694f else 3.6f))
        val limit = (if (NavEngine.active) NavEngine.speedLimit else null)?.let { if (imp) ((it * 0.621371 / 5).roundToInt() * 5) else it }
        val over = limit != null && kmh > limit + 4
        val pad = 14 * d
        text.textAlign = Paint.Align.CENTER
        text.textSize = 30 * d
        val big = kmh.toString()
        val bw = maxOf(text.measureText(big), text.measureText("88")) + 24 * d
        val box = RectF(a.left + pad, a.bottom - pad - 58 * d, a.left + pad + bw, a.bottom - pad)
        panel(c, box, d, t, outline = over)
        text.color = if (over) Color.rgb(255, 92, 92) else Ui.ink(t)
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
    @Volatile private var hitsShown: List<Pair<RectF, String>> = emptyList()

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
            "recenter" -> { iconPaint.style = Paint.Style.FILL
                p.moveTo(cx, cy - h); p.lineTo(cx + h * 0.7f, cy + h); p.lineTo(cx, cy + h * 0.55f); p.lineTo(cx - h * 0.7f, cy + h); p.close() }
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
        if (NavEngine.active) btn(if (Voice.muted) "unmute" else "mute", y) else btn("traffic", y)
        y += size + gap
        btn("report", y)
        // bottom right: zoom and recenter / overview
        var b = a.bottom - 14 * d - size
        when {
            !view.following || view.userBearing != null -> { btn("recenter", b, filled = true); b -= size + gap }
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
        } else if (!NavEngine.active && selectedPlace == null) {
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
        text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 13 * d; text.color = Ui.onPanel(t, t.route, 3.0)
        c.drawText(Explorer.completeTitle(t).uppercase(), box.left + 18 * d, box.top + 28 * d, text)
        text.typeface = t.typeface; text.textSize = 24 * d; text.color = ink
        c.drawText(ellipsize(trip.place, w - 36 * d), box.left + 18 * d, box.top + 60 * d, text)
        val stats = listOf(
            NavEngine.formatDuration(trip.seconds.toDouble()) to "journey",
            NavEngine.formatDistance(trip.meters) to "travelled",
            "+${trip.xp} XP" to "level ${Explorer.level}",
        )
        val col = (w - 36 * d) / 3
        stats.forEachIndexed { i, (big, small) ->
            val x = box.left + 18 * d + i * col
            text.typeface = t.typeface; text.textSize = 22 * d; text.color = ink
            c.drawText(gr.gamemap.L.t(big), x, box.top + 104 * d, text)
            text.typeface = Typeface.DEFAULT; text.textSize = 13 * d; text.color = Ui.dim(t, 190)
            c.drawText(gr.gamemap.L.t(small), x, box.top + 124 * d, text)
        }
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
        bannerBottom = r.bottom
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
        bannerBottom = r.bottom
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
        bannerBottom = r.bottom
    }

    /** Tunnel mode: a small chip saying the position is estimated. */
    private fun drawNoGps(c: Canvas, a: Rect, d: Float, t: Theme) {
        val label = gr.gamemap.L.t("No GPS: estimating")
        text.textAlign = Paint.Align.LEFT; text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 13 * d
        val w = text.measureText(label) + 34 * d
        val x = if (car) a.left + 12 * d else a.centerX() - w / 2
        val y = maxOf(bannerBottom, a.top.toFloat()) + 8 * d
        val r = RectF(x, y, x + w, y + 30 * d)
        fill.color = Color.argb(235, 40, 44, 52); c.drawRoundRect(r, 15 * d, 15 * d, fill)
        fill.color = Color.rgb(250, 176, 5); c.drawCircle(r.left + 15 * d, r.centerY(), 4.5f * d, fill)
        text.color = Color.WHITE; c.drawText(label, r.left + 25 * d, r.centerY() + 4.5f * d, text)
        text.typeface = t.typeface
        bannerBottom = r.bottom
    }

    /** Where the phone's convoy strip is (taps open the list). */
    val convoyStrip = RectF()

    /** The others in the convoy: an arrow in their colour, name and speed. */
    private fun drawConvoyMembers(c: Canvas, cam: Camera, a: Rect, d: Float, bearing: Float) {
        val now = System.currentTimeMillis()
        val hits = ArrayList<Pair<RectF, Convoy.Member>>()
        for (m in Convoy.members.values) {
            if (m.state == "left") continue
            val p = m.display(now) ?: continue
            val (x, y) = toScreen(p, cam)
            if (x < -40 * d || y < -40 * d || x > a.right + 40 * d || y > a.bottom + 40 * d) continue
            c.save(); c.translate(x, y); c.rotate(m.shownHeading - bearing)
            val s = 12 * d
            path.reset()
            path.moveTo(0f, -s * 1.2f); path.lineTo(s * 0.85f, s * 0.85f); path.lineTo(0f, s * 0.4f); path.lineTo(-s * 0.85f, s * 0.85f); path.close()
            fill.color = if (m.stale) Color.GRAY else m.color; c.drawPath(path, fill)
            stroke.color = Color.WHITE; stroke.strokeWidth = 2.2f * d; c.drawPath(path, stroke)
            c.restore()
            // name tag
            val label = "${m.name}  ${if (m.state == "arrived") "✓" else m.kmh.toString()}"
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

    /** Phone: a slim strip under the turn card: everyone on a line to the flag, with speeds. */
    private fun drawConvoyStrip(c: Canvas, a: Rect, d: Float, t: Theme) {
        val rows = Convoy.rows(t.route)
        val r = RectF(a.left + 12 * d, bannerBottom + 8 * d, a.right - 12 * d, bannerBottom + 8 * d + 54 * d)
        panel(c, r, d, t, outline = true)
        val x0 = r.left + 18 * d; val x1 = r.right - 52 * d; val ly = r.top + 18 * d
        fill.color = withAlpha(Ui.ink(t), 60); c.drawRoundRect(x0, ly - 1.5f * d, x1, ly + 1.5f * d, 2 * d, 2 * d, fill)
        text.textAlign = Paint.Align.CENTER; text.typeface = Typeface.DEFAULT; text.textSize = 13 * d
        c.drawText("🏁", x1 + 14 * d, ly + 5 * d, text)
        for (row in rows.sortedBy { if (it.me) 1 else 0 }) { // me on top
            val x = x0 + ((x1 - x0) * row.progress).toFloat()
            fill.color = if (row.stale) Color.GRAY else row.color
            c.drawCircle(x, ly, 11 * d, fill)
            stroke.color = Ui.panel(t); stroke.strokeWidth = 2 * d; c.drawCircle(x, ly, 11 * d, stroke)
            text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 11 * d; text.color = Color.rgb(17, 20, 24)
            c.drawText(if (row.me) "★" else row.name.take(1).uppercase(), x, ly + 4 * d, text)
            text.textSize = 10 * d
            text.color = if (row.kmh == 0 && row.state == "driving") Color.rgb(255, 120, 120) else Ui.dim(t, 220)
            c.drawText(if (row.state == "arrived") "✓" else "${row.kmh}", x, ly + 25 * d, text)
        }
        text.textSize = 15 * d; text.color = Ui.onPanel(t, t.route, 3.0)
        c.drawText("▾", r.right - 16 * d, ly + 5 * d, text)
        text.textAlign = Paint.Align.LEFT; text.typeface = t.typeface
        convoyStrip.set(r)
        bannerBottom = r.bottom
    }

    /** Car: the convoy box under the turn box (name · how far ahead, speed, arrival). */
    private fun drawCarConvoy(c: Canvas, a: Rect, d: Float, t: Theme) {
        val rows = Convoy.rows(t.route).take(5)
        val w = minOf(352 * d, a.width() - 28f * d)
        val rowH = 26 * d
        val r = RectF(a.left + 12 * d, bannerBottom + 8 * d, a.left + 12 * d + w, bannerBottom + 8 * d + 46 * d + rows.size * rowH)
        panel(c, r, d, t, outline = true)
        text.textAlign = Paint.Align.LEFT; text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 11 * d
        text.color = Ui.onPanel(t, t.route, 3.0)
        c.drawText(gr.gamemap.L.t("CONVOY") + " · ${Convoy.destination?.name?.uppercase()?.take(24) ?: ""}", r.left + 12 * d, r.top + 17 * d, text)
        // progress line
        val x0 = r.left + 14 * d; val x1 = r.right - 30 * d; val ly = r.top + 31 * d
        fill.color = withAlpha(Ui.ink(t), 60); c.drawRoundRect(x0, ly - 1.5f * d, x1, ly + 1.5f * d, 2 * d, 2 * d, fill)
        text.textSize = 12 * d; text.typeface = Typeface.DEFAULT; c.drawText("🏁", x1 + 6 * d, ly + 5 * d, text)
        for (row in rows.sortedBy { if (it.me) 1 else 0 }) {
            fill.color = if (row.stale) Color.GRAY else row.color
            c.drawCircle(x0 + ((x1 - x0) * row.progress).toFloat(), ly, 6.5f * d, fill)
        }
        var y = r.top + 46 * d
        for (row in rows) {
            fill.color = if (row.stale) Color.GRAY else row.color
            c.drawCircle(r.left + 18 * d, y + 13 * d, 5 * d, fill)
            text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 15 * d; text.color = Ui.ink(t)
            val name = if (row.me) gr.gamemap.L.t("You") else row.name
            c.drawText(ellipsize(name, 90 * d), r.left + 30 * d, y + 18 * d, text)
            val nw = text.measureText(ellipsize(name, 90 * d))
            text.typeface = Typeface.DEFAULT; text.textSize = 13 * d; text.color = Ui.dim(t, 190)
            c.drawText(ellipsize(gr.gamemap.L.t(" · " + Convoy.relative(row)), w - nw - 160 * d), r.left + 30 * d + nw, y + 18 * d, text)
            text.textAlign = Paint.Align.RIGHT; text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 15 * d
            text.color = if (row.kmh == 0 && row.state == "driving") Color.rgb(255, 120, 120) else Ui.ink(t)
            c.drawText(if (row.state == "arrived") "✓" else "${row.kmh}", r.right - 72 * d, y + 18 * d, text)
            text.color = Ui.onPanel(t, Color.rgb(47, 210, 122), 3.0)
            c.drawText(if (row.eta > 0) NavEngine.clock(row.eta) else "–", r.right - 12 * d, y + 18 * d, text)
            text.textAlign = Paint.Align.LEFT
            carHits += RectF(r.left, y, r.right, y + rowH) to "friend:${row.id}"
            y += rowH
        }
        text.typeface = t.typeface
        bannerBottom = r.bottom
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

    private fun drawBanner(c: Canvas, a: Rect, d: Float, t: Theme, pad: Float = 12 * d) {
        if (!NavEngine.active) return
        val step = NavEngine.currentStep
        val (title, sub) = when {
            NavEngine.arrived -> Explorer.completeTitle(t).uppercase() to NavEngine.destinationName
            NavEngine.error != null -> (NavEngine.error ?: "") to NavEngine.destinationName
            NavEngine.loading || step == null -> gr.gamemap.L.t("Plotting route…") to NavEngine.destinationName
            else -> {
                val st = step!!
                NavEngine.formatDistance(NavEngine.distanceToNext) to
                    (if (st.road.isNotBlank() && st.type != "arrive") st.road else st.text)
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
            val label = ellipsize(if (then.road.isNotBlank() && then.type != "arrive") then.road else then.text, box.width() - 110 * d)
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
        val ang = when {
            dir == "uturn" -> 180f
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
            bannerBottom = r.bottom
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
        val angle = when {
            dir == "uturn" -> 180f
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
        val x = a.left + 14 * d + r
        val y = maxOf(a.top + 14 * d, bannerBottom + 12 * d) + r
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
