package gr.gamemap

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.location.Location
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Update 100, stage 2 (speed and battery): Settings › Performance and what each choice does, the
 * map effects switch, a phone that struggles losing its effects, finished map squares kept on the
 * phone, and Arctic snow.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class SpeedStage2Test {
    private val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
    private val log = StringBuilder()

    @Test
    fun stage2() {
        try { run() } finally { File(out, "stage2.txt").writeText(log.toString()) }
    }

    private fun run() {
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

        // ---- the settings page
        log.append("== Settings › Performance ==\n")
        activity.showSettings(); idle()
        shot("stage2_settings")
        tap("Performance"); shot("stage2_performance")
        val t = texts(root)
        log.append(t.joinToString(" | ") + "\n")
        for (want in listOf("Mode", "Animations", "Map smoothness while driving", "Save GPS when stopped", "Last drive"))
            assertTrue("'$want' on the page: $t", t.any { it.startsWith(want) })

        // ---- what the choices do
        log.append("\n== Choices ==\n")
        Perf.resetForTest()
        Perf.mode = Perf.SMOOTH; Perf.smoothness = Perf.NORMAL; Perf.effects = true; Perf.animations = Perf.FULL
        assertFalse(Perf.saving); assertTrue(Perf.effectsOn)
        assertEquals(33L, Perf.frameDelay(true)); assertEquals(50L, Perf.frameDelay(false))
        Perf.smoothness = Perf.HIGH; assertEquals(16L, Perf.frameDelay(true))
        Perf.smoothness = Perf.LOW; assertEquals(66L, Perf.frameDelay(true))
        Perf.mode = Perf.BATTERY; Perf.smoothness = Perf.HIGH
        log.append("Battery mode: saving=${Perf.saving}, effects=${Perf.effectsOn}, anim=${Perf.anim}, frame=${Perf.frameDelay(true)} ms\n")
        assertTrue(Perf.saving); assertFalse("no effects in Battery mode", Perf.effectsOn)
        assertEquals("at most Reduced animations", Perf.REDUCED, Perf.anim)
        assertEquals("fewer frames", 66L, Perf.frameDelay(true))
        Perf.mode = Perf.AUTO; Perf.smoothness = Perf.NORMAL
        log.append("Auto, battery ${Perf.battery()}%: saving=${Perf.saving}\n")

        // a phone that keeps drawing slowly loses its effects
        Perf.resetForTest()
        repeat(200) { Perf.frameDrawn(60) }
        log.append("after 200 frames of 60 ms: struggling=${Perf.struggling}, effects=${Perf.effectsOn}\n")
        assertTrue(Perf.struggling); assertFalse(Perf.effectsOn)
        Perf.resetForTest()
        repeat(200) { Perf.frameDrawn(20) }
        assertFalse("a fast phone keeps them", Perf.struggling)

        // ---- map: Arctic snow, effects on and off; finished squares saved on the phone
        val data = File(System.getProperty("previewData") ?: "../previewdata")
        data.listFiles()?.filter { it.name.endsWith(".pbf") }?.forEach { f ->
            val (x, y) = f.nameWithoutExtension.split("_").map { it.toInt() }
            VectorSource.put(14, x, y, VectorTile(f.readBytes()))
        }
        val r = Osrm.parseAll(File(data, "route.json").readText())[0]
        NavEngine.debugDrive(Place("Museum", "", r.points.last()), r, 60)
        val p = r.points[r.points.size / 3]
        LocationTracker.onLocationChanged(Location("gps").apply { latitude = p.lat; longitude = p.lon; speed = 0f; accuracy = 5f; time = System.currentTimeMillis() })
        val w = 1920; val h = 720
        val rr = MapRenderer(car = true).apply { carButtons = true; forceLite = false } // the test machine is slow: no automatic lite mode
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val c = Canvas(bmp)
        MapState.setTheme(Theme.ARCTIC); MapState.setLightMode(MapState.LightMode.LIGHT)
        fun frames(n: Int) = repeat(n) { rr.draw(c, w, h, Rect(0, 0, w, h), 1.6f, true); looper.idleFor(java.time.Duration.ofMillis(60)); Thread.sleep(25) }
        frames(40)
        File(out, "stage2_snow_car.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        log.append("\nArctic: effectsMoving=${rr.effectsMoving} (keeps a slow redraw going while stopped)\n")
        assertTrue(rr.effectsMoving)
        Perf.effects = false; frames(3)
        assertFalse("effects off: no snow", rr.effectsMoving)
        File(out, "stage2_snow_off_car.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Perf.effects = true

        // squares saved on the phone
        val dir = File(activity.cacheDir, "squares")
        val saved = dir.walkTopDown().filter { it.isFile && it.name.endsWith(".webp") }.toList()
        log.append("squares saved on the phone: ${saved.size} (${saved.sumOf { it.length() } / 1024} KB)\n")
        assertTrue("finished squares are saved", saved.isNotEmpty())
        TileCache.clear()
        val parts = saved.first().name.removeSuffix(".webp").split('_')
        val k = listOf(parts.dropLast(4).joinToString("_")) + parts.takeLast(4) // THEME_NAME, d/l, z, x, y
        val theme = Theme.valueOf(k[0])
        val t0 = System.nanoTime()
        var back: Bitmap? = null
        for (i in 0 until 50) {
            back = TileCache.get(theme, k[2].toInt(), k[3].toInt(), k[4].toInt()) ?: TileCache.peek(theme, k[2].toInt(), k[3].toInt(), k[4].toInt())
            if (back != null) break
            Thread.sleep(20); looper.idle()
        }
        log.append("a saved square back after clearing memory: ${back != null} in ${(System.nanoTime() - t0) / 1_000_000} ms\n")
        assertNotNull("loads from the phone", back)

        // phone map with snow
        MapState.setLightMode(MapState.LightMode.DARK)
        val pw = 1080; val ph = 2340
        val pr = MapRenderer(car = false).apply { forceLite = false }
        val pb = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888); val pc = Canvas(pb)
        repeat(30) { pr.draw(pc, pw, ph, Rect(0, 0, pw, ph), 2.75f, true); looper.idleFor(java.time.Duration.ofMillis(60)); Thread.sleep(25) }
        File(out, "stage2_snow_phone_dark.png").outputStream().use { pb.compress(Bitmap.CompressFormat.PNG, 100, it) }
        NavEngine.stop()
        log.append("\nall stage 2 checks passed\n")
    }
}
