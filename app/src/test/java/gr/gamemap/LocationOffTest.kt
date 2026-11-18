package gr.gamemap

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Looper
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Location off / not allowed: the card on the phone (and gone when it's back), the message in the car. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class LocationOffTest {
    @Test
    fun cards() {
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val looper = shadowOf(Looper.getMainLooper())
        val root = a.window.decorView
        fun shot(name: String) {
            repeat(6) { looper.idleFor(java.time.Duration.ofMillis(60)) }
            val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bmp)); File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        assertNull("location fine: no card", a.locCardForTest())
        LocationStatus.setForTest(LocationStatus.State.OFF); looper.idle()
        assertNotNull("location off: the card", a.locCardForTest()); shot("location_off_phone")
        LocationStatus.setForTest(LocationStatus.State.NO_PERMISSION); looper.idle()
        assertNotNull("not allowed: the card", a.locCardForTest()); shot("location_perm_phone")
        // the car
        val w = 1280; val h = 480
        val rr = MapRenderer(car = true).apply { carButtons = true; forceLite = false }
        LocationStatus.setForTest(LocationStatus.State.OFF)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        repeat(2) { rr.draw(Canvas(bmp), w, h, Rect(0, 0, w, h), 1f, true); Thread.sleep(80) }
        File(out, "location_off_car.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        LocationStatus.setForTest(LocationStatus.State.OK); looper.idle()
        assertNull("back on: the card goes", a.locCardForTest())
    }
}
