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
    /** Fixes from the simulator (real GPS is ignored while it drives). */
    const val SIM = "sim"

    // ================================================================== a simulated drive

    /** The simulator is driving (real GPS fixes are ignored). */
    @Volatile var simActive = false
        private set
    /** A simulated drive happened on this trip: it earns nothing. */
    @Volatile var simTrip = false
    private var pos: LatLng? = null
    private var bearing = 0f
    private const val SPEED = 11.0 // m/s, about 40 km/h
    private var leaveUntil = 0L
    private var weakUntil = 0L
    private var tunnelUntil = 0L
    @Volatile private var noNetUntil = 0L
    /** "No internet" for the routers (the phone's own router still works). */
    val noInternet get() = SystemClock.uptimeMillis() < noNetUntil

    /** Drives to [dest] (or 2.5 km away), along the route, one fix a second. */
    fun simulate(ctx: Context, dest: Place?) {
        val start = LocationTracker.position ?: LatLng(37.9838, 23.7275)
        pos = start; bearing = LocationTracker.heading
        simActive = true; simTrip = true
        leaveUntil = 0; weakUntil = 0; tunnelUntil = 0; noNetUntil = 0
        fix(start, 0f, 5f)
        val to = dest ?: Place(L.t("Test destination"), "", Geo.offset(start, 45f, 2500.0))
        NavEngine.start(ctx, to)
        CrashLog.note("dev: simulated drive to ${to.name}")
        main.removeCallbacks(tick); main.postDelayed(tick, 1000)
    }

    fun stopSim() {
        simActive = false
        main.removeCallbacks(tick)
        if (NavEngine.active) NavEngine.stop()
    }

    /** Off the route: the next 7 s turn down a side street. */
    fun leaveRoute() { leaveUntil = SystemClock.uptimeMillis() + 7000; CrashLog.note("dev: leaving the route") }
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

    private val tick = object : Runnable {
        override fun run() {
            if (!simActive) return
            val now = SystemClock.uptimeMillis()
            val p = pos ?: return
            if (NavEngine.arrived || !NavEngine.active) { simActive = false; return }
            val next: LatLng
            if (now < leaveUntil) {
                // a side street: 90° to the right of where we were going
                next = Geo.offset(p, (bearing + 90f) % 360f, SPEED)
            } else {
                next = along(p, NavEngine.route, SPEED) ?: Geo.offset(p, bearing, SPEED)
            }
            if (Geo.distance(p, next) > 0.5) bearing = Geo.bearing(p, next)
            if (now >= leaveUntil) { /* back to following */ }
            pos = next
            if (now >= tunnelUntil) fix(next, SPEED.toFloat(), if (now < weakUntil) 60f else 5f)
            main.postDelayed(this, 1000)
        }
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
        LocationTracker.onLocationChanged(Location(SIM).apply {
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
        Friends.core.addTestInvite("test-nikos", "Nikos (test)", "test" + (100000..999999).random(), "Sounio")
        Friends.changed.fire()
    }

    /** A convoy of 3 made-up drivers moving along with you, with a quick message now and then. */
    @Volatile var convoyOn = false
        private set
    private var convoyT = 0

    fun startTestConvoy() {
        val here = LocationTracker.position ?: LatLng(37.9838, 23.7275)
        if (Convoy.active) Convoy.leave(quiet = true)
        Convoy.debugStart(Place("Nafplio (test)", "", LatLng(37.5673, 22.8016)), code = "test" + (100000..999999).random())
        convoyOn = true; convoyT = 0
        main.removeCallbacks(convoyTick); main.post(convoyTick)
        CrashLog.note("dev: test convoy around ${here.lat.toFloat()},${here.lon.toFloat()}")
    }

    fun stopTestConvoy() { convoyOn = false; main.removeCallbacks(convoyTick); if (Convoy.active) Convoy.leave(quiet = true) }

    private val convoyTick = object : Runnable {
        override fun run() {
            if (!convoyOn || !Convoy.active) { convoyOn = false; return }
            val me = LocationTracker.position ?: LatLng(37.9838, 23.7275)
            val b = LocationTracker.heading
            convoyT++
            listOf(Triple("Nikos (test)", 120.0, 78), Triple("Maria (test)", -90.0, 64), Triple("Eleni (test)", 260.0, 85)).forEach { (n, gap, kmh) ->
                val wobble = kotlin.math.sin(convoyT / 6.0) * 30
                Convoy.debugMember(n, Geo.offset(me, b, gap + wobble), kmh, System.currentTimeMillis() + 40 * 60_000, 30_000.0, 120_000.0, "driving")
            }
            if (convoyT % 25 == 5) Convoy.debugMessage(listOf("Nikos (test)", "Maria (test)", "Eleni (test)")[convoyT / 25 % 3], listOf("fuel", "stop", "ahead", "wait")[convoyT / 25 % 4])
            Convoy.changed.fire()
            main.postDelayed(this, 1000)
        }
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
