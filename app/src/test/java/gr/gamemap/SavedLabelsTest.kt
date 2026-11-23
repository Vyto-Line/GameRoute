package gr.gamemap

import android.graphics.Bitmap
import android.graphics.Canvas
import android.location.Location
import android.os.Looper
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Saved places with labels: "Dad" as a button on the home screen, and first in search. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class SavedLabelsTest {
    @Test
    fun labels() {
        val data = File(System.getProperty("previewData") ?: "../previewdata")
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        data.listFiles()?.filter { it.name.endsWith(".pbf") }?.forEach { f ->
            val (x, y) = f.nameWithoutExtension.split("_").map { it.toInt() }
            VectorSource.put(14, x, y, VectorTile(f.readBytes()))
        }
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = activity.window.decorView
        val looper = shadowOf(Looper.getMainLooper())
        fun shot(name: String) {
            repeat(30) { looper.idleFor(java.time.Duration.ofMillis(60)); Thread.sleep(30) }
            val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888); root.draw(Canvas(bmp))
            File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        val here = LatLng(37.9840, 23.7280)
        LocationTracker.onLocationChanged(Location("gps").apply { latitude = here.lat; longitude = here.lon; accuracy = 5f; time = System.currentTimeMillis() })
        MapState.setTheme(Theme.OPEN_WORLD); MapState.setLightMode(MapState.LightMode.LIGHT)
        val log = StringBuilder()
        // a place found in search, saved with a label
        val dadHouse = Place("Aigyptou 25", "Glyfada 165 62", LatLng(37.8930, 23.7644), "🏠")
        Places.saveFavourite(dadHouse, "Dad", "👤")
        Places.saveFavourite(Place("Gym Plus", "Kifisias 12", LatLng(38.0, 23.79), "🏋"), "Gym", "🏋")
        Places.saveFavourite(Place("Mikel Coffee", "Kolonaki", LatLng(37.9785, 23.7410)), null, null)
        log.append("labelled: ${Places.labelled().joinToString { "${it.icon} ${it.label} = ${it.name}" }}\n")
        log.append("saved list shows: ${Places.savedPlaces().joinToString { "${it.icon} ${it.name} (${it.address})" }}\n")
        for (q in listOf("dad", "da", "gym", "mikel")) {
            val inst = Places.local(q, here).firstOrNull()
            val full = Places.find(q, here, full = true)?.firstOrNull()
            log.append("\"$q\": instant first = ${inst?.icon} ${inst?.name}; full first = ${full?.icon} ${full?.name}\n")
        }
        // edit the label, then remove it
        Places.saveFavourite(Places.savedAt(dadHouse.pos)!!.place(), "Μπαμπάς", "👤")
        log.append("after renaming: ${Places.labelled().map { it.label }}; original name kept: ${Places.savedAt(dadHouse.pos)?.name}\n")
        log.append("\"babas\" (Greeklish) first: ${Places.local("babas", here).firstOrNull()?.name}\n")
        MapState.zoomBy(16.0 - MapState.targetZoom)
        shot("labels_home")
        val run = ActivityMethod.of("runQuery", String::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
        val search = MainActivity::class.java.getDeclaredField("search").apply { isAccessible = true }.get(activity) as android.widget.EditText
        search.requestFocus(); search.setText("gy"); run.invoke(activity, "gy", false)
        repeat(40) { looper.idleFor(java.time.Duration.ofMillis(100)); Thread.sleep(60) }
        shot("labels_search")
        File(out, "labels.txt").writeText(log.toString())
    }
}
