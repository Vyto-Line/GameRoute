package gr.gamemap

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.location.Location
import android.os.Looper
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.testing.ScreenController
import androidx.car.app.testing.TestCarContext
import androidx.lifecycle.Lifecycle
import gr.gamemap.car.CarMenuScreen
import gr.gamemap.car.MapScreen
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** A tour of Android Auto as it is today: the car map in each state, and what the car's own screens contain. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class CarTourTest {
    private val log = StringBuilder()

    private fun act(a: Action) = (a.title?.toCharSequence()?.toString() ?: "") + (if (a.icon != null) " [icon]" else "") + (if (a.type != Action.TYPE_CUSTOM) " (type ${a.type})" else "")
    private fun strip(name: String, s: ActionStrip?) { if (s != null) log.append("  $name: ${s.actions.joinToString(" | ") { act(it) }}\n") }

    private fun dump(title: String, t: Template) {
        log.append("\n== $title: ${t.javaClass.simpleName} ==\n")
        when (t) {
            is ListTemplate -> {
                log.append("  title: ${t.title?.toCharSequence()}\n")
                strip("action strip", t.actionStrip)
                (t.singleList?.items ?: emptyList()).forEach { r -> if (r is Row) log.append("  • ${r.title?.toCharSequence()}" + r.texts.joinToString("") { "  |  " + it.toCharSequence() } + "\n") }
            }
            is NavigationTemplate -> {
                strip("action strip", t.actionStrip); strip("map action strip", t.mapController?.mapActionStrip)
                log.append("  turn info: ${t.navigationInfo?.javaClass?.simpleName ?: "none"} · travel estimate: ${t.destinationTravelEstimate != null}\n")
            }
            else -> log.append("  $t\n")
        }
    }

    @Test
    fun tour() {
        val data = File(System.getProperty("previewData") ?: "../previewdata")
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        data.listFiles()?.filter { it.name.endsWith(".pbf") }?.forEach { f ->
            val (x, y) = f.nameWithoutExtension.split("_").map { it.toInt() }
            VectorSource.put(14, x, y, VectorTile(f.readBytes()))
        }
        val opts = Osrm.parseAll(File(data, "route.json").readText())
        opts.forEachIndexed { i, o ->
            o.trafficSeconds = (o.duration * 1.4 + i * 90).toInt(); o.delaySeconds = if (i == 1) 240 else 60
            o.traffic = listOf(Stretch(o.length * 0.5, o.length * 0.6, 2, 60)); o.limits = listOf(Stretch(0.0, o.length, 50))
        }
        val r = opts[0]
        val dest = Place("National Archaeological Museum", "Patision 44", r.points.last())
        val looper = shadowOf(Looper.getMainLooper())
        var fix: Location? = null
        fun locate(m: Double, speed: Float = 11f) {
            var i = 0
            while (i < r.points.size - 2 && r.cum[i + 1] < m) i++
            val f = ((m - r.cum[i]) / maxOf(r.cum[i + 1] - r.cum[i], 0.1)).coerceIn(0.0, 1.0)
            val p = Geo.lerp(r.points[i], r.points[i + 1], f)
            fix = Location("gps").apply { latitude = p.lat; longitude = p.lon; this.speed = speed; bearing = Geo.bearing(r.points[i], r.points[i + 1]); accuracy = 5f; time = System.currentTimeMillis() }
            LocationTracker.onLocationChanged(fix!!)
        }
        val w = 1920; val h = 720
        val rr = MapRenderer(car = true).apply { carButtons = true }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val c = Canvas(bmp); val area = Rect(0, 0, w, h)
        fun shot(name: String, t: Theme, dark: Boolean = true) {
            MapState.setTheme(t); MapState.setLightMode(if (dark) MapState.LightMode.DARK else MapState.LightMode.LIGHT)
            repeat(35) { fix?.let { l -> LocationTracker.onLocationChanged(Location(l).apply { time = System.currentTimeMillis() }) }; rr.draw(c, w, h, area, 1.6f, true); looper.idleFor(java.time.Duration.ofMillis(60)); Thread.sleep(30) }
            rr.draw(c, w, h, area, 1.6f, true)
            File(out, "car_$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        locate(300.0, 0f)
        MapState.car.zoomBy(16.0 - MapState.car.targetZoom)
        shot("1_browse", Theme.NEON_NOIR)
        rr.selectedPlace = Place("EKO", "Fuel · Patision 60", LatLng(37.9880, 23.7300), "⛽", extra = "95 €1.970 · Diesel €2.035")
        shot("2_place", Theme.VIOLET_RAIN); rr.selectedPlace = null
        NavEngine.debugPlan(dest, opts); shot("3_routes", Theme.OPEN_WORLD, dark = false)
        NavEngine.cancelPlan()
        NavEngine.debugDrive(dest, r, 120); locate(400.0)
        repeat(100) { MapState.tick(); LocationTracker.tick(); looper.idleFor(java.time.Duration.ofMillis(10)) }
        shot("4_drive", Theme.VIOLET_RAIN)
        shot("5_drive_light", Theme.ARCTIC, dark = false)
        val now = System.currentTimeMillis()
        Convoy.myName = "Vyto"; Convoy.debugStart(dest)
        Convoy.debugMember("Nikos", r.points[r.points.size / 3], 48, now + 9 * 60_000L, r.length * 0.55, r.length, "driving")
        Convoy.debugMember("Maria", r.points[3], 31, now + 14 * 60_000L, r.length * 0.95, r.length, "driving")
        shot("6_convoy", Theme.NEON_NOIR)
        Convoy.leave(quiet = true)
        NavEngine.debugTrip(Explorer.Trip("National Archaeological Museum", now, 840, 4200.0, 120, 110, 1, 1,
            listOf(Xp.Part("Trip", 31), Xp.Part("Clean drive", 6), Xp.Part("New roads", 18), Xp.Part("Landmark", 25), Xp.Part("Daily streak", 30))))
        shot("7_arrival", Theme.RALLY, dark = false)
        NavEngine.dismissTrip(); NavEngine.stop()

        // the car's own screens (drawn by Android Auto from these templates)
        val ctx = TestCarContext.createCarContext(RuntimeEnvironment.getApplication())
        androidx.car.app.CarContext::class.java.getDeclaredMethod("updateHandshakeInfo", androidx.car.app.HandshakeInfo::class.java)
            .apply { isAccessible = true }.invoke(ctx, androidx.car.app.HandshakeInfo("com.google.android.projection.gearhead", 6))
        try {
            val m = MapScreen(ctx); ScreenController(m).moveToState(Lifecycle.State.RESUMED)
            dump("Map, browsing", m.onGetTemplate())
            NavEngine.debugDrive(dest, r, 120); locate(400.0); looper.idle()
            dump("Map, driving", m.onGetTemplate())
            NavEngine.stop(); looper.idle()
        } catch (e: Throwable) { log.append("map screen: $e\n") }
        try {
            val menu = CarMenuScreen(ctx); ScreenController(menu).moveToState(Lifecycle.State.RESUMED)
            dump("Menu", menu.onGetTemplate())
        } catch (e: Throwable) { log.append("menu: $e\n") }
        File(out, "car_tour.txt").writeText(log.toString())
    }
}
