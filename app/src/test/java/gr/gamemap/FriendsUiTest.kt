package gr.gamemap

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The friends screens with some friends, a request and invites (English and Greek). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class FriendsUiTest {
    @Test
    fun screens() {
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        val now = System.currentTimeMillis()
        // a phone that already has friends (as if they'd been added over the network)
        RuntimeEnvironment.getApplication().getSharedPreferences("friends", Context.MODE_PRIVATE).edit()
            .putString("code", "KX7P-4821").putString("fid", "me0000000000")
            .putString("friends", JSONArray()
                .put(JSONObject().put("fid", "f1nikos").put("n", "Nikos").put("s", "c2VjcmV0MQ").put("since", now - 86_400_000L * 3))
                .put(JSONObject().put("fid", "f2giorgos").put("n", "Giorgos").put("s", "c2VjcmV0Mg").put("since", now))
                .put(JSONObject().put("fid", "f3maria").put("n", "Maria").put("s", "c2VjcmV0Mw").put("since", now))
                .put(JSONObject().put("fid", "f4kostas").put("n", "Kostas").put("s", "c2VjcmV0NA").put("since", now)).toString())
            .putString("requests", JSONArray().put(JSONObject().put("fid", "r1eleni").put("n", "Eleni").put("s", "c2VjcmV0NQ").put("at", now).put("nonce", "n1")).toString())
            .putString("invites", JSONArray().put(JSONObject().put("iid", "i1").put("fid", "f3maria").put("n", "Maria").put("cv", "glyf22").put("d", "Glyfada Marina").put("at", now - 120_000).put("k", 3)).toString())
            .putString("recent", JSONArray().put(JSONObject().put("fid", "p1petros").put("n", "Petros").put("c", "PTRS-2290").put("d", "Sounio").put("at", now)).toString())
            .apply()
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = activity.window.decorView
        val looper = shadowOf(Looper.getMainLooper())
        Friends.core.setOnline("f1nikos", now); Friends.core.setOnline("f2giorgos", now)
        fun shot(name: String) {
            repeat(20) { looper.idleFor(java.time.Duration.ofMillis(60)); Thread.sleep(10) }
            val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888); root.draw(Canvas(bmp))
            File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        fun call(name: String) = try { MainActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity) } catch (_: Exception) { null }
        MapState.setTheme(Theme.NEON_NOIR); MapState.setLightMode(MapState.LightMode.DARK)
        val page = MainActivity::class.java.getDeclaredField("settingsPage").apply { isAccessible = true }
        for (lang in listOf("en", "el")) {
            L.setMode(lang)
            call("closeSheet"); shot("fr_${lang}_home")
            activity.openSheetForTest("friends"); shot("fr_${lang}_list"); call("closeSheet")
            activity.openSheetForTest("addfriend"); shot("fr_${lang}_add"); call("closeSheet")
            page.set(activity, "sharing"); activity.openSheetForTest("settings"); shot("fr_${lang}_settings"); page.set(activity, null); call("closeSheet")
        }
        L.setMode("en")
        // in a convoy: add buttons next to people, the invite statuses in Friends
        val r = Osrm.parseAll(File(File(System.getProperty("previewData") ?: "../previewdata"), "route.json").readText())[0]
        Convoy.myName = "Vyto"; Convoy.debugStart(Place("Acropolis Museum", "", r.points.last()))
        Convoy.debugMember("Petros", r.points[20], 0, 0, -1.0, -1.0, "ready")
        Convoy.debugMember("Nikos", r.points[30], 0, 0, -1.0, -1.0, "ready")
        Convoy.members.values.first { it.name == "Petros" }.apply { fid = "p1petros"; fc = "PTRS-2290" }
        Convoy.members.values.first { it.name == "Nikos" }.apply { fid = "f1nikos"; fc = "NIKO-7731" }
        val sent = JSONArray()
            .put(JSONObject().put("fid", "f1nikos").put("iid", "a").put("cv", Convoy.code).put("at", now).put("st", "joined"))
            .put(JSONObject().put("fid", "f2giorgos").put("iid", "b").put("cv", Convoy.code).put("at", now).put("st", "seen"))
            .put(JSONObject().put("fid", "f4kostas").put("iid", "c").put("cv", Convoy.code).put("at", now).put("st", "sent"))
        RuntimeEnvironment.getApplication().getSharedPreferences("friends", Context.MODE_PRIVATE).edit().putString("sent", sent.toString()).commit()
        activity.openSheetForTest("convoy"); shot("fr_en_convoy"); call("closeSheet")
        activity.openSheetForTest("friends"); shot("fr_en_list_convoy"); call("closeSheet")
        Convoy.leave(quiet = true)
    }
}
