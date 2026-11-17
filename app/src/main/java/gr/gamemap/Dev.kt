package gr.gamemap

import android.content.Context
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Test-build tools (Settings › Developer, test builds only): drive without a car, friends and
 * convoys without friends, XP without driving. Nothing here is ever sent to real friends, and
 * simulated drives earn no XP, add no trips and find no roads.
 */
object Dev {
    private val main = Handler(Looper.getMainLooper())
    /** Marks fixes from the simulator (they look like GPS; real GPS is ignored while it drives). */
    const val SIM = "dev_sim"
    fun isSim(l: Location) = try { l.extras?.getBoolean(SIM) == true } catch (_: Exception) { false }

    // ================================================================== a simulated drive

    /** The simulator is driving (real GPS fixes are ignored). */
    @Volatile var simActive = false
        private set
    /** A simulated drive happened on this trip: it earns nothing. */
    @Volatile var simTrip = false
    private var pos: LatLng? = null
    private var bearing = 0f
    /** Fast forward: 1 = the road's speed; 2, 4, 8 = that many times faster, past any limit. */
    @Volatile var fastForward = 1
    /** The simulated car's speed now (m/s): eases towards the road's speed. */
    private var v = 0.0
    private var leaveUntil = 0L
    private var weakUntil = 0L
    private var tunnelUntil = 0L
    @Volatile private var noNetUntil = 0L
    /** "No internet" for the routers (the phone's own router still works). */
    val noInternet get() = SystemClock.uptimeMillis() < noNetUntil

    /** Drives to [dest] (or 2.5 km away), along the route, one fix a second. */
    fun simulate(ctx: Context, dest: Place?, startNav: Boolean = true) {
        val start = LocationTracker.position ?: LatLng(37.9838, 23.7275)
        pos = start; bearing = LocationTracker.heading
        simActive = true; simTrip = true
        leaveUntil = 0; weakUntil = 0; tunnelUntil = 0; noNetUntil = 0
        v = 0.0
        fix(start, 0f, 5f)
        if (startNav) {
            val to = dest ?: Place(L.t("Test destination"), "", Geo.offset(start, 45f, 2500.0))
            NavEngine.start(ctx, to)
            CrashLog.note("dev: simulated drive to ${to.name}")
        }
        main.removeCallbacks(tick); main.postDelayed(tick, 1000)
    }

    fun stopSim() {
        simActive = false
        main.removeCallbacks(tick)
        if (NavEngine.active) NavEngine.stop()
    }

    /** Off the route: the next 7 s turn down a side street. */
    fun leaveRoute() { bearing = (bearing + 90f) % 360f; leaveUntil = SystemClock.uptimeMillis() + 7000; CrashLog.note("dev: leaving the route") }
    fun weakGps() { weakUntil = SystemClock.uptimeMillis() + 30_000 }
    fun tunnel() { tunnelUntil = SystemClock.uptimeMillis() + 30_000 }
    fun noSignal() { noNetUntil = SystemClock.uptimeMillis() + 30_000 }

    /** Jump to a few metres before the end: the arrival card. */
    fun arriveNow() {
        val r = NavEngine.route
        if (r.size < 2) return
        pos = r[maxOf(0, r.size - 2)]
    }

    /** The faster-route offer, as if a quicker way had been found. */
    fun showOffer() { NavEngine.current?.let { NavEngine.debugOffer(it, 240) } }

    /** How fast to drive here (m/s): the speed limit when known, else by the kind of road. */
    private fun roadSpeed(p: LatLng): Double {
        NavEngine.speedLimit?.takeIf { it in 20..130 }?.let { return it / 3.6 * 0.95 }
        val kind = try { RoadMatch.match(p, bearing, v.toFloat().coerceAtLeast(5f), 5f); RoadMatch.kindNow() } catch (_: Exception) { null }
        return when (kind) {
            "motorway" -> 33.0; "trunk" -> 25.0; "primary" -> 19.0; "secondary" -> 15.5
            "tertiary" -> 12.5; "service" -> 6.0; else -> 10.0
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!simActive) return
            val now = SystemClock.uptimeMillis()
            val p = pos ?: return
            if (NavEngine.arrived || !NavEngine.active) { simActive = false; return }
            // speed up / slow down gently towards this road's speed (slower into turns)
            var want = roadSpeed(p)
            val turn = NavEngine.currentStep?.direction.orEmpty()
            if (NavEngine.distanceToNext in 0.0..70.0 && (turn.contains("left") || turn.contains("right") || turn == "uturn")) want = minOf(want, 8.0)
            want *= fastForward
            v += (want - v).coerceIn(-3.0 * fastForward, 2.5 * fastForward)
            val route = NavEngine.route
            val off = route.size < 2 || nearestDistance(p, route) > 25
            val next = if (now < leaveUntil || off) {
                // a side street (to the right), or still off the route until the new one comes:
                // keep going the way we're going, never back to where we left it
                Geo.offset(p, bearing, v)
            } else along(p, route, v) ?: Geo.offset(p, bearing, v)
            if (Geo.distance(p, next) > 0.5) bearing = Geo.bearing(p, next)
            pos = next
            if (now >= tunnelUntil) fix(next, v.toFloat(), if (now < weakUntil) 60f else 5f)
            main.postDelayed(this, 1000)
        }
    }

    private fun nearestDistance(p: LatLng, route: List<LatLng>): Double {
        var best = Double.MAX_VALUE
        for (i in 0 until route.size - 1) best = minOf(best, Geo.project(p, route[i], route[i + 1]).second)
        return best
    }

    /** [m] metres on along [route] from the point of it nearest to [p]. */
    private fun along(p: LatLng, route: List<LatLng>, m: Double): LatLng? {
        if (route.size < 2) return null
        var best = 0; var bestD = Double.MAX_VALUE
        for (i in 0 until route.size - 1) {
            val d = Geo.project(p, route[i], route[i + 1]).second
            if (d < bestD) { bestD = d; best = i }
        }
        var at = Geo.project(p, route[best], route[best + 1]).first
        var left = m; var i = best
        while (i < route.size - 1) {
            val seg = Geo.distance(at, route[i + 1])
            if (seg >= left) return Geo.offset(at, Geo.bearing(at, route[i + 1]), left)
            left -= seg; at = route[i + 1]; i++
        }
        return route.last()
    }

    private fun fix(p: LatLng, speed: Float, acc: Float) {
        LocationTracker.onLocationChanged(Location(android.location.LocationManager.GPS_PROVIDER).apply {
            extras = android.os.Bundle().apply { putBoolean(SIM, true) }
            latitude = p.lat; longitude = p.lon; time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            this.speed = speed; this.bearing = this@Dev.bearing; accuracy = acc
        })
    }

    // ================================================================== friends and convoys

    private val testFriends = listOf(
        listOf("test-nikos", "Nikos (test)", "bolt:0891b2", "drive"),
        listOf("test-maria", "Maria (test)", "owl:1e3a8a", "convoy"),
        listOf("test-eleni", "Eleni (test)", "compass:0d9488", "busy"),
        listOf("test-kostas", "Kostas (test)", "", "off"),
    )

    /** Four made-up friends (driving, in a convoy, busy, offline), on this phone only. */
    fun addTestFriends() {
        if (!Friends.ready) return
        val c = Friends.core
        for ((fid, name, av, state) in testFriends) {
            c.addTestFriend(fid, name)
            if (av.isNotBlank()) Friends.avatars[fid] = av
            val now = System.currentTimeMillis()
            when (state) {
                "off" -> { c.setOnline(fid, null); Friends.activityForTest(fid, false, false, false, now - 40 * 60_000) }
                else -> { c.setOnline(fid, now, dnd = state == "busy"); Friends.activityForTest(fid, true, state == "drive", state == "convoy", now) }
            }
        }
        keepOnline()
        Friends.changed.fire()
    }

    /** They stay online while they exist (real friends refresh themselves; these can't). */
    private val keep = object : Runnable {
        override fun run() {
            if (!Friends.ready || Friends.core.friends.none { it.fid.startsWith("test-") }) return
            val now = System.currentTimeMillis()
            for ((fid, _, _, state) in testFriends) if (state != "off" && Friends.core.isFriend(fid)) Friends.core.setOnline(fid, now, dnd = state == "busy")
            Friends.changed.fire()
            main.postDelayed(this, 60_000)
        }
    }
    private fun keepOnline() { main.removeCallbacks(keep); main.postDelayed(keep, 60_000) }

    fun removeTestFriends() {
        if (!Friends.ready) return
        Friends.core.removeTestFriends()
        for (f in testFriends) Friends.avatars.remove(f[0])
        Friends.changed.fire()
    }

    /** A convoy invite, as if from Nikos (adds the test friends first if needed). */
    fun testInvite() {
        if (!Friends.ready) return
        if (!Friends.core.isFriend("test-nikos")) addTestFriends()
        val cv = "test" + (100000..999999).random()
        Friends.core.addTestInvite("test-nikos", "Nikos (test)", cv, "Sounio")
        Friends.changed.fire()
        Friends.core.invites.firstOrNull { it.convoy == cv }?.let { InvitePop.offer(it) }
    }

    /** A convoy of 3 made-up drivers moving along with you, with a quick message now and then. */
    @Volatile var convoyOn = false
        private set
    private var convoyT = 0

    /** Did the test convoy start the simulated drive (so stopping it stops that too)? */
    private var convoyDrives = false

    /** The convoy and a simulated drive with it: you move too (to where you're already going, else Nafplio). */
    fun startTestConvoy(ctx: Context) {
        val here = LocationTracker.position ?: LatLng(37.9838, 23.7275)
        if (Convoy.active) Convoy.leave(quiet = true)
        val dest = NavEngine.destination?.takeIf { NavEngine.active && !NavEngine.arrived }?.let { Place(NavEngine.destinationName, "", it) }
            ?: Place("Nafplio (test)", "", LatLng(37.5673, 22.8016))
        Convoy.debugStart(dest, code = "test" + (100000..999999).random())
        convoyDrives = false
        if (!simActive) { simulate(ctx, dest, startNav = !(NavEngine.active && !NavEngine.arrived)); convoyDrives = true }
        convoyOn = true; convoyT = 0; trail.clear()
        gaps[0] = 180.0; gaps[1] = 380.0; gaps[2] = -140.0; lastMe = null
        main.removeCallbacks(convoyTick); main.post(convoyTick)
        CrashLog.note("dev: test convoy around ${here.lat.toFloat()},${here.lon.toFloat()}")
    }

    fun stopTestConvoy() {
        convoyOn = false; main.removeCallbacks(convoyTick); if (Convoy.active) Convoy.leave(quiet = true)
        if (convoyDrives) { convoyDrives = false; stopSim() }
    }

    /** Where each made-up driver is: metres ahead of you along the road (minus: behind). */
    private val gaps = doubleArrayOf(180.0, 380.0, -140.0)
    /** How fast each one drives (m/s): they really move, even while you're parked. */
    private val speeds = doubleArrayOf(12.5, 10.0, 14.0)
    private val factors = doubleArrayOf(1.06, 0.96, 1.1)
    private var lastMe: LatLng? = null
    private val trail = ArrayList<LatLng>() // where I've been, for the driver behind me without a route

    private val convoyTick = object : Runnable {
        override fun run() {
            if (!convoyOn || !Convoy.active) { convoyOn = false; return }
            val me = LocationTracker.position ?: LatLng(37.9838, 23.7275)
            if (trail.isEmpty() || Geo.distance(trail.last(), me) > 3) { trail += me; if (trail.size > 200) trail.removeAt(0) }
            val moved = lastMe?.let { Geo.distance(it, me).coerceAtMost(60.0) } ?: 0.0
            lastMe = me
            convoyT++
            val names = listOf("Nikos (test)", "Eleni (test)", "Maria (test)")
            for (i in 0..2) {
                // each drives at its own speed (a little faster and slower in turn); I move too
                // as fast as the road lets you (a little faster or slower each); parked: their own pace
                val base = maxOf(LocationTracker.speedMps.toDouble(), speeds[i])
                val v = base * (if (LocationTracker.speedMps > 3) factors[i] else 1.0) + kotlin.math.sin((convoyT + i * 7) / 9.0) * 1.5
                gaps[i] += v - moved
                // far ahead: comes round again from behind (so there's always someone to watch)
                if (gaps[i] > 600) gaps[i] = -300.0
                if (gaps[i] < -400) gaps[i] = 500.0
                val p = onRoad(me, gaps[i]) ?: continue
                val ahead = onRoad(me, gaps[i] + 15) ?: p
                val heading = if (Geo.distance(p, ahead) > 1) Geo.bearing(p, ahead) else LocationTracker.heading
                val r = NavEngine.current
                val left = if (r != null) (r.length - NavEngine.along - gaps[i]).coerceAtLeast(0.0) else 30_000.0
                Convoy.debugMember(names[i], p, (v * 3.6).toInt(), System.currentTimeMillis() + (left / 11 * 1000).toLong(), left, r?.length ?: 120_000.0, "driving", heading)
            }
            if (convoyT % 25 == 5) Convoy.debugMessage(names[convoyT / 25 % 3], listOf("fuel", "stop", "ahead", "wait")[convoyT / 25 % 4])
            Convoy.changed.fire()
            main.postDelayed(this, 1000)
        }
    }

    /** A point on the road [m] metres ahead of me (behind if negative). */
    private fun onRoad(me: LatLng, m: Double): LatLng? {
        if (NavEngine.active && NavEngine.route.size >= 2) {
            val at = NavEngine.along + m
            if (at >= 0) return NavEngine.routePointAt(at)
        }
        if (m < 0) { // behind: back along where I've driven
            var left = -m; var p = me
            for (i in trail.indices.reversed()) {
                val d = Geo.distance(p, trail[i]); if (d >= left) return Geo.offset(p, Geo.bearing(p, trail[i]), left)
                left -= d; p = trail[i]
            }
            return Geo.offset(me, (LocationTracker.heading + 180f) % 360f, -m)
        }
        return Geo.offset(me, LocationTracker.heading, m)
    }

    // ================================================================== XP and levels

    private fun backupFile(ctx: Context) = java.io.File(ctx.filesDir, "dev_progress_backup.json")
    fun hasBackup(ctx: Context) = backupFile(ctx).exists()

    /** Keeps the real progress in a file (once: the first time any XP tool is used). */
    fun backupOnce(ctx: Context) {
        if (hasBackup(ctx)) return
        try { backupFile(ctx).outputStream().use { Backup.export(ctx, it) } } catch (e: Exception) { CrashLog.note("dev backup: $e") }
    }

    /** Puts the real progress back. True: done (the app should restart). */
    fun restoreBackup(ctx: Context): Boolean {
        val f = backupFile(ctx); if (!f.exists()) return false
        val root = f.inputStream().use { Backup.peek(it) } ?: return false
        if (!Backup.restore(ctx, root)) return false
        f.delete()
        return true
    }

    fun addXp(ctx: Context, n: Int) { backupOnce(ctx); Explorer.addXp(n) }
    fun setLevel(ctx: Context, level: Int) { backupOnce(ctx); Explorer.setXpForDev(Explorer.need(level.coerceIn(1, 50)) + 1) }

    // ================================================================== public builds

    /** In a public build: nothing from testing is left (test friends, their invites and avatars). */
    fun cleanForPublic() {
        if (BuildConfig.TEST_BUILD) return
        try { if (Friends.ready) { Friends.core.removeTestFriends(); Friends.avatars.keys.removeAll { it.startsWith("test-") } } } catch (_: Exception) {}
    }
}
