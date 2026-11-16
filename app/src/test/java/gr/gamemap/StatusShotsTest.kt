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

/** Pictures for stage 3: You with your status, Do not disturb, and friends' dots (green / red). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class StatusShotsTest {
    @Test
    fun shots() {
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        val t0 = System.currentTimeMillis()
        RuntimeEnvironment.getApplication().getSharedPreferences("friends", Context.MODE_PRIVATE).edit()
            .putString("code", "VYTO-4821").putString("codeV", "2").putString("fid", "me0000000000")
            .putString("friends", JSONArray()
                .put(JSONObject().put("fid", "f1nikos").put("n", "Nikos").put("s", "c2VjcmV0MQ").put("since", t0))
                .put(JSONObject().put("fid", "f3maria").put("n", "Maria").put("s", "c2VjcmV0Mw").put("since", t0))
                .put(JSONObject().put("fid", "f2eleni").put("n", "Eleni").put("s", "c2VjcmV0Mg").put("since", t0)).toString())
            .apply()
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = activity.window.decorView
        val looper = shadowOf(Looper.getMainLooper())
        fun shot(name: String) {
            repeat(20) { looper.idleFor(java.time.Duration.ofMillis(60)) }
            val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888); root.draw(Canvas(bmp))
            File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        Convoy.myName = "Vyto"
        Friends.core.setOnline("f1nikos", t0); Friends.core.setOnline("f3maria", t0, dnd = true)
        MapState.setTheme(Theme.VIOLET_RAIN); MapState.setLightMode(MapState.LightMode.DARK)
        activity.openSheetForTest("you"); shot("stage3_you_online")
        Friends.setStatus(FriendCore.DND, t0 + 3_600_000)
        activity.refreshForTest(); activity.openSheetForTest("you"); shot("stage3_you_dnd")
        Friends.setStatus(FriendCore.INVISIBLE)
        activity.refreshForTest(); activity.openSheetForTest("you"); shot("stage3_you_invisible")
        Friends.setStatus(FriendCore.ONLINE)
        activity.openSheetForTest("friends"); shot("stage3_friends")
    }
}
