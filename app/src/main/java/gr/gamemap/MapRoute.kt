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
import gr.gamemap.MapRenderer.Companion.allocMark
import gr.gamemap.MapRenderer.Companion.trafficWorker
import gr.gamemap.MapRenderer.Companion.NAV_PLACES
import gr.gamemap.MapRenderer.Companion.profile
import gr.gamemap.MapRenderer.Companion.allocProbe
import gr.gamemap.MapRenderer.Companion.allocs
import gr.gamemap.MapRenderer.Companion.times

// Part of MapRenderer, moved out in the clean-up of 23 Nov 2026 (see tools/split_main.py).

internal fun MapRenderer.prof(k: String, t0: Long) {
    if (!profile) return
    times[k] = (times[k] ?: 0L) + (System.nanoTime() - t0)
    allocProbe?.let { p -> val now = p(); allocs[k] = (allocs[k] ?: 0L) + (now - allocMark); allocMark = now }
}

internal fun MapRenderer.pathFor(pts: List<LatLng>, world: Double, z: Int): CachedPath {
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

internal fun MapRenderer.strokeRoute(
    c: Canvas, pts: List<LatLng>, world: Double, z: Int, px: Double, py: Double, scale: Float, d: Float,
    color: Int, glow: Int, width: Float, dashed: Boolean, pixel: Boolean,
) {
    if (pts.size < 2) return
    strokePath(c, pathFor(pts, world, z), px, py, scale, d, color, glow, width, dashed, pixel)
}

internal fun MapRenderer.strokePath(
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

internal fun MapRenderer.aheadPathFor(route: List<LatLng>, from: Int, world: Double, z: Int): CachedPath {
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
internal fun MapRenderer.drawRouteAhead(
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

internal fun MapRenderer.withAlpha(col: Int, a: Int) = Color.argb(a, Color.red(col), Color.green(col), Color.blue(col))

internal fun MapRenderer.drawRoute(c: Canvas, theme: Theme, world: Double, px: Double, py: Double, scale: Float, d: Float) {
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

internal fun MapRenderer.drawDestination(c: Canvas, dest: LatLng, world: Double, px: Double, py: Double, scale: Float, d: Float, theme: Theme) {
    val x = (Geo.x(dest.lon, world) - px).toFloat(); val y = (Geo.y(dest.lat, world) - py).toFloat()
    val u = d / scale
    fill.color = Xp.routeGlow(theme.look); c.drawCircle(x, y, 18 * u, fill)
    // the Garage's destination flag (standing on the spot, upright whatever the map's turn)
    val flagId = Xp.chosen("flag").id
    if (flagId in Flags.ids) {
        c.save(); c.rotate(currentBearing, x, y)
        Flags.draw(c, flagId, x, y, u * 1.15f, Xp.rankColor())
        c.restore()
        return
    }
    when (flagId) {
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
internal fun MapRenderer.fit(pts: List<LatLng>, area: Rect, d: Float, theme: Theme) {
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
