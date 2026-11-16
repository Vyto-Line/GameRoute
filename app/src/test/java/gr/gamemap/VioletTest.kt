package gr.gamemap

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.location.Location
import android.os.Looper
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Violet Rain while driving: phone and car, dark and light, and the frame time. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class VioletTest {
    @Test
    fun drive() {
        val data = File(System.getProperty("previewData") ?: "../previewdata")
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        data.listFiles()?.filter { it.name.endsWith(".pbf") }?.forEach { f ->
            val (x, y) = f.nameWithoutExtension.split("_").map { it.toInt() }
            VectorSource.put(14, x, y, VectorTile(f.readBytes()))
        }
        val r = Osrm.parseAll(File(data, "route.json").readText())[0]
        r.limits = listOf(Stretch(0.0, r.length, 50))
        NavEngine.debugDrive(Place("Museum", "", r.points.last()), r, 60)
        fun at(m: Double): Location {
            var i = 0
            while (i < r.points.size - 2 && r.cum[i + 1] < m) i++
            val f = ((m - r.cum[i]) / maxOf(r.cum[i + 1] - r.cum[i], 0.1)).coerceIn(0.0, 1.0)
            val p = Geo.lerp(r.points[i], r.points[i + 1], f)
            return Location("gps").apply { latitude = p.lat; longitude = p.lon; speed = 12f; bearing = Geo.bearing(r.points[i], r.points[i + 1]); accuracy = 5f; time = System.currentTimeMillis() }
        }
        val log = StringBuilder()
        MapState.setTheme(Theme.VIOLET_RAIN)
        for (mode in listOf(MapState.LightMode.DARK, MapState.LightMode.LIGHT)) for (car in listOf(false, true)) {
            MapState.setLightMode(mode)
            val w = if (car) 1920 else 1080; val h = if (car) 720 else 2340; val d = if (car) 1.6f else 2.75f
            val rr = MapRenderer(car = car).apply { carButtons = car }
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val area = Rect(0, 0, w, h)
            LocationTracker.onLocationChanged(at(300.0))
            repeat(60) { rr.draw(c, w, h, area, d, true); shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50)); Thread.sleep(40) }
            val t0 = System.nanoTime()
            repeat(30) { rr.draw(c, w, h, area, d, true) }
            log.append("${if (car) "car  " else "phone"} ${mode.name.padEnd(5)} ${"%.1f".format((System.nanoTime() - t0) / 30 / 1e6)} ms/frame\n")
            File(out, "violet_${if (car) "car" else "phone"}_${mode.name.lowercase()}.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
        }
        File(out, "violet.txt").writeText(log.toString())
    }
}
