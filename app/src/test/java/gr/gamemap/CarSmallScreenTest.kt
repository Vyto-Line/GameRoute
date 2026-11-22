package gr.gamemap

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.location.Location
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Small car screens (photos, 22 Nov): buttons on top of each other (Friends behind +), End on the
 * street name, the invite card squeezed ("Ni…") with the compass on it. Driving, with an invite, on
 * several car sizes: no two buttons overlap, the name is clear of End, the compass clear of the card.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class CarSmallScreenTest {
    @Test
    fun nothingOnTopOfEachOther() {
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        val data = File(System.getProperty("previewData") ?: "../previewdata")
        data.listFiles()?.filter { it.name.endsWith(".pbf") }?.forEach { f ->
            val (x, y) = f.nameWithoutExtension.split("_").map { it.toInt() }
            VectorSource.put(14, x, y, VectorTile(f.readBytes()))
        }
        val route = Osrm.parseAll(File(data, "route.json").readText())[0]
        val start = route.points[0]
        LocationTracker.onLocationChanged(Location("gps").apply { latitude = start.lat; longitude = start.lon; accuracy = 5f; time = System.currentTimeMillis() })
        NavEngine.debugDrive(Place("Sounio", "", route.points.last()), route, 0)
        val log = StringBuilder()
        for (mode in listOf("plain", "invite", "message")) {
            val withInvite = mode == "invite"
            InvitePop.resetForTest()
            if (withInvite) {
                // (a turn under 200 m holds the card back: offer it parked, as in the photo)
                NavEngine.stop()
                InvitePop.offer(FriendCore.Invite("i1", "f1", "Nikos (test)", "ABC123", "Sounio", System.currentTimeMillis(), 1, "race"))
                assertTrue("the card is up", InvitePop.current != null)
            }
            if (mode == "message") {
                // a convoy message while parked, with Where to? and the Home / Work chips (the photo of 22 Nov)
                NavEngine.stop(); Convoy.debugStart(Place("Sounio", "", route.points.last())); Convoy.debugMessage("Maria (test)", "stop")
                // no destination yet, so Where to? and the chips show instead of the lobby
                Convoy::class.java.getDeclaredField("destination").apply { isAccessible = true }.set(null, null)
            }
            for ((w, h, dens) in listOf(Triple(800, 400, 1f), Triple(800, 480, 1f), Triple(1280, 480, 1f), Triple(1886, 816, 1.75f), Triple(1024, 600, 1.3f))) {
                MapState.setTheme(Theme.HIGH_CONTRAST)
                val r = MapRenderer(car = true).apply { carButtons = true }
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                repeat(3) { r.draw(Canvas(bmp), w, h, Rect(0, 0, w, h), dens, true); Thread.sleep(60) }
                val hits = r.carHits.filter { it.second != "cp:none" && !it.second.startsWith("cp:") }
                for (i in hits.indices) for (j in i + 1 until hits.size) {
                    val (a, an) = hits[i]; val (b, bn) = hits[j]
                    val ov = RectF(); if (ov.setIntersect(a, b) && ov.width() > 2 && ov.height() > 2) log.append("${w}x$h invite=$withInvite: $an overlaps $bn\n")
                }
                if (!r.endAt.isEmpty && RectF.intersects(r.endAt, r.roadNameRect)) log.append("${w}x$h: street name under End\n")
                if (mode == "message") {
                    if (r.messageAt.isEmpty) log.append("${w}x$h: no convoy message\n")
                    for ((hr, n) in hits) { val ov = RectF(); if (ov.setIntersect(hr, r.messageAt) && ov.width() > 2 && ov.height() > 2) log.append("${w}x$h: convoy message overlaps $n\n") }
                }
                if (withInvite && r.inviteAt.isEmpty) log.append("${w}x$h: no invite card\n")
                File(out, "car_small_${w}x${h}${if (mode == "plain") "" else "_$mode"}.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
            }
        }
        NavEngine.stop(); InvitePop.resetForTest(); Convoy.leave(quiet = true)
        File(out, "car_small.txt").writeText(log.ifEmpty { "no overlaps" }.toString())
        assertTrue(log.toString(), log.isEmpty())
    }
}
