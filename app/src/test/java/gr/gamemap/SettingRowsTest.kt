package gr.gamemap

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.LinearLayout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Settings rows with long descriptions (the Developer page): the title must stay on one line. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingRowsTest {
    @Test
    fun longValuesGoUnderTheTitle() {
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val m = MainActivity::class.java.getDeclaredMethod("settingRow", String::class.java, String::class.java, Function0::class.java).apply { isAccessible = true }
        val rows = listOf(
            "Reset what's been seen" to "Celebrations, What's new, welcome screens",
            "Test a crash" to "Saves a report, like a real crash",
            "Simulate a drive" to "Fake GPS to Home (or 2.5 km away) · earns nothing",
            "Add 4 test friends" to "Driving, in a convoy, busy, offline · this phone only",
            "Set level" to "1 to 50",
            "Faster routes" to "Ask me",
        )
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFFF3EFF8.toInt()) }
        val views = rows.map { (t, v) -> (m.invoke(a, t, v, { }) as View).also { col.addView(it) } }
        val w = 1080
        col.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        col.layout(0, 0, w, col.measuredHeight)
        val b = Bitmap.createBitmap(w, col.measuredHeight, Bitmap.Config.ARGB_8888)
        col.draw(Canvas(b))
        b.compress(Bitmap.CompressFormat.PNG, 100, File(out, "setting_rows.png").outputStream())
        // no row taller than two lines of text plus padding
        for ((i, v) in views.withIndex()) assertTrue("row ${rows[i].first} is ${v.height}px", v.height < 260)
    }
}
