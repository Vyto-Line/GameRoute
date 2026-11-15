package gr.gamemap

import android.location.Location
import androidx.car.app.OnDoneCallback
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.navigation.model.RoutePreviewNavigationTemplate
import androidx.car.app.testing.ScreenController
import androidx.car.app.testing.TestCarContext
import androidx.lifecycle.Lifecycle
import gr.gamemap.car.FriendsCarScreen
import gr.gamemap.car.RoutePreviewScreen
import gr.gamemap.car.SearchScreen
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper
import java.io.File

/**
 * Android Auto: car search finds places (Greek and Latin), planned trips get several routes, and
 * each route row shows its toll price; the car's Friends screen builds.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class CarAutoTest {
    private val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
    private val log = StringBuilder()

    private fun text(r: Row) = (listOf(r.title?.toCharSequence()?.toString().orEmpty()) + r.texts.map { it.toCharSequence().toString() }).joinToString("  |  ")

    private fun waitFor(ms: Long, done: () -> Boolean) {
        val until = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < until) {
            ShadowLooper.idleMainLooper()
            if (done()) return
            Thread.sleep(100)
        }
    }

    @Test
    fun car() {
        val app = RuntimeEnvironment.getApplication()
        Traffic.init(app)
        Tolls.loadForTest(Http.get("https://raw.githubusercontent.com/Vyto-Line/GameRoute/data/tolls/GR.json", timeout = 20000))
        val athens = LatLng(37.9755, 23.7348)
        LocationTracker.onLocationChanged(Location("gps").apply { latitude = athens.lat; longitude = athens.lon; accuracy = 5f; time = System.currentTimeMillis() })
        ShadowLooper.idleMainLooper()

        // ---- 1) routes: how many choices, with tolls
        log.append("== Routes (Router.routes, alternatives) ==\n")
        var multi = 0
        for ((name, to) in listOf(
            "Athens → Nafplio" to LatLng(37.5675, 22.8016),
            "Athens → Patras" to LatLng(38.2466, 21.7346),
            "Athens → Glyfada" to LatLng(37.8650, 23.7530),
            "Athens → Kifisia" to LatLng(38.0740, 23.8110),
            "Athens → Lamia" to LatLng(38.8990, 22.4340),
        )) {
            val osrm = try { Osrm.routes(athens, to, alternatives = true).size } catch (e: Exception) { -1 }
            val t0 = System.nanoTime()
            val r = try { Router.routes(athens, to, alternatives = true).also { Tolls.annotate(it) } } catch (e: Exception) { log.append("$name: FAILED $e\n"); continue }
            val ms = (System.nanoTime() - t0) / 1_000_000
            if (r.size > 1) multi++
            log.append("$name: ${r.size} routes (OSRM alone: $osrm) in $ms ms\n")
            r.forEach { o ->
                log.append("    ${(o.distance / 1000).toInt()} km, ${(o.duration / 60).toInt()} min, via ${o.via}, tolls ${Tolls.money(o.tollCost)} (${o.tolls.size} stations)\n")
            }
            for (i in r.indices) for (j in r.indices) if (i < j) log.append("    overlap $i/$j: ${"%.2f".format(routeOverlap(r[i], r[j]))}\n")
            Thread.sleep(300)
        }
        assertTrue("several routes for most trips", multi >= 3)

        // ---- 2) the car's route preview rows (time · toll price, distance · via)
        val ctx = TestCarContext.createCarContext(app)
        // a car on Car App API 6 (the handshake a real car makes)
        androidx.car.app.CarContext::class.java.getDeclaredMethod("updateHandshakeInfo", androidx.car.app.HandshakeInfo::class.java)
            .apply { isAccessible = true }.invoke(ctx, androidx.car.app.HandshakeInfo("com.google.android.projection.gearhead", 6))
        val nafplio = Place("Nafplio", "Argolida", LatLng(37.5675, 22.8016))
        val opts = Router.routes(athens, nafplio.pos, alternatives = true).also { Tolls.annotate(it) }
        opts.forEach { it.timingFailed = true }
        NavEngine.debugPlan(nafplio, opts)
        val preview = RoutePreviewScreen(ctx)
        ScreenController(preview).moveToState(Lifecycle.State.RESUMED)
        val pt = preview.onGetTemplate() as RoutePreviewNavigationTemplate
        log.append("\n== Car route preview ==\n")
        val rows = pt.itemList!!.items.map { it as Row }
        rows.forEach { log.append("  " + text(it) + "\n") }
        assertTrue("toll price in the car rows", rows.any { text(it).contains("€") })
        NavEngine.cancelPlan()

        // ---- 3) the car search
        log.append("\n== Car search ==\n")
        for (q in listOf("ναυπλιο", "sounio", "Ακρόπολη", "Glyfada", "κορινθος")) {
            val s = SearchScreen(ctx)
            ScreenController(s).moveToState(Lifecycle.State.RESUMED)
            val st = s.onGetTemplate() as SearchTemplate
            val t0 = System.currentTimeMillis()
            st.searchCallbackDelegate.sendSearchSubmitted(q, object : OnDoneCallback {})
            var firstAt = 0L
            var lastRows: List<Row> = emptyList()
            waitFor(15_000) {
                val t = s.onGetTemplate() as SearchTemplate
                val list = if (t.isLoading) emptyList() else t.itemList!!.items.map { it as Row }
                if (list.isNotEmpty() && firstAt == 0L) firstAt = System.currentTimeMillis() - t0
                lastRows = list
                // finished: not loading, and the search services have answered (find took over)
                !t.isLoading && list.isNotEmpty() && System.currentTimeMillis() - t0 > 2500
            }
            log.append("'$q': first rows after $firstAt ms\n")
            lastRows.take(4).forEach { log.append("    " + text(it) + "\n") }
            assertTrue("car search '$q' shows results", lastRows.isNotEmpty())
        }

        // ---- 4) typing fast: only the last search counts, and it still answers
        val s = SearchScreen(ctx)
        ScreenController(s).moveToState(Lifecycle.State.RESUMED)
        val st = s.onGetTemplate() as SearchTemplate
        val t0 = System.currentTimeMillis()
        for (part in listOf("na", "naf", "nafp", "nafpl", "nafpli", "nafplio")) {
            st.searchCallbackDelegate.sendSearchTextChanged(part, object : OnDoneCallback {})
            ShadowLooper.idleMainLooper(); Thread.sleep(120)
        }
        var typedRows: List<Row> = emptyList()
        waitFor(15_000) {
            val t = s.onGetTemplate() as SearchTemplate
            typedRows = if (t.isLoading) emptyList() else t.itemList!!.items.map { it as Row }
            typedRows.any { text(it).contains("Nafpli", true) || text(it).contains("Ναύπλι") }
        }
        log.append("\ntyping 'nafplio' letter by letter: ${System.currentTimeMillis() - t0} ms\n")
        typedRows.take(3).forEach { log.append("    " + text(it) + "\n") }
        assertTrue("typed search finds Nafplio", typedRows.isNotEmpty())

        // ---- 5) Friends in the car (no friends here: the empty message)
        val f = FriendsCarScreen(ctx)
        ScreenController(f).moveToState(Lifecycle.State.RESUMED)
        val ft = f.onGetTemplate() as ListTemplate
        log.append("\n== Car friends ==\n  ${ft.title?.toCharSequence()} · ${ft.singleList?.items?.size} rows · ${ft.singleList?.noItemsMessage?.toCharSequence()}\n")

        File(out, "car_auto.txt").writeText(log.toString())
    }
}
