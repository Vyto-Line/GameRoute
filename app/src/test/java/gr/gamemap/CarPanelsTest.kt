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
        // the convoy step by step in the car (47b-47d), and the race board after finishing (47a)
        Convoy.leave(quiet = true)
        Convoy.debugStart(Place("x", "", LatLng(37.98, 23.73)), "k7p2qx"); Convoy.debugSet(null, true)
        gr.gamemap.car.MapScreen::class.java.getDeclaredMethod("openConvoyWhere").apply { isAccessible = true }.invoke(screen)
        var p = rend.carPanel!!
        log.append("where: ${p.title} · ${p.tiles.map { it.title }} · ${p.note}\n")
        assertTrue("step 1 opens", p.key == "cwhere" && p.tiles.any { it.id == "search" } && p.tiles.any { it.id == "share" })
        var h = render("convoy_where", p); assertTrue("step 1 tiles tappable", "cp:search" in h && "cp:share" in h)
        Convoy.debugSet(Place("Sounio", "", LatLng(37.65, 24.02)), true)
        gr.gamemap.car.MapScreen::class.java.getDeclaredMethod("openConvoyHow", String::class.java).apply { isAccessible = true }.invoke(screen, "along")
        p = rend.carPanel!!
        log.append("how: ${p.title} · ${p.rows.map { it.title + (it.right ?: "") }}\n")
        assertTrue("step 2: Drive together picked", p.key == "chow" && p.rows.first { it.id == "along" }.highlight)
        h = render("convoy_how", p); assertTrue("step 2 tappable", "cp:race" in h && "cp:next" in h)
        // the race board: I'm 2nd, one still driving
        val t = System.currentTimeMillis()
        Convoy.members.clear()
        Convoy.debugMember("Nikos", LatLng(37.65, 24.02), 0, 0, -1.0, -1.0, "arrived")
        Convoy.members.values.first().arrivedAt = t - 95_000
        Convoy.debugMember("Maria", LatLng(37.70, 23.95), 64, t + 180_000, 1200.0, 30_000.0, "driving")
        NavEngine.debugArrive(t)
        Thread.sleep(600)
        gr.gamemap.car.MapScreen::class.java.getDeclaredMethod("openRaceBoard").apply { isAccessible = true }.invoke(screen)
        p = rend.carPanel!!
        log.append("race: ${p.title} · ${p.rows.map { it.title + " " + (it.sub ?: "") }} · ${p.note}\n")
        assertTrue("the race board", p.key == "race" && p.title.contains("2nd") && p.rows.any { it.title == "Maria" })
        h = render("race_board", p); assertTrue("Done tappable", "cp:done" in h)
        NavEngine.stop(); Convoy.leave(quiet = true)
        Convoy.debugStart(Place("Nafplio", "", LatLng(37.567, 22.805)))
        // a long list pages
        val many = CarPanel("fuel", "Fuel · Diesel", rows = (1..11).map { CarRow("⛽", "Station $it", "${it * 200} m", "€1.7$it", "p:$it") },
            head = CarRow("", "Cheapest ▾", id = "sort"))
        val hits = render("fuel_long", many)
        assertTrue("pages", "cp:page+" in hits && "cp:sort" in hits)
        Convoy.leave(quiet = true)
    }
}
