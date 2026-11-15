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

    /** Car roads: everything but tracks, paths and the like. */
    private val carRoads = setOf("motorway", "trunk", "primary", "secondary", "tertiary", "minor", "service")

    private fun tileOf(p: LatLng): Pair<Int, Int> {
        val n = (1 shl VectorSource.MAX_Z).toDouble(); val r = p.lat * PI / 180
        return floor((p.lon + 180) / 360 * n).toInt() to floor((1 - ln(tan(r) + 1 / cos(r)) / PI) / 2 * n).toInt()
    }

    /** True if a car road (not a track or a path) runs within 20 m of [p]. */
    private fun carRoadNear(p: LatLng): Boolean? {
        val z = VectorSource.MAX_Z; val n = (1 shl z).toDouble()
        val wx = (p.lon + 180) / 360 * n; val latR = p.lat * PI / 180
        val wy = (1 - ln(tan(latR) + 1 / cos(latR)) / PI) / 2 * n
        val tx = floor(wx).toInt(); val ty = floor(wy).toInt()
        val vt = VectorSource.peek(z, tx, ty) ?: return null
        val extent = vt.features.firstOrNull()?.extent ?: 4096
        val px = ((wx - tx) * extent).toFloat(); val py = ((wy - ty) * extent).toFloat()
        val maxD = (20 / (40_075_016.0 * cos(latR) / n / extent)).toFloat()
        for (f in vt.features) {
            if (f.layer != "transportation" || f.type != 2 || f["class"] !in carRoads) continue
            if (f["surface"] == "unpaved" && f["class"] == "service") continue
            for (part in f.parts) {
                var j = 2
                while (j < part.size) {
                    val x0 = part[j - 2]; val y0 = part[j - 1]; val x1 = part[j]; val y1 = part[j + 1]; j += 2
                    if (px < minOf(x0, x1) - maxD || px > maxOf(x0, x1) + maxD || py < minOf(y0, y1) - maxD || py > maxOf(y0, y1) + maxD) continue
                    val sx = x1 - x0; val sy = y1 - y0; val sl2 = sx * sx + sy * sy
                    val t = if (sl2 == 0f) 0f else (((px - x0) * sx + (py - y0) * sy) / sl2).coerceIn(0f, 1f)
                    if (hypot(px - (x0 + sx * t), py - (y0 + sy * t)) < maxD) return true
                }
            }
        }
        return false
    }

    /**
     * A route whose last part is a dirt track or a footpath (a cave, a peak, a chapel up a hill):
     * where the car road ends (metres along [r], and the point), if at least 200 m are left after
     * it. Loads the map squares it needs (call off the main thread). Null: the road goes all the way.
     */
    fun dirtTail(r: RouteOption): Pair<Double, LatLng>? {
        val len = r.length
        if (r.points.size < 2 || len < 400) return null
        // the map squares over the last 4 km
        val tiles = r.points.filterIndexed { i, _ -> r.cum[i] > len - 4000 }.map { tileOf(it) }.toSet()
        if (tiles.size > 12) return null
        for ((x, y) in tiles) try { VectorSource.get(VectorSource.MAX_Z, x, y) } catch (_: Exception) {}
        // back from the end: the last point (sampled every 25 m) that is on a car road
        var m = len - 10
        var off = 0
        while (m > len - 4000 && m > 0) {
            val p = pointAt(r, m)
            when (carRoadNear(p)) {
                null -> return null // no map data: don't guess
                true -> { if (off == 0) return null; break }
                false -> off++
            }
            m -= 25
        }
        val left = len - m
        return if (left >= 200 && m > 0) m to pointAt(r, m) else null
    }

    private fun pointAt(r: RouteOption, meters: Double): LatLng {
        val pts = r.points; val cum = r.cum
        val i = java.util.Arrays.binarySearch(cum, meters).let { if (it < 0) -it - 2 else it }.coerceIn(0, pts.size - 2)
        val a = pts[i]; val b = pts[i + 1]
        val f = if (cum[i + 1] > cum[i]) ((meters - cum[i]) / (cum[i + 1] - cum[i])).coerceIn(0.0, 1.0) else 0.0
        return LatLng(a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f)
    }

    /** For tests: every road class within 25 m of the point [meters] along [r] (tracks and paths too, with surface). */
    internal fun allNear(r: RouteOption, meters: Double): String {
        val i = r.cum.indexOfFirst { it >= meters }.coerceAtLeast(0)
        val p = r.points[i]
        val z = VectorSource.MAX_Z; val n = (1 shl z).toDouble()
        val wx = (p.lon + 180) / 360 * n; val latR = p.lat * PI / 180
        val wy = (1 - ln(tan(latR) + 1 / cos(latR)) / PI) / 2 * n
        val tx = floor(wx).toInt(); val ty = floor(wy).toInt()
        val vt = VectorSource.peek(z, tx, ty) ?: return "no data"
        val extent = vt.features.firstOrNull()?.extent ?: 4096
        val px = ((wx - tx) * extent).toFloat(); val py = ((wy - ty) * extent).toFloat()
        val maxD = (25 / (40_075_016.0 * cos(latR) / n / extent)).toFloat()
        val found = LinkedHashSet<String>()
        for (f in vt.features) {
            if (f.layer != "transportation" || f.type != 2) continue
            for (part in f.parts) { var j = 2; while (j < part.size) {
                val x0 = part[j - 2]; val y0 = part[j - 1]; val x1 = part[j]; val y1 = part[j + 1]; j += 2
                val sx = x1 - x0; val sy = y1 - y0; val sl2 = sx * sx + sy * sy
                val t = if (sl2 == 0f) 0f else (((px - x0) * sx + (py - y0) * sy) / sl2).coerceIn(0f, 1f)
                if (hypot(px - (x0 + sx * t), py - (y0 + sy * t)) < maxD) found += "${f["class"]}${f["subclass"]?.let { ":$it" } ?: ""}${f["surface"]?.let { "($it)" } ?: ""}${f["access"]?.let { "[access $it]" } ?: ""}"
            } }
        }
        return found.joinToString(",")
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
