package gr.gamemap

import android.location.Location
import android.os.Looper
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/** The camera while driving: out on highways, in on small streets, in earlier before turning onto one. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoadZoomTest {
    private fun tileOf(p: LatLng): Pair<Int, Int> {
        val n = (1 shl 14).toDouble(); val r = p.lat * PI / 180
        return floor((p.lon + 180) / 360 * n).toInt() to floor((1 - ln(tan(r) + 1 / cos(r)) / PI) / 2 * n).toInt()
    }

    @Test
    fun zoom() {
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        Traffic.init(RuntimeEnvironment.getApplication())
        val log = StringBuilder()
        val counts = HashMap<String, MutableList<Double>>()
        // Marousi → the airport (Attiki Odos), then into Spata's small streets; and a city trip
        for ((name, a, b) in listOf(
            Triple("Marousi → airport", LatLng(38.0480, 23.8080), LatLng(37.9370, 23.9460)),
            Triple("Syntagma → Exarchia side streets", LatLng(37.9755, 23.7348), LatLng(37.9870, 23.7330)),
            Triple("Glyfada → Voula small streets", LatLng(37.8650, 23.7530), LatLng(37.8455, 23.7690)),
        )) {
            val r = Router.routes(a, b, alternatives = false)[0]
            // the map squares along the way
            val tiles = r.points.map { tileOf(it) }.toSet()
            for ((x, y) in tiles) try { VectorSource.get(14, x, y) } catch (_: Exception) {}
            log.append("\n== $name: ${(r.length / 1000).toInt()} km, ${tiles.size} squares\n")
            NavEngine.debugDrive(Place(name, "", b), r, 0)
            var m = 0.0
            var last = ""
            while (m < r.length - 30) {
                val i = r.cum.indexOfFirst { it >= m }.coerceAtLeast(1)
                val p = r.points[i]; val prev = r.points[i - 1]
                NavEngine.updateRoadKinds()
                val kind = NavEngine.roadHere ?: "?"
                val kmh = when (kind) { "motorway", "trunk" -> 100f; "ramp" -> 60f; "primary" -> 60f; "secondary" -> 45f; "minor", "service" -> 25f; else -> 40f }
                LocationTracker.onLocationChanged(Location("gps").apply {
                    latitude = p.lat; longitude = p.lon; speed = kmh / 3.6f; bearing = Geo.bearing(prev, p); accuracy = 4f; time = System.currentTimeMillis()
                })
                shadowOf(Looper.getMainLooper()).idle()
                NavEngine.updateRoadKinds()
                val z = MapState.phone.autoZoomForTest()
                counts.getOrPut(NavEngine.roadHere ?: "?") { ArrayList() } += z
                val line = "here=${NavEngine.roadHere} next=${NavEngine.roadNext} turn in ${NavEngine.distanceToNext.toInt()} m (${NavEngine.currentStep?.direction}) → zoom ${"%+.1f".format(z)}"
                if (line != last) log.append("  ${(m / 1000).let { "%.2f".format(it) }} km  $line\n")
                last = line
                m += 40
            }
            NavEngine.stop()
        }
        log.append("\naverage zoom by road kind: " + counts.entries.joinToString { (k, v) -> "$k ${"%+.2f".format(v.average())} (${v.size})" } + "\n")
        File(out, "roadzoom.txt").writeText(log.toString())
        val hw = counts["motorway"]?.average() ?: 0.0
        val small = (counts["minor"].orEmpty() + counts["service"].orEmpty()).average()
        assertTrue("highways out ($hw), small streets in ($small)", hw < -0.6 && small > 0.1)
    }
}
