package gr.gamemap

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.location.Location
import android.os.Looper
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Frame time while driving (car 1920×720 and phone), per drawing stage, for every style. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class PerfTest {
    @Test
    fun frames() {
        val data = File(System.getProperty("previewData") ?: "../previewdata")
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        data.listFiles()?.filter { it.name.endsWith(".pbf") }?.forEach { f ->
            val (x, y) = f.nameWithoutExtension.split("_").map { it.toInt() }
            VectorSource.put(14, x, y, VectorTile(f.readBytes()))
        }
        val r = Osrm.parseAll(File(data, "route.json").readText())[0]
        r.limits = listOf(Stretch(0.0, r.length, 50))
        NavEngine.debugDrive(Place("Museum", "", r.points.last()), r, 60)
        // a convoy around you: three friends on the map, a message (the busy frame)
        Convoy.debugStart(Place("Museum", "", r.points.last()))
        for ((k, n) in listOf("Giorgos", "Nikos", "Maria").withIndex())
            Convoy.debugMember(n, r.points[minOf(r.points.size - 1, 8 + k * 6)], 40 + k * 4, System.currentTimeMillis() + 300_000, 1500.0 - k * 200, 2500.0, "driving", 30f * k)
        Convoy.debugMessage("Maria", "stop")
        val log = StringBuilder()
        fun at(m: Double): Location {
            var i = 0
            while (i < r.points.size - 2 && r.cum[i + 1] < m) i++
            val f = ((m - r.cum[i]) / maxOf(r.cum[i + 1] - r.cum[i], 0.1)).coerceIn(0.0, 1.0)
            val p = Geo.lerp(r.points[i], r.points[i + 1], f)
            return Location("gps").apply { latitude = p.lat; longitude = p.lon; speed = 12f; bearing = Geo.bearing(r.points[i], r.points[i + 1]); accuracy = 5f; time = System.currentTimeMillis() }
        }
        for (lite in listOf(false, true)) for (car in listOf(true, false)) for (th in Theme.entries) {
            if (lite && th !in listOf(Theme.OPEN_WORLD, Theme.PARCHMENT_QUEST, Theme.NEON_NOIR)) continue
            MapState.setTheme(th); MapState.setLightMode(MapState.LightMode.DARK)
            val w = if (car) 1920 else 1080; val h = if (car) 720 else 2340; val d = if (car) 1.6f else 2.75f
            val rr = MapRenderer(car = car).apply { carButtons = car; forceLite = lite }
            val c = Canvas(Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888))
            val area = Rect(0, 0, w, h)
            var m = 200.0
            // warm up: tiles rendered, labels cached
            repeat(60) { LocationTracker.onLocationChanged(at(m)); rr.draw(c, w, h, area, d, true); shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50)); Thread.sleep(40) }
            MapRenderer.times.clear(); MapRenderer.allocs.clear(); MapRenderer.profile = true
            // the JVM's per-thread allocation counter (not in android.jar, so by reflection)
            val mx = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
            val probe = Class.forName("com.sun.management.ThreadMXBean").getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
            MapRenderer.allocProbe = { probe.invoke(mx, Thread.currentThread().id) as Long }
            val frames = 60
            val t0 = System.nanoTime()
            repeat(frames) { k ->
                if (k % 30 == 0) { m += 12.0; LocationTracker.onLocationChanged(at(m)) } // a GPS fix a second
                rr.draw(c, w, h, area, d, true)
            }
            val total = (System.nanoTime() - t0) / frames / 1e6
            MapRenderer.profile = false
            // the speed pass (23 Nov) halved the garbage (car ~85 → ~40 KB a frame): keep it there
            val kb = MapRenderer.allocs.values.sum() / frames / 1024
            if (!lite) org.junit.Assert.assertTrue("${th.name} ${if (car) "car" else "phone"}: $kb KB of garbage a frame", kb < 70)
            log.append("${if (car) "car  " else "phone"}${if (lite) " LITE" else ""} ${th.name.padEnd(16)} ${"%5.1f".format(total)} ms  " +
                MapRenderer.times.entries.sortedByDescending { it.value }.joinToString { "${it.key} ${"%.1f".format(it.value / frames / 1e6)}" } +
                "\n      garbage/frame ${MapRenderer.allocs.values.sum() / frames / 1024} KB: " +
                MapRenderer.allocs.entries.filter { it.value / frames > 512 }.sortedByDescending { it.value }.joinToString { "${it.key} ${it.value / frames / 1024}" } + "\n")
        }
        // drawing one 512 px map tile (background work while driving)
        for (th in Theme.entries) for (dark in listOf(false, true)) {
            if (th == Theme.BLOCKY) continue
            VectorRenderer.render(th, 16, 37085, 25286, 512, dark)
            val t0 = System.nanoTime()
            var k = 0
            for (ty in 25284..25287) for (tx in 37083..37086) { VectorRenderer.render(th, 16, tx, ty, 512, dark); k++ }
            log.append("tile ${th.name.padEnd(16)} ${if (dark) "dark " else "light"} ${"%.1f".format((System.nanoTime() - t0) / k / 1e6)} ms\n")
        }
        File(out, "perf.txt").writeText(log.toString())
    }
}
