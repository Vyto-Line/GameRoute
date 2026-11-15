package gr.gamemap

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/** A town typed from far away comes first, before nearby streets named after it. */
@RunWith(RobolectricTestRunner::class)
class CitySearchTest {
    @Test
    fun towns() {
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        Traffic.init(RuntimeEnvironment.getApplication()); Places.init(RuntimeEnvironment.getApplication())
        val athens = LatLng(37.9755, 23.7348)
        fun d(b: LatLng) = "%.0f km".format(Geo.distance(athens, b) / 1000)
        val log = StringBuilder(); val bad = ArrayList<String>()
        // query → the town and roughly how far it is from Athens (km)
        val cases = listOf("ναυπλιο" to 90..100, "Ναύπλιο" to 85..100, "nafplio" to 85..100, "ναυπλ" to 85..100,
            "πατρα" to 170..185, "patra" to 170..185, "λαμια" to 145..160, "τριπολη" to 125..135,
            "καλαματα" to 170..185, "βολος" to 160..175, "κορινθος" to 65..80,
            // sights: the place itself, not the roads named after it
            "σουνιο" to 38..48, "sounio" to 38..48, "Σούνιο" to 38..48, "μυκηνες" to 85..95, "αραχωβα" to 105..120)
        for ((q, km) in cases) {
            val t0 = System.currentTimeMillis()
            val r = Places.find(q, athens, full = false).orEmpty()
            val ms = System.currentTimeMillis() - t0
            log.append("\"$q\" (${ms} ms): " + r.take(5).joinToString(" | ") { "${it.icon} ${it.name} t${it.town} ${d(it.pos)}" } + "\n")
            val first = r.firstOrNull()
            if (first == null || (Geo.distance(athens, first.pos) / 1000).toInt() !in km || first.icon == "🛣") bad += q
            Thread.sleep(1500) // TomTom allows a few requests a second
        }
        // still: a shop near me by name, a street with a number
        for (q in listOf("crepa land", "lidl", "αιγυπτου 25 γλυφαδα")) {
            val r = Places.find(q, LatLng(37.8650, 23.7530), full = false).orEmpty()
            log.append("\"$q\" near Glyfada: " + r.take(4).joinToString(" | ") { "${it.icon} ${it.name} ${"%.1f km".format(Geo.distance(LatLng(37.8650, 23.7530), it.pos) / 1000)}" } + "\n")
            Thread.sleep(1500)
        }
        log.append("\nwrong first: $bad\n")
        File(out, "citysearch.txt").writeText(log.toString())
        assertTrue("town not first for $bad", bad.isEmpty())
    }
}
