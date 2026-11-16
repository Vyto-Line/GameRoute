package gr.gamemap

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Looper
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.testing.ScreenController
import androidx.car.app.testing.TestCarContext
import androidx.lifecycle.Lifecycle
import gr.gamemap.car.MapScreen
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Android Auto: choosing a route on GameRoute's own cards (newer cars); the template still builds. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class CarRoutesTest {
    @Test
    fun cards() {
        val data = File(System.getProperty("previewData") ?: "../previewdata")
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        data.listFiles()?.filter { it.name.endsWith(".pbf") }?.forEach { f ->
            val (x, y) = f.nameWithoutExtension.split("_").map { it.toInt() }
            VectorSource.put(14, x, y, VectorTile(f.readBytes()))
        }
        val opts = Osrm.parseAll(File(data, "route.json").readText()).toMutableList()
        opts.forEachIndexed { i, o ->
            o.trafficSeconds = (o.duration * 2.2 + i * 100).toInt()
            o.delaySeconds = if (i == 1) 240 else 30
            o.traffic = if (i == 1) listOf(Stretch(o.length * 0.3, o.length * 0.55, 3, 200)) else listOf(Stretch(o.length * 0.5, o.length * 0.58, 1, 30))
        }
        opts[opts.size - 1].tollCost = 2.4
        val place = Place("National Archaeological Museum", "Patision 44", opts[0].points.last())
        NavEngine.debugPlan(place, opts)
        val log = StringBuilder()

        // the template (Start, next route, ✕) builds on a newer car
        val ctx = TestCarContext.createCarContext(RuntimeEnvironment.getApplication())
        androidx.car.app.CarContext::class.java.getDeclaredMethod("updateHandshakeInfo", androidx.car.app.HandshakeInfo::class.java)
            .apply { isAccessible = true }.invoke(ctx, androidx.car.app.HandshakeInfo("com.google.android.projection.gearhead", 6))
        val screen = MapScreen(ctx)
        ScreenController(screen).moveToState(Lifecycle.State.RESUMED)
        val tpl: Template = screen.onGetTemplate()
        val acts = (tpl as NavigationTemplate).actionStrip?.actions?.map { it.title?.toCharSequence()?.toString() ?: "icon" }
        log.append("template while choosing: ${tpl.javaClass.simpleName} actions=$acts\n")
        assertTrue(acts?.firstOrNull() == "Start")

        // the cards, in three styles
        val w = 1920; val h = 720
        val rr = MapRenderer(car = true).apply { carButtons = true }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        for ((th, dark) in listOf(Theme.NEON_NOIR to true, Theme.OPEN_WORLD to false, Theme.TRAILBLAZER to false, Theme.RETRO to true)) {
            MapState.setTheme(th); MapState.setLightMode(if (dark) MapState.LightMode.DARK else MapState.LightMode.LIGHT)
            MapState.car.showOverview(opts.flatMap { o -> o.points.filterIndexed { i, _ -> i % 5 == 0 } })
            val area = Rect(0, 0, w, h)
            repeat(30) { rr.draw(c, w, h, area, 1.6f, hud = true); shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(60)); Thread.sleep(50) }
            rr.draw(c, w, h, area, 1.6f, hud = true)
            File(out, "car_routes_${th.name.lowercase()}.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        // a tap on the second card picks it
        val second = rr.carButtonAt(300f * 1.6f / 1.6f, 0f)
        log.append("hit at the top-left: $second\n")
        File(out, "car_routes.txt").writeText(log.toString())
    }
}
