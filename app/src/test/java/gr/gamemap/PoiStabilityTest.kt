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

/** Map icons while the map moves: no flicker (icons swapping in the middle), few car parks, chargers as chargers. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class PoiStabilityTest {
    @Test
    fun steady() {
        val data = File(System.getProperty("previewData") ?: "../previewdata")
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        data.listFiles()?.filter { it.name.endsWith(".pbf") }?.forEach { f ->
            val (x, y) = f.nameWithoutExtension.split("_").map { it.toInt() }
            VectorSource.put(14, x, y, VectorTile(f.readBytes()))
        }
        val looper = shadowOf(Looper.getMainLooper())
        val w = 1170; val h = 2532
        val r = MapRenderer(car = false)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val c = Canvas(bmp)
        val log = StringBuilder()
        val start = LatLng(37.9800, 23.7300)
        for ((zoom, label) in listOf(16.2 to "zoom 16.2", 17.3 to "zoom 17.3")) {
            MapState.phone.pan(start, 0f); MapState.phone.zoomBy(zoom - MapState.phone.targetZoom)
            repeat(20) { MapState.tick(); r.draw(c, w, h, Rect(0, 0, w, h), 3f, true); looper.idleFor(java.time.Duration.ofMillis(30)) }
            var prev: Set<String>? = null
            var swaps = 0; var frames = 0; var maxParking = 0
            val kinds = HashMap<String, Int>()
            for (step in 0 until 120) { // pan slowly east (about 2 px a frame)
                MapState.phone.pan(Geo.offset(start, 90f, step * 1.5), 0f); MapState.tick()
                r.draw(c, w, h, Rect(0, 0, w, h), 3f, true)
                val now = r.pois.shownNow()
                maxParking = maxOf(maxParking, now.count { it.kind == PoiLayer.Kind.PARKING })
                now.forEach { kinds[it.kind.name] = (kinds[it.kind.name] ?: 0) + 1 }
                // icons in the middle of the screen that vanish while the map barely moved = flicker
                val mid = now.filter { p -> Geo.distance(p.pos, Geo.offset(start, 90f, step * 1.5)) < 250 }.map { "${it.pos.lat},${it.pos.lon}" }.toSet()
                prev?.let { pr -> swaps += pr.count { it !in mid && Geo.distance(LatLng(it.substringBefore(',').toDouble(), it.substringAfter(',').toDouble()), Geo.offset(start, 90f, step * 1.5)) < 200 } }
                prev = mid; frames++
            }
            log.append("$label: $frames frames, icons vanishing in the middle: $swaps, most car parks at once: $maxParking, kinds seen: $kinds\n")
            File(out, "poi_${zoom}.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        val chg = PoiLayer.of(14, 9273, 6324, VectorSource.peek(14, 9273, 6324) ?: VectorTile(ByteArray(0))).groupBy { it.kind }.mapValues { it.value.size }
        log.append("one tile, by kind: $chg\n")
        File(out, "poistable.txt").writeText(log.toString())
    }
}
