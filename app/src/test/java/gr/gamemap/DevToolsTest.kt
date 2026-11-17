package gr.gamemap

import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Developer page's tools are for test builds only: a public build has none of them, made-up
 * friends never send anything over the network, and a public build cleans them away.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DevToolsTest {

    @Test
    fun publicBuildHasNoDeveloperTools() {
        // unit tests build like a public release (no -PtestBuild)
        assertFalse("tests must run as a public build", BuildConfig.TEST_BUILD)
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        @Suppress("UNCHECKED_CAST")
        val rows = MainActivity::class.java.getDeclaredMethod("settingsRowsFor", String::class.java)
            .apply { isAccessible = true }.invoke(activity, "dev") as List<View>
        assertEquals("a public build shows no Developer rows", 0, rows.size)
    }

    @Test
    fun testFriendsStayOnThePhone() {
        val net = FriendCoreTest.Net()
        val me = FriendCore(FriendCoreTest.Mem(), net) { System.currentTimeMillis() }.apply { name = "Vyto" }
        me.addTestFriend("test-nikos", "Nikos (test)")
        me.addTestInvite("test-nikos", "Nikos (test)", "test123456", "Sounio")
        assertTrue(me.isFriend("test-nikos"))
        val before = net.msgs.size
        me.invite(me.friend("test-nikos")!!, "conv1", "Nafplio")
        me.remove("test-nikos")
        assertEquals("nothing is sent to a made-up friend", before, net.msgs.size)

        me.addTestFriend("test-maria", "Maria (test)")
        me.removeTestFriends()
        assertTrue(me.friends.none { it.fid.startsWith("test-") })
        assertTrue(me.invites.none { it.fid.startsWith("test-") })
    }

    @Test
    fun simulatedDrivesEarnNothing() {
        Dev.simTrip = true
        // the flag is read (and cleared) when the trip ends: see NavEngine.finishTrip
        val src = java.io.File("src/main/java/gr/gamemap/NavEngine.kt").takeIf { it.exists() }
            ?: java.io.File("app/src/main/java/gr/gamemap/NavEngine.kt")
        val s = src.readText()
        assertTrue(s.contains("val sim = Dev.simTrip") && s.contains("if (!sim) lastTrip?.let { Explorer.record(it) }"))
        Dev.simTrip = false
    }
}
