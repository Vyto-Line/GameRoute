package gr.gamemap

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The arrival time jumping 6 → 15 → 6 (drive, 20 Nov): TomTom's time was asked from the raw GPS
 * point, and a few metres off it routed a detour back onto the route. Now: asked from the spot on
 * the route, and an answer that went another way is thrown away. Live against TomTom along the
 * test route: the times from one point to the next must be steady.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EtaTomTomTest {
    @Test
    fun steady() {
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        val log = StringBuilder()
        // the rule on its own
        assertTrue(TomTomTime.followsRoute(5000.0, 5200))
        assertFalse("a detour round the block", TomTomTime.followsRoute(3000.0, 4100))
        assertTrue("no length given", TomTomTime.followsRoute(3000.0, 0))
        if (!Traffic.active) { File(out, "etatomtom.txt").writeText("no TomTom key: rule checked only\n"); return }
        val data = File(System.getProperty("previewData") ?: "../previewdata")
        val r = Osrm.parseAll(File(data, "route.json").readText())[0]
        log.append("route ${r.length.toInt()} m, ${r.duration.toInt()} s by OSRM\n")
        var last: Double? = null; var worst = 0.0; var got = 0
        for (k in 0..6) {
            val from = r.length * k / 8
            val res = TomTomTime.evaluate(r, from) ?: run { log.append("from ${from.toInt()} m: no answer\n"); null } ?: continue
            got++
            val left = r.length - from
            // per metre, so points along the way compare
            val spm = res.seconds / left
            log.append("from ${from.toInt()} m: TomTom ${res.seconds} s for ${res.meters} m (our ${left.toInt()} m left) · ${"%.3f".format(spm)} s/m\n")
            last?.let { worst = maxOf(worst, Math.abs(spm / it - 1)) }
            last = spm
        }
        log.append("biggest change between neighbouring points: ${"%.0f".format(worst * 100)}%\n")
        File(out, "etatomtom.txt").writeText(log.toString())
        assertTrue("TomTom answered", got >= 4)
        assertTrue("TomTom's time per metre jumped ${"%.0f".format(worst * 100)}% between neighbouring points", worst < 0.5)
    }
}
