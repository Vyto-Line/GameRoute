package gr.gamemap

import android.location.Location
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * A right-angle turn at city speed: the drawn arrow must point the way it's moving on screen
 * (no "moonwalking" sideways through the corner).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TurnArrowTest {
    @Test
    fun noSidewaysSlide() {
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        val log = StringBuilder()
        val looper = shadowOf(Looper.getMainLooper())
        // a city corner at about 29 km/h, and a slower one at 18 (slid sideways after 113, 23 Nov)
        for ((n, v) in listOf(0 to 8f, 1 to 5f)) {
        val start = LatLng(37.9800 + n * 0.01, 23.7300)
        // a fresh start (not a GPS jump from the end of the first corner)
        for (f in listOf("last", "lastReal", "fromPos", "toPos", "ctrlPos", "travelFrom", "slowPending"))
            try { LocationTracker::class.java.getDeclaredField(f).apply { isAccessible = true }.set(null, null) } catch (_: Exception) {}
        // 6 s north, a 90° right turn, 6 s east; one fix a second, GPS bearing lagging by a fix
        val path = ArrayList<Pair<LatLng, Float>>()
        var p = start
        repeat(6) { p = Geo.offset(p, 0f, v.toDouble()); path += p to 0f }
        path += Geo.offset(p, 45f, v.toDouble()).also { p = it } to 0f // the corner: still saying north
        repeat(6) { i -> p = Geo.offset(p, 90f, v.toDouble()); path += p to (if (i == 0) 45f else 90f) }
        var t = System.currentTimeMillis()
        var worst = 0f; var worstAt = -1
        var prevShown: LatLng? = null
        path.forEachIndexed { k, (pos, brg) ->
            LocationTracker.onLocationChanged(Location(LocationManager.GPS_PROVIDER).apply {
                latitude = pos.lat; longitude = pos.lon; time = t; speed = v; bearing = brg; accuracy = 5f
            })
            t += 1000
            repeat(30) {
                looper.idleFor(java.time.Duration.ofMillis(33)); LocationTracker.tick()
                val shown = LocationTracker.displayPosition() ?: return@repeat
                val prev = prevShown
                if (prev != null && Geo.distance(prev, shown) > 0.15) {
                    val moving = Geo.bearing(prev, shown)
                    val off = kotlin.math.abs(Geo.angleDiff(LocationTracker.heading, moving))
                    if (k > 1 && off > worst) { worst = off; worstAt = k }
                }
                prevShown = shown
            }
        }
        log.append("${(v * 3.6).toInt()} km/h: worst angle between the arrow and its motion: ${"%.0f".format(worst)}° (fix $worstAt)\n")
        File(out, "turn_arrow.txt").writeText(log.toString())
        assertTrue("the arrow slides sideways at ${(v * 3.6).toInt()} km/h (${worst}°)", worst < 40f)
        looper.idleFor(java.time.Duration.ofSeconds(5))
        }
    }
}
