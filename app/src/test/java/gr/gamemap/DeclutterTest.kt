package gr.gamemap

import android.graphics.Bitmap
import android.graphics.Canvas
import android.location.Location
import android.os.Looper
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The new chip row, More, and the rebuilt convoy screens (start, lobby as host and as guest, invite, home card). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class DeclutterTest {
    @Test
    fun screens() {
        val data = File(System.getProperty("previewData") ?: "../previewdata")
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        data.listFiles()?.filter { it.name.endsWith(".pbf") }?.forEach { f ->
            val (x, y) = f.nameWithoutExtension.split("_").map { it.toInt() }
            VectorSource.put(14, x, y, VectorTile(f.readBytes()))
        }
        val r = Osrm.parseAll(File(data, "route.json").readText())[0]
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = activity.window.decorView
        fun shot(name: String) {
            val tmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            repeat(25) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(60)); root.draw(Canvas(tmp)); Thread.sleep(30) }
            val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bmp))
            File(out, "dc_$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        fun call(name: String) = ActivityMethod.of(name).apply { isAccessible = true }.invoke(activity)
        MapState.setLightMode(MapState.LightMode.DARK); MapState.setTheme(Theme.NEON_NOIR)
        val now = System.currentTimeMillis()
        val p = r.points[r.points.size / 3]
        LocationTracker.onLocationChanged(Location("gps").apply { latitude = p.lat; longitude = p.lon; speed = 0f; accuracy = 5f; time = now })
        Places.save("home", Place("Home", "Ilioupoli", LatLng(37.931, 23.756)))
        Places.addRecent(Place("Gym", "Vouliagmenis 100", LatLng(37.95, 23.74), "🏋"))
        Places.addRecent(Place("Kafeneio", "Plaka", LatLng(37.972, 23.729), "☕"))
        Convoy.myName = "Alex"
        activity.refreshForTest(); shot("home")
        activity.openSheetForTest("more"); shot("more"); call("closeSheet")
        activity.openSheetForTest("you"); shot("you"); call("closeSheet")
        activity.openSheetForTest("convoy"); shot("convoy_start"); call("closeSheet")
        // a convoy at the start: me (host) racing, Maria ready, Nikos driving along
        val dest = Place("Glyfada Marina", "Glyfada", r.points.last())
        Convoy.debugStart(dest); Convoy.debugHost(true)
        Convoy.debugMember("Maria", r.points[5], 0, 0, -1.0, -1.0, "ready")
        Convoy.debugMember("Nikos", r.points[8], 0, 0, -1.0, -1.0, "ready")
        Convoy.members.values.firstOrNull { it.name == "Maria" }?.ready = true
        Convoy.members.values.firstOrNull { it.name == "Nikos" }?.along = true
        activity.refreshForTest(); shot("home_convoy")
        activity.openSheetForTest("convoy"); shot("lobby_host"); call("closeSheet")
        Convoy.debugHost(false)
        activity.openSheetForTest("convoy"); shot("lobby_guest"); call("closeSheet")
        activity.openSheetForTest("convoyinvite"); shot("invite"); call("closeSheet")
        // settings with its search box, and a search
        Convoy.leave(quiet = true)
        activity.showSettings(); shot("settings")
        // driving with a message waiting: the buttons fade, the counter shows
        Later.add("🏛 Landmark discovered: Panathenaic Stadium · +25 XP"); Later.add("👥 1 friend request: open Together")
        call("closeSheet")
        NavEngine.debugDrive(Place("Museum", "", r.points.last()), r, 60)
        val q = r.points[r.points.size / 2]; val q2 = r.points[r.points.size / 2 + 1]
        LocationTracker.onLocationChanged(Location("gps").apply { latitude = q.lat; longitude = q.lon; speed = 13f; bearing = Geo.bearing(q, q2); accuracy = 5f; time = System.currentTimeMillis() })
        activity.refreshForTest(); shot("drive_quiet")
        Convoy.debugStart(dest)
        // Greek
        L.setMode("el")
        activity.openSheetForTest("convoy"); shot("lobby_el"); call("closeSheet")
        L.setMode("en")
    }
}
