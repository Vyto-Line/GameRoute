package gr.gamemap

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The map turning slowly (like the video): how often street names come and go, old vs new. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class LabelFlickerTest {
    @Test
    fun steadyNames() {
        val data = File(System.getProperty("previewData") ?: "../previewdata")
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        data.listFiles()?.filter { it.name.endsWith(".pbf") }?.forEach { f ->
            val (x, y) = f.nameWithoutExtension.split("_").map { it.toInt() }
            VectorSource.put(14, x, y, VectorTile(f.readBytes()))
        }
        val r = Osrm.parseAll(File(data, "route.json").readText())[0]
        val p = r.points[r.points.size / 2]
        LocationTracker.onLocationChanged(android.location.Location("gps").apply { latitude = p.lat; longitude = p.lon; accuracy = 5f; time = System.currentTimeMillis() })
        MapState.setTheme(Theme.VIOLET_RAIN)
        val log = StringBuilder()
        val counts = HashMap<Boolean, Pair<Int, Int>>()
        for (legacy in listOf(true, false)) {
            Labels.legacy = legacy
            MapState.phone.clearRotation()
            MapState.zoomBy(15.6 - MapState.targetZoom)
            repeat(200) { MapState.tick(); Thread.sleep(2) }
            val mr = MapRenderer()
            val frame = Bitmap.createBitmap(1080, 2000, Bitmap.Config.ARGB_8888)
            val c = Canvas(frame)
            // settle
            repeat(20) { mr.draw(c, 1080, 2000, Rect(0, 0, 1080, 2000), 2.75f, hud = false); org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(30)) }
            Labels.appeared = 0; Labels.vanished = 0
            // turn 60 degrees in small steps, a frame each (~ a slow two-finger twist)
            repeat(120) {
                MapState.phone.rotateBy(0.5f, 0f)
                MapState.tick()
                mr.draw(c, 1080, 2000, Rect(0, 0, 1080, 2000), 2.75f, hud = false)
                // the app's clock (names are re-placed 8 times a second, fades run on it)
                org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(16))
            }
            counts[legacy] = Labels.appeared to Labels.vanished
            log.append("bearing now ${MapState.phone.userBearing} following=${MapState.phone.following}\n")
            log.append("${if (legacy) "old" else "new"}: names appeared ${Labels.appeared} times, vanished ${Labels.vanished} times over 120 frames\n")
            File(out, "labels_${if (legacy) "old" else "new"}.png").outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 90, it) }
        }
        Labels.legacy = false
        File(out, "labelflicker.txt").writeText(log.toString())
        val old = counts[true]!!.let { it.first + it.second }; val new = counts[false]!!.let { it.first + it.second }
        assertTrue("names still come and go: $new times (old $old)", new <= old / 2 + 4)
    }
}
