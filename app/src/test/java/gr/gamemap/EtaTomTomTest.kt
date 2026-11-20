package gr.gamemap

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The arrival time jumping 6 → 15 → 6 (drive, 20 Nov): TomTom's time was asked from the raw GPS
 * point, and a few metres off it routed a detour back onto the route. Now: asked from the spot on
 * the route, and an answer that went another way is thrown away. Live against TomTom along the
 * test route: the times from one point to the next must be steady.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EtaTomTomTest {
    @Test
    fun steady() {
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        val log = StringBuilder()
        // the rule on its own
        assertTrue(TomTomTime.followsRoute(5000.0, 5200))
        assertFalse("a detour round the block", TomTomTime.followsRoute(3000.0, 4100))
        assertTrue("no length given", TomTomTime.followsRoute(3000.0, 0))
        if (!Traffic.active) { File(out, "etatomtom.txt").writeText("no TomTom key: rule checked only\n"); return }
        val data = File(System.getProperty("previewData") ?: "../previewdata")
        val r = Osrm.parseAll(File(data, "route.json").readText())[0]
        log.append("route ${r.length.toInt()} m, ${r.duration.toInt()} s by OSRM\n")
        var last: Double? = null; var worst = 0.0; var got = 0
        for (k in 0..6) {
            val from = r.length * k / 8
            Thread.sleep(1500) // (TomTom's free key: a few requests a second at most)
            val res = (try { TomTomTime.evaluate(r, from) } catch (e: Exception) { Thread.sleep(4000); try { TomTomTime.evaluate(r, from) } catch (e2: Exception) { log.append("from ${from.toInt()} m: ${e2.message}\n"); null } })
                ?: run { log.append("from ${from.toInt()} m: no answer\n"); null } ?: continue
            got++
            val left = r.length - from
            // per metre, so points along the way compare
            val spm = res.seconds / left
            log.append("from ${from.toInt()} m: TomTom ${res.seconds} s for ${res.meters} m (our ${left.toInt()} m left) · ${"%.3f".format(spm)} s/m\n")
            last?.let { worst = maxOf(worst, Math.abs(spm / it - 1)) }
            last = spm
        }
        log.append("biggest change between neighbouring points: ${"%.0f".format(worst * 100)}%\n")
        // TomTom's own route A → B (no points to follow), with and without traffic, to compare
        for (traffic in listOf(true, false)) {
            Thread.sleep(1500)
            try {
                val a = r.points.first(); val b = r.points.last()
                val o = org.json.JSONObject(Http.get("https://api.tomtom.com/routing/1/calculateRoute/${a.lat},${a.lon}:${b.lat},${b.lon}/json?key=${Traffic.key}&traffic=$traffic&travelMode=car&computeTravelTimeFor=all"))
                val sum = o.getJSONArray("routes").getJSONObject(0).getJSONObject("summary")
                log.append("TomTom's own route, traffic=$traffic: ${sum.optInt("travelTimeInSeconds")} s for ${sum.optInt("lengthInMeters")} m (no traffic ${sum.optInt("noTrafficTravelTimeInSeconds")} s, historic ${sum.optInt("historicTrafficTravelTimeInSeconds")} s, live ${sum.optInt("liveTrafficIncidentsTravelTimeInSeconds")} s)\n")
            } catch (e: Exception) { log.append("own route: ${e.message}\n") }
        }
        File(out, "etatomtom.txt").writeText(log.toString())
        assertTrue("TomTom answered", got >= 4)
        assertTrue("TomTom's time per metre jumped ${"%.0f".format(worst * 100)}% between neighbouring points", worst < 0.5)
    }
}
