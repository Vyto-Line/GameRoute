package gr.gamemap

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Routes with no signal, on the phone, over the road file for Greece (tools/roadgraph.py, built
 * monthly in CI, downloaded in Settings › Offline maps). Turn restrictions are followed (the
 * search goes road by road, not junction by junction, so "no left turn" is never broken), with
 * a little time for each turn (left across traffic more than right, U-turns only at dead ends).
 * The file is read straight from storage (memory-mapped), so it costs little memory.
 */
object OfflineRouter {
    const val URL = "https://github.com/Vyto-Line/GameRoute/releases/download/roads-data/roads-gr.bin.gz"
    const val INFO = "https://github.com/Vyto-Line/GameRoute/releases/download/roads-data/roads-gr.json"

    private lateinit var file: File
    fun init(ctx: Context) { file = File(ctx.filesDir, "roads-gr.bin") }

    /** The road file is on the phone. */
    val present: Boolean get() = ::file.isInitialized && file.exists() && file.length() > 1000
    fun bytes(): Long = if (present) file.length() else 0L
    fun date(): Long = if (present) file.lastModified() else 0L

    // ---------------------------------------------------------------- the file

    private class Graph(buf: ByteBuffer) {
        val n: Int; val e: Int; val g: Int; val r: Int; val nameCount: Int; val nameBytes: Int
        val lat0: Double; val lon0: Double; val cell: Double; val rows: Int; val cols: Int; val gridEntries: Int
        val vLat: IntBuffer; val vLon: IntBuffer
        val eA: IntBuffer; val eB: IntBuffer; val eLen: IntBuffer; val eName: IntBuffer; val eGeo: IntBuffer
        val eSpeed: ByteBuffer; val eMax: ByteBuffer; val eFlags: ByteBuffer; val eCls: ByteBuffer
        val gLat: IntBuffer; val gLon: IntBuffer
        val adjOff: IntBuffer; val adj: IntBuffer
        val rFrom: IntBuffer; val rVia: IntBuffer; val rTo: IntBuffer; val rKind: ByteBuffer
        val nameOff: IntBuffer; val names: ByteBuffer
        val cellOff: IntBuffer; val cellEdges: IntBuffer
        /** Restrictions by their "from" road. */
        val restrictFrom = HashMap<Int, IntArray>()

        init {
            buf.order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4); buf.get(magic)
            require(String(magic) == "GRRG") { "not a road file" }
            require(buf.int == 1) { "road file version" }
            n = buf.int; e = buf.int; g = buf.int; r = buf.int; nameCount = buf.int; nameBytes = buf.int
            lat0 = buf.double; lon0 = buf.double; cell = buf.double; rows = buf.int; cols = buf.int; gridEntries = buf.int
            var pos = buf.position()
            fun ints(count: Int): IntBuffer {
                val b = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN); b.position(pos); b.limit(pos + count * 4)
                pos += count * 4
                return b.slice().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
            }
            fun bytes(count: Int): ByteBuffer {
                val b = buf.duplicate(); b.position(pos); b.limit(pos + count)
                pos += count + ((4 - count % 4) % 4)
                return b.slice()
            }
            vLat = ints(n); vLon = ints(n)
            eA = ints(e); eB = ints(e); eLen = ints(e); eName = ints(e); eGeo = ints(e + 1)
            eSpeed = bytes(e); eMax = bytes(e); eFlags = bytes(e); eCls = bytes(e)
            gLat = ints(g); gLon = ints(g)
            adjOff = ints(n + 1); adj = ints(2 * e)
            rFrom = ints(r); rVia = ints(r); rTo = ints(r); rKind = bytes(r)
            nameOff = ints(nameCount + 1); names = bytes(nameBytes)
            cellOff = ints(rows * cols + 1); cellEdges = ints(gridEntries)
            val tmp = HashMap<Int, MutableList<Int>>()
            for (i in 0 until r) tmp.getOrPut(rFrom[i]) { ArrayList() } += i
            for ((k, v) in tmp) restrictFrom[k] = v.toIntArray()
        }

        fun flags(i: Int) = eFlags[i].toInt() and 0xff
        fun speed(i: Int) = (eSpeed[i].toInt() and 0xff).coerceAtLeast(5)
        fun cls(i: Int) = eCls[i].toInt() and 0xff
        fun maxspeed(i: Int) = eMax[i].toInt() and 0xff
        fun seconds(i: Int) = eLen[i] * 0.36 / speed(i)   // dm and km/h → s
        fun name(i: Int): String {
            val id = eName[i]
            if (id <= 0 || id >= nameCount) return ""
            val a = nameOff[id]; val b = nameOff[id + 1]
            val bytes = ByteArray(b - a)
            for (k in bytes.indices) bytes[k] = names[a + k]
            return String(bytes, Charsets.UTF_8)
        }
        /** The road's points from a to b (or b to a). */
        fun shape(i: Int, reverse: Boolean): List<LatLng> {
            val out = ArrayList<LatLng>()
            out += LatLng(vLat[eA[i]] / 1e6, vLon[eA[i]] / 1e6)
            for (k in eGeo[i] until eGeo[i + 1]) out += LatLng(gLat[k] / 1e6, gLon[k] / 1e6)
            out += LatLng(vLat[eB[i]] / 1e6, vLon[eB[i]] / 1e6)
            if (reverse) out.reverse()
            return out
        }
    }

    @Volatile private var graph: Graph? = null

    /** Opens the road file (once). Off the main thread. */
    @Synchronized
    private fun open(): Graph? {
        graph?.let { return it }
        if (!present) return null
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val buf = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
                Graph(buf).also { graph = it }
            }
        } catch (e: Throwable) {
            CrashLog.note("road file: ${e.message}")
            null
        }
    }

    /** The speed limit (km/h) of the road right at [p] (within 25 m, facing [heading]), if the road file knows it. */
    fun limitAt(p: LatLng, heading: Float?): Int? {
        val g = graph ?: (if (present) open() else null) ?: return null
        val s = try { snap(g, p, heading, 25.0) } catch (_: Exception) { null } ?: return null
        return g.maxspeed(s.edge).takeIf { it in 5..150 }
    }

    /** Ready to route (the file is on the phone and opens). */
    fun ready(): Boolean = present && (graph != null || open() != null)

    /** Forgets the open file (after a new download or a delete). */
    @Synchronized fun close() { graph = null; dist = null; parent = null; stamp = null }

    // ---------------------------------------------------------------- finding the road at a point

    private class Snap(val edge: Int, val seg: Int, val point: LatLng, val fromA: Double, val dist: Double)

    private fun mx(g: Graph, lon: Int, lat: Int) = lon * 0.1113195 * cos(Math.toRadians(lat / 1e6))
    private fun my(lat: Int) = lat * 0.1113195

    /** The drivable road nearest to [p] (within ~400 m), facing [heading] if given. */
    private fun snap(g: Graph, p: LatLng, heading: Float?, max: Double = 400.0): Snap? {
        val r0 = ((p.lat - g.lat0) / g.cell).toInt(); val c0 = ((p.lon - g.lon0) / g.cell).toInt()
        val cosL = cos(Math.toRadians(p.lat))
        val pxm = p.lon * 1e6 * 0.1113195 * cosL; val pym = p.lat * 1e6 * 0.1113195
        var best: Snap? = null; var bestScore = Double.MAX_VALUE
        val seen = HashSet<Int>()
        for (ring in 0..(if (max > 1000) 18 else 3)) {
            for (r in r0 - ring..r0 + ring) for (c in c0 - ring..c0 + ring) {
                if (maxOf(abs(r - r0), abs(c - c0)) != ring) continue
                if (r < 0 || c < 0 || r >= g.rows || c >= g.cols) continue
                val cell = r * g.cols + c
                for (k in g.cellOff[cell] until g.cellOff[cell + 1]) {
                    val e = g.cellEdges[k]
                    if (!seen.add(e)) continue
                    val pts = g.shape(e, false)
                    var along = 0.0
                    for (s in 0 until pts.size - 1) {
                        val ax = pts[s].lon * 1e6 * 0.1113195 * cosL; val ay = pts[s].lat * 1e6 * 0.1113195
                        val bx = pts[s + 1].lon * 1e6 * 0.1113195 * cosL; val by = pts[s + 1].lat * 1e6 * 0.1113195
                        val sx = bx - ax; val sy = by - ay; val sl2 = sx * sx + sy * sy
                        val t = if (sl2 == 0.0) 0.0 else (((pxm - ax) * sx + (pym - ay) * sy) / sl2).coerceIn(0.0, 1.0)
                        val qx = ax + sx * t; val qy = ay + sy * t
                        val d = hypot(pxm - qx, pym - qy)
                        var score = d
                        // facing the road's direction (or able to drive it the other way)
                        if (heading != null && sl2 > 0) {
                            val brg = Math.toDegrees(atan2(sx, sy))
                            val diff = abs(((heading - brg + 540) % 360) - 180)
                            val fl = g.flags(e)
                            val ok = (diff < 90 && fl and 1 != 0) || (diff >= 90 && fl and 2 != 0)
                            score += if (ok) diff.coerceAtMost(180 - diff) * 0.15 else 40.0
                        }
                        // service roads and parking aisles only if nothing else is close
                        if (g.cls(e) == 8) score += 15.0
                        if (score < bestScore) {
                            bestScore = score
                            val seg = sqrt(sl2)
                            best = Snap(e, s, LatLng(qy / (1e6 * 0.1113195), qx / (1e6 * 0.1113195 * cosL)), along + seg * t, d)
                        }
                        along += sqrt(sl2)
                    }
                }
            }
            if (best != null && best.dist < ring * 900.0) break
            if (best == null && ring * 900.0 > max) break // nothing nearer can be in the next ring
        }
        return best?.takeIf { it.dist < max }
    }

    // ---------------------------------------------------------------- the search

    private var dist: FloatArray? = null
    private var parent: IntArray? = null
    private var stamp: IntArray? = null
    private var gen = 0
    private var heapK = FloatArray(1 shl 16)
    private var heapV = IntArray(1 shl 16)
    private var heapN = 0

    private fun push(k: Float, v: Int) {
        if (heapN == heapK.size) { heapK = heapK.copyOf(heapN * 2); heapV = heapV.copyOf(heapN * 2) }
        var i = heapN++
        while (i > 0) {
            val p = (i - 1) shr 1
            if (heapK[p] <= k) break
            heapK[i] = heapK[p]; heapV[i] = heapV[p]; i = p
        }
        heapK[i] = k; heapV[i] = v
    }

    private fun pop(): Int {
        val top = heapV[0]
        val k = heapK[--heapN]; val v = heapV[heapN]
        var i = 0
        while (true) {
            var c = 2 * i + 1
            if (c >= heapN) break
            if (c + 1 < heapN && heapK[c + 1] < heapK[c]) c++
            if (heapK[c] >= k) break
            heapK[i] = heapK[c]; heapV[i] = heapV[c]; i = c
        }
        if (heapN > 0) { heapK[i] = k; heapV[i] = v }
        return top
    }

    /** Bearing (degrees) of a road leaving vertex [v] along edge [e]. */
    private fun outBearing(g: Graph, e: Int, v: Int): Double {
        val fromA = g.eA[e] == v
        val (la, lo) = if (fromA) {
            if (g.eGeo[e + 1] > g.eGeo[e]) g.gLat[g.eGeo[e]] to g.gLon[g.eGeo[e]] else g.vLat[g.eB[e]] to g.vLon[g.eB[e]]
        } else {
            if (g.eGeo[e + 1] > g.eGeo[e]) g.gLat[g.eGeo[e + 1] - 1] to g.gLon[g.eGeo[e + 1] - 1] else g.vLat[g.eA[e]] to g.vLon[g.eA[e]]
        }
        val dx = (lo - g.vLon[v]) * cos(Math.toRadians(g.vLat[v] / 1e6)); val dy = (la - g.vLat[v]).toDouble()
        return Math.toDegrees(atan2(dx, dy))
    }

    /** Bearing arriving at [v] along [e] (the reverse of leaving [v] along it). */
    private fun inBearing(g: Graph, e: Int, v: Int) = (outBearing(g, e, v) + 180) % 360

    /** -180..180, + = to the right. */
    private fun turn(inB: Double, outB: Double) = ((outB - inB + 540) % 360) - 180

    /** Seconds a turn costs (driving on the right: left turns cross the traffic). */
    private fun turnCost(d: Double): Double {
        val a = abs(d)
        return when {
            a < 25 -> 0.0
            a > 165 -> 45.0
            d > 0 -> if (a < 60) 2.0 else 5.0
            else -> if (a < 60) 4.0 else 11.0
        }
    }

    /** Allowed to leave vertex [v] along edge [f] (one-way). */
    private fun canLeave(g: Graph, f: Int, v: Int): Boolean {
        val fl = g.flags(f)
        return if (g.eA[f] == v) fl and 1 != 0 else fl and 2 != 0
    }

    /** Allowed to turn from [e] onto [f] at [v] (turn restrictions). */
    private fun allowed(g: Graph, e: Int, v: Int, f: Int): Boolean {
        val rs = g.restrictFrom[e] ?: return true
        var only = -1
        for (i in rs) {
            if (g.rVia[i] != v) continue
            val k = g.rKind[i].toInt()
            if (k == 0 && g.rTo[i] == f) return false
            if (k == 1) only = g.rTo[i]
        }
        return only < 0 || only == f
    }

    /**
     * A route from [from] to [to] (through [via] stops), or null. Off the main thread: a long trip
     * can take a second or two.
     */
    fun route(from: LatLng, to: LatLng, heading: Float? = null, via: List<LatLng> = emptyList()): RouteOption? {
        val g = open() ?: return null
        val stops = listOf(from) + via + to
        val legs = ArrayList<List<Int>>()
        val snaps = ArrayList<Snap>()
        var prevSnap = snap(g, from, heading) ?: return null
        snaps += prevSnap
        for (k in 1 until stops.size) {
            val t = snap(g, stops[k], null, if (k == stops.size - 1) 2000.0 else 400.0) ?: return null
            val path = synchronized(this) { search(g, prevSnap, t) } ?: return null
            legs += path; snaps += t
            prevSnap = t
        }
        return build(g, legs, snaps)
    }

    /** States: edge × 2 + (0: a → b, 1: b → a); the result is the list of states, start to goal. */
    private fun search(g: Graph, s: Snap, t: Snap): List<Int>? {
        val size = g.e * 2
        if (dist == null || dist!!.size != size) { dist = FloatArray(size); parent = IntArray(size); stamp = IntArray(size); gen = 0 }
        val dist = dist!!; val parent = parent!!; val stamp = stamp!!
        gen++
        if (gen == Int.MAX_VALUE) { stamp.fill(0); gen = 1 }
        heapN = 0
        val tLat = (t.point.lat * 1e6).toInt(); val tLon = (t.point.lon * 1e6).toInt()
        val cosT = cos(Math.toRadians(t.point.lat))
        fun h(v: Int): Float {
            val dx = (g.vLon[v] - tLon) * 0.1113195 * cosT; val dy = (g.vLat[v] - tLat) * 0.1113195
            return (sqrt(dx * dx + dy * dy) / 36.1).toFloat() // at 130 km/h: never more than the real time
        }
        fun head(st: Int) = if (st and 1 == 0) g.eB[st shr 1] else g.eA[st shr 1]
        fun relax(st: Int, cost: Double, par: Int) {
            if (stamp[st] == gen && dist[st] <= cost) return
            stamp[st] = gen; dist[st] = cost.toFloat(); parent[st] = par
            push((cost + h(head(st))).toFloat(), st)
        }
        val se = s.edge; val sLen = g.eLen[se] / 10.0; val sSp = g.speed(se) / 3.6
        val te = t.edge; val tLen = g.eLen[te] / 10.0; val tSp = g.speed(te) / 3.6
        var best = Double.MAX_VALUE; var bestState = -2; var bestDir = 0
        // the same road, ahead of us
        if (se == te) {
            if (t.fromA >= s.fromA && g.flags(se) and 1 != 0) { best = (t.fromA - s.fromA) / sSp; bestState = -1; bestDir = 0 }
            if (t.fromA <= s.fromA && g.flags(se) and 2 != 0) { val c = (s.fromA - t.fromA) / sSp; if (c < best) { best = c; bestState = -1; bestDir = 1 } }
        }
        if (g.flags(se) and 1 != 0) relax(se * 2, (sLen - s.fromA) / sSp, -1)
        if (g.flags(se) and 2 != 0) relax(se * 2 + 1, s.fromA / sSp, -1)
        var pops = 0
        while (heapN > 0) {
            val k = heapK[0]
            if (k >= best) break
            val st = pop()
            val e = st shr 1
            val v = head(st)
            val gst = dist[st].toDouble()
            if (stamp[st] != gen || k > gst + h(v) + 0.01f) continue // an old entry
            if (++pops > 4_000_000) break
            val inB = inBearing(g, e, v)
            val deg = g.adjOff[v + 1] - g.adjOff[v]
            for (k2 in g.adjOff[v] until g.adjOff[v + 1]) {
                val f = g.adj[k2]
                if (f == e && deg > 1) continue // U-turns only at a dead end
                if (!canLeave(g, f, v) || !allowed(g, e, v, f)) continue
                val tc = turnCost(turn(inB, outBearing(g, f, v)))
                val dirOut = if (g.eA[f] == v) 0 else 1
                if (f == te) {
                    // the goal is on this road: drive to it
                    val part = if (dirOut == 0) t.fromA / tSp else (tLen - t.fromA) / tSp
                    val c = gst + tc + part
                    if (c < best) { best = c; bestState = st; bestDir = dirOut }
                }
                relax(f * 2 + dirOut, gst + tc + g.seconds(f), st)
            }
        }
        if (bestState == -2) return null
        if (bestState == -1) return listOf(-1 - bestDir) // same road: marker
        val out = ArrayList<Int>()
        var st = bestState
        while (st >= 0) { out += st; st = parent[st] }
        out.reverse()
        out += te * 2 + bestDir // the goal's road, partly
        return out
    }

    // ---------------------------------------------------------------- the route

    private fun build(g: Graph, legs: List<List<Int>>, snaps: List<Snap>): RouteOption {
        val pts = ArrayList<LatLng>()
        val steps = ArrayList<NavStep>()
        var seconds = 0.0
        var toll = false; var motorway = false
        val roadLen = HashMap<String, Double>()
        val limits = ArrayList<Stretch>()
        fun addPts(list: List<LatLng>) { for (p in list) if (pts.isEmpty() || Geo.distance(pts.last(), p) > 0.5) pts += p }
        fun cumNow(): Double { var s = 0.0; for (i in 1 until pts.size) s += Geo.distance(pts[i - 1], pts[i]); return s }
        for ((li, leg) in legs.withIndex()) {
            val s = snaps[li]; val t = snaps[li + 1]
            if (leg.size == 1 && leg[0] < 0) {
                // start and goal on the same road
                val rev = leg[0] == -2
                val sh = g.shape(s.edge, false)
                val part = ArrayList<LatLng>(); part += s.point
                if (!rev) for (i in s.seg + 1..t.seg) part += sh[i] else for (i in s.seg downTo t.seg + 1) part += sh[i]
                part += t.point
                if (li == 0) steps += NavStep("depart", "straight", g.name(s.edge), 0, s.point, null)
                addPts(part)
                seconds += abs(t.fromA - s.fromA) / (g.speed(s.edge) / 3.6)
            } else {
                for ((i, st) in leg.withIndex()) {
                    val e = st shr 1; val rev = st and 1 == 1
                    val sh = g.shape(e, rev)
                    val n = sh.size
                    val part: List<LatLng> = when {
                        i == 0 -> {
                            // from the snap point to the road's end
                            val segIdx = if (!rev) s.seg else (n - 2 - s.seg)
                            listOf(s.point) + sh.subList(segIdx + 1, n)
                        }
                        i == leg.size - 1 -> {
                            val segIdx = if (!rev) t.seg else (n - 2 - t.seg)
                            sh.subList(0, segIdx + 1) + t.point
                        }
                        else -> sh
                    }
                    val startAt = cumNow()
                    if (i == 0 && li == 0) steps += NavStep("depart", "straight", g.name(e), 0, s.point, null)
                    if (i > 0) step(g, leg, i)?.let { st ->
                        // the same turn again a few metres on (a split junction): say it once
                        val last = steps.lastOrNull()
                        if (last != null && last.type != "depart" && last.road.isNotBlank() && last.road == st.road && st.type != "roundabout" &&
                            Geo.distance(last.pos, st.pos) < 80) return@let
                        steps += st
                    }
                    addPts(part)
                    val len = cumNow() - startAt
                    seconds += len / (g.speed(e) / 3.6)
                    if (i > 0) seconds += turnCost(turn(inBearing(g, leg[i - 1] shr 1, vertexBetween(g, leg[i - 1])), outBearing(g, e, vertexBetween(g, leg[i - 1]))))
                    if (g.flags(e) and 8 != 0) toll = true
                    if (g.flags(e) and 32 != 0) motorway = true
                    g.name(e).takeIf { it.isNotBlank() }?.let { roadLen[it] = (roadLen[it] ?: 0.0) + len }
                    val mxs = g.maxspeed(e)
                    if (mxs > 0 && len > 0) {
                        val last = limits.lastOrNull()
                        if (last != null && last.value == mxs && abs(last.end - startAt) < 1) limits[limits.size - 1] = Stretch(last.start, startAt + len, mxs)
                        else limits += Stretch(startAt, startAt + len, mxs)
                    }
                }
            }
            steps += NavStep(if (li == legs.size - 1) "arrive" else "waypoint", if (li == legs.size - 1) "arrive" else "straight", "", 0, t.point, null)
        }
        val via = roadLen.entries.sortedByDescending { it.value }.take(2).joinToString(" & ") { it.key }
        val distance = cumNow()
        seconds *= 1.15
        return RouteOption(pts, steps, distance, seconds, via, toll, motorway).also { it.limits = limits }
    }

    private fun vertexBetween(g: Graph, st: Int) = if (st and 1 == 0) g.eB[st shr 1] else g.eA[st shr 1]

    /** The maneuver between leg[i-1] and leg[i] (or null: just keep going). */
    private fun step(g: Graph, leg: List<Int>, i: Int): NavStep? {
        val prev = leg[i - 1]; val st = leg[i]
        val e0 = prev shr 1; val e = st shr 1
        val v = vertexBetween(g, prev)
        val pos = LatLng(g.vLat[v] / 1e6, g.vLon[v] / 1e6)
        val f0 = g.flags(e0); val f1 = g.flags(e)
        val name0 = g.name(e0); val name = g.name(e)
        val inB = inBearing(g, e0, v)
        val d = turn(inB, outBearing(g, e, v))
        // inside a roundabout: nothing (said when entering it)
        if (f0 and 4 != 0) return null
        if (f1 and 4 != 0) {
            // entering: count the exits up to the one we take
            var exits = 0
            var j = i
            var lastV = v
            while (j < leg.size && g.flags(leg[j] shr 1) and 4 != 0) {
                val vv = vertexBetween(g, leg[j])
                lastV = vv
                if (j + 1 < leg.size && g.flags(leg[j + 1] shr 1) and 4 != 0) {
                    // an exit we pass: another road leaving the roundabout here
                    for (k in g.adjOff[vv] until g.adjOff[vv + 1]) {
                        val o = g.adj[k]
                        if (g.flags(o) and 4 == 0 && canLeave(g, o, vv)) { exits++; break }
                    }
                }
                j++
            }
            val outName = if (j < leg.size) g.name(leg[j] shr 1) else name
            val finalB = if (j < leg.size) outBearing(g, leg[j] shr 1, lastV) else inB
            return NavStep("roundabout", modifier(turn(inB, finalB)), outName, exits + 1, pos, null)
        }
        // the other ways out of this junction
        var others = 0; var closeOthers = 0; var closeSide = 0.0
        for (k in g.adjOff[v] until g.adjOff[v + 1]) {
            val o = g.adj[k]
            if (o == e0 || o == e || !canLeave(g, o, v)) continue
            others++
            val od = turn(inB, outBearing(g, o, v))
            // a real fork: the other way is as big a road as ours (not a side street or a slip road)
            if (abs(od - d) < 40 && g.cls(o) <= g.cls(e) && (g.flags(o) and 16 == 0 || f1 and 16 != 0)) { closeOthers++; closeSide = od }
        }
        val m = modifier(d)
        val ramp0 = f0 and 16 != 0; val ramp1 = f1 and 16 != 0
        val mw0 = f0 and 32 != 0; val mw1 = f1 and 32 != 0
        // where a slip road leads: onto a motorway, or just to another road (and that road's name)
        var leadsTo = -1
        if (ramp1 && !ramp0) {
            var j = i
            while (j < leg.size && g.flags(leg[j] shr 1) and 16 != 0 && j < i + 12) j++
            if (j < leg.size) leadsTo = leg[j] shr 1
        }
        val toMotorway = leadsTo >= 0 && g.flags(leadsTo) and 32 != 0
        val side = if (m == "straight") (if (d < 0) "slight left" else "slight right") else m
        return when {
            ramp1 && !ramp0 && mw0 -> NavStep("off ramp", side, name.ifBlank { if (leadsTo >= 0) g.name(leadsTo) else "" }, 0, pos, null)
            ramp1 && !ramp0 && toMotorway -> NavStep("on ramp", side, g.name(leadsTo), 0, pos, null)
            // a slip road to an ordinary road: a turn onto that road
            ramp1 && !ramp0 -> if (others > 0) NavStep("turn", side, if (leadsTo >= 0) g.name(leadsTo) else name, 0, pos, null) else null
            ramp0 && !ramp1 && mw1 -> NavStep("merge", if (d < 0) "slight left" else "slight right", name, 0, pos, null)
            // the end of a slip road onto an ordinary road: already said when it began
            ramp0 && !ramp1 && !mw0 -> if (others > 0 && m != "straight" && !m.startsWith("slight")) NavStep("turn", m, name, 0, pos, null) else null
            others == 0 -> null // no choice here: nothing to say
            m == "straight" && closeOthers > 0 -> NavStep("fork", if (closeSide > d) "slight left" else "slight right", name, 0, pos, null)
            m == "straight" -> if (name.isNotBlank() && name != name0) NavStep("new name", "straight", name, 0, pos, null) else null
            else -> NavStep("turn", m, name, 0, pos, null)
        }
    }

    private fun modifier(d: Double): String {
        val a = abs(d)
        val side = if (d > 0) "right" else "left"
        return when {
            a < 20 -> "straight"
            a < 45 -> "slight $side"
            a < 125 -> side
            a < 165 -> "sharp $side"
            else -> "uturn"
        }
    }

    // ---------------------------------------------------------------- download

    @Volatile var downloading = false
        private set
    @Volatile var progress = 0f
        private set

    /** Downloads (or refreshes) the road file. Off the main thread. True if it's ready. */
    fun download(onProgress: () -> Unit = {}): Boolean {
        if (downloading) return false
        downloading = true; progress = 0f; onProgress()
        try {
            val tmp = File(file.parentFile, "roads-gr.part")
            val c = java.net.URL(URL).openConnection() as java.net.HttpURLConnection
            c.instanceFollowRedirects = true
            c.connectTimeout = 15000; c.readTimeout = 30000
            c.setRequestProperty("User-Agent", "GameRoute/1.0 (Android)")
            if (c.responseCode != 200) return false
            val total = c.contentLengthLong.coerceAtLeast(1)
            var got = 0L; var lastTick = 0L
            java.util.zip.GZIPInputStream(object : java.io.FilterInputStream(c.inputStream) {
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val n = super.read(b, off, len)
                    if (n > 0) {
                        got += n; progress = got.toFloat() / total
                        val now = System.currentTimeMillis()
                        if (now - lastTick > 500) { lastTick = now; onProgress() }
                    }
                    return n
                }
            }, 1 shl 16).use { input -> tmp.outputStream().buffered(1 shl 16).use { input.copyTo(it, 1 shl 16) } }
            c.disconnect()
            close()
            // check it opens before it replaces the old one
            RandomAccessFile(tmp, "r").use { raf -> Graph(raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())) }
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
            return ready()
        } catch (e: Throwable) {
            CrashLog.note("road file download: ${e.message}")
            return false
        } finally {
            downloading = false; onProgress()
        }
    }

    fun delete() { close(); file.delete() }
}
