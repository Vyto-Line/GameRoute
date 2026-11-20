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
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The car with no route and the map turned (the photo, 20 Nov): the compass sat on top of the
 * Home chip. It must stay clear of Where to? and every chip, on every car screen size.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class CarCompassTest {
    @Test
    fun clearOfTheChips() {
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        MapState.init(RuntimeEnvironment.getApplication())
        val here = LatLng(37.8950, 23.7470)
        LocationTracker.onLocationChanged(Location("gps").apply { latitude = here.lat; longitude = here.lon; accuracy = 5f; time = System.currentTimeMillis() })
        Places.save("home", Place("Home", "Nikomachou 47", Geo.offset(here, 40f, 900.0)))
        Places.save("work", Place("Work", "Syngrou", Geo.offset(here, 10f, 4000.0)))
        Places.saveFavourite(Place("Dad", "", Geo.offset(here, 200f, 1500.0)), "Dad", "👤")
        Places.saveFavourite(Place("Thea Glyfadas", "", Geo.offset(here, 160f, 2500.0)), "Θεα Γλυφαδας", "★")
        MapState.setTheme(Theme.VIOLET_RAIN)
        MapState.car.rotateBy(25f, 0f)
        val log = StringBuilder()
        var bad = 0
        for ((w, h, d) in listOf(Triple(1920, 720, 1.6f), Triple(1280, 720, 1.3f), Triple(800, 480, 1.0f), Triple(1280, 480, 1.0f))) {
            val r = MapRenderer(car = true)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            repeat(3) { r.draw(Canvas(bmp), w, h, Rect(0, 0, w, h), d, true) }
            val hits = r.hitsShown.filter { RectF.intersects(it.first, r.compassBounds) }
            log.append("${w}x$h: compass ${r.compassBounds}, overlapping: ${hits.map { it.second }}\n")
            if (r.compassBounds.isEmpty || hits.isNotEmpty()) bad++
            File(out, "car_compass_${w}x$h.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
        }
        MapState.car.clearRotation()
        File(out, "carcompass.txt").writeText(log.toString())
        assertTrue("the compass overlaps a button:\n$log", bad == 0)
    }
}
