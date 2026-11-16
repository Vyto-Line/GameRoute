package gr.gamemap

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.floor

/**
 * Offline maps: the map (and the places, for search) of an area or a whole region downloaded
 * to the phone, so it shows without signal and without using data. Every trip also keeps the
 * map along its route. Settings: Wi-Fi only, automatic updates, the map along routes.
 */
object Offline {
    class Area(val name: String, val center: LatLng, val km: Int, val tiles: Int, val bytes: Long, val at: Long,
               /** A region's box (south, west, north, east), or null: [km] around [center]. */
               val box: DoubleArray? = null)

    /** Greek regions (and Cyprus), ready to download whole. */
    class Region(val id: String, val en: String, val el: String, val s: Double, val w: Double, val n: Double, val e: Double) {
        val name get() = if (L.greek) el else en
        val box get() = doubleArrayOf(s, w, n, e)
    }

    val regions = listOf(
        Region("attica", "Attica (Athens, Piraeus)", "Αττική (Αθήνα, Πειραιάς)", 37.60, 23.30, 38.35, 24.10),
        Region("cmacedonia", "Central Macedonia (Thessaloniki, Chalkidiki)", "Κεντρική Μακεδονία (Θεσσαλονίκη, Χαλκιδική)", 39.95, 22.20, 41.30, 24.10),
        Region("peloponnese", "Peloponnese", "Πελοπόννησος", 36.35, 21.10, 38.30, 23.50),
        Region("crete", "Crete", "Κρήτη", 34.80, 23.45, 35.75, 26.35),
        Region("cgreece", "Central Greece & Evia", "Στερεά Ελλάδα & Εύβοια", 38.15, 21.30, 39.30, 24.65),
        Region("thessaly", "Thessaly (Larissa, Volos)", "Θεσσαλία (Λάρισα, Βόλος)", 38.85, 21.20, 40.10, 23.40),
        Region("wgreece", "Western Greece (Patras)", "Δυτική Ελλάδα (Πάτρα)", 37.60, 20.90, 39.20, 22.20),
        Region("epirus", "Epirus (Ioannina)", "Ήπειρος (Ιωάννινα)", 38.90, 19.90, 40.40, 21.40),
        Region("wmacedonia", "Western Macedonia", "Δυτική Μακεδονία", 39.90, 20.90, 40.95, 22.30),
        Region("emacedonia", "Eastern Macedonia & Thrace", "Ανατολική Μακεδονία & Θράκη", 40.60, 23.60, 41.75, 26.65),
        Region("ionian", "Ionian Islands", "Ιόνια Νησιά", 37.65, 19.60, 39.90, 21.00),
        Region("naegean", "North Aegean", "Βόρειο Αιγαίο", 37.55, 24.90, 40.05, 27.10),
        Region("saegean", "South Aegean (Cyclades, Dodecanese)", "Νότιο Αιγαίο (Κυκλάδες, Δωδεκάνησα)", 35.30, 24.30, 37.70, 28.30),
        Region("cyprus", "Cyprus", "Κύπρος", 34.50, 32.20, 35.75, 34.65),
    )

    // ---- settings
    var wifiOnly: Boolean
        get() = prefs().getBoolean("offlineWifi", true)
        set(v) { prefs().edit().putBoolean("offlineWifi", v).apply(); changed.fire() }
    var autoUpdate: Boolean
        get() = prefs().getBoolean("offlineUpdate", true)
        set(v) { prefs().edit().putBoolean("offlineUpdate", v).apply(); changed.fire() }
    var alongRoutes: Boolean
        get() = prefs().getBoolean("offlineAlong", true)
        set(v) { prefs().edit().putBoolean("offlineAlong", v).apply(); changed.fire() }

    /** On Wi-Fi (or an unmetered network) right now. */
    fun onWifi(): Boolean = try {
        val cm = appContext.getSystemService(android.net.ConnectivityManager::class.java)
        cm.isActiveNetworkMetered.not()
    } catch (e: Exception) { false }

    /** An old downloaded square may be refreshed now (automatic updates on, and Wi-Fi if asked). */
    fun mayRefresh(): Boolean = ::appContext.isInitialized && autoUpdate && (!wifiOnly || onWifi())

    /** Offline routing ready (the road file downloaded). */
    fun routingReady(): Boolean = OfflineRouter.present

    /** Connected to the internet right now (a network that has been checked to work). */
    fun online(): Boolean = try {
        val cm = appContext.getSystemService(android.net.ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        caps != null && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    } catch (e: Exception) { true }

    /** The road file's download size (from its info file), once known. */
    @Volatile var roadsSize = 0L

    fun loadRoadsInfo() {
        if (roadsSize > 0) return
        Thread {
            try { roadsSize = org.json.JSONObject(Http.get(OfflineRouter.INFO, timeout = 8000)).optLong("gz"); changed.fire() } catch (_: Exception) {}
        }.apply { isDaemon = true }.start()
    }

    /** Downloads the road file (offline routing), on Wi-Fi if asked. */
    fun downloadRoads() {
        if (OfflineRouter.downloading) return
        Thread {
            while (wifiOnly && !onWifi()) {
                if (!waitingForWifi) { waitingForWifi = true; changed.fire() }
                try { Thread.sleep(3000) } catch (_: InterruptedException) { return@Thread }
            }
            if (waitingForWifi) { waitingForWifi = false; changed.fire() }
            OfflineRouter.download { changed.fire() }
            changed.fire()
        }.apply { priority = Thread.MIN_PRIORITY }.start()
    }

    private lateinit var appContext: Context
    fun init(ctx: Context) { appContext = ctx.applicationContext }
    private fun prefs() = appContext.getSharedPreferences("gamemap", Context.MODE_PRIVATE)
    val changed = Signal()

    fun areas(): List<Area> = try {
        val a = JSONArray(prefs().getString("offlineAreas", "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Area(o.getString("n"), LatLng(o.getDouble("lat"), o.getDouble("lon")), o.getInt("km"), o.getInt("t"), o.getLong("b"), o.getLong("at"),
                o.optJSONArray("bb")?.let { b -> DoubleArray(4) { b.getDouble(it) } })
        }
    } catch (e: Exception) { emptyList() }

    private fun save(list: List<Area>) {
        val a = JSONArray()
        for (x in list) a.put(JSONObject().put("n", x.name).put("lat", x.center.lat).put("lon", x.center.lon)
            .put("km", x.km).put("t", x.tiles).put("b", x.bytes).put("at", x.at)
            .apply { x.box?.let { b -> put("bb", JSONArray().apply { b.forEach { v -> put(v) } }) } })
        prefs().edit().putString("offlineAreas", a.toString()).apply()
    }

    /** Map tiles of an area: every zoom from the whole region (8) down to street detail (14). */
    private fun tiles(center: LatLng, km: Int): List<Triple<Int, Int, Int>> {
        val dLat = km / 111.0
        val dLon = km / (111.0 * Math.cos(Math.toRadians(center.lat)))
        val out = ArrayList<Triple<Int, Int, Int>>()
        for (z in 8..VectorSource.MAX_Z) {
            val n = 1 shl z
            val x0 = floor(Geo.x(center.lon - dLon, 1.0) * n).toInt(); val x1 = floor(Geo.x(center.lon + dLon, 1.0) * n).toInt()
            val y0 = floor(Geo.y(center.lat + dLat, 1.0) * n).toInt(); val y1 = floor(Geo.y(center.lat - dLat, 1.0) * n).toInt()
            for (y in y0..y1) for (x in x0..x1) out += Triple(z, x, y)
        }
        return out
    }

    /** Map tiles of a box (south, west, north, east), zoom 8 to 14. */
    private fun tiles(b: DoubleArray): List<Triple<Int, Int, Int>> {
        val out = ArrayList<Triple<Int, Int, Int>>()
        for (z in 8..VectorSource.MAX_Z) {
            val n = 1 shl z
            val x0 = floor(Geo.x(b[1], 1.0) * n).toInt(); val x1 = floor(Geo.x(b[3], 1.0) * n).toInt()
            val y0 = floor(Geo.y(b[2], 1.0) * n).toInt(); val y1 = floor(Geo.y(b[0], 1.0) * n).toInt()
            for (y in y0..y1) for (x in x0..x1) out += Triple(z, x, y)
        }
        return out
    }

    private fun tilesOf(a: Area) = a.box?.let { tiles(it) } ?: tiles(a.center, a.km)

    fun tileCount(center: LatLng, km: Int) = tiles(center, km).size
    fun tileCount(r: Region) = tiles(r.box).size

    /** A rough size: towns are big, sea and mountains tiny. */
    fun estimate(tiles: Int) = tiles * 28_000L

    /** The places squares (0.1°) of a box, for search without signal. */
    private fun placeKeys(b: DoubleArray): List<String> {
        val out = ArrayList<String>()
        for (y in Math.floor(b[0] * 10).toInt()..Math.floor(b[2] * 10).toInt())
            for (x in Math.floor(b[1] * 10).toInt()..Math.floor(b[3] * 10).toInt()) out += "${y}_$x"
        return out
    }

    fun downloaded(r: Region) = areas().firstOrNull { it.name == r.id || it.name == r.en || it.name == r.el }

    /** Downloads a whole region: its map and its places. */
    fun download(r: Region) = start(r.id, tiles(r.box), placeKeys(r.box), LatLng((r.s + r.n) / 2, (r.w + r.e) / 2), 0, r.box)

    /**
     * The map along a route (zoom 12 to 14, the squares it passes and their neighbours), so a trip
     * keeps its map when the signal goes. Kept in the cache; nothing to manage.
     */
    fun saveAlong(r: RouteOption) {
        if (!::appContext.isInitialized || !alongRoutes || r.points.size < 2) return
        Thread {
            val want = LinkedHashSet<Triple<Int, Int, Int>>()
            var next = 0.0
            for (i in r.points.indices) {
                if (r.cum[i] < next && i != r.points.size - 1) continue
                next = r.cum[i] + 300
                val p = r.points[i]
                for (z in 12..VectorSource.MAX_Z) {
                    val n = 1 shl z
                    val x = floor(Geo.x(p.lon, 1.0) * n).toInt(); val y = floor(Geo.y(p.lat, 1.0) * n).toInt()
                    val ring = if (z == VectorSource.MAX_Z) 1 else 0
                    for (dy in -ring..ring) for (dx in -ring..ring) want += Triple(z, x + dx, y + dy)
                }
                if (want.size > 3000) break // a very long trip: the first part (the rest as it comes)
            }
            for ((z, x, y) in want) try { VectorSource.prefetch(z, x, y) } catch (_: Exception) {}
        }.apply { priority = Thread.MIN_PRIORITY; isDaemon = true }.start()
    }

    // ---- downloading (one at a time, two connections; survives leaving the screen)
    @Volatile var downloading: String? = null
        private set
    val done = AtomicInteger(0)
    @Volatile var total = 0
        private set
    @Volatile var cancelled = false

    fun download(name: String, center: LatLng, km: Int) = start(name, tiles(center, km), placeKeys(
        doubleArrayOf(center.lat - km / 111.0, center.lon - km / (111.0 * Math.cos(Math.toRadians(center.lat))),
            center.lat + km / 111.0, center.lon + km / (111.0 * Math.cos(Math.toRadians(center.lat))))), center, km, null)

    /** Waiting for Wi-Fi before going on (Wi-Fi only is on). */
    @Volatile var waitingForWifi = false
        private set

    private fun start(name: String, list: List<Triple<Int, Int, Int>>, places: List<String>, center: LatLng, km: Int, box: DoubleArray?) {
        if (downloading != null) return
        downloading = name; total = list.size + places.size; done.set(0); cancelled = false
        changed.fire()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(4, Bg.factory("offline"))
        Thread {
            var failed = 0
            fun waitWifi(): Boolean {
                while (wifiOnly && !onWifi() && !cancelled) {
                    if (!waitingForWifi) { waitingForWifi = true; changed.fire() }
                    try { Thread.sleep(3000) } catch (_: InterruptedException) { return false }
                }
                if (waitingForWifi) { waitingForWifi = false; changed.fire() }
                return !cancelled
            }
            val tasks = list.map { (z, x, y) ->
                pool.submit {
                    if (!cancelled && waitWifi()) { if (!VectorSource.saveOffline(z, x, y)) failed++ }
                    if (done.incrementAndGet() % 20 == 0) changed.fire()
                }
            }
            tasks.forEach { try { it.get() } catch (_: Exception) {} }
            pool.shutdown()
            // the places too, so search works without signal
            for (k in places) {
                if (cancelled || !waitWifi()) break
                try { OpenPlaces.saveOffline(k) } catch (_: Exception) {}
                if (done.incrementAndGet() % 20 == 0) changed.fire()
            }
            if (!cancelled) {
                val bytes = list.sumOf { (z, x, y) -> File(VectorSource.offlineDir, "$z/$x/$y.pbf").length() }
                save(areas().filterNot { it.name == name } + Area(name, center, km, list.size - failed, bytes, System.currentTimeMillis(), box))
            }
            downloading = null; waitingForWifi = false
            changed.fire()
        }.apply { priority = Thread.MIN_PRIORITY }.start()
    }

    /** Space used by downloaded maps, and free on the phone. */
    fun usedBytes(): Long = areas().sumOf { it.bytes }
    fun freeBytes(): Long = try { appContext.filesDir.usableSpace } catch (e: Exception) { 0L }

    /** Deletes an area's map (tiles other downloaded areas still use are kept). */
    fun delete(a: Area) {
        val others = areas().filter { it.name != a.name }
        val keep = HashSet<String>()
        for (o in others) for ((z, x, y) in tilesOf(o)) keep += "$z/$x/$y"
        for ((z, x, y) in tilesOf(a)) if ("$z/$x/$y" !in keep) File(VectorSource.offlineDir, "$z/$x/$y.pbf").delete()
        save(others)
        changed.fire()
    }

    fun size(bytes: Long) = when {
        bytes >= 1_000_000_000 -> "${"%.1f".format(bytes / 1_000_000_000.0)} GB"
        bytes >= 1_000_000 -> "${"%.0f".format(bytes / 1_000_000.0)} MB"
        else -> "${bytes / 1000} KB"
    }
}
