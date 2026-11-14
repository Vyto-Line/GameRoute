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

/** Zoomed far out: countries, their borders and names (real map data). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class ZoomOutTest {
    @Test
    fun countries() {
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = activity.window.decorView
        val looper = shadowOf(Looper.getMainLooper())
        val athens = LatLng(37.9755, 23.7348)
        LocationTracker.onLocationChanged(Location("gps").apply { latitude = athens.lat; longitude = athens.lon; accuracy = 5f; time = System.currentTimeMillis() })
        for ((style, dark) in listOf(Theme.values().first() to false, Theme.NEON_NOIR to true, Theme.BLOCKY to false, Theme.RETRO to true)) {
            MapState.setTheme(style); MapState.setLightMode(if (dark) MapState.LightMode.DARK else MapState.LightMode.LIGHT)
            for (z in listOf(MapState.MIN_ZOOM, 3.5, 5.5, 7.2)) {
                MapState.zoomBy(z - MapState.targetZoom)
                repeat(120) { looper.idleFor(java.time.Duration.ofMillis(60)); val b = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888); root.draw(Canvas(b)); Thread.sleep(60) }
                val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888); root.draw(Canvas(bmp))
                File(out, "zoom_${style.name.lowercase()}_${z}.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        L.setMode("el")
        MapState.setTheme(Theme.values().first()); MapState.setLightMode(MapState.LightMode.LIGHT)
        MapState.zoomBy(3.5 - MapState.targetZoom)
        repeat(80) { looper.idleFor(java.time.Duration.ofMillis(60)); val b = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888); root.draw(Canvas(b)); Thread.sleep(60) }
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888); root.draw(Canvas(bmp))
        File(out, "zoom_greek_3.5.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        L.setMode("auto")
    }
}
