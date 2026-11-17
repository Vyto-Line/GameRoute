package gr.gamemap

import android.graphics.Bitmap
import android.graphics.Canvas
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The every-5-levels celebration in every style (as built), at its fullest moment. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class CelebrationsTest {
    @Test
    fun everyStyle() {
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val w = activity.window.decorView.width; val h = activity.window.decorView.height
        for (th in Theme.entries) for (dark in listOf(true, false)) {
            MapState.setTheme(th); MapState.setLightMode(if (dark) MapState.LightMode.DARK else MapState.LightMode.LIGHT)
            activity.refreshForTest()
            val v = activity.milestoneForTest(10, 1700)
            v.measure(android.view.View.MeasureSpec.makeMeasureSpec(w, android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(h, android.view.View.MeasureSpec.EXACTLY))
            v.layout(0, 0, w, h)
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); v.draw(Canvas(b))
            File(out, "celebrate_${th.name.lowercase()}_${if (dark) "d" else "l"}.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 90, it) }
        }
        MapState.setTheme(Theme.OPEN_WORLD); MapState.setLightMode(MapState.LightMode.DARK); activity.refreshForTest()
        val v = activity.milestoneForTest(50, 1700)
        v.measure(android.view.View.MeasureSpec.makeMeasureSpec(w, android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(h, android.view.View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); v.draw(Canvas(b))
        File(out, "celebrate_level50.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 90, it) }
    }
}
