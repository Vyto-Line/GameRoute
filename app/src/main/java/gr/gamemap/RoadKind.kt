package gr.gamemap

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.tan

/**
 * What kind of road a point of the route is on ("motorway", "trunk", "primary", "secondary",
 * "tertiary", "minor", "service"…), from the map data already on the phone (OpenStreetMap
 * classes). Used to zoom out on highways and in on small streets.
 */
object RoadKind {
    /** The road under [p] running along [bearing] (degrees), within ~25 m; null if not known. */
    fun at(p: LatLng, bearing: Double): String? {
        val z = VectorSource.MAX_Z
        val n = (1 shl z).toDouble()
        val wx = (p.lon + 180) / 360 * n
        val latR = p.lat * PI / 180
        val wy = (1 - ln(tan(latR) + 1 / cos(latR)) / PI) / 2 * n
        val tx = floor(wx).toInt(); val ty = floor(wy).toInt()
        val vt = VectorSource.peek(z, tx, ty) ?: return null
        val extent = vt.features.firstOrNull()?.extent ?: 4096
        val px = ((wx - tx) * extent).toFloat(); val py = ((wy - ty) * extent).toFloat()
        // metres per tile unit here
        val mpu = 40_075_016.0 * cos(latR) / n / extent
        val maxD = (25 / mpu).toFloat()
        // the route's direction in tile units (y grows southwards)
        val br = bearing * PI / 180
        val dx = kotlin.math.sin(br).toFloat(); val dy = (-cos(br)).toFloat()
        var best: String? = null; var bestD = maxD
        for (f in vt.features) {
            if (f.layer != "transportation" || f.type != 2) continue
            val cls = f["class"] ?: continue
            if (cls in setOf("path", "track", "rail", "transit", "ferry", "bridge", "pier", "aerialway", "busway", "raceway")) continue
            for (part in f.parts) {
                var i = 2
                while (i < part.size) {
                    val x0 = part[i - 2]; val y0 = part[i - 1]; val x1 = part[i]; val y1 = part[i + 1]
                    i += 2
                    if (px < minOf(x0, x1) - maxD || px > maxOf(x0, x1) + maxD || py < minOf(y0, y1) - maxD || py > maxOf(y0, y1) + maxD) continue
                    val sx = x1 - x0; val sy = y1 - y0
                    val sl = hypot(sx, sy)
                    if (sl == 0f) continue
                    // the road we drive along, not a side street crossing it
                    if (abs((sx * dx + sy * dy) / sl) < 0.7f) continue
                    val t = (((px - x0) * sx + (py - y0) * sy) / (sl * sl)).coerceIn(0f, 1f)
                    val d = hypot(px - (x0 + sx * t), py - (y0 + sy * t))
                    if (d < bestD) { bestD = d; best = if (f["ramp"] == "1") "ramp" else cls }
                }
            }
        }
        return best
    }

    /** The road [meters] along route [r] (after its start). */
    fun along(r: RouteOption, meters: Double): String? {
        val pts = r.points; val cum = r.cum
        if (pts.size < 2) return null
        val m = meters.coerceIn(0.0, cum.last())
        var i = java.util.Arrays.binarySearch(cum, m).let { if (it < 0) -it - 2 else it }.coerceIn(0, pts.size - 2)
        while (i < pts.size - 2 && cum[i + 1] - cum[i] < 0.5) i++
        val a = pts[i]; val b = pts[i + 1]
        val f = if (cum[i + 1] > cum[i]) ((m - cum[i]) / (cum[i + 1] - cum[i])).coerceIn(0.0, 1.0) else 0.0
        val p = LatLng(a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f)
        val brg = atan2((b.lon - a.lon) * cos(a.lat * PI / 180), b.lat - a.lat) * 180 / PI
        return at(p, brg)
    }

    /** How far the camera zooms for this kind of road: out for highways (−), in for small streets (+). */
    fun zoomFor(kind: String?): Double = when (kind) {
        "motorway", "trunk" -> -0.9
        "primary" -> -0.45
        "ramp" -> -0.3
        "secondary" -> -0.15
        "tertiary" -> 0.0
        "minor" -> 0.3
        "service" -> 0.45
        else -> 0.0
    }

    fun small(kind: String?) = kind == "minor" || kind == "service"
}
