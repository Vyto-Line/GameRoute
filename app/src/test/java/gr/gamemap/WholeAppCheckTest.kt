package gr.gamemap

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.location.Location
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The whole-app check (row 28 / mockup 50): every phone screen in every map style, light and
 * dark, English and Greek, looked over for words cut off, things off the screen or on top of
 * each other, English left in Greek, and text too faint to read. Writes what it finds (and a
 * picture of each case) to wholecheck.txt; the car screens are drawn for a look by eye.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class WholeAppCheckTest {
    @Test
    fun everything() {
        val data = File(System.getProperty("previewData") ?: "../previewdata")
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        val pics = File(out, "check").apply { mkdirs() }
        data.listFiles()?.filter { it.name.endsWith(".pbf") }?.forEach { f ->
            val (x, y) = f.nameWithoutExtension.split("_").map { it.toInt() }
            VectorSource.put(14, x, y, VectorTile(f.readBytes()))
        }
        RuntimeEnvironment.getApplication().getSharedPreferences("gamemap", 0).edit()
            .putLong("lastUpdateCheck", System.currentTimeMillis()).putInt("levelCelebrated", 50).putBoolean("homeOpen", true).apply()
        val r = Osrm.parseAll(File(data, "route.json").readText())[0]
        val start = r.points[r.points.size / 3]
        Places.save("home", Place("Home", "Nikomachou 47", r.points.last()))
        Places.addRecent(Place("National Archaeological Museum", "Patision 44", r.points.last()))
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = activity.window.decorView
        val looper = shadowOf(Looper.getMainLooper())
        LocationTracker.onLocationChanged(Location("gps").apply { latitude = start.lat; longitude = start.lon; accuracy = 5f; time = System.currentTimeMillis() })
        Convoy.myName = "Vyto"

        val findings = LinkedHashMap<String, MutableList<String>>() // what → where
        var shots = 0
        fun draw(): Bitmap {
            repeat(4) { looper.idleFor(java.time.Duration.ofMillis(60)) }
            val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bmp)); return bmp
        }
        fun visibleTexts(v: View, acc: MutableList<TextView>) {
            if (v.visibility != View.VISIBLE || v.alpha < 0.05f) return
            if (v is TextView && v.text.isNotBlank()) acc += v
            if (v is ViewGroup) for (i in 0 until v.childCount) visibleTexts(v.getChildAt(i), acc)
        }
        fun clickables(v: View, acc: MutableList<View>) {
            if (v.visibility != View.VISIBLE || v.alpha < 0.05f) return
            if (v.hasOnClickListeners() && v.width > 0) acc += v
            if (v is ViewGroup) for (i in 0 until v.childCount) clickables(v.getChildAt(i), acc)
        }
        fun lum(c: Int): Double {
            fun ch(x: Int): Double { val s = x / 255.0; return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4) }
            return 0.2126 * ch(Color.red(c)) + 0.7152 * ch(Color.green(c)) + 0.0722 * ch(Color.blue(c))
        }
        fun contrast(a: Int, b: Int): Double { val la = lum(a); val lb = lum(b); return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05) }
        // English left in Greek: two English words in a row (names of styles, brands and places allowed)
        val allowed = Regex("(?i)GameRoute|OpenStreetMap|OpenFreeMap|TomTom|Android Auto|Google|Overture|Natural Earth|Geofabrik|Photon|Nominatim|OSRM|ntfy|EMQX|HiveMQ|ZXing|" +
            "Free Roam|Might City|Midnight Run|Blockworld|Realm Map|Fire Tower|8-Bit|Catch 'Em Trail|Violet Rain|Rally Roadbook|Blueprint|Nautical Chart|Arctic|High Contrast|" +
            "National Archaeological Museum|Patision|Nikomachou|Plateia|Omonoias|Panagi Tsaldari|Aiolou|Vyto|Glyfada|Marina|Shizuku|UnknownAuto|fuel-prices|Rain Runner|Roads of Greece")
        val english = Regex("[A-Za-z]{3,}[ ,.'’-]+[A-Za-z]{3,}")

        fun check(screen: String, theme: Theme, dark: Boolean, greek: Boolean) {
            val bmp = draw()
            val where = "${theme.title} · ${if (dark) "dark" else "light"} · ${if (greek) "Greek" else "English"}"
            val texts = ArrayList<TextView>(); visibleTexts(root, texts)
            val local = ArrayList<String>()
            val rr = Rect()
            for (tv in texts) {
                if (!tv.getGlobalVisibleRect(rr) || rr.width() < 4 || rr.height() < 4) continue
                val txt = tv.text.toString().replace('\n', ' ').trim()
                val lay = tv.layout
                // cut off: a short label with "…", or a single line wider than its box
                if (lay != null) {
                    val last = lay.lineCount - 1
                    if (last >= 0 && lay.getEllipsisCount(last) > 0 && txt.length <= 24) local += "cut off: \"$txt\""
                    if (tv.maxLines == 1 && tv.ellipsize == null && lay.getLineWidth(0) > tv.width - tv.totalPaddingLeft - tv.totalPaddingRight + 2) local += "cut off (no …): \"$txt\""
                }
                // off the screen
                val loc = IntArray(2); tv.getLocationOnScreen(loc)
                if (loc[0] < -2 || loc[0] + tv.width > root.width + 2) local += "past the screen edge: \"${txt.take(30)}\""
                // English left in Greek
                if (greek) {
                    val stripped = allowed.replace(txt, "")
                    if (english.containsMatchIn(stripped)) local += "English in Greek: \"${txt.take(40)}\""
                }
                // too faint: the text's colour against what's behind it (the most common colour in its box)
                val counts = HashMap<Int, Int>()
                val x0 = rr.left.coerceIn(0, bmp.width - 1); val x1 = rr.right.coerceIn(0, bmp.width - 1)
                val y0 = rr.top.coerceIn(0, bmp.height - 1); val y1 = rr.bottom.coerceIn(0, bmp.height - 1)
                var yy = y0; while (yy < y1) { var xx = x0; while (xx < x1) { val px = bmp.getPixel(xx, yy) and 0xF0F0F0.inv().inv(); counts[px or 0xFF000000.toInt()] = (counts[px or 0xFF000000.toInt()] ?: 0) + 1; xx += 3 }; yy += 3 }
                val bg = counts.maxByOrNull { it.value }?.key
                val fg = tv.currentTextColor
                if (bg != null && Color.alpha(fg) > 60 && txt.any { it.isLetterOrDigit() }) {
                    val a = Color.alpha(fg) / 255.0
                    val mixed = Color.rgb((Color.red(fg) * a + Color.red(bg) * (1 - a)).toInt(), (Color.green(fg) * a + Color.green(bg) * (1 - a)).toInt(), (Color.blue(fg) * a + Color.blue(bg) * (1 - a)).toInt())
                    val cr = contrast(mixed, bg)
                    if (cr < 2.6) local += "faint (${"%.1f".format(cr)}:1): \"${txt.take(30)}\""
                }
            }
            // buttons on top of each other
            val cl = ArrayList<View>(); clickables(root, cl)
            val rects = cl.mapNotNull { v -> Rect().takeIf { v.getGlobalVisibleRect(it) }?.let { v to it } }
            for (i in rects.indices) for (j in i + 1 until rects.size) {
                val (a, ra) = rects[i]; val (b, rb) = rects[j]
                if (isAncestor(a, b) || isAncestor(b, a)) continue
                val ix = Rect(); if (!ix.setIntersect(ra, rb)) continue
                val small = minOf(ra.width() * ra.height(), rb.width() * rb.height())
                if (small > 0 && ix.width() * ix.height() > small * 0.25) local += "buttons overlap: ${label(a)} / ${label(b)}"
            }
            for (f in local.distinct()) {
                val k = "$screen | $f"
                val list = findings.getOrPut(k) { ArrayList() }
                if (list.isEmpty() && shots < 80) { shots++; File(pics, "${shots}_${screen.replace(' ', '_')}.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 80, it) } ; list += "picture ${shots}" }
                list += where
            }
        }

        val screens = listOf<Pair<String, () -> Unit>>(
            "Home" to { activity.refreshForTest() },
            "Settings" to { activity.openSheetForTest("settings") },
            "Settings pages" to { activity.openSheetForTest("settings") },
            "You" to { activity.openSheetForTest("you") },
            "Progress" to { activity.openSheetForTest("progress") },
            "Garage" to { activity.openSheetForTest("garage") },
            "Friends" to { activity.openSheetForTest("friends") },
            "Convoy" to { activity.openSheetForTest("convoy") },
            "Report" to { activity.openSheetForTest("report") },
            "Map style" to { activity.openSheetForTest("style") },
            "Trips" to { activity.openSheetForTest("trips") },
            "Offline maps" to { activity.openSheetForTest("offline") },
        )
        val themes = Theme.values().toList()
        var n = 0
        for (greek in listOf(false, true)) for (dark in listOf(false, true)) for (t in themes) {
            L.setMode(if (greek) "el" else "en")
            MapState.setLightMode(if (dark) MapState.LightMode.DARK else MapState.LightMode.LIGHT); MapState.setTheme(t)
            for ((name, open) in screens) {
                repeat(2) { activity.onBackPressedDispatcher.onBackPressed(); looper.idle() }
                open(); looper.idle()
                if (name == "Settings pages") for (k in activity.settingsKeysForTest()) { activity.settingsPageForTest(k); looper.idle(); check("Settings › $k", t, dark, greek); n++ }
                else { check(name, t, dark, greek); n++ }
            }
            repeat(2) { activity.onBackPressedDispatcher.onBackPressed(); looper.idle() }
            // driving
            NavEngine.debugDrive(Place("National Archaeological Museum", "Patision 44", r.points.last()), r, 60)
            LocationTracker.onLocationChanged(Location("gps").apply { latitude = start.lat; longitude = start.lon; speed = 12f; bearing = Geo.bearing(start, r.points[r.points.size / 3 + 1]); accuracy = 5f; time = System.currentTimeMillis() })
            looper.idle(); check("Driving", t, dark, greek); n++
            NavEngine.stop(); NavEngine.dismissTrip(); looper.idle()
        }
        L.setMode("en")
        // the car: drawn for a look by eye (one picture per style, light and dark)
        val car = MapRenderer(car = true).apply { carButtons = true }
        for (dark in listOf(false, true)) {
            MapState.setLightMode(if (dark) MapState.LightMode.DARK else MapState.LightMode.LIGHT)
            for (t in themes) {
                MapState.setTheme(t)
                val cb = Bitmap.createBitmap(1280, 480, Bitmap.Config.ARGB_8888)
                repeat(2) { car.draw(Canvas(cb), 1280, 480, Rect(0, 0, 1280, 480), 1.0f, true) }
                File(pics, "car_${t.name.lowercase()}_${if (dark) "dark" else "light"}.png").outputStream().use { cb.compress(Bitmap.CompressFormat.PNG, 80, it) }
            }
        }
        val sb = StringBuilder("Checked $n screens (12 screens + settings pages + driving, 14 styles, light/dark, English/Greek)\n\n")
        for ((k, v) in findings.entries.sortedByDescending { it.value.size }) {
            val pic = v.firstOrNull { it.startsWith("picture") }
            val wh = v.filter { !it.startsWith("picture") }
            sb.append("${wh.size}× $k   [$pic]   e.g. ${wh.take(3).joinToString(" / ")}\n")
        }
        File(out, "wholecheck.txt").writeText(sb.toString())
    }

    private fun isAncestor(a: View, b: View): Boolean {
        var p: android.view.ViewParent? = b.parent
        while (p != null) { if (p === a) return true; p = p.parent }
        return false
    }
    private fun label(v: View): String {
        (v as? TextView)?.let { return "\"" + it.text.toString().take(20) + "\"" }
        v.contentDescription?.let { return "\"" + it.toString().take(20) + "\"" }
        if (v is ViewGroup) for (i in 0 until v.childCount) { val c = v.getChildAt(i); if (c is TextView) return "\"" + c.text.toString().take(20) + "\"" }
        return v.javaClass.simpleName
    }
}
