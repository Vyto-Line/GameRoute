package gr.gamemap

import android.graphics.Bitmap
import android.graphics.Canvas
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Roads in tunnels are drawn (faded, dashed edges), in every style. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class TunnelTest {
    @Test
    fun tunnelsDrawn() {
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        VectorSource.init(RuntimeEnvironment.getApplication())
        // central Athens at z14: find the tile with the most tunnel road
        var best: Triple<Int, Int, Int>? = null
        for (x in 9254..9260) for (y in 6302..6308) {
            val vt = VectorSource.get(14, x, y) ?: continue
            val n = vt.features.count { it.layer == "transportation" && it["brunnel"] == "tunnel" && it.type == 2 && it["class"] in setOf("motorway", "trunk", "primary", "secondary", "tertiary") }
            if (n > (best?.third ?: 0)) best = Triple(x, y, n)
        }
        assertTrue("a tile with a tunnel", best != null)
        val (tx, ty, n) = best!!
        val themes = Theme.entries
        val tile = 512
        val sheet = Bitmap.createBitmap(tile * 2 * themes.size, tile * 2, Bitmap.Config.ARGB_8888)
        val c = Canvas(sheet)
        for ((i, th) in themes.withIndex()) {
            for (sx in 0..1) for (sy in 0..1) {
                val b = VectorRenderer.render(th, 15, tx * 2 + sx, ty * 2 + sy, tile, dark = true) ?: continue
                c.drawBitmap(Bitmap.createScaledBitmap(b, tile, tile, false), (i * 2 + sx) * tile.toFloat(), sy * tile.toFloat(), null)
            }
        }
        File(out, "tunnels.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
        File(out, "tunnels.txt").writeText("tile 14/$tx/$ty: $n tunnel roads; styles: ${themes.joinToString { it.name }}\n")
    }
}
