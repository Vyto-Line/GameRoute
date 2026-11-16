package gr.gamemap

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sinh

/**
 * Keeps the arrow on the road when there is no route (or the car has left it): the map's own roads
 * around the car (the vector tiles already loaded for drawing), the one that fits best by
 *  - distance from the fix,
 *  - heading (one-way streets only the right way),
 *  - speed (a car at 110 km/h is on the motorway, not on the service road beside it),
 *  - the road the car was on a moment ago (same stretch, or one joined to it): so parallel roads
 *    and bridges over streets don't make the arrow hop between them.
 * Only for drawing: routing, trips and XP still use the measured position.
 */
object RoadMatch {
    private const val Z = 14

    /** One stretch of road, in metres east/north of [originLat]/[originLon]. */
    internal class Seg(
        val ax: Double, val ay: Double, val bx: Double, val by: Double,
        val cls: String, val oneway: Int, val feature: Long, val bridge: Boolean,
    )

    private var tileKey = ""
    private var segs: List<Seg> = emptyList()
    /** Named stretches (the map's street names), for "the road you're on" without a route. */
    private var named: List<Pair<Seg, String>> = emptyList()
    private var originLat = 0.0
    private var originLon = 0.0

    /** The stretch chosen last time, and when. */
    private var last: Seg? = null
    private var lastAt = 0L

    internal fun reset() { tileKey = ""; segs = emptyList(); last = null }

    /** The kind of road the car is on right now (driving without a route), as RoadKind names it. */
    fun kindNow(): String? {
        val s = last?.takeIf { System.currentTimeMillis() - lastAt < 8_000 } ?: return null
        return s.cls.takeIf { it in setOf("motorway", "trunk", "primary", "secondary", "tertiary", "minor", "service") }
    }

    /** For tests: the stretch the car is on. */
    internal fun setLastForTest(cls: String) { last = Seg(0.0, 0.0, 1.0, 1.0, cls, 0, 1L, false); lastAt = System.currentTimeMillis() }

    /** For tests: use these stretches (already in metres around [lat]/[lon]). */
    internal fun useForTest(lat: Double, lon: Double, list: List<Seg>) {
        originLat = lat; originLon = lon; segs = list; tileKey = "test"; last = null
    }

    private fun mx(lon: Double) = (lon - originLon) * 111_320.0 * cos(originLat * PI / 180)
    private fun my(lat: Double) = (lat - originLat) * 110_540.0
    private fun lonOf(x: Double) = originLon + x / (111_320.0 * cos(originLat * PI / 180))
    private fun latOf(y: Double) = originLat + y / 110_540.0

    /** Loads the roads of the 3×3 data tiles around [p] (once per tile the car is in). */
    private fun load(p: LatLng) {
        val n = 1 shl Z
        val tx = floor((p.lon + 180) / 360 * n).toInt()
        val latR = p.lat * PI / 180
        val ty = floor((1 - kotlin.math.ln(kotlin.math.tan(latR) + 1 / cos(latR)) / PI) / 2 * n).toInt()
        val key = "$tx/$ty"
        if (key == tileKey && segs.isNotEmpty()) return
        val out = ArrayList<Seg>()
        val names = ArrayList<Pair<Seg, String>>()
        originLat = p.lat; originLon = p.lon
        var any = false
        for (dy in -1..1) for (dx in -1..1) {
            val x = tx + dx; val y = ty + dy
            val vt = VectorSource.peek(Z, x, y) ?: continue
            any = true
            for ((fi, f) in vt.features.withIndex()) {
                if (f.layer == "transportation_name" && f.type == 2) {
                    val nm = (if (L.greek) f["name:el"] else f["name:latin"]) ?: f["name"] ?: continue
                    for (part in f.parts) {
                        var i = 2
                        while (i < part.size) {
                            fun lon(v: Float) = (x + v / f.extent) / n * 360.0 - 180
                            fun lat(v: Float) = atan(sinh(PI * (1 - 2 * (y + v / f.extent) / n))) * 180 / PI
                            names += Seg(mx(lon(part[i - 2])), my(lat(part[i - 1])), mx(lon(part[i])), my(lat(part[i + 1])), "", 0, 0, false) to nm
                            i += 2
                        }
                    }
                    continue
                }
                if (f.layer != "transportation" || f.type != 2) continue
                val cls = f["class"] ?: continue
                if (cls !in DRIVABLE) continue
                val one = when (f["oneway"]) { "1" -> 1; "-1" -> -1; else -> 0 }
                val bridge = f["brunnel"] == "bridge"
                val id = (x.toLong() shl 40) xor (y.toLong() shl 20) xor fi.toLong()
                for (part in f.parts) {
                    var i = 2
                    while (i < part.size) {
                        fun lon(v: Float) = (x + v / f.extent) / n * 360.0 - 180
                        fun lat(v: Float) = atan(sinh(PI * (1 - 2 * (y + v / f.extent) / n))) * 180 / PI
                        out += Seg(mx(lon(part[i - 2])), my(lat(part[i - 1])), mx(lon(part[i])), my(lat(part[i + 1])), cls, one, id, bridge)
                        i += 2
                    }
                }
            }
        }
        if (!any) return
        tileKey = key; segs = out; named = names; last = null
    }

    /**
     * The point on the best-fitting road near [p] and that road's direction of travel, or null when
     * no road fits (the arrow then shows the fix as it is).
     */
    fun match(p: LatLng, bearing: Float?, speed: Float, accuracy: Float): Pair<LatLng, Float>? {
        if (tileKey != "test") load(p)
        if (segs.isEmpty()) return null
        val px = mx(p.lon); val py = my(p.lat)
        val reach = (accuracy * 1.2f).coerceIn(12f, 30f).toDouble()
        val now = System.currentTimeMillis()
        val prev = last?.takeIf { now - lastAt < 10_000 }
        var best: Seg? = null; var bestScore = Double.MAX_VALUE; var bestT = 0.0; var bestDist = 0.0
        var prevScore = Double.MAX_VALUE; var prevT = 0.0
        for (s in segs) {
            val vx = s.bx - s.ax; val vy = s.by - s.ay
            val len2 = vx * vx + vy * vy
            if (len2 < 0.01) continue
            val t = (((px - s.ax) * vx + (py - s.ay) * vy) / len2).coerceIn(0.0, 1.0)
            val qx = s.ax + vx * t; val qy = s.ay + vy * t
            val d = kotlin.math.hypot(px - qx, py - qy)
            if (d > reach) continue
            var score = d
            if (bearing != null) {
                val along = compass(vx, vy)
                val diff = abs(Geo.angleDiff(bearing, along))
                val back = 180 - diff
                val off = when (s.oneway) { 1 -> diff; -1 -> back; else -> minOf(diff, back) }
                if (off > 70) continue // crossing it, or against a one-way street
                score += off / 3.0
            }
            // a fast car is on a big road; a slow one rarely on a motorway
            if (speed > 25f && s.cls in SMALL) score += 15
            else if (speed > 18f && s.cls == "service") score += 10
            if (speed < 8f && s.cls == "motorway") score += 8
            // the road we were on, or one joined to it, is the most likely
            if (prev != null) {
                if (s.feature == prev.feature) score -= 8
                else if (joined(s, prev)) score -= 5
                else if (s.bridge != prev.bridge) score += 6
            }
            if (s === prev) { prevScore = score; prevT = t }
            if (score < bestScore) { bestScore = score; best = s; bestT = t; bestDist = d }
        }
        var b = best ?: return null
        var t = bestT
        // stay on the same stretch unless another is clearly better (no hopping between neighbours)
        if (prev != null && b !== prev && prevScore < Double.MAX_VALUE && prevScore - bestScore < 4) { b = prev; t = prevT }
        last = b; lastAt = now
        val qx = b.ax + (b.bx - b.ax) * t; val qy = b.ay + (b.by - b.ay) * t
        var dir = compass(b.bx - b.ax, b.by - b.ay)
        if (b.oneway == -1) dir = (dir + 180) % 360
        else if (b.oneway == 0 && bearing != null && abs(Geo.angleDiff(bearing, dir)) > 90) dir = (dir + 180) % 360
        if (bestDist < 0 || dir.isNaN()) return null
        return LatLng(latOf(qy), lonOf(qx)) to dir
    }

    /** The name of the street at [p] going [bearing] (within 20 m), or null on unnamed roads. */
    fun roadName(p: LatLng, bearing: Float?): String? {
        if (tileKey != "test") load(p)
        val px = mx(p.lon); val py = my(p.lat)
        var best: String? = null; var bestD = 20.0
        for ((s, nm) in named) {
            val vx = s.bx - s.ax; val vy = s.by - s.ay
            val len2 = vx * vx + vy * vy
            if (len2 < 0.01) continue
            val t = (((px - s.ax) * vx + (py - s.ay) * vy) / len2).coerceIn(0.0, 1.0)
            val d = kotlin.math.hypot(px - (s.ax + vx * t), py - (s.ay + vy * t))
            if (d >= bestD) continue
            if (bearing != null) {
                val diff = abs(Geo.angleDiff(bearing, compass(vx, vy)))
                if (minOf(diff, 180 - diff) > 40) continue // a crossing street
            }
            bestD = d; best = nm
        }
        return best
    }

    private fun joined(a: Seg, b: Seg): Boolean {
        fun near(x0: Double, y0: Double, x1: Double, y1: Double) = abs(x0 - x1) < 1.5 && abs(y0 - y1) < 1.5
        return near(a.ax, a.ay, b.ax, b.ay) || near(a.ax, a.ay, b.bx, b.by) || near(a.bx, a.by, b.ax, b.ay) || near(a.bx, a.by, b.bx, b.by)
    }

    /** Compass direction (0 = north, clockwise) of a vector in east/north metres. */
    private fun compass(vx: Double, vy: Double): Float = ((Math.toDegrees(kotlin.math.atan2(vx, vy)) + 360) % 360).toFloat()

    private val DRIVABLE = setOf("motorway", "trunk", "primary", "secondary", "tertiary", "minor", "service", "busway")
    private val SMALL = setOf("minor", "service", "track")
}
