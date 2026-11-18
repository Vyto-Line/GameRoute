package gr.gamemap

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import androidx.car.app.testing.TestCarContext
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The car's panels in the style (menu, fuel, quick messages, report, leave the convoy): drawn, and tappable. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class CarPanelsTest {
    private val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
    private val log = StringBuilder()

    @Test
    fun panels() { try { run() } finally { File(out, "car_panels.txt").writeText(log.toString()) } }

    private fun render(name: String, p: CarPanel): Set<String> {
        val w = 1280; val h = 480
        val rr = MapRenderer(car = true).apply { carButtons = true; forceLite = false; carPanel = p }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        repeat(2) { rr.draw(Canvas(bmp), w, h, Rect(0, 0, w, h), 1f, true); Thread.sleep(80) }
        File(out, "carpanel_$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val found = HashSet<String>()
        var y = 0f
        while (y < h) { var x = 0f; while (x < w) { rr.carButtonAt(x, y)?.takeIf { it.startsWith("cp:") }?.let { found += it }; x += 10f }; y += 10f }
        log.append("$name: ${found.sorted()}\n")
        return found
    }

    private fun run() {
        val app = RuntimeEnvironment.getApplication()
        // the real panels, as the car builds them
        val ctx = TestCarContext.createCarContext(app)
        androidx.car.app.CarContext::class.java.getDeclaredMethod("updateHandshakeInfo", androidx.car.app.HandshakeInfo::class.java)
            .apply { isAccessible = true }.invoke(ctx, androidx.car.app.HandshakeInfo("com.google.android.projection.gearhead", 7))
        Convoy.debugStart(Place("Nafplio", "", LatLng(37.567, 22.805)))
        Convoy.debugMember("Nikos", LatLng(37.98, 23.73), 60, System.currentTimeMillis() + 3_600_000, 30_000.0, 120_000.0, "driving")
        val screen = gr.gamemap.car.MapScreen(ctx)
        val surf = gr.gamemap.car.MapScreen::class.java.getDeclaredField("surface").apply { isAccessible = true }.get(screen)
        val rend = surf.javaClass.getDeclaredField("renderer").apply { isAccessible = true }.get(surf) as MapRenderer
        for ((m, name) in listOf("openMenuPanel" to "menu", "openQuickPanel" to "quick", "openReportPanel" to "report", "openLeavePanel" to "leave")) {
            gr.gamemap.car.MapScreen::class.java.getDeclaredMethod(m).apply { isAccessible = true }.invoke(screen)
            val p = rend.carPanel
            log.append("$m → ${p?.key}: ${p?.title} · rows ${p?.rows?.map { it.title }} tiles ${p?.tiles?.map { it.title }}\n")
            assertTrue("$m opens its panel", p?.key == name)
            val hits = render(name, p!!)
            assertTrue("$name: ‹ or its buttons work", hits.isNotEmpty())
            if (p.dialog) assertTrue("leave: Stay and Leave", "cp:stay" in hits && "cp:go" in hits)
            else assertTrue("$name: back", "cp:back" in hits)
            (p.rows + p.tiles).filter { !p.dialog }.forEach { r -> assertTrue("$name: ${r.title} tappable", "cp:" + r.id in hits || p.rows.size > 6) }
        }
        // a long list pages
        val many = CarPanel("fuel", "Fuel · Diesel", rows = (1..11).map { CarRow("⛽", "Station $it", "${it * 200} m", "€1.7$it", "p:$it") },
            head = CarRow("", "Cheapest ▾", id = "sort"))
        val hits = render("fuel_long", many)
        assertTrue("pages", "cp:page+" in hits && "cp:sort" in hits)
        Convoy.leave(quiet = true)
    }
}
