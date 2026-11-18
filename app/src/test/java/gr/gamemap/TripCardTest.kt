package gr.gamemap

import android.location.Location
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The arrival card ("Through the rain") goes by itself once you drive off. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TripCardTest {
    private fun fix(lat: Double, lon: Double, kmh: Float, t: Long) = Location("gps").apply {
        latitude = lat; longitude = lon; speed = kmh / 3.6f; accuracy = 5f; time = t
    }

    @Test
    fun closesWhenYouDriveOff() {
        val t0 = System.currentTimeMillis()
        NavEngine.debugTrip(Explorer.Trip("Nikomachou 47", t0, 600, 3200.0, 0, 120, 0, 5))
        NavEngine.onLocation(fix(37.9050, 23.7480, 0f, t0))
        NavEngine.onLocation(fix(37.9051, 23.7480, 0f, t0 + 30_000))
        assertNotNull("parked at the end: the card stays", NavEngine.lastTrip)
        NavEngine.onLocation(fix(37.9085, 23.7480, 40f, t0 + 60_000)) // ~390 m on
        assertNull("driven off: the card has gone", NavEngine.lastTrip)
    }
}
