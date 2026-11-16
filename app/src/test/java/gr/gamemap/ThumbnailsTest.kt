package gr.gamemap

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Small light/dark previews of every theme for the style picker (res/drawable-nodpi/thumb_*.webp). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ThumbnailsTest {
    @Test
    fun thumbnails() {
        val data = File(System.getProperty("previewData") ?: "../previewdata")
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        data.listFiles()?.filter { it.name.endsWith(".pbf") }?.forEach { f ->
            val (x, y) = f.nameWithoutExtension.split("_").map { it.toInt() }
            VectorSource.put(14, x, y, VectorTile(f.readBytes()))
        }
        val w = 336; val h = 264
        for (mode in listOf(MapState.LightMode.LIGHT, MapState.LightMode.DARK)) {
            for (theme in Theme.entries) {
                MapState.setLightMode(mode); MapState.setTheme(theme)
                MapState.zoomBy((if (theme == Theme.BLOCKY) 16.6 else 16.0) - MapState.targetZoom)
                repeat(400) { MapState.tick(); Thread.sleep(2) }
                val r = MapRenderer().apply { forceLite = false }
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val c = Canvas(bmp)
                // draw until the map squares are in (roads and blocks bring many colours) and
                // nothing changes any more: a slow test machine never leaves an empty preview
                // (squares at the edge with no test data never come: wait until nothing changes)
                var ready = 0; var last = -1
                for (i in 0 until 600) {
                    r.draw(c, w, h, Rect(0, 0, w, h), 1.6f, hud = false); Thread.sleep(50)
                    if (r.squaresMissing == last && r.squaresMissing < r.squaresShown) ready++ else ready = 0
                    last = r.squaresMissing
                    if (ready >= 40) break // in, and two seconds more for the fades
                }
                r.draw(c, w, h, Rect(0, 0, w, h), 1.6f, hud = false)
                val colours = colours(bmp)
                println("thumb ${theme.name} $mode: missing=${r.squaresMissing} of ${r.squaresShown}, colours=$colours, problem=${TileCache.lastProblem}")
                assertTrue("${theme.name} $mode: map squares never came (${r.squaresMissing} of ${r.squaresShown} missing; ${TileCache.lastProblem}; data ${VectorSource.debugKeys()})", r.squaresMissing * 2 < r.squaresShown)
                val tag = if (mode == MapState.LightMode.DARK) "d" else "l"
                @Suppress("DEPRECATION")
                File(out, "thumb_${theme.name.lowercase()}_$tag.webp").outputStream().use { bmp.compress(Bitmap.CompressFormat.WEBP, 82, it) }
            }
        }
    }

    /** Distinct colours (a little rounded), a cheap measure of how much map is drawn. */
    private fun colours(b: Bitmap): Int {
        val px = IntArray(b.width * b.height); b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        val seen = HashSet<Int>()
        for (i in px.indices step 3) seen += px[i] and 0xF0F0F0
        return seen.size
    }
}
