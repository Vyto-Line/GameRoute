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
        // What's new: build 100 and every earlier build
        MainActivity::class.java.getDeclaredField("settingsPage").apply { isAccessible = true }.set(activity, "whatsnew")
        MainActivity::class.java.getDeclaredMethod("openSheet", String::class.java).apply { isAccessible = true }.invoke(activity, "settings")
        MainActivity::class.java.getDeclaredField("settingsPage").apply { isAccessible = true }.set(activity, "whatsnew")
        activity.refreshForTest(); shot("whatsnew_top")
        fun texts(v: android.view.View): List<String> = if (v is android.widget.TextView) listOf(v.text.toString()) else (v as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).flatMap { texts(g.getChildAt(it)) } } ?: emptyList()
        val host = MainActivity::class.java.getDeclaredField("sheetHost").apply { isAccessible = true }.get(activity) as android.view.View
        val all = texts(host)
        log.append("what's new: ${all.size} texts, has build 3: ${all.any { it.startsWith("First version") }}, has 99: ${"99" in all}\n")
        assertTrue("older builds listed", all.any { it.startsWith("First version") } && "99" in all)
        // level 5 reached without the phone's arrival card (no route, or only in the car): the
        // celebration still comes, once, the next time the phone is on the map
        activity.popupsForTest().forEach { it.dismiss() }
        MainActivity::class.java.getDeclaredMethod("closeSheet").apply { isAccessible = true }.invoke(activity)
        Perf.animations = Perf.FULL
        Explorer.addXp(1000 - Explorer.xp + 10)
        activity.getSharedPreferences("gamemap", android.content.Context.MODE_PRIVATE).edit().putInt("levelCelebrated", 4).commit()
        activity.refreshForTest(); looper.idle()
        fun find(v: android.view.View): Milestone? = if (v is Milestone) v else (v as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { find(g.getChildAt(it)) } }
        val ms = find(root)
        log.append("level ${Explorer.level}: celebration shown=${ms != null}\n")
        assertTrue("the level-5 celebration shows", ms != null)
        // it plays out and stays until a tap (time to read), then goes (removed after the frame, not during it)
        repeat(60) { looper.idleFor(java.time.Duration.ofMillis(100)); root.draw(Canvas(Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888))) }
        log.append("after 6 s: celebration still there=${find(root) != null}\n")
        assertTrue("the celebration waits for a tap", find(root) != null)
        val m = find(root)!!
        m.dispatchTouchEvent(android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_DOWN, 100f, 100f, 0))
        m.dispatchTouchEvent(android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_UP, 100f, 100f, 0))
        repeat(5) { looper.idleFor(java.time.Duration.ofMillis(100)); root.draw(Canvas(Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888))) }
        log.append("after a tap: celebration still there=${find(root) != null}\n")
        assertTrue("a tap ends it", find(root) == null)
        val seen = activity.getSharedPreferences("gamemap", android.content.Context.MODE_PRIVATE).getInt("levelCelebrated", 0)
        log.append("counted as celebrated: $seen\n")
        assertTrue("counted only after it played", seen == 5)
        // avatars: all 12 drawn, and the picker
        run {
            val b = Bitmap.createBitmap(4 * 180, 3 * 180, Bitmap.Config.ARGB_8888); val cv = Canvas(b); cv.drawColor(android.graphics.Color.rgb(20, 12, 34))
            Avatars.all.forEachIndexed { i, a -> Avatars.draw(cv, a, a.color, 90f + (i % 4) * 180f, 90f + (i / 4) * 180f, 70f) }
            File(out, "avatars_all.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        MapState.setTheme(Theme.VIOLET_RAIN); MapState.setLightMode(MapState.LightMode.DARK); activity.refreshForTest()
        activity.popupsForTest().forEach { it.dismiss() }
        Avatars.mine = "fox"
        call("chooseAvatar"); shot("avatar_picker")
        log.append("avatar picker open=${activity.popupsForTest().size}, my code=${Avatars.myCode()}\n")
        assertTrue(Avatars.myCode().startsWith("fox:"))
        File(out, "popups.txt").writeText(log.toString())
    }
}
