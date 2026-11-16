package gr.gamemap

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.pow

/**
 * Puts TomTom traffic lanes onto the map's own (OpenStreetMap) roads: each lane locks onto
 * the nearest road running the same way. A one-way carriageway takes the lane on its
 * centre line; a two-way road takes it in the middle of the correct half.
 */
object RoadSnap {

    /** Road segments in world pixels (relative to an origin), with a grid for fast lookups. */
    class Index(private val cell: Float) {
        // x0, y0, x1, y1, direction (1 = one-way along, -1 = one-way against, 0 = two-way), half width
        val segs = ArrayList<FloatArray>()
        private val grid = HashMap<Long, ArrayList<Int>>()

        private fun key(cx: Int, cy: Int) = (cx.toLong() shl 32) or (cy.toLong() and 0xffffffffL)

        fun add(x0: Float, y0: Float, x1: Float, y1: Float, dir: Float, half: Float) {
            val i = segs.size
            segs += floatArrayOf(x0, y0, x1, y1, dir, half)
            val cx0 = floor(minOf(x0, x1) / cell).toInt(); val cx1 = floor(maxOf(x0, x1) / cell).toInt()
            val cy0 = floor(minOf(y0, y1) / cell).toInt(); val cy1 = floor(maxOf(y0, y1) / cell).toInt()
            if ((cx1 - cx0 + 1) * (cy1 - cy0 + 1) > 64) return // absurdly long segment
            for (cy in cy0..cy1) for (cx in cx0..cx1) grid.getOrPut(key(cx, cy)) { ArrayList(4) } += i
        }

        fun near(x: Float, y: Float, out: HashSet<Int>) {
            out.clear()
            val cx = floor(x / cell).toInt(); val cy = floor(y / cell).toInt()
            for (dy in -1..1) for (dx in -1..1) grid[key(cx + dx, cy + dy)]?.let { out.addAll(it) }
        }

        val isEmpty get() = segs.isEmpty()
    }

    private val roadMeters = mapOf(
        "motorway" to 22f, "trunk" to 20f, "primary" to 16f, "secondary" to 14f, "tertiary" to 12f,
        "minor" to 9f, "service" to 6f,
    )

    /** Roads around a square (origin ox, oy, size span, all in world pixels at map zoom z). */
    fun index(z: Int, ox: Double, oy: Double, span: Double, maxDist: Float): Index? {
        val tilePx = TileCache.tilePx
        val dz = VectorSource.dataZoom(z)
        val osmSpan = tilePx * 2.0.pow(z - dz)
        val world = tilePx * 2.0.pow(z)
        val mpp = Geo.metersPerPixel(Geo.lat(oy + span / 2, world), world).toFloat()
        val m = maxDist.toDouble()
        val tx0 = floor((ox - m) / osmSpan).toInt(); val tx1 = floor((ox + span + m) / osmSpan).toInt()
        val ty0 = floor((oy - m) / osmSpan).toInt(); val ty1 = floor((oy + span + m) / osmSpan).toInt()
        if ((tx1 - tx0 + 1) * (ty1 - ty0 + 1) > 16) return null
        val idx = Index(maxDist)
        var any = false
        for (ty in ty0..ty1) for (tx in tx0..tx1) {
            val vt = VectorSource.peek(dz, tx, ty) ?: continue
            any = true
            for (f in vt.features) {
                if (f.layer != "transportation" || f.type != 2) continue // tunnels too: in one, the car is on it, not on the road above
                val meters = roadMeters[f["class"]] ?: continue
                val half = meters * 0.85f / 2 / mpp
                val dir = when (f["oneway"]) { "1" -> 1f; "-1" -> -1f; else -> 0f }
                val k = osmSpan / f.extent
                for (part in f.parts) {
                    var i = 2
                    while (i < part.size) {
                        val x0 = (tx * osmSpan + part[i - 2] * k - ox).toFloat(); val y0 = (ty * osmSpan + part[i - 1] * k - oy).toFloat()
                        val x1 = (tx * osmSpan + part[i] * k - ox).toFloat(); val y1 = (ty * osmSpan + part[i + 1] * k - oy).toFloat()
                        idx.add(x0, y0, x1, y1, dir, half)
                        i += 2
                    }
                }
            }
        }
        return if (any) idx else null
    }

    /**
     * Snaps one lane. [raw] runs in the lane's driving direction (TomTom's line); [fallback]
     * is where to draw a point that matches no road. [side] = 1 for driving on the right.
     * Returns null when the lane would run against a one-way street (no such traffic).
     */
    fun snapLane(idx: Index, raw: FloatArray, fallback: FloatArray, maxDist: Float, side: Float, minHalfLane: Float): FloatArray? {
        val n = raw.size / 2
        val out = fallback.copyOf()
        val cand = HashSet<Int>()
        var wrongWay = 0 // points that only match a one-way street going the other way
        for (i in 0 until n) {
            val px = raw[i * 2]; val py = raw[i * 2 + 1]
            // lane direction at this point
            val a = maxOf(0, i - 1); val b = minOf(n - 1, i + 1)
            var dx = raw[b * 2] - raw[a * 2]; var dy = raw[b * 2 + 1] - raw[a * 2 + 1]
            val dl = hypot(dx, dy)
            if (dl == 0f) continue
            dx /= dl; dy /= dl
            idx.near(px, py, cand)
            var best = -1; var bestD = maxDist; var bx = 0f; var by = 0f
            var againstD = Float.MAX_VALUE
            for (si in cand) {
                val sg = idx.segs[si]
                var sx = sg[2] - sg[0]; var sy = sg[3] - sg[1]
                val sl = hypot(sx, sy)
                if (sl == 0f) continue
                sx /= sl; sy /= sl
                val dot = sx * dx + sy * dy
                // only roads running the same way (not cross streets); one-ways in our direction
                if (abs(dot) < 0.75f) continue
                val t = (((px - sg[0]) * sx + (py - sg[1]) * sy) / sl).coerceIn(0f, 1f)
                val qx = sg[0] + (sg[2] - sg[0]) * t; val qy = sg[1] + (sg[3] - sg[1]) * t
                val d = hypot(px - qx, py - qy)
                if (sg[4] != 0f && dot * sg[4] < 0) { if (d < againstD) againstD = d; continue }
                if (d < bestD) { bestD = d; best = si; bx = qx; by = qy }
            }
            if (best < 0) {
                if (againstD < maxDist) wrongWay++
                continue
            }
            val sg = idx.segs[best]
            if (sg[4] == 0f) {
                // two-way road: middle of our half (right of travel for right-hand traffic)
                val off = maxOf(sg[5] / 2, minHalfLane) * side
                bx += -dy * off; by += dx * off
            }
            out[i * 2] = bx; out[i * 2 + 1] = by
        }
        // mostly along a one-way street the other way: there is no traffic in this direction
        return if (wrongWay * 2 > n) null else out
    }
}
