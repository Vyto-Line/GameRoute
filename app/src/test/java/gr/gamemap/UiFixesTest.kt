package gr.gamemap

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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

/** The U-turn arrow; recenter at the bottom (target icon) and zooming back in. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class UiFixesTest {
    @Test
    fun fixes() {
        val data = File(System.getProperty("previewData") ?: "../previewdata")
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        val b = Bitmap.createBitmap(300, 160, Bitmap.Config.ARGB_8888)
        val c = Canvas(b); c.drawColor(Color.rgb(20, 24, 30))
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(242, 226, 26); strokeWidth = 18f }
        drawUTurn(c, 80f, 80f, 55f, p)
        p.color = Color.WHITE; p.strokeWidth = 6f
        drawUTurn(c, 200f, 80f, 22f, p)
        File(out, "uturn.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }

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
            File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        val log = StringBuilder()
        NavEngine.debugDrive(Place("Museum", "", r.points.last()), r, 20)
        val pt = r.points[20]
        LocationTracker.onLocationChanged(Location("gps").apply { latitude = pt.lat; longitude = pt.lon; accuracy = 5f; time = System.currentTimeMillis() })
        // look around zoomed far out
        MapState.phone.pan(r.points[r.points.size / 2], 0f)
        MapState.phone.zoomBy(11.0 - MapState.phone.targetZoom)
        shot("recenter_bottom")
        log.append("before recenter: zoom ${MapState.phone.targetZoom}\n")
        MapState.phone.recenter()
        log.append("after recenter: zoom ${MapState.phone.targetZoom} following=${MapState.phone.following}\n")
        File(out, "uifixes.txt").writeText(log.toString())
    }
}
