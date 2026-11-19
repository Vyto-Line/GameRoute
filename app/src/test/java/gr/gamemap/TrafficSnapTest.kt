package gr.gamemap

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.Rect
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.hypot
import kotlin.math.pow

/**
 * Live TomTom traffic over central Athens: every drawn traffic line must lie on one of the map's
 * own roads (within the road's half width plus a lane), not across the blocks. Compares the old
 * way (only TomTom's corners snapped) with the new one (the line walked along the road).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class TrafficSnapTest {
    @Test
    fun onTheRoads() {
        val data = File(System.getProperty("previewData") ?: "../previewdata")
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        val z14 = data.listFiles()?.filter { it.name.endsWith(".pbf") }?.map { f ->
            val (x, y) = f.nameWithoutExtension.split("_").map { it.toInt() }
            VectorSource.put(14, x, y, VectorTile(f.readBytes())); x to y
        } ?: emptyList()
        val log = StringBuilder("traffic active=${Traffic.active}, map squares ${z14.size}\n")
        if (!Traffic.active) { File(out, "trafficsnap.txt").writeText(log.append("no TomTom key: skipped\n").toString()); return }
        // the video's area (Nea Smyrni / Neos Kosmos, Ilia Iliou): map data downloaded live
        VectorSource.init(org.robolectric.RuntimeEnvironment.getApplication())
        val vx = Geo.x(23.7215, 16384.0).toInt(); val vy = Geo.y(37.9555, 16384.0).toInt()
        val video = ArrayList<Pair<Int, Int>>()
        for (dy in -1..1) for (dx in -1..1) if (VectorSource.get(14, vx + dx, vy + dy) != null) video += (vx + dx) to (vy + dy)
        log.append("video area squares ${video.size}\n")
        val d = 2.75f; val u = d
        val r = MapRenderer()
        val results = ArrayList<Double>()
        for ((areaName, area) in listOf("video area" to video, "central Athens" to z14)) for (z in listOf(15, 16)) {
        val f = 1 shl (z - 14)
        val squares = area.flatMap { (x, y) -> (0 until f).flatMap { dx -> (0 until f).map { dy -> (x * f + dx) to (y * f + dy) } } }.take(48)
        // fetch them all
        val deadline = System.currentTimeMillis() + 40_000
        while (System.currentTimeMillis() < deadline && squares.any { (x, y) -> Traffic.tile(z, x, y) == null }) Thread.sleep(300)
        val tilePx = TileCache.tilePx.toDouble()
        val span = tilePx
        val world = tilePx * 2.0.pow(z)
        var newOff = 0; var newAll = 0; var oldOff = 0; var oldAll = 0; var ms = 0L; var lines = 0
        val worst = ArrayList<String>()
        for ((ax, ay) in squares) {
            val t = Traffic.tile(z, ax, ay) ?: continue
            val lat = Geo.lat((ay + 0.5) * span, world)
            val mpp = Geo.metersPerPixel(lat, world).toFloat()
            val measure = RoadSnap.index(z, ax * span, ay * span, span, 40f / mpp) ?: continue
            fun offRoadMeters(x: Float, y: Float): Float {
                val cand = HashSet<Int>(); measure.near(x, y, cand)
                var best = Float.MAX_VALUE
                for (i in cand) {
                    val s = measure.segs[i]
                    val sx = s[2] - s[0]; val sy = s[3] - s[1]; val l2 = sx * sx + sy * sy
                    val tt = if (l2 == 0f) 0f else (((x - s[0]) * sx + (y - s[1]) * sy) / l2).coerceIn(0f, 1f)
                    // beyond the road's own half width
                    val dd = hypot(x - (s[0] + sx * tt), y - (s[1] + sy * tt)) - s[5]
                    if (dd < best) best = dd
                }
                return best * mpp
            }
            fun check(p: Path, isNew: Boolean) {
                val pm = PathMeasure(p, false)
                do {
                    var at = 0f
                    while (at <= pm.length) {
                        val pos = FloatArray(2); pm.getPosTan(at, pos, null)
                        val off = offRoadMeters(pos[0], pos[1])
                        if (isNew) { newAll++; if (off > 6f) { newOff++; if (worst.size < 8) worst += "${"%.0f".format(off)} m off at $ax/$ay" } }
                        else { oldAll++; if (off > 6f) oldOff++ }
                        at += 4f / mpp
                    }
                } while (pm.nextContour())
            }
            val t0 = System.nanoTime()
            val tp = r.buildTraffic(t, ax, ay, span, u, z)
            ms += (System.nanoTime() - t0) / 1_000_000
            for (st in 1..3) check(tp.byState[st], true)
            // the old way: only TomTom's corners snapped, unmatched corners where TomTom put them
            val maxSnap = maxOf(25f / mpp, 12f * u)
            val roads = RoadSnap.index(z, ax * span, ay * span, span, maxSnap) ?: continue
            val k = (span / t.extent).toFloat()
            for (sg in t.segs) {
                if (sg.state == Traffic.FREE) continue
                val base = FloatArray(sg.pts.size) { sg.pts[it] * k }
                val lane = RoadSnap.snapLane(roads, base, base, maxSnap, 1f, 1.8f * u) ?: continue
                val p = Path(); p.moveTo(lane[0], lane[1]); for (i in 1 until lane.size / 2) p.lineTo(lane[i * 2], lane[i * 2 + 1])
                check(p, false); lines++
            }
        }
        val newPct = if (newAll == 0) 0.0 else newOff * 100.0 / newAll
        val oldPct = if (oldAll == 0) 0.0 else oldOff * 100.0 / oldAll
        log.append("== $areaName, zoom $z\n")
        log.append("traffic lines: $lines\n")
        log.append("old: ${"%.1f".format(oldPct)}% of the drawn line more than 6 m off any road ($oldOff of $oldAll points)\n")
        log.append("new: ${"%.1f".format(newPct)}% ($newOff of $newAll points); building all squares took $ms ms\n")
        worst.forEach { log.append("  $it\n") }
        results += newPct
        }
        // a picture: the map with the new traffic, around the video's area is outside the data, so central Athens
        MapState.setTheme(Theme.VIOLET_RAIN); MapState.zoomBy(15.6 - MapState.targetZoom)
        LocationTracker.onLocationChanged(android.location.Location("gps").apply { latitude = 37.9555; longitude = 23.7215; accuracy = 5f; time = System.currentTimeMillis() })
        repeat(200) { MapState.tick(); Thread.sleep(2) }
        val frame = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888)
        val c = Canvas(frame)
        repeat(40) { r.draw(c, 1080, 1920, Rect(0, 0, 1080, 1920), d, hud = false); Thread.sleep(60) }
        File(out, "traffic_on_roads.png").outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 90, it) }
        File(out, "trafficsnap.txt").writeText(log.toString())
        assertTrue("new traffic lines off the roads: $results", results.all { it < 2.0 })
    }
}
