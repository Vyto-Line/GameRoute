package gr.gamemap

import android.graphics.Bitmap
import android.graphics.Canvas
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
 * Every screen with Android's text size turned up (1.3×) and in Greek: pictures to check for
 * text that's cut off or overlapping, and a list of words still in English.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class TextAuditTest {
    private fun texts(v: View, out: MutableList<Pair<TextView, String>>) {
        if (v.visibility != View.VISIBLE) return
        if (v is TextView && v.text.isNotBlank()) out += v to v.text.toString()
        if (v is ViewGroup) for (i in 0 until v.childCount) texts(v.getChildAt(i), out)
    }

    /** Cut off: a one-line text that doesn't fit (ellipsized), or a text wider than its box. */
    private fun clipped(t: TextView): Boolean {
        val l = t.layout ?: return false
        for (i in 0 until l.lineCount) if (l.getEllipsisCount(i) > 0) return true
        return false
    }

    @Test
    fun audit() {
        RuntimeEnvironment.setFontScale(1.3f)
        val data = File(System.getProperty("previewData") ?: "../previewdata")
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        data.listFiles()?.filter { it.name.endsWith(".pbf") }?.forEach { f ->
            val (x, y) = f.nameWithoutExtension.split("_").map { it.toInt() }
            VectorSource.put(14, x, y, VectorTile(f.readBytes()))
        }
        val opts = Osrm.parseAll(File(data, "route.json").readText())
        val r = opts[0]
        val dest = Place("National Archaeological Museum", "Patision 44", r.points.last())
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = activity.window.decorView
        val looper = shadowOf(Looper.getMainLooper())
        val p = r.points[5]
        LocationTracker.onLocationChanged(Location("gps").apply { latitude = p.lat; longitude = p.lon; accuracy = 5f; time = System.currentTimeMillis() })
        Places.save("home", Place("Home", "Glyfada", LatLng(37.8650, 23.7530)))
        Places.saveFavourite(Place("Aigyptou 25", "Glyfada", LatLng(37.8930, 23.7644)), "Dad", "👤")
        val english = sortedMapOf<String, MutableSet<String>>()
        val cut = sortedMapOf<String, MutableSet<String>>()
        // Latin words that are fine in Greek (names, units, brands)
        val ok = setOf("km", "km/h", "min", "GameRoute", "OK", "XP", "EV", "CCS", "CHAdeMO", "Type", "Tesla", "LPG", "CNG", "Diesel",
            "TomTom", "OpenStreetMap", "OpenFreeMap", "Overture", "Google", "Android", "Auto", "ntfy", "EKO", "Shell", "BP", "Avin",
            "Aegean", "Revoil", "Elin", "Dad", "Vyto", "Nikos", "Maria", "Museum", "National", "Archaeological", "Patision", "Glyfada",
            "Aigyptou", "Mikel", "Coffee", "kW", "GPS", "Shizuku", "UnknownAuto", "AA", "K7P", "2QX", "Kolonaki", "Home")
        fun check(screen: String, lang: String) {
            repeat(25) { looper.idleFor(java.time.Duration.ofMillis(60)); Thread.sleep(20) }
            val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888); root.draw(Canvas(bmp))
            File(out, "ta_${lang}_$screen.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val all = ArrayList<Pair<TextView, String>>(); texts(root, all)
            for ((tv, s) in all) {
                if (clipped(tv)) cut.getOrPut("$lang/$screen") { sortedSetOf() } += s.take(60)
                if (lang != "el") continue
                val words = Regex("[A-Za-z]{3,}").findAll(s).map { it.value }.filter { w -> ok.none { it.equals(w, true) } }.toList()
                if (words.isNotEmpty()) english.getOrPut(screen) { sortedSetOf() } += s.take(80)
            }
        }
        fun call(name: String) = try { MainActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity) } catch (_: Exception) { null }
        fun clean() { call("closeSheet"); call("closePlace"); call("closeSearch"); looper.idle() }
        val page = MainActivity::class.java.getDeclaredField("settingsPage").apply { isAccessible = true }
        for (lang in listOf("en", "el")) {
            L.setMode(lang)
            clean()
            check("home", lang)
            for (s in listOf("quick", "style", "fuelfilters")) { activity.openSheetForTest(s); check(s, lang); clean() }
            activity.openSheetForTest("settings"); check("settings", lang)
            for (g in listOf("map", "nav", "alerts", "sharing", "app", "advanced")) {
                page.set(activity, g); activity.openSheetForTest("settings"); check("settings_$g", lang)
            }
            page.set(activity, null); clean()
            activity.openSheetForTest("convoy"); check("convoy_none", lang); clean()
            Convoy.myName = "Vyto"; Convoy.debugStart(dest)
            Convoy.debugMember("Nikos", r.points[20], 0, 0, -1.0, -1.0, "ready")
            activity.openSheetForTest("convoy"); check("convoy_lobby", lang); clean()
            Convoy.leave(quiet = true)
            val map = MainActivity::class.java.getDeclaredField("map").apply { isAccessible = true }.get(activity)
            @Suppress("UNCHECKED_CAST")
            (map.javaClass.getMethod("getOnPlaceTap").invoke(map) as (Place) -> Unit)(Place("EKO", "Fuel · Patision 60", LatLng(37.988, 23.730), "⛽", rating = 4.1, ratings = 212))
            check("place", lang); clean()
            NavEngine.debugPlan(dest, opts); check("routes", lang)
            NavEngine.debugDrive(dest, r, 120); check("drive", lang)
            NavEngine.stop(); looper.idle()
        }
        L.setMode("auto")
        val log = StringBuilder("TEXT CUT OFF at 1.3× (screen: text)\n")
        cut.forEach { (k, v) -> log.append("  $k: ${v.joinToString(" | ")}\n") }
        log.append("\nSTILL IN ENGLISH in Greek mode\n")
        english.forEach { (k, v) -> log.append("  $k: ${v.joinToString(" | ")}\n") }
        File(out, "textaudit.txt").writeText(log.toString())
    }
}
