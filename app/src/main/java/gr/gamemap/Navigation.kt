package gr.gamemap

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.exp

/**
 * GPS position and heading from the phone's own providers (no Play services needed),
 * smoothed for display: the arrow glides between fixes, keeps moving between them at
 * the current speed, and turns gradually.
 */
object LocationTracker : LocationListener {
    val changed = Signal()
    var last: Location? = null
        private set
    private var started = false

    // display smoothing
    private var fromPos: LatLng? = null
    private var toPos: LatLng? = null
    /** The glide bends: it leaves the way the arrow was pointing and curves into the new fix. */
    private var ctrlPos: LatLng? = null
    private var fixAt = 0L
    private var speed = 0f
    private var moveBearing = 0f
    private var hasMoved = false
    private var drovenAt = 0L
    /** The way the car was last really going (within 15 min): a new trip from a parked car starts that way. */
    val lastDriveHeading: Float? get() = if (hasMoved && System.currentTimeMillis() - drovenAt < 15 * 60_000L) moveBearing else null
    private var headingTarget = 0f
    private var compass: Float? = null
    private var lastTick = 0L

    /** Heading shown on the map, degrees (smoothed). */
    var heading = 0f
        private set

    fun hasPermission(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private val users = java.util.Collections.synchronizedSet(HashSet<String>())

    /**
     * [who] ("phone", "car", "nav") needs positions. GPS runs only while someone does, so GameMap
     * left open in the background doesn't keep the GPS, network and voice busy.
     */
    fun start(ctx: Context, who: String = "phone") {
        appCtx = ctx.applicationContext
        users += who
        if (started) updateMode() else startUpdates(ctx)
    }

    fun release(ctx: Context, who: String) {
        users -= who
        if (users.isNotEmpty()) { updateMode(); return }
        if (!started) return
        val lm = ctx.applicationContext.getSystemService(LocationManager::class.java) ?: return
        try { lm.removeUpdates(this) } catch (_: Exception) {}
        started = false
    }

    val inUse: Boolean get() = users.isNotEmpty()

    /**
     * Precise = GPS every second (navigating, in a convoy, or on the car screen). Otherwise the phone's
     * battery-friendly fused location every 3 s / 5 m: plenty for a map you're looking at.
     */
    @Volatile var precise = false
        private set

    /**
     * In a convoy without a route: parked or walking for a minute = GPS slows down to save the
     * battery (the others still get an update every few seconds); driving again = every second.
     */
    @Volatile private var convoyIdle = false
    private var movedAt = 0L

    private fun checkConvoyPace(l: Location) {
        if (!Convoy.active || NavEngine.active) { if (convoyIdle) { convoyIdle = false; updateMode() }; return }
        val now = System.currentTimeMillis()
        val moving = l.hasSpeed() && l.speed > 3f
        if (moving || movedAt == 0L) movedAt = now
        val idle = now - movedAt > 60_000
        if (idle != convoyIdle) { convoyIdle = idle; updateMode() }
    }
    private var appCtx: Context? = null

    /**
     * Save GPS when stopped (Settings › Performance): standing still for 15 s at a red light or in a
     * jam, the GPS reports every 3 s instead of every second; the first fix that moves brings it back.
     */
    @Volatile var restingGps = false
        private set
    @Volatile private var deepRest = false
    private var stillSince = 0L

    private fun checkResting(l: Location) {
        if (l.provider == ESTIMATE) return
        val now = System.currentTimeMillis()
        val still = l.hasSpeed() && l.speed < 0.5f
        if (!still) stillSince = 0L else if (stillSince == 0L) stillSince = now
        val rest = Perf.saveGpsStopped && precise && still && now - stillSince > 15_000
        // parked for three minutes with no route (Android Auto on, a convoy waiting): every 10 s
        val deep = rest && !NavEngine.active && now - stillSince > 180_000
        if ((rest != restingGps || deep != deepRest) && (rest || (l.hasSpeed() && l.speed > 1.5f) || !Perf.saveGpsStopped)) {
            restingGps = rest; deepRest = deep
            CrashLog.note(if (rest) "gps resting (stopped)" else "gps every second")
            val ctx = appCtx ?: return
            if (started) {
                try { ctx.getSystemService(LocationManager::class.java)?.removeUpdates(this) } catch (_: Exception) {}
                started = false
            }
            if (users.isNotEmpty()) startUpdates(ctx)
        }
    }

    /** Called when navigation starts/stops or the car connects/disconnects. */
    fun updateMode() {
        val want = NavEngine.active || NavEngine.planning || "car" in users || "nav" in users || (Convoy.active && !convoyIdle)
        // a route starts while parked in the deep rest: GPS every second again straight away
        val wake = deepRest && (NavEngine.active || NavEngine.planning)
        if (wake) { deepRest = false; restingGps = false }
        if (want == precise && started && !wake) return
        val ctx = appCtx ?: return
        if (started) {
            val lm = ctx.getSystemService(LocationManager::class.java)
            try { lm?.removeUpdates(this) } catch (_: Exception) {}
            started = false
        }
        if (users.isNotEmpty()) startUpdates(ctx)
    }

    @SuppressLint("MissingPermission")
    private fun startUpdates(ctx: Context) {
        if (started || !hasPermission(ctx)) return
        appCtx = ctx.applicationContext
        val lm = ctx.applicationContext.getSystemService(LocationManager::class.java) ?: return
        val looper = Looper.getMainLooper()
        // the same rule as updateMode(): a convoy parked for a minute doesn't need GPS every second
        precise = NavEngine.active || NavEngine.planning || "car" in users || "nav" in users || (Convoy.active && !convoyIdle)
        // faster lock: show the freshest fix any provider still has (up to 30 min old) straight away
        if (last == null) {
            val fresh = lm.allProviders.mapNotNull { pr -> try { lm.getLastKnownLocation(pr) } catch (_: Exception) { null } }
                .filter { System.currentTimeMillis() - it.time < 30 * 60_000L }
                .maxByOrNull { it.time }
            fresh?.let { onLocationChanged(Location(it).apply { speed = 0f }) }
        }
        val fused = if (Build.VERSION.SDK_INT >= 31 && lm.allProviders.contains(LocationManager.FUSED_PROVIDER))
            LocationManager.FUSED_PROVIDER else null
        val plan: List<Triple<String, Long, Float>> = when {
            // GPS every second; the fused/network one only as a backup (indoors, a cold start):
            // its fixes are dropped anyway while GPS ones come, so asking every second was waste
            precise -> listOfNotNull(Triple(LocationManager.GPS_PROVIDER, if (deepRest) 10_000L else if (restingGps) 3000L else 1000L, 0f),
                (fused ?: LocationManager.NETWORK_PROVIDER).let { Triple(it, 5000L, 0f) })
            fused != null -> listOf(Triple(fused, 3000L, 5f))
            else -> listOf(Triple(LocationManager.GPS_PROVIDER, 3000L, 5f), Triple(LocationManager.NETWORK_PROVIDER, 5000L, 10f))
        }
        for ((p, ms, m) in plan) {
            try {
                if (lm.allProviders.contains(p)) {
                    lm.requestLocationUpdates(p, ms, m, this, looper)
                    if (last == null) lm.getLastKnownLocation(p)?.let { onLocationChanged(it) }
                }
            } catch (_: Exception) {
            }
        }
        started = true
        changed.fire()
    }

    /** Raw latest position (for routing and search). */
    val position: LatLng? get() = last?.let { LatLng(it.latitude, it.longitude) }
    val accuracy: Float get() = last?.takeIf { it.hasAccuracy() }?.accuracy ?: 0f
    val speedMps: Float get() = speed

    /** Smoothed position for drawing. */
    fun displayPosition(): LatLng? {
        val to = toPos ?: return null
        val from = fromPos ?: to
        val t = (SystemClock.uptimeMillis() - fixAt) / 1000.0
        if (t < 1.0) return glideAt(from, to, t)
        if (speed < 2f) return to
        // Between fixes, keep rolling forward at the current speed (up to 1.5 s).
        return Geo.offset(to, moveBearing, speed * (t - 1.0).coerceAtMost(1.5))
    }

    /** A point on the glide: a gentle curve from [from] (leaving along the arrow) into [to]. */
    private fun glideAt(from: LatLng, to: LatLng, t: Double): LatLng {
        val c = ctrlPos ?: return Geo.lerp(from, to, t)
        val u = 1 - t
        return LatLng(u * u * from.lat + 2 * u * t * c.lat + t * t * to.lat, u * u * from.lon + 2 * u * t * c.lon + t * t * to.lon)
    }

    /**
     * Pulling away or creeping (under ~20 km/h): a big change of direction (over 50°) only counts
     * once the next fix agrees with it. Just after starting, the road match could pick the cross
     * street or the right street facing the wrong way for one fix, and the map swung there and back.
     */
    private var slowPending: Float? = null
    private var travelFrom: LatLng? = null
    private var travelBearing = 0f
    private var travelAt = 0L
    private var savedDriveAt = 0L

    /**
     * Starting the app where the car was parked (within 60 m, last 12 hours): the map starts facing
     * the way you parked, not north, so it doesn't turn round as you set off.
     */
    private fun parkedHeading(p: LatLng): Float? = try {
        val v = MapState.prefsOrNull()?.getString("lastDrive", null)?.split(",") ?: emptyList()
        if (v.size == 4 && System.currentTimeMillis() - v[3].toLong() < 12 * 3600_000L &&
            Geo.distance(LatLng(v[0].toDouble(), v[1].toDouble()), p) < 60) v[2].toFloat() else null
    } catch (e: Exception) { null }
    private fun confirmSlow(dir: Float, speed: Float): Float {
        if (speed >= 5.5f || abs(Geo.angleDiff(headingTarget, dir)) <= 50f) { slowPending = null; return dir }
        val pend = slowPending
        return if (pend != null && abs(Geo.angleDiff(pend, dir)) <= 30f) { slowPending = null; dir }
        else { slowPending = dir; headingTarget }
    }

    /** Advances the heading animation. Returns true while anything is still moving. */
    private var glideMeters = 0.0

    fun tick(): Boolean {
        val now = SystemClock.uptimeMillis()
        val dt = (now - lastTick).coerceIn(0, 100)
        lastTick = now
        val c = compass
        // While the arrow glides to the next fix it points the way it's actually moving: in a turn
        // the glide already heads into the new street while the road's heading lagged behind, and
        // the arrow slid sideways ("moonwalking"). Small glides keep the road's heading.
        val from = fromPos; val to = toPos
        // (only at a proper speed: pulling away, the glide between two wobbly fixes swung the map)
        val gliding = from != null && to != null && now - fixAt < 1000 && glideMeters > 3.0 && speed > 8f
        // the phone's compass, standing still: only on foot. In the car (Android Auto, or just
        // driven) it points anywhere (metal, the phone mount), and the map swung from it to the
        // road and back as you pulled away; there the way you were driving is kept instead.
        val useCompass = c != null && !NavEngine.active && "car" !in users && lastDriveHeading == null
        val target = when {
            speed < 1.0f && useCompass -> c!!
            gliding -> {
                // the curve's own direction right here: the arrow is always tangent to its path
                val gt = ((now - fixAt) / 1000.0).coerceIn(0.0, 0.95)
                Geo.bearing(glideAt(from!!, to!!, gt), glideAt(from, to, gt + 0.05))
            }
            else -> headingTarget
        }
        val diff = Geo.angleDiff(heading, target)
        val turning = abs(diff) > 0.3f
        var step = diff * (1 - exp(-dt / (if (gliding) 70.0 else 260.0))).toFloat()
        // slow (pulling away, creeping in traffic): the map turns at most 40° a second, so one odd
        // reading can't swing it; at speed, turns follow the road as before
        if (speed < 8f && !gliding) step = step.coerceIn(-0.04f * dt, 0.04f * dt)
        heading = if (turning) (heading + step + 360f) % 360f else target
        // standing still, a new fix a few cm away isn't worth 30 frames a second
        val moving = toPos != null && ((now - fixAt < 1000 && glideMeters > 0.8) || speed >= 2f)
        return turning || moving
    }

    /** Phone compass (used only when standing still). Pass null to stop using it. */
    fun setCompass(deg: Float?) {
        compass = deg
        if (deg != null && speed < 1.0f) changed.fire()
    }

    /** Provider name of tunnel-mode positions (estimated along the route, not measured). */
    const val ESTIMATE = "estimate"

    /** Provider name of fixes from the car's own GPS (Android Auto, when the car shares it). */
    const val CAR = "car"

    /** How far ahead of the last fix the arrow aims, in seconds of driving (fix age + glide). */
    private const val LEAD_S = 1.0

    /** Tunnel mode: a position worked out along the route while GPS is lost. */
    fun estimate(p: LatLng, bearing: Float, speedMps: Float) {
        onLocationChanged(Location(ESTIMATE).apply {
            latitude = p.lat; longitude = p.lon; this.bearing = bearing; speed = speedMps; accuracy = 15f
            time = System.currentTimeMillis()
        })
    }

    /** Fixes in a row thrown away as impossible jumps (believed after 3 that agree with each other). */
    private var jumps = 0
    private var lastSkipped: Location? = null
    private var skippedInRow = 0
    /** When a jump was last believed (the route waits a moment before calling it "off route"). */
    @Volatile var jumpedAt = 0L
        private set

    /** The last measured (not estimated) fix: what the Weak GPS note goes by. */
    @Volatile var lastReal: Location? = null
        private set

    /**
     * Weak GPS: moving or navigating, not in tunnel mode, and the last measured fix is vague (over
     * 30 m) or a few seconds old. The map shows a small note under the arrow.
     */
    val weak: Boolean get() {
        val r = lastReal ?: return false
        if (NavEngine.estimating) return false
        if (!NavEngine.active && speed < 2f) return false
        val age = System.currentTimeMillis() - r.time
        return (r.hasAccuracy() && r.accuracy > 30f) || age in (if (deepRest) 25_000L else if (restingGps) 7_000L else 4_000L)..90_000L
    }

    /** True if [l] is farther from [prev] than a car could have got in the time between them. */
    internal fun impossibleJump(prev: Location, l: Location): Boolean {
        val dt = ((l.time - prev.time) / 1000.0).coerceAtLeast(0.5)
        val fastest = maxOf(if (l.hasSpeed()) l.speed else 0f, if (prev.hasSpeed()) prev.speed else 0f, 6f) * 1.6 + 8
        val slack = ((if (l.hasAccuracy()) l.accuracy else 30f) + (if (prev.hasAccuracy()) prev.accuracy else 30f)).coerceAtMost(80f) + 15
        val d = Geo.distance(LatLng(prev.latitude, prev.longitude), LatLng(l.latitude, l.longitude))
        return d > fastest * dt + slack
    }

    override fun onLocationChanged(location: Location) {
        // test builds: while the simulator drives, the phone's real GPS is ignored
        if (Dev.simActive && location.provider != ESTIMATE && !Dev.isSim(location)) return
        val prev = last
        val real = location.provider != ESTIMATE
        val now = System.currentTimeMillis()
        // In a tunnel the phone may still report wild fixes (over 100 m off): keep estimating instead.
        // Merely vague city fixes (30-100 m, between tall buildings) are kept: throwing those away
        // left the arrow guessing at an old speed, and turns came too late.
        if (NavEngine.active && real && location.hasAccuracy() && location.accuracy > 100 &&
            prev != null && now - prev.time < 120_000) return
        // Prefer GPS (or the car's own); ignore a coarser fix that arrives right after one.
        if (prev != null && (prev.provider == LocationManager.GPS_PROVIDER || prev.provider == CAR) && real &&
            location.provider != LocationManager.GPS_PROVIDER && location.provider != CAR &&
            location.time - prev.time < 10_000
        ) return
        // the car's own GPS only fills in while the phone's has gone quiet
        if (location.provider == CAR && prev != null && prev.provider == LocationManager.GPS_PROVIDER && now - prev.time < 2_500) return
        // Drop wildly inaccurate fixes once we have a decent one.
        if (prev != null && location.hasAccuracy() && location.accuracy > 150 &&
            prev.hasAccuracy() && prev.accuracy < 50 && location.time - prev.time < 30_000
        ) return
        // A jump no car could make (signal bouncing off buildings): skip it, up to 3 in a row.
        val prevReal = lastReal
        if (real && prevReal != null && location.time - prevReal.time < 30_000 && impossibleJump(prevReal, location)) {
            // a real move (a long gap, a ferry): the new fixes agree with each other; a teleport
            // (signal off buildings) scatters: those keep being skipped (up to 10 in a row)
            val ls = lastSkipped
            val agrees = ls != null && !impossibleJump(ls, location)
            lastSkipped = location
            jumps = if (agrees) jumps + 1 else 1
            if (jumps <= 3 && ++skippedInRow <= 10) { CrashLog.note("gps jump skipped"); return }
            jumpedAt = now; CrashLog.note("gps jump believed")
        }
        if (real) { jumps = 0; skippedInRow = 0; lastSkipped = null; lastReal = location }

        last = location
        speed = if (location.hasSpeed()) location.speed else 0f
        if (location.provider != ESTIMATE) { checkConvoyPace(location); checkResting(location) }
        var p = LatLng(location.latitude, location.longitude)
        val bearing = if (location.hasBearing() && speed > 3f) location.bearing else null
        // slow: the way we were going (not none), so the road match keeps the right carriageway
        val matchBearing = bearing ?: moveBearing.takeIf { speed > 1f && hasMoved }
        // stopped (a red light): the fixes wander a few metres; keep the arrow still
        val held = toPos?.takeIf { real && speed < 0.7f && prev != null && (!prev.hasSpeed() || prev.speed < 0.7f) &&
            Geo.distance(it, p) < maxOf(if (location.hasAccuracy()) location.accuracy * 0.8 else 10.0, 8.0) }
        val onRoute = if (held != null || speed <= 1f) null else NavEngine.snap(p, bearing)
        val snapped = if (held != null || speed <= 1f) null
            else onRoute
                // no route (or off it): the map's own roads, by heading, speed and the road we were on
                ?: if (speed > 2f) RoadMatch.match(p, matchBearing, speed, if (location.hasAccuracy()) location.accuracy else 20f) else null
        if (held != null) {
            p = held
        } else if (snapped != null) {
            p = snapped.first
            headingTarget = confirmSlow(snapped.second, speed)
        } else if (location.hasBearing() && speed > 3f &&
            (android.os.Build.VERSION.SDK_INT < 26 || !location.hasBearingAccuracy() || location.bearingAccuracyDegrees <= 30f)) {
            // under ~11 km/h GPS's direction swings 20-40° from one second to the next: the map
            // kept turning left and right; it keeps the last good direction until moving properly
            // under ~30 km/h GPS's own direction still wobbles 20-30°: averaged with the way the
            // car has actually moved over the last dozen metres
            val travel = travelBearing.takeIf { System.currentTimeMillis() - travelAt < 4000 }
            val dir = if (speed < 8f && travel != null) Geo.meanBearing(location.bearing, travel) else location.bearing
            headingTarget = confirmSlow(dir, speed)
        }
        // the way the car has really moved: every 12 m or more
        val anchor = travelFrom
        if (held == null && speed > 1f && real) {
            if (anchor == null || Geo.distance(anchor, p) > 60) travelFrom = p
            else if (Geo.distance(anchor, p) >= 12) { travelBearing = Geo.bearing(anchor, p); travelAt = System.currentTimeMillis(); travelFrom = p }
        }
        if (speed > 3f) {
            moveBearing = headingTarget; hasMoved = true; drovenAt = System.currentTimeMillis()
            // remembered across app restarts: where and which way the car was last driving
            if (drovenAt - savedDriveAt > 10_000) {
                savedDriveAt = drovenAt
                MapState.prefsOrNull()?.edit()?.putString("lastDrive", "${p.lat},${p.lon},$moveBearing,$drovenAt")?.apply()
            }
        }

        val firstFix = toPos == null
        if (firstFix && !hasMoved) parkedHeading(p)?.let { headingTarget = it; moveBearing = it; hasMoved = true; drovenAt = System.currentTimeMillis() }
        fromPos = if (firstFix) p else displayPosition()
        // The arrow glides to the new fix over the next second, so aim it where the car will be by
        // then (a fix is already a moment old): otherwise the arrow trails the car by 1-2 s.
        // (on the route: aimed along the route, round the corner, not straight on past it into the
        // buildings: at a turn the arrow overshot and swung back)
        val lead = (speed * LEAD_S).coerceAtMost(40.0)
        val target = if (!firstFix && held == null && speed > 2f)
            (if (onRoute != null) NavEngine.aheadOnRoute(p, lead) else null) ?: Geo.offset(p, moveBearing, lead)
        else p
        glideMeters = fromPos?.let { Geo.distance(it, target) } ?: 0.0
        // leave along the arrow's current heading for a third of the way, then curve into the fix
        ctrlPos = fromPos?.takeIf { !firstFix && held == null && speed > 2f && glideMeters > 3.0 }?.let { Geo.offset(it, heading, glideMeters / 3) }
        toPos = target
        fixAt = SystemClock.uptimeMillis()
        if (firstFix) heading = headingTarget

        NavEngine.onLocation(location)
        SpeedLimit.onFix(LatLng(location.latitude, location.longitude), if (location.hasBearing()) location.bearing else null, speed)
        Places.warmUp(p)
        Convoy.tick()
        Explorer.onLocation(LatLng(location.latitude, location.longitude), speed)
        Alerts.onLocation(p, speed, headingTarget)
        changed.fire()
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
}

/** A place found by search. [icon] is a small emoji for its category. */
data class Place(
    val name: String, val address: String, val pos: LatLng, val icon: String = "📍",
    /** Fuel prices, when known: "95 €1.970 · Diesel €2.035" */
    val extra: String = "",
    /** Google star rating and how many ratings (Settings › Reviews) */
    val rating: Double? = null, val ratings: Int = 0,
    /** Fuel prices by kind ("sp95", "diesel", "lpg"…), € per litre; [areaAvg] = an area average, not this station's */
    val prices: Map<String, Double> = emptyMap(), val areaAvg: Boolean = false,
    /** Brand (fuel stations), and for chargers: plug kinds ("type2", "ccs", "chademo", "tesla") and top power */
    val brand: String = "", val plugs: Set<String> = emptySet(), val kw: Int = 0,
    /** Where to drive to: the entrance on the street, when known (else the place itself). */
    val entry: LatLng? = null,
    /** A city or town (3), a village (2), a neighbourhood or area (1); 0 = not a settlement. */
    val town: Int = 0,
) {
    val goal: LatLng get() = entry ?: pos
}

/** Destination search: Photon for live suggestions, Nominatim as a fallback, plus recents. */
object Places {
    private lateinit var appContext: Context

    fun init(ctx: Context) {
        appContext = ctx.applicationContext
    }

    private val searchLang get() = if (java.util.Locale.getDefault().language == "el") "el-GR" else "en-GB"

    /** TomTom search (fast, good with Greek and English). [typeahead] = the user is still typing. */
    /** TomTom's one-box search; off when the key isn't allowed it (then the two below are used). */
    @Volatile private var fuzzyOk = true

    /** Towns and areas only (TomTom's "Geo" index), typed as you go: "ναυπλ" → Nafplio. */
    fun tomtomTowns(query: String, near: LatLng?): List<Place>? {
        if (!tomtomOk() || !fuzzyOk) return null
        val q = URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        var url = "https://api.tomtom.com/search/2/search/$q.json?key=${Traffic.key}&limit=4&language=$searchLang&typeahead=true&idxSet=Geo"
        if (near != null) url += "&lat=${near.lat}&lon=${near.lon}"
        return try { parseTomTom(Http.get(url, timeout = 4000)).filter { it.town > 0 } } catch (e: Exception) { null }
    }

    fun tomtom(query: String, near: LatLng?, typeahead: Boolean): List<Place> {
        val q = URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        val key = Traffic.key
        if (fuzzyOk) {
            var url = "https://api.tomtom.com/search/2/search/$q.json?key=$key&limit=8&language=$searchLang" +
                "&typeahead=$typeahead&idxSet=POI,PAD,Addr,Str,Geo"
            if (near != null) url += "&lat=${near.lat}&lon=${near.lon}"
            try { return parseTomTom(Http.get(url, timeout = 6000)) }
            catch (e: Exception) { if (e.message?.contains("403") == true) fuzzyOk = false else throw e }
        }
        // Places by name (POI search) and addresses (geocoding), both kept to ~60 km around
        val bias = near?.let { "&lat=${it.lat}&lon=${it.lon}&radius=60000" } ?: ""
        var err: Exception? = null
        val pois = try { parseTomTom(Http.get("https://api.tomtom.com/search/2/poiSearch/$q.json?key=$key&limit=6&language=$searchLang&typeahead=$typeahead$bias", timeout = 6000)) }
            catch (e: Exception) { err = e; null }
        val addrs = try { parseTomTom(Http.get("https://api.tomtom.com/search/2/geocode/$q.json?key=$key&limit=5&language=$searchLang$bias", timeout = 6000)) }
            catch (e: Exception) { err = e; null }
        if (pois == null && addrs == null) throw err ?: RuntimeException("TomTom search failed")
        val first = if (query.any { it.isDigit() }) addrs.orEmpty() + pois.orEmpty() else pois.orEmpty() + addrs.orEmpty()
        return first.distinctBy { norm(it.name) + "|" + (it.pos.lat * 2000).toInt() + "|" + (it.pos.lon * 2000).toInt() }.take(8)
    }

    /** The address at a point (dropped pin): TomTom, or OpenStreetMap's Nominatim without it. */
    fun addressAt(p: LatLng): Place {
        val ll = "${"%.6f".format(java.util.Locale.US, p.lat)},${"%.6f".format(java.util.Locale.US, p.lon)}"
        if (Traffic.key.isNotEmpty()) try {
            val o = JSONObject(Http.get("https://api.tomtom.com/search/2/reverseGeocode/$ll.json?key=${Traffic.key}&language=$searchLang", timeout = 5000))
            val a = o.optJSONArray("addresses")?.optJSONObject(0)?.optJSONObject("address")
            if (a != null) {
                val street = a.optString("streetNameAndNumber").ifBlank { a.optString("streetName") }
                val area = listOf(a.optString("municipalitySubdivision"), a.optString("municipality"), a.optString("postalCode"))
                    .filter { it.isNotBlank() }.distinct().joinToString(", ")
                if (street.isNotBlank() || area.isNotBlank()) return Place(street.ifBlank { area }, if (street.isBlank()) "" else area, p, "📍")
            }
        } catch (_: Exception) {}
        try {
            val o = JSONObject(Http.get("https://nominatim.openstreetmap.org/reverse?lat=${p.lat}&lon=${p.lon}&format=jsonv2&zoom=18", timeout = 6000))
            val a = o.optJSONObject("address")
            val road = a?.optString("road").orEmpty()
            val num = a?.optString("house_number").orEmpty()
            val street = listOf(road, num).filter { it.isNotBlank() }.joinToString(" ")
            val area = listOfNotNull(a?.optString("suburb"), a?.optString("city") ?: a?.optString("town"), a?.optString("postcode"))
                .filter { it.isNotBlank() }.distinct().joinToString(", ")
            if (street.isNotBlank() || area.isNotBlank()) return Place(street.ifBlank { area }, if (street.isBlank()) "" else area, p, "📍")
        } catch (_: Exception) {}
        return Place("Dropped pin", ll, p, "📍")
    }

    private fun parseTomTom(json: String): List<Place> {
        val arr = JSONObject(json).optJSONArray("results") ?: return emptyList()
        val out = mutableListOf<Place>()
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            val pos = r.optJSONObject("position") ?: continue
            val addr = r.optJSONObject("address")
            val poi = r.optJSONObject("poi")
            val free = addr?.optString("freeformAddress").orEmpty()
            val street = listOfNotNull(addr?.optString("streetName")?.takeIf { it.isNotBlank() }, addr?.optString("streetNumber")?.takeIf { it.isNotBlank() })
                .joinToString(" ")
            val name = poi?.optString("name")?.takeIf { it.isNotBlank() }
                ?: street.ifBlank { null }
                ?: addr?.optString("municipality")?.takeIf { it.isNotBlank() }
                ?: free
            if (name.isBlank()) continue
            val code = poi?.optJSONArray("classifications")?.optJSONObject(0)?.optString("code").orEmpty()
            val address = free.removePrefix(name).trimStart(',', ' ').ifBlank { addr?.optString("municipality").orEmpty() }
            val brand = poi?.optJSONArray("brands")?.optJSONObject(0)?.optString("name").orEmpty()
            // chargers: which plugs and how fast
            val plugs = HashSet<String>(); var kw = 0
            r.optJSONObject("chargingPark")?.optJSONArray("connectors")?.let { cs ->
                for (k in 0 until cs.length()) {
                    val cn = cs.optJSONObject(k) ?: continue
                    Fuel.plugKind(cn.optString("connectorType"))?.let { plugs += it }
                    kw = maxOf(kw, cn.optDouble("ratedPowerKW", 0.0).toInt())
                }
            }
            val cats = poi?.optJSONArray("categories")?.let { a -> (0 until a.length()).joinToString(" ") { a.optString(it) } }.orEmpty()
            val at = LatLng(pos.getDouble("lat"), pos.getDouble("lon"))
            // the entrance the route should end at: the main one nearest the place (the first listed
            // could be far round the block: Iatropolis was sent 350 m up the side road), and only a
            // close one: a far "entrance" is more likely wrong than the place itself
            val entries = r.optJSONArray("entryPoints")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()
                .mapNotNull { e -> e.optJSONObject("position")?.let { p -> (e.optString("type") == "main") to LatLng(p.getDouble("lat"), p.getDouble("lon")) } }
            val entry = (entries.filter { it.first }.ifEmpty { entries }).map { it.second }.minByOrNull { Geo.distance(it, at) }
            val town = if (r.optString("type") != "Geography") 0 else when (r.optString("entityType")) {
                "Municipality" -> 3
                "Country", "CountrySubdivision", "CountrySecondarySubdivision", "CountryTertiarySubdivision" -> 2
                else -> 1 // MunicipalitySubdivision, Neighbourhood, PostalCodeArea
            }
            out += Place(name, address, at, tomtomIcon(code, r.optString("type"), cats),
                brand = brand, plugs = plugs, kw = kw, entry = entry?.takeIf { Geo.distance(it, at) in 3.0..120.0 }, town = town)
        }
        return out.distinctBy { it.name + "|" + it.address }
    }

    private fun tomtomIcon(code: String, type: String, cats: String = "") = when {
        code == "ELECTRIC_VEHICLE_STATION" -> "🔌"
        "bakery" in cats || "pastry" in cats || "ice cream" in cats || "dessert" in cats || "confection" in cats -> "🍰"
        "café" in cats || "coffee" in cats -> "☕"
        code == "NIGHTLIFE" || "bar" in cats.split(' ') || "pub" in cats -> "🍸"
        "supermarket" in cats || "grocer" in cats || "food shop" in cats || "convenience" in cats -> "🛒"
        code == "BANK" || code == "CASH_DISPENSER" -> "🏦"
        code == "DENTIST" -> "🦷"
        code == "PLACE_OF_WORSHIP" -> "⛪"
        code == "SPORTS_CENTER" || code == "FITNESS_CLUB_CENTER" -> "🏋"
        code == "REPAIR_FACILITY" || code == "CAR_DEALER" || code == "RENT_A_CAR_FACILITY" -> "🔧"
        code == "PETROL_STATION" -> "⛽"
        code == "RESTAURANT" -> "🍽"
        code == "CAFE_PUB" -> "☕"
        code == "PARKING_GARAGE" || code == "OPEN_PARKING_AREA" -> "🅿"
        code == "HOSPITAL_POLYCLINIC" || code == "DOCTOR" -> "🏥"
        code == "PHARMACY" -> "💊"
        code == "SHOP" || code == "SHOPPING_CENTER" || code == "MARKET" -> "🛍"
        code == "HOTEL_MOTEL" -> "🏨"
        code == "MUSEUM" || code == "IMPORTANT_TOURIST_ATTRACTION" || code == "TOURIST_ATTRACTION" -> "🏛"
        code == "AIRPORT" -> "✈"
        code == "RAILWAY_STATION" || code == "PUBLIC_TRANSPORT_STOP" -> "🚉"
        code == "BEACH" -> "🏖"
        code == "PARK_RECREATION_AREA" -> "🌳"
        code == "COLLEGE_UNIVERSITY" || code == "SCHOOL" -> "🎓"
        type == "Geography" -> "🏙"
        type == "Street" -> "🛣"
        type == "Point Address" || type == "Address Range" -> "🏠"
        else -> "📍"
    }

    // ---- instant search in the map data already on the phone (streets, places, shops…) ----

    private class Named(val norm: List<String>, val full: String, val name: String, val pos: LatLng, val icon: String, val kind: Int)
    private val nameIndex = java.util.concurrent.ConcurrentHashMap<String, List<Named>>()

    /**
     * A word of [query] that none of [results] has (in its name or address, Greek or Latin): the
     * place itself isn't on the map here, and these are only other matches. ("Iatropolis
     * Argyroupoli": no Iatropolis in Argyroupolis on any map, and the top result was Argyroupoli
     * metro station: the car drove there.)
     */
    fun missingWord(query: String, results: List<Place>): String? {
        if (results.isEmpty()) return null
        val hay = results.joinToString(" ") { norm(it.name + " " + it.address) }
        val hayLatin = norm(Names.latin(hay))
        for (word in query.trim().split(Regex("[\\s,.]+"))) {
            val w = norm(word)
            if (w.length < 5 || w.any { it.isDigit() }) continue
            val forms = setOf(w, norm(Names.latin(w)), norm(greeklish(w))).map { it.take(maxOf(5, it.length - 2)) }
            if (forms.none { hay.contains(it) || hayLatin.contains(it) }) return word
        }
        return null
    }

    /** Lower case, no accents (ά→α), final ς→σ: so "ομονοια" finds "Ομόνοια". */
    fun norm(s: String): String = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "").replace('ς', 'σ')

    /** Greek typed in Latin letters ("eggeli", "xalandri", "thiseio") → Greek letters. */
    fun greeklish(s: String): String {
        val t = s.lowercase()
        if (t.none { it in 'a'..'z' }) return s
        val sb = StringBuilder()
        var i = 0
        val vowels = "aeiouyw"
        while (i < t.length) {
            val two = if (i + 1 < t.length) t.substring(i, i + 2) else ""
            val g2 = when (two) {
                "th" -> "θ"; "ch", "kh" -> "χ"; "ps" -> "ψ"; "ks" -> "ξ"; "ou" -> "ου"; else -> null
            }
            if (g2 != null) { sb.append(g2); i += 2; continue }
            val ch = t[i]
            sb.append(when (ch) {
                'a' -> "α"; 'b', 'v' -> "β"; 'g' -> "γ"; 'd' -> "δ"; 'e' -> "ε"; 'z' -> "ζ"
                'h' -> if (i + 1 < t.length && t[i + 1] in vowels) "χ" else "η"
                'i' -> "ι"; 'k', 'c', 'q' -> "κ"; 'l' -> "λ"; 'm' -> "μ"; 'n' -> "ν"; 'x' -> "χ"
                'o' -> "ο"; 'p' -> "π"; 'r' -> "ρ"; 's' -> "σ"; 't' -> "τ"; 'y' -> "υ"; 'u' -> "ου"
                'f' -> "φ"; 'w' -> "ω"; 'j' -> "τζ"
                else -> ch.toString()
            })
            i++
        }
        return sb.toString()
    }

    /**
     * Greek spelling by sound, so small spelling slips still match: ι/η/υ/ει/οι all become ι,
     * ω→ο, αι→ε, double letters single ("Λυκοβριση" finds "Λυκόβρυση").
     */
    fun sound(s: String): String {
        var t = norm(s)
        // single letters first, so "kallirois" (οι) and Καλλιρόης (οη) end up the same
        t = t.replace("αυ", "αβ").replace("ευ", "εβ").replace("αφ", "αβ").replace("εφ", "εβ").replace("ου", "\u0001")
            .replace('η', 'ι').replace('υ', 'ι').replace('ω', 'ο')
            .replace("ει", "ι").replace("οι", "ι").replace("αι", "ε").replace("\u0001", "ου")
            // sounds Greeklish spells loosely: μπ/β (b), ντ/δ (d), γκ/γγ/νγ (g), ξ/χ (x), κσ (ks)
            .replace("μπ", "β").replace("ντ", "δ").replace("νδ", "δ").replace("γκ", "γ").replace("νγ", "γ")
            .replace("κσ", "χ").replace('ξ', 'χ').replace("πσ", "ψ")
        return t.replace(Regex("(\\p{L})\\1"), "$1")
    }

    private fun indexTile(key: String, vt: VectorTile): List<Named> = nameIndex.getOrPut(key) {
        val (zs, xs, ys) = key.split("/")
        val z = zs.removePrefix("s").toInt(); val tx = xs.toInt(); val ty = ys.toInt()
        val n = (1 shl z).toDouble()
        val out = ArrayList<Named>()
        for (f in vt.features) {
            val kind = when {
                f.layer == "place" && f.type == 1 -> 0
                f.layer == "poi" && f.type == 1 -> 1
                f.layer == "transportation_name" -> 2
                else -> continue
            }
            val name = f["name"]?.takeIf { it.isNotBlank() } ?: continue
            val part = f.parts.firstOrNull() ?: continue
            val k = (part.size / 4) * 2
            val px = part.getOrElse(k) { 0f }; val py = part.getOrElse(k + 1) { 0f }
            if (px < 0 || py < 0 || px > f.extent || py > f.extent) continue
            val pos = LatLng(Geo.lat((ty + py / f.extent.toDouble()) / n, 1.0), Geo.lon((tx + px / f.extent.toDouble()) / n, 1.0))
            val alt = listOfNotNull(f["name:latin"], f["name_en"], f["name:en"]).joinToString(" ")
            val full = sound("$name $alt")
            val icon = when (kind) { 0 -> "🏙"; 2 -> "🛣"; else -> icon(f["class"], f["subclass"]) }
            out += Named(full.split(Regex("[\\s,.\\-()]+")).filter { it.isNotEmpty() }, full, name, pos, icon, kind)
        }
        if (nameIndex.size > 200) nameIndex.clear()
        out
    }

    @Volatile private var gazetteer: List<Named> = emptyList()
    @Volatile private var gazetteerAt: LatLng? = null

    /**
     * Towns, villages and neighbourhoods for ~70 km around [near] (from zoom-11 map data, downloaded
     * once and cached), so searching a suburb's name works even before the map has been there.
     */
    fun prepareGazetteer(near: LatLng) {
        val at = gazetteerAt
        if (at != null && Geo.distance(at, near) < 20_000) return
        gazetteerAt = near
        val z = 11; val n = 1 shl z
        val cx = (Geo.x(near.lon, 1.0) * n).toInt(); val cy = (Geo.y(near.lat, 1.0) * n).toInt()
        val out = ArrayList<Named>()
        for (ty in cy - 2..cy + 2) for (tx in cx - 2..cx + 2) {
            val vt = try { VectorSource.get(z, tx, ty) } catch (e: Exception) { null } ?: continue
            out += indexTile("$z/$tx/$ty", vt).filter { it.kind == 0 }
        }
        gazetteer = out.distinctBy { it.name }
    }

    /** The nearest named shops, cafés, sights… around [near] from the map data (search box opened, nothing typed). */
    fun around(near: LatLng): List<Place> {
        val hits = ArrayList<Named>()
        for ((key, vt) in VectorSource.parsedTiles()) {
            if (!key.startsWith("${VectorSource.MAX_Z}/")) continue
            hits += indexTile(key, vt).filter { it.kind == 1 && it.icon != "📍" }
        }
        return hits.asSequence().map { it to Geo.distance(near, it.pos) }.filter { it.second < 1500 }
            .sortedBy { it.second }.map { it.first }.distinctBy { it.name }.take(8)
            .map { Place(it.name, "", it.pos, it.icon) }.toList()
    }

    /** Names on the map data in memory that start with what was typed, nearest first. Instant, offline. */
    /** A search typed as words (numbers apart) and, if any, the house number in it. */
    /** Place words people type in English or Greek: "Acropolis Museum" also finds "Μουσείο Ακρόπολης". */
    private val synonyms: Map<String, List<String>> by lazy {
        val pairs = listOf("museum" to "μουσειο", "cave" to "σπηλια", "cave" to "σπηλαιο", "beach" to "παραλια", "castle" to "καστρο",
            "monastery" to "μονη", "church" to "εκκλησια", "temple" to "ναος", "lake" to "λιμνη", "airport" to "αεροδρομιο",
            "port" to "λιμανι", "harbour" to "λιμανι", "station" to "σταθμος", "hospital" to "νοσοκομειο", "square" to "πλατεια",
            "university" to "πανεπιστημιο", "park" to "παρκο", "stadium" to "σταδιο", "theatre" to "θεατρο", "theater" to "θεατρο",
            "gorge" to "φαραγγι", "waterfall" to "καταρρακτης", "waterfalls" to "καταρρακτες", "bridge" to "γεφυρι", "market" to "αγορα",
            "lighthouse" to "φαρος", "mountain" to "βουνο", "mount" to "ορος", "island" to "νησι", "old town" to "παλια πολη")
        val m = HashMap<String, MutableList<String>>()
        for ((en, el) in pairs) {
            val a = sound(greeklish(en)); val b = sound(greeklish(el))
            m.getOrPut(a) { ArrayList() } += listOf(b, sound(el)); m.getOrPut(b) { ArrayList() } += listOf(a, sound(en))
        }
        m
    }

    /** Small words a name can have or not ("Σπηλιά του Νταβέλη" is "Σπηλιά Νταβέλη"). */
    private val stopWords: Set<String> by lazy {
        listOf("το", "τα", "ο", "η", "οι", "του", "της", "των", "τον", "την", "και", "στο", "στη", "the", "of", "and", "de", "la", "at")
            .flatMap { listOf(sound(greeklish(it)), sound(it)) }.toSet()
    }

    class Query(text: String) {
        private val raw = text.trim().split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
        val number: String? = raw.firstOrNull { w -> w.any { it.isDigit() } && w.length <= 5 }
        /** The words to find ("του", "the"… left out unless that's all there is). */
        val words: List<String> = raw.filter { w -> w.none { it.isDigit() } }.let { all ->
            all.filter { sound(greeklish(it)) !in stopWords && sound(it) !in stopWords }.ifEmpty { all }
        }
        /** Each word as typed and as Greek, by sound. */
        val forms: List<List<String>> = words.map { w ->
            (listOf(sound(w), sound(greeklish(w))) + (synonyms[sound(greeklish(w))] ?: emptyList())).filter { f -> f.isNotEmpty() }.distinct()
        }
        fun matches(e: List<String>, k: Int) = forms[k].any { f -> e.any { it.startsWith(f) } }
        /** All the words run together ("crepa land" also finds "Crepaland"). */
        val joined: List<String> = if (words.size < 2) emptyList() else
            words.joinToString("").let { listOf(sound(it), sound(greeklish(it))) }.distinct()
    }

    /** Instant results, offline: names in the map data and the places squares on the phone, best first. */
    fun local(query: String, near: LatLng?): List<Place> {
        // coordinates or a map link pasted in: that exact spot (the address comes with find())
        SharedLocation.coordinates(query)?.let { return listOf(Place(SharedLocation.format(it), L.t("Coordinates"), it, "📍")) }
        if (SharedLocation.hasLink(query)) return emptyList()
        val q = Query(query)
        val tiles = localNamed(q, near).map { e -> Place(e.name, when (e.kind) { 0 -> "Area"; 2 -> "Street"; else -> "" }, e.pos, e.icon, town = if (e.kind == 0) 1 else 0) }
        val open = try { OpenPlaces.search(q, near) } catch (e: Exception) { emptyList() }
        val mine = savedMatches(query)
        return (mine + rank(q, near, null, listOf(open to SRC_OPEN, tiles to SRC_TILES)).filter { p -> mine.none { samePlace(it, p) || Geo.distance(it.pos, p.pos) < 25 } }).take(8)
    }

    // ---- ranking: what fits the words best, then what's nearest

    private const val SRC_TOMTOM = 0
    private const val SRC_OPEN = 1
    private const val SRC_TILES = 2
    private const val SRC_OSM = 3

    /**
     * How well a name fits what was typed: 1 = every word starts a word of the name (or the words
     * run together are one), in any spelling (Greek, Latin, Greeklish). Words found only in the
     * address (the street, the area) count half.
     */
    fun nameScore(q: Query, name: String, address: String = ""): Double {
        if (q.words.isEmpty()) return 0.0
        val s = OpenPlaces.searchText(name)
        if (q.joined.any { s.contains(" $it") }) return 1.0
        val a = if (address.isNotBlank()) OpenPlaces.searchText(address) else ""
        var m = 0.0; var firstHit = false
        for ((k, fs) in q.forms.withIndex()) {
            if (fs.any { s.contains(" $it") }) { m += 1.0; if (k == 0) firstHit = true }
            else if (a.isNotEmpty() && fs.any { a.contains(" $it") }) m += 0.5
        }
        var sc = m / q.words.size
        if (sc > 0 && !firstHit) sc *= 0.85
        return sc.coerceAtMost(1.0)
    }

    /** A name as words by sound, in Greek letters ("Cape Sounio" → καπε, σουνιο). */
    private fun nameWords(name: String): List<String> =
        sound(greeklish(name)).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.ifEmpty { listOf("") }

    /** Words in front of a sight's name that aren't what people type. */
    private val genericWords: Set<String> by lazy {
        listOf("αρχαιολογικός", "χώρος", "archaeological", "site", "ακρωτήριο", "cape", "παραλία", "beach", "λίμνη", "lake",
            "ναός", "temple", "κάστρο", "castle", "ιερά", "μονή", "monastery", "όρος", "mount", "φαράγγι", "gorge", "σπήλαιο", "cave",
            "αρχαίο", "αρχαία", "ancient", "θέατρο", "μουσείο", "museum", "το", "τα", "ο", "η", "οι", "the", "of", "του", "της", "των")
            .map { sound(greeklish(it)) }.toSet()
    }

    /**
     * A sight named after a town that is far from where it's placed, with no town of that name near
     * it: the places data sometimes puts a famous sight at the middle of the big city ("Sounion At
     * The Temple Of Poseidon", "Σούνιο" in central Athens). Streets aren't checked (Greek streets
     * are named after towns).
     */
    private fun misplaced(p: Place): Boolean {
        val towns = gazetteer
        if (towns.isEmpty()) return false
        val core = nameWords(p.name).filter { it.length >= 5 && it !in genericWords && it !in stopWords }
        fun same(a: String, b: String) = a == b || (minOf(a.length, b.length) >= 5 && (a.startsWith(b) || b.startsWith(a)) && Math.abs(a.length - b.length) <= 2)
        for (w in core) {
            val named = towns.filter { t -> nameWords(t.name).any { same(it, w) } }
            if (named.isEmpty()) continue
            if (named.all { Geo.distance(it.pos, p.pos) > 15_000 }) return true
        }
        return false
    }

    /** The same place from two sources: the same name (by sound, spaces aside) within 250 m. */
    private fun samePlace(a: Place, b: Place): Boolean {
        val d = Geo.distance(a.pos, b.pos)
        // a town: each service puts its point somewhere else in it (the centre, the town hall…)
        val townLike = a.town >= 2 || b.town >= 2
        // (a municipality's middle can be 20 km from its town)
        if (d > (if (townLike) 25_000.0 else 250.0)) return false
        val x = sound(greeklish(a.name)).filter { it.isLetterOrDigit() }
        val y = sound(greeklish(b.name)).filter { it.isLetterOrDigit() }
        // Patra / Patras, Ναύπλιο / Nafplio
        if (townLike && d > 250) return x.length >= 4 && y.length >= 4 && (x == y || ((x.startsWith(y) || y.startsWith(x)) && Math.abs(x.length - y.length) <= 2))
        if (x.isEmpty() || y.isEmpty()) return false
        if (x == y) return true
        return minOf(x.length, y.length) >= 4 && (x.startsWith(y) || y.startsWith(x)) && Geo.distance(a.pos, b.pos) < 150
    }

    /**
     * All sources together, best first: how well the name fits (most), how close it is to [focus]
     * (the user, or the area named in the search), and a little for being in that area.
     */
    private fun rank(q: Query, focus: LatLng?, area: Named?, lists: List<Pair<List<Place>, Int>>): List<Place> {
        class C(val p: Place, val score: Double, val fit: Double)
        val all = ArrayList<C>()
        val whole = sound(greeklish(q.words.joinToString(""))).filter { it.isLetterOrDigit() }
        val qWords = q.forms
        for ((list, src) in lists) for (p in list) {
            val fit = nameScore(q, p.name, p.address)
            val isAddr = p.icon == "🏠" || p.icon == "🛣" || p.icon == "🏙"
            var s = fit * 100
            if (isAddr && q.number != null) s = maxOf(s, 75.0)
            val d = focus?.let { Geo.distance(it, p.pos) } ?: 0.0
            // towns are where people drive far to: "ναυπλιο" is Nafplio 140 km away, not the Ναυπλίου
            // street round the corner (Greek streets are named after towns), which still comes next
            // sights people drive far to (an ancient site, a museum, a beach) count like a village
            val big = maxOf(p.town, if (p.icon == "🏛" || p.icon == "🏖") 2 else 0)
            val far = when (big) { 3 -> 0.3; 2 -> 0.6; 1 -> 0.85; else -> 1.0 }
            s -= far * 14 * Math.log10(1 + d / 1000) // 1 km −4, 10 km −15, 100 km −28 (a town: −8)
            // the name's words, "Αρχαιολογικός Χώρος", "Cape", "Το"… in front left aside
            val words = nameWords(p.name)
            val core = words.dropWhile { it in genericWords }.ifEmpty { words }
            val startsCore = qWords.firstOrNull()?.let { fs -> val w = core.first(); fs.any { w.startsWith(it) } } == true
            if (startsCore) s += 5 // "lidl" → the shop "Lidl …" before "Parking Lidl"
            // whole words, not just their start: "σουνιο" is Σούνιο more than the Σουνίου street
            if (qWords.isNotEmpty() && qWords.all { fs -> fs.any { it in words } }) s += 4
            // a town: only when it's named first ("Άργος-Μυκήνες" is Argos, not Mycenae)
            if (fit >= 0.99 && startsCore) s += when (big) { 3 -> 10; 2 -> 4; 1 -> 1; else -> 0 }
            // the whole name exactly as typed (by sound): "Ναύπλιο" over "Ναύπλιο - Τολό"
            if (big > 0 && whole.isNotEmpty() && (words.joinToString("") == whole || core.joinToString("") == whole)) s += 6
            // the name is just what was typed (small words aside): "Σπηλιά Νταβέλη" (Penteli) over
            // "Σπηλιά του Νταβέλη / Μπίμπεση" round the corner; each extra word costs a little
            // ("Acropolis Museum" before "Acropolis Museum Restaurant")
            // ("Αρχαιολογικός Χώρος", "Cape"… aren't extra; a street isn't the town it's named after)
            val sig = words.filter { it !in stopWords && it !in genericWords && it.length > 1 }
            if (qWords.isNotEmpty() && sig.isNotEmpty()) {
                val extra = sig.count { w -> qWords.none { fs -> fs.any { w.startsWith(it) } } }
                val allIn = qWords.all { fs -> fs.any { f -> sig.any { it.startsWith(f) } } }
                val exact = allIn && extra == 0 && sig.all { w -> qWords.any { fs -> w in fs } }
                // (a sight or a town: a lot; a shop or a café with the same name: a little, so
                // "αραχωβα" is still the town, not the Αράχωβα restaurant round the corner)
                if (exact && !(p.icon == "🛣" && q.number == null)) s += (if (big > 0) 12.0 else 4.0) + far * 14 * Math.log10(1 + d / 1000) * 0.15
                else if (allIn) s -= minOf(extra, 3) * 2.5
            }
            // far away (another country) and not the name typed: not what anyone wants
            if (d > 1_500_000 && fit < 0.99) continue
            // a sight in the places squares placed in the wrong town (Sounio's temple "in Athens")
            if (src == SRC_OPEN && p.icon == "🏛" && misplaced(p)) continue
            if (area != null && d < 5000) s += 8
            if (src == SRC_TOMTOM || src == SRC_OPEN) s += 1 // richer data (address, entrance)
            all += C(p, s, if (isAddr && q.number != null) 1.0 else fit)
        }
        all.sortByDescending { it.score }
        val out = ArrayList<Place>()
        for (c in all) {
            val i = out.indexOfFirst { samePlace(it, c.p) }
            if (i >= 0) {
                if (out[i].entry == null && c.p.entry != null) out[i] = out[i].copy(entry = c.p.entry)
                if (c.p.town > out[i].town) out[i] = out[i].copy(town = c.p.town)
                // the same town twice (its centre, its municipality's middle): keep the point
                // where the other results are (shops, streets of that town), i.e. the real centre
                if (c.p.town >= 2 && out[i].town >= 2) {
                    fun support(at: LatLng) = all.count { Geo.distance(it.p.pos, at) < 3000 }
                    if (support(c.p.pos) > support(out[i].pos)) out[i] = out[i].copy(pos = c.p.pos, entry = c.p.entry)
                }
                continue
            }
            out += c.p
        }
        // the ones that fit; if none do, the best guesses still show
        val good = out.filter { p -> all.first { it.p === p || samePlace(it.p, p) }.fit >= 0.5 }
        return if (good.isNotEmpty()) good else out.take(5)
    }

    /**
     * "crepa land glyfada" → ("crepa land", Γλυφάδα): a town or neighbourhood named at the end
     * (or the start) of a search, so the search looks there.
     */
    private fun splitArea(query: String, near: LatLng?): Pair<String, Named>? {
        val words = query.trim().split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
        if (words.size < 2) return null
        val towns = gazetteer
        if (towns.isEmpty()) return null
        val from = near ?: LocationTracker.position
        for (n in listOf(2, 1)) for (atEnd in listOf(true, false)) {
            if (words.size - n < 1) continue
            val part = (if (atEnd) words.takeLast(n) else words.take(n)).joinToString(" ")
            val keys = listOf(sound(part), sound(greeklish(part))).filter { it.length >= 3 }.distinct()
            val hit = towns.filter { t ->
                keys.any { k -> if (n == 1) k in t.norm else t.full.startsWith(k) || t.full.contains(" $k") }
            }.minByOrNull { t -> from?.let { Geo.distance(it, t.pos) } ?: 0.0 } ?: continue
            var rest = if (atEnd) words.dropLast(n) else words.drop(n)
            // "… in glyfada", "… στη γλυφάδα"
            if (atEnd) while (rest.size > 1 && rest.last().lowercase() in setOf("in", "at", "near", "στη", "στην", "στο", "στον", "κοντα", "κοντά")) rest = rest.dropLast(1)
            if (rest.isEmpty()) continue
            return rest.joinToString(" ") to hit
        }
        return null
    }

    /**
     * Names in the map data on the phone. Extra words that don't match (a misspelt area, "odos")
     * don't hide a result; they only rank it lower. The first word must match, or most of them.
     */
    private fun localNamed(q: Query, near: LatLng?): List<Named> {
        val n = q.words.size
        if (n == 0) return emptyList()
        val hits = ArrayList<Pair<Named, Int>>()
        fun consider(e: Named) {
            var m = 0
            var first = false
            for (k in 0 until n) if (q.matches(e.norm, k)) { m++; if (k == 0) first = true }
            if (m > 0 && (first || m * 2 >= n)) hits += e to m
        }
        for ((key, vt) in VectorSource.parsedTiles()) {
            if (!key.startsWith("${VectorSource.MAX_Z}/") && !key.startsWith("${VectorSource.MAX_Z - 1}/")) continue
            for (e in indexTile(key, vt)) consider(e)
        }
        for (e in streets) consider(e)
        for (e in gazetteer) consider(e)
        val from = near ?: LocationTracker.position
        fun dist(e: Named) = if (from != null) Geo.distance(from, e.pos) else 0.0
        val best = LinkedHashMap<String, Named>()
        // most words matched first; with a house number, streets first; then the nearest
        for ((e, _) in hits.sortedWith(compareBy<Pair<Named, Int>>({ -it.second }, { if (q.number != null && it.first.kind == 2) 0 else 1 }, { dist(it.first) }))) {
            if (from != null && dist(e) > 80_000) continue
            best.putIfAbsent(norm(e.name) + e.kind, e) // a street once (its nearest piece)
        }
        return best.values.take(8).toList()
    }

    /**
     * A house number on a street found in the map data: OpenStreetMap's address points, looked up
     * with the street's real (Greek) spelling right where the street is.
     */
    fun address(query: String, near: LatLng?): Place? {
        val q = Query(query)
        val num = q.number ?: return null
        val street = localNamed(q, near).firstOrNull { it.kind == 2 } ?: return null
        val d = 0.03
        val url = "https://nominatim.openstreetmap.org/search?q=" + URLEncoder.encode("${street.name} $num", "UTF-8") +
            "&format=jsonv2&limit=1&bounded=1&viewbox=${street.pos.lon - d},${street.pos.lat + d},${street.pos.lon + d},${street.pos.lat - d}"
        val arr = try { JSONArray(Http.get(url, timeout = 5000)) } catch (e: Exception) { null }
        val o = arr?.takeIf { it.length() > 0 }?.getJSONObject(0)
        val area = o?.optString("display_name")?.split(", ")?.drop(2)?.take(2)?.joinToString(", ").orEmpty()
        return if (o != null) Place("${street.name} $num", area, LatLng(o.getString("lat").toDouble(), o.getString("lon").toDouble()), "🏠")
        else Place(street.name, "Street · number $num not on the map", street.pos, "🛣")
    }

    private val finders = Executors.newCachedThreadPool(Bg.factory("find"))

    /**
     * Everything at once: the exact address, TomTom, OpenStreetMap (Photon), the places squares
     * and the map data, ranked together (see [rank]). A town named in the search ("… glyfada")
     * moves the search there. Used by the phone and the car. Null = no connection and nothing local.
     */
    fun find(query: String, near: LatLng?, full: Boolean, local: List<Place> = local(query, near)): List<Place>? {
        // coordinates or a map link (short links too: followed online): the spot, with its address
        if (SharedLocation.coordinates(query) != null || SharedLocation.hasLink(query)) {
            val f = SharedLocation.find(query) ?: return emptyList()
            f.pos?.let { pos ->
                val a = try { addressAt(pos) } catch (e: Exception) { null }
                val where = a?.let { listOf(it.name, it.address).filter { x -> x.isNotBlank() }.distinct().joinToString(", ") }.orEmpty()
                return listOf(Place(f.label ?: SharedLocation.format(pos), where.ifBlank { L.t("Coordinates") }, pos, "📍"))
            }
            // a link that only names a place: search for that name
            return f.query?.let { find(it, near, full) } ?: emptyList()
        }
        val split = try { splitArea(query, near) } catch (e: Exception) { null }
        val core = split?.first ?: query
        val area = split?.second
        val focus = area?.pos ?: near
        val q = Query(core)
        val fTom = finders.submit(java.util.concurrent.Callable { tryTomtom(query, focus, !full) }) // the whole text: TomTom knows the areas
        val fPho = finders.submit(java.util.concurrent.Callable { tryPhoton(core, focus) })
        // typed in Greek: TomTom also in Latin letters ("σουνιο" finds nothing there, "sounio" the temple)
        val greek = query.any { it in 'Α'..'ω' || it in 'ά'..'ώ' } && q.number == null && q.words.joinToString("").length >= 4
        val fLat = if (!greek) null else finders.submit(java.util.concurrent.Callable { tryTomtom(Voice.latin(query), focus, !full) })
        val fTown = if (q.number != null || q.words.size > 3) null
            else finders.submit(java.util.concurrent.Callable { try { photon(core, focus, townsOnly = true) } catch (e: Exception) { null } })
        // towns from TomTom too (Photon is often slow or down: then a half-typed town name
        // found only streets named after it, "ναυπλ" → Nafpliou streets, not Nafplio)
        val fTomTown = if (q.number != null || q.words.size > 2) null
            else finders.submit(java.util.concurrent.Callable { tomtomTowns(core, focus) })
        val fAddr = finders.submit(java.util.concurrent.Callable { try { address(query, near) } catch (e: Exception) { null } })
        // the places squares there (downloaded the first time a far area is searched)
        if (area != null && OpenPlaces.ready) try { OpenPlaces.prepare(area.pos) } catch (_: Exception) {}
        val open = try { OpenPlaces.search(q, focus, 20) } catch (e: Exception) { emptyList() }
        val tiles = if (area == null) local else try { local(core, focus) } catch (e: Exception) { local }
        fun <T> get(f: java.util.concurrent.Future<T>, ms: Long): T? =
            try { f.get(ms, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (e: Exception) { f.cancel(true); null }
        val tom = get(fTom, 7000)?.let { t -> t + fLat?.let { get(it, 1500) }.orEmpty() } ?: fLat?.let { get(it, 3000) }
        val pho = get(fPho, if (tom.isNullOrEmpty()) 4000 else if (full) 2500 else 1500)
        val addr = get(fAddr, 5000)
        val towns = (fTown?.let { get(it, 1200) }.orEmpty() + fTomTown?.let { get(it, 1500) }.orEmpty())
            .distinctBy { norm(Names.latin(it.name)) + (it.pos.lat * 100).toInt() + (it.pos.lon * 100).toInt() }
        var osm = pho.orEmpty() + towns
        // Photon down (it often is for a moment): OpenStreetMap through Nominatim, on Enter, so
        // places only it knows (a cave, a chapel) aren't lost
        if (pho == null && full) osm = osm + (try { nominatim(core, focus) } catch (e: Exception) { emptyList() })
        else if (tom.isNullOrEmpty() && osm.isEmpty() && open.isEmpty() && tiles.isEmpty() && addr == null) {
            osm = try { nominatim(core, focus) } catch (e: Exception) { return if (tom == null && pho == null) null else emptyList() }
        }
        val ranked = rank(q, focus, area, listOf(open to SRC_OPEN, tom.orEmpty() to SRC_TOMTOM, tiles to SRC_TILES, osm to SRC_OSM))
        val out = ArrayList<Place>()
        out += savedMatches(query) // "dad": my shortcut first
        addr?.let { if (out.none { o -> Geo.distance(o.pos, it.pos) < 25 }) out += it }
        for (p in ranked) if (out.none { samePlace(it, p) || Geo.distance(it.pos, p.pos) < 25 && Places.isSaved(it) }) out += p
        return out.take(10)
    }

    /**
     * Every named street for ~15 km around, from OpenStreetMap (Overpass), in 0.1° squares kept
     * on the phone for a month. The map tiles leave many small streets unnamed, so this is what
     * lets "eggeli" find Εγγέλη 8 km away.
     */
    @Volatile private var streets: List<Named> = emptyList()
    @Volatile private var streetsAt: LatLng? = null
    private val streetCells = java.util.concurrent.ConcurrentHashMap<String, List<Named>>()

    private val warm = java.util.concurrent.Executors.newSingleThreadExecutor(Bg.factory("search"))
    @Volatile private var warming = false

    /** Keeps the offline street/town index around the user ready (cheap check, work off-thread). */
    fun warmUp(p: LatLng, force: Boolean = false) {
        // not while driving along (data use); opening search asks for it with force
        if (warming || (!force && (NavEngine.active || LocationTracker.speedMps > 8f))) return
        if (!::appContext.isInitialized) return
        val s = streetsAt; val g = gazetteerAt
        if (s != null && Geo.distance(s, p) < 4_000 && g != null && Geo.distance(g, p) < 20_000 && OpenPlaces.has(p)) return
        warming = true
        warm.execute {
            try { prepareGazetteer(p); OpenPlaces.prepare(p); prepareStreets(p) } catch (_: Exception) {} finally { warming = false }
        }
    }

    fun prepareStreets(near: LatLng) {
        val at = streetsAt
        if (at != null && Geo.distance(at, near) < 4_000) return
        streetsAt = near
        val cy = Math.floor(near.lat * 10).toInt(); val cx = Math.floor(near.lon * 10).toInt()
        val cells = (cy - 1..cy + 1).flatMap { y -> (cx - 1..cx + 1).map { x -> y to x } }
            .sortedBy { (y, x) -> Math.abs(y - cy) + Math.abs(x - cx) } // own square first
        val keep = cells.map { "${it.first}_${it.second}" }.toSet()
        streetCells.keys.retainAll(keep)
        for ((y, x) in cells) {
            val key = "${y}_$x"
            if (streetCells[key] == null) streetCell(y, x)?.let { streetCells[key] = it }
            streets = streetCells.values.flatten()
        }
    }

    private fun streetCell(y: Int, x: Int): List<Named>? {
        val file = java.io.File(appContext.cacheDir, "streets/${y}_$x.tsv")
        val text = if (file.exists() && System.currentTimeMillis() - file.lastModified() < 30L * 86_400_000) file.readText() else {
            val s = y / 10.0; val w = x / 10.0; val n = s + 0.1; val e = w + 0.1
            val q = "[out:csv(name,::lat,::lon;false;\"\\t\")][timeout:60];" +
                "way[highway~\"^(motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street|pedestrian|service|road)$\"][name]($s,$w,$n,$e);out center;"
            var t: String? = null
            for (ep in Alerts.overpass) {
                t = try { Http.post(ep, "data=" + URLEncoder.encode(q, "UTF-8"), "application/x-www-form-urlencoded", timeout = 75_000) } catch (ex: Exception) { null }
                if (t != null && !t.trimStart().startsWith("<")) break else t = null
            }
            if (t == null) return if (file.exists()) parseStreets(file.readText()) else null
            file.parentFile?.mkdirs(); file.writeText(t)
            t
        }
        return parseStreets(text)
    }

    /** "name<TAB>lat<TAB>lon" lines → one entry per street per neighbourhood. */
    private fun parseStreets(tsv: String): List<Named> {
        val byName = HashMap<String, MutableList<LatLng>>()
        val out = ArrayList<Named>()
        for (line in tsv.lineSequence()) {
            val f = line.split('\t')
            if (f.size < 3 || f[0].isBlank()) continue
            val pos = LatLng(f[1].toDoubleOrNull() ?: continue, f[2].toDoubleOrNull() ?: continue)
            val seen = byName.getOrPut(f[0]) { ArrayList() }
            if (seen.any { Geo.distance(it, pos) < 1200 }) continue // same street, next piece
            seen += pos
            val full = sound(f[0])
            out += Named(full.split(Regex("[\\s,.\\-()]+")).filter { it.isNotEmpty() }, full, f[0], pos, "🛣", 2)
        }
        return out
    }

    /** Set when TomTom says the key isn't allowed to search (Search API not ticked on the key). */
    @Volatile var tomtomSearchBlockedUntil = 0L

    private fun tomtomOk() = Traffic.key.isNotEmpty() && System.currentTimeMillis() > tomtomSearchBlockedUntil

    private fun tryTomtom(query: String, near: LatLng?, typeahead: Boolean): List<Place>? {
        if (!tomtomOk()) return null
        return try { tomtom(query, near, typeahead) } catch (e: Exception) {
            // 403 = the key has no Search API: don't keep paying the round trip
            if (e.message?.contains("403") == true) tomtomSearchBlockedUntil = System.currentTimeMillis() + 6 * 3_600_000
            null
        }
    }

    /** Puts results within 40 km first, nearest first; far ones keep the service's order after them. */
    fun nearFirst(list: List<Place>, near: LatLng?): List<Place> {
        if (near == null) return list
        val (close, far) = list.partition { Geo.distance(near, it.pos) < 40_000 }
        return close.sortedBy { Geo.distance(near, it.pos) } + far
    }

    /** Search-as-you-type suggestions: TomTom, then Photon, then Nominatim (whole words). */
    fun suggest(query: String, near: LatLng?): List<Place> {
        tryTomtom(query, near, true)?.let { return it }
        tryPhoton(query, near)?.takeIf { it.isNotEmpty() }?.let { return it }
        return nominatim(query, near)
    }

    @Volatile private var photonDownUntil = 0L

    @Volatile private var photonFails = 0

    /**
     * OpenStreetMap places (caves, sights, small places TomTom doesn't have): Photon, once more
     * after a short wait if it fails (it often answers 503 for a moment), else Nominatim.
     */
    private fun tryPhoton(query: String, near: LatLng?): List<Place>? {
        if (System.currentTimeMillis() >= photonDownUntil) {
            for (attempt in 0..1) {
                try { return photon(query, near).also { photonFails = 0 } } catch (e: Exception) {
                    if (attempt == 0) try { Thread.sleep(350) } catch (_: InterruptedException) { return null }
                }
            }
            // down for a minute only after failing twice in a row
            if (++photonFails >= 2) { photonDownUntil = System.currentTimeMillis() + 60_000; photonFails = 0 }
        }
        return null // (find() then asks Nominatim)
    }


    /** Photon (OpenStreetMap data); short timeout, it's only a fallback now. */
    fun photon(query: String, near: LatLng?, townsOnly: Boolean = false): List<Place> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        // lang=default: the names as on the map (Ναύπλιο, not Nafplio)
        var url = "https://photon.komoot.io/api/?q=$q&lang=default&limit=" + if (townsOnly) "4&osm_tag=place" else "8"
        if (near != null) url += "&lat=${near.lat}&lon=${near.lon}&zoom=12&location_bias_scale=0.3"
        val features = JSONObject(Http.get(url, timeout = 3000)).getJSONArray("features")
        val out = mutableListOf<Place>()
        for (i in 0 until features.length()) {
            val f = features.getJSONObject(i)
            val c = f.getJSONObject("geometry").getJSONArray("coordinates")
            val p = f.getJSONObject("properties")
            fun s(k: String) = p.optString(k).takeIf { it.isNotBlank() && it != "null" }
            val street = listOfNotNull(s("street"), s("housenumber")).joinToString(" ").ifBlank { null }
            val name = s("name") ?: street ?: s("city") ?: continue
            val address = listOfNotNull(
                street.takeIf { it != name },
                s("district") ?: s("locality"),
                s("city").takeIf { it != name },
                s("state").takeIf { s("city") == null },
                s("country"),
            ).distinct().joinToString(", ")
            val town = if (s("osm_key") != "place") 0 else when (s("osm_value")) {
                "city", "town" -> 3
                "village", "island" -> 2
                else -> 1 // suburb, quarter, neighbourhood, hamlet, locality
            }
            out += Place(name, address, LatLng(c.getDouble(1), c.getDouble(0)), icon(s("osm_key"), s("osm_value")), town = town)
        }
        return out.distinctBy { it.name + "|" + it.address }
    }

    /** Full search on Enter: TomTom, then Photon, then Nominatim. */
    fun search(query: String, near: LatLng?): List<Place> {
        tryTomtom(query, near, false)?.takeIf { it.isNotEmpty() }?.let { return it }
        tryPhoton(query, near)?.takeIf { it.isNotEmpty() }?.let { return it }
        return nominatim(query, near)
    }

    /**
     * OpenStreetMap's Nominatim. First only around the user (~25 km), also as Greek if typed in
     * Latin letters; the wider region only if nothing is near.
     */
    fun nominatim(query: String, near: LatLng?): List<Place> {
        if (near != null) {
            val local = nominatimOnce(query, near, 0.25, bounded = true)
            if (local.isNotEmpty()) return local
            val greek = greeklish(query)
            if (greek != query) nominatimOnce(greek, near, 0.25, bounded = true).let { if (it.isNotEmpty()) return it }
        }
        return nominatimOnce(query, near, 0.6, bounded = false)
    }

    private fun nominatimOnce(query: String, near: LatLng?, d: Double, bounded: Boolean): List<Place> {
        val q = URLEncoder.encode(query, "UTF-8")
        var url = "https://nominatim.openstreetmap.org/search?q=$q&format=jsonv2&limit=8"
        if (near != null) {
            url += "&viewbox=${near.lon - d},${near.lat + d},${near.lon + d},${near.lat - d}"
            if (bounded) url += "&bounded=1"
        }
        val arr = JSONArray(Http.get(url, timeout = 6000))
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val display = o.optString("display_name")
            val parts = display.split(", ")
            var name = o.optString("name").ifBlank { parts.first() }
            var rest = display.removePrefix(name).trimStart(',', ' ')
            // a house: "32, Έγγελη, Dourgouti…" → "Έγγελη 32", "Dourgouti…"
            if (name.isNotEmpty() && name.all { it.isDigit() || it in "-/ΑΒΓαβγabc" } && parts.size > 1) {
                name = "${parts[1]} ${parts[0]}"; rest = parts.drop(2).joinToString(", ")
            }
            Place(
                name, rest,
                LatLng(o.getString("lat").toDouble(), o.getString("lon").toDouble()),
                icon(o.optString("category"), o.optString("type")),
            )
        }
    }

    fun icon(key: String?, value: String?): String = when {
        value == "fuel" -> "⛽"
        value == "charging_station" -> "🔌"
        value in setOf("restaurant", "fast_food", "food_court") -> "🍽"
        value == "cafe" -> "☕"
        value in setOf("ice_cream", "bakery", "pastry", "confectionery", "chocolate") -> "🍰"
        value == "dentist" -> "🦷"
        value in setOf("bank", "atm") -> "🏦"
        value == "place_of_worship" -> "⛪"
        value in setOf("car_repair", "car", "tyres") -> "🔧"
        value in setOf("bar", "pub", "nightclub") -> "🍺"
        value == "parking" -> "🅿"
        value in setOf("hospital", "clinic", "doctors") -> "🏥"
        value == "pharmacy" -> "💊"
        value in setOf("school", "university", "college") -> "🎓"
        value in setOf("supermarket", "convenience") -> "🛒"
        key == "shop" -> "🛍"
        value in setOf("hotel", "hostel", "guest_house", "motel") -> "🏨"
        value in setOf("museum", "attraction", "monument", "castle", "ruins", "cave_entrance", "archaeological_site", "viewpoint", "waterfall") -> "🏛"
        key == "aeroway" -> "✈"
        key == "railway" || value == "bus_station" -> "🚉"
        value in setOf("beach") -> "🏖"
        key == "leisure" || value in setOf("park", "forest", "wood", "peak") -> "🌳"
        key == "place" -> "🏙"
        key == "highway" -> "🛣"
        key == "building" || value == "house" -> "🏠"
        else -> "📍"
    }

    // ---- places near me by category (Fuel, Food, Parking) ----

    /** Places of an OSM amenity type around [near], nearest first (TomTom, Nominatim as fallback). */
    /**
     * Car parks, fuel, food… straight from the map data on the phone (the tiles around [near]
     * are usually already there, since the map just showed them): no waiting for the internet.
     */
    @Volatile private var nearbyCache: Triple<String, LatLng, List<Place>>? = null

    /** Ready the car parks at the destination before arriving (called near the end of a trip). */
    fun prefetchNearby(amenities: List<String>, near: LatLng) {
        val key = amenities.joinToString()
        nearbyCache?.let { (k, at, _) -> if (k == key && Geo.distance(at, near) < 150) return }
        nearbyCache = Triple(key, near, nearbyLocal(amenities, near))
    }

    fun nearbyLocal(amenities: List<String>, near: LatLng): List<Place> {
        val key = amenities.joinToString()
        nearbyCache?.let { (k, at, list) ->
            if (k == key && Geo.distance(at, near) < 400) return list.sortedBy { Geo.distance(near, it.pos) }
        }
        return scanNearby(amenities, near)
    }

    /**
     * Everything for a "nearby" list after the instant local one: the online places, fuel prices
     * for stations, star ratings when a Google key is set.
     */
    fun nearbyFull(amenities: List<String>, near: LatLng, local: List<Place>): List<Place>? {
        // prices load alongside the place search
        var area: Fuel.Area? = null
        val prices = if ("fuel" in amenities) Thread { area = try { Fuel.near(near) } catch (e: Exception) { null } }.apply { start() } else null
        val online = try { nearby(amenities, near) } catch (e: Exception) { null }
        prices?.join(9000)
        var list: List<Place> = if (online == null && local.isEmpty() && area == null) return null else mergeNearby(local, online, near)
        if (area != null) Fuel.asPlaces(near, list)?.let { list = it.take(15) }
        list = try { Reviews.rate(list, near, amenities) } catch (e: Exception) { list }
        return list
    }

    /**
     * Fuel stations and (if you want them) chargers, fetched separately so neither crowds the
     * other out of the list. Null when nothing could be loaded at all.
     */
    fun fuelAndChargers(near: LatLng, chargers: Boolean): List<Place>? {
        val fuel = nearbyFull(listOf("fuel"), near, nearbyLocal(listOf("fuel"), near))
        val ev = if (chargers) nearbyFull(listOf("charging_station"), near, emptyList())?.filter { it.icon == "🔌" } else emptyList()
        if (fuel == null && ev.isNullOrEmpty()) return null
        return fuel.orEmpty() + ev.orEmpty().filter { c -> fuel.orEmpty().none { Geo.distance(it.pos, c.pos) < 25 && it.name == c.name } }
    }

    /** Local first, then the online services' extra places (not already listed). */
    fun mergeNearby(local: List<Place>, online: List<Place>?, near: LatLng): List<Place> {
        val out = ArrayList(local)
        for (p in online.orEmpty()) if (out.none { Geo.distance(it.pos, p.pos) < 60 }) out += p
        return out.sortedBy { Geo.distance(near, it.pos) }.take(12)
    }

    private fun scanNearby(amenities: List<String>, near: LatLng): List<Place> {
        val classes = amenities.flatMap {
            when (it) {
                "restaurant", "fast_food" -> listOf("restaurant", "fast_food")
                "charging_station" -> listOf("fuel", "charging_station")
                else -> listOf(it)
            }
        }.toSet()
        val z = VectorSource.MAX_Z; val n = 1 shl z
        val fx = Geo.x(near.lon, 1.0) * n; val fy = Geo.y(near.lat, 1.0) * n
        val out = ArrayList<Pair<Place, Double>>()
        for (ty in fy.toInt() - 1..fy.toInt() + 1) for (tx in fx.toInt() - 1..fx.toInt() + 1) {
            val vt = VectorSource.fetch(z, tx, ty) ?: continue
            for (f in vt.features) {
                if (f.layer != "poi" || f.type != 1) continue
                val cls = f["class"]; val sub = f["subclass"]
                if (cls !in classes && sub !in classes) continue
                val part = f.parts.firstOrNull() ?: continue
                if (part.size < 2) continue
                val pos = LatLng(Geo.lat((ty + part[1] / f.extent.toDouble()) / n, 1.0), Geo.lon((tx + part[0] / f.extent.toDouble()) / n, 1.0))
                val d = Geo.distance(near, pos)
                if (d > 2500) continue
                val kind = when (cls) { "parking" -> "Car park"; "fuel" -> "Fuel"; "cafe" -> "Café"; else -> "Food" }
                val name = f["name"]?.takeIf { it.isNotBlank() } ?: kind
                out += Place(name, kind, pos, icon("amenity", if (cls == "parking") "parking" else sub ?: cls)) to d
            }
        }
        return out.sortedBy { it.second }.map { it.first }
            .fold(ArrayList<Place>()) { acc, p -> if (acc.none { Geo.distance(it.pos, p.pos) < 40 }) acc += p; acc }
            .take(12)
    }

    fun nearby(amenities: List<String>, near: LatLng): List<Place> {
        val cats = amenities.mapNotNull {
            when (it) {
                "fuel" -> "7311"; "charging_station" -> "7309"; "restaurant", "fast_food" -> "7315"
                "cafe" -> "9376"; "parking" -> "7313,7369"; else -> null
            }
        }.distinct().joinToString(",")
        if (tomtomOk() && cats.isNotEmpty()) {
            try {
                val url = "https://api.tomtom.com/search/2/nearbySearch/.json?key=${Traffic.key}&lat=${near.lat}&lon=${near.lon}" +
                    "&radius=6000&limit=15&categorySet=$cats&language=$searchLang"
                val r = parseTomTom(Http.get(url, timeout = 6000)).sortedBy { Geo.distance(near, it.pos) }
                if (r.isNotEmpty()) return r
            } catch (_: Exception) {}
        }
        val out = mutableListOf<Place>()
        for (span in listOf(0.025, 0.08)) {
            for (a in amenities) {
                val url = "https://nominatim.openstreetmap.org/search?amenity=$a&format=jsonv2&limit=15&bounded=1" +
                    "&viewbox=${near.lon - span},${near.lat + span},${near.lon + span},${near.lat - span}"
                val arr = try { JSONArray(Http.get(url)) } catch (e: Exception) { continue }
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    if (o.optString("type") != a) continue
                    val display = o.optString("display_name")
                    val named = o.optString("name").takeIf { it.isNotBlank() }
                    // unnamed: say what it is, not the house number Nominatim starts the address with
                    val name = named ?: when (a) {
                        "fuel" -> "Fuel"; "charging_station" -> "Charging"; "parking" -> "Car park"
                        "cafe" -> "Café"; else -> "Food"
                    }
                    out += Place(
                        name, (if (named != null) display.removePrefix(named) else display).trimStart(',', ' ').split(", ").take(2).joinToString(", "),
                        LatLng(o.getString("lat").toDouble(), o.getString("lon").toDouble()),
                        icon("amenity", a),
                    )
                }
            }
            if (out.size >= 4) break
        }
        return out.distinctBy { "${it.name}|${(it.pos.lat * 5000).toInt()}|${(it.pos.lon * 5000).toInt()}" }
            .sortedBy { Geo.distance(near, it.pos) }.take(12)
    }

    // ---- home & work ----

    fun saved(key: String): Place? {
        if (!::appContext.isInitialized) return null
        val raw = appContext.getSharedPreferences("gamemap", Context.MODE_PRIVATE).getString("place_$key", null) ?: return null
        return try {
            val o = JSONObject(raw)
            Place(o.getString("n"), o.optString("a"), LatLng(o.getDouble("lat"), o.getDouble("lon")), if (key == "home") "🏠" else "💼")
        } catch (e: Exception) { null }
    }

    fun save(key: String, p: Place) {
        val o = JSONObject().put("n", p.name).put("a", p.address).put("lat", p.pos.lat).put("lon", p.pos.lon)
        appContext.getSharedPreferences("gamemap", Context.MODE_PRIVATE).edit().putString("place_$key", o.toString()).apply()
    }

    // ---- live ETAs for the home screen (TomTom, cached 5 minutes) ----

    private val etaCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Traffic.Eta>>()

    fun cachedEta(p: Place): Traffic.Eta? = etaCache[key(p)]?.takeIf { System.currentTimeMillis() - it.first < 300_000 }?.second

    fun refreshEta(p: Place, from: LatLng): Traffic.Eta? {
        cachedEta(p)?.let { return it }
        if (Geo.distance(from, p.pos) < 150) return null
        val e = try { Traffic.eta(from, p.pos) } catch (e: Exception) { null } ?: return null
        etaCache[key(p)] = System.currentTimeMillis() to e
        return e
    }

    private fun key(p: Place) = "${(p.pos.lat * 10000).toInt()},${(p.pos.lon * 10000).toInt()}"

    // ---- recent destinations ----

    fun recents(): List<Place> {
        if (!::appContext.isInitialized) return emptyList()
        val raw = appContext.getSharedPreferences("gamemap", Context.MODE_PRIVATE).getString("recents", "[]")
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                Place(o.getString("n"), o.optString("a"), LatLng(o.getDouble("lat"), o.getDouble("lon")), "🕘")
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ---- saved places (favourites) ----

    /**
     * A saved place. With a [label] ("Dad", "Gym") it's a shortcut: it shows by that name (and
     * [icon]) on the home screen, in the car, and first in search when you type the label.
     */
    class Saved(val name: String, val address: String, val pos: LatLng, val label: String?, val icon: String?) {
        fun place(): Place = if (label.isNullOrBlank()) Place(name, address, pos, icon ?: "★")
            else Place(label, listOf(name, address).filter { it.isNotBlank() && it != label }.distinct().joinToString(", "), pos, icon ?: "★")
    }

    fun savedList(): List<Saved> {
        if (!::appContext.isInitialized) return emptyList()
        val raw = appContext.getSharedPreferences("gamemap", Context.MODE_PRIVATE).getString("favourites", "[]")
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                Saved(o.getString("n"), o.optString("a"), LatLng(o.getDouble("lat"), o.getDouble("lon")),
                    o.optString("l").ifBlank { null }, o.optString("i").ifBlank { null })
            }
        } catch (e: Exception) { emptyList() }
    }

    private fun writeSaved(list: List<Saved>) {
        val a = JSONArray()
        list.take(60).forEach { s ->
            val o = JSONObject().put("n", s.name).put("a", s.address).put("lat", s.pos.lat).put("lon", s.pos.lon)
            s.label?.let { o.put("l", it) }; s.icon?.let { o.put("i", it) }
            a.put(o)
        }
        appContext.getSharedPreferences("gamemap", Context.MODE_PRIVATE).edit().putString("favourites", a.toString()).apply()
        savedChanged.fire()
    }

    val savedChanged = Signal()

    fun savedPlaces(): List<Place> = savedList().map { it.place() }

    /** The saved place here, if any (within 25 m). */
    fun savedAt(p: LatLng): Saved? = savedList().firstOrNull { Geo.distance(it.pos, p) < 25 }

    /** The shortcuts with a label, in the order they were made. */
    fun labelled(): List<Saved> = savedList().filter { !it.label.isNullOrBlank() }.reversed()

    fun isSaved(p: Place) = savedAt(p.pos) != null

    /** Saves [p] (or changes its label and icon if it's saved already). */
    fun saveFavourite(p: Place, label: String?, icon: String?) {
        val old = savedAt(p.pos)
        val s = Saved(old?.name ?: p.name, old?.address ?: p.address, old?.pos ?: p.pos, label?.trim()?.take(24)?.ifBlank { null }, icon)
        val list = savedList().toMutableList()
        val i = list.indexOfFirst { Geo.distance(it.pos, s.pos) < 25 }
        if (i >= 0) list[i] = s else list.add(0, s) // edited: stays where it was
        writeSaved(list)
    }

    fun unsave(p: Place) = writeSaved(savedList().filterNot { Geo.distance(it.pos, p.pos) < 25 })

    /** Saves or un-saves a place; returns true when it is saved now. */
    fun toggleSaved(p: Place): Boolean {
        if (isSaved(p)) { unsave(p); return false }
        saveFavourite(p, null, null); return true
    }

    /** Saved places whose label (or name) is what was typed: they come first in search. */
    fun savedMatches(query: String): List<Place> {
        val q = Query(query)
        if (q.words.isEmpty()) return emptyList()
        return savedList().filter { s ->
            (!s.label.isNullOrBlank() && nameScore(q, s.label) >= 0.99) || nameScore(q, s.name) >= 0.99
        }.sortedBy { if (it.label.isNullOrBlank()) 1 else 0 }.map { it.place() }
    }

    /** Takes one place out of the recent searches (the ✕ next to it). */
    fun removeRecent(p: Place) {
        val list = recents().filterNot { Geo.distance(it.pos, p.pos) < 30 }
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("n", it.name).put("a", it.address).put("lat", it.pos.lat).put("lon", it.pos.lon)) }
        appContext.getSharedPreferences("gamemap", Context.MODE_PRIVATE).edit().putString("recents", a.toString()).apply()
    }

    fun addRecent(p: Place) {
        val list = (listOf(p) + recents().filterNot { Geo.distance(it.pos, p.pos) < 30 }).take(8)
        val a = JSONArray()
        list.forEach {
            a.put(JSONObject().put("n", it.name).put("a", it.address).put("lat", it.pos.lat).put("lon", it.pos.lon))
        }
        appContext.getSharedPreferences("gamemap", Context.MODE_PRIVATE).edit().putString("recents", a.toString()).apply()
    }
}

object Http {
    fun get(url: String, timeout: Int = 15000): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = minOf(10000, timeout)
        c.readTimeout = timeout
        c.setRequestProperty("User-Agent", "GameRoute/1.0 (Android; personal use)")
        c.setRequestProperty("Accept-Language", java.util.Locale.getDefault().language)
        // read to the end and closed (not disconnected): the connection goes back to the pool, so
        // the next request to the same server skips the TCP + TLS handshake (≈0.3–0.8 s on mobile data)
        try {
            if (c.responseCode != 200) throw RuntimeException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) { c.disconnect(); throw e }
    }

    fun post(url: String, body: String, contentType: String = "application/json", timeout: Int = 20000): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = timeout
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", contentType)
        c.setRequestProperty("User-Agent", "GameRoute/1.0 (Android; personal use)")
        try {
            c.outputStream.use { it.write(body.toByteArray()) }
            if (c.responseCode != 200) throw RuntimeException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) { c.disconnect(); throw e }
    }
}

