package gr.gamemap

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Settings › Offline maps: regions, settings; and how big each region is. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class OfflineSheetTest {
    @Test
    fun sheet() {
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = activity.window.decorView
        val log = StringBuilder()
        for (r in Offline.regions) log.append("${r.id}: ${Offline.tileCount(r)} tiles, about ${Offline.size(Offline.estimate(Offline.tileCount(r)))}\n")
        // a real download of a small place: the places square and a few tiles
        OpenPlaces.saveOffline("379_237")
        log.append("places square saved: ${File(activity.filesDir, "places-offline/379_237.tsv.gz").length()} bytes\n")
        activity.openSheetForTest("offline")
        val tmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        repeat(20) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(60)); root.draw(Canvas(tmp)); Thread.sleep(30) }
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        File(out, "offline_sheet.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        File(out, "offline.txt").writeText(log.toString())
    }
}
