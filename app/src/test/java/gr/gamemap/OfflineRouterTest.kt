package gr.gamemap

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** Offline routing: the real road file, real trips, compared with OSRM online. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OfflineRouterTest {
    @Test
    fun routes() {
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        val app = RuntimeEnvironment.getApplication()
        OfflineRouter.init(app)
        val log = StringBuilder()
        val t0 = System.currentTimeMillis()
        val ok = OfflineRouter.download()
        log.append("download: $ok in ${(System.currentTimeMillis() - t0) / 1000} s, ${OfflineRouter.bytes() / 1_000_000} MB\n")
        assertTrue(ok)
        val trips = listOf(
            Triple("Ilioupoli → Glyfada", LatLng(37.9310, 23.7560), LatLng(37.8650, 23.7530)),
            Triple("Syntagma → Exarchia", LatLng(37.9755, 23.7348), LatLng(37.9870, 23.7330)),
            Triple("Marousi → airport", LatLng(38.0480, 23.8080), LatLng(37.9370, 23.9460)),
            Triple("Athens → Nafplio", LatLng(37.9755, 23.7348), LatLng(37.5675, 22.8016)),
            Triple("Athens → Thessaloniki", LatLng(37.9755, 23.7348), LatLng(40.6401, 22.9444)),
            Triple("Ilioupoli → Davelis cave", LatLng(37.9310, 23.7560), LatLng(38.0708, 23.8773)),
        )
        var close = 0
        for ((name, a, b) in trips) {
            val s = System.currentTimeMillis()
            val r = OfflineRouter.route(a, b, null)
            val ms = System.currentTimeMillis() - s
            assertNotNull(name, r)
            val on = try { Osrm.routes(a, b, false)[0] } catch (e: Exception) { null }
            val ratio = on?.let { r!!.distance / it.distance }
            if (ratio != null && ratio in 0.85..1.2) close++
            log.append("\n== $name: ${"%.1f".format(r!!.distance / 1000)} km, ${(r.duration / 60).toInt()} min, via ${r.via}, ${r.steps.size} steps, $ms ms" +
                (on?.let { " | OSRM ${"%.1f".format(it.distance / 1000)} km, ${(it.duration / 60).toInt()} min" } ?: "") + "\n")
            r.steps.take(14).forEach { st -> log.append("   ${st.type}/${st.direction} '${st.road}' exit=${st.exit} → ${st.text}\n") }
            if (r.steps.size > 14) log.append("   … ${r.steps.size - 14} more\n")
            Thread.sleep(500)
        }
        // a second time: the arrays are ready (reroutes)
        val s2 = System.currentTimeMillis()
        OfflineRouter.route(LatLng(37.9755, 23.7348), LatLng(37.5675, 22.8016), 200f)
        log.append("\nAthens → Nafplio again (with a heading): ${System.currentTimeMillis() - s2} ms\n")
        File(out, "offlinerouter.txt").writeText(log.toString())
        assertTrue("most routes about as long as OSRM's ($close)", close >= 4)
    }
}
