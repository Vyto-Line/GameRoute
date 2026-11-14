package gr.gamemap

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt

/** How long each start-up step took (ms), for the crash log and tests. */
object Startup {
    val steps = java.util.Collections.synchronizedList(ArrayList<Pair<String, Long>>())
    inline fun <T> step(name: String, f: () -> T): T {
        val t = System.nanoTime()
        try { return f() } finally { steps += name to (System.nanoTime() - t) / 1_000_000 }
    }
}

class GameMapApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Startup.step("crash log") { CrashLog.init(this) }
        Startup.step("language") { L.init(this) }
        Startup.step("map state") { MapState.init(this) }
        Startup.step("tile cache") { TileCache.init(this) }
        Startup.step("places") { Places.init(this) }
        Startup.step("open places") { OpenPlaces.init(this) }
        Startup.step("offline") { Offline.init(this) }
        Startup.step("convoy") { Convoy.init(this) }
        Startup.step("fuel") { Fuel.init(this) }
        Startup.step("parked") { Parked.init(this) }
        Startup.step("tolls") { Tolls.init(this) }
        Startup.step("reviews") { Reviews.init(this) }
        Startup.step("nav engine") { NavEngine.init(this) }
        // GPS precision follows navigation (precise while navigating, battery-friendly otherwise)
        NavEngine.changed += { LocationTracker.updateMode() }
        Startup.step("voice") { Voice.init(this) }
        Startup.step("explorer") { Explorer.init(this) }
        Startup.step("alerts") { Alerts.init(this) }
        CrashLog.note("start: " + Startup.steps.filter { it.second >= 5 }.joinToString { "${it.first} ${it.second} ms" })
    }
}

data class LatLng(val lat: Double, val lon: Double)

/** A set of "something changed, redraw" callbacks, always fired on the main thread. */
class Signal {
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    operator fun plusAssign(l: () -> Unit) { listeners += l }
    operator fun minusAssign(l: () -> Unit) { listeners -= l }
    fun fire() {
        if (Looper.myLooper() == Looper.getMainLooper()) listeners.forEach { it() }
        else main.post { listeners.forEach { it() } }
    }
}

object Geo {
    private const val R = 6_371_000.0
    private const val M_PER_DEG = 111_320.0

    fun x(lon: Double, world: Double) = (lon + 180.0) / 360.0 * world

    fun y(lat: Double, world: Double): Double {
        val s = sin(Math.toRadians(lat)).coerceIn(-0.9999, 0.9999)
        return (0.5 - ln((1 + s) / (1 - s)) / (4 * PI)) * world
    }

    fun lon(x: Double, world: Double) = x / world * 360.0 - 180.0

    fun lat(y: Double, world: Double) = Math.toDegrees(atan(sinh(PI * (1 - 2 * y / world))))

    /** Metres covered by one world pixel at this latitude. */
    fun metersPerPixel(lat: Double, world: Double) = cos(Math.toRadians(lat)) * 2 * PI * R / world

    fun distance(a: LatLng, b: LatLng): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * R * atan2(sqrt(h), sqrt(1 - h))
    }

    /** Compass bearing from a to b, degrees. */
    fun bearing(a: LatLng, b: LatLng): Float {
        val k = cos(Math.toRadians(a.lat))
        val dx = (b.lon - a.lon) * k; val dy = b.lat - a.lat
        return ((Math.toDegrees(atan2(dx, dy)) + 360) % 360).toFloat()
    }

    /** Point [meters] away from p in direction [bearing]. */
    fun offset(p: LatLng, bearing: Float, meters: Double): LatLng {
        val b = Math.toRadians(bearing.toDouble())
        return LatLng(
            p.lat + meters * cos(b) / M_PER_DEG,
            p.lon + meters * sin(b) / (M_PER_DEG * cos(Math.toRadians(p.lat))),
        )
    }

    fun lerp(a: LatLng, b: LatLng, t: Double) = LatLng(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)

    /** Closest point to p on segment a-b, and its distance in metres (flat approximation). */
    fun project(p: LatLng, a: LatLng, b: LatLng): Pair<LatLng, Double> {
        val k = cos(Math.toRadians(p.lat))
        val ax = (a.lon - p.lon) * k; val ay = a.lat - p.lat
        val bx = (b.lon - p.lon) * k; val by = b.lat - p.lat
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
        val cx = ax + t * dx; val cy = ay + t * dy
        return LatLng(p.lat + cy, p.lon + cx / k) to sqrt(cx * cx + cy * cy) * M_PER_DEG
    }

    fun distanceToSegment(p: LatLng, a: LatLng, b: LatLng) = project(p, a, b).second

    /** Signed smallest difference b - a, in -180..180. */
    fun angleDiff(a: Float, b: Float): Float = ((b - a + 540f) % 360f) - 180f
}

/** Sunrise and sunset, to switch themes between light and dark automatically. */
object Sun {
    /** Daylight between sunrise and ~20 minutes after sunset (simple solar formula, ±10 min). */
    fun isDaylight(p: LatLng, timeMs: Long): Boolean {
        val dayOfYear = ((timeMs / 86_400_000L) % 365.2422).toDouble()
        val decl = Math.toRadians(-23.44 * cos(2 * PI / 365.0 * (dayOfYear + 10)))
        val lat = Math.toRadians(p.lat)
        val cosH = (-0.0145 - sin(lat) * sin(decl)) / (cos(lat) * cos(decl))
        if (cosH <= -1) return true   // midnight sun
        if (cosH >= 1) return false   // polar night
        val halfDayHours = Math.toDegrees(kotlin.math.acos(cosH)) / 15.0
        val noonUtc = 12.0 - p.lon / 15.0
        val hourUtc = (timeMs % 86_400_000L) / 3_600_000.0
        return hourUtc >= noonUtc - halfDayHours && hourUtc <= noonUtc + halfDayHours + 0.33
    }
}

/** Camera and settings shared by the phone and the car screen. */
/**
 * Where one screen's map is looking: zoom, following or panned, rotation, route fitting.
 * The phone and the car each have their own, so moving one never moves the other.
 */
class MapView(private val zoomKey: String) {
    /** Zoom being shown right now (animates toward [targetZoom]). */
    var zoom = 16.5
        private set
    var targetZoom = 16.5
        private set

    /** True while the camera follows the player; false after the user drags the map. */
    var following = true
        private set
    var freeCenter: LatLng? = null
        private set
    var freeBearing = 0f
        private set

    /** Map rotation chosen with a two-finger twist; null = turn with the direction of travel. */
    var userBearing: Float? = null
        private set

    /** A finger is on this map (pinch, drag, twist): its renderer keeps frames light meanwhile. */
    @Volatile var gesture = false

    private var lastTick = 0L

    internal fun load(z: Double) { zoom = z.coerceIn(MapState.MIN_ZOOM, MapState.MAX_ZOOM); targetZoom = zoom }

    /** Button zoom: animates smoothly to the next level. */
    fun zoomBy(delta: Double) {
        targetZoom = (targetZoom + delta).coerceIn(MapState.MIN_ZOOM, MapState.MAX_ZOOM)
        saveZoom()
        MapState.changed.fire()
    }

    /** Pinch zoom: follows the fingers directly. */
    fun zoomByFactor(factor: Float) {
        if (factor <= 0) return
        zoom = (zoom + log2(factor.toDouble())).coerceIn(MapState.MIN_ZOOM, MapState.MAX_ZOOM)
        targetZoom = (zoom - autoTarget).coerceIn(MapState.MIN_ZOOM, MapState.MAX_ZOOM)
        saveZoom()
        MapState.changed.fire()
    }

    private fun saveZoom() = MapState.saveZoom(zoomKey, targetZoom)

    /** While driving: zoomed out a little at speed, in a little just before a turn (on top of your own zoom). */
    private var autoTarget = 0.0
    /** When the map was last moved by hand; it comes back to the car 8 s later while navigating. */
    @Volatile var touchedAt = 0L

    private fun autoZoom(): Double {
        if (!NavEngine.active || NavEngine.arrived || !following || overview || gesture) return 0.0
        val kmh = LocationTracker.speedMps * 3.6
        val fast = -((kmh - 35) / 65).coerceIn(0.0, 1.0) * 1.1          // 35 km/h: 0, 100 km/h: -1.1
        val step = NavEngine.currentStep
        val turning = step != null && step.direction != "straight" && step.type !in setOf("continue", "new name") &&
            NavEngine.distanceToNext < maxOf(120.0, LocationTracker.speedMps * 9.0)
        val v = if (turning) maxOf(fast, -0.2) + 0.45 else fast
        return Math.round(v * 10) / 10.0 // steps of 0.1, so the zoom isn't always moving
    }

    /** Advances the zoom animation. Returns true while animating. */
    fun tick(): Boolean {
        val now = SystemClock.uptimeMillis()
        val dt = (now - lastTick).coerceIn(0, 100)
        lastTick = now
        // panned away while navigating: back to the car after 8 s without touching (not in a
        // convoy: there you look around for the others, and the recenter button stays until pressed)
        if (!following && !overview && NavEngine.active && !Convoy.active && !gesture && touchedAt > 0 && now - touchedAt > 8_000) {
            touchedAt = 0
            android.os.Handler(android.os.Looper.getMainLooper()).post { if (!following) recenter() } // the car draws on its own thread
        }
        autoTarget = autoZoom()
        val goal = (targetZoom + autoTarget).coerceIn(MapState.MIN_ZOOM, MapState.MAX_ZOOM)
        if (abs(goal - zoom) < 0.003) {
            zoom = goal
            return false
        }
        // the automatic part moves gently (about a second); your own zooming stays quick
        zoom += (goal - zoom) * (1 - exp(-dt / (if (abs(goal - zoom) < 1.2 && autoTarget != 0.0) 420.0 else 110.0)))
        return true
    }

    fun pan(center: LatLng, bearing: Float) {
        following = false
        touchedAt = SystemClock.uptimeMillis()
        freeCenter = center
        freeBearing = bearing
        MapState.changed.fire()
    }

    fun recenter() {
        following = true
        freeCenter = null
        userBearing = null
        overview = false
        fitRequest = null
        savedZoom?.let { targetZoom = it; savedZoom = null }
        MapState.changed.fire()
    }

    /** Points the next frame should fit on screen (route preview, overview). */
    var fitRequest: List<LatLng>? = null
        private set
    /** True while showing the whole route instead of following. */
    var overview = false
        private set
    private var savedZoom: Double? = null

    fun requestFit(points: List<LatLng>) {
        if (points.isEmpty()) return
        fitRequest = points
        MapState.changed.fire()
    }

    /** Whole-route view while driving; [recenter] goes back. */
    fun showOverview(points: List<LatLng>) {
        overview = true
        requestFit(points)
    }

    /** Called by a renderer once it has worked out where [fitRequest] fits. */
    fun applyFit(center: LatLng, z: Double) {
        fitRequest = null
        if (savedZoom == null) savedZoom = targetZoom
        following = false
        freeCenter = center
        freeBearing = 0f
        zoom = z.coerceIn(MapState.MIN_ZOOM, 17.5)
        targetZoom = zoom
        MapState.changed.fire()
    }

    /** Two-finger twist: rotate the map by [delta] degrees from [current]. */
    fun rotateBy(delta: Float, current: Float) {
        val b = ((userBearing ?: current) + delta + 720f) % 360f
        userBearing = b
        if (!following) freeBearing = b
        MapState.changed.fire()
    }

    /** Reset rotation to the direction of travel (or north when panning). */
    fun clearRotation() {
        userBearing = null
        if (!following) freeBearing = 0f
        MapState.changed.fire()
    }
}

object MapState {
    val changed = Signal()
    var theme = Theme.OPEN_WORLD
        private set

    /** The phone's map and the car's map: separate, so one never moves the other. */
    val phone = MapView("zoom")
    val car = MapView("carZoom")

    // The phone's view, under the names the phone code (and tests) always used.
    val zoom get() = phone.zoom
    val targetZoom get() = phone.targetZoom
    val following get() = phone.following
    val freeCenter get() = phone.freeCenter
    val freeBearing get() = phone.freeBearing
    val userBearing get() = phone.userBearing
    val overview get() = phone.overview
    val fitRequest get() = phone.fitRequest
    var gesture: Boolean
        get() = phone.gesture
        set(v) { phone.gesture = v }
    fun zoomBy(delta: Double) = phone.zoomBy(delta)
    fun zoomByFactor(factor: Float) = phone.zoomByFactor(factor)
    fun tick() = phone.tick()
    fun pan(center: LatLng, bearing: Float) = phone.pan(center, bearing)
    fun applyFit(center: LatLng, z: Double) = phone.applyFit(center, z)
    fun rotateBy(delta: Float, current: Float) = phone.rotateBy(delta, current)
    fun clearRotation() = phone.clearRotation()

    /** Navigation events (route preview, start): both screens. */
    fun recenter() { phone.recenter(); car.recenter() }
    fun requestFit(points: List<LatLng>) { phone.requestFit(points); car.requestFit(points) }
    fun showOverview(points: List<LatLng>) { phone.showOverview(points); car.showOverview(points) }

    private lateinit var appContext: Context

    fun init(ctx: Context) {
        appContext = ctx.applicationContext
        val p = prefs()
        theme = p.getString("themeName", null)?.let { n -> Theme.entries.firstOrNull { it.name == n } }
            ?: Theme.OPEN_WORLD
        lightMode = LightMode.entries.getOrElse(p.getInt("lightMode", 0)) { LightMode.AUTO }
        phone.load(p.getFloat("zoom", 16.5f).toDouble())
        car.load(p.getFloat("carZoom", p.getFloat("zoom", 16.5f)).toDouble())
        carTheme = p.getString("carTheme", null)?.let { n -> Theme.entries.firstOrNull { it.name == n } }
        imperial = p.getBoolean("imperial", false)
    }

    private fun prefs() = appContext.getSharedPreferences("gamemap", Context.MODE_PRIVATE)
    internal fun saveZoom(key: String, z: Double) {
        if (::appContext.isInitialized) prefs().edit().putFloat(key, z.toFloat()).apply()
    }

    fun nextTheme(): Theme = setTheme(Theme.entries[(theme.ordinal + 1) % Theme.entries.size])

    /** Style for the car screen; null = same as the phone. */
    var carTheme: Theme? = null
        private set

    fun setCarTheme(t: Theme?) {
        carTheme = t
        prefs().edit().putString("carTheme", t?.name ?: "").apply()
        changed.fire()
    }

    /** The car's theme button: cycles the car style (or the shared one when they're the same). */
    fun nextCarTheme(): Theme {
        val c = carTheme ?: return nextTheme()
        val n = Theme.entries[(c.ordinal + 1) % Theme.entries.size]
        setCarTheme(n)
        return n
    }

    /** Setting: fuel, parking, food… as icons on the map at street level. */
    var showPlaces: Boolean
        get() = !::appContext.isInitialized || prefs().getBoolean("showPlaces", true)
        set(v) { prefs().edit().putBoolean("showPlaces", v).apply(); changed.fire() }

    /** Miles and mph instead of kilometres. */
    var imperial = false
        private set

    fun setImperial(on: Boolean) {
        imperial = on
        prefs().edit().putBoolean("imperial", on).apply()
        changed.fire()
    }

    fun setTheme(t: Theme): Theme {
        theme = t
        prefs().edit().putString("themeName", theme.name).apply()
        changed.fire()
        return theme
    }

    // ---------------------------------------------------------------- light / dark

    enum class LightMode(val label: String) { AUTO("Auto (sunrise / sunset)"), LIGHT("Light"), DARK("Dark") }

    var lightMode = LightMode.AUTO
        private set

    private var darkNow = false
    private var darkCheckedAt = 0L

    /** Setting: in the car, the map goes dark when the headlights are on (the car's night mode). */
    var followHeadlights: Boolean
        get() = prefs().getBoolean("followHeadlights", true)
        set(v) { prefs().edit().putBoolean("followHeadlights", v).apply(); darkCheckedAt = 0; TileCache.clear(); changed.fire() }

    /** The car's day/night state while Android Auto is connected (null = not connected). */
    @Volatile var carNight: Boolean? = null
        private set

    fun setCarNight(night: Boolean?) {
        if (carNight == night) return
        val before = dark
        carNight = night
        if (dark != before) { TileCache.clear(); changed.fire() }
    }

    /** True when the dark version of the theme should be shown right now. */
    val dark: Boolean
        get() {
            carNight?.let { if (followHeadlights) return it }
            when (lightMode) {
                LightMode.LIGHT -> return false
                LightMode.DARK -> return true
                LightMode.AUTO -> {}
            }
            val now = System.currentTimeMillis()
            if (now - darkCheckedAt > 60_000) {
                darkCheckedAt = now
                val pos = LocationTracker.position ?: LatLng(37.98, 23.73)
                val d = !Sun.isDaylight(pos, now)
                if (d != darkNow) {
                    darkNow = d
                    TileCache.clear()
                    changed.fire()
                }
            }
            return darkNow
        }

    fun setLightMode(m: LightMode) {
        lightMode = m
        prefs().edit().putInt("lightMode", m.ordinal).apply()
        darkCheckedAt = 0
        changed.fire()
    }

    const val MIN_ZOOM = 1.5 // the whole of Europe and beyond: countries and their names
    const val MAX_ZOOM = 19.0
}

/** Background threads that never compete with drawing the screen. */
object Bg {
    fun factory(name: String) = java.util.concurrent.ThreadFactory { r ->
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            r.run()
        }, name).apply { isDaemon = true }
    }

    fun pool(name: String, n: Int): java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newFixedThreadPool(n, factory(name))
}
