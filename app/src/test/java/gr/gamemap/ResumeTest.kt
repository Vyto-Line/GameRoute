package gr.gamemap

import android.location.Location
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/** Android Auto stops navigation (closing, another app): the route comes back; your own End clears it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ResumeTest {
    @Test
    fun resume() {
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        val ctx = RuntimeEnvironment.getApplication()
        val log = StringBuilder()
        val here = LatLng(37.9310, 23.7560)
        LocationTracker.onLocationChanged(Location("gps").apply { latitude = here.lat; longitude = here.lon; accuracy = 5f; time = System.currentTimeMillis() })
        val dest = Place("Glyfada", "Athens", LatLng(37.8650, 23.7530))
        NavEngine.start(ctx, dest)
        NavEngine.addStop(Place("Alimos", "", LatLng(37.9100, 23.7200)))
        shadowOf(Looper.getMainLooper()).idle()
        NavEngine.stop(keepResume = true)
        val r = NavEngine.resumable(ctx)
        log.append("after the car stopped it: ${r?.first?.name} with ${r?.second?.size} stop(s)\n")
        assertNotNull(r); assertEquals("Glyfada", r!!.first.name); assertEquals(1, r.second.size)
        NavEngine.resume(ctx, r.first, r.second)
        log.append("resumed: active=${NavEngine.active} to ${NavEngine.destinationName}, stops ${NavEngine.stops.size}\n")
        assertTrue(NavEngine.active && NavEngine.stops.size == 1)
        NavEngine.stop()
        log.append("after End: ${NavEngine.resumable(ctx)}\n")
        assertNull(NavEngine.resumable(ctx))
        File(out, "resume.txt").writeText(log.toString())
    }
}
