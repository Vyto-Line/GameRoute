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
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
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

        // what friends are doing: Nikos drives, Giorgos was on 25 minutes ago
        Friends.activityForTest(fr[0].fid, true, true, false, now)
        Friends.activityForTest(fr[2].fid, false, false, false, System.currentTimeMillis() - 25 * 60_000)
        val acts = fr.map { it.name + ": " + Friends.activityText(it.fid) }
        log.append("activity: $acts\n")
        assertTrue(acts.toString(), acts.any { it.contains("Driving now") } && acts.any { it.contains("Last seen 25 min ago") })

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
        // in a convoy: Leave is there (a row and a header button), and its question builds
        Convoy.leave(quiet = true)
        Convoy.debugStart(Place("Nafplio", "", LatLng(37.567, 22.805)))
        val ctx = TestCarContext.createCarContext(app)
        androidx.car.app.CarContext::class.java.getDeclaredMethod("updateHandshakeInfo", androidx.car.app.HandshakeInfo::class.java)
            .apply { isAccessible = true }.invoke(ctx, androidx.car.app.HandshakeInfo("com.google.android.projection.gearhead", 7))
        val s = FriendsCarScreen(ctx); ScreenController(s).moveToState(Lifecycle.State.RESUMED)
        val t = s.onGetTemplate() as ListTemplate
        val rows = t.sectionedLists.flatMap { it.itemList.items }.map { (it as Row).title.toString() }
        log.append("convoy rows: $rows, header buttons ${t.actionStrip?.actions?.size}\n")
        assertTrue("Leave the convoy row", rows.any { it.contains("Leave the convoy") })
        assertTrue("Drive to the destination row", rows.any { it.contains("Drive to Nafplio") })
        val q = gr.gamemap.car.LeaveConvoyCarScreen(ctx); ScreenController(q).moveToState(Lifecycle.State.RESUMED)
        val qt = q.onGetTemplate() as androidx.car.app.model.MessageTemplate
        log.append("leave question: ${qt.title} · ${qt.actions.map { it.title }}\n")
        Convoy.leave(quiet = true)

        // the car buttons stay at the screen's edge even when the car reports a half-width free area
        val w = 1920; val h = 720
        val rr = MapRenderer(car = true).apply { carButtons = true; forceLite = false }
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        rr.draw(android.graphics.Canvas(bmp), w, h, android.graphics.Rect(0, 0, w / 2, h), 1.6f, true)
        val edge = rr.carButtonAt(w - 14 * 1.6f - 24 * 1.6f, h - 14 * 1.6f - 24 * 1.6f)
        log.append("button at the right edge with a half-width area: $edge\n")
        assertTrue("zoom button at the edge", edge != null)
        // the Friends panel drawn on the map (list, then the convoy page), in a dark and a light style
        fun hits(rr2: MapRenderer): List<String> {
            val f = MapRenderer::class.java.getDeclaredField("hitsShown").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST") return (f.get(rr2) as List<Pair<Any, String>>).map { it.second }
        }
        for ((th, dark) in listOf(Theme.VIOLET_RAIN to true, Theme.VIOLET_RAIN to false, Theme.RALLY to true)) {
            MapState.setTheme(th); MapState.setLightMode(if (dark) MapState.LightMode.DARK else MapState.LightMode.LIGHT)
            for (page in listOf("list", "convoy")) {
                if (page == "convoy") { Convoy.leave(quiet = true); Convoy.debugStart(Place("Nafplio", "", LatLng(37.567, 22.805)))
                    Convoy.debugMember("Nikos", LatLng(37.98, 23.73), 82, 0L, 400.0, 1000.0, "driving") } else Convoy.leave(quiet = true)
                val p2 = MapRenderer(car = true).apply { carButtons = true; forceLite = false; carFriends = page }
                val b2 = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
                repeat(3) { p2.draw(android.graphics.Canvas(b2), w, h, android.graphics.Rect(0, 0, w, h), 1.6f, true); Thread.sleep(150) }
                File(out, "car_friends_${page}_${th.name.lowercase()}_${if (dark) "dark" else "light"}.png").outputStream().use { b2.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                val hs = hits(p2).filter { it.startsWith("fp:") }
                log.append("panel $page ${th.name} ${if (dark) "dark" else "light"}: $hs\n")
                assertTrue("back on the panel", "fp:back" in hs)
                if (page == "list") assertTrue("invite buttons", hs.any { it.startsWith("fp:inv:") || it == "fp:none" })
                else assertTrue("leave on the convoy page", "fp:leave" in hs && "fp:drive" in hs && hs.any { it.startsWith("fp:member:") })
            }
        }
        Convoy.leave(quiet = true)
        assertTrue("the car Friends screen threw on $failed setups (see car_friends.txt)", failed == 0)
    }
}
