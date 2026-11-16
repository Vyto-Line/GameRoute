package gr.gamemap

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.location.Location
import android.os.Looper
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The speed check (runs on every push that changes the app): how long a driving frame takes to
 * draw, on the car screen and the phone, in a light, a dark and an effects-heavy style. It fails
 * when a change makes the map clearly slower than the budget, so later updates can't quietly undo
 * the speed work. (Numbers are from the test machine's software drawing, not a phone's: what
 * matters is that they don't grow.)
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class FrameBudgetTest {
    /** Milliseconds per frame on the test machine, with room for its ups and downs. */
    private val budgetCar = 45.0
    private val budgetPhone = 85.0

    @Test
    fun budget() {
        val data = File(System.getProperty("previewData") ?: "../previewdata")
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        data.listFiles()?.filter { it.name.endsWith(".pbf") }?.forEach { f ->
            val (x, y) = f.nameWithoutExtension.split("_").map { it.toInt() }
            VectorSource.put(14, x, y, VectorTile(f.readBytes()))
        }
        val r = Osrm.parseAll(File(data, "route.json").readText())[0]
        NavEngine.debugDrive(Place("Museum", "", r.points.last()), r, 60)
        fun at(m: Double): Location {
            var i = 0
            while (i < r.points.size - 2 && r.cum[i + 1] < m) i++
            val f = ((m - r.cum[i]) / maxOf(r.cum[i + 1] - r.cum[i], 0.1)).coerceIn(0.0, 1.0)
            val p = Geo.lerp(r.points[i], r.points[i + 1], f)
            return Location("gps").apply { latitude = p.lat; longitude = p.lon; speed = 12f; bearing = Geo.bearing(r.points[i], r.points[i + 1]); accuracy = 5f; time = System.currentTimeMillis() }
        }
        val log = StringBuilder()
        val over = ArrayList<String>()
        for (car in listOf(true, false)) for (th in listOf(Theme.OPEN_WORLD, Theme.NEON_NOIR, Theme.VIOLET_RAIN, Theme.ARCTIC)) {
            MapState.setTheme(th); MapState.setLightMode(if (th == Theme.ARCTIC || th == Theme.OPEN_WORLD) MapState.LightMode.LIGHT else MapState.LightMode.DARK)
            val w = if (car) 1920 else 1080; val h = if (car) 720 else 2340; val d = if (car) 1.6f else 2.75f
            val rr = MapRenderer(car = car).apply { carButtons = car; forceLite = false }
            val c = Canvas(Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888))
            val area = Rect(0, 0, w, h)
            var m = 200.0
            repeat(60) { LocationTracker.onLocationChanged(at(m)); rr.draw(c, w, h, area, d, true); shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50)); Thread.sleep(40) }
            // the middle of three runs (the test machine has its moments)
            val runs = (1..3).map {
                val t0 = System.nanoTime()
                repeat(60) { k ->
                    if (k % 30 == 0) { m += 12.0; LocationTracker.onLocationChanged(at(m)) }
                    rr.draw(c, w, h, area, d, true)
                }
                (System.nanoTime() - t0) / 60 / 1e6
            }.sorted()
            val ms = runs[1]
            val budget = if (car) budgetCar else budgetPhone
            val line = "${if (car) "car  " else "phone"} ${th.name.padEnd(13)} ${"%5.1f".format(ms)} ms (budget ${budget.toInt()})"
            log.append(line).append('\n')
            if (ms > budget) over += line
        }
        File(out, "frame_budget.txt").writeText(log.toString())
        println(log)
        assertTrue("slower than the budget:\n" + over.joinToString("\n"), over.isEmpty())
    }
}
