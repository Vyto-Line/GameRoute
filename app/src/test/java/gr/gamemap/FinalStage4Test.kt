package gr.gamemap

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.location.Location
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.car.app.testing.ScreenController
import androidx.car.app.testing.TestCarContext
import androidx.lifecycle.Lifecycle
import gr.gamemap.car.MapScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Update 100, stage 4: declutter, What's new, the car arrival card, the road you're on, the pace-note
 * turn box in every style, directions on the car's own displays, prestige ranks, celebrations,
 * your recap, the home screen widget, backup & restore.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class FinalStage4Test {
    private val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
    private val log = StringBuilder()

    @Test
    fun stage4() {
        try { run() } finally { File(out, "stage4.txt").writeText(log.toString()) }
    }

    private fun run() {
        val app = RuntimeEnvironment.getApplication()
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = activity.window.decorView
        val looper = shadowOf(Looper.getMainLooper())
        fun idle() = repeat(20) { looper.idleFor(java.time.Duration.ofMillis(60)) }
        fun texts(v: View, acc: MutableList<String> = mutableListOf()): List<String> {
            if (v is TextView && v.isShown) acc += v.text.toString()
            if (v is ViewGroup) for (i in 0 until v.childCount) texts(v.getChildAt(i), acc)
            return acc
        }
        fun tap(label: String) {
            fun find(v: View): View? {
                if (v is TextView && v.isShown && v.text.toString().startsWith(label)) return v
                if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
                return null
            }
            var t = find(root) ?: error("no '$label' in ${texts(root)}")
            while (!t.hasOnClickListeners() && t.parent is View) t = t.parent as View
            t.performClick(); idle()
        }
        fun shot(name: String) {
            idle()
            val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888); root.draw(Canvas(bmp))
            File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }

        // ---- declutter and What's new
        log.append("== Declutter, What's new ==\n")
        idle()
        val home = texts(root)
        assertFalse("no Report button", home.any { it == "⚠" })
        activity.showSettings(); idle(); tap("Map")
        assertTrue("Show traffic in Settings › Map", texts(root).any { it.startsWith("Show traffic") })
        tap("‹ Settings"); tap("Advanced")
        assertTrue(texts(root).any { it.startsWith("What's new") })
        tap("What's new"); shot("stage4_whatsnew")
        log.append("What's new: ${texts(root).filter { it.length > 3 }.take(6)}\n")

        // ---- prestige
        log.append("\n== Prestige ==\n")
        val prefs = app.getSharedPreferences("gamemap", Context.MODE_PRIVATE)
        var x = 0; while (Explorer.levelFor(x) < 50) x += 500
        prefs.edit().putInt("xp", x).apply()
        assertTrue(Xp.canPrestige)
        val (name1, boost1, items1) = Xp.nextRank()
        log.append("at level 50: next rank $name1 +$boost1% with ${items1.map { it.name }}\n")
        assertEquals("Bronze", name1)
        activity.openSheetForTest("progress"); shot("stage4_progress_50")
        Xp.doPrestige()
        assertEquals("Bronze", Xp.rankName()); assertEquals(10, Xp.boostPct)
        assertTrue("Bronze rewards unlocked", Xp.items.filter { it.rank == 1 }.all { it.unlocked })
        assertFalse("Silver rewards not yet", Xp.items.filter { it.rank == 2 }.any { it.unlocked })
        Xp.startTrip()
        val parts = Xp.finish(4000.0, 0, 0, false, null, 0)
        log.append("a trip as Bronze: ${parts.joinToString { "${it.label} ${it.xp}" }}\n")
        assertTrue(parts.any { it.label.startsWith("Bronze boost +10%") })
        assertTrue("friends see the rank", Xp.badge().startsWith("◆ Bronze"))
        activity.openSheetForTest("progress"); shot("stage4_progress_bronze")

        // ---- celebrations: one frame from the middle and the end of each kind
        for ((th, lv) in listOf(Theme.NEON_NOIR to 10, Theme.VIOLET_RAIN to 20, Theme.RALLY to 5, Theme.NAUTICAL to 15, Theme.OPEN_WORLD to 25, Theme.NEON_NOIR to 50)) {
            MapState.setTheme(th)
            for (ms in listOf(800L, 2600L)) {
                val m = activity.milestoneForTest(lv, ms)
                m.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2340, View.MeasureSpec.EXACTLY))
                m.layout(0, 0, 1080, 2340)
                val b = Bitmap.createBitmap(1080, 2340, Bitmap.Config.ARGB_8888); m.draw(Canvas(b))
                File(out, "stage4_milestone_${th.name.lowercase()}_${lv}_$ms.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 90, it) }
            }
        }
        log.append("celebrations drawn: neon 10, violet 20, rally 5, nautical 15, free roam 25, level 50\n")

        // ---- recap: Home never shown
        log.append("\n== Recap ==\n")
        Places.save("home", Place("Home", "Glyfada", LatLng(37.8650, 23.7530)))
        val now = System.currentTimeMillis()
        val trips = listOf(
            Explorer.Trip("Home", now - 3_600_000, 1500, 18_000.0, 0, 60, 0, 30),
            Explorer.Trip("Home", now - 7_200_000, 1500, 18_000.0, 0, 60, 0, 30),
            Explorer.Trip("Nafplio", now - 86_400_000, 6700, 138_000.0, 0, 400, 1, 30, listOf(Xp.Part("New roads", 60))),
            Explorer.Trip("Nafplio", now - 2 * 86_400_000L, 6700, 138_000.0, 0, 380, 0, 30),
        )
        val st = Recap.stats(false, now, trips)
        log.append("this month: ${st.trips} trips, ${st.km.toInt()} km, ${"%.1f".format(st.hours)} h, new roads ${st.newRoadsKm} km, favourite ${st.favourite}\n")
        assertEquals("Nafplio", st.favourite)
        val cb = Bitmap.createBitmap(1080, 1350, Bitmap.Config.ARGB_8888)
        MapState.setTheme(Theme.VIOLET_RAIN); MapState.setLightMode(MapState.LightMode.DARK)
        Recap.draw(Canvas(cb), 1080f, 1350f, st, Recap.LINES.map { it.first }.toSet(), Theme.VIOLET_RAIN, "Vyto", Xp.title())
        File(out, "stage4_recap_card.png").outputStream().use { cb.compress(Bitmap.CompressFormat.PNG, 100, it) }
        for (t in trips) Explorer.record(t)
        activity.openSheetForTest("recap"); shot("stage4_recap_sheet")

        // ---- widget
        val wv = HomeWidget.views(app)
        assertNotNull(wv)
        val ring = HomeWidget.ring(app)
        File(out, "stage4_widget_ring.png").outputStream().use { ring.compress(Bitmap.CompressFormat.PNG, 100, it) }
        log.append("\nwidget builds; ring ${ring.width}px\n")

        // ---- backup & restore round trip
        val bo = ByteArrayOutputStream(); Backup.export(app, bo)
        val before = Explorer.xp
        prefs.edit().putInt("xp", 7).apply()
        val rootJ = Backup.peek(ByteArrayInputStream(bo.toByteArray()))!!
        log.append("backup: ${bo.size() / 1024} KB · ${Backup.describe(rootJ)}\n")
        assertTrue(Backup.restore(app, rootJ))
        assertEquals("XP back", before, Explorer.xp)
        assertEquals(null, Backup.peek(ByteArrayInputStream("{\"x\":1}".toByteArray())))

        // ---- driving: pace-note box in several styles, the road you're on, the car's own displays
        log.append("\n== Driving ==\n")
        val data = File(System.getProperty("previewData") ?: "../previewdata")
        data.listFiles()?.filter { it.name.endsWith(".pbf") }?.forEach { f ->
            val (tx, ty) = f.nameWithoutExtension.split("_").map { it.toInt() }
            VectorSource.put(14, tx, ty, VectorTile(f.readBytes()))
        }
        val r = Osrm.parseAll(File(data, "route.json").readText())[0]
        NavEngine.debugDrive(Place("National Archaeological Museum", "Patision 44", r.points.last()), r, 120)
        fun on(m: Double) {
            var i = 0
            while (i < r.points.size - 2 && r.cum[i + 1] < m) i++
            val f = ((m - r.cum[i]) / maxOf(r.cum[i + 1] - r.cum[i], 0.1)).coerceIn(0.0, 1.0)
            val p = Geo.lerp(r.points[i], r.points[i + 1], f)
            LocationTracker.onLocationChanged(Location("gps").apply { latitude = p.lat; longitude = p.lon; speed = 11f; accuracy = 5f; time = System.currentTimeMillis(); bearing = Geo.bearing(r.points[i], r.points[i + 1]) })
        }
        on(NavEngine.along + 20); looper.idle()
        log.append("next: ${NavEngine.currentStep?.text}, road now: '${NavEngine.currentRoad()}'\n")
        for ((th, dark) in listOf(Theme.NEON_NOIR to true, Theme.RALLY to false, Theme.ARCTIC to false, Theme.RETRO to false, Theme.NAUTICAL to false)) {
            MapState.setTheme(th); MapState.setLightMode(if (dark) MapState.LightMode.DARK else MapState.LightMode.LIGHT)
            val cw = 1920; val ch = 720
            val car = MapRenderer(car = true).apply { carButtons = true; forceLite = false }
            val cbm = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888); val cc = Canvas(cbm)
            repeat(25) { on(NavEngine.along + 1); car.draw(cc, cw, ch, Rect(0, 0, cw, ch), 1.6f, true); looper.idleFor(java.time.Duration.ofMillis(60)); Thread.sleep(15) }
            File(out, "stage4_turnbox_car_${th.name.lowercase()}.png").outputStream().use { cbm.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        MapState.setTheme(Theme.VIOLET_RAIN); MapState.setLightMode(MapState.LightMode.DARK)
        val pr = MapRenderer(car = false).apply { forceLite = false }
        val pb = Bitmap.createBitmap(1080, 2340, Bitmap.Config.ARGB_8888); val pc = Canvas(pb)
        repeat(25) { on(NavEngine.along + 1); pr.draw(pc, 1080, 2340, Rect(0, 0, 1080, 2340), 2.75f, true); looper.idleFor(java.time.Duration.ofMillis(60)); Thread.sleep(15) }
        File(out, "stage4_turnbox_phone.png").outputStream().use { pb.compress(Bitmap.CompressFormat.PNG, 100, it) }

        val ctx = TestCarContext.createCarContext(app)
        androidx.car.app.CarContext::class.java.getDeclaredMethod("updateHandshakeInfo", androidx.car.app.HandshakeInfo::class.java)
            .apply { isAccessible = true }.invoke(ctx, androidx.car.app.HandshakeInfo("com.google.android.projection.gearhead", 6))
        val ms = MapScreen(ctx); ScreenController(ms).moveToState(Lifecycle.State.RESUMED)
        val trip = ms.tripForTest()!!
        log.append("to the car's displays: step '${trip.steps.firstOrNull()?.cue?.toCharSequence()}', " +
            "to ${trip.destinations.firstOrNull()?.name?.toCharSequence()}, road '${trip.currentRoad?.toCharSequence()}'\n")
        assertTrue(trip.steps.isNotEmpty() && trip.destinations.isNotEmpty())

        // ---- the car arrival card
        NavEngine.debugTrip(Explorer.Trip("National Archaeological Museum of Athens", now, 840, 4200.0, 120, 110, 1, 1))
        MapState.setTheme(Theme.RALLY); MapState.setLightMode(MapState.LightMode.LIGHT)
        val car = MapRenderer(car = true).apply { carButtons = true; forceLite = false }
        val ab = Bitmap.createBitmap(1920, 720, Bitmap.Config.ARGB_8888); val ac = Canvas(ab)
        repeat(10) { car.draw(ac, 1920, 720, Rect(0, 0, 1920, 720), 1.6f, true); looper.idleFor(java.time.Duration.ofMillis(60)) }
        File(out, "stage4_car_arrival.png").outputStream().use { ab.compress(Bitmap.CompressFormat.PNG, 100, it) }
        NavEngine.dismissTrip(); NavEngine.stop()
        log.append("\nall stage 4 checks passed\n")
    }
}
