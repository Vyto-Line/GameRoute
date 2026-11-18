package gr.gamemap

import java.util.concurrent.Executors

/**
 * The speed limit where you are, for the speedometer: the route's own (TomTom) first; where the
 * route has none (a gap, or just after a new route), or with no route at all, the limit of the road
 * from Roads of Greece; and a limit seen last stays for a little while (the next 400 m) so it doesn't
 * blink off between stretches.
 */
object SpeedLimit {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "speed-limit").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    @Volatile private var roadLimit: Int? = null
    @Volatile private var roadAt = 0L
    @Volatile private var looking = false
    @Volatile private var lastLimit: Int? = null
    @Volatile private var lastPos: LatLng? = null

    /** The limit now (km/h), or null when nothing knows it. */
    fun now(): Int? {
        val pos = LocationTracker.position
        val route = if (NavEngine.active) NavEngine.speedLimit else null
        val road = roadLimit?.takeIf { System.currentTimeMillis() - roadAt < 8_000 }
        val found = route ?: road
        if (found != null) { lastLimit = found; lastPos = pos; return found }
        // nothing here: the last one, for the next 400 m
        val lp = lastPos
        return if (lp != null && pos != null && Geo.distance(lp, pos) < 400) lastLimit else null
    }

    /** A new fix: look the road up in the road file (in the background, every 2 s at most). */
    fun onFix(p: LatLng, heading: Float?, speed: Float) {
        if (looking || speed < 1.5f || !OfflineRouter.present) return
        val now = System.currentTimeMillis()
        if (now - roadAt < 2_000) return
        looking = true
        worker.execute {
            try {
                val v = OfflineRouter.limitAt(p, heading)
                roadLimit = v; roadAt = System.currentTimeMillis()
            } catch (_: Throwable) {
            } finally { looking = false }
        }
    }

    /** How far over the limit: 0 = not over (or no limit), 1 = up to 15 km/h over (orange), 2 = more (red). */
    fun level(kmh: Int, limit: Int?): Int = when {
        limit == null || kmh <= limit -> 0
        kmh <= limit + 15 -> 1
        else -> 2
    }

    /** For tests. */
    internal fun setForTest(road: Int?) { roadLimit = road; roadAt = System.currentTimeMillis(); lastLimit = null; lastPos = null }
}
