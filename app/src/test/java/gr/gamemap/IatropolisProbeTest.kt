package gr.gamemap

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/** Where search puts "Iatropolis" near Argyroupolis: each result, its point, its entrance, and the app's pick. */
@RunWith(RobolectricTestRunner::class)
class IatropolisProbeTest {
    @Test
    fun probe() {
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        val app = RuntimeEnvironment.getApplication()
        Traffic.init(app); Places.init(app)
        val log = StringBuilder()
        val argy = LatLng(37.9045, 23.7480) // Leoforos Vouliagmenis, Argyroupolis
        for (q in listOf("Iatropolis", "iatropolis argyroupoli", "Ιατρόπολις", "Ιατρόπολις Αργυρούπολη")) {
            log.append("\n=== \"$q\"\n")
            for ((name, f) in listOf<Pair<String, () -> List<Place>>>(
                "search (the app's)" to { Places.search(q, argy) },
                "photon" to { Places.photon(q, argy) },
                "nominatim" to { Places.nominatim(q, argy) },
            )) {
                val r = try { f() } catch (e: Exception) { log.append(" $name: ERR $e\n"); continue }
                log.append(" $name:\n")
                for (p in r.take(6)) log.append("   '${p.name}' · ${p.address} · ${p.pos.lat},${p.pos.lon} · ${"%.0f".format(Geo.distance(argy, p.pos))} m away" +
                    (p.entry?.let { e -> " · entrance ${e.lat},${e.lon} (${"%.0f".format(Geo.distance(e, p.pos))} m from the place)" } ?: "") + "\n")
            }
        }
        File(out, "iatropolis.txt").writeText(log.toString())
    }
}
