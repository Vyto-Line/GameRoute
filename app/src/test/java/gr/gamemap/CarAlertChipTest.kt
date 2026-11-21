package gr.gamemap

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.location.Location
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** A warning ahead ("Road closed · 600 m") in the car never covers the buttons (photo, 21 Nov), in every style and screen size. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class CarAlertChipTest {
    @Test
    fun chipClearOfButtons() {
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        LocationTracker.onLocationChanged(Location("gps").apply { latitude = 37.968; longitude = 23.746; accuracy = 5f; time = System.currentTimeMillis() })
        Alerts.ahead = Alerts.Ahead("t", "Road closed", "⛔", 600.0, "")
        val log = StringBuilder()
        for ((w, h) in listOf(1280 to 480, 800 to 480, 1920 to 720, 1024 to 768)) for (th in Theme.entries) {
            MapState.setTheme(th)
            val r = MapRenderer(car = true).apply { carButtons = true }
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            r.draw(Canvas(bmp), w, h, Rect(0, 0, w, h), 1f, true)
            val chip = RectF(r.alertChipAt)
            val hit = r.carHits.filter { RectF.intersects(it.first, chip) }.map { it.second }
            if (hit.isNotEmpty()) log.append("${w}x$h ${th.name}: chip $chip covers $hit\n")
            if (th == Theme.NEON_NOIR) File(out, "car_alert_${w}x$h.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
        }
        Alerts.ahead = null
        File(out, "car_alert.txt").writeText(log.ifEmpty { "no overlaps" }.toString())
        assertTrue(log.toString(), log.isEmpty())
    }
}
