package gr.gamemap

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The six new destination flags (mockups 53a-53c) and the two new styles' panels and fonts, drawn. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class FlagsTest {
    @Test
    fun flagsAndPanels() {
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        val ids = listOf("flag_pitboard", "flag_banner", "flag_star", "flag_x", "flag_beacon", "flag_rank")
        assertTrue("all six in the Garage", ids.all { id -> Xp.kindItems("flag").any { it.id == id } })
        val b = Bitmap.createBitmap(6 * 180, 220, Bitmap.Config.ARGB_8888)
        val c = Canvas(b); c.drawColor(Color.rgb(20, 24, 36))
        ids.forEachIndexed { i, id -> Flags.draw(c, id, i * 180 + 90f, 190f, 2.2f, Color.rgb(205, 127, 50)) }
        File(out, "flags_new.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // something drawn for each
        ids.forEachIndexed { i, id ->
            var n = 0
            for (x in i * 180 until i * 180 + 180 step 3) for (y in 0 until 220 step 3) if (b.getPixel(x, y) != Color.rgb(20, 24, 36)) n++
            assertTrue("$id drawn", n > 40)
        }
        // the panels: glass (Tokyo Night) and paper (Watercolour), and their fonts (with Greek)
        Fonts.init(org.robolectric.RuntimeEnvironment.getApplication())
        val p = Bitmap.createBitmap(820, 240, Bitmap.Config.ARGB_8888)
        val pc = Canvas(p); pc.drawColor(Color.rgb(120, 120, 130))
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 34f }
        listOf(Theme.TOKYO_NIGHT, Theme.WATERCOLOUR).forEachIndexed { i, th ->
            MapState.setTheme(th); MapState.setLightMode(if (i == 0) MapState.LightMode.DARK else MapState.LightMode.LIGHT)
            val dr = HudDrawable(th.hud, th.hudPanel, 2.5f, 16f, glow = th.route)
            dr.setBounds(20 + i * 400, 30, 380 + i * 400, 210); dr.draw(pc)
            tp.typeface = th.typeface; tp.color = th.hudText
            pc.drawText("Where to? Πού πας;", 44f + i * 400, 110f, tp)
            pc.drawText("7 min · Ακαδημίας", 44f + i * 400, 160f, tp)
            assertTrue("${th.name} has its own font", th.font != null && Fonts.get(th.font!!) != null)
        }
        File(out, "panels_new.png").outputStream().use { p.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
