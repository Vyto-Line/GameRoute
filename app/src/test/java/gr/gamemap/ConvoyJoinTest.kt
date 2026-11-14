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

/** Joining a convoy that's already on its way, joining a friend's own route, and the map buttons' icons. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class ConvoyJoinTest {
    @Test
    fun join() {
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
            repeat(30) { looper.idleFor(java.time.Duration.ofMillis(60)); Thread.sleep(25) }
            val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888); root.draw(Canvas(bmp))
            File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        MapState.setTheme(Theme.NEON_NOIR); MapState.setLightMode(MapState.LightMode.DARK)
        val p = r.points[5]
        LocationTracker.onLocationChanged(Location("gps").apply { latitude = p.lat; longitude = p.lon; accuracy = 5f; time = System.currentTimeMillis() })
        val dest = Place("National Archaeological Museum", "Patision 44", r.points.last())
        Convoy.myName = "Vyto"; Convoy.debugStart(dest)
        Convoy.debugMember("Nikos", r.points[30], 45, System.currentTimeMillis() + 600_000, 3000.0, 6000.0, "driving")
        Convoy.debugMember("Maria", r.points[12], 30, 0, -1.0, -1.0, "own")
        Convoy.members.values.first { it.name == "Maria" }.ownDest = Place("Mikel Coffee", "", LatLng(37.9785, 23.7410))
        MapState.zoomBy(16.0 - MapState.targetZoom)
        shot("join_home")
        activity.openSheetForTest("convoy"); shot("join_sheet")
        MainActivity::class.java.getDeclaredMethod("closeSheet").apply { isAccessible = true }.invoke(activity)
        val map = MainActivity::class.java.getDeclaredField("map").apply { isAccessible = true }.get(activity)
        @Suppress("UNCHECKED_CAST")
        val tap = map.javaClass.getMethod("getOnFriendTap").invoke(map) as ((Convoy.Member) -> Unit)
        tap(Convoy.members.values.first { it.name == "Maria" }); shot("join_friend")
        Convoy.leave(quiet = true)
    }
}
