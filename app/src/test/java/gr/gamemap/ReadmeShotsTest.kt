package gr.gamemap

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.location.Location
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.robolectric.RuntimeEnvironment

/** The pictures for the GitHub page: the real app, phone and car, in a mix of styles. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class ReadmeShotsTest {
    private fun find(v: View): GameMapView? {
        if (v is GameMapView) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
        return null
    }

    @Test
    fun shots() {
        val data = File(System.getProperty("previewData") ?: "../previewdata")
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        data.listFiles()?.filter { it.name.endsWith(".pbf") }?.forEach { f ->
            val (x, y) = f.nameWithoutExtension.split("_").map { it.toInt() }
            VectorSource.put(14, x, y, VectorTile(f.readBytes()))
        }
        val opts = Osrm.parseAll(File(data, "route.json").readText())
        opts.forEachIndexed { i, o ->
            o.trafficSeconds = (o.duration * 1.6 + i * 90).toInt(); o.delaySeconds = if (i == 1) 240 else 60
            o.traffic = listOf(Stretch(o.length * 0.5, o.length * 0.6, 2, 60)); o.limits = listOf(Stretch(0.0, o.length, 50))
        }
        val r = opts[0]
        val dest = Place("National Archaeological Museum", "Patision 44", r.points.last())
        // a phone that already has friends (as if they'd been added over the network)
        val t0 = System.currentTimeMillis()
        RuntimeEnvironment.getApplication().getSharedPreferences("friends", Context.MODE_PRIVATE).edit()
            .putString("code", "VYTO-4821").putString("codeV", "2").putString("fid", "me0000000000")
            .putString("friends", JSONArray()
                .put(JSONObject().put("fid", "f1nikos").put("n", "Nikos").put("s", "c2VjcmV0MQ").put("since", t0 - 86_400_000L * 30))
                .put(JSONObject().put("fid", "f3maria").put("n", "Maria").put("s", "c2VjcmV0Mw").put("since", t0 - 86_400_000L * 12))
                .put(JSONObject().put("fid", "f2giorgos").put("n", "Giorgos").put("s", "c2VjcmV0Mg").put("since", t0))
                .put(JSONObject().put("fid", "f4kostas").put("n", "Kostas").put("s", "c2VjcmV0NA").put("since", t0)).toString())
            .putString("requests", JSONArray().put(JSONObject().put("fid", "r1eleni").put("n", "Eleni").put("s", "c2VjcmV0NQ").put("at", t0).put("nonce", "n1")).toString())
            .apply()
        // no update check (it finds the real newest build) and no level celebration over the pictures
        RuntimeEnvironment.getApplication().getSharedPreferences("gamemap", Context.MODE_PRIVATE).edit()
            .putLong("lastUpdateCheck", System.currentTimeMillis()).putInt("levelCelebrated", 50).apply()
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = activity.window.decorView
        val looper = shadowOf(Looper.getMainLooper())
        var lastFix: Location? = null
        fun fresh() {
            lastFix?.let { l -> LocationTracker.onLocationChanged(Location(l).apply { time = System.currentTimeMillis() }) }
            // the convoy's made-up drivers keep "sending" (as real ones do every second): no "signal lost"
            for (m in Convoy.members.values) m.seen = System.currentTimeMillis()
        }
        fun shot(name: String, frames: Int = 30) {
            val tmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            repeat(frames) { fresh(); looper.idleFor(java.time.Duration.ofMillis(60)); root.draw(Canvas(tmp)); Thread.sleep(30) }
            val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bmp))
            File(out, "rd_$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        fun locate(m: Double, speed: Float = 11f) {
            var i = 0
            while (i < r.points.size - 2 && r.cum[i + 1] < m) i++
            val f = ((m - r.cum[i]) / maxOf(r.cum[i + 1] - r.cum[i], 0.1)).coerceIn(0.0, 1.0)
            val p = Geo.lerp(r.points[i], r.points[i + 1], f)
            val l = Location("gps").apply {
                latitude = p.lat; longitude = p.lon; this.speed = speed; bearing = Geo.bearing(r.points[i], r.points[i + 1])
                accuracy = 5f; time = System.currentTimeMillis()
            }
            lastFix = l
            LocationTracker.onLocationChanged(l)
        }
        fun back() { activity.onBackPressedDispatcher.onBackPressed(); looper.idle() }
        fun call(name: String) = MainActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity)
        lateinit var mapView: GameMapView
        fun clean() { // nothing left open from the shot before
            try { call("closeSearch") } catch (_: Exception) {}
            try { call("closeSheet") } catch (_: Exception) {}
            try { call("closePlace") } catch (_: Exception) {}
            mapView.onMapTap?.invoke()
            looper.idle()
        }
        fun style(t: Theme, dark: Boolean) { MapState.setTheme(t); MapState.setLightMode(if (dark) MapState.LightMode.DARK else MapState.LightMode.LIGHT) }
        val now = System.currentTimeMillis()
        Explorer.record(Explorer.Trip("National Archaeological Museum", now - 3_600_000, 840, 4200.0, 120, 41, 1, 1))
        Places.toggleSaved(Place("Mikel Coffee", "Kolonaki", LatLng(37.9785, 23.7410)))
        Places.save("home", Place("Home", "Glyfada", LatLng(37.8650, 23.7530)))
        locate(300.0, 0f)
        val map = find(root)!!
        mapView = map
        Friends.core.setOnline("f1nikos", now); Friends.core.setOnline("f3maria", now)
        // avatars: mine and my friends'
        Avatars.mine = "fox"
        Friends.avatars["f1nikos"] = "bolt:0891b2"; Friends.avatars["f3maria"] = "owl:7c3aed"; Friends.avatars["f2giorgos"] = "peak:059669"
        Explorer.addXp(3400)

        // home
        style(Theme.OPEN_WORLD, false); MapState.zoomBy(16.5 - MapState.targetZoom); shot("home")
        // a convoy invite while parked: the small card under Where to?
        style(Theme.VIOLET_RAIN, true)
        Friends.core.addTestInvite("f1nikos", "Nikos", "conv7777777", "Sounio")
        Friends.core.invites.firstOrNull { it.convoy == "conv7777777" }?.let { InvitePop.resetForTest(); InvitePop.offer(it) }
        shot("invitecard", frames = 12)
        InvitePop.close(); Friends.core.removeInviteForTest("conv7777777"); looper.idle()
        style(Theme.OPEN_WORLD, false)
        // search: a shop only the new places data has
        val run = MainActivity::class.java.getDeclaredMethod("runQuery", String::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
        Places.prepareGazetteer(LatLng(37.9755, 23.7348)); OpenPlaces.prepare(LatLng(37.9755, 23.7348))
        val search = MainActivity::class.java.getDeclaredField("search").apply { isAccessible = true }.get(activity) as android.widget.EditText
        search.requestFocus(); search.setText("starbucks"); run.invoke(activity, "starbucks", true)
        repeat(60) { looper.idleFor(java.time.Duration.ofMillis(100)); Thread.sleep(80) }
        shot("search"); search.setText(""); clean()
        // place card with fuel prices
        style(Theme.NEON_NOIR, true)
        map.onPlaceTap?.invoke(Place("EKO", "Fuel · Patision 60", LatLng(37.9880, 23.7300), "⛽", rating = 4.1, ratings = 212,
            extra = "95 €1.970 · Diesel €2.035 (area average, 13 Nov)"))
        shot("place"); clean()
        // routes
        style(Theme.LOOKOUT, false); clean()
        NavEngine.debugPlan(dest, opts); shot("routes")
        // driving
        style(Theme.VIOLET_RAIN, true); clean()
        NavEngine.debugDrive(dest, r, 120); locate(400.0, 14.7f) // 53 in a 50: orange
        repeat(200) { MapState.tick(); LocationTracker.tick(); looper.idleFor(java.time.Duration.ofMillis(10)) }
        shot("drive")
        // convoy while driving
        style(Theme.NEON_NOIR, true); clean()
        Convoy.myName = "Vyto"; Convoy.debugStart(dest)
        Convoy.debugMember("Nikos", r.points[r.points.size / 3], 48, now + 9 * 60_000L, r.length * 0.55, r.length, "driving")
        Convoy.debugMember("Maria", r.points[3], 31, now + 14 * 60_000L, r.length * 0.95, r.length, "driving")
        Convoy.debugMember("Giorgos", r.points[r.points.size / 2], 44, now + 7 * 60_000L, r.length * 0.4, r.length, "driving")
        MapState.zoomBy(15.6 - MapState.targetZoom)
        shot("convoy")
        activity.openSheetForTest("convoy"); shot("convoy_list"); back()
        // quiet driving: the buttons faded, two messages waiting for the stop
        Later.add("🏛 Landmark discovered: Panathenaic Stadium · +25 XP"); Later.add("👥 1 friend request: open Together")
        locate(600.0, 13f); repeat(60) { MapState.tick(); LocationTracker.tick(); looper.idleFor(java.time.Duration.ofMillis(10)) }
        shot("quiet")
        Later.take()
        Convoy.leave(quiet = true); NavEngine.stop(); looper.idle()
        // the convoy lobby (you started it) and its invite screen
        style(Theme.NEON_NOIR, true); locate(300.0, 0f); clean()
        Convoy.debugStart(Place("Glyfada Marina", "Glyfada", r.points.last())); Convoy.debugHost(true)
        Convoy.debugMember("Maria", r.points[5], 0, 0, -1.0, -1.0, "ready")
        Convoy.debugMember("Nikos", r.points[8], 0, 0, -1.0, -1.0, "ready")
        Convoy.members.values.first { it.name == "Maria" }.ready = true
        Convoy.members.values.first { it.name == "Nikos" }.along = true
        activity.openSheetForTest("convoy"); shot("lobby"); clean()
        activity.openSheetForTest("convoyinvite"); shot("invite"); clean()
        activity.refreshForTest(); shot("home_convoy")
        Convoy.leave(quiet = true); looper.idle()
        // You, and friends
        style(Theme.VIOLET_RAIN, true); clean()
        activity.openSheetForTest("you"); shot("you"); clean()
        activity.openSheetForTest("friends"); shot("friends"); clean()
        // race start countdown
        style(Theme.TRAILBLAZER, false); locate(300.0, 0f); clean()
        Convoy.debugStart(dest)
        Convoy.debugMember("Nikos", Geo.offset(r.points[2], 90f, 30.0), 0, 0, -1.0, -1.0, "ready")
        Convoy.members.values.first().apply { ready = true; readyAt = System.currentTimeMillis() }
        Convoy.setReady(true)
        MapState.zoomBy(16.5 - MapState.targetZoom)
        val go = Convoy.goAt
        while (System.currentTimeMillis() < go - 2600) { looper.idleFor(java.time.Duration.ofMillis(50)); Thread.sleep(40) }
        shot("race", frames = 4)
        repeat(60) { looper.idleFor(java.time.Duration.ofMillis(50)); Thread.sleep(40) }
        Convoy.leave(quiet = true); NavEngine.stop(); looper.idle()
        // fuel list
        style(Theme.OPEN_WORLD, false); locate(300.0, 0f)
        clean(); activity.showFuelForTest()
        repeat(250) { looper.idleFor(java.time.Duration.ofMillis(100)); Thread.sleep(80) }
        shot("fuel"); clean()
        // arrival card
        style(Theme.PARCHMENT_QUEST, false); clean()
        val parts = listOf(Xp.Part("Trip", 31, "4.2 km"), Xp.Part("Clean drive", 6), Xp.Part("New roads", 18, "0.9 km never driven"),
            Xp.Part("Landmark", 25, "1 new"), Xp.Part("Daily streak", 30, "day 3"))
        NavEngine.debugTrip(Explorer.Trip("National Archaeological Museum", now, 840, 4200.0, 120, parts.sumOf { it.xp }, 1, Explorer.level, parts)); shot("arrival")
        NavEngine.dismissTrip(); looper.idle()
        style(Theme.NEON_NOIR, true); clean()
        activity.openSheetForTest("progress"); shot("progress"); clean()
        activity.openSheetForTest("garage"); shot("garage"); clean()
        // styles and settings
        clean(); style(Theme.BLOCKY, false); MapState.zoomBy(17.0 - MapState.targetZoom); shot("blocky")
        style(Theme.RETRO, false); activity.openSheetForTest("style"); shot("styles"); clean()
        style(Theme.OPEN_WORLD, true); activity.openSheetForTest("settings"); shot("settings"); clean()
        // zoomed out: countries
        style(Theme.OPEN_WORLD, false); MapState.zoomBy(4.2 - MapState.targetZoom)
        shot("countries", frames = 120)
        MapState.zoomBy(16.0 - MapState.targetZoom)
        // every style, light and dark, the same street corner
        for (t in Theme.values()) for (dark in listOf(false, true)) {
            style(t, dark); shot("theme_${t.name.lowercase()}_${if (dark) "dark" else "light"}", frames = 40)
        }

        // the car
        val w = 1920; val h = 720
        val rr = MapRenderer(car = true).apply { carButtons = true }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val c = Canvas(bmp); val area = Rect(0, 0, w, h)
        fun car(name: String) {
            repeat(30) { fresh(); rr.draw(c, w, h, area, 1.6f, true); looper.idleFor(java.time.Duration.ofMillis(60)); Thread.sleep(30) }
            rr.draw(c, w, h, area, 1.6f, true)
            File(out, "rd_car_$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        style(Theme.NEON_NOIR, true)
        NavEngine.debugDrive(dest, r, 120); locate(400.0)
        repeat(100) { MapState.tick(); LocationTracker.tick(); looper.idleFor(java.time.Duration.ofMillis(10)) }
        MapState.car.zoomBy(16.0 - MapState.car.targetZoom)
        car("drive")
        Convoy.debugStart(dest)
        Convoy.debugMember("Nikos", r.points[r.points.size / 3], 48, now + 9 * 60_000L, r.length * 0.55, r.length, "driving")
        Convoy.debugMember("Maria", r.points[3], 31, now + 14 * 60_000L, r.length * 0.95, r.length, "driving")
        style(Theme.TRAILBLAZER, false); car("convoy")
        Convoy.leave(quiet = true); NavEngine.stop(); locate(300.0, 0f)
        style(Theme.LOOKOUT, true)
        rr.selectedPlace = Place("EKO", "Fuel", LatLng(37.9880, 23.7300), "⛽", extra = "95 €1.970 · Diesel €2.035 (area average)")
        car("place")
    }
}
