package gr.gamemap

import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** No XP for trips under 200 m; the routes screen's convoy button suggests, it doesn't start a 2nd convoy. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class XpAndSuggestTest {
    private fun field(name: String) = NavEngine::class.java.getDeclaredField(name).apply { isAccessible = true }

    private fun finish(driven: Double, planned: Double): Int {
        field("tripMeters").set(NavEngine, driven)
        field("tripPlanned").set(NavEngine, planned)
        field("tripBonus").set(NavEngine, 0)
        NavEngine::class.java.getDeclaredMethod("finishTrip").apply { isAccessible = true }.invoke(NavEngine)
        return NavEngine.lastTrip!!.xp
    }

    @Test
    fun xp() {
        Robolectric.buildActivity(MainActivity::class.java).setup()
        assertEquals("150 m route", 0, finish(150.0, 150.0))
        assertEquals("long route, but barely driven", 0, finish(80.0, 3000.0))
        assertEquals("short route asked for, wobble adds metres", 0, finish(260.0, 120.0))
        assertTrue("a real 3 km trip", finish(3000.0, 3000.0) > 0)
    }

    @Test
    fun suggest() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val a = Place("National Archaeological Museum", "Patision 44", LatLng(37.9890, 23.7320))
        val b = Place("Mikel Coffee", "Kolonaki", LatLng(37.9785, 23.7410))
        Convoy.myName = "Vyto"; Convoy.debugStart(a)
        val code = Convoy.code
        field("planPlace").set(NavEngine, b)
        ActivityMethod.of("startConvoyFromPreview").apply { isAccessible = true }.invoke(activity)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("same convoy", code, Convoy.code)
        assertEquals("the place went to the convoy", "Mikel Coffee", Convoy.destination?.name)
        Convoy.leave(quiet = true)
    }
}
