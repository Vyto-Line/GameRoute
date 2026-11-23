package gr.gamemap

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.location.Location
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Parked car, tolls in the route picker, the toll chip and "No GPS" while driving (phone and car). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class NewFeaturesTest {
    private fun find(v: View): GameMapView? {
        if (v is GameMapView) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
        return null
    }

    @Test
    fun screens() {
        val data = File(System.getProperty("previewData") ?: "../previewdata")
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        data.listFiles()?.filter { it.name.endsWith(".pbf") }?.forEach { f ->
            val (x, y) = f.nameWithoutExtension.split("_").map { it.toInt() }
            VectorSource.put(14, x, y, VectorTile(f.readBytes()))
        }
        val opts = Osrm.parseAll(File(data, "route.json").readText())
        val r = opts[0]
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = activity.window.decorView
        val looper = shadowOf(Looper.getMainLooper())
        fun shot(name: String) {
            val tmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            repeat(30) { looper.idleFor(java.time.Duration.ofMillis(60)); root.draw(Canvas(tmp)); Thread.sleep(30) }
            val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bmp))
            File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        fun locate(m: Double, speed: Float) {
            var i = 0
            while (i < r.points.size - 2 && r.cum[i + 1] < m) i++
            val p = Geo.lerp(r.points[i], r.points[i + 1], ((m - r.cum[i]) / maxOf(r.cum[i + 1] - r.cum[i], 0.1)).coerceIn(0.0, 1.0))
            LocationTracker.onLocationChanged(Location("gps").apply {
                latitude = p.lat; longitude = p.lon; this.speed = speed; bearing = Geo.bearing(r.points[i], r.points[i + 1]); accuracy = 5f; time = System.currentTimeMillis()
            })
        }
        MapState.setTheme(Theme.TRAILBLAZER); MapState.setLightMode(MapState.LightMode.LIGHT)
        MapState.zoomBy(16.5 - MapState.targetZoom)
        locate(300.0, 0f)
        // parked car: home card, marker, place card
        Parked.save(r.points[r.points.size / 3], System.currentTimeMillis() - 40 * 60_000L)
        shot("new_parked_home")
        val m = ActivityMethod.of("showPlace", Place::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
        m.invoke(activity, Parked.place(), false); shot("new_parked_card")
        activity.onBackPressedDispatcher.onBackPressed(); looper.idle()
        Parked.clear()
        // tolls in the route picker
        opts.forEachIndexed { i, o ->
            o.trafficSeconds = (o.duration * 1.6).toInt() + i * 120
            o.tolls = if (i == 0) listOf(Tolls.OnRoute("Μετωπικά Διόδια Ελευσίνας", 2.5, o.length * 0.3, o.points[o.points.size / 3]),
                Tolls.OnRoute("Μετωπικά Διόδια Ισθμού", 2.1, o.length * 0.7, o.points[o.points.size * 2 / 3])) else emptyList()
        }
        NavEngine.debugPlan(Place("National Archaeological Museum", "Patision 44", r.points.last()), opts); shot("new_routes_tolls")
        // driving toward a toll
        NavEngine.debugDrive(Place("National Archaeological Museum", "Patision 44", r.points.last()), r, 60)
        locate(r.length * 0.3 - 700, 14f)
        repeat(100) { MapState.tick(); LocationTracker.tick(); looper.idleFor(java.time.Duration.ofMillis(10)) }
        shot("new_toll_chip")
        // car
        val w = 1920; val h = 720
        val rr = MapRenderer(car = true).apply { carButtons = true }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val c = Canvas(bmp); val area = Rect(0, 0, w, h)
        repeat(30) { rr.draw(c, w, h, area, 1.6f, true); looper.idleFor(java.time.Duration.ofMillis(60)); Thread.sleep(30) }
        File(out, "new_car_toll.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // tunnel: stop fixes for a few seconds
        repeat(5) { Thread.sleep(1000); looper.idleFor(java.time.Duration.ofMillis(1000)) }
        shot("new_tunnel")
        File(out, "new.txt").writeText("estimating=${NavEngine.estimating} tollAhead=${NavEngine.tollAhead?.second}\n")
    }
}
