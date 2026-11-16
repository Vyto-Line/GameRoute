package gr.gamemap

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Update 100, stage 1 (driving): the new voice timing, the GPS jump filter, the still arrow at a
 * red light, keeping vague city fixes while navigating (no tunnel guessing), matching the right road
 * without a route, and the Weak GPS note.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class DrivingStage1Test {
    private val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
    private val log = StringBuilder()

    private fun step(type: String, dir: String, road: String) = NavStep(type, dir, road, 0, LatLng(37.98, 23.73), null)

    /** Drives at [speed] m/s from [from] metres to the turn, one position a second; returns what was said. */
    private fun approach(index: Int, from: Double, speed: Float, talk: Int, s: NavStep = step("turn", "right", "Αιόλου")): List<String> {
        val heard = ArrayList<String>()
        Voice.heardForTest = heard
        Voice.talk = talk
        var d = from
        while (d > 0) {
            Voice.progress(index, s, d, speed, null)
            d -= speed
        }
        Voice.heardForTest = null
        return heard
    }

    @Test
    fun stage1() {
        try { run() } finally { File(out, "stage1.txt").writeText(log.toString()) }
    }

    private fun run() {
        val app = RuntimeEnvironment.getApplication()
        Voice.init(app)
        Voice.lang = Phrases.ENGLISH
        Voice.classic = false

        // ---- voice: town at 45 km/h
        log.append("== Voice ==\n")
        val town = approach(1, 600.0, 12.5f, 0)
        log.append("town 45 km/h: $town\n")
        assertEquals("town: a heads-up and the turn", 2, town.size)
        assertTrue(town[0], town[0].startsWith("In 200 metres"))
        assertTrue(town[1], town[1].startsWith("Turn right"))

        val road = approach(2, 900.0, 17f, 0)
        log.append("road 60 km/h: $road\n")
        assertEquals(2, road.size)
        assertTrue(road[0], road[0].startsWith("In 300 metres"))

        val motorway = approach(3, 2500.0, 30f, 0, step("off ramp", "slight right", "Αθηνών"))
        log.append("motorway 110 km/h: $motorway\n")
        assertEquals("motorway: 1 km, 500 m and the exit", 3, motorway.size)
        assertTrue(motorway[0], motorway[0].startsWith("In 1 kilometres") || motorway[0].startsWith("In 1 kilometre"))
        assertTrue(motorway[1], motorway[1].startsWith("In 500 metres"))
        assertTrue(motorway[2], motorway[2].startsWith("Take the exit"))

        val fewer = approach(4, 2500.0, 30f, 1, step("off ramp", "slight right", "Αθηνών"))
        log.append("motorway, Fewer: $fewer\n")
        assertEquals(2, fewer.size); assertTrue(fewer[0].startsWith("In 500 metres"))

        val turnsOnly = approach(5, 600.0, 12.5f, 2)
        log.append("town, Turns only: $turnsOnly\n")
        assertEquals(listOf("Turn right onto Αιόλου"), turnsOnly)

        // a turn that starts 150 m after the last one: no "in 200 metres", just the turn
        val close = approach(6, 150.0, 12.5f, 0)
        log.append("turn 150 m after the last: $close\n")
        assertEquals(1, close.size)

        // every line said in good time: the turn itself at least ~5 s before it at 45 km/h
        val timed = ArrayList<Pair<Double, String>>()
        run {
            Voice.heardForTest = ArrayList()
            var d = 600.0
            while (d > 0) {
                val before = Voice.heardForTest!!.size
                Voice.progress(7, step("turn", "left", "Σταδίου"), d, 12.5f, null)
                if (Voice.heardForTest!!.size > before) timed += d to Voice.heardForTest!!.last()
                d -= 12.5
            }
            Voice.heardForTest = null
        }
        log.append("when: ${timed.joinToString { "${it.first.toInt()} m: ${it.second}" }}\n")
        assertTrue("turn said at least 60 m before", timed.last().first >= 60)
        assertTrue("heads-up said near 200 m", timed.first().first in 200.0..240.0)

        // ---- GPS: impossible jumps
        log.append("\n== GPS ==\n")
        fun fix(lat: Double, lon: Double, t: Long, spd: Float = 12f, acc: Float = 20f) = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = lat; longitude = lon; time = t; speed = spd; accuracy = acc; bearing = 0f
        }
        val t0 = System.currentTimeMillis()
        val a = fix(37.9800, 23.7300, t0)
        assertFalse("12 m in a second is fine", LocationTracker.impossibleJump(a, fix(37.98011, 23.7300, t0 + 1000)))
        assertTrue("400 m in a second is not", LocationTracker.impossibleJump(a, fix(37.9836, 23.7300, t0 + 1000)))
        assertFalse("400 m in 30 s is", LocationTracker.impossibleJump(a, fix(37.9836, 23.7300, t0 + 30_000)))

        val looper = shadowOf(Looper.getMainLooper())
        LocationTracker.onLocationChanged(fix(37.9800, 23.7300, t0))
        LocationTracker.onLocationChanged(fix(37.98011, 23.7300, t0 + 1000))
        LocationTracker.onLocationChanged(fix(37.9850, 23.7300, t0 + 2000)) // a 550 m bounce
        val after = LocationTracker.position!!
        log.append("after a bounce: ${after.lat}\n")
        assertTrue("the bounce is ignored", after.lat < 37.981)
        // four in a row at the new place: it's real (e.g. a long gap), believe it
        for (k in 3..6) LocationTracker.onLocationChanged(fix(37.9850, 23.7300, t0 + k * 1000L))
        assertTrue("a lasting move is believed", LocationTracker.position!!.lat > 37.984)

        // ---- the arrow keeps still at a red light
        val tr = t0 + 60_000
        LocationTracker.onLocationChanged(fix(37.9900, 23.7300, tr, spd = 0f, acc = 12f))
        repeat(20) { looper.idleFor(java.time.Duration.ofMillis(100)) }
        val still = LocationTracker.displayPosition()!!
        LocationTracker.onLocationChanged(fix(37.99004, 23.73005, tr + 1000, spd = 0f, acc = 12f)) // 6 m of wander
        repeat(20) { looper.idleFor(java.time.Duration.ofMillis(100)) }
        val moved = Geo.distance(still, LocationTracker.displayPosition()!!)
        log.append("arrow moved at a red light: ${"%.1f".format(moved)} m\n")
        assertTrue("arrow still when stopped", moved < 0.5)

        // ---- road matching without a route: parallel roads, one-way, continuity
        log.append("\n== Road matching ==\n")
        val o = LatLng(37.95, 23.70)
        fun at(x: Double, y: Double) = LatLng(o.lat + y / 110_540.0, o.lon + x / (111_320.0 * Math.cos(o.lat * Math.PI / 180)))
        RoadMatch.useForTest(o.lat, o.lon, listOf(
            RoadMatch.Seg(-500.0, 0.0, 500.0, 0.0, "motorway", 1, 1, false),     // eastbound motorway
            RoadMatch.Seg(-500.0, 15.0, 500.0, 15.0, "service", 0, 2, false),    // service road beside it
            RoadMatch.Seg(100.0, -300.0, 100.0, 300.0, "primary", 0, 3, true),   // a bridge across
        ))
        val fast = RoadMatch.match(at(0.0, 9.0), 90f, 30f, 15f)!!
        log.append("110 km/h, 9 m from the motorway, 6 m from the service road → y=${"%.1f".format((fast.first.lat - o.lat) * 110_540)}\n")
        assertTrue("fast car: motorway", Math.abs((fast.first.lat - o.lat) * 110_540) < 1)
        RoadMatch.useForTest(o.lat, o.lon, listOf(
            RoadMatch.Seg(-500.0, 0.0, 500.0, 0.0, "primary", 0, 1, false),
            RoadMatch.Seg(-500.0, 14.0, 500.0, 14.0, "primary", 0, 2, false),
        ))
        fun y(r: Pair<LatLng, Float>?) = (r!!.first.lat - o.lat) * 110_540
        val r1 = y(RoadMatch.match(at(0.0, 2.0), 90f, 14f, 15f))
        val r2 = y(RoadMatch.match(at(15.0, 8.0), 90f, 14f, 15f))   // drifted closer to the other road
        val r3 = y(RoadMatch.match(at(30.0, 13.0), 90f, 14f, 15f))  // really on the other one now
        log.append("parallel roads: ${"%.0f".format(r1)} → ${"%.0f".format(r2)} → ${"%.0f".format(r3)}\n")
        assertTrue("starts on the first", Math.abs(r1) < 1)
        assertTrue("a drift doesn't hop roads", Math.abs(r2) < 1)
        assertTrue("a real change does", Math.abs(r3 - 14) < 1)
        RoadMatch.useForTest(o.lat, o.lon, listOf(RoadMatch.Seg(-500.0, 0.0, 500.0, 0.0, "primary", 1, 1, false)))
        assertEquals("against a one-way street: no match", null, RoadMatch.match(at(0.0, 3.0), 270f, 10f, 15f))
        RoadMatch.reset()

        // ---- navigating through vague city fixes: no tunnel guessing, the trip moves on
        log.append("\n== Vague fixes while navigating ==\n")
        val data = File(System.getProperty("previewData") ?: "../previewdata")
        val opts = Osrm.parseAll(File(data, "route.json").readText())
        val r = opts[0]
        val dest = Place("National Archaeological Museum", "Patision 44", r.points.last())
        NavEngine.debugDrive(dest, r, 120)
        fun on(m: Double, acc: Float, t: Long) {
            var i = 0
            while (i < r.points.size - 2 && r.cum[i + 1] < m) i++
            val f = ((m - r.cum[i]) / maxOf(r.cum[i + 1] - r.cum[i], 0.1)).coerceIn(0.0, 1.0)
            val p = Geo.lerp(r.points[i], r.points[i + 1], f)
            LocationTracker.onLocationChanged(Location(LocationManager.GPS_PROVIDER).apply {
                latitude = p.lat; longitude = p.lon; speed = 11f; accuracy = acc; time = t
                bearing = Geo.bearing(r.points[i], r.points[i + 1])
            })
        }
        val tn = System.currentTimeMillis()
        on(200.0, 10f, tn)
        val alongStart = NavEngine.along
        for (k in 1..8) { on(200.0 + k * 11, 70f, tn + k * 1000L); looper.idleFor(java.time.Duration.ofMillis(1000)) }
        log.append("8 fixes at 70 m accuracy: along ${alongStart.toInt()} → ${NavEngine.along.toInt()} m, estimating=${NavEngine.estimating}\n")
        assertFalse("not guessing", NavEngine.estimating)
        assertTrue("the trip followed the fixes", NavEngine.along - alongStart > 60)

        // ---- Weak GPS note: picture (phone)
        data.listFiles()?.filter { it.name.endsWith(".pbf") }?.forEach { f ->
            val (x, yy) = f.nameWithoutExtension.split("_").map { it.toInt() }
            VectorSource.put(14, x, yy, VectorTile(f.readBytes()))
        }
        on(320.0, 45f, System.currentTimeMillis())
        assertTrue("45 m accuracy while navigating = weak", LocationTracker.weak)
        val w = 1080; val h = 2340
        val rr = MapRenderer(car = false)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val c = Canvas(bmp)
        MapState.setTheme(Theme.NEON_NOIR); MapState.setLightMode(MapState.LightMode.DARK)
        repeat(30) { rr.draw(c, w, h, Rect(0, 0, w, h), 2.75f, true); looper.idleFor(java.time.Duration.ofMillis(60)); Thread.sleep(20) }
        File(out, "stage1_weak_gps.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // and the car
        val cw = 1920; val ch = 720
        val car = MapRenderer(car = true).apply { carButtons = true }
        val cb = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888); val cc = Canvas(cb)
        MapState.setTheme(Theme.VIOLET_RAIN)
        repeat(30) { car.draw(cc, cw, ch, Rect(0, 0, cw, ch), 1.6f, true); looper.idleFor(java.time.Duration.ofMillis(60)); Thread.sleep(20) }
        File(out, "stage1_weak_gps_car.png").outputStream().use { cb.compress(Bitmap.CompressFormat.PNG, 100, it) }
        on(330.0, 6f, System.currentTimeMillis())
        assertFalse("a sharp fix clears it", LocationTracker.weak)
        NavEngine.stop()
        log.append("\nall stage 1 checks passed\n")
    }
}
