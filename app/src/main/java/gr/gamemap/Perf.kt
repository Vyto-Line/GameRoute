package gr.gamemap

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager

/**
 * Settings › Performance: how smooth the map is against how long the battery lasts.
 *
 * Mode Auto (the default) is smooth, and saves battery by itself when the phone's Battery Saver is on
 * or the battery is under 20 %; Smooth and Battery fix it either way. Beside the mode: map effects
 * (rain, snow, glow, scanlines, drafting grid), animations (Full / Reduced / Off), map smoothness
 * (High / Normal / Low), live friend updates and saving GPS when stopped.
 *
 * Also watches the map: a phone that keeps taking too long to draw (weak hardware) loses the effects
 * for the rest of the session. And it measures each drive: battery used per hour and frames per second.
 */
object Perf {
    val changed = Signal()
    private var ctx: Context? = null
    private fun prefs() = ctx?.getSharedPreferences("gamemap", Context.MODE_PRIVATE)

    const val AUTO = 0; const val SMOOTH = 1; const val BATTERY = 2
    const val FULL = 0; const val REDUCED = 1; const val OFF = 2
    const val HIGH = 0; const val NORMAL = 1; const val LOW = 2

    fun init(c: Context) { ctx = c.applicationContext }

    private fun int(k: String, def: Int) = prefs()?.getInt(k, def) ?: def
    private fun bool(k: String, def: Boolean) = prefs()?.getBoolean(k, def) ?: def
    private fun put(k: String, v: Any) {
        prefs()?.edit()?.apply { if (v is Int) putInt(k, v) else putBoolean(k, v as Boolean) }?.apply()
        changed.fire()
    }

    var mode: Int get() = int("perfMode", AUTO); set(v) = put("perfMode", v.coerceIn(0, 2))
    var effects: Boolean get() = bool("perfEffects", true); set(v) = put("perfEffects", v)
    var animations: Int get() = int("perfAnim", FULL); set(v) = put("perfAnim", v.coerceIn(0, 2))
    var smoothness: Int get() = int("perfSmooth", NORMAL); set(v) = put("perfSmooth", v.coerceIn(0, 2))
    var liveFriends: Boolean get() = bool("perfLive", true); set(v) = put("perfLive", v)
    var saveGpsStopped: Boolean get() = bool("perfGps", true); set(v) = put("perfGps", v)

    // ---- is the battery being saved right now?
    private var batteryAt = 0L
    private var batteryPct = 100
    private var powerSave = false

    /** The battery level, 0-100 (read at most once a minute). */
    fun battery(): Int {
        val now = System.currentTimeMillis()
        val c = ctx ?: return 100
        if (now - batteryAt > 60_000) {
            batteryAt = now
            try {
                val i = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
                val plugged = (i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
                batteryPct = if (level < 0) 100 else if (plugged) 100 else level * 100 / maxOf(scale, 1)
                powerSave = c.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true
            } catch (_: Exception) {}
        }
        return batteryPct
    }

    /** True when the battery is being saved: Battery mode, or Auto with Battery Saver on / under 20 %. */
    val saving: Boolean get() = when (mode) {
        BATTERY -> true
        SMOOTH -> false
        else -> battery() < 20 || powerSave
    }

    /** This phone took too long to draw, again and again: effects off until the app restarts. */
    @Volatile var struggling = false
        private set

    /** Map effects drawn right now. */
    val effectsOn: Boolean get() = effects && !saving && !struggling

    /** Animations right now: saving the battery means at most Reduced. */
    val anim: Int get() = if (saving) maxOf(animations, REDUCED) else animations

    /**
     * How long to wait before the next frame, in ms, while something on the map moves.
     * [moving]: the car is driving (the arrow glides). Standing still, a slow frame rate is plenty.
     */
    fun frameDelay(moving: Boolean): Long {
        val s = if (saving) maxOf(smoothness, LOW) else smoothness
        return when (s) {
            HIGH -> if (moving) 16L else 33L
            NORMAL -> if (moving) 33L else 50L
            else -> if (moving) 66L else 100L
        }
    }

    /** Only the effects are moving (rain, snow) while the car stands still: a few frames a second. */
    const val EFFECTS_ONLY_DELAY = 200L

    // ---- watching the drawing
    private val recent = LongArray(90)
    private var recentN = 0
    private var slowStreak = 0

    /** Every drawn frame: how long it took (ms). */
    fun frameDrawn(ms: Long) {
        recent[recentN % recent.size] = ms; recentN++
        if (recentN % recent.size == 0 && !struggling && effects) {
            val avg = recent.average()
            slowStreak = if (avg > 45) slowStreak + 1 else 0
            if (slowStreak >= 2) {
                struggling = true
                CrashLog.note("map slow (${avg.toInt()} ms a frame): effects off")
                changed.fire()
            }
        }
        if (drive != null) driveFrames++
    }

    // ---- the last drive
    private var drive: Long? = null
    private var driveBattery = -1
    private var driveFrames = 0L

    /** A route started (or resumed). */
    fun driveStarted() {
        drive = System.currentTimeMillis(); driveFrames = 0
        batteryAt = 0; driveBattery = battery()
    }

    /** The trip ended: battery used per hour and frames a second, kept for the Performance page. */
    fun driveEnded() {
        val start = drive ?: return
        drive = null
        val secs = (System.currentTimeMillis() - start) / 1000.0
        if (secs < 300) return // under 5 minutes says little
        batteryAt = 0
        val used = (driveBattery - battery()).coerceAtLeast(0)
        val perHour = if (driveBattery >= 100 && batteryPct >= 100) -1 else (used * 3600 / secs).toInt()
        val fps = (driveFrames / secs).toInt()
        prefs()?.edit()?.putInt("perfLastBattery", perHour)?.putInt("perfLastFps", fps)?.putLong("perfLastAt", System.currentTimeMillis())?.apply()
        changed.fire()
    }

    /** "battery 7% an hour · 30 fps", or null before the first drive of 5 minutes. */
    fun lastDrive(): String? {
        val p = prefs() ?: return null
        if (!p.contains("perfLastAt")) return null
        val b = p.getInt("perfLastBattery", -1); val f = p.getInt("perfLastFps", 0)
        val bat = if (b < 0) L.t("charging") else L.t("battery $b% an hour")
        return "$bat · $f fps"
    }

    internal fun resetForTest() { struggling = false; slowStreak = 0; recentN = 0; drive = null }
}
