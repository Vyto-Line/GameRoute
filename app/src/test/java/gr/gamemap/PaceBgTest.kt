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

/** Mockup backgrounds: the car map while driving in every style, without the turn box, plus each style's colours. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class PaceBgTest {
    private fun hex(c: Int) = "#%06X".format(c and 0xFFFFFF)
    @Test
    fun backgrounds() {
        val data = File(System.getProperty("previewData") ?: "../previewdata")
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        data.listFiles()?.filter { it.name.endsWith(".pbf") }?.forEach { f ->
            val (x, y) = f.nameWithoutExtension.split("_").map { it.toInt() }
            VectorSource.put(14, x, y, VectorTile(f.readBytes()))
        }
        val opts = Osrm.parseAll(File(data, "route.json").readText())
        val r = opts[0]
        val dest = Place("National Archaeological Museum", "Patision 44", r.points.last())
        val looper = shadowOf(Looper.getMainLooper())
        var fix: Location? = null
        fun locate(m: Double) {
            var i = 0
            while (i < r.points.size - 2 && r.cum[i + 1] < m) i++
            val f = ((m - r.cum[i]) / maxOf(r.cum[i + 1] - r.cum[i], 0.1)).coerceIn(0.0, 1.0)
            val p = Geo.lerp(r.points[i], r.points[i + 1], f)
            fix = Location("gps").apply { latitude = p.lat; longitude = p.lon; speed = 11f; bearing = Geo.bearing(r.points[i], r.points[i + 1]); accuracy = 5f; time = System.currentTimeMillis() }
            LocationTracker.onLocationChanged(fix!!)
        }
        NavEngine.debugDrive(dest, r, 120); locate(400.0)
        repeat(100) { MapState.tick(); LocationTracker.tick(); looper.idleFor(java.time.Duration.ofMillis(10)) }
        val w = 1920; val h = 720
        val rr = MapRenderer(car = true)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val c = Canvas(bmp); val area = Rect(0, 0, w, h)
        val lightOnes = setOf(Theme.OPEN_WORLD, Theme.BLOCKY, Theme.PARCHMENT_QUEST, Theme.RETRO, Theme.TRAILBLAZER, Theme.RALLY, Theme.NAUTICAL, Theme.ARCTIC)
        val js = StringBuilder("[\n")
        for (t in Theme.values()) {
            val dark = t !in lightOnes
            MapState.setTheme(t); MapState.setLightMode(if (dark) MapState.LightMode.DARK else MapState.LightMode.LIGHT)
            repeat(30) { fix?.let { l -> LocationTracker.onLocationChanged(Location(l).apply { time = System.currentTimeMillis() }) }; rr.draw(c, w, h, area, 1.6f, false); looper.idleFor(java.time.Duration.ofMillis(60)); Thread.sleep(20) }
            rr.draw(c, w, h, area, 1.6f, false)
            File(out, "pace_${t.name.lowercase()}.jpg").outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 86, it) }
            val l = t.lookFor(dark)
            js.append("""{"id":"${t.name}","title":"${t.title}","dark":$dark,"route":"${hex(l.route)}","text":"${hex(l.hudText)}","panel":"${hex(l.hudPanel)}","panelA":${(l.hudPanel ushr 24)},"player":"${hex(l.player)}","land":"${hex(l.style.land)}","major":"${hex(l.style.major)}","hud":"${t.hud}","serif":${t.serif},"paper":${t.paper},"pixel":${t.pixel}},""" + "\n")
        }
        File(out, "pace_styles.json").writeText(js.toString().trimEnd(',', '\n') + "\n]\n")
    }
}
