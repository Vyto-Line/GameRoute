package gr.gamemap

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/** Probe (22 Nov): "μπουμπουλινας 17 Καλλιθέα" and "Καλλιθέα" from the south of Athens. */
@RunWith(RobolectricTestRunner::class)
class KallitheaProbeTest {
    @Test
    fun probe() {
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        Traffic.init(RuntimeEnvironment.getApplication()); Places.init(RuntimeEnvironment.getApplication())
        val near = LatLng(37.9340, 23.7290) // Agios Dimitrios
        val log = StringBuilder()
        fun km(p: LatLng) = "%.1f km".format(Geo.distance(near, p) / 1000)
        for (q in listOf("καλλ", "καλλιθ", "καλλιθεα", "Καλλιθέα", "kallithea", "μπουμπουλινας 17", "μπουμπουλινας 17 καλλιθεα", "Μπουμπουλίνας 17 Καλλιθέα", "μπουμπουλινας 17, καλλιθεα")) {
            for (full in listOf(false, true)) {
                val t0 = System.currentTimeMillis()
                val split = try { Places.splitAreaForTest(q, near) } catch (e: Exception) { "split error $e" }
                val r = try { Places.find(q, near, full) } catch (e: Exception) { log.append("  error $e\n"); null }
                log.append("\"$q\" full=$full (${System.currentTimeMillis() - t0} ms) split=$split\n")
                r.orEmpty().take(8).forEach { log.append("   ${it.icon} ${it.name} · ${it.address} · ${km(it.pos)} · ${it.pos}\n") }
                if (r == null) log.append("   (null)\n") else if (r.isEmpty()) log.append("   (nothing)\n")
                Thread.sleep(1500)
            }
        }
        for (q in listOf("μπουμπουλινας 17 καλλιθεα", "καλλιθεα")) {
            log.append("\nproviders for \"$q\":\n")
            try { log.append("  tomtom: " + Places.tryTomtomForTest(q, near).orEmpty().take(5).joinToString(" | ") { "${it.name} · ${it.address} ${km(it.pos)}" } + "\n") } catch (e: Exception) { log.append("  tomtom error $e\n") }
            Thread.sleep(1200)
            try { log.append("  photon: " + Places.photon(q, near).take(5).joinToString(" | ") { "${it.name} · ${it.address} ${km(it.pos)}" } + "\n") } catch (e: Exception) { log.append("  photon error $e\n") }
            try { log.append("  towns: " + Places.photon(q, near, townsOnly = true).take(5).joinToString(" | ") { "${it.name} · ${it.address} ${km(it.pos)}" } + "\n") } catch (e: Exception) { log.append("  towns error $e\n") }
            try { log.append("  address: " + Places.addressForTest(q, near)?.let { "${it.name} · ${it.address} ${km(it.pos)}" } + "\n") } catch (e: Exception) { log.append("  address error $e\n") }
            Thread.sleep(1200)
        }
        File(out, "kallithea.txt").writeText(log.toString())
    }
}
