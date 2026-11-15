package gr.gamemap

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.location.Location
import android.os.Looper
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

/** Convoy: Race or Drive along at the start; drive-along drivers aren't waited for, have no %, aren't ranked. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class DriveAlongTest {
    @Test
    fun driveAlong() {
        val data = File(System.getProperty("previewData") ?: "../previewdata")
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        data.listFiles()?.filter { it.name.endsWith(".pbf") }?.forEach { f ->
            val (x, y) = f.nameWithoutExtension.split("_").map { it.toInt() }
            VectorSource.put(14, x, y, VectorTile(f.readBytes()))
        }
        val r = Osrm.parseAll(File(data, "route.json").readText())[0]
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = activity.window.decorView
        val looper = shadowOf(Looper.getMainLooper())
        fun shot(name: String) {
            val tmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            repeat(30) { looper.idleFor(java.time.Duration.ofMillis(60)); root.draw(Canvas(tmp)); Thread.sleep(30) }
            val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bmp))
            File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        val log = StringBuilder()
        fun at(i: Int) = r.points[i.coerceIn(0, r.points.size - 1)]

        MapState.setLightMode(MapState.LightMode.DARK); MapState.setTheme(Theme.NEON_NOIR)
        val dest = Place("National Archaeological Museum", "Patision 44", r.points.last())
        Convoy.myName = "Vyto"
        Convoy.debugStart(dest)
        val start = r.points[0]
        LocationTracker.onLocationChanged(Location("gps").apply { latitude = start.lat; longitude = start.lon; accuracy = 5f; time = System.currentTimeMillis() })
        val now = System.currentTimeMillis()
        Convoy.debugMember("Nikos", Geo.offset(start, 90f, 25.0), 0, 0, -1.0, -1.0, "ready")
        Convoy.debugMember("Giorgos", Geo.offset(start, 200f, 40.0), 0, 0, -1.0, -1.0, "ready")
        Convoy.debugMember("Maria", Geo.offset(start, 300f, 30.0), 0, 0, -1.0, -1.0, "ready")
        Convoy.members.values.first { it.name == "Nikos" }.apply { ready = true; readyAt = now }
        Convoy.members.values.first { it.name == "Maria" }.along = true
        MapState.zoomBy(16.5 - MapState.targetZoom)

        // 1) the start: Race picked
        log.append("racers at the start (not me): ${Convoy.lobby().map { it.name }}; everyone: ${Convoy.atStart().map { it.name }}\n")
        assertEquals(listOf("Giorgos", "Nikos"), Convoy.lobby().map { it.name }.sorted())
        activity.openSheetForTest("convoy"); shot("along_1_start")

        // Maria (driving along) starts driving: the race isn't "underway"
        Convoy.members.values.first { it.name == "Maria" }.state = "driving"
        log.append("Maria drives along and left: underway=${Convoy.underway} (want false)\n")
        assertTrue(!Convoy.underway)
        Convoy.members.values.first { it.name == "Maria" }.state = "ready"

        // 2) I pick Drive along
        Convoy.setAlong(true); shot("along_2_picked")
        Convoy.members.values.first { it.name == "Giorgos" }.apply { ready = true; readyAt = System.currentTimeMillis() }
        Convoy.setAlong(true) // (no change)
        log.append("me driving along, both racers ready: my phone counts down? goAt=${Convoy.goAt} (want 0)\n")
        assertEquals(0L, Convoy.goAt)

        // 3) back to Race, ready: now the countdown (Nikos, Giorgos, me)
        Convoy.setAlong(false); Convoy.setReady(true)
        log.append("me racing and ready: goAt in ${Convoy.goAt - System.currentTimeMillis()} ms (want ~4000)\n")
        assertTrue(Convoy.goAt > 0)
        Convoy.setReady(false)
        log.append("pressing Drive along during the countdown is ignored: ")
        Convoy.setReady(true); Convoy.setAlong(true)
        log.append("meAlong=${Convoy.meAlong}\n")
        Convoy.setReady(false)

        // 3b) Eleni only shares her location: not a racer, not at the start, the race doesn't wait
        Convoy.debugMember("Eleni", Geo.offset(start, 45f, 3400.0), 0, 0, -1.0, -1.0, "ready")
        Convoy.members.values.first { it.name == "Eleni" }.sharing = true
        log.append("Eleni sharing: racers=${Convoy.lobby().map { it.name }.sorted()} atStart has Eleni=${Convoy.atStart().any { it.name == "Eleni" }} rows have Eleni=${Convoy.rows(0).any { it.name == "Eleni" }}\n")
        assertTrue(Convoy.lobby().none { it.name == "Eleni" } && Convoy.rows(0).none { it.name == "Eleni" })
        activity.onBackPressedDispatcher.onBackPressed(); activity.openSheetForTest("convoy"); shot("along_5_others_sharing")
        // me too: only sharing
        Convoy.setSharing(true)
        log.append("me sharing: meSharing=${Convoy.meSharing} meAlong=${Convoy.meAlong} me in rows=${Convoy.rows(0).any { it.me }}\n")
        assertTrue(Convoy.rows(0).none { it.me })
        activity.onBackPressedDispatcher.onBackPressed(); activity.openSheetForTest("convoy"); shot("along_6_me_sharing")
        Convoy.setSharing(false)
        log.append("back: meSharing=${Convoy.meSharing} me in rows=${Convoy.rows(0).any { it.me }}\n")

        // 4) driving: Maria along, the others racing
        activity.onBackPressedDispatcher.onBackPressed()
        NavEngine.debugDrive(dest, r, 60)
        val n = r.points.size
        val t = System.currentTimeMillis()
        Convoy.members.clear()
        Convoy.debugMember("Nikos", at(n * 2 / 3), 68, t + 9 * 60_000L, r.length * 0.3, r.length, "driving")
        Convoy.debugMember("Maria", at(n / 2), 44, t + 11 * 60_000L, r.length * 0.5, r.length, "driving")
        Convoy.debugMember("Giorgos", at(2), 39, t + 20 * 60_000L, r.length * 0.85, r.length, "driving")
        Convoy.members.values.first { it.name == "Maria" }.along = true
        Convoy.debugMember("Eleni", Geo.offset(at(n / 3), 45f, 600.0), 0, 0, -1.0, -1.0, "ready")
        Convoy.members.values.first { it.name == "Eleni" }.sharing = true
        val p = at(n / 3); val p2 = at(n / 3 + 1)
        LocationTracker.onLocationChanged(Location("gps").apply { latitude = p.lat; longitude = p.lon; speed = 11.7f; bearing = Geo.bearing(p, p2); accuracy = 5f; time = t })
        MapState.zoomBy(15.5 - MapState.targetZoom)
        log.append("order: ${Convoy.rows(0).map { (if (it.me) "You" else it.name) + (if (it.along) "(along)" else "") }}\n")
        assertTrue(Convoy.rows(0).last().along)
        shot("along_3_drive")
        activity.openSheetForTest("convoy"); shot("along_4_list")
        activity.onBackPressedDispatcher.onBackPressed()
        val w = 1920; val h = 720
        val rr = MapRenderer(car = true).apply { carButtons = true }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        MapState.car.zoomBy(15.0 - MapState.car.targetZoom)
        repeat(30) { rr.draw(Canvas(bmp), w, h, Rect(0, 0, w, h), 1.6f, true); looper.idleFor(java.time.Duration.ofMillis(60)); Thread.sleep(30) }
        File(out, "along_car.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        File(out, "drivealong.txt").writeText(log.toString())
    }
}
