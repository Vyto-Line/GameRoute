package gr.gamemap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** Coordinates and map links typed or pasted in the search box. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CoordSearchTest {
    private fun near(a: LatLng?, lat: Double, lon: Double) = a != null && Geo.distance(a, LatLng(lat, lon)) < 5

    @Test
    fun coordinates() {
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        val log = StringBuilder()
        val yes = listOf(
            "37.9715, 23.7257", "37.9715,23.7257", "37.9715 23.7257", " (37.9715, 23.7257) ",
            "37°58'17.4\"N 23°43'32.5\"E", "37°58'17.4\"N, 23°43'32.5\"E", "37°58′17.4″N 23°43′32.5″E",
            "37°58.29'N 23°43.542'E", "37.9715N 23.7257E", "23°43'32.5\"E 37°58'17.4\"N",
            "geo:37.9715,23.7257", "https://www.google.com/maps/place/Acropolis/@37.9715,23.7257,17z",
            "https://www.google.com/maps/search/?api=1&query=37.9715,23.7257",
            "https://www.openstreetmap.org/?mlat=37.9715&mlon=23.7257#map=17/37.9715/23.7257",
        )
        for (t in yes) {
            val p = SharedLocation.coordinates(t)
            log.append("${if (near(p, 37.9715, 23.7257)) "OK " else "BAD"} $t -> ${p?.let { SharedLocation.format(it) }}\n")
            assertTrue(t, near(p, 37.9715, 23.7257))
        }
        // southern / western
        assertTrue(near(SharedLocation.coordinates("-33.8568, 151.2153"), -33.8568, 151.2153))
        assertTrue(near(SharedLocation.coordinates("40°41'21.4\"N 74°02'40.2\"W"), 40.68928, -74.04450))
        // not coordinates
        for (t in listOf("Patision 44", "ναυπλιο", "12 Ermou", "37, 23", "Odos 3.5", "95.1, 23.7")) {
            val p = SharedLocation.coordinates(t)
            log.append("${if (p == null) "OK " else "BAD"} (not coordinates) $t -> $p\n")
            assertNull(t, p)
        }

        // the search: the spot first, with its address
        Traffic.init(RuntimeEnvironment.getApplication())
        val home = LatLng(37.98, 23.73)
        val loc = Places.local("37.9715, 23.7257", home)
        log.append("\nlocal (instant): ${loc.map { it.name + " | " + it.address }}\n")
        assertEquals(1, loc.size)
        for (q in listOf("37.9715, 23.7257", "37°58'17.4\"N 23°43'32.5\"E", "https://www.google.com/maps/place/Acropolis+Museum/@37.968,23.7285,17z")) {
            val r = Places.find(q, home, full = true)
            log.append("find '$q': ${r?.map { it.name + " | " + it.address + " @ " + SharedLocation.format(it.pos) }}\n")
            assertNotNull(r); assertTrue(r!!.isNotEmpty())
        }
        val named0 = Places.find("https://www.google.com/maps/place/Acropolis+Museum/@37.968,23.7285,17z", home, full = true)!!.first()
        assertEquals("Acropolis Museum", named0.name)
        // a link that only names the place: searched by name
        val named = Places.find("https://www.google.com/maps/search/?api=1&query=Acropolis+Museum", home, full = true)
        log.append("find (link with a name only): ${named?.take(2)?.map { it.name + " @ " + SharedLocation.format(it.pos) }}\n")
        File(out, "coordsearch.txt").writeText(log.toString())
    }
}
