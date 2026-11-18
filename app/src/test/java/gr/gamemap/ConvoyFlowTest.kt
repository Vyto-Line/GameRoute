package gr.gamemap

import android.graphics.Bitmap
import android.graphics.Canvas
import android.location.Location
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The convoy one question at a time (43a-e), the convoy button and panel while driving (44a-b),
 * the race standings after I finish (44c), and the faint "?" when location is off.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class ConvoyFlowTest {
    @Test
    fun flow() {
        val data = File(System.getProperty("previewData") ?: "../previewdata")
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        data.listFiles()?.filter { it.name.endsWith(".pbf") }?.forEach { f ->
            val (x, y) = f.nameWithoutExtension.split("_").map { it.toInt() }
            VectorSource.put(14, x, y, VectorTile(f.readBytes()))
        }
        val r = Osrm.parseAll(File(data, "route.json").readText())[0]
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = activity.window.decorView
        val looper = shadowOf(Looper.getMainLooper())
        fun shot(name: String) {
            val tmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            repeat(20) { looper.idleFor(java.time.Duration.ofMillis(60)); root.draw(Canvas(tmp)); Thread.sleep(20) }
            val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bmp))
            File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        fun texts(v: View = root, acc: MutableList<String> = mutableListOf()): List<String> {
            if (v.visibility != View.VISIBLE) return acc
            if (v is TextView) acc += v.text.toString()
            if (v is ViewGroup) for (i in 0 until v.childCount) texts(v.getChildAt(i), acc)
            return acc
        }
        fun click(t: String, last: Boolean = false) {
            val found = mutableListOf<View>()
            fun find(v: View) {
                if (v.visibility != View.VISIBLE) return
                if (v is TextView && v.text.toString() == t) found += v
                else if (v.contentDescription?.toString()?.startsWith(t) == true && v.hasOnClickListeners()) found += v
                if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))
            }
            find(root)
            val v = (if (last) found.lastOrNull() else found.firstOrNull()) ?: error("no \"$t\" in ${texts()}")
            var c: View? = v
            while (c != null && !c.hasOnClickListeners()) c = c.parent as? View
            (c ?: v).performClick(); looper.idle()
        }
        val log = StringBuilder()
        MapState.setLightMode(MapState.LightMode.DARK); MapState.setTheme(Theme.NEON_NOIR)
        val start = r.points[0]
        LocationTracker.onLocationChanged(Location("gps").apply { latitude = start.lat; longitude = start.lon; accuracy = 5f; time = System.currentTimeMillis() })
        MapState.zoomBy(16.0 - MapState.targetZoom)
        Convoy.myName = "Vyto"
        val prefs = activity.getSharedPreferences("gamemap", android.content.Context.MODE_PRIVATE)

        // 43a: not in a convoy
        activity.openSheetForTest("convoy"); shot("flow_43a_start")
        assertTrue(texts().any { it.contains("Start a convoy") })

        // 43b: my convoy, no place yet: step 1
        activity.onBackPressedDispatcher.onBackPressed()
        Convoy.debugStart(Place("x", "", start), "k7p2qx"); Convoy.debugSet(null, true)
        prefs.edit().putString("convoyModeFor", "").apply()
        activity.openSheetForTest("convoy"); shot("flow_43b_where")
        log.append("43b: ${texts().filter { it.contains("together") || it.contains("Search") }}\n")
        assertTrue(texts().any { it == "Where are you going together?" })

        // a place: step 2 (how)
        val dest = Place("National Archaeological Museum", "Patision 44", r.points.last())
        Convoy.debugSet(dest, true); activity.onBackPressedDispatcher.onBackPressed(); activity.openSheetForTest("convoy"); shot("flow_43c_how")
        assertTrue(texts().any { it == "How do you want to drive?" })
        click("Drive together"); shot("flow_43c_how_along_picked")
        log.append("picked along, not confirmed: meAlong=${Convoy.meAlong} (want false)\n")
        assertTrue(!Convoy.meAlong)
        click("Race")
        Convoy.debugMember("Nikos", Geo.offset(start, 90f, 25.0), 0, 0, -1.0, -1.0, "ready")
        Convoy.debugMember("Maria", Geo.offset(start, 300f, 30.0), 0, 0, -1.0, -1.0, "ready")
        Convoy.members.values.first { it.name == "Maria" }.along = true
        Convoy.members.values.first { it.name == "Nikos" }.ready = true
        click("Next: invite people ›")
        log.append("confirmed race: meAlong=${Convoy.meAlong} meSharing=${Convoy.meSharing}\n")
        // 43d: the lobby
        activity.onBackPressedDispatcher.onBackPressed(); activity.openSheetForTest("convoy"); shot("flow_43d_lobby")
        log.append("43d: ${texts().filter { it.startsWith("You:") || it.startsWith("Waiting") || it.contains("ready") }}\n")
        assertTrue(texts().any { it.startsWith("You: ") })
        click("Change", last = true); shot("flow_43d_change")
        assertTrue(texts().any { it == "How do you want to drive?" })
        click("Next: invite people ›")
        activity.onBackPressedDispatcher.onBackPressed()

        // 43e: joining someone else's convoy from an invite (they race: Race is picked for me)
        Convoy.debugStart(dest, "q4m8zt"); Convoy.debugSet(dest, false)
        Convoy.debugMember("Nikos", Geo.offset(start, 90f, 25.0), 0, 0, -1.0, -1.0, "ready")
        prefs.edit().putString("convoyInviteMode", "q4m8zt|Nikos|race").putString("convoyModeFor", "").apply()
        activity.openSheetForTest("convoy"); shot("flow_43e_join")
        log.append("43e: ${texts().filter { it.contains("convoy") || it.contains("race") }}\n")
        assertTrue(texts().any { it == "Nikos's convoy" })
        click("Join the race ›")
        activity.onBackPressedDispatcher.onBackPressed()

        // 44a/b: driving
        Convoy.debugSet(dest, true)
        NavEngine.debugDrive(dest, r, 60)
        val n = r.points.size
        fun at(i: Int) = r.points[i.coerceIn(0, n - 1)]
        val t = System.currentTimeMillis()
        Convoy.members.clear()
        Convoy.debugMember("Nikos", at(n * 2 / 3), 92, t + 9 * 60_000L, r.length * 0.3, r.length, "driving")
        Convoy.debugMember("Maria", at(n / 4), 85, t + 11 * 60_000L, r.length * 0.7, r.length, "driving")
        Convoy.members.values.first { it.name == "Maria" }.along = true
        val p = at(n / 3); val p2 = at(n / 3 + 1)
        LocationTracker.onLocationChanged(Location("gps").apply { latitude = p.lat; longitude = p.lon; speed = 24f; bearing = Geo.bearing(p, p2); accuracy = 5f; time = t })
        MapState.zoomBy(15.5 - MapState.targetZoom)
        shot("flow_44a_button")
        activity.openSheetForTest("convoy"); shot("flow_44b_panel")
        log.append("44b: ${texts().filter { it.contains("Race to") || it.contains("See all") || it.contains("km/h") }}\n")
        assertTrue(texts().any { it.contains("Race to") })
        activity.onBackPressedDispatcher.onBackPressed()

        // 44c: I finish 2nd, Giorgos is still driving
        Convoy.members.values.first { it.name == "Nikos" }.apply { state = "arrived"; arrivedAt = t - 95_000; kmh = 0 }
        Convoy.debugMember("Giorgos", at(n / 2), 70, t + 4 * 60_000L, r.length * 0.4, r.length, "driving")
        NavEngine.debugArrive(t)
        log.append("my place: ${Convoy.myPlace()} race going on: ${NavEngine.raceGoingOn()}\n")
        assertEquals(2, Convoy.myPlace()?.first)
        assertTrue(NavEngine.raceGoingOn())
        activity.openSheetForTest("convoy"); shot("flow_44c_standings")
        log.append("44c: ${texts().filter { it.contains("finished") || it.contains("LIVE") || it.contains("Winner") }}\n")
        assertTrue(texts().any { it == "You finished 2nd" })
        activity.onBackPressedDispatcher.onBackPressed()

        // the faint "?" when location is off
        NavEngine.stop(); Convoy.leave()
        LocationStatus.setForTest(LocationStatus.State.OFF)
        MapState.zoomBy(16.0 - MapState.targetZoom)
        shot("flow_45_location_off")
        LocationStatus.setForTest(LocationStatus.State.OK)
        File(out, "convoyflow.txt").writeText(log.toString())
    }
}
