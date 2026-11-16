package gr.gamemap

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Looper
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Car map while choosing a route (routes on the map, no buttons): backgrounds for mockups. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class CarRoutesBgTest {
    @Test
    fun backgrounds() {
        val data = File(System.getProperty("previewData") ?: "../previewdata")
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        data.listFiles()?.filter { it.name.endsWith(".pbf") }?.forEach { f ->
            val (x, y) = f.nameWithoutExtension.split("_").map { it.toInt() }
            VectorSource.put(14, x, y, VectorTile(f.readBytes()))
        }
        val opts = Osrm.parseAll(File(data, "route.json").readText())
        opts.forEachIndexed { i, o -> o.trafficSeconds = (o.duration * 2.2 + i * 100).toInt() }
        NavEngine.debugPlan(Place("National Archaeological Museum", "Patision 44", opts[0].points.last()), opts)
        val w = 1920; val h = 720
        val rr = MapRenderer(car = true)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        for ((th, dark) in listOf(Theme.NEON_NOIR to true, Theme.OPEN_WORLD to false, Theme.TRAILBLAZER to false)) {
            MapState.setTheme(th); MapState.setLightMode(if (dark) MapState.LightMode.DARK else MapState.LightMode.LIGHT)
            // the routes on the right two thirds (the cards go on the left)
            val area = Rect(w * 2 / 5, 0, w, h)
            repeat(40) { rr.draw(c, w, h, area, 1.6f, hud = false); shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(60)); Thread.sleep(60) }
            rr.draw(c, w, h, area, 1.6f, hud = false)
            File(out, "car_routes_bg_${th.name.lowercase()}.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        File(out, "car_routes_bg.txt").writeText("routes ${opts.size}")
    }
}
