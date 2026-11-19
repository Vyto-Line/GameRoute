package gr.gamemap

import android.location.Location
import android.location.LocationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.time.Duration

/**
 * Pulling away (the drive, 19 Nov): the map swung left and right as the car set off. With a phone
 * compass pointing the wrong way (as in a car) and wobbly GPS directions at walking pace, the map
 * must keep facing the way the car goes. Also the car's closer normal view.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PullAwayTest {
    @Test
    fun steadyWhenSettingOff() {
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        val log = StringBuilder()
        MapState.init(RuntimeEnvironment.getApplication())
        val o = LatLng(37.9300, 23.7400)
        fun at(m: Double) = LatLng(o.lat, o.lon + m / (111_320.0 * Math.cos(o.lat * Math.PI / 180))) // going east
        val t0 = System.currentTimeMillis()
        fun fix(m: Double, spd: Float, brg: Float?, t: Long) = Location(LocationManager.GPS_PROVIDER).apply {
            val p = at(m); latitude = p.lat; longitude = p.lon; time = t; speed = spd; accuracy = 6f
            if (brg != null) { bearing = brg; bearingAccuracyDegrees = 20f }
        }
        // driven east earlier, parked here
        var m = 0.0; var t = t0
        for (k in 0 until 6) { LocationTracker.onLocationChanged(fix(m, 12f, 90f, t)); m += 12; t += 1000 }
        repeat(60) { ShadowSystemClock.advanceBy(Duration.ofMillis(50)); LocationTracker.tick() }
        for (k in 0 until 5) { LocationTracker.onLocationChanged(fix(m, 0f, null, t)); t += 1000 }
        // the phone's compass in the car points somewhere else entirely
        LocationTracker.setCompass(200f)
        // setting off: walking pace, then faster, with GPS directions wobbling and one flipped
        val pull = listOf(0.6f to null, 1.4f to 140f, 2.2f to 60f, 3.2f to 270f, 4.0f to 120f, 4.8f to 75f, 6f to 92f, 8f to 88f, 10f to 91f)
        var worst = 0f
        for ((spd, brg) in pull) {
            m += spd
            LocationTracker.onLocationChanged(fix(m, spd, brg, t)); t += 1000
            repeat(20) {
                ShadowSystemClock.advanceBy(Duration.ofMillis(50)); LocationTracker.tick()
                worst = maxOf(worst, Math.abs(Geo.angleDiff(LocationTracker.heading, 90f)))
            }
            log.append("${"%.1f".format(spd * 3.6)} km/h, GPS ${brg ?: "-"}: map faces ${LocationTracker.heading.toInt()}°\n")
        }
        LocationTracker.setCompass(null)
        log.append("worst swing off the road's direction while setting off: ${worst.toInt()}°\n")

        // the car's normal view: closer, and a zoom-out from last time doesn't stick
        MapState.car.zoomBy(-2.0)
        MapState.car.startDrive()
        log.append("car view at a new drive: ${MapState.car.targetZoom} (phone ${MapState.phone.targetZoom})\n")
        File(out, "pullaway.txt").writeText(log.toString())
        assertTrue("the map swung ${worst.toInt()}° while setting off", worst <= 25f)
        assertEquals(CAR_BASE_ZOOM, MapState.car.targetZoom, 0.01)
    }
}
