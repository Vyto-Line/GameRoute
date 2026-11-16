package gr.gamemap

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Fixes after build 100: the Map style button at the bottom right, the style list scrolls to its
 * end (big text, short phone), and the rain moves between frames.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h780dp-xxhdpi")
class Fix101Test {
    private val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
    private val log = StringBuilder()

    @Test
    fun fixes() {
        try { run() } finally { File(out, "fix101.txt").writeText(log.toString()) }
    }

    private fun run() {
        RuntimeEnvironment.setFontScale(1.3f)
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = activity.window.decorView
        val looper = shadowOf(Looper.getMainLooper())
        fun shot(name: String) {
            repeat(20) { looper.idleFor(java.time.Duration.ofMillis(60)); Thread.sleep(10) }
            val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888); root.draw(Canvas(bmp))
            File(out, "fix101_$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        Places.save("home", Place("Home", "Ilioupoli", LatLng(37.931, 23.756)))
        MapState.setTheme(Theme.VIOLET_RAIN); MapState.setLightMode(MapState.LightMode.DARK)
        activity.refreshForTest(); shot("home")

        // the style button: bottom right
        val style = MainActivity::class.java.getDeclaredField("styleBtn").apply { isAccessible = true }.get(activity) as View
        val at = IntArray(2); style.getLocationInWindow(at)
        log.append("style button at ${at[0]},${at[1]} (${style.width}x${style.height}) on a ${root.width}x${root.height} screen\n")
        assertTrue("style button on the right", at[0] + style.width > root.width * 0.8)
        assertTrue("style button in the lower half", at[1] > root.height * 0.5)

        // the style list scrolls to the end: the last style and the Android Auto row can be reached
        activity.showStylePicker(); shot("styles_top")
        fun find(v: View): ScrollView? = if (v is ScrollView) v else (v as? ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { find(g.getChildAt(it)) } }
        val host = MainActivity::class.java.getDeclaredField("sheetHost").apply { isAccessible = true }.get(activity) as View
        val sc = find(host)
        assertTrue("the style list scrolls", sc != null)
        val hostAt = IntArray(2); host.getLocationInWindow(hostAt)
        log.append("sheet top at ${hostAt[1]}, list ${sc!!.height} tall, content ${sc.getChildAt(0).height}\n")
        assertTrue("the sheet's title is on screen", hostAt[1] >= 0)
        sc.scrollTo(0, sc.getChildAt(0).height); shot("styles_end")
        log.append("scrolled to ${sc.scrollY} of ${sc.getChildAt(0).height - sc.height}\n")
        assertTrue("scrolled to the end", sc.scrollY > 0 && sc.scrollY >= sc.getChildAt(0).height - sc.height - 2)

        // the rain moves: two frames 100 ms apart differ
        val r = MapRenderer().apply { forceLite = false }
        val a = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888); val b = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
        r.draw(Canvas(a), 300, 300, Rect(0, 0, 300, 300), 1.6f, hud = false)
        looper.idleFor(java.time.Duration.ofMillis(100)); Thread.sleep(100)
        r.draw(Canvas(b), 300, 300, Rect(0, 0, 300, 300), 1.6f, hud = false)
        var diff = 0
        for (y in 0 until 300 step 2) for (x in 0 until 300 step 2) if (a.getPixel(x, y) != b.getPixel(x, y)) diff++
        log.append("rain: $diff pixels changed in 100 ms, effectsMoving=${r.effectsMoving}, delay ${Perf.effectsDelay()} ms\n")
        assertTrue("the rain moves", diff > 50)
        assertTrue("rain at 20+ frames a second", Perf.effectsDelay() <= 50)
        // driving without a route: the zoom follows the road (out on a motorway)
        RoadMatch.setLastForTest("motorway")
        val z = MapState.phone.freeZoomForTest()
        log.append("no route, on a motorway: auto zoom $z\n")
        assertTrue("zooms out on a motorway without a route", z <= -0.8)
        log.append("all checks passed\n")
    }
}
