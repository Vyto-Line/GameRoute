package gr.gamemap

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** Where routes to a place off the road (Davelis cave, Penteli) end, from each router. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CaveRouteProbeTest {
    @Test
    fun probe() {
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        Traffic.init(RuntimeEnvironment.getApplication())
        val log = StringBuilder()
        val from = LatLng(37.9310, 23.7560)
        val cave = LatLng(38.0708, 23.8773)
        fun f(p: LatLng) = "%.5f,%.5f".format(p.lat, p.lon)
        try {
            val raw = Http.get("https://router.project-osrm.org/route/v1/driving/${from.lon},${from.lat};${cave.lon},${cave.lat}?overview=false&steps=true", timeout = 15000)
            val o = org.json.JSONObject(raw)
            val wps = o.getJSONArray("waypoints")
            for (i in 0 until wps.length()) { val w = wps.getJSONObject(i); log.append("OSRM waypoint $i: '${w.optString("name")}' snapped ${"%.0f".format(w.getDouble("distance"))} m from the point\n") }
            val legs = o.getJSONArray("routes").getJSONObject(0).getJSONArray("legs").getJSONObject(0).getJSONArray("steps")
            for (i in maxOf(0, legs.length() - 8) until legs.length()) { val s = legs.getJSONObject(i); log.append("   step '${s.optString("name")}' ${s.optString("ref")} ${s.getJSONObject("maneuver").optString("type")} ${s.getDouble("distance").toInt()} m\n") }
        } catch (e: Exception) { log.append("OSRM raw: $e\n") }
        for (alt in listOf(false, true)) {
            val rs = Router.routes(from, cave, alternatives = alt)
            for (r in rs) {
                val end = r.points.last()
                log.append("Router(alt=$alt) ${Router.lastSource}: ${(r.length / 1000).toInt()} km, ends ${f(end)} = ${Geo.distance(end, cave).toInt()} m from the cave; last steps: ${r.steps.takeLast(4).joinToString(" | ") { it.road + " " + it.direction }}\n")
            }
        }
        // the kind of road over the last 5 km (map data)
        val r = Router.routes(from, cave, alternatives = false)[0]
        val n = (1 shl 14).toDouble()
        for (p in r.points.filterIndexed { i, _ -> r.cum[i] > r.length - 5000 }) {
            val x = Math.floor((p.lon + 180) / 360 * n).toInt(); val la = p.lat * Math.PI / 180
            val y = Math.floor((1 - Math.log(Math.tan(la) + 1 / Math.cos(la)) / Math.PI) / 2 * n).toInt()
            try { VectorSource.get(14, x, y) } catch (_: Exception) {}
        }
        var m = r.length - 5000; var last = ""
        while (m < r.length) {
            // all classes near the point (paths and tracks too)
            val k = RoadKind.along(r, m) ?: "?"
            val raw = RoadKind.allNear(r, m)
            val line = "$k / $raw"
            if (line != last) log.append("  at ${"%.2f".format((r.length - m) / 1000)} km before the end: $line\n")
            last = line; m += 50
        }
        // the app's answer: where the car road ends
        val tail = RoadKind.dirtTail(r)
        log.append("\ndirtTail: ${tail?.let { "car road ends ${(r.length - it.first).toInt()} m before the end, at ${f(it.second)}, ${Geo.distance(it.second, cave).toInt()} m from the cave" } ?: "none"}\n")
        org.junit.Assert.assertNotNull(tail)
        // a normal town address: no tail
        val r2 = Router.routes(from, LatLng(37.9755, 23.7348), alternatives = false)[0]
        log.append("Syntagma: ${RoadKind.dirtTail(r2)}\n")
        org.junit.Assert.assertNull(RoadKind.dirtTail(r2))
        // what roads are near the cave (Nominatim reverse at the cave and the end)
        try { log.append("reverse at cave: " + Http.get("https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=${cave.lat}&lon=${cave.lon}&zoom=17", timeout = 8000).take(400) + "\n") } catch (e: Exception) { log.append("rev: $e\n") }
        File(out, "caveroute.txt").writeText(log.toString())
    }
}
