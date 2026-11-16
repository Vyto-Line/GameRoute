package gr.gamemap

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Settings › Sharing › Convoy (and Settings › App › Trip history) open their own sheet. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class SettingsConvoyTest {
    @Test
    fun opens() {
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = activity.window.decorView
        val looper = shadowOf(Looper.getMainLooper())
        fun idle() = repeat(20) { looper.idleFor(java.time.Duration.ofMillis(60)) }
        fun texts(v: View, acc: MutableList<String> = mutableListOf()): List<String> {
            if (v is TextView && v.isShown) acc += v.text.toString()
            if (v is ViewGroup) for (i in 0 until v.childCount) texts(v.getChildAt(i), acc)
            return acc
        }
        fun tap(label: String) {
            fun find(v: View): View? {
                if (v is TextView && v.isShown && v.text.toString() == label) return v
                if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
                return null
            }
            var t = find(root) ?: error("no '$label' in ${texts(root)}")
            while (!t.hasOnClickListeners() && t.parent is View) t = t.parent as View
            t.performClick(); idle()
        }
        fun shot(name: String) {
            val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888); root.draw(Canvas(bmp))
            File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        activity.showSettings(); idle()
        tap("Sharing"); shot("set_sharing")
        tap("Convoy"); shot("set_convoy")
        val t = texts(root)
        assertTrue("convoy sheet should show Start a convoy: $t", t.any { it.contains("Start a convoy") })
        activity.showSettings(); idle(); tap("App"); tap("Trip history")
        assertTrue("trips sheet should open: ${texts(root)}", texts(root).none { it == "Explorer profile" })
    }
}
