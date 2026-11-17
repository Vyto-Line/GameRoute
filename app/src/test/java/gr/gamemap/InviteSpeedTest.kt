package gr.gamemap

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.location.Location
import android.os.Looper
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

/** Build 111.2: the invite card (phone and car), the speed in orange/red, the road name under the arrow. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class InviteSpeedTest {
    private val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
    private val log = StringBuilder()

    @Test
    fun all() {
        try { run() } finally { File(out, "invite_speed.txt").writeText(log.toString()) }
    }

    private fun run() {
        // speed colours
        assertEquals(0, SpeedLimit.level(30, 30)); assertEquals(1, SpeedLimit.level(31, 30)); assertEquals(1, SpeedLimit.level(35, 30))
        assertEquals(2, SpeedLimit.level(36, 30)); assertEquals(0, SpeedLimit.level(80, null))
        log.append("levels: 30→0 31→1 35→1 36→2\n")

        val data = File(System.getProperty("previewData") ?: "../previewdata")
        data.listFiles()?.filter { it.name.endsWith(".pbf") }?.forEach { f ->
            val (x, y) = f.nameWithoutExtension.split("_").map { it.toInt() }
            VectorSource.put(14, x, y, VectorTile(f.readBytes()))
        }
        val r = Osrm.parseAll(File(data, "route.json").readText())[0]
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = activity.window.decorView
        val looper = shadowOf(Looper.getMainLooper())
        fun shot(name: String) {
            repeat(10) { looper.idleFor(Duration.ofMillis(60)) }
            val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bmp))
            File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        val start = r.points[0]
        fun fix(kmh: Float) = LocationTracker.onLocationChanged(Location("gps").apply {
            latitude = start.lat; longitude = start.lon; accuracy = 5f; speed = kmh / 3.6f; bearing = 10f; time = System.currentTimeMillis()
        })
        fix(0f)

        // 1) the invite card, parked: under Where to?
        val net = FriendCoreTest.Net()
        val me = FriendCore(FriendCoreTest.Mem(), net) { System.currentTimeMillis() }.apply { name = "Vyto" }
        Friends::class.java.getDeclaredField("core").apply { isAccessible = true }.set(null, me)
        me.addTestFriend("test-nikos", "Nikos")
        me.addTestInvite("test-nikos", "Nikos", "conv12345678", "Sounio")
        InvitePop.resetForTest()
        log.append("invites: ${me.invites.map { it.from + "/" + it.convoy }}, convoy active ${Convoy.active}, nav ${NavEngine.active}\n")
        InvitePop.changed += { if (InvitePop.current == null) log.append("closed at uptime ${android.os.SystemClock.uptimeMillis()}\n") else log.append("shown at uptime ${android.os.SystemClock.uptimeMillis()}\n") }
        InvitePop.offer(me.invites.single())
        log.append("current after offer: ${InvitePop.current?.from}, card now ${activity.invitePopForTest() != null}\n")
        try { MainActivity::class.java.getDeclaredMethod("syncInvitePop").apply { isAccessible = true }.invoke(activity) } catch (e: Exception) { log.append("sync: ${e.cause ?: e}\n${(e.cause ?: e).stackTrace.take(6).joinToString("\n")}\n") }
        log.append("after sync: card ${activity.invitePopForTest() != null}\n")
        repeat(10) { looper.idleFor(Duration.ofMillis(60)) }
        val card = activity.invitePopForTest()
        log.append("card on screen: ${card != null}\n")
        assertTrue("the invite card shows", card != null)
        val loc = IntArray(2); card!!.getLocationOnScreen(loc)
        val search = MainActivity::class.java.getDeclaredField("searchCard").apply { isAccessible = true }.get(activity) as View
        val sl = IntArray(2); search.getLocationOnScreen(sl)
        log.append("card top ${loc[1]}, Where to? bottom ${sl[1] + search.height}\n")
        assertTrue("the card is under Where to?", loc[1] >= sl[1] + search.height - 2)
        shot("invite_phone_parked")
        // car: centred at the top
        run {
            val w = 1920; val h = 720
            val rr = MapRenderer(car = true).apply { carButtons = true; forceLite = false }
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            repeat(3) { rr.draw(Canvas(bmp), w, h, android.graphics.Rect(0, 0, w, h), 1.6f, true); Thread.sleep(150) }
            File(out, "invite_car.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val join = rr.carButtonAt(w / 2f + 560 * 1.6f / 2 - 60 * 1.6f, 12 * 1.6f + 38 * 1.6f)
            log.append("car: the button at Join's place = $join\n")
            assertEquals("inv:join", join)
        }
        // after 10 s it goes (it stays in Friends)
        looper.idleFor(Duration.ofMillis(InvitePop.SHOW_MS + 800))
        repeat(5) { looper.idleFor(Duration.ofMillis(100)) }
        log.append("after 10 s: card=${activity.invitePopForTest() != null}, still in Friends: ${me.invites.size}\n")
        assertTrue(activity.invitePopForTest() == null && InvitePop.current == null)

        // 2) driving: speed colours and the road name under the arrow
        NavEngine.debugDrive(Place("Museum", "", r.points.last()), r, 0)
        val rend = (MainActivity::class.java.getDeclaredField("map").apply { isAccessible = true }.get(activity) as GameMapView).rendererForTest
        for ((kmh, want) in listOf(28f to 0, 33f to 1, 43f to 2)) {
            SpeedLimit.setForTest(30)
            fix(kmh); shot("speed_$kmh")
            val col = rend.speedColor
            val lvl = when (col) { 0 -> 0; android.graphics.Color.rgb(255, 159, 28) -> 1; else -> 2 }
            log.append("$kmh km/h in a 30: colour level $lvl (want $want); road name at ${rend.roadNameRect}, speed at ${rend.speedRect}\n")
            assertEquals(want, lvl)
            if (!rend.roadNameRect.isEmpty) assertTrue("the road name never covers the speed", !RectF.intersects(rend.roadNameRect, rend.speedRect))
        }
        NavEngine.stop()
    }
}
