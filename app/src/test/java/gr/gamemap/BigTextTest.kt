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

/** Big phone text (font scale 1.3): the chips and the You screen still fit. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h866dp-xxhdpi")
class BigTextTest {
    @Test
    fun screens() {
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        val now = System.currentTimeMillis()
        RuntimeEnvironment.setFontScale(1.3f)
        // a phone that already has friends (as if they'd been added over the network)
        RuntimeEnvironment.getApplication().getSharedPreferences("friends", Context.MODE_PRIVATE).edit()
            .putString("code", "VYTO-4821").putString("codeV", "2").putString("fid", "me0000000000")
            .putString("friends", JSONArray()
                .put(JSONObject().put("fid", "f1nikos").put("n", "Nikos").put("s", "c2VjcmV0MQ").put("since", now - 86_400_000L * 3))
                .put(JSONObject().put("fid", "f2giorgos").put("n", "Giorgos").put("s", "c2VjcmV0Mg").put("since", now))
                .put(JSONObject().put("fid", "f3maria").put("n", "Maria").put("s", "c2VjcmV0Mw").put("since", now))
                .put(JSONObject().put("fid", "f4kostas").put("n", "Kostas").put("s", "c2VjcmV0NA").put("since", now)).toString())
            .putString("requests", JSONArray().put(JSONObject().put("fid", "r1eleni").put("n", "Eleni").put("s", "c2VjcmV0NQ").put("at", now).put("nonce", "n1")).toString())
            .putString("invites", JSONArray().put(JSONObject().put("iid", "i1").put("fid", "f3maria").put("n", "Maria").put("cv", "glyf22").put("d", "Glyfada Marina").put("at", now - 120_000).put("k", 3)).toString())
            .putString("pending", JSONArray()
                .put(JSONObject().put("nonce", "p1").put("code", "ELENI-5512").put("n", "").put("at", now).put("st", "waiting"))
                .put(JSONObject().put("nonce", "p2").put("code", "KOSTAS-1180").put("n", "Kostas").put("at", now).put("st", "accepted")).toString())
            .putString("recent", JSONArray().put(JSONObject().put("fid", "p1petros").put("n", "Petros").put("c", "PETROS-2290").put("d", "Sounio").put("at", now)).toString())
            .apply()
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = activity.window.decorView
        val looper = shadowOf(Looper.getMainLooper())
        Friends.core.setOnline("f1nikos", now)
        fun shot(name: String) {
            repeat(20) { looper.idleFor(java.time.Duration.ofMillis(60)); Thread.sleep(10) }
            val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888); root.draw(Canvas(bmp))
            File(out, "big_$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        Convoy.myName = "Vyto"
        Places.save("home", Place("Home", "Ilioupoli", LatLng(37.931, 23.756)))
        for ((th, dark) in listOf(Theme.VIOLET_RAIN to true, Theme.OPEN_WORLD to true)) {
            MapState.setTheme(th); MapState.setLightMode(if (dark) MapState.LightMode.DARK else MapState.LightMode.LIGHT)
            activity.refreshForTest(); shot("home_${th.name.lowercase()}")
            activity.openSheetForTest("you"); shot("you_${th.name.lowercase()}")
            if (th == Theme.VIOLET_RAIN) {
                // opened from You: a back button to You; Settings › a page: back to Settings
                ActivityMethod.of("openSheet", String::class.java).apply { isAccessible = true }.invoke(activity, "progress"); shot("progress_back")
                ActivityMethod.of("sheetBack").apply { isAccessible = true }.invoke(activity); shot("back_to_you")
                activity.showSettings(); shot("settings_center")
            }
            ActivityMethod.of("closeSheet").apply { isAccessible = true }.invoke(activity)
        }
    }
}
