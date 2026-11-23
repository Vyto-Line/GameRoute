package gr.gamemap

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.location.Location
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.car.app.testing.TestCarContext
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Calendar
import java.util.TimeZone

/** Pharmacies on duty (mockups 51a-51g): map, evening bar, menu tile, list, card, search, car menu and panel. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class PharmacyDutyTest {
    private val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
    private val log = StringBuilder()

    @After fun done() { Duties.testNow = null; File(out, "pharmacy_duty_${name.methodName}.txt").writeText(log.toString()) }
    @get:org.junit.Rule val name = org.junit.rules.TestName()

    /** You are at [p] (forgetting the last fix, so the jump filter doesn't drop it). */
    private fun youAt(p: LatLng) {
        for (f in listOf("last", "lastReal")) try { LocationTracker::class.java.getDeclaredField(f).apply { isAccessible = true }.set(null, null) } catch (_: Exception) {}
        LocationTracker.onLocationChanged(Location("gps").apply { latitude = p.lat; longitude = p.lon; accuracy = 5f; time = System.currentTimeMillis() })
        log.append("you at $p: ${LocationTracker.position}\n")
    }

    /** 22:30 tonight in Athens, and a duty list around [at]. */
    private fun setUp(at: LatLng) {
        val c = Calendar.getInstance(TimeZone.getTimeZone("Europe/Athens")).apply {
            set(2026, Calendar.NOVEMBER, 20, 22, 30, 0); set(Calendar.MILLISECOND, 0)
        }
        val now = c.timeInMillis
        fun at(day: Int, h: Int) = Calendar.getInstance(TimeZone.getTimeZone("Europe/Athens")).apply {
            set(2026, Calendar.NOVEMBER, 20 + day, h, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis / 1000
        val a = Geo.offset(at, 40f, 650.0); val b = Geo.offset(at, 200f, 1400.0); val far = Geo.offset(at, 90f, 30_000.0)
        val tomorrow = Geo.offset(at, 300f, 500.0)
        val json = """{"updated":${now / 1000 - 5400},"d":[
          {"n":"ΠΑΠΑΔΟΠΟΥΛΟΥ ΕΛΕΝΗ","a":"ΥΜΗΤΤΟΥ 112","r":"ΠΑΓΚΡΑΤΙ","t":"2107010101","la":${a.lat},"lo":${a.lon},"s":[[${at(0, 21)},${at(1, 8)}]],"src":"fsa"},
          {"n":"ΚΩΣΤΟΠΟΥΛΟΣ ΙΩΑΝΝΗΣ","a":"ΚΑΡΠΑΘΟΥ 3","r":"ΒΥΡΩΝΑΣ","t":"2107020202","la":${b.lat},"lo":${b.lon},"s":[[${at(0, 8)},${at(0, 23)}]],"src":"fsa"},
          {"n":"ΜΑΚΡΙΑ ΑΝΝΑ","a":"ΜΑΚΡΙΑ 1","r":"ΜΑΡΑΘΩΝΑΣ","t":"","la":${far.lat},"lo":${far.lon},"s":[[${at(0, 21)},${at(1, 8)}]],"src":"fsa"},
          {"n":"ΑΥΡΙΑΝΟ ΦΑΡΜΑΚΕΙΟ","a":"ΑΥΡΙΟΥ 5","r":"ΠΑΓΚΡΑΤΙ","t":"","la":${tomorrow.lat},"lo":${tomorrow.lon},"s":[[${at(1, 21)},${at(2, 8)}]],"src":"fsa"}]}"""
        Duties.loadForTest(json)
        Duties.testNow = now
        log.append("duties: ${Duties.all.size}, on duty near: ${Duties.onDutyNear(at).map { it.name }}\n")
        assertTrue("two on duty near", Duties.onDutyNear(at, 3000.0).size == 2)
        assertTrue("closed hours at 22:30", Duties.usuallyClosed())
        assertTrue("search words", Duties.isPharmacyQuery("φαρμ") && Duties.isPharmacyQuery("Pharmacy") && Duties.isPharmacyQuery("εφημερεύοντα") && !Duties.isPharmacyQuery("φαλ"))
    }

    @Test
    fun phone() {
        val data = File(System.getProperty("previewData") ?: "../previewdata")
        data.listFiles()?.filter { it.name.endsWith(".pbf") }?.forEach { f ->
            val (x, y) = f.nameWithoutExtension.split("_").map { it.toInt() }
            VectorSource.put(14, x, y, VectorTile(f.readBytes()))
        }
        val start = Osrm.parseAll(File(data, "route.json").readText())[0].points[0]
        setUp(start)
        RuntimeEnvironment.getApplication().getSharedPreferences("gamemap", 0).edit()
            .putLong("lastUpdateCheck", System.currentTimeMillis()).putInt("levelCelebrated", 50).putBoolean("seenWhatsNew115", true).apply()
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = activity.window.decorView
        val looper = shadowOf(Looper.getMainLooper())
        MapState.setLightMode(MapState.LightMode.DARK); MapState.setTheme(Theme.NEON_NOIR)
        youAt(start)
        MapState.zoomBy(15.0 - MapState.targetZoom)
        fun shot(name: String) {
            val tmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            repeat(12) { looper.idleFor(java.time.Duration.ofMillis(60)); root.draw(Canvas(tmp)); Thread.sleep(20) }
            File(out, "$name.png").outputStream().use { tmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        fun texts(v: View = root, acc: MutableList<String> = mutableListOf()): List<String> {
            if (v.visibility != View.VISIBLE) return acc
            if (v is TextView) acc += v.text.toString()
            if (v is ViewGroup) for (i in 0 until v.childCount) texts(v.getChildAt(i), acc)
            return acc
        }
        activity.refreshForTest()
        shot("pharm_51a_map")
        val shownDuties = activity.poisShownForTest().filter { it.duty != null }.map { it.name }
        log.append("51a map: duty pins $shownDuties; bar: ${activity.dutyCardForTest()?.let { texts(it) }}\n")
        assertTrue("the evening bar shows", activity.dutyCardForTest() != null && texts(activity.dutyCardForTest()!!).any { it.contains("2 pharmacies on duty") })
        assertTrue("on-duty pins on the map", shownDuties.isNotEmpty())

        activity.openSheetForTest("more")
        shot("pharm_51e_menu")
        log.append("51e menu: ${texts().filter { it.contains("Pharmacy") || it.contains("on duty") }}\n")
        assertTrue("menu tile with the count", texts().any { it == "Pharmacy" } && texts().any { it.contains("2 on duty") })
        ActivityMethod.of("closeSheet").apply { isAccessible = true }.invoke(activity)

        activity.showPharmaciesForTest()
        looper.idleFor(java.time.Duration.ofMillis(800))
        shot("pharm_51b_list")
        val t = texts()
        log.append("51b list: ${t.filter { it.isNotBlank() }.take(24)}\n")
        val iOn = t.indexOfFirst { it.contains("ON DUTY NOW") }
        val iA = t.indexOfFirst { it.contains("ΠΑΠΑΔΟΠΟΥΛΟΥ") }
        assertTrue("on duty first, nearest first", iOn in 0 until iA && t.indexOfFirst { it.contains("ΚΩΣΤΟΠΟΥΛΟΣ") } > iA)
        assertTrue("closing times", t.any { it.contains("🌙 till 08:00 tomorrow") } && t.any { it.contains("🌙 till 23:00") })
        assertTrue("source line", t.any { it.startsWith("Duty list: Pharmacists' Association of Attica") })
        assertTrue("not the far one", t.none { it.contains("ΜΑΚΡΙΑ") })

        // ➤ in the list: the card first (Go / Call), no route yet
        fun find(pred: (View) -> Boolean, v: View = root): View? {
            if (v.visibility != View.VISIBLE) return null
            if (pred(v)) return v
            if (v is ViewGroup) for (i in 0 until v.childCount) find(pred, v.getChildAt(i))?.let { return it }
            return null
        }
        find({ it is TextView && it.text == "➤" })!!.performClick()
        looper.idleFor(java.time.Duration.ofMillis(400))
        shot("pharm_list_go_card")
        log.append("after ➤: nav active ${NavEngine.active}, card: ${texts().filter { it.contains("On duty tonight") || it.contains("Call") }}\n")
        assertTrue("➤ shows the card, no route yet", !NavEngine.active && texts().any { it.contains("On duty tonight") })
        // tapping the duty box: all on duty now
        find({ it is TextView && it.text.startsWith("All on duty now") })!!.let { v -> (v.parent.parent as View).performClick() }
        looper.idleFor(java.time.Duration.ofMillis(400))
        log.append("after the duty box: ${texts().filter { it.contains("ON DUTY NOW") }}\n")
        assertTrue("duty box opens the list", texts().any { it.contains("ON DUTY NOW") })

        val showPlace = ActivityMethod.of("showPlace", Place::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
        showPlace.invoke(activity, Duties.place(Duties.onDutyNear(start)[0]), false)
        shot("pharm_51c_card")
        log.append("51c card: ${texts().filter { it.contains("duty") || it.contains("Call") || it.contains("Duty list") }}\n")
        assertTrue("card: on duty tonight with hours, Call", texts().any { it.contains("On duty tonight · 21:00 → 08:00") } && texts().any { it.contains("Call") })
        // a pharmacy that isn't on duty: closed, and the nearest one on duty
        showPlace.invoke(activity, Place("ΚΛΕΙΣΤΟ", "Pharmacy", Geo.offset(start, 0f, 300.0), "💊"), false)
        shot("pharm_51c_closed")
        log.append("51c closed: ${texts().filter { it.contains("Closed") || it.contains("On duty nearby") }}\n")
        assertTrue("closed + nearest on duty", texts().any { it.startsWith("Closed now · opens 08:00") } && texts().any { it.startsWith("On duty nearby: ΠΑΠΑΔΟΠΟΥΛΟΥ") })
        ActivityMethod.of("closePlace").apply { isAccessible = true }.invoke(activity)

        val search = MainActivity::class.java.getDeclaredField("search").apply { isAccessible = true }.get(activity) as EditText
        search.requestFocus(); search.setText("φαρμακ")
        looper.idleFor(java.time.Duration.ofMillis(1500))
        shot("pharm_51f_search")
        log.append("51f search: ${texts().filter { it.isNotBlank() }.take(14)}\n")
        assertTrue("search: on duty on top", texts().any { it.contains("ON DUTY NOW") } && texts().any { it.startsWith("All pharmacies nearby") })

        // Greek
        L.setMode("el")
        search.setText(""); search.clearFocus()
        activity.showPharmaciesForTest(); looper.idleFor(java.time.Duration.ofMillis(800))
        shot("pharm_51b_list_el")
        log.append("51b el: ${texts().filter { it.isNotBlank() }.take(12)}\n")
        assertTrue("Greek list", texts().any { it.contains("ΕΦΗΜΕΡΕΥΟΥΝ ΤΩΡΑ") } && texts().any { it.contains("έως 08:00 αύριο") })
        L.setMode("en")
    }

    @Test
    fun car() {
        val at = LatLng(37.9680, 23.7460)
        setUp(at)
        youAt(at)
        val ctx = TestCarContext.createCarContext(RuntimeEnvironment.getApplication())
        androidx.car.app.CarContext::class.java.getDeclaredMethod("updateHandshakeInfo", androidx.car.app.HandshakeInfo::class.java)
            .apply { isAccessible = true }.invoke(ctx, androidx.car.app.HandshakeInfo("com.google.android.projection.gearhead", 7))
        val screen = gr.gamemap.car.MapScreen(ctx)
        val surf = gr.gamemap.car.MapScreen::class.java.getDeclaredField("surface").apply { isAccessible = true }.get(screen)
        val rend = surf.javaClass.getDeclaredField("renderer").apply { isAccessible = true }.get(surf) as MapRenderer
        fun render(name: String, p: CarPanel) {
            val w = 1280; val h = 480
            val rr = MapRenderer(car = true).apply { carButtons = true; forceLite = false; carPanel = p }
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            repeat(2) { rr.draw(Canvas(bmp), w, h, Rect(0, 0, w, h), 1f, true); Thread.sleep(80) }
            File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        gr.gamemap.car.MapScreen::class.java.getDeclaredMethod("openMenuPanel").apply { isAccessible = true }.invoke(screen)
        var p = rend.carPanel!!
        log.append("51g car menu: ${p.rows.map { it.title + " / " + it.sub }}\n")
        assertTrue("car menu: Pharmacies with the count", p.rows.any { it.id == "pharmacy" && it.sub?.contains("2 on duty now") == true && it.moon })
        render("pharm_51g_carmenu", p)
        gr.gamemap.car.MapScreen::class.java.getDeclaredMethod("openPharmacyPanel").apply { isAccessible = true }.invoke(screen)
        p = rend.carPanel!!
        log.append("51d car panel: ${p.rows.map { it.title + " / " + it.sub + " / " + it.right }} · ${p.note}\n")
        assertTrue("car panel: on duty first", p.key == "pharmacy" && p.rows.size >= 2 && p.rows[0].moon && p.rows[0].sub!!.contains("till 08:00"))
        render("pharm_51d_carpanel", p)
    }
}
