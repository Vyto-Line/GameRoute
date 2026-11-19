package gr.gamemap

import android.graphics.Bitmap
import android.graphics.Canvas
import android.location.Location
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Saved & recent tucked into a slim handle (46b): swipe up opens it, swipe down / picking a place tucks it away. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class HomeHandleTest {
    @Test
    fun handle() {
        val data = File(System.getProperty("previewData") ?: "../previewdata")
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        data.listFiles()?.filter { it.name.endsWith(".pbf") }?.forEach { f ->
            val (x, y) = f.nameWithoutExtension.split("_").map { it.toInt() }
            VectorSource.put(14, x, y, VectorTile(f.readBytes()))
        }
        val r = Osrm.parseAll(File(data, "route.json").readText())[0]
        val start = r.points[0]
        Places.addRecent(Place("National Archaeological Museum", "Patision 44", r.points.last()))
        Places.addRecent(Place("Syntagma", "Athens", Geo.offset(start, 120f, 900.0)))
        // no update check and no level celebration over the pictures
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("gamemap", 0).edit()
            .putLong("lastUpdateCheck", System.currentTimeMillis()).putInt("levelCelebrated", 50).apply()
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = activity.window.decorView
        val looper = shadowOf(Looper.getMainLooper())
        MapState.setLightMode(MapState.LightMode.DARK); MapState.setTheme(Theme.NEON_NOIR)
        LocationTracker.onLocationChanged(Location("gps").apply { latitude = start.lat; longitude = start.lon; accuracy = 5f; time = System.currentTimeMillis() })
        MapState.zoomBy(16.0 - MapState.targetZoom)
        fun shot(name: String) {
            val tmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            repeat(15) { looper.idleFor(java.time.Duration.ofMillis(60)); root.draw(Canvas(tmp)); Thread.sleep(20) }
            File(out, "$name.png").outputStream().use { tmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        fun texts(v: View = root, acc: MutableList<String> = mutableListOf()): List<String> {
            if (v.visibility != View.VISIBLE) return acc
            if (v is TextView) acc += v.text.toString()
            if (v is ViewGroup) for (i in 0 until v.childCount) texts(v.getChildAt(i), acc)
            return acc
        }
        fun find(pred: (View) -> Boolean, v: View = root): View? {
            if (v.visibility != View.VISIBLE) return null
            if (pred(v)) return v
            if (v is ViewGroup) for (i in 0 until v.childCount) find(pred, v.getChildAt(i))?.let { return it }
            return null
        }
        val log = StringBuilder()
        shot("home_46b_handle")
        val handle = find({ it is TextView && it.text.startsWith("⌃") }) as TextView?
        log.append("handle: ${handle?.text}\n")
        assertTrue("the handle shows", handle != null)
        assertFalse("cards hidden", texts().any { it.contains("Syntagma") })
        // swipe up on the sheet
        val sheet = find({ it.contentDescription?.toString() == "Saved & recent" }) as ViewGroup
        val loc = IntArray(2); sheet.getLocationOnScreen(loc)
        val x = loc[0] + sheet.width / 2f; val y = loc[1] + sheet.height / 2f
        val t0 = SystemClock.uptimeMillis()
        fun ev(a: Int, yy: Float, dt: Long) = MotionEvent.obtain(t0, t0 + dt, a, x, yy, 0)
        val h0 = sheet.height
        root.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, y, 0))
        root.dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE, y - 20, 16))
        root.dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE, y - 70, 48))
        looper.idle()
        val hMid = sheet.height
        log.append("lp height ${sheet.layoutParams.height}, handle alpha ${handle!!.alpha}, y=$y loc=${loc.toList()} root=${root.height}\n")
        File(out, "home46.txt").writeText(log.toString())
        shot("home_46b_dragging")
        log.append("sheet height: closed $h0, mid-drag $hMid\n")
        assertTrue("the sheet follows the finger ($h0 → $hMid)", hMid > h0)
        root.dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE, y - 110, 64))
        root.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, y - 120, 80))
        // the glide: part-way, then done
        looper.idleFor(java.time.Duration.ofMillis(100))
        val hGlide = sheet.height
        looper.idleFor(java.time.Duration.ofMillis(500))
        log.append("gliding $hGlide, open ${sheet.height}\n")
        shot("home_46b_open")
        log.append("after swipe up: ${texts().filter { it.contains("Syntagma") || it.startsWith("Saved") || it.startsWith("Recent") }}\n")
        assertTrue("swipe up opens it", texts().any { it.contains("Syntagma") })
        // picking a place tucks it away
        find({ it is TextView && it.text.contains("Syntagma") })!!.let { v -> var c: View? = v; while (c != null && !c.hasOnClickListeners()) c = c.parent as? View; c!!.performClick() }
        looper.idle()
        log.append("after picking: homeOpen=${activity.getSharedPreferences("gamemap", 0).getBoolean("homeOpen", true)}\n")
        assertFalse(activity.getSharedPreferences("gamemap", 0).getBoolean("homeOpen", true))
        File(out, "home46.txt").writeText(log.toString())
    }
}
