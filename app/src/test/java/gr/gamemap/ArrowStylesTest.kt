package gr.gamemap

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.location.Location
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The arrow in every style, phone and car, close up: see-through (no disc), never lost on the map. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ArrowStylesTest {
    @Test
    fun everyStyle() {
        val data = File(System.getProperty("previewData") ?: "../previewdata")
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        data.listFiles()?.filter { it.name.endsWith(".pbf") }?.forEach { f ->
            val (x, y) = f.nameWithoutExtension.split("_").map { it.toInt() }
            VectorSource.put(14, x, y, VectorTile(f.readBytes()))
        }
        val start = Osrm.parseAll(File(data, "route.json").readText())[0].points[0]
        LocationTracker.onLocationChanged(Location("gps").apply { latitude = start.lat; longitude = start.lon; accuracy = 5f; time = System.currentTimeMillis() })
        val themes = Theme.entries
        val cell = 300
        val sheet = Bitmap.createBitmap(cell * 7, cell * ((themes.size * 2 + 6) / 7), Bitmap.Config.ARGB_8888)
        val sc = Canvas(sheet)
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 22f; color = android.graphics.Color.WHITE; setShadowLayer(3f, 0f, 0f, android.graphics.Color.BLACK) }
        var i = 0
        for (car in listOf(false, true)) for (theme in themes) {
            MapState.setTheme(theme)
            val ms = if (car) MapState.car else MapState.phone
            ms.zoomBy(17.0 - ms.targetZoom)
            repeat(300) { MapState.tick(); Thread.sleep(1) }
            val w = if (car) 1280 else 1080; val h = if (car) 480 else 1920
            val r = MapRenderer(car = car).apply { if (car) carButtons = true }
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            for (k in 0 until 120) {
                r.draw(c, w, h, Rect(0, 0, w, h), if (car) 1f else 2.75f, hud = true)
                Thread.sleep(40); org.robolectric.shadows.ShadowLooper.idleMainLooper()
                if (k > 40 && VectorSource.busy.get() == 0) break
            }
            r.draw(c, w, h, Rect(0, 0, w, h), if (car) 1f else 2.75f, hud = true)
            val p = r.lastPlayerAt ?: continue
            val half = if (car) 80 else 150
            val crop = Bitmap.createBitmap(bmp, (p.x - half).toInt().coerceIn(0, w - 2 * half), (p.y - half).toInt().coerceIn(0, h - 2 * half), 2 * half, 2 * half)
            val dst = Rect((i % 7) * cell, (i / 7) * cell, (i % 7) * cell + cell, (i / 7) * cell + cell)
            sc.drawBitmap(crop, null, dst, null)
            sc.drawText((if (car) "car · " else "") + theme.name.lowercase(), dst.left + 8f, dst.top + 26f, label)
            i++
        }
        File(out, "arrow_styles.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 90, it) }
    }
}
