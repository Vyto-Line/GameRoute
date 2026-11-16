package gr.gamemap

import android.graphics.Bitmap
import android.graphics.Canvas
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

/** XP: what a trip earns, the arrival card, Progress, the Garage, a level-up, and Garage items on the map. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class XpLevelsTest {
    @Test
    fun levels() {
        val data = File(System.getProperty("previewData") ?: "../previewdata")
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        data.listFiles()?.filter { it.name.endsWith(".pbf") }?.forEach { f ->
            val (x, y) = f.nameWithoutExtension.split("_").map { it.toInt() }
            VectorSource.put(14, x, y, VectorTile(f.readBytes()))
        }
        val r = Osrm.parseAll(File(data, "route.json").readText())[0]
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = activity.window.decorView
        val log = StringBuilder()
        fun shot(name: String) {
            val tmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            repeat(25) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(60)); root.draw(Canvas(tmp)); Thread.sleep(30) }
            val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bmp))
            File(out, "xp_$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        fun call(name: String) = MainActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity)
        MapState.setLightMode(MapState.LightMode.DARK); MapState.setTheme(Theme.NEON_NOIR)
        // a trip: clean, new roads, a convoy race in 2nd place
        Xp.startTrip()
        var t = System.currentTimeMillis()
        for (i in 0 until r.points.size - 1) Xp.onFix(r.points[i], 12f, 50, true)
        val before = Explorer.level
        val parts = Xp.finish(r.length, 10, 1, convoyDone = true, place = 2, of = 3)
        parts.forEach { log.append("${it.xp}  ${it.label} · ${it.note}\n") }
        assertTrue("clean drive", parts.any { it.label == "Clean drive" })
        assertTrue("race place paid after a clean drive", parts.any { it.label.startsWith("Race") && it.xp == 30 })
        // speeding for 20 s: not clean, no race bonus
        Xp.startTrip()
        repeat(25) { k -> Xp.onFix(r.points[k], 22f, 50, true); Thread.sleep(1000) }
        val fast = Xp.finish(1000.0, 0, 0, convoyDone = true, place = 1, of = 3)
        assertTrue("not clean", fast.none { it.label == "Clean drive" })
        assertEquals("no race XP when speeding", 0, fast.first { it.label.startsWith("Race") }.xp)
        log.append("speeding trip: ${fast.joinToString { "${it.label} ${it.xp}" }}\n")
        // the arrival card with a level-up
        Explorer.addXp(4000)
        val trip = Explorer.Trip("Glyfada Marina", System.currentTimeMillis(), 1080, 12400.0, 0, parts.sumOf { it.xp }, 1, before, parts)
        NavEngine.debugTrip(trip)
        shot("arrival")
        NavEngine.dismissTrip(); shot("levelup")
        activity.openSheetForTest("progress"); shot("progress"); call("closeSheet")
        activity.openSheetForTest("garage"); shot("garage"); call("closeSheet")
        // Garage items on the map: gold arrow, electric route, checkered flag
        Xp.items.first { it.id == "arrow_gold" }.let { if (it.unlocked) Xp.choose(it) }
        Xp.items.first { it.id == "route_electric" }.let { if (it.unlocked) Xp.choose(it) }
        Xp.items.first { it.id == "flag_checkered" }.let { if (it.unlocked) Xp.choose(it) }
        NavEngine.debugDrive(Place("Museum", "", r.points.last()), r, 60)
        val p = r.points[r.points.size - 8]; val p2 = r.points[r.points.size - 7]
        LocationTracker.onLocationChanged(Location("gps").apply { latitude = p.lat; longitude = p.lon; speed = 8f; bearing = Geo.bearing(p, p2); accuracy = 5f; time = System.currentTimeMillis() })
        shot("map_items")
        log.append("level ${Explorer.level} · ${Xp.title()} · unlocked ${Xp.items.count { it.unlocked }}/${Xp.items.size}\n")
        File(out, "xp.txt").writeText(log.toString())
    }
}
