package gr.gamemap

import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

/**
 * Route planning and turn-by-turn navigation. OSRM draws the routes and the turns; TomTom
 * (when there's a key) adds live traffic times, jams and speed limits, and spots faster routes.
 */
object NavEngine {
    val changed = Signal()

    // ------------------------------------------------------------- planning (route preview)

    var planning = false
        private set
    /**
     * The route choice was started in the car: it stays on the car's screen (the phone keeps its own
     * map, untouched). Started on the phone, it shows on both, as before.
     */
    @Volatile var planInCar = false
        internal set
    /** Shows the routes being chosen: on the car only, when they were asked for there. */
    private fun fitPlan(points: List<LatLng>) = if (planInCar) MapState.car.requestFit(points) else MapState.requestFit(points)
    /** Planning, as this screen sees it. */
    fun planningOn(car: Boolean) = planning && (car || !planInCar)
    var planPlace: Place? = null
        private set
    var options: List<RouteOption> = emptyList()
        private set
    var selected = 0
        private set
    var planLoading = false
        private set
    var planError: String? = null
        private set
    /** Planned departure (ms) for "Leave later", null = now. */
    var departAt: Long? = null
        private set
    private var userPicked = false
    private var planSeq = 0

    // ------------------------------------------------------------- driving

    var destination: LatLng? = null
        private set
    var destinationName = ""
        private set
    var current: RouteOption? = null
        private set
    val route: List<LatLng> get() = current?.points ?: emptyList()
    val steps: List<NavStep> get() = current?.steps ?: emptyList()
    var loading = false
        private set
    var arrived = false
        private set
    /** When I arrived (kept after the arrival card closes: the race standings need it). */
    var arrivedAtMs = 0L
        private set

    internal fun debugArrive(at: Long) { arrived = true; arrivedAtMs = at; changed.fire() }
    var error: String? = null
        private set
    var nextStep = -1
    /** The route [nextStep] counts in (a new route starts the count again). */
    private var stepRoute: RouteOption? = null
        private set
    var distanceToNext = 0.0
        private set
    var remainingDistance = 0.0
        private set
    var remainingSeconds = 0.0
        private set
    /** How far along the current route we are, metres. */
    var along = 0.0
        private set
    var trafficDelaySeconds = 0
        private set
    private var trafficRatio = 1.0
    private var trafficAt = 0L

    val active get() = destination != null
    /** Index of the route point just behind us (the line is only drawn ahead of it). */
    val passedIndex: Int get() = nearest
    val currentStep: NavStep? get() = steps.getOrNull(nextStep)

    /** The maneuver after the next one, if it comes soon after it ("Then …"). */
    /** How far the turn after the next one comes after it (metres), when it's close. */
    val followingGap: Double?
        get() {
            val r = current ?: return null
            val a = steps.getOrNull(nextStep) ?: return null
            val b = followingStep ?: return null
            return r.cum[b.routeIndex] - r.cum[a.routeIndex]
        }

    /** Driven so far on this trip, metres (the turn box's trip counter). */
    val tripSoFar: Double get() = tripMeters

    val followingStep: NavStep?
        get() {
            val r = current ?: return null
            val a = steps.getOrNull(nextStep) ?: return null
            val b = steps.getOrNull(nextStep + 1) ?: return null
            if (a.direction == "arrive") return null
            return b.takeIf { r.cum[it.routeIndex] - r.cum[a.routeIndex] < 400 }
        }

    /** The kind of road we're on, and the one we turn onto next (from the map data; see RoadKind). */
    @Volatile var roadHere: String? = null
        private set
    @Volatile var roadNext: String? = null
        private set
    private var roadPending: String? = null
    private var roadAlong = -1e9
    private var roadStepAt = -2
    private var roadRoute: RouteOption? = null

    /** Looks the road kinds up again once we've moved 40 m or passed a turn (cheap otherwise). */
    fun updateRoadKinds() {
        val r = current ?: run { roadHere = null; roadNext = null; return }
        if (r === roadRoute && Math.abs(along - roadAlong) < 40 && nextStep == roadStepAt) return
        if (r !== roadRoute) { roadHere = null; roadPending = null }
        roadRoute = r; roadAlong = along; roadStepAt = nextStep
        // a new kind of road counts once it's seen twice in a row (~80 m): a side street or a
        // slip road brushing past doesn't make the camera zoom in and out
        // (not known here, the map data still loading: keep the last kind, so the zoom doesn't jump)
        val k = try { RoadKind.along(r, along + 15) } catch (e: Exception) { null }
        if (k != null) {
            if (k == roadHere || roadHere == null || k == roadPending) { roadHere = k; roadPending = null } else roadPending = k
        }
        roadNext = try {
            steps.getOrNull(nextStep)?.takeIf { it.direction != "arrive" && it.routeIndex in r.cum.indices }
                ?.let { RoadKind.along(r, r.cum[it.routeIndex] + 35) }
        } catch (e: Exception) { null }
    }

    /** A new route couldn't be fetched (no signal): the one we have is still followed. */
    @Volatile var noSignal = false
        private set

    fun noSignalText() = if (Offline.routingReady()) "No signal: offline routing" else "No signal: following your route"

    /** The route stops short of the place (dirt track or path after it): what to tell the driver. */
    @Volatile var endNote: String? = null

    // ------------------------------------------------------------- resume after the car app closed

    private var resumePlace: Place? = null

    private fun saveResume() {
        if (!::appContext.isInitialized) return
        val p = resumePlace ?: return
        fun j(x: Place) = org.json.JSONObject().put("n", x.name).put("a", x.address).put("la", x.pos.lat).put("lo", x.pos.lon).put("i", x.icon)
            .apply { x.entry?.let { put("ela", it.lat); put("elo", it.lon) } }
        val o = j(p).put("at", System.currentTimeMillis()).put("stops", org.json.JSONArray().apply { stops.forEach { put(j(it)) } })
        try { prefs().edit().putString("resume", o.toString()).apply() } catch (_: Exception) {}
    }

    private fun clearResume() {
        resumePlace = null
        if (::appContext.isInitialized) try { prefs().edit().remove("resume").apply() } catch (_: Exception) {}
    }

    /** The route that was running when the car app closed (within 45 min), with its stops. */
    fun resumable(ctx: Context): Pair<Place, List<Place>>? {
        if (active || planning) return null
        val raw = ctx.getSharedPreferences("gamemap", Context.MODE_PRIVATE).getString("resume", null) ?: return null
        return try {
            val o = org.json.JSONObject(raw)
            if (System.currentTimeMillis() - o.getLong("at") > 45 * 60_000) return null
            fun pl(x: org.json.JSONObject) = Place(x.getString("n"), x.optString("a"), LatLng(x.getDouble("la"), x.getDouble("lo")), x.optString("i", "📍"),
                entry = if (x.has("ela")) LatLng(x.getDouble("ela"), x.getDouble("elo")) else null)
            val st = o.optJSONArray("stops")
            pl(o) to (0 until (st?.length() ?: 0)).map { pl(st!!.getJSONObject(it)) }
        } catch (e: Exception) { null }
    }

    /** Starts that route again (from where the car is now). */
    fun resume(ctx: Context, place: Place, via: List<Place>) {
        CrashLog.note("resume navigation")
        appContext = ctx.applicationContext
        planning = false; planSeq++
        begin(place)
        stops.addAll(via)
        saveResume()
        fetchRoute()
    }

    /** The speed limit where we are now, km/h, if known. */
    val speedLimit: Int?
        get() = current?.limits?.firstOrNull { along >= it.start - 5 && along < it.end + 5 }?.value

    /** The next jam on the route within 3 km: (metres to it, delay seconds). */
    val jamAhead: Pair<Double, Int>?
        get() {
            val r = current ?: return null
            val j = r.traffic.firstOrNull { it.end > along && it.start - along < 3000 && (it.value >= 2 || it.extra >= 60) }
                ?: return null
            return (j.start - along).coerceAtLeast(0.0) to j.extra
        }

    // ------------------------------------------------------------- trip stats

    /** The trip that just ended (arrival card), until dismissed. */
    var lastTrip: Explorer.Trip? = null
        private set
    private var tripStart = 0L
    private var tripMeters = 0.0
    /** The first route's length (the trip as asked for). */
    private var tripPlanned = -1.0
    private var tripSaved = 0
    private var tripBonus = 0
    private var lastPos: LatLng? = null

    fun dismissTrip() {
        CarParking.list = null
        lastTrip = null; tripEndAt = null; tripEndTime = 0L; awaySince = 0L
        changed.fire()
    }

    /** I finished a race and others are still racing (no stale ones: they may never come). */
    fun raceGoingOn() = Convoy.myPlace() != null &&
        Convoy.members.values.any { !it.along && !it.sharing && !it.stale && it.state != "arrived" && it.state != "left" }

    /** Where and when the last trip ended (the card goes by itself once you drive off, or after a while). */
    private var tripEndAt: LatLng? = null
    private var tripEndTime = 0L
    private var awaySince = 0L

    /** The arrival card left open: it closes when you drive off (300 m away, or moving 20 s), or after 30 seconds. */
    private fun autoDismissTrip(pos: LatLng, speed: Float) {
        if (lastTrip == null || active) return
        // a race: the card (and the standings) stay until every racer is in
        if (raceGoingOn()) { tripEndTime = System.currentTimeMillis(); return }
        val now = System.currentTimeMillis()
        val end = tripEndAt ?: run { tripEndAt = pos; tripEndTime = now; return }
        if (speed > 4f) { if (awaySince == 0L) awaySince = now } else awaySince = 0L
        if (Geo.distance(end, pos) > 300 || (awaySince > 0 && now - awaySince > 20_000) || now - tripEndTime > 30_000) {
            CrashLog.note("trip card closed by itself")
            dismissTrip()
        }
    }

    /** Trips shorter than this earn no XP (else a few quick 100 m "trips" would farm levels). */
    const val MIN_XP_METERS = 200.0

    private fun finishTrip() {
        val secs = ((System.currentTimeMillis() - tripStart) / 1000).toInt()
        val levelBefore = Explorer.level
        // short by the route asked for, or by what was really driven (GPS wobble while parked adds a little)
        val tooShort = tripMeters < MIN_XP_METERS || (tripPlanned >= 0 && tripPlanned < MIN_XP_METERS)
        // the convoy: arrived together on its route; my place among the racers (counted here: my
        // own "arrived" goes out just after this)
        val inConvoy = Convoy.active && Convoy.onConvoyRoute && !Convoy.meSharing && Convoy.members.isNotEmpty()
        val racers = Convoy.members.values.filter { !it.along && !it.sharing && it.state != "left" }
        val racing = inConvoy && !Convoy.meAlong && racers.isNotEmpty()
        val place = if (racing) 1 + racers.count { it.state == "arrived" } else null
        // a simulated drive (test builds): no XP, no trip in the history
        val sim = Dev.simTrip; Dev.simTrip = false
        val parts = if (tooShort || sim) emptyList() else Xp.finish(tripMeters, tripBonus, Explorer.tripFound, inConvoy, place, racers.size + 1)
        val xp = if (sim) 0 else if (tooShort) Explorer.tripFound * 25 else parts.sumOf { it.xp }
        tripEndAt = null; tripEndTime = 0L; awaySince = 0L
        lastTrip = Explorer.Trip(
            destinationName, System.currentTimeMillis(), secs, tripMeters, tripSaved,
            xp, Explorer.tripFound, levelBefore, parts,
        )
        if (!sim) lastTrip?.let { Explorer.record(it) }
    }

    // ------------------------------------------------------------- faster route offers

    class Offer(val option: RouteOption, val savingSeconds: Int, val createdAt: Long)

    var offer: Offer? = null
        private set
    const val OFFER_SECONDS = 12
    private val declined = mutableListOf<Pair<RouteOption, Long>>()

    private var secondsPerMeter = 0.08

    /**
     * A new traffic factor. A change of more than a third only counts when the next answer
     * agrees (within 15%): one odd answer no longer moves the arrival time by minutes.
     */
    private var pendingRatio: Double? = null
    private fun acceptRatio(r: Double, why: String) {
        val old = trafficRatio
        val big = Math.abs(r / old - 1) > 0.33
        val p = pendingRatio
        if (big && (p == null || Math.abs(r / p - 1) > 0.15)) {
            pendingRatio = r
            CrashLog.note("arrival: $why says ×${"%.2f".format(r)} (now ×${"%.2f".format(old)}), waiting for a second answer")
            return
        }
        pendingRatio = null
        trafficRatio = r
        if (Math.abs(r / old - 1) > 0.1) CrashLog.note("arrival: $why ×${"%.2f".format(old)} → ×${"%.2f".format(r)}")
    }

    // ---------------------------------------------------------------- time left (steady)
    /**
     * Time from each route point to the end, without traffic. Not "distance × the route's average
     * speed": a stretch of motorway and a stretch of city streets take very different times, so the
     * route's own time is spread over its stretches by their speed limits (where known), else evenly.
     */
    private var timeLeftOf: DoubleArray? = null
    private var timeLeftFor: RouteOption? = null
    private var timeLeftLimits: List<Stretch>? = null

    private fun plainLeft(r: RouteOption, at: Double): Double {
        if (timeLeftFor !== r || timeLeftLimits !== r.limits) {
            timeLeftFor = r; timeLeftLimits = r.limits
            val n = r.points.size
            val w = DoubleArray(n)  // weight of the stretch ending at point i
            var total = 0.0
            for (i in 1 until n) {
                val mid = (r.cum[i - 1] + r.cum[i]) / 2
                val kmh = r.limits.firstOrNull { mid >= it.start && mid < it.end }?.value?.takeIf { it in 10..140 }
                // typical driving is a bit under the limit, slower still on slow roads (lights, corners)
                val speed = if (kmh != null) (kmh * (if (kmh <= 50) 0.62 else if (kmh <= 90) 0.78 else 0.88)) / 3.6 else 0.0
                w[i] = if (speed > 0) (r.cum[i] - r.cum[i - 1]) / speed else -1.0
            }
            // stretches without a limit: the route's average for the rest
            val known = (1 until n).filter { w[it] >= 0 }
            val knownLen = known.sumOf { r.cum[it] - r.cum[it - 1] }
            val knownT = known.sumOf { w[it] }
            val unknownLen = (r.length - knownLen).coerceAtLeast(0.0)
            val restSpm = if (unknownLen > 1) ((r.duration - knownT).coerceAtLeast(unknownLen * 0.03)) / unknownLen else 0.0
            for (i in 1 until n) { if (w[i] < 0) w[i] = (r.cum[i] - r.cum[i - 1]) * restSpm; total += w[i] }
            // scaled so the whole route still takes the router's time
            val k = if (total > 1) r.duration / total else 1.0
            val left = DoubleArray(n)
            for (i in n - 2 downTo 0) left[i] = left[i + 1] + w[i + 1] * k
            timeLeftOf = left
        }
        val left = timeLeftOf ?: return (r.length - at).coerceAtLeast(0.0) * secondsPerMeter
        var i = java.util.Arrays.binarySearch(r.cum, at.coerceIn(0.0, r.length))
        if (i < 0) i = (-i - 2).coerceAtLeast(0)
        if (i >= r.points.size - 1) return 0.0
        val seg = r.cum[i + 1] - r.cum[i]
        val f = if (seg > 0) ((at - r.cum[i]) / seg).coerceIn(0.0, 1.0) else 0.0
        return left[i] + (left[i + 1] - left[i]) * f
    }

    /** When the arrival shown on screen is (eased towards the real estimate, so it doesn't jump). */
    private var shownArrival = 0L
    private var etaAt = 0L

    /**
     * The arrival time on screen follows the estimate smoothly: small changes at once, big ones
     * (a reroute, a traffic update, one odd GPS fix) eased in over ~15 s instead of a jump.
     */
    private fun setEta(raw: Double) {
        val now = System.currentTimeMillis()
        val target = now + (raw * 1000).toLong()
        val dt = if (etaAt == 0L) 1.0 else ((now - etaAt) / 1000.0).coerceIn(0.0, 5.0)
        etaAt = now
        shownArrival = when {
            shownArrival == 0L || raw < 60 -> target                    // first estimate, or nearly there
            Math.abs(target - shownArrival) < 20_000 -> target          // under 20 s: just follow
            else -> shownArrival + ((target - shownArrival) * (1 - Math.pow(0.86, dt))).toLong()
        }
        remainingSeconds = ((shownArrival - now) / 1000.0).coerceAtLeast(0.0)
    }
    private var nearest = 0
    private var offRouteCount = 0
    private var parkingReady = false
    /**
     * After arriving: the route ends by itself 30 s later, and the arrival card goes with it.
     * A clock of its own (every 5 s), not the GPS: a parked phone may send no fixes at all.
     * Waits while the car's parking list is open (up to 5 minutes) or a race is still on.
     */
    private val closeArrival: Runnable = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            if (arrived) {
                val since = now - arrivedAtMs
                val hold = raceGoingOn() || (CarParking.list != null && since < 300_000)
                if (!hold && since >= ARRIVAL_CLOSE_MS) {
                    CrashLog.note("route ended by itself after arriving")
                    stop(); dismissTrip(); return
                }
                main.postDelayed(this, 5_000); return
            }
            // the route has ended but the card is still up (no GPS fixes to close it): close it here
            if (lastTrip != null && !active) {
                if (raceGoingOn()) { main.postDelayed(this, 5_000); return }
                if (tripEndTime == 0L) tripEndTime = now
                if (now - tripEndTime >= ARRIVAL_CLOSE_MS) { CrashLog.note("trip card closed by itself"); dismissTrip(); return }
                main.postDelayed(this, 5_000)
            }
        }
    }
    const val ARRIVAL_CLOSE_MS = 30_000L
    private var lastRouteAt = 0L
    /** Routes (first route, reroutes, previews): never wait behind anything else. */
    private val io = Executors.newSingleThreadExecutor()
    /** The slow background work (traffic re-timing, faster-route search): its own line. */
    private val bg = Executors.newSingleThreadExecutor(Bg.factory("nav-bg"))
    /** The phone's router, ahead of time: warming up, and a new route at the first sign of leaving. */
    private val spec = Executors.newSingleThreadExecutor(Bg.factory("nav-spec"))
    @Volatile private var specBusy = false

    /**
     * Ready before the turn: the most common miss is carrying straight on past it, so while a turn
     * is coming up (under 300 m) the phone works out the way on from just past the junction. Miss
     * the turn and that route is there at once, on the first fix that shows it.
     */
    private class Missed(val step: Int, val turn: LatLng, val past: LatLng, val bearing: Float, val route: RouteOption, val forRoute: RouteOption)
    @Volatile private var missed: Missed? = null
    @Volatile private var missedBusyFor = -1

    private fun prepareMissedTurn(r: RouteOption) {
        if (!OfflineRouter.present || specBusy) return
        val s = r.steps.getOrNull(nextStep) ?: return
        if (s.direction == "arrive" || s.direction == "straight" || s.type == "depart") return
        if (distanceToNext !in 40.0..300.0) return
        if (missed?.let { it.step == nextStep && it.forRoute === r } == true || missedBusyFor == nextStep) return
        val i = s.routeIndex.coerceIn(1, r.points.size - 1)
        val turn = r.points[i]
        // the way the car comes in (over the last ~25 m before the turn)
        var k = i - 1
        while (k > 0 && Geo.distance(r.points[k], turn) < 25) k--
        val inBearing = Geo.bearing(r.points[k], turn)
        val past = Geo.offset(turn, inBearing, 45.0)
        val dest = destination ?: return
        val via = stops.map { it.pos }
        val step = nextStep
        missedBusyFor = step; specBusy = true
        spec.execute {
            try {
                OfflineRouter.route(past, dest, inBearing, via)?.let { missed = Missed(step, turn, past, inBearing, it, r) }
            } catch (_: Throwable) {
            } finally { specBusy = false; missedBusyFor = -1 }
        }
    }

    /** The car went straight on past the turn it was given: the route prepared for that, if it fits. */
    private fun missedTurnRoute(pos: LatLng, bearing: Float?, r: RouteOption): RouteOption? {
        val m = missed ?: return null
        if (m.forRoute !== r || bearing == null) return null
        if (Math.abs(Geo.angleDiff(bearing, m.bearing)) > 35) return null
        // past the junction, on the stretch straight on from it
        val (_, off) = Geo.project(pos, m.turn, Geo.offset(m.past, m.bearing, 80.0))
        if (off > 20 || Geo.distance(pos, m.turn) < 12) return null
        return m.route
    }
    /** A route worked out at the first sign of leaving: (from where, when, the route). */
    @Volatile private var specRoute: Triple<LatLng, Long, RouteOption>? = null
    @Volatile private var routeSeq = 0
    private val timing = Bg.pool("timing", 3)
    private val main = Handler(Looper.getMainLooper())
    private lateinit var appContext: Context

    private fun prefs() = appContext.getSharedPreferences("gamemap", Context.MODE_PRIVATE)

    /** Switch to a faster route by itself when the prompt runs out (otherwise keep the current one). */
    var autoSwitch: Boolean
        get() = !::appContext.isInitialized || prefs().getBoolean("autoSwitch", true)
        set(v) { prefs().edit().putBoolean("autoSwitch", v).apply() }

    fun init(ctx: Context) {
        appContext = ctx.applicationContext
    }

    var avoidTolls: Boolean
        get() = ::appContext.isInitialized && prefs().getBoolean("avoidTolls", false)
        set(v) { prefs().edit().putBoolean("avoidTolls", v).apply(); changed.fire() }
    var avoidMotorways: Boolean
        get() = ::appContext.isInitialized && prefs().getBoolean("avoidMotorways", false)
        set(v) { prefs().edit().putBoolean("avoidMotorways", v).apply(); changed.fire() }

    /** True if the route uses roads the user asked to avoid. */
    fun breaksAvoid(o: RouteOption) = (avoidTolls && o.hasToll) || (avoidMotorways && o.hasMotorway)

    /** Puts routes that respect "avoid tolls / motorways" first (OSRM can't exclude roads itself). */
    private fun preferAllowed(list: List<RouteOption>): List<RouteOption> {
        val ok = list.filterNot { breaksAvoid(it) }
        return if (ok.isEmpty()) list else ok + list.filter { breaksAvoid(it) }
    }

    private val wantsAlternatives get() = avoidTolls || avoidMotorways

    // ============================================================= planning

    /** Shows the route choices to [place] (the preview), without starting. */
    fun plan(ctx: Context, place: Place) {
        CrashLog.note("plan route")
        planInCar = ctx.javaClass.name.startsWith("androidx.car.app")
        appContext = ctx.applicationContext
        if (active) stop()
        planning = true
        lastTrip = null
        planPlace = place
        endNote = null
        options = emptyList(); selected = 0; userPicked = false
        departAt = null
        planError = null
        fetchOptions()
    }

    private fun fetchOptions() {
        val place = planPlace ?: return
        val from = LocationTracker.position
        val seq = ++planSeq
        if (from == null) {
            planLoading = true; planError = gr.gamemap.L.t("Waiting for GPS…"); changed.fire()
            main.postDelayed({ if (planning && seq == planSeq) fetchOptions() }, 2000)
            return
        }
        planLoading = true; planError = null
        changed.fire()
        if (planInCar) MapState.car.requestFit(listOf(from, place.pos)) else MapState.requestFit(listOf(from, place.pos))
        io.execute {
            var routes = try { Router.routes(from, place.goal, alternatives = true, heading = heading()).also { Tolls.annotate(it) } } catch (e: Exception) { null }
            // the last part is a dirt track or a footpath (a cave, a peak): the route ends where
            // the car road does, and says how much is left
            var goal = place
            val tail = routes?.firstOrNull()?.let { try { RoadKind.dirtTail(it) } catch (e: Exception) { null } }
            if (tail != null) {
                val again = try { Router.routes(from, tail.second, alternatives = true, heading = heading()).also { Tolls.annotate(it) } } catch (e: Exception) { null }
                if (!again.isNullOrEmpty()) {
                    routes = again
                    goal = place.copy(entry = tail.second)
                }
            }
            main.post {
                if (seq != planSeq || !planning) return@post
                if (goal !== place) {
                    planPlace = goal
                    val left = Geo.distance(goal.goal, place.pos)
                    endNote = gr.gamemap.L.t("The road ends ${formatDistance(left)} before ${place.name}: the rest is a dirt track or a path")
                } else endNote = null
                if (routes.isNullOrEmpty()) {
                    planLoading = false; planError = gr.gamemap.L.t("No route found"); changed.fire(); return@post
                }
                options = preferAllowed(routes).take(3)
                selected = 0
                planLoading = false
                fitPlan(options.flatMap { o -> o.points.filterIndexed { i, _ -> i % 10 == 0 } } + place.pos)
                changed.fire()
                evaluateOptions(seq)
            }
        }
    }

    /** TomTom times all the routes at once (each takes ~0.1 s). */
    private fun evaluateOptions(seq: Int) {
        val opts = options
        val depart = departAt
        for (o in opts) timing.execute {
            val r = try { TomTomTime.evaluate(o, 0.0, departAt = depart) } catch (e: Exception) { null }
            main.post {
                if (seq != planSeq) return@post
                if (r == null) { o.timingFailed = true; changed.fire(); return@post }
                // our own time × how congested TomTom says it is (not TomTom's own, much slower, time)
                o.trafficSeconds = (o.duration * r.congestion).toInt(); o.delaySeconds = (o.duration * (r.congestion - 1)).toInt()
                o.traffic = r.traffic; o.limits = r.limits
                if (!userPicked) selected = fastestIndex()
                changed.fire()
            }
        }
    }

    /** True once a route's time is worth showing (TomTom answered, failed, or isn't available). */
    fun timeKnown(o: RouteOption) = o.trafficSeconds != null || o.timingFailed || !Traffic.active

    fun fastestIndex(): Int {
        val allowed = options.indices.filterNot { breaksAvoid(options[it]) }.ifEmpty { options.indices.toList() }
        return allowed.minByOrNull { options[it].seconds } ?: 0
    }

    fun select(i: Int) {
        if (i !in options.indices) return
        selected = i; userPicked = true
        changed.fire()
    }

    /** "Leave later": re-times the routes for a departure at [ms] (null = now). */
    fun setDeparture(ms: Long?) {
        departAt = ms
        options.forEach { it.trafficSeconds = null; it.timingFailed = false; it.delaySeconds = 0; it.traffic = emptyList() }
        changed.fire()
        evaluateOptions(planSeq)
    }

    fun cancelPlan() {
        if (!planning) return
        planning = false; planPlace = null; options = emptyList(); planSeq++
        if (planInCar) MapState.car.recenter() else MapState.recenter()
        changed.fire()
    }

    /** Starts driving the chosen route. */
    fun startPlanned() {
        CrashLog.note("start navigation (planned)")
        val place = planPlace ?: return
        val opt = options.getOrNull(selected)
        planning = false; planSeq++
        begin(place)
        // picking the fastest route counts as time saved against the next best one
        if (opt != null && opt.trafficSeconds != null) {
            val others = options.filter { it !== opt && it.trafficSeconds != null }.minOfOrNull { it.seconds }
            if (others != null && others - opt.seconds >= 60) tripSaved = (others - opt.seconds).toInt()
        }
        if (opt != null) apply(opt, announce = true) else fetchRoute()
        endNote?.let { Voice.say(it) }
    }

    /** Starts navigation straight away (no preview). */
    fun start(ctx: Context, place: Place) {
        CrashLog.note("start navigation")
        appContext = ctx.applicationContext
        planning = false; planSeq++
        endNote = null
        begin(place)
        fetchRoute()
    }

    private fun begin(place: Place) {
        stops.clear()
        Places.addRecent(place)
        lastTrip = null
        shownArrival = 0L; etaAt = 0L   // a new trip: the first estimate shows as it is
        tripStart = System.currentTimeMillis(); tripMeters = 0.0; tripSaved = 0; tripBonus = 0; lastPos = null; tripPlanned = -1.0
        Xp.startTrip()
        Perf.driveStarted()
        Explorer.tripFound = 0
        parkingReady = false
        destination = place.goal // the entrance on the street, when known
        destinationName = place.name
        arrived = false
        error = null
        offer = null; declined.clear(); lastOfferAt = 0L
        trafficRatio = 1.0; trafficDelaySeconds = 0; trafficAt = 0; pendingRatio = null
        current = null; nextStep = -1
        MapState.recenter()
        Voice.reset()
        Convoy.startedDriving()
        estimating = false; lastRealFixAt = 0; tollAhead = null; tollSaid = null
        main.removeCallbacks(tunnel); main.postDelayed(tunnel, 1000)
        // the phone's road file opened (and the roads around here read) now, not at the first reroute
        if (OfflineRouter.present) spec.execute { try { val p = LocationTracker.position; if (p != null) OfflineRouter.route(p, place.goal) else OfflineRouter.ready() } catch (_: Throwable) {} }
        resumePlace = place
        saveResume()
        changed.fire()
        try {
            if (LocationTracker.hasPermission(appContext))
                NavService.update(appContext)
        } catch (_: Exception) {
        }
    }

    /** For convoy friends: how far along this route [p] is (if within ~35 m of it), else null. */
    fun routeDistanceOf(p: LatLng): Double? {
        val r = current ?: return null
        val pts = r.points
        if (pts.size < 2) return null
        // projected onto the segments (not the nearest corner: those can be 100 m apart on a straight road)
        var best = -1.0; var bestD = 35.0
        for (i in 0 until pts.size - 1) {
            val (c, dd) = Geo.project(p, pts[i], pts[i + 1])
            if (dd < bestD) { bestD = dd; best = r.cum[i] + Geo.distance(pts[i], c) }
        }
        return if (best < 0) null else best
    }

    /**
     * The road you're on now: on a route, the road of the turn you last made (or the one you set off
     * on); without one, the map's name for the street under the car. Blank on unnamed roads.
     */
    fun currentRoad(): String {
        val r = current
        if (active && r != null && !arrived) {
            val prev = r.steps.getOrNull(nextStep - 1)?.road ?: r.steps.firstOrNull()?.road
            if (!prev.isNullOrBlank()) return Names.shown(prev)
        }
        val p = LocationTracker.position ?: return ""
        if (LocationTracker.speedMps < 2f && !active) return ""
        // the map's own name for the street (in Greek letters there): in Latin letters in English
        return Names.shown(RoadMatch.roadName(p, LocationTracker.heading).orEmpty())
    }

    /** Settings › Map › "The road you're on": the name under the arrow, on or off. */
    var showRoadName: Boolean
        get() = MapState.prefsOrNull()?.getBoolean("roadName", true) ?: true
        set(v) { MapState.prefsOrNull()?.edit()?.putBoolean("roadName", v)?.apply(); MapState.changed.fire() }

    /** The point at [dist] metres along this route. */
    fun routePointAt(dist: Double): LatLng? {
        val r = current ?: return null
        val pts = r.points
        if (pts.size < 2) return null
        var lo = 0; var hi = pts.size - 1
        while (lo < hi - 1) { val mid = (lo + hi) / 2; if (r.cum[mid] <= dist) lo = mid else hi = mid }
        val seg = (r.cum[hi] - r.cum[lo]).coerceAtLeast(0.1)
        return Geo.lerp(pts[lo], pts[hi], ((dist - r.cum[lo]) / seg).coerceIn(0.0, 1.0))
    }

    // ============================================================= stops on the way

    /** Places to pass on the way to the destination, in order. */
    val stops = java.util.concurrent.CopyOnWriteArrayList<Place>()

    /** Adds a stop (fuel, coffee…) without ending the trip; the route is recalculated through it. */
    fun addStop(p: Place) {
        if (!active || stops.any { Geo.distance(it.pos, p.pos) < 30 }) return
        stops += p
        saveResume()
        Voice.say(if (Voice.lang == Phrases.GREEK) "Προστέθηκε στάση: ${p.name}" else "Stop added: ${p.name}")
        fetchRoute()
    }

    internal fun debugStop(p: Place) { stops += p; changed.fire() }

    fun removeStop(p: Place) {
        if (stops.remove(p)) { saveResume(); fetchRoute() }
    }

    /** Reached the next stop: it's done, the route carries on to the destination. */
    private fun checkStops(pos: LatLng) {
        val s = stops.firstOrNull() ?: return
        if (Geo.distance(pos, s.pos) < 40) {
            stops.remove(s)
            saveResume()
            Voice.say(if (Voice.lang == Phrases.GREEK) "Φτάσατε στη στάση: ${s.name}" else "Stop reached: ${s.name}")
            changed.fire()
        }
    }

    /**
     * Ends navigation. [keepResume]: Android Auto stopped it (the car app closed, another app
     * took over), not the driver: the route comes back when the car screen opens again.
     */
    fun stop(keepResume: Boolean = false) {
        CrashLog.note(if (keepResume) "navigation stopped by the car" else "stop navigation")
        Perf.driveEnded()
        if (!keepResume) clearResume()
        endNote = null
        noSignal = false
        LiveShare.stop(if (arrived) "arrived" else "stopped")
        stops.clear()
        destination = null
        current = null; nextStep = -1
        loading = false; arrived = false; error = null; offer = null
        main.removeCallbacks(routeWatchdog); routeSeq++
        // a trip card left up (End tapped near the end): it closes by itself too
        main.removeCallbacks(closeArrival); if (lastTrip != null) main.postDelayed(closeArrival, 5_000)
        Voice.stop()
        if (::appContext.isInitialized) {
            NavService.update(appContext) // (still in a convoy: keeps sharing, with its own notice)
        }
        changed.fire()
    }

    // ============================================================= driving

    private fun heading(): Float? =
        if (LocationTracker.speedMps > 3f) LocationTracker.heading
        // parked (or crawling): the way the car was going when it stopped, so the route starts on
        // the road you're on (outside Iatropolis on the side road, not the main road going the other way)
        else LocationTracker.lastDriveHeading

    /**
     * After leaving the route, pick the option that carries on the way the driver went:
     * no early turn-back, and as little of the abandoned route as possible.
     */
    /** A new route that starts by pointing back, or rejoins the old route behind the car. */
    private fun pullsBack(r: RouteOption, heading: Float?, old: RouteOption?): Boolean {
        val p = r.points
        if (p.size < 2) return false
        if (heading != null) {
            val i = r.cum.indexOfFirst { it > 150 }.let { if (it < 1) p.size - 1 else it }
            val diff = Math.abs(((Geo.bearing(p[0], p[i]) - heading + 540) % 360) - 180)
            if (diff > 100) return true
        }
        if (old != null) {
            val end = (nearest + 1).coerceAtMost(old.points.size)
            val behind = old.points.subList(maxOf(0, end - 400), end)
            val sample = p.filterIndexed { k, _ -> k % 8 == 0 }.take(40)
            if (sample.count { q -> behind.any { Geo.distance(it, q) < 25 } } >= 4) return true
        }
        return false
    }

    private fun pickForward(list: List<RouteOption>, heading: Float?, old: RouteOption?): RouteOption? {
        if (list.size <= 1) return list.firstOrNull()
        val best = list.first()
        fun score(r: RouteOption): Double {
            var s = r.duration
            if (heading != null) {
                // first ~150 m must not point back
                val p = r.points
                val i = r.cum.indexOfFirst { it > 150 }.let { if (it < 1) p.size - 1 else it }
                val b = Geo.bearing(p[0], p[i])
                val diff = Math.abs(((b - heading + 540) % 360) - 180)
                if (diff > 100) s += 600
            }
            if (old != null) {
                // re-joining the old route behind the car = being pulled back
                val end = (nearest + 1).coerceAtMost(old.points.size)
                val behind = old.points.subList(maxOf(0, end - 400), end)
                val sample = r.points.filterIndexed { k, _ -> k % 8 == 0 }.take(40)
                val back = sample.count { q -> behind.any { Geo.distance(it, q) < 25 } }
                s += back * 30
            }
            return s
        }
        val forward = list.minByOrNull { score(it) }?.takeIf { score(it) < score(best) - 30 || it === best } ?: best
        // carrying on is nicer than turning back, but not at any price: a forward loop more than a
        // quarter (and 90 s) longer than the quickest way loses to the quickest (it was taking
        // drivers on long detours after a missed turn)
        val quickest = list.minByOrNull { it.duration } ?: return forward
        return if (forward.duration > quickest.duration * 1.25 && forward.duration - quickest.duration > 90) quickest else forward
    }

    /** If p is within 30 m of the route, the closest point on it and the road's direction there. */
    fun snap(p: LatLng, bearing: Float? = null): Pair<LatLng, Float>? {
        val route = route
        if (!active || route.size < 2) return null
        var best: Pair<LatLng, Double>? = null
        var seg = 0
        val lo = maxOf(0, nearest - 5); val hi = minOf(route.size - 1, nearest + 60)
        for (i in lo until hi) {
            val pr = Geo.project(p, route[i], route[i + 1])
            if (best == null || pr.second < best.second) { best = pr; seg = i }
        }
        val b = best ?: return null
        if (b.second > 20) return null
        val segBearing = Geo.bearing(route[seg], route[seg + 1])
        // turned onto another street: show the car where it really is, not stuck on the route
        if (bearing != null && kotlin.math.abs(Geo.angleDiff(bearing, segBearing)) > 45 && !nearRouteTurn(seg, bearing)) return null
        return b.first to segBearing
    }

    /** The point [m] metres further along the route from [p] (on it), or null if [p] isn't on the route. */
    fun aheadOnRoute(p: LatLng, m: Double): LatLng? {
        val r = current ?: return null
        val route = r.points
        if (!active || route.size < 2) return null
        var best: Pair<LatLng, Double>? = null; var seg = 0
        val lo = maxOf(0, nearest - 5); val hi = minOf(route.size - 1, nearest + 60)
        for (i in lo until hi) {
            val pr = Geo.project(p, route[i], route[i + 1])
            if (best == null || pr.second < best.second) { best = pr; seg = i }
        }
        val b = best ?: return null
        if (b.second > 20) return null
        return routePointAt(r.cum[seg] + Geo.distance(route[seg], b.first) + m)
    }

    /** True if the route itself heads that way within ~40 m of segment [seg] (a turn we're taking). */
    private fun nearRouteTurn(seg: Int, bearing: Float): Boolean {
        val r = current ?: return false
        val route = r.points
        val from = r.cum[seg] - 15; val to = r.cum[seg] + 40
        var i = seg
        while (i > 0 && r.cum[i] > from) i--
        while (i < route.size - 1 && r.cum[i] <= to) {
            if (kotlin.math.abs(Geo.angleDiff(bearing, Geo.bearing(route[i], route[i + 1]))) <= 40) return true
            i++
        }
        return false
    }

    /** The route being worked out is a new one after leaving the old (the banner says "New route…"). */
    @Volatile var rerouting = false
        private set

    private fun fetchRoute(reroute: Boolean = false) {
        CrashLog.note(if (reroute) "reroute" else "fetch route")
        rerouting = reroute
        val dest = destination ?: return
        val from = LocationTracker.position
        if (from == null) {
            error = gr.gamemap.L.t("Waiting for GPS…")
            loading = true
            changed.fire()
            return
        }
        loading = true
        error = null
        lastRouteAt = System.currentTimeMillis()
        if (reroute) Voice.say(Phrases.rerouting(Voice.lang))
        changed.fire()
        val seq = ++routeSeq
        // never stuck on "Rerouting…": if no answer in 15 s, ask again from where the car is now
        main.removeCallbacks(routeWatchdog); main.postDelayed(routeWatchdog, 15_000)
        io.execute {
            if (seq != routeSeq) return@execute // a newer request is waiting: that one counts
            // from where the car is now (the request may have waited a moment)
            val here = LocationTracker.position ?: from
            val h = heading()
            val via = stops.map { it.pos }
            // Rerouting with the roads of Greece downloaded: the phone works out a route itself first
            // (well under a second, no internet) and the car follows it at once; the online route,
            // with live traffic, quietly takes over when it arrives.
            var phoneRoute = false
            val t0 = System.currentTimeMillis()
            if (reroute && !wantsAlternatives && OfflineRouter.present) {
                // worked out a moment ago at the first sign of leaving, from close by: use it as it is
                val ready = specRoute?.takeIf { (at, t, _) -> System.currentTimeMillis() - t < 4_000 && Geo.distance(at, here) < 30 }?.third
                specRoute = null
                if (ready != null) CrashLog.note("reroute: ready from the first sign")
                val off = ready ?: try { OfflineRouter.route(here, dest, h, via) } catch (e: Throwable) { null }
                if (off != null && !pullsBack(off, h, current)) {
                    phoneRoute = true
                    try { Tolls.annotate(listOf(off)) } catch (_: Exception) {}
                    val ms = System.currentTimeMillis() - t0
                    main.post {
                        if (destination != dest || seq != routeSeq) return@post
                        CrashLog.note("reroute: phone route in $ms ms")
                        apply(off, announce = false)
                    }
                }
            }
            val r0 = try {
                if (reroute && !wantsAlternatives) {
                    // one route, raced (OSRM / TomTom): ~3–6× quicker than asking for alternatives.
                    // Only if it would send the car back the way it came, ask again for the choices.
                    val q = Router.quick(here, dest, h, via).also { Tolls.annotate(it) }.firstOrNull()
                    if (q != null && !pullsBack(q, h, current)) q
                    else pickForward(preferAllowed(Router.routes(here, dest, alternatives = true, heading = h, reroute = true, via = via).also { Tolls.annotate(it) }), h, current) ?: q
                } else {
                    val list = preferAllowed(Router.routes(here, dest, alternatives = reroute || wantsAlternatives, heading = h, reroute = reroute, via = via).also { Tolls.annotate(it) })
                    if (reroute) pickForward(list, h, current) else list.firstOrNull()
                }
            } catch (e: Exception) { null }
            // a new trip that ends on a dirt track: end where the car road does
            var newDest: LatLng? = null
            var routeOut = r0
            if (!reroute && r0 != null && stops.isEmpty()) {
                val tail = try { RoadKind.dirtTail(r0) } catch (e: Exception) { null }
                if (tail != null) {
                    val again = try { Router.routes(here, tail.second, alternatives = false, heading = h).also { Tolls.annotate(it) }.firstOrNull() } catch (e: Exception) { null }
                    if (again != null) { routeOut = again; newDest = tail.second }
                }
            }
            val r = routeOut
            main.post {
                if (destination != dest || seq != routeSeq) return@post
                if (newDest != null) {
                    endNote = gr.gamemap.L.t("The road ends ${formatDistance(Geo.distance(newDest, dest))} before $destinationName: the rest is a dirt track or a path")
                    destination = newDest
                    Voice.say(endNote!!)
                }
                main.removeCallbacks(routeWatchdog)
                if (r == null && phoneRoute) {
                    // no answer online, but the phone's own route is already being followed: fine
                    loading = false; CrashLog.note("reroute: online failed, keeping the phone's route"); changed.fire()
                } else if (r == null && reroute && current != null) {
                    // no signal (or the routers down): keep driving the route we have, say so,
                    // and try again every 15 s
                    loading = false
                    if (!noSignal) { noSignal = true; CrashLog.note("reroute failed: keeping the route") }
                    changed.fire()
                    main.postDelayed({ if (active && !arrived && noSignal && !loading) fetchRoute(reroute = true) }, 15_000)
                } else if (r == null) {
                    loading = false; error = gr.gamemap.L.t("No route found"); changed.fire()
                    CrashLog.note("route failed; trying again")
                    main.postDelayed({ if (active && !arrived && error != null && !loading) fetchRoute(reroute = true) }, 5_000)
                } else { CrashLog.note("route from ${Router.lastSource} in ${System.currentTimeMillis() - t0} ms"); apply(r, announce = !reroute) }
            }
        }
    }

    private val routeWatchdog = Runnable {
        if (active && loading && !arrived) { CrashLog.note("route request slow: again"); loading = false; fetchRoute(reroute = current != null) }
    }

    private fun apply(r: RouteOption, announce: Boolean) {
        current = r
        // planned on the phone with no signal: the chip says so
        noSignal = Router.lastSource == "Offline"
        Offline.saveAlong(r)
        if (tripPlanned < 0) tripPlanned = r.distance
        secondsPerMeter = if (r.distance > 0) r.duration / r.distance else 0.08
        trafficRatio = r.trafficSeconds?.let { (it / maxOf(r.duration, 1.0)).coerceIn(0.6, 4.0) } ?: trafficRatio
        trafficDelaySeconds = r.delaySeconds
        // fresh traffic soon after the start, then every 3 minutes
        trafficAt = if (r.trafficSeconds != null) System.currentTimeMillis() else System.currentTimeMillis() - 85_000
        nearest = 0; along = 0.0
        offRouteCount = 0
        loading = false
        error = null
        Voice.reset()
        if (announce) Voice.say(Phrases.routeReady(r.length * secondsPerMeter * trafficRatio, Voice.lang))
        LocationTracker.last?.let { onLocation(it) } ?: changed.fire()
    }

    /** The next toll on the route and how far it is (shown as a chip), or null. */
    @Volatile var tollAhead: Pair<Tolls.OnRoute, Double>? = null
        private set
    private var tollSaid: Tolls.OnRoute? = null

    // ------------------------------------------------------------ tunnel mode

    /** True while GPS is lost and the position is being estimated along the route. */
    @Volatile var estimating = false
        private set
    private var lastRealFixAt = 0L
    private var alongAtFix = 0.0
    private var speedAtFix = 0f
    private var onRouteAtFix = true
    private var estAlong = -1.0
    private var estAt = 0L

    /**
     * Tunnel mode: once a second while navigating. With no usable GPS fix for 2.5 s while you
     * were driving, the arrow keeps moving along the route at your last speed (for up to two
     * minutes) so directions and the voice carry on; the next real fix takes over again.
     */
    private val tunnel = object : Runnable {
        override fun run() {
            if (!active) { estimating = false; return }
            main.postDelayed(this, 1000)
            val r = current ?: return
            if (arrived || loading || lastRealFixAt == 0L || !onRouteAtFix) return
            val age = System.currentTimeMillis() - lastRealFixAt
            if (age < 2500 || speedAtFix < 4f || age > 120_000) {
                if (estimating && age > 120_000) { estimating = false; changed.fire() }
                return
            }
            // the car's own speed when Android Auto shares it (it knows when you stop in the tunnel),
            // else the speed at the last fix; moved forward a second at a time
            val now = System.currentTimeMillis()
            val v = CarFeed.speed() ?: speedAtFix
            estAlong = if (!estimating || estAlong < 0) alongAtFix + v * age / 1000.0 else estAlong + v * (now - estAt) / 1000.0
            estAt = now
            val est = estAlong.coerceAtMost(r.length - 25)
            val p = routePointAt(est) ?: return
            val ahead = routePointAt(est + 15) ?: return
            if (!estimating) CrashLog.note("tunnel mode on" + if (CarFeed.speed() != null) " (car speed)" else "")
            estimating = true
            LocationTracker.estimate(p, Geo.bearing(p, ahead), v)
        }
    }

    fun onLocation(l: Location) {
        if (!active) { autoDismissTrip(LatLng(l.latitude, l.longitude), l.speed); return }
        val r = current
        val pos = LatLng(l.latitude, l.longitude)
        val real = l.provider != LocationTracker.ESTIMATE
        lastPos?.let { lp -> Geo.distance(lp, pos).takeIf { it < 300 }?.let { tripMeters += it } }
        lastPos = pos
        Xp.onFix(pos, l.speed, speedLimit, real)
        if (r == null || r.points.size < 2) {
            if (loading && error != null) fetchRoute() // was waiting for the first GPS fix
            return
        }
        val route = r.points
        var best = nearest; var bestD = Double.MAX_VALUE
        val lo = maxOf(0, nearest - 10); val hi = minOf(route.size - 1, nearest + 300)
        for (i in lo until hi) {
            val d = Geo.distanceToSegment(pos, route[i], route[i + 1])
            if (d < bestD) { bestD = d; best = i }
        }
        // how far along: where the car falls on that piece of road (well off the road: the
        // progress stays where it was, so a far-away road can't count as having got there)
        val onRoad = bestD < 60
        if (onRoad || along == 0.0) {
            nearest = best
            val (proj, _) = Geo.project(pos, route[best], route[best + 1])
            along = r.cum[best] + Geo.distance(route[best], proj).coerceAtMost(r.cum[best + 1] - r.cum[best])
        }
        if (real) {
            if (estimating) { estimating = false; estAlong = -1.0; CrashLog.note("tunnel mode off") }
            lastRealFixAt = System.currentTimeMillis(); alongAtFix = along; onRouteAtFix = onRoad
            speedAtFix = if (l.hasSpeed()) l.speed else speedAtFix
        }
        remainingDistance = (r.length - along).coerceAtLeast(0.0)
        setEta(plainLeft(r, along) * trafficRatio)
        maybeRefreshTraffic(pos)
        checkStops(pos)
        LiveShare.tick()
        // last ~1.5 km: have the car parks at the destination ready for the arrival card
        if (remainingDistance < 1500 && !parkingReady) {
            parkingReady = true
            destination?.let { d -> Bg.factory("parking").newThread { try { Places.prefetchNearby(listOf("parking"), d) } catch (_: Exception) {} }.start() }
        }

        val dest = destination!!
        // arrived: really there (a real fix, at the destination or at the route's end while on it)
        // (30 m from it on the other carriageway used to count: the route's end must be close too)
        if (real && ((Geo.distance(pos, dest) < 30 && remainingDistance < 120 && bestD < 25) || (remainingDistance < 20 && bestD < 35))) {
            if (!arrived) {
                arrived = true; arrivedAtMs = System.currentTimeMillis()
                clearResume()
                offer = null
                finishTrip()
                LiveShare.stop("arrived")
                Convoy.arrived()
                Voice.say(Phrases.instruction(r.steps.lastOrNull() ?: NavStep("arrive", "arrive", "", 0, dest, null), Voice.lang))
                // the arrival card stays 30 s (Parking / Done), then the route ends by itself
                main.removeCallbacks(closeArrival); main.postDelayed(closeArrival, 5_000)
            }
            changed.fire()
            return
        }

        // the next toll ahead: chip from ~1.2 km, spoken once at ~1 km (sooner when fast)
        val toll = r.tolls.firstOrNull { it.along > along - 10 }
        tollAhead = toll?.let { t -> (t.along - along).takeIf { it in 0.0..1200.0 }?.let { t to it } }
        tollAhead?.let { (t, dist) ->
            val warnAt = maxOf(1000.0, LocationTracker.speedMps * 40.0)
            if (dist < warnAt && tollSaid !== t) { tollSaid = t; Voice.say(Phrases.toll(t.price, Voice.lang)) }
        }
        var next = r.steps.indexOfFirst { it.routeIndex > best || it.direction == "arrive" }
            .let { if (it < 0) r.steps.size - 1 else it }
        // Move on to the next turn only once this one is really behind us (12 m past it): near the
        // turn a vague fix can land just past the corner, and the box and voice skipped ahead.
        if (stepRoute === r && next > nextStep && nextStep >= 0) {
            val held = r.steps.getOrNull(nextStep)
            if (held != null && held.direction != "arrive" && held.routeIndex in r.cum.indices && along < r.cum[held.routeIndex] + 12) next = nextStep
        }
        stepRoute = r
        nextStep = next
        r.steps.getOrNull(nextStep)?.let { distanceToNext = (r.cum[it.routeIndex] - along).coerceAtLeast(0.0) }
        if (onRoad) currentStep?.let { Voice.progress(nextStep, it, distanceToNext, LocationTracker.speedMps, followingStep) }
        if (onRoad && real) prepareMissedTurn(r)

        // Off the route, any of:
        //  - more than ~30 m away (two fixes in a row, or one fix far away);
        //  - a block early: 12+ m away and heading along a different street than the route
        //    (its nearby part, including the turn just ahead), seen on two fixes in a row;
        //  - driving the wrong way along it.
        // Then ask for a new route straight away.
        val accuracy = if (l.hasAccuracy()) l.accuracy.toDouble() else 20.0
        val far = bestD > maxOf(30.0, accuracy * 1.2)
        val moving = l.hasBearing() && l.speed > 3.5f
        val segBearing = Geo.bearing(route[best], route[best + 1])
        val turnedOff = moving && bestD > maxOf(if (accuracy <= 10) 8.0 else 12.0, accuracy * 0.8) &&
            kotlin.math.abs(Geo.angleDiff(l.bearing, segBearing)) > 50 && !nearRouteTurn(best, l.bearing)
        val wrongWay = moving && bestD < 30 && kotlin.math.abs(Geo.angleDiff(l.bearing, segBearing)) > 120
        // clearly off (100 m, or 50 m with a sharp fix): straight away, without waiting for a second fix
        val clearly = (bestD > 100 && accuracy < 40) || (bestD > 50 && accuracy < 20)
        // a fix that just jumped (signal off buildings): wait for the next ones before a new route
        if (System.currentTimeMillis() - LocationTracker.jumpedAt < 4_000) { offRouteCount = 0; changed.fire(); return }
        // a sharp fix heading down another street is enough on its own: the new route is asked for
        // on that first fix (about a second after the turn) instead of waiting for a second one
        val sure = clearly || (turnedOff && accuracy <= 20) || (wrongWay && accuracy <= 20)
        offRouteCount = if (far || turnedOff || wrongWay) offRouteCount + (if (sure) 2 else 1) else 0
        // straight on past a turn, with a route ready for exactly that: take it now, on this fix
        // (the online route still comes after and takes over)
        if (offRouteCount >= 1 && !loading && System.currentTimeMillis() - lastRouteAt > 3_000) {
            val ready = missedTurnRoute(LatLng(l.latitude, l.longitude), if (l.hasBearing() && l.speed > 3f) l.bearing else null, r)
            if (ready != null) {
                CrashLog.note("off route: missed the turn, route ready")
                missed = null; offRouteCount = 0; offer = null
                apply(ready, announce = false)
                fetchRoute(reroute = true)
                return
            }
        }
        // the first sign of leaving: the phone starts on a new route already, so it's ready (or
        // nearly) if the next fix confirms it; thrown away if the car comes back
        if (offRouteCount == 1 && !specBusy && OfflineRouter.present) {
            val dest = destination; val from = LatLng(l.latitude, l.longitude); val brg = l.bearing; val via = stops.map { it.pos }
            if (dest != null) { specBusy = true; spec.execute {
                try { OfflineRouter.route(from, dest, brg, via)?.let { specRoute = Triple(from, System.currentTimeMillis(), it) } } catch (_: Throwable) {}
                specBusy = false
            } }
        }
        if (offRouteCount >= 2 && !loading && System.currentTimeMillis() - lastRouteAt > 3_000) {
            CrashLog.note("off route: ${bestD.toInt()} m, ${if (turnedOff) "other street" else if (wrongWay) "wrong way" else "far"}")
            offer?.let { declined += it.option to System.currentTimeMillis() } // not offered again right after
            offRouteCount = 0
            offer = null
            fetchRoute(reroute = true)
        }
        changed.fire()
    }

    /**
     * Every 3 minutes: TomTom re-times the rest of the route (traffic, jams, speed limits), then
     * OSRM's other ways from here are timed too; one that saves 3+ minutes is offered.
     */
    private fun maybeRefreshTraffic(pos: LatLng) {
        if (!Traffic.active || loading || System.currentTimeMillis() - trafficAt < 90_000) return
        val dest = destination ?: return
        val r = current ?: return
        trafficAt = System.currentTimeMillis()
        val from = along
        val oursNow = plainLeft(r, from) // (worked out here: not thread-safe)
        val declinedNow = declined.toList()
        val nearEnd = remainingDistance < 1500
        val hasOffer = offer != null
        bg.execute {
            val res = try { TomTomTime.evaluate(r, from) } catch (e: Exception) { null }
            main.post {
                if (current !== r || res == null) return@post
                trafficDelaySeconds = res.delay
                r.traffic = res.traffic
                if (res.limits.isNotEmpty()) r.limits = res.limits
                // the factor against our own time for the same stretch, worked out with the limits
                // just put in (before, it was worked out with the old ones and applied to the new)
                acceptRatio(res.congestion, "TomTom traffic")
                setEta(plainLeft(r, along) * trafficRatio)
                changed.fire()
            }
            if (res == null || hasOffer || nearEnd) return@execute
            val alts = try { Router.routes(pos, dest, alternatives = true, heading = heading(), via = stops.map { it.pos }).also { Tolls.annotate(it) } } catch (e: Exception) { null }
                ?: return@execute
            val now = System.currentTimeMillis()
            var bestAlt: RouteOption? = null
            for (a in alts) {
                if (r.similarTo(a) || breaksAvoid(a)) continue
                if (declinedNow.any { now - it.second < 15 * 60_000 && it.first.similarTo(a) }) continue
                val e = try { TomTomTime.evaluate(a, 0.0) } catch (ex: Exception) { null } ?: continue
                a.trafficSeconds = (a.duration * e.congestion).toInt(); a.delaySeconds = (a.duration * (e.congestion - 1)).toInt(); a.traffic = e.traffic; a.limits = e.limits
                if (bestAlt == null || a.seconds < bestAlt.seconds) bestAlt = a
            }
            val alt = bestAlt ?: return@execute
            // both on our own times, each with its own congestion from TomTom
            val nowSecs = oursNow * res.congestion
            val saving = (nowSecs - alt.seconds).toInt()
            if (saving >= 180 && saving >= nowSecs * 0.08) main.post {
                if (current === r && offer == null && active) showOffer(Offer(alt, saving, System.currentTimeMillis()))
            }
        }
    }

    /** One faster-route message at a time, and at most one every 5 minutes. */
    private var lastOfferAt = 0L

    private fun showOffer(o: Offer) {
        val now = System.currentTimeMillis()
        if (now - lastOfferAt < 5 * 60_000) return
        // already offered (and taken or let go) in the last 15 minutes: not again
        if (declined.any { now - it.second < 15 * 60_000 && it.first.similarTo(o.option) }) return
        lastOfferAt = now
        declined += o.option to now
        offer = o
        Voice.say(Phrases.faster(o.savingSeconds, Voice.lang))
        changed.fire()
        main.postDelayed({
            if (offer === o) { if (autoSwitch) acceptOffer() else declineOffer() }
        }, OFFER_SECONDS * 1000L)
    }

    fun acceptOffer() {
        val o = offer ?: return
        offer = null
        tripSaved += o.savingSeconds
        tripBonus += 10
        Voice.say(Phrases.switched(Voice.lang))
        // the road we left isn't offered back as "faster" a minute later
        current?.let { declined += it to System.currentTimeMillis() }
        apply(o.option, announce = false)
    }

    fun declineOffer() {
        val o = offer ?: return
        offer = null
        declined += o.option to System.currentTimeMillis()
        changed.fire()
    }

    // ============================================================= previews / tests

    internal fun debugPlan(place: Place, opts: List<RouteOption>) {
        planning = true; planPlace = place; options = opts; selected = fastestIndex()
        planLoading = false; planError = null
        fitPlan(opts.flatMap { it.points } + place.pos)
        changed.fire()
    }

    internal fun debugDrive(place: Place, r: RouteOption, delaySeconds: Int) {
        planning = false
        begin(place)
        apply(r, announce = false)
        trafficRatio = (r.duration + delaySeconds) / maxOf(r.duration, 1.0)
        trafficDelaySeconds = delaySeconds
        changed.fire()
    }

    internal fun debugTrip(t: Explorer.Trip) {
        lastTrip = t; tripEndAt = null; tripEndTime = 0L; awaySince = 0L
        changed.fire()
    }

    internal fun debugOffer(o: RouteOption, saving: Int) {
        offer = Offer(o, saving, System.currentTimeMillis())
        changed.fire()
    }

    // ============================================================= text helpers

    fun formatDistance(m: Double): String {
        if (MapState.imperial) {
            val mi = m / 1609.344
            return when {
                mi >= 10 -> "${mi.toInt()} mi"
                mi >= 0.2 -> String.format(java.util.Locale.US, "%.1f mi", mi)
                else -> "${((m * 3.28084 / 50).toInt() * 50).coerceAtLeast(50)} ft"
            }
        }
        return when {
            m >= 10_000 -> "${(m / 1000).toInt()} km"
            m >= 1000 -> String.format(java.util.Locale.US, "%.1f km", m / 1000)
            m >= 100 -> "${((m / 50).toInt() * 50)} m"
            else -> "${((m / 10).toInt() * 10)} m"
        }
    }

    fun formatDuration(sec: Double): String {
        val mins = (sec / 60).toInt().coerceAtLeast(1)
        return if (mins >= 60) "${mins / 60} h ${mins % 60} min" else "$mins min"
    }

    fun clock(atMs: Long): String =
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(atMs))

    fun arrivalClock(): String = clock(System.currentTimeMillis() + (remainingSeconds * 1000).toLong())

    /** Arrival time of a planned option. */
    fun optionArrival(o: RouteOption): String = clock((departAt ?: System.currentTimeMillis()) + (o.seconds * 1000).toLong())
}
