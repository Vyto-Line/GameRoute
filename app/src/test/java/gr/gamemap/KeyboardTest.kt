package gr.gamemap

import android.os.Looper
import android.widget.EditText
import android.widget.LinearLayout
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/** A text box in a pop-up (renaming a friend, labelling a place…) is never under the keyboard. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class KeyboardTest {
    @Test
    fun popUpRidesAboveTheKeyboard() {
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        val log = StringBuilder()
        try {
            val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
            val looper = shadowOf(Looper.getMainLooper())
            val root = a.window.decorView
            val input = EditText(a).apply { setText("Nikos"); setSingleLine() }
            a.GameBuilder(a).setTitle("Your name for Nikos").setMessage("Only you see it.")
                .setView(LinearLayout(a).apply { addView(input) })
                .setPositiveButton("Save", null).setNegativeButton("Cancel", null).show()
            looper.idle()
            val h = root.height
            val nav = (48 * a.resources.displayMetrics.density).toInt()
            val ime = (h * 0.42).toInt()
            val wi = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, 80, 0, nav))
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, ime))
                .setVisible(WindowInsetsCompat.Type.ime(), true).build()
            ViewCompat.dispatchApplyWindowInsets(root, wi)
            repeat(10) { looper.idleFor(java.time.Duration.ofMillis(50)) }
            val loc = IntArray(2); input.getLocationInWindow(loc)
            val bottom = loc[1] + input.height
            val keyTop = h - ime
            log.append("screen $h, keyboard from $keyTop, text box ${loc[1]}..$bottom\n")
            assertTrue("the text box ($bottom) is above the keyboard ($keyTop)", bottom <= keyTop)
            assertTrue("and still on screen", loc[1] > 0)
        } finally { File(out, "keyboard.txt").writeText(log.toString()) }
    }
}
