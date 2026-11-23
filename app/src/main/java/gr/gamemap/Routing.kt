package gr.gamemap

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToLong

/** One lane at a junction: the arrows painted on it and whether it leads where we're going. */
class Lane(val directions: List<String>, val valid: Boolean)

/** One maneuver of a route. */
class NavStep(
    val type: String,
    /** "left", "right", "slight left", "uturn", "straight", "arrive", ... */
    val direction: String,
    val road: String,
    val exit: Int,
    val pos: LatLng,
    val lanes: List<Lane>?,
) {
    /** Index of the route point where this maneuver happens. */
    var routeIndex = 0
    val text: String get() = Phrases.instruction(this, if (L.greek) Phrases.GREEK else Phrases.ENGLISH)
}

/** A stretch of a route, in metres from its start. */
class Stretch(val start: Double, val end: Double, val value: Int, val extra: Int = 0)

/** One way to drive somewhere, with traffic and speed limits once TomTom has looked at it. */
class RouteOption(
    val points: List<LatLng>,
    val steps: List<NavStep>,
    val distance: Double,
    /** OSRM's time without traffic. */
    val duration: Double,
    val via: String,
    val hasToll: Boolean = false,
    val hasMotorway: Boolean = false,
) {
    val cum = DoubleArray(points.size).also { c ->
        for (i in 1 until points.size) c[i] = c[i - 1] + Geo.distance(points[i - 1], points[i])
    }
    val length get() = cum.lastOrNull() ?: 0.0

    /** TomTom's time with traffic for the whole route (null until known). */
    var trafficSeconds: Int? = null
    /** TomTom couldn't time this route (show OSRM's time instead). */
    var timingFailed = false
    var delaySeconds = 0
    /** Traffic stretches: value = TomTom magnitude 1..4, extra = delay seconds. */
    var traffic: List<Stretch> = emptyList()
    /** Speed limits: value = km/h. */
    var limits: List<Stretch> = emptyList()
    /** Toll stations on the way (Greece), in order; [tollCost] adds them up. */
    var tolls: List<Tolls.OnRoute> = emptyList()
    val tollCost: Double get() = tolls.sumOf { it.price }

    /** Best time estimate in seconds. */
    val seconds: Double get() = trafficSeconds?.toDouble() ?: duration

    init {
        var from = 0
        for (s in steps) {
            var best = from; var bestD = Double.MAX_VALUE
            for (i in from until minOf(points.size, from + 3000)) {
                val d = Geo.distance(points[i], s.pos)
                if (d < bestD) { bestD = d; best = i }
                if (d < 1.0) break
            }
            s.routeIndex = best
            from = best
        }
    }

    /** How far along the route (metres) the point nearest to [p] is, searching from point [hint]. */
    fun alongOf(p: LatLng, hint: Int, window: Int = 400): Pair<Double, Int> {
        var best = hint; var bestD = Double.MAX_VALUE
        val lo = maxOf(0, hint - 20); val hi = minOf(points.size - 1, hint + window)
        for (i in lo until hi) {
            val d = Geo.distanceToSegment(p, points[i], points[i + 1])
            if (d < bestD) { bestD = d; best = i }
        }
        if (points.size < 2) return 0.0 to 0
        val along = cum[best] + Geo.distance(points[best], p).coerceAtMost(cum[best + 1] - cum[best])
        return along to best
    }

    /** True if the two routes share most of their way. */
    fun similarTo(o: RouteOption): Boolean {
        val sample = o.points.filterIndexed { i, _ -> i % maxOf(1, o.points.size / 40) == 0 }
        var near = 0; var hint = 0
        for (p in sample) {
            val (_, idx) = alongOf(p, hint, points.size)
            hint = idx
            if (Geo.distanceToSegment(p, points[idx], points[minOf(idx + 1, points.size - 1)]) < 35) near++
        }
        return near >= sample.size * 0.9
    }
}

/** The public OSRM router: geometry, maneuvers and lanes. */
object Osrm {
    /** Routes from [from] to [to], through the [via] stops in order. */
    fun routes(from: LatLng, to: LatLng, alternatives: Boolean, heading: Float? = null, reroute: Boolean = false, via: List<LatLng> = emptyList()): List<RouteOption> {
        val pts = listOf(from) + via + listOf(to)
        var url = "https://router.project-osrm.org/route/v1/driving/" +
            pts.joinToString(";") { "${it.lon},${it.lat}" } +
            "?overview=full&geometries=geojson&steps=true"
        // (OSRM offers alternatives only for a plain A to B trip)
        if (alternatives && via.isEmpty()) url += "&alternatives=3"
        // on a reroute: keep going the way the car faces, never a U-turn back to the old route
        if (heading != null) url += "&bearings=${heading.toInt().coerceIn(0, 359)},${if (reroute) 45 else 60}" + ";".repeat(pts.size - 1)
        if (reroute) url += "&continue_straight=true"
        return parseAll(Http.get(url, timeout = 8000))
    }

    fun parseAll(json: String): List<RouteOption> {
        val o = JSONObject(json)
        if (o.optString("code") != "Ok") throw RuntimeException(o.optString("code"))
        val arr = o.getJSONArray("routes")
        return (0 until arr.length()).map { parse(arr.getJSONObject(it)) }
    }

    private fun parse(r: JSONObject): RouteOption {
        val coords = r.getJSONObject("geometry").getJSONArray("coordinates")
        val pts = (0 until coords.length()).map {
            val c = coords.getJSONArray(it)
            LatLng(c.getDouble(1), c.getDouble(0))
        }
        val out = mutableListOf<NavStep>()
        val legs = r.getJSONArray("legs")
        var summary = ""
        var toll = false; var motorway = false
        for (l in 0 until legs.length()) {
            val leg = legs.getJSONObject(l)
            if (summary.isBlank()) summary = leg.optString("summary")
            val s = leg.getJSONArray("steps")
            for (i in 0 until s.length()) {
                val st = s.getJSONObject(i)
                val m = st.getJSONObject("maneuver")
                val loc = m.getJSONArray("location")
                var type = m.optString("type")
                val mod = m.optString("modifier", "straight")
                val lastLeg = l == legs.length() - 1
                if (type == "depart" && l > 0) continue // carrying on after a stop
                if (type == "arrive" && !lastLeg) type = "waypoint" // a stop on the way
                st.optJSONArray("intersections")?.let { ins ->
                    for (k in 0 until ins.length()) ins.getJSONObject(k).optJSONArray("classes")?.let { cl ->
                        for (q in 0 until cl.length()) when (cl.optString(q)) {
                            "toll" -> toll = true
                            "motorway" -> motorway = true
                        }
                    }
                }
                var lanes: List<Lane>? = null
                st.optJSONArray("intersections")?.optJSONObject(0)?.optJSONArray("lanes")?.let { la ->
                    lanes = (0 until la.length()).map { k ->
                        val lo = la.getJSONObject(k)
                        val ind = lo.optJSONArray("indications") ?: JSONArray()
                        Lane((0 until ind.length()).map { ind.getString(it) }, lo.optBoolean("valid"))
                    }
                }
                out += NavStep(
                    type = type,
                    direction = if (type == "arrive") "arrive" else if (type == "waypoint") "straight" else mod,
                    road = st.optString("name"),
                    exit = m.optInt("exit", 0),
                    pos = LatLng(loc.getDouble(1), loc.getDouble(0)),
                    lanes = lanes?.takeIf { l2 -> l2.size >= 2 },
                )
            }
        }
        val via = summary.split(",").map { it.trim() }.filter { it.isNotBlank() }.distinct().take(2).joinToString(" & ")
        return RouteOption(pts, out, r.getDouble("distance"), r.getDouble("duration"), via, toll, motorway)
    }
}

/**
 * Asks OSRM first (free, with lanes); if it's down, slow or finds nothing, TomTom plans the route
 * instead (traffic-aware), so a trip never fails just because one server is having a bad day.
 */
object Router {
    /** Which service planned the last route ("OSRM" or "TomTom"), for the About screen and tests. */
    @Volatile var lastSource = "OSRM"
        private set

    private val racers = java.util.concurrent.Executors.newCachedThreadPool(Bg.factory("route"))

    /**
     * A reroute, as fast as possible: OSRM, and if it hasn't answered in [headStart] ms TomTom too;
     * the first good answer wins (no more waiting out OSRM's 8 s timeout when it's having a slow moment).
     */
    /** The phone's own router (no signal needed), if the road file is downloaded. */
    private fun offline(from: LatLng, to: LatLng, heading: Float?, via: List<LatLng>): List<RouteOption>? {
        if (!OfflineRouter.present) return null
        val r = try { OfflineRouter.route(from, to, heading, via) } catch (e: Throwable) { CrashLog.note("offline route: ${e.message}"); null } ?: return null
        lastSource = "Offline"
        return listOf(r)
    }

    private fun online() = !Dev.noInternet && try { Offline.online() } catch (e: Exception) { true }

    fun quick(from: LatLng, to: LatLng, heading: Float?, via: List<LatLng>, headStart: Long = 1200): List<RouteOption> {
        // no internet: straight to the phone's own router (no waiting for timeouts)
        if (!online()) offline(from, to, heading, via)?.let { return it }
        if (!Traffic.active) return routes(from, to, false, heading, reroute = true, via = via)
        val done = java.util.concurrent.ExecutorCompletionService<Pair<String, List<RouteOption>?>>(racers)
        done.submit { "OSRM" to try { Osrm.routes(from, to, false, heading, true, via).takeIf { it.isNotEmpty() } } catch (e: Exception) { null } }
        var running = 1
        var tomtomAsked = false
        fun askTomTom() {
            tomtomAsked = true; running++
            done.submit { "TomTom" to try { TomTomRoute.routes(from, to, false, heading, via).takeIf { it.isNotEmpty() } } catch (e: Exception) { null } }
        }
        val until = System.currentTimeMillis() + 12_000
        while (running > 0) {
            val wait = if (!tomtomAsked) headStart else until - System.currentTimeMillis()
            if (wait <= 0) break
            val f = done.poll(wait, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (f == null) { if (!tomtomAsked) { askTomTom(); continue } else break }
            running--
            val (src, list) = try { f.get() } catch (e: Exception) { "" to null }
            if (list != null) { lastSource = src; return list }
            if (!tomtomAsked) askTomTom() // OSRM failed fast: TomTom now
        }
        offline(from, to, heading, via)?.let { return it }
        throw RuntimeException("No route")
    }

    fun routes(from: LatLng, to: LatLng, alternatives: Boolean, heading: Float? = null, reroute: Boolean = false, via: List<LatLng> = emptyList()): List<RouteOption> {
        if (!online()) offline(from, to, heading, via)?.let { return it }
        return try { onlineRoutes(from, to, alternatives, heading, reroute, via) } catch (e: Exception) {
            offline(from, to, heading, via) ?: throw e
        }
    }

    private fun onlineRoutes(from: LatLng, to: LatLng, alternatives: Boolean, heading: Float?, reroute: Boolean, via: List<LatLng>): List<RouteOption> {
        if (Dev.noInternet) throw java.io.IOException("No internet (test)")
        // planning a trip: TomTom's alternatives too, at the same time (OSRM often finds just one)
        val extra = if (alternatives && !reroute && via.isEmpty() && Traffic.active)
            racers.submit(java.util.concurrent.Callable { try { TomTomRoute.routes(from, to, true, heading, via) } catch (e: Exception) { null } }) else null
        val first = try { Osrm.routes(from, to, alternatives, heading, reroute, via).takeIf { it.isNotEmpty() } } catch (e: Exception) { null }
        if (first != null) {
            lastSource = "OSRM"
            if (extra == null || first.size >= 3) { extra?.cancel(true); return first }
            val more = try { extra.get(4000, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (e: Exception) { extra.cancel(true); null }
            return mergeRoutes(first, more.orEmpty())
        }
        if (!Traffic.active) throw RuntimeException("No route")
        val early = extra?.let { try { it.get(12_000, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (e: Exception) { null } }
        val backup = early?.takeIf { it.isNotEmpty() } ?: TomTomRoute.routes(from, to, alternatives && via.isEmpty(), heading, via)
        if (backup.isEmpty()) throw RuntimeException("No route")
        lastSource = "TomTom"
        return backup
    }
}

/**
 * Adds the routes of [more] that really are other ways (not the same road with a small detour,
 * and not far longer) to [base], up to [max] in all.
 */
internal fun mergeRoutes(base: List<RouteOption>, more: List<RouteOption>, max: Int = 3): List<RouteOption> {
    val out = base.toMutableList()
    val shortest = base.minOfOrNull { it.distance } ?: return more.take(max)
    for (r in more) {
        if (out.size >= max) break
        if (r.points.size < 2 || r.distance > shortest * 1.4) continue
        if (out.none { routeOverlap(r, it) > 0.8 }) out += r
    }
    return out
}

/** How much of [a], by length, runs along [b] (within 40 m): 0..1. */
internal fun routeOverlap(a: RouteOption, b: RouteOption): Double {
    if (a.points.size < 2 || b.points.size < 2) return 0.0
    val step = maxOf(200.0, a.length / 150)
    var next = 0.0; var n = 0; var on = 0
    // b's box, a little wider: points outside it are off b for sure
    var minLat = 90.0; var maxLat = -90.0; var minLon = 180.0; var maxLon = -180.0
    for (p in b.points) { minLat = minOf(minLat, p.lat); maxLat = maxOf(maxLat, p.lat); minLon = minOf(minLon, p.lon); maxLon = maxOf(maxLon, p.lon) }
    for (i in a.points.indices) {
        if (a.cum[i] < next) continue
        next = a.cum[i] + step
        n++
        val p = a.points[i]
        if (p.lat < minLat - 0.001 || p.lat > maxLat + 0.001 || p.lon < minLon - 0.001 || p.lon > maxLon + 0.001) continue
        var hit = false
        for (k in 0 until b.points.size - 1) {
            val s = b.points[k]; val e = b.points[k + 1]
            // cheap skip: both ends far in latitude (~1 km)
            if (kotlin.math.abs(s.lat - p.lat) > 0.01 && kotlin.math.abs(e.lat - p.lat) > 0.01 && (s.lat - p.lat) * (e.lat - p.lat) > 0) continue
            if (Geo.distanceToSegment(p, s, e) < 40.0) { hit = true; break }
        }
        if (hit) on++
    }
    return if (n == 0) 0.0 else on.toDouble() / n
}

/** TomTom as a full router (the backup): geometry, turns, traffic and speed limits in one go. */
object TomTomRoute {
    fun routes(from: LatLng, to: LatLng, alternatives: Boolean, heading: Float?, via: List<LatLng>): List<RouteOption> {
        val pts = listOf(from) + via + listOf(to)
        var url = "https://api.tomtom.com/routing/1/calculateRoute/" + pts.joinToString(":") { "${it.lat},${it.lon}" } + "/json" +
            "?key=${Traffic.key}&traffic=true&travelMode=car&routeRepresentation=polyline&instructionsType=coded&language=el-GR" +
            "&sectionType=traffic&sectionType=speedLimit&sectionType=tollRoad&sectionType=motorway"
        if (alternatives) url += "&maxAlternatives=2"
        if (heading != null) url += "&vehicleHeading=${heading.toInt().coerceIn(0, 359)}"
        return parseAll(Http.get(url, timeout = 12000))
    }

    fun parseAll(json: String): List<RouteOption> {
        val arr = JSONObject(json).optJSONArray("routes") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { try { parse(arr.getJSONObject(it)) } catch (e: Exception) { null } }
    }

    /** TomTom maneuver code → our (type, direction), in OSRM's words so everything else stays the same. */
    private fun maneuver(code: String, angle: Int): Pair<String, String> {
        val side = if (angle < 0) "slight left" else "slight right"
        return when (code) {
            "DEPART" -> "depart" to "straight"
            "ARRIVE", "ARRIVE_LEFT", "ARRIVE_RIGHT" -> "arrive" to "arrive"
            "WAYPOINT_LEFT", "WAYPOINT_RIGHT", "WAYPOINT_REACHED" -> "waypoint" to "straight"
            "KEEP_LEFT" -> "fork" to "slight left"
            "KEEP_RIGHT" -> "fork" to "slight right"
            "BEAR_LEFT" -> "turn" to "slight left"
            "BEAR_RIGHT" -> "turn" to "slight right"
            "TURN_LEFT" -> "turn" to "left"
            "TURN_RIGHT" -> "turn" to "right"
            "SHARP_LEFT" -> "turn" to "sharp left"
            "SHARP_RIGHT" -> "turn" to "sharp right"
            "MAKE_UTURN", "TRY_MAKE_UTURN" -> "turn" to "uturn"
            "ENTER_MOTORWAY", "ENTER_FREEWAY", "ENTER_HIGHWAY", "ENTRANCE_RAMP" -> "on ramp" to side
            "MOTORWAY_EXIT_LEFT" -> "off ramp" to "slight left"
            "TAKE_EXIT", "MOTORWAY_EXIT_RIGHT" -> "off ramp" to (if (code == "TAKE_EXIT") side else "slight right")
            "ROUNDABOUT_CROSS" -> "roundabout" to "straight"
            "ROUNDABOUT_RIGHT" -> "roundabout" to "right"
            "ROUNDABOUT_LEFT" -> "roundabout" to "left"
            "ROUNDABOUT_BACK" -> "roundabout" to "uturn"
            else -> "continue" to "straight" // STRAIGHT, FOLLOW, SWITCH_*_ROAD, TAKE_FERRY
        }
    }

    private fun parse(r: JSONObject): RouteOption {
        val pts = ArrayList<LatLng>()
        val legs = r.getJSONArray("legs")
        for (l in 0 until legs.length()) {
            val p = legs.getJSONObject(l).getJSONArray("points")
            for (i in 0 until p.length()) { val q = p.getJSONObject(i); pts += LatLng(q.getDouble("latitude"), q.getDouble("longitude")) }
        }
        val steps = ArrayList<NavStep>()
        val names = HashMap<String, Double>()
        val ins = r.optJSONObject("guidance")?.optJSONArray("instructions") ?: JSONArray()
        for (i in 0 until ins.length()) {
            val o = ins.getJSONObject(i)
            val (type, dir) = maneuver(o.optString("maneuver"), o.optInt("turnAngleInDecimalDegrees", 0))
            val road = o.optString("street").ifBlank { o.optString("roadNumbers").trim('[', ']', '"') }
            val pt = o.getJSONObject("point")
            val exit = if (type == "roundabout") o.optInt("roundaboutExitNumber", 0) else 0
            // which roads the trip mostly uses, for the "via" line
            if (i + 1 < ins.length() && road.isNotBlank()) {
                val len = ins.getJSONObject(i + 1).optDouble("routeOffsetInMeters", 0.0) - o.optDouble("routeOffsetInMeters", 0.0)
                names[road] = (names[road] ?: 0.0) + len
            }
            // TomTom repeats "continue" for every name change; keep them only when they name the road
            if (type == "continue" && i > 0 && i + 1 < ins.length() && road.isBlank()) continue
            steps += NavStep(type, dir, road, exit, LatLng(pt.getDouble("latitude"), pt.getDouble("longitude")), null)
        }
        val sum = r.getJSONObject("summary")
        val travel = sum.getInt("travelTimeInSeconds")
        val delay = sum.optInt("trafficDelayInSeconds", 0)
        val secs = r.optJSONArray("sections") ?: JSONArray()
        val types = (0 until secs.length()).map { secs.getJSONObject(it).optString("sectionType") }.toSet()
        val via = names.entries.sortedByDescending { it.value }.take(2).joinToString(" & ") { it.key }
        val out = RouteOption(pts, steps, sum.getDouble("lengthInMeters"), (travel - delay).coerceAtLeast(1).toDouble(), via,
            hasToll = "TOLL_ROAD" in types, hasMotorway = "MOTORWAY" in types)
        out.trafficSeconds = travel
        out.delaySeconds = delay
        val traffic = ArrayList<Stretch>(); val limits = ArrayList<Stretch>()
        for (i in 0 until secs.length()) {
            val sec = secs.getJSONObject(i)
            val a = out.cum[sec.optInt("startPointIndex").coerceIn(0, pts.size - 1)]
            val b = out.cum[sec.optInt("endPointIndex").coerceIn(0, pts.size - 1)]
            when (sec.optString("sectionType")) {
                "TRAFFIC" -> traffic += Stretch(a, b, sec.optInt("magnitudeOfDelay", 1), sec.optInt("delayInSeconds", 0))
                "SPEED_LIMIT" -> sec.optInt("maxSpeedLimitInKmh", 0).takeIf { it > 0 }?.let { limits += Stretch(a, b, it) }
            }
        }
        out.traffic = traffic; out.limits = limits
        return out
    }
}

/**
 * TomTom looks at a route we already have (by following points along it) and tells us how long
 * it takes in traffic, where the jams are and the speed limits. Geometry and turns stay OSRM's.
 */
object TomTomTime {
    class Result(val seconds: Int, val delay: Int, val traffic: List<Stretch>, val limits: List<Stretch>, val meters: Int = 0,
                 /** TomTom's own time for the same roads with no traffic. */
                 val freeFlow: Int = 0) {
        /**
         * How much slower than free-flowing traffic it is now: TomTom's time ÷ its own no-traffic
         * time. This is what goes onto our own times. TomTom's whole time was used before, and
         * TomTom's base speeds in town are much slower than real driving (a 2.8 km trip in Athens:
         * 8 min with no traffic by TomTom, under 5 by OSRM): the arrival said 15 min for a 6 min drive.
         */
        val congestion: Double get() = if (freeFlow > 0) (seconds.toDouble() / freeFlow).coerceIn(1.0, 4.0) else 1.0
    }

    /**
     * TomTom's answer only counts if it followed our route: its length close to what's left of
     * ours. Longer means it went another way (round a block, a U-turn) to join the route, which
     * added minutes the driver never drives: the arrival time jumped 6 → 15 → 6 (drive, 20 Nov).
     */
    fun followsRoute(remainingMeters: Double, ttMeters: Int): Boolean =
        ttMeters <= 0 || ttMeters <= remainingMeters * 1.05 + 120

    /** Evaluates [r] from [fromAlong] metres to its end. [departAt] (ms) asks for predicted traffic. */
    fun evaluate(r: RouteOption, fromAlong: Double, start: LatLng? = null, departAt: Long? = null): Result? {
        if (!Traffic.active || r.points.size < 2) return null
        val total = r.length
        val remaining = total - fromAlong
        if (remaining < 50) return null
        val step = maxOf(remaining / 90, 25.0)
        val pts = ArrayList<LatLng>()
        // (from the spot on the route, not the raw GPS point: a few metres off, on the other side
        // of the road, and TomTom routed a detour back onto the route)
        var next = fromAlong
        for (i in r.points.indices) {
            if (r.cum[i] + 0.1 >= next) { pts += r.points[i]; next = r.cum[i] + step }
        }
        if (pts.last() != r.points.last()) pts += r.points.last()
        if (pts.size < 2) return null
        val body = JSONObject().put("supportingPoints", JSONArray().apply {
            pts.forEach { put(JSONObject().put("latitude", it.lat).put("longitude", it.lon)) }
        })
        val a = pts.first(); val b = pts.last()
        var url = "https://api.tomtom.com/routing/1/calculateRoute/${a.lat},${a.lon}:${b.lat},${b.lon}/json" +
            "?key=${Traffic.key}&traffic=true&travelMode=car&routeRepresentation=polyline&computeTravelTimeFor=all" +
            "&sectionType=traffic&sectionType=speedLimit"
        if (departAt != null) {
            url += "&departAt=" + SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date(departAt))
        }
        val o = JSONObject(Http.post(url, body.toString()))
        val route = o.getJSONArray("routes").getJSONObject(0)
        val sum = route.getJSONObject("summary")
        // Where each TomTom point sits along our route.
        val tPts = ArrayList<LatLng>()
        val legs = route.getJSONArray("legs")
        for (l in 0 until legs.length()) {
            val p = legs.getJSONObject(l).getJSONArray("points")
            for (i in 0 until p.length()) {
                val q = p.getJSONObject(i); tPts += LatLng(q.getDouble("latitude"), q.getDouble("longitude"))
            }
        }
        val along = DoubleArray(tPts.size)
        var hint = r.alongOf(tPts.firstOrNull() ?: a, 0, r.points.size).second
        for (i in tPts.indices) {
            val (d, idx) = r.alongOf(tPts[i], hint)
            along[i] = d; hint = idx
        }
        val traffic = mutableListOf<Stretch>()
        val limits = mutableListOf<Stretch>()
        route.optJSONArray("sections")?.let { s ->
            for (i in 0 until s.length()) {
                val sec = s.getJSONObject(i)
                val si = sec.optInt("startPointIndex").coerceIn(0, along.size - 1)
                val ei = sec.optInt("endPointIndex").coerceIn(0, along.size - 1)
                if (along.isEmpty()) break
                when (sec.optString("sectionType")) {
                    "TRAFFIC" -> traffic += Stretch(along[si], along[ei], sec.optInt("magnitudeOfDelay", 1), sec.optInt("delayInSeconds", 0))
                    "SPEED_LIMIT" -> sec.optInt("maxSpeedLimitInKmh", 0).takeIf { it > 0 }?.let {
                        limits += Stretch(along[si], along[ei], it)
                    }
                }
            }
        }
        val meters = sum.optInt("lengthInMeters", 0)
        if (!followsRoute(remaining, meters)) {
            CrashLog.note("TomTom time ignored: ${meters} m against ${remaining.toInt()} m left (it went another way)")
            return null
        }
        return Result(sum.getInt("travelTimeInSeconds"), sum.optInt("trafficDelayInSeconds", 0), traffic, limits, meters,
            sum.optInt("noTrafficTravelTimeInSeconds", 0))
    }
}

/** Spoken and written instructions in English or Greek. */
object Phrases {
    const val ENGLISH = "en"
    const val GREEK = "el"

    private fun en(dir: String) = when (dir) {
        "uturn" -> "make a U-turn"
        "straight" -> "go straight"
        "sharp left" -> "turn sharp left"
        "sharp right" -> "turn sharp right"
        "slight left" -> "bear left"
        "slight right" -> "bear right"
        "left" -> "turn left"
        "right" -> "turn right"
        else -> "continue"
    }

    private fun el(dir: String) = when (dir) {
        "uturn" -> "κάνε αναστροφή"
        "straight" -> "συνέχισε ευθεία"
        "sharp left" -> "στρίψε απότομα αριστερά"
        "sharp right" -> "στρίψε απότομα δεξιά"
        "slight left" -> "κράτα αριστερά"
        "slight right" -> "κράτα δεξιά"
        "left" -> "στρίψε αριστερά"
        "right" -> "στρίψε δεξιά"
        else -> "συνέχισε"
    }

    private val ordEl = listOf("", "πρώτη", "δεύτερη", "τρίτη", "τέταρτη", "πέμπτη", "έκτη", "έβδομη", "όγδοη")
    private val ordEn = listOf("", "first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth")

    /** The instruction, capitalised, without a distance. */
    fun instruction(s: NavStep, lang: String, named: Boolean = true): String {
        val g = lang == GREEK
        // spoken directions say only the turn ("turn right"); on screen the name, in the reader's letters
        val name = if (!named) "" else if (g) s.road else Names.latin(s.road)
        val onto = if (name.isBlank()) "" else if (g) " στην $name" else " onto $name"
        val base = when (s.type) {
            "arrive" -> if (g) "Έφτασες στον προορισμό σου" else "You have arrived"
            "waypoint" -> if (g) "Φτάνεις στη στάση σου" else "You're reaching your stop"
            "depart" -> if (g) "Ξεκίνα$onto" else "Head out$onto"
            "roundabout", "rotary" ->
                if (s.exit in 1..8) {
                    if (g) "Στον κυκλικό κόμβο, πάρε την ${ordEl[s.exit]} έξοδο$onto"
                    else "At the roundabout, take the ${ordEn[s.exit]} exit$onto"
                } else if (g) "Μπες στον κυκλικό κόμβο" else "Enter the roundabout"
            "exit roundabout", "exit rotary" -> if (g) "Βγες από τον κυκλικό κόμβο$onto" else "Exit the roundabout$onto"
            "fork" -> {
                val side = if (s.direction.contains("left")) (if (g) "αριστερά" else "left") else (if (g) "δεξιά" else "right")
                if (g) "Στη διχάλα, κράτα $side$onto" else "At the fork, keep $side$onto"
            }
            "merge" -> if (g) "Μπες στην κυκλοφορία$onto" else "Merge$onto"
            "on ramp" -> if (g) "Πάρε τη ράμπα$onto" else "Take the ramp$onto"
            "off ramp" -> if (g) "Πάρε την έξοδο$onto" else "Take the exit$onto"
            "end of road" -> if (g) "Στο τέλος του δρόμου, ${el(s.direction)}$onto" else "At the end of the road, ${en(s.direction)}$onto"
            "continue", "new name" -> if (g) "Συνέχισε$onto" else "Continue$onto"
            else -> if (g) el(s.direction) + onto else en(s.direction) + onto
        }
        return base.replaceFirstChar { it.uppercase() }
    }

    fun distance(m: Double, lang: String): String {
        val g = lang == GREEK
        if (!g && MapState.imperial) {
            val mi = m / 1609.344
            return when {
                mi >= 0.95 -> (Math.round(mi * 10) / 10.0).let { v -> if (v % 1.0 == 0.0) "${v.toInt()} miles" else "$v miles" }
                mi >= 0.2 -> if (mi < 0.35) "a quarter mile" else if (mi < 0.65) "half a mile" else "three quarters of a mile"
                else -> "${((m * 3.28084 / 100).roundToLong() * 100).coerceAtLeast(100)} feet"
            }
        }
        return when {
            m >= 950 -> {
                val km = Math.round(m / 100) / 10.0
                val s = if (km % 1.0 == 0.0) km.toInt().toString() else String.format(Locale.US, "%.1f", km).let { if (g) it.replace('.', ',') else it }
                if (s == "1") (if (g) "1 χιλιόμετρο" else "1 kilometre") else if (g) "$s χιλιόμετρα" else "$s kilometres"
            }
            else -> {
                val r = if (m >= 100) (Math.round(m / 50) * 50).toInt() else (Math.round(m / 10) * 10).toInt().coerceAtLeast(10)
                if (g) "$r μέτρα" else "$r metres"
            }
        }
    }

    /** "In 300 metres, turn left onto X" */
    fun ahead(s: NavStep, m: Double, lang: String, named: Boolean = true): String {
        val instr = instruction(s, lang, named)
        val lower = instr.replaceFirstChar { it.lowercase() }
        return if (lang == GREEK) "Σε ${distance(m, lang)}, $lower" else "In ${distance(m, lang)}, $lower"
    }

    fun then(s: NavStep, lang: String, named: Boolean = true): String {
        val lower = instruction(s, lang, named).replaceFirstChar { it.lowercase() }
        return if (lang == GREEK) "και μετά $lower" else "then $lower"
    }

    fun minutes(sec: Double, lang: String): String {
        val m = (sec / 60).toInt().coerceAtLeast(1)
        return if (lang == GREEK) {
            if (m >= 60) "${m / 60} ώρες και ${m % 60} λεπτά" else "$m λεπτά"
        } else if (m >= 60) "${m / 60} hours ${m % 60} minutes" else "$m minutes"
    }

    fun routeReady(sec: Double, lang: String) =
        if (lang == GREEK) "Η διαδρομή είναι έτοιμη. ${minutes(sec, lang)}." else "Route ready. ${minutes(sec, lang)}."

    fun rerouting(lang: String) = if (lang == GREEK) "Επανυπολογισμός διαδρομής" else "Rerouting"

    fun faster(saved: Int, lang: String) =
        if (lang == GREEK) "Βρέθηκε ταχύτερη διαδρομή. Κερδίζετε ${minutes(saved.toDouble(), lang)}."
        else "Faster route found. It saves ${minutes(saved.toDouble(), lang)}."

    fun switched(lang: String) = if (lang == GREEK) "Αλλαγή διαδρομής" else "Switched to the faster route"

    fun toll(price: Double, lang: String): String {
        val euros = price.toInt(); val cents = ((price - euros) * 100 + 0.5).toInt()
        return if (lang == GREEK) "Διόδια μπροστά, $euros ευρώ" + (if (cents > 0) " και $cents λεπτά" else "")
        else "Toll ahead, $euros euros" + (if (cents > 0) " $cents" else "")
    }

    fun jam(lang: String) = if (lang == GREEK) "Κίνηση μπροστά" else "Traffic ahead"
}
