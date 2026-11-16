package gr.gamemap

import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.testing.ScreenController
import androidx.car.app.testing.TestCarContext
import androidx.lifecycle.Lifecycle
import gr.gamemap.car.FriendsCarScreen
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * Friends in the car with real friends in it (online, offline, busy, invited, an invite to me), on
 * every car level: the screen must build without throwing, in and out of a convoy.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CarFriendsTest {
    private val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
    private val log = StringBuilder()
    private var now = System.currentTimeMillis()
    private val net = FriendCoreTest.Net()
    private fun phone(name: String) = FriendCore(FriendCoreTest.Mem(), net) { now }.apply { this.name = name }

    @Test
    fun friendsInTheCar() {
        try { run() } finally { File(out, "car_friends.txt").writeText(log.toString()) }
    }

    private fun run() {
        val app = RuntimeEnvironment.getApplication()
        val me = phone("Vyto")
        val others = listOf("Nikos", "Maria", "Giorgos", "Kostas", "Eleni").map { phone(it) }
        for (o in others) { me.request(o.code); o.check(); o.accept(o.requests.single()); me.check() }
        val fr = me.friends
        me.setOnline(fr[0].fid, now, dnd = false)
        me.setOnline(fr[1].fid, now, dnd = true)
        // Kostas invites me
        others[3].invite(others[3].friends.single(), "conv77", "Sounio"); me.check()
        log.append("friends=${fr.size}, invites=${me.invites.size}\n")
        Friends::class.java.getDeclaredField("core").apply { isAccessible = true }.set(null, me)

        // what broke build 100: a row button with a title and no icon
        try {
            androidx.car.app.model.Row.Builder().setTitle("x").addAction(androidx.car.app.model.Action.Builder().setTitle("Invite").setOnClickListener {}.build())
            log.append("a row button without an icon: allowed\n")
        } catch (e: Exception) { log.append("a row button without an icon: $e\n") }

        var failed = 0
        for (level in listOf(1, 3, 5, 6, 7, 8)) for (inConvoy in listOf(false, true)) {
            Convoy.leave(quiet = true)
            if (inConvoy) {
                Convoy.debugStart(Place("Nafplio", "", LatLng(37.567, 22.805)))
                me.invite(fr[2], Convoy.code!!, "Nafplio")
            }
            val ctx = TestCarContext.createCarContext(app)
            androidx.car.app.CarContext::class.java.getDeclaredMethod("updateHandshakeInfo", androidx.car.app.HandshakeInfo::class.java)
                .apply { isAccessible = true }.invoke(ctx, androidx.car.app.HandshakeInfo("com.google.android.projection.gearhead", level))
            try {
                val s = FriendsCarScreen(ctx); ScreenController(s).moveToState(Lifecycle.State.RESUMED)
                val t = s.onGetTemplate() as ListTemplate
                log.append("level $level, convoy=$inConvoy: OK · ${t.title?.toCharSequence()}\n")
                for (sec in t.sectionedLists) log.append("  [${sec.header?.toCharSequence()}] " + sec.itemList.items.joinToString { r -> (r as Row).let { "${it.title?.toCharSequence()} {${it.actions.map { a -> a.title?.toCharSequence() }}}" } } + "\n")
            } catch (e: Throwable) {
                failed++
                log.append("level $level, convoy=$inConvoy: CRASH ${e}\n")
                e.stackTrace.take(12).forEach { log.append("    at $it\n") }
            }
        }
        Convoy.leave(quiet = true)
        assertTrue("the car Friends screen threw on $failed setups (see car_friends.txt)", failed == 0)
    }
}
