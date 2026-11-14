package gr.gamemap

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/** How long each way of asking for a new route takes (a reroute in Athens, a long trip). */
@RunWith(RobolectricTestRunner::class)
class RerouteProbeTest {
    @Test
    fun probe() {
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        Traffic.init(RuntimeEnvironment.getApplication())
        val log = StringBuilder()
        val trips = listOf(
            "Pagrati→Glyfada" to (LatLng(37.9685, 23.7480) to LatLng(37.8650, 23.7530)),
            "Kifisia→Piraeus" to (LatLng(38.0740, 23.8110) to LatLng(37.9420, 23.6465)),
            "Athens→Nafplio" to (LatLng(37.9755, 23.7348) to LatLng(37.5675, 22.8016)),
        )
        fun time(label: String, f: () -> List<RouteOption>) {
            val t0 = System.nanoTime()
            val r = try { f() } catch (e: Exception) { log.append("  $label: ERR ${e.message?.take(80)}\n"); return }
            log.append("  $label: ${(System.nanoTime() - t0) / 1_000_000} ms, ${r.size} routes\n")
        }
        for (round in 1..3) for ((name, ft) in trips) {
            val (a, b) = ft
            log.append("$name (round $round)\n")
            time("osrm reroute alt=3") { Osrm.routes(a, b, alternatives = true, heading = 90f, reroute = true) }
            time("osrm reroute no-alt") { Osrm.routes(a, b, alternatives = false, heading = 90f, reroute = true) }
            time("tomtom no-alt") { TomTomRoute.routes(a, b, false, 90f, emptyList()) }
            Thread.sleep(400)
        }
        File(out, "rerouteprobe.txt").writeText(log.toString())
    }
}
