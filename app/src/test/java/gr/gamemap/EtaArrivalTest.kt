package gr.gamemap

import android.location.Location
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * The arrival time doesn't jump around (one odd fix, a reroute); the route ends by itself 30 s
 * after arriving even with no GPS fixes; my nickname for a friend shows in the convoy.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EtaArrivalTest {
    private val data = File(System.getProperty("previewData") ?: "../previewdata")
    private fun route() = Osrm.parseAll(File(data, "route.json").readText())[0]
    private fun fix(p: LatLng, kmh: Float, bearing: Float, t: Long) = Location("gps").apply {
        latitude = p.lat; longitude = p.lon; speed = kmh / 3.6f; this.bearing = bearing; accuracy = 5f; time = t
    }

    @Test
    fun steadyArrivalTime() {
        val r = route()
        val log = StringBuilder()
        NavEngine.debugDrive(Place("End", "", r.points.last()), r, 0)
        val n = r.points.size
        var t = System.currentTimeMillis()
        val arrivals = mutableListOf<Long>()
        for (i in 1 until n / 2) {
            val p = r.points[i]; val q = r.points[i + 1]
            NavEngine.onLocation(fix(p, 40f, Geo.bearing(p, q), t))
            arrivals += System.currentTimeMillis() + (NavEngine.remainingSeconds * 1000).toLong()
            t += 1000; Thread.sleep(5)
        }
        // one wild fix 400 m ahead along the route, then back
        val far = NavEngine.aheadOnRoute(r.points[n / 2], 400.0) ?: r.points[n / 2 + 3]
        val before = System.currentTimeMillis() + (NavEngine.remainingSeconds * 1000).toLong()
        NavEngine.onLocation(fix(far, 40f, 0f, t)); t += 1000
        val after = System.currentTimeMillis() + (NavEngine.remainingSeconds * 1000).toLong()
        log.append("one wild fix moved the arrival by ${(after - before) / 1000} s\n")
        val steps = arrivals.zipWithNext { a, b -> Math.abs(b - a) / 1000 }
        log.append("biggest change between fixes: ${steps.maxOrNull()} s over ${steps.size} fixes\n")
        File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }.resolve("eta.txt").writeText(log.toString())
        assertTrue("arrival jumped ${steps.maxOrNull()} s between fixes", (steps.maxOrNull() ?: 0) <= 25)
        assertTrue("one wild fix moved the arrival ${(after - before) / 1000} s", Math.abs(after - before) <= 25_000)
        NavEngine.stop(); NavEngine.dismissTrip()
    }

    @Test
    fun routeEndsByItselfAfterArriving() {
        val r = route()
        NavEngine.debugDrive(Place("End", "", r.points.last()), r, 0)
        val n = r.points.size
        var t = System.currentTimeMillis()
        for (i in n - 6 until n) {
            val p = r.points[i]; val q = r.points[minOf(i + 1, n - 1)]
            NavEngine.onLocation(fix(p, if (i == n - 1) 0f else 15f, Geo.bearing(p, q), t)); t += 1000
        }
        assertTrue("arrived", NavEngine.arrived)
        // no more GPS fixes (a parked phone): only the clock
        val looper = shadowOf(Looper.getMainLooper())
        Thread.sleep(15_000); looper.idleFor(java.time.Duration.ofSeconds(16))
        assertTrue("15 s: still showing", NavEngine.arrived)
        Thread.sleep(17_000); looper.idleFor(java.time.Duration.ofSeconds(17))
        assertFalse("30 s after arriving the route has ended", NavEngine.active)
        assertNull("and the card has gone", NavEngine.lastTrip)
    }

    @Test
    fun nicknameInConvoy() {
        Robolectric.buildActivity(MainActivity::class.java).setup()
        assertTrue(Friends.ready)
        Friends.core.setNickname("f-nikos", "Nikolakis")
        Convoy.debugMember("Nikos", LatLng(37.98, 23.73), 50, 0, -1.0, -1.0, "driving")
        val m = Convoy.members.values.first { it.theirName == "Nikos" }
        assertEquals("Nikos", m.name)
        m.fid = "f-nikos"
        assertEquals("Nikolakis", m.name)
        Thread.sleep(600) // the list is kept half a second
        assertTrue(Convoy.rows(0).any { it.name == "Nikolakis" })
        Friends.core.setNickname("f-nikos", "")
        assertEquals("Nikos", m.name)
        Convoy.members.clear()
    }
}
