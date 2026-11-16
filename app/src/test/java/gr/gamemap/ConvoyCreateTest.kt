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

/** Convoy sheet with Create, a convoy without a destination, everyone's colours, tapping a friend. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class ConvoyCreateTest {
    @Test
    fun screens() {
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
            repeat(30) { looper.idleFor(java.time.Duration.ofMillis(50)); Thread.sleep(30) }
            val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888); root.draw(Canvas(bmp))
            File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        MapState.setLightMode(MapState.LightMode.DARK); MapState.setTheme(Theme.NEON_NOIR)
        val p = r.points[8]
        LocationTracker.onLocationChanged(Location("gps").apply { latitude = p.lat; longitude = p.lon; accuracy = 5f; time = System.currentTimeMillis() })
        Convoy.myName = "Vyto"
        activity.openSheetForTest("convoy"); shot("create_1_sheet")
        Convoy.create(null); activity.openSheetForTest("convoy"); shot("create_2_nodest")
        val now = System.currentTimeMillis()
        for ((i, n) in listOf("Nikos", "Maria", "Giorgos", "Eleni", "Kostas").withIndex())
            Convoy.debugMember(n, r.points[12 + i * 6], 20 + i * 9, now + 600_000, r.length * (0.9 - i * 0.1), r.length, "ready")
        Convoy.setDestination(Place("National Archaeological Museum", "Patision 44", r.points.last()))
        activity.openSheetForTest("convoy"); shot("create_3_colours")
        val colours = (Convoy.members.values.map { it.color } + Convoy.myColor)
        activity.onBackPressedDispatcher.onBackPressed()
        MapState.zoomBy(16.0 - MapState.targetZoom)
        shot("create_4_map")
        val map = MainActivity::class.java.getDeclaredField("map").apply { isAccessible = true }.get(activity)
        @Suppress("UNCHECKED_CAST")
        val tap = map.javaClass.getMethod("getOnFriendTap").invoke(map) as ((Convoy.Member) -> Unit)
        tap(Convoy.members.values.first { it.name == "Maria" }); shot("create_5_friend")
        // a friend chooses a new destination: I'm asked (dialog), and the car shows Go / ✕
        val nikos = Convoy.members.values.first { it.name == "Nikos" }
        Convoy.take(org.json.JSONObject().put("t", "pos").put("id", nikos.id).put("n", "Nikos").put("st", "ready")
            .put("ts", System.currentTimeMillis()).put("dn", "Acropolis Museum").put("da", "Dionysiou Areopagitou 15")
            .put("dlat", 37.9685).put("dlon", 23.7285).put("dt", System.currentTimeMillis() + 5000))
        shot("create_6_proposal")
        val car = MapRenderer(car = true).apply { carButtons = true }
        val cb = Bitmap.createBitmap(1920, 720, Bitmap.Config.ARGB_8888)
        repeat(20) { car.draw(Canvas(cb), 1920, 720, android.graphics.Rect(0, 0, 1920, 720), 1.6f, true); looper.idleFor(java.time.Duration.ofMillis(50)); Thread.sleep(40) }
        File(out, "create_7_car_proposal.png").outputStream().use { cb.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val before = Convoy.destination?.name
        Convoy.accept()
        val afterAccept = Convoy.destination?.name
        // option B's None: I remove it, and what I tell the others
        Convoy.clearDestination()
        val hello = Convoy::class.java.getDeclaredMethod("hello", String::class.java).apply { isAccessible = true }.invoke(Convoy, "ready") as org.json.JSONObject
        val mine = "after None: destination=${Convoy.destination?.name}, message says removed=${hello.optBoolean("dx")} dt=${hello.optLong("dt") > 0}"
        // someone else removes it: I'm asked; OK removes it for me too
        Convoy.setDestination(Place("Acropolis Museum", "", LatLng(37.9685, 23.7285)))
        Convoy.take(org.json.JSONObject().put("t", "pos").put("id", nikos.id).put("n", "Nikos").put("st", "ready")
            .put("ts", System.currentTimeMillis() + 1).put("dt", System.currentTimeMillis() + 9000).put("dx", true))
        val asked = Convoy.proposal?.let { "asked: from ${it.from}, removal=${it.place == null}" } ?: "NOT asked"
        car.draw(Canvas(cb), 1920, 720, android.graphics.Rect(0, 0, 1920, 720), 1.6f, true)
        File(out, "create_8_car_removed.png").outputStream().use { cb.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Convoy.accept()
        File(out, "convoycreate.txt").writeText("proposal: before=$before after accept=$afterAccept\n$mine\n$asked; after OK: destination=${Convoy.destination?.name}\n" + "colours all different: ${colours.distinct().size == colours.size} (${colours.size})\n" +
            "destination: ${Convoy.destination?.name}\n")
        Convoy.leave(quiet = true)
    }
}
