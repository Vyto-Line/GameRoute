package gr.gamemap

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.location.Location
import android.os.Looper
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.testing.ScreenController
import androidx.car.app.testing.TestCarContext
import androidx.lifecycle.Lifecycle
import gr.gamemap.car.FriendsCarScreen
import gr.gamemap.car.QuickMessageCarScreen
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Update 100, stage 3 (friends and convoys): your status and what it does to invites, quick
 * messages, the convoy's shared clock and distances, signal lost, and friends and the lobby in the car.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class FriendsStage3Test {
    private val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
    private val log = StringBuilder()

    private var now = 1_000_000_000L
    private val net = FriendCoreTest.Net()
    private fun phone(name: String) = FriendCore(FriendCoreTest.Mem(), net) { now }.apply { this.name = name }

    @Test
    fun stage3() {
        try { run() } finally { File(out, "stage3.txt").writeText(log.toString()) }
    }

    private fun run() {
        // ---- your status: what invites do
        log.append("== Status ==\n")
        val a = phone("Vyto"); val b = phone("Nikos")
        a.request(b.code); b.check(); b.accept(b.requests.single()); a.check()
        val fb = a.friends.single()

        b.status = FriendCore.INVISIBLE
        assertFalse("invisible: not shown as online", b.showOnline)
        a.invite(fb, "conv01", "Nafplio"); b.check()
        log.append("invisible: invites=${b.invites.size}, sender sees ${a.sentTo(fb.fid, "conv01")?.status}\n")
        assertTrue("invisible: the invite isn't delivered", b.invites.isEmpty())
        assertEquals("the sender sees only Sent", "sent", a.sentTo(fb.fid, "conv01")?.status)

        b.status = FriendCore.DND
        now += 1000
        a.invite(fb, "conv02", "Sounio"); b.check()
        log.append("do not disturb: shown invites=${b.invites.size}\n")
        assertTrue("do not disturb: it waits", b.invites.isEmpty())
        b.statusUntil = now + 3_600_000
        now += 3_600_001
        assertEquals("back to Online after the hour", FriendCore.ONLINE, b.status)
        log.append("after the hour: status=${b.status}, invites=${b.invites.map { it.dest }}\n")
        assertEquals("the waiting invite shows now", listOf("Sounio"), b.invites.map { it.dest })
        assertTrue(b.showOnline)

        // a friend's Do not disturb comes with their presence
        a.setOnline(fb.fid, now, dnd = true)
        assertTrue(a.isOnline(fb.fid)); assertTrue(a.isBusy(fb.fid))
        a.setOnline(fb.fid, now, dnd = false)
        assertFalse(a.isBusy(fb.fid))

        // ---- convoy: shared clock, quick messages, signal lost, same distances
        log.append("\n== Convoy ==\n")
        val app = RuntimeEnvironment.getApplication()
        Voice.init(app); Voice.lang = Phrases.ENGLISH
        val data = File(System.getProperty("previewData") ?: "../previewdata")
        val r = Osrm.parseAll(File(data, "route.json").readText())[0]
        val dest = Place("National Archaeological Museum", "Patision 44", r.points.last())
        NavEngine.debugDrive(dest, r, 60)
        val looper = shadowOf(Looper.getMainLooper())
        fun on(m: Double): LatLng {
            var i = 0
            while (i < r.points.size - 2 && r.cum[i + 1] < m) i++
            val f = ((m - r.cum[i]) / maxOf(r.cum[i + 1] - r.cum[i], 0.1)).coerceIn(0.0, 1.0)
            return Geo.lerp(r.points[i], r.points[i + 1], f)
        }
        val meAt = NavEngine.along + 50
        val p = on(meAt)
        LocationTracker.onLocationChanged(Location("gps").apply { latitude = p.lat; longitude = p.lon; speed = 12f; accuracy = 5f; time = System.currentTimeMillis() })
        looper.idle()
        Convoy.debugStart(dest)
        // Nikos's phone clock runs 3 s fast; his updates come over the real-time line
        val fast = 3_000L
        fun from(id: String, name: String, m: Double, extra: (JSONObject) -> Unit = {}) {
            val q = on(m)
            val o = JSONObject().put("t", "pos").put("id", id).put("n", name).put("st", "driving").put("ts", System.currentTimeMillis() + fast)
                .put("lat", q.lat).put("lon", q.lon).put("h", 0.0).put("s", 40).put("left", r.length - m).put("tot", r.length)
            extra(o)
            Convoy.take(o)
        }
        repeat(5) { from("nikos", "Nikos", NavEngine.along + 850); Thread.sleep(30) }
        val nikos = Convoy.members["nikos"]!!
        log.append("measured clock difference: ${nikos.skew} ms (his clock is 3000 ms fast)\n")
        assertTrue("clock difference found", nikos.skew in -3_200L..-2_900L)
        val row = Convoy.rows(0).first { it.id == "nikos" }
        val rel = Convoy.relative(row)
        log.append("Nikos is 850 m ahead on the road: \"$rel\"\n")
        assertTrue(rel, rel.endsWith("ahead") && (rel.startsWith("800") || rel.startsWith("850") || rel.startsWith("900") || rel.startsWith("0.8") || rel.startsWith("0.9")))

        // a quick message: shown and said aloud while driving
        val heard = ArrayList<String>(); Voice.heardForTest = heard
        Convoy.take(JSONObject().put("t", "msg").put("id", "nikos").put("n", "Nikos").put("k", "fuel").put("ts", System.currentTimeMillis() + fast + 100))
        looper.idle()
        Voice.heardForTest = null
        log.append("message: shown=${Convoy.showMessage()?.let { "${it.first}: ${Convoy.messageText(it.second)}" }}, heard=$heard\n")
        assertEquals("Nikos", Convoy.showMessage()?.first)
        assertTrue(heard.any { it == "Nikos: Need fuel" })

        // signal lost: no update for 10 s
        nikos.seen = System.currentTimeMillis() - 10_000
        val lostRel = Convoy.relative(Convoy.rows(0).let { Convoy.rows(0) }.first { it.id == "nikos" })
        log.append("10 s without updates: lostSecs=${nikos.lostSecs}, \"$lostRel\"\n")
        assertTrue(nikos.lostSecs >= 10)
        nikos.seen = System.currentTimeMillis()

        // the race start: Maria's clock is 2 s slow; both ready → the same GO moment on my clock
        Convoy.members.clear()
        NavEngine.stop()
        Convoy.debugStart(dest); Convoy.debugHost(true)
        val slow = -2_000L
        val pressed = System.currentTimeMillis()
        Convoy.take(JSONObject().put("t", "pos").put("id", "maria").put("n", "Maria").put("st", "ready").put("rd", true)
            .put("ra", pressed + slow).put("ts", System.currentTimeMillis() + slow))
        val maria = Convoy.members["maria"]!!
        log.append("Maria pressed ready at (my clock) ${maria.readyAt - pressed} ms vs when she really did: 0 (her clock 2 s slow)\n")
        assertTrue("her ready time is on my clock", Math.abs(maria.readyAt - pressed) < 300)

        // ---- the car: lobby on the map, Friends list, quick messages
        Convoy.myName = "Vyto"
        val w = 1920; val h = 720
        val rr = MapRenderer(car = true).apply { carButtons = true; forceLite = false }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val c = Canvas(bmp)
        data.listFiles()?.filter { it.name.endsWith(".pbf") }?.forEach { f ->
            val (x, y) = f.nameWithoutExtension.split("_").map { it.toInt() }
            VectorSource.put(14, x, y, VectorTile(f.readBytes()))
        }
        Convoy.take(JSONObject().put("t", "pos").put("id", "nikos2").put("n", "Nikos").put("st", "ready").put("ts", System.currentTimeMillis()))
        MapState.setTheme(Theme.NEON_NOIR); MapState.setLightMode(MapState.LightMode.DARK)
        repeat(30) { rr.draw(c, w, h, Rect(0, 0, w, h), 1.6f, true); looper.idleFor(java.time.Duration.ofMillis(60)); Thread.sleep(20) }
        File(out, "stage3_car_lobby.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }

        Convoy.debugMessage("Nikos", "fuel")
        rr.draw(c, w, h, Rect(0, 0, w, h), 1.6f, true)
        File(out, "stage3_car_message.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }

        val ctx = TestCarContext.createCarContext(app)
        androidx.car.app.CarContext::class.java.getDeclaredMethod("updateHandshakeInfo", androidx.car.app.HandshakeInfo::class.java)
            .apply { isAccessible = true }.invoke(ctx, androidx.car.app.HandshakeInfo("com.google.android.projection.gearhead", 6))
        val f = FriendsCarScreen(ctx); ScreenController(f).moveToState(Lifecycle.State.RESUMED)
        val ft = f.onGetTemplate() as ListTemplate
        log.append("\ncar Friends: ${ft.title?.toCharSequence()} · action ${ft.actionStrip?.actions?.map { it.title?.toCharSequence() }}\n")
        for (s in ft.sectionedLists) log.append("  [${s.header?.toCharSequence()}] " + s.itemList.items.joinToString { (it as Row).title?.toCharSequence().toString() } + "\n")
        val q = QuickMessageCarScreen(ctx); ScreenController(q).moveToState(Lifecycle.State.RESUMED)
        val qt = q.onGetTemplate() as ListTemplate
        val qs = qt.singleList!!.items.map { (it as Row).title?.toCharSequence().toString() }
        log.append("car quick messages: $qs\n")
        assertEquals(listOf("Need fuel", "Stopping soon", "Go ahead", "Wait for me"), qs)
        Convoy.leave(quiet = true)
        log.append("\nall stage 3 checks passed\n")
    }
}
