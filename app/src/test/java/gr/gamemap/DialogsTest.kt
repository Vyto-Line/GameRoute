package gr.gamemap

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File

/** Pop-ups in GameRoute's own look (not Android's dialogs), in several styles. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class DialogsTest {
    @Test
    fun popups() {
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        val log = StringBuilder()
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = activity.window.decorView
        val looper = shadowOf(Looper.getMainLooper())
        fun shot(name: String) {
            repeat(12) { looper.idleFor(java.time.Duration.ofMillis(60)); Thread.sleep(10) }
            val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888); root.draw(Canvas(bmp))
            File(out, "popup_$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        fun call(name: String) = MainActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity)
        Friends.init(activity); Friends.start()
        for ((th, dark) in listOf(Theme.VIOLET_RAIN to true, Theme.RALLY to true, Theme.BLUEPRINT to true, Theme.OPEN_WORLD to false)) {
            MapState.setTheme(th); MapState.setLightMode(if (dark) MapState.LightMode.DARK else MapState.LightMode.LIGHT)
            activity.refreshForTest(); looper.idle()
            activity.popupsForTest().forEach { it.dismiss() } // anything the app showed at start
            call("chooseStatus"); shot("status_${th.name.lowercase()}")
            log.append("${th.name}: status pop-ups open=${activity.popupsForTest().size}, Android dialogs=${ShadowDialog.getShownDialogs().size}\n")
            assertTrue("our own pop-up shows", activity.popupsForTest().size == 1)
            activity.popupsForTest().forEach { it.dismiss() }
            call("confirmLeaveConvoy"); shot("leave_${th.name.lowercase()}")
            activity.popupsForTest().forEach { it.dismiss() }
        }
        // the back button closes the pop-up first
        activity.popupsForTest().forEach { it.dismiss() }
        call("confirmLeaveConvoy")
        activity.onBackPressedDispatcher.onBackPressed(); looper.idle()
        log.append("after back: open=${activity.popupsForTest().size}\n")
        assertTrue(activity.popupsForTest().isEmpty())
        assertTrue("no Android dialog was used", ShadowDialog.getShownDialogs().isEmpty())
        File(out, "popups.txt").writeText(log.toString())
    }
}
