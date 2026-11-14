package gr.gamemap

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.floor

/**
 * Things on the road ahead: fixed speed cameras (OpenStreetMap), live incidents (TomTom) and
 * reports from other GameMap drivers (police, hazards… kept in a small Firebase database).
 */
object Alerts {
    val changed = Signal()
    private lateinit var appContext: Context
    private val net = Bg.pool("alerts", 2)
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    fun init(ctx: Context) {
        appContext = ctx.applicationContext
    }

    private fun prefs() = appContext.getSharedPreferences("gamemap", Context.MODE_PRIVATE)
    private val ready get() = ::appContext.isInitialized

    var camerasOn: Boolean
        get() = ready && prefs().getBoolean("alertCameras", true)
        set(v) { prefs().edit().putBoolean("alertCameras", v).apply(); changed.fire() }
    var incidentsOn: Boolean
        get() = ready && prefs().getBoolean("alertIncidents", true)
        set(v) { prefs().edit().putBoolean("alertIncidents", v).apply(); changed.fire() }

    // =============================================================== speed cameras (OSM)

    class Camera(val pos: LatLng, val maxspeed: Int?, val direction: Float?, val kind: String = "speed") {
        val redLight get() = kind == "redlight"
        val title get() = when (kind) { "redlight" -> "Red light camera"; "average" -> "Average speed check"; else -> "Speed camera" }
        val glyph get() = if (redLight) "🚦" else "📷"
    }

    var redLightOn: Boolean
        get() = ready && prefs().getBoolean("alertRedLight", true)
        set(v) { prefs().edit().putBoolean("alertRedLight", v).apply(); changed.fire() }

    private val cameraCells = ConcurrentHashMap<String, List<Camera>>()
    private val cellBusy = ConcurrentHashMap.newKeySet<String>()
    private val cellFailedAt = ConcurrentHashMap<String, Long>()
    val overpass = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
    )

    private fun cellOf(p: LatLng) = "${floor(p.lat * 2).toInt()}_${floor(p.lon * 2).toInt()}"

    /**
     * Whole-country camera lists, made weekly from OpenStreetMap by GameMap's own GitHub job
     * (Overpass is too slow and busy to ask from a phone). Countries without a list fall back to
     * asking Overpass for the half-degree square around.
     */
    private const val DATA = "https://raw.githubusercontent.com/Vyto-Line/GameRoute/data/cameras"
    private val national = ConcurrentHashMap<String, List<Camera>>()
    private val countryOfCell = ConcurrentHashMap<String, String>()
    @Volatile private var covered: Set<String>? = null
    @Volatile private var coveredAt = 0L
    private val loading = ConcurrentHashMap.newKeySet<String>()

    // ---- cameras the driver marked (kept on this phone only) ----

    @Volatile private var mine: List<Camera>? = null

    fun myCameras(): List<Camera> {
        mine?.let { return it }
        if (!ready) return emptyList()
        val list = try {
            val a = JSONArray(prefs().getString("myCameras", "[]"))
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                Camera(LatLng(o.getDouble("lat"), o.getDouble("lon")), null,
                    if (o.has("d")) o.getDouble("d").toFloat() else null, o.optString("k", "speed"))
            }
        } catch (e: Exception) { emptyList() }
        mine = list
        return list
    }

    private fun saveMine(list: List<Camera>) {
        val a = JSONArray()
        for (c in list) a.put(JSONObject().put("lat", c.pos.lat).put("lon", c.pos.lon).put("k", c.kind).apply { c.direction?.let { put("d", it.toDouble()) } })
        prefs().edit().putString("myCameras", a.toString()).apply()
        mine = list
        changed.fire()
    }

    /** "There's a camera here": marks it where the car is now, facing the way it drives. */
    fun markCamera(kind: String): Boolean {
        val p = LocationTracker.position ?: return false
        val heading = LocationTracker.heading.takeIf { LocationTracker.speedMps > 2f }
        saveMine(myCameras().filterNot { Geo.distance(it.pos, p) < 30 } + Camera(p, null, heading, kind))
        return true
    }

    fun clearMyCameras() = saveMine(emptyList())

    /** Cameras around [p] (the caller filters by distance). Never waits for the network. */
    fun cameras(p: LatLng): List<Camera> {
        if (!camerasOn) return emptyList()
        val all = camerasRaw(p) + myCameras()
        return if (redLightOn) all else all.filter { !it.redLight }
    }

    private fun camerasRaw(p: LatLng): List<Camera> {
        val idx = covered
        if (idx == null || System.currentTimeMillis() - coveredAt > 86_400_000) { loadIndex(); if (idx == null) return emptyList() }
        val deg = "${floor(p.lat).toInt()}_${floor(p.lon).toInt()}"
        val cc = countryOfCell[deg]
        if (cc == null) {
            if (loading.add("cc:$deg")) net.execute {
                try { Fuel.country(p)?.let { countryOfCell[deg] = it; changed.fire() } } finally { loading.remove("cc:$deg") }
            }
            return emptyList()
        }
        if (cc in idx!!) return national[cc] ?: run { loadNational(cc); emptyList() }
        return cellCameras(p)
    }

    private fun loadIndex() {
        if (!loading.add("index")) return
        net.execute {
            val file = File(appContext.cacheDir, "cameras/index.json")
            try {
                val text = try { Http.get("$DATA/index.json", timeout = 8000).also { file.parentFile?.mkdirs(); file.writeText(it) } }
                    catch (e: Exception) { if (file.exists()) file.readText() else null }
                covered = text?.let { JSONObject(it).keys().asSequence().toSet() } ?: emptySet()
                coveredAt = if (text != null) System.currentTimeMillis() else System.currentTimeMillis() - 86_400_000 + 600_000
                changed.fire()
            } catch (e: Exception) {
                covered = covered ?: emptySet()
            } finally { loading.remove("index") }
        }
    }

    private fun loadNational(cc: String) {
        if (!loading.add("cc-data:$cc")) return
        net.execute {
            val file = File(appContext.cacheDir, "cameras/$cc.json")
            try {
                val fresh = file.exists() && System.currentTimeMillis() - file.lastModified() < 7 * 86_400_000L
                val text = if (fresh) file.readText() else try {
                    Http.get("$DATA/$cc.json", timeout = 15000).also { file.parentFile?.mkdirs(); file.writeText(it) }
                } catch (e: Exception) { if (file.exists()) file.readText() else null }
                if (text != null) {
                    val a = JSONArray(text)
                    national[cc] = (0 until a.length()).map { i ->
                        val o = a.getJSONObject(i)
                        Camera(LatLng(o.getDouble("lat"), o.getDouble("lon")),
                            o.optInt("s").takeIf { it > 0 }, if (o.has("d")) o.optDouble("d").toFloat() else null,
                            o.optString("k", "speed"))
                    }
                    changed.fire()
                }
            } catch (_: Exception) {
            } finally { loading.remove("cc-data:$cc") }
        }
    }

    /** Fallback: cameras in the half-degree square around [p] from Overpass (kept 3 days). */
    private fun cellCameras(p: LatLng): List<Camera> {
        val cell = cellOf(p)
        cameraCells[cell]?.let { return it }
        val file = File(appContext.cacheDir, "cameras/$cell.json")
        if (file.exists() && System.currentTimeMillis() - file.lastModified() < 3 * 86_400_000L) {
            try {
                val list = parseCameras(file.readText()); cameraCells[cell] = list; return list
            } catch (_: Exception) {}
        }
        if ((cellFailedAt[cell] ?: 0) > System.currentTimeMillis() - 600_000 || !cellBusy.add(cell)) return emptyList()
        net.execute {
            try {
                val (a, b) = cell.split("_").map { it.toInt() }
                val s = a / 2.0; val w = b / 2.0
                val box = "($s,$w,${s + 0.5},${w + 0.5})"
                val q = "[out:json][timeout:60];(node[\"highway\"=\"speed_camera\"]$box;" +
                    "node[\"enforcement\"~\"^(maxspeed|traffic_signals|average_speed)$\"]$box;);out;"
                var text: String? = null
                for (ep in overpass) {
                    text = try { Http.post(ep, "data=" + URLEncoder.encode(q, "UTF-8"), "application/x-www-form-urlencoded", timeout = 70_000) } catch (e: Exception) { null }
                    if (text != null && text.trimStart().startsWith("{")) break else text = null
                }
                if (text == null) { cellFailedAt[cell] = System.currentTimeMillis(); return@execute }
                val list = parseCameras(text)
                file.parentFile?.mkdirs(); file.writeText(text)
                cameraCells[cell] = list
                changed.fire()
            } catch (e: Exception) {
                cellFailedAt[cell] = System.currentTimeMillis()
            } finally {
                cellBusy.remove(cell)
            }
        }
        return emptyList()
    }

    private fun parseCameras(json: String): List<Camera> {
        val e = JSONObject(json).getJSONArray("elements")
        return (0 until e.length()).mapNotNull { i ->
            val o = e.getJSONObject(i)
            if (!o.has("lat")) return@mapNotNull null
            val tags = o.optJSONObject("tags")
            Camera(
                LatLng(o.getDouble("lat"), o.getDouble("lon")),
                tags?.optString("maxspeed")?.takeWhile { it.isDigit() }?.toIntOrNull(),
                tags?.optString("direction")?.toFloatOrNull(),
                when (tags?.optString("enforcement")) { "traffic_signals" -> "redlight"; "average_speed" -> "average"; else -> "speed" },
            )
        }
    }

    // =============================================================== incidents (TomTom)

    class Incident(val pos: LatLng, val category: Int, val text: String, val delay: Int)

    @Volatile var incidents: List<Incident> = emptyList()
        private set
    private var incCenter: LatLng? = null
    private var incAt = 0L
    @Volatile private var incBusy = false

    private val showCategories = setOf(1, 3, 5, 7, 8, 9, 11, 14)

    fun incidentsNear(p: LatLng): List<Incident> {
        if (!incidentsOn || !Traffic.active) return emptyList()
        val c = incCenter
        val stale = System.currentTimeMillis() - incAt > (if (NavEngine.active) 120_000 else 300_000)
        if (!incBusy && (c == null || Geo.distance(c, p) > 4000 || stale)) {
            incBusy = true
            net.execute {
                try {
                    val d = 0.09
                    val fields = "{incidents{type,geometry{type,coordinates},properties{iconCategory,magnitudeOfDelay,events{description},delay}}}"
                    val url = "https://api.tomtom.com/traffic/services/5/incidentDetails?key=${Traffic.key}" +
                        "&bbox=${p.lon - d},${p.lat - d * 0.8},${p.lon + d},${p.lat + d * 0.8}" +
                        "&fields=${URLEncoder.encode(fields, "UTF-8")}&language=en-GB&timeValidityFilter=present"
                    val arr = JSONObject(Http.get(url)).getJSONArray("incidents")
                    val out = ArrayList<Incident>()
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val pr = o.getJSONObject("properties")
                        val cat = pr.optInt("iconCategory")
                        if (cat !in showCategories) continue
                        val g = o.getJSONObject("geometry")
                        val co = g.getJSONArray("coordinates")
                        val first = if (g.optString("type") == "Point") co else co.getJSONArray(0)
                        val text = pr.optJSONArray("events")?.optJSONObject(0)?.optString("description") ?: ""
                        out += Incident(LatLng(first.getDouble(1), first.getDouble(0)), cat, text, pr.optInt("delay"))
                    }
                    incidents = out
                    incCenter = p; incAt = System.currentTimeMillis()
                    changed.fire()
                } catch (_: Exception) {
                    incAt = System.currentTimeMillis() - 60_000 // try again in a minute
                } finally {
                    incBusy = false
                }
            }
        }
        return incidents
    }

    fun incidentGlyph(cat: Int) = when (cat) {
        1 -> "💥"; 8 -> "⛔"; 9 -> "🚧"; 7 -> "↯"; 14 -> "🚗"; 5 -> "❄"; 11 -> "🌊"; else -> "⚠"
    }

    fun incidentName(cat: Int) = when (cat) {
        1 -> "Accident"; 8 -> "Road closed"; 9 -> "Roadworks"; 7 -> "Lane closed"; 14 -> "Broken-down vehicle"
        5 -> "Ice"; 11 -> "Flooding"; else -> "Hazard"
    }

    // =============================================================== community reports (Firebase)

    class ReportType(val key: String, val title: String, val glyph: String)

    val reportTypes = listOf(
        ReportType("police", "Police", "👮"),
        ReportType("accident", "Accident", "💥"),
        ReportType("hazard", "Hazard", "⚠"),
        ReportType("jam", "Traffic jam", "🚦"),
        ReportType("closed", "Road closed", "⛔"),
        ReportType("camera", "Speed camera", "📷"),
    )

    fun typeOf(key: String) = reportTypes.firstOrNull { it.key == key } ?: reportTypes[2]

    class Report(val id: String, val type: String, val pos: LatLng, val heading: Float?, val at: Long, val lastSeen: Long, val yes: Int, val no: Int)

    /** The reports database (Firebase Realtime Database URL), from Settings or built in. */
    val dbUrl: String
        get() = (if (ready) prefs().getString("reportsUrl", "") else "").orEmpty().ifBlank { BuildConfig.REPORTS_URL }.trimEnd('/')
    /** Shared road reports are switched off until GameRoute runs its own server. */
    const val REPORTS_ENABLED = false
    val reportsReady get() = REPORTS_ENABLED && dbUrl.startsWith("https://")

    fun setDbUrl(u: String) {
        prefs().edit().putString("reportsUrl", u.trim()).apply()
        reportsAt = 0
        changed.fire()
    }

    @Volatile var reports: List<Report> = emptyList()
        private set
    private var reportsAt = 0L
    @Volatile private var reportsBusy = false

    /** Live reports (last hour since someone saw them, not voted away). */
    fun reportsNear(p: LatLng): List<Report> {
        if (!reportsReady) return emptyList()
        if (!reportsBusy && System.currentTimeMillis() - reportsAt > 60_000) {
            reportsBusy = true
            net.execute {
                try {
                    val since = System.currentTimeMillis() - 3 * 3_600_000L
                    val url = "$dbUrl/reports.json?orderBy=%22t%22&startAt=$since"
                    val o = JSONObject(Http.get(url).let { if (it == "null") "{}" else it })
                    val out = ArrayList<Report>()
                    val now = System.currentTimeMillis()
                    for (id in o.keys()) {
                        val r = o.optJSONObject(id) ?: continue
                        val yes = r.optJSONObject("yes")
                        val no = r.optJSONObject("no")
                        var last = r.optLong("t")
                        yes?.keys()?.forEach { k -> last = maxOf(last, yes.optLong(k)) }
                        val ny = yes?.length() ?: 0; val nn = no?.length() ?: 0
                        if (now - last > 3_600_000L || nn >= ny + 2) continue
                        out += Report(
                            id, r.optString("type"), LatLng(r.optDouble("lat"), r.optDouble("lon")),
                            r.optDouble("h", Double.NaN).takeIf { !it.isNaN() }?.toFloat(), r.optLong("t"), last, ny, nn,
                        )
                    }
                    reports = out
                    reportsAt = System.currentTimeMillis()
                    changed.fire()
                } catch (_: Exception) {
                    reportsAt = System.currentTimeMillis() - 30_000
                } finally {
                    reportsBusy = false
                }
            }
        }
        return reports.filter { Geo.distance(it.pos, p) < 30_000 }
    }

    /** Sends a report at the current position. Calls back on the main thread with success. */
    fun report(type: String, done: (Boolean) -> Unit) {
        val p = LocationTracker.position
        if (!reportsReady || p == null) { done(false); return }
        val h = if (LocationTracker.speedMps > 2f) LocationTracker.heading else null
        net.execute {
            val ok = try {
                val body = JSONObject().put("type", type).put("lat", p.lat).put("lon", p.lon)
                    .put("t", JSONObject().put(".sv", "timestamp"))
                if (h != null) body.put("h", h.toDouble())
                Http.post("$dbUrl/reports.json", body.toString()); true
            } catch (e: Exception) { false }
            reportsAt = 0
            main.post { done(ok); if (ok) changed.fire() }
        }
    }

    /** "Still there?" answer. */
    fun vote(r: Report, stillThere: Boolean) {
        if (!reportsReady) return
        net.execute {
            try {
                Http.post("$dbUrl/reports/${r.id}/${if (stillThere) "yes" else "no"}.json", JSONObject().put(".sv", "timestamp").toString())
            } catch (_: Exception) {}
            reportsAt = 0
        }
    }

    // =============================================================== what's ahead

    class Ahead(val key: String, val label: String, val glyph: String, val dist: Double, val speech: String)

    var ahead: Ahead? = null
        private set
    /** A report we just drove past, to ask "still there?". */
    var askAbout: Report? = null
        private set
    private var askedAt = 0L
    private val announced = HashSet<String>()
    private val asked = HashSet<String>()

    fun clearAsk() { askAbout = null; changed.fire() }

    /** Called with each GPS fix. */
    fun onLocation(pos: LatLng, speed: Float, heading: Float) {
        if (!ready) return
        val items = ArrayList<Triple<String, LatLng, Pair<String, String>>>() // key, pos, (label, glyph)
        for (c in cameras(pos)) {
            if (Geo.distance(pos, c.pos) > 1200) continue
            val lbl = c.title + (c.maxspeed?.takeIf { !c.redLight }?.let { " · $it" } ?: "")
            items += Triple("cam:${c.pos.lat},${c.pos.lon}", c.pos, lbl to c.glyph)
        }
        for (i in incidentsNear(pos)) {
            if (Geo.distance(pos, i.pos) > 2500) continue
            items += Triple("inc:${i.pos.lat},${i.pos.lon}:${i.category}", i.pos, incidentName(i.category) to incidentGlyph(i.category))
        }
        val reps = reportsNear(pos)
        for (r in reps) {
            if (Geo.distance(pos, r.pos) > 2000) continue
            val t = typeOf(r.type)
            items += Triple("rep:${r.id}", r.pos, "${t.title} reported" to t.glyph)
        }
        var best: Ahead? = null
        val route = NavEngine.current
        // only the next ~1 km of the route matters (alerts count from 900 m): search that stretch,
        // not the whole trip for every camera, every second
        var lo = 0; var span = route?.points?.size ?: 0
        if (route != null && NavEngine.active) {
            fun at(m: Double): Int { var a = 0; var b = route.cum.size - 1; while (a < b) { val k = (a + b) / 2; if (route.cum[k] < m) a = k + 1 else b = k }; return a }
            lo = maxOf(0, at(NavEngine.along - 60) - 1); span = at(NavEngine.along + 1000) - lo + 2
        }
        for ((key, p, lg) in items) {
            val dist: Double = if (route != null && NavEngine.active) {
                val (a, idx) = route.alongOf(p, lo + 20, span)
                val off = Geo.distanceToSegment(p, route.points[idx], route.points[minOf(idx + 1, route.points.size - 1)])
                if (off > 40) continue
                a - NavEngine.along
            } else {
                if (speed < 4f) continue
                val bearing = Geo.bearing(pos, p)
                if (abs(Geo.angleDiff(heading, bearing)) > 25) continue
                Geo.distance(pos, p)
            }
            if (dist < 15 || dist > 900) continue
            if (best == null || dist < best.dist) best = Ahead(key, lg.first, lg.second, dist, lg.first)
        }
        val was = ahead?.key
        ahead = best
        best?.let { b ->
            if (b.key !in announced && b.dist < maxOf(300.0, speed * 15.0)) {
                announced += b.key
                val d = Phrases.distance(b.dist, Voice.lang)
                Voice.say(if (Voice.lang == Phrases.GREEK) "${greek(b.label)} σε $d" else "${b.label} in $d")
            }
        }
        // drove right past a report: ask whether it's still there
        if (askAbout == null && speed > 2f) {
            reps.firstOrNull { it.id !in asked && Geo.distance(pos, it.pos) < 45 }?.let {
                asked += it.id
                askAbout = it; askedAt = System.currentTimeMillis()
                main.postDelayed({ if (askAbout === it) { askAbout = null; changed.fire() } }, 10_000)
                changed.fire()
            }
        }
        if (was != ahead?.key) changed.fire()
    }

    internal fun debugInject(cams: List<Camera>, inc: List<Incident>, reps: List<Report>) {
        cams.firstOrNull()?.let { cameraCells[cellOf(it.pos)] = cams }
        incidents = inc; incCenter = inc.firstOrNull()?.pos ?: cams.firstOrNull()?.pos; incAt = System.currentTimeMillis()
        reports = reps; reportsAt = System.currentTimeMillis()
        changed.fire()
    }

    private fun greek(label: String) = when {
        label.startsWith("Speed camera") -> "Κάμερα ταχύτητας"
        label.startsWith("Red light camera") -> "Κάμερα φαναριού"
        label.startsWith("Average speed") -> "Έλεγχος μέσης ταχύτητας"
        label.startsWith("Police") -> "Αστυνομία"
        label.startsWith("Accident") -> "Ατύχημα"
        label.startsWith("Road closed") -> "Κλειστός δρόμος"
        label.startsWith("Roadworks") -> "Έργα"
        label.startsWith("Traffic jam") -> "Κίνηση"
        else -> "Κίνδυνος"
    }
}
