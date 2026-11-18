package gr.gamemap

import android.location.Location
import android.os.Looper
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration

/** Test builds' simulated drive: real fixes (no tunnel mode), moving on, never pulled back after leaving the route. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SimDriveTest {
    @Test
    fun simulatedDrive() {
        val data = File(System.getProperty("previewData") ?: "../previewdata")
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        val log = StringBuilder()
        try {
            val r = Osrm.parseAll(File(data, "route.json").readText())[0]
            val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
            val looper = shadowOf(Looper.getMainLooper())
            val start = r.points[0]
            // a real GPS fix first (the simulator's fixes used to lose to it)
            LocationTracker.onLocationChanged(Location("gps").apply { latitude = start.lat; longitude = start.lon; accuracy = 5f; speed = 0f; time = System.currentTimeMillis() })
            NavEngine.debugDrive(Place("Museum", "", r.points.last()), r, 0)
            Dev.simulate(activity, null, startNav = false)
            var prev = LocationTracker.position!!
            var maxBack = 0.0; var est = 0; var lastAlong = 0.0
            for (s in 1..40) {
                looper.idleFor(Duration.ofMillis(1000))
                val p = LocationTracker.position!!
                if (NavEngine.estimating) est++
                log.append("t=$s along=${NavEngine.along.toInt()} v=${(LocationTracker.speedMps * 3.6).toInt()} km/h est=${NavEngine.estimating} weak=${LocationTracker.weak}\n")
                if (s == 20) { Dev.leaveRoute(); log.append("-- leave the route\n") }
                if (s > 20) {
                    // after leaving: each fix moves on from the last one (not back towards the old route)
                    val step = Geo.distance(prev, p)
                    if (step > 45) maxBack = maxOf(maxBack, step)
                }
                prev = p; if (s == 19) lastAlong = NavEngine.along
            }
            // fast forward: 4× the road's speed
            Dev.fastForward = 4
            val a0 = NavEngine.along
            Dev.stopSim(); NavEngine.debugDrive(Place("Museum", "", r.points.last()), r, 0)
            LocationTracker.onLocationChanged(Location("gps").apply { latitude = start.lat; longitude = start.lon; accuracy = 5f; speed = 0f; time = System.currentTimeMillis() })
            Dev.simulate(activity, null, startNav = false)
            // the arrow, as drawn, stays on the road (round the corners too): sampled every 100 ms
            var worst = 0.0
            repeat(150) {
                looper.idleFor(Duration.ofMillis(100))
                LocationTracker.tick()
                val shown = LocationTracker.displayPosition() ?: return@repeat
                var dmin = Double.MAX_VALUE
                for (i in 0 until r.points.size - 1) dmin = minOf(dmin, Geo.project(shown, r.points[i], r.points[i + 1]).second)
                worst = maxOf(worst, dmin)
            }
            log.append("4x: the drawn arrow was at most ${worst.toInt()} m off the road\n")
            assertTrue("the arrow stays on the road at speed (worst ${worst.toInt()} m)", worst < 15)
            val fast = (LocationTracker.speedMps * 3.6).toInt()
            log.append("fast forward 4x: ${fast} km/h after 15 s, along=${NavEngine.along.toInt()} (was $a0)\n")
            Dev.fastForward = 1
            assertTrue("fast forward goes past the road's speed", fast > 80)
            log.append("tunnel-mode seconds=$est, along at 19 s=${lastAlong.toInt()}, biggest jump after leaving=${maxBack.toInt()} m\n")
            assertTrue("simulated fixes count as GPS (no tunnel mode)", est == 0)
            assertTrue("it drives along the route", lastAlong > 100)
            assertTrue("never pulled back after leaving", maxBack == 0.0)
            Dev.stopSim()
        } finally { File(out, "sim_drive.txt").writeText(log.toString()) }
    }
}
