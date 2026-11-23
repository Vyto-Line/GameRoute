@file:Suppress("unused")

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
import android.content.Context
import gr.gamemap.MapRenderer.Camera
import gr.gamemap.MapRenderer.ArrowLine
import gr.gamemap.MapRenderer.TrafficPaths
import gr.gamemap.MapRenderer.CachedPath
import gr.gamemap.MapRenderer.EllKey
import gr.gamemap.MapRenderer.Companion.trafficWorker
import gr.gamemap.MapRenderer.Companion.NAV_PLACES
import gr.gamemap.MapRenderer.Companion.profile
import gr.gamemap.MapRenderer.Companion.allocProbe
import gr.gamemap.MapRenderer.Companion.allocs
import gr.gamemap.MapRenderer.Companion.times

// Part of MapRenderer, moved out in the clean-up of 23 Nov 2026 (see tools/split_main.py).

internal fun MapRenderer.drawTiles(
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

internal fun MapRenderer.prefetchAhead(theme: Theme, z: Int, onScreen: List<IntArray>) {
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

internal fun MapRenderer.drawStandIn(c: Canvas, theme: Theme, z: Int, x: Int, y: Int, paint: Paint) {
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

internal fun MapRenderer.drawTraffic(
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

internal fun MapRenderer.buildTrafficLater(key: String, t: Traffic.Tile, ax: Int, ay: Int, span: Double, u: Float, z: Int) {
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

internal fun MapRenderer.buildTraffic(t: Traffic.Tile, ax: Int, ay: Int, span: Double, u: Float, z: Int): TrafficPaths {
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

internal fun MapRenderer.reversedLine(p: FloatArray): FloatArray {
    val out = FloatArray(p.size)
    var i = 0
    while (i < p.size) { out[p.size - 2 - i] = p[i]; out[p.size - 1 - i] = p[i + 1]; i += 2 }
    return out
}

/** Shifts a line sideways by [d] (positive = right of its direction, screen coordinates). */
internal fun MapRenderer.offsetLine(p: FloatArray, d: Float) {
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
internal fun MapRenderer.drawArrows(c: Canvas, tp: TrafficPaths, look: TrafficLook, u: Float, vx: Double, vy: Double, reach: Double): Boolean {
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
internal fun MapRenderer.arrowRun(c: Canvas, pts: FloatArray, total: Float, start: Float, spacing: Float, backwards: Boolean): Int {
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
