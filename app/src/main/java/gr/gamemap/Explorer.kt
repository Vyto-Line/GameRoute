package gr.gamemap

import android.content.Context
import android.os.SystemClock

/**
 * The game layer: landmarks to discover (museums, monuments, viewpoints… straight from the
 * OpenStreetMap data already on the map), XP, levels and the summary of each finished trip.
 */
object Explorer {
    val changed = Signal()

    class Landmark(val id: String, val name: String, val pos: LatLng, val kind: String)

    /** A finished trip, shown on the arrival card. */
    class Trip(
        val place: String,
        val arrivedAt: Long,
        val seconds: Int,
        val meters: Double,
        val savedSeconds: Int,
        val xp: Int,
        val landmarks: Int,
        val levelBefore: Int,
    ) {
        val avgKmh: Int get() = if (seconds > 0) (meters / seconds * 3.6).toInt() else 0
    }

    private lateinit var appContext: Context
    private val found = HashSet<String>()

    fun init(ctx: Context) {
        appContext = ctx.applicationContext
        found += prefs().getStringSet("landmarksFound", emptySet()) ?: emptySet()
    }

    private fun prefs() = appContext.getSharedPreferences("gamemap", Context.MODE_PRIVATE)

    // ---- trip history (newest first, last 200 trips) ----

    fun history(): List<Trip> {
        if (!::appContext.isInitialized) return emptyList()
        return try {
            val a = org.json.JSONArray(prefs().getString("tripHistory", "[]"))
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                Trip(o.getString("p"), o.getLong("t"), o.getInt("s"), o.getDouble("m"), o.optInt("v"), o.optInt("x"), o.optInt("l"), o.optInt("b"))
            }.sortedByDescending { it.arrivedAt }
        } catch (e: Exception) { emptyList() }
    }

    fun record(t: Trip) {
        if (!::appContext.isInitialized || t.meters < 200) return // not a trip, just a test
        val a = org.json.JSONArray()
        a.put(org.json.JSONObject().put("p", t.place).put("t", t.arrivedAt).put("s", t.seconds).put("m", t.meters)
            .put("v", t.savedSeconds).put("x", t.xp).put("l", t.landmarks).put("b", t.levelBefore))
        for (old in history().take(199)) a.put(org.json.JSONObject().put("p", old.place).put("t", old.arrivedAt).put("s", old.seconds)
            .put("m", old.meters).put("v", old.savedSeconds).put("x", old.xp).put("l", old.landmarks).put("b", old.levelBefore))
        prefs().edit().putString("tripHistory", a.toString()).apply()
    }

    fun clearHistory() { if (::appContext.isInitialized) prefs().edit().remove("tripHistory").apply() }

    // ------------------------------------------------------------------ XP & levels

    val xp: Int get() = if (::appContext.isInitialized) prefs().getInt("xp", 0) else 0
    val level: Int get() = levelFor(xp)
    val foundCount: Int get() = found.size

    /** Total XP needed to reach level [n] (1 → 0, 2 → 100, 3 → 300, 4 → 600 …). */
    fun need(n: Int) = 50 * (n - 1) * n

    fun levelFor(x: Int): Int {
        var l = 1
        while (x >= need(l + 1)) l++
        return l
    }

    /** XP into the current level and the size of the level. */
    fun progress(): Pair<Int, Int> {
        val l = level
        return (xp - need(l)) to (need(l + 1) - need(l))
    }

    fun addXp(n: Int) {
        if (!::appContext.isInitialized || n <= 0) return
        prefs().edit().putInt("xp", xp + n).apply()
        changed.fire()
    }

    fun isFound(l: Landmark) = l.id in found

    /** What finishing a trip is called in each theme. */
    fun completeTitle(t: Theme): String = when (t) {
        Theme.OPEN_WORLD -> gr.gamemap.L.t("Mission complete")
        Theme.NEON_NOIR -> gr.gamemap.L.t("Job done")
        Theme.NEON_RACER -> gr.gamemap.L.t("Race finished")
        Theme.BLOCKY -> gr.gamemap.L.t("Journey complete")
        Theme.PARCHMENT_QUEST -> gr.gamemap.L.t("Quest complete")
        Theme.LOOKOUT -> gr.gamemap.L.t("Destination reached")
        Theme.RETRO -> gr.gamemap.L.t("Stage clear!")
        Theme.TRAILBLAZER -> gr.gamemap.L.t("Area explored!")
        Theme.VIOLET_RAIN -> gr.gamemap.L.t("Through the rain")
    }

    // ------------------------------------------------------------------ landmarks

    /** The kinds of places worth collecting (not every plaque, gallery or small theatre). */
    private val kinds = setOf(
        "attraction", "museum", "castle", "monument", "stadium", "viewpoint",
        "archaeological_site", "ruins", "zoo", "aquarium", "theme_park", "fort", "lighthouse",
    )

    private var scanCenter: LatLng? = null
    private var scanAt = 0L
    private var scanned: List<Landmark> = emptyList()
    /** Bumps whenever the set of known or found landmarks changes (for redraw keys). */
    var version = 0
        private set

    /** Landmarks within [radius] metres of [center], from the map data that's loaded. */
    fun near(center: LatLng, radius: Double): List<Landmark> {
        val now = SystemClock.uptimeMillis()
        val last = scanCenter
        if (last == null || Geo.distance(last, center) > 250 || now - scanAt > 8000) {
            val fresh = scan(center)
            if (fresh.size != scanned.size) version++
            scanned = fresh; scanCenter = center; scanAt = now
        }
        return scanned.filter { Geo.distance(center, it.pos) <= radius }
    }

    private fun scan(center: LatLng): List<Landmark> {
        val z = VectorSource.MAX_Z
        val n = 1 shl z
        val cx = (Geo.x(center.lon, 1.0) * n).toInt()
        val cy = (Geo.y(center.lat, 1.0) * n).toInt()
        val out = LinkedHashMap<String, Landmark>()
        for (ty in cy - 1..cy + 1) for (tx in cx - 1..cx + 1) {
            val vt = VectorSource.peek(z, tx, ty) ?: continue
            for (f in vt.features) {
                if (f.layer != "poi" || f.type != 1) continue
                val name = f["name"]?.takeIf { it.isNotBlank() } ?: continue
                val kind = f["subclass"]?.takeIf { it in kinds } ?: f["class"]?.takeIf { it in kinds } ?: continue
                val pt = f.parts.firstOrNull() ?: continue
                if (pt.size < 2 || pt[0] < 0 || pt[1] < 0 || pt[0] > f.extent || pt[1] > f.extent) continue
                val pos = LatLng(
                    Geo.lat((ty + pt[1] / f.extent.toDouble()) / n, 1.0),
                    Geo.lon((tx + pt[0] / f.extent.toDouble()) / n, 1.0),
                )
                val id = "$name@${"%.4f".format(java.util.Locale.US, pos.lat)},${"%.4f".format(java.util.Locale.US, pos.lon)}"
                // one landmark per spot (big sites often come as several points close together)
                if (out.values.any { it.name == name && Geo.distance(it.pos, pos) < 150 }) continue
                out.putIfAbsent(id, Landmark(id, name, pos, kind))
            }
        }
        return out.values.toList()
    }

    var lastFound: Landmark? = null
        private set
    var lastFoundAt = 0L
        private set
    /** Landmarks found since the current trip started. */
    var tripFound = 0

    /** Called with every GPS fix: landmarks you pass close to count as discovered. */
    fun onLocation(pos: LatLng, speed: Float) {
        if (!::appContext.isInitialized) return
        val reach = if (speed > 5f) 120.0 else 70.0
        for (l in near(pos, 700.0)) {
            if (l.id !in found && Geo.distance(pos, l.pos) < reach) discover(l)
        }
    }

    private fun discover(l: Landmark) {
        found += l.id
        prefs().edit().putStringSet("landmarksFound", HashSet(found)).apply()
        tripFound++
        version++
        addXp(25)
        // driving: it waits until you stop (no banner, no voice at speed)
        if (Later.driving) { Later.add("🏛 " + gr.gamemap.L.t("Landmark discovered: ${l.name}") + " · +25 XP"); return }
        lastFound = l
        lastFoundAt = SystemClock.uptimeMillis()
        Voice.say(if (Voice.lang == Phrases.GREEK) "Νέο αξιοθέατο: ${l.name}" else "Landmark discovered: ${l.name}")
    }
}
