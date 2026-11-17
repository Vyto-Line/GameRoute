package gr.gamemap

import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * A convoy invite that arrives while GameRoute is on screen (phone or car): a small card for about
 * 10 s (phone: out from under Where to? or the turn box; car: centred at the top) with Join and Not
 * now. It waits while a turn is under 200 m away. Missed: it stays in Friends and on the red number.
 */
object InvitePop {
    const val SHOW_MS = 10_000L
    private val main = Handler(Looper.getMainLooper())
    val changed = Signal()

    /** The invite on show now, and when it came up. */
    @Volatile var current: FriendCore.Invite? = null
        private set
    @Volatile var shownAt = 0L
        private set
    private val popped = HashSet<String>()
    private val waiting = ArrayList<FriendCore.Invite>()
    private var appCtx: Context? = null

    fun init(ctx: Context) { appCtx = ctx.applicationContext }

    /** A new invite while the app is on screen. */
    fun offer(i: FriendCore.Invite) {
        if (!popped.add(i.convoy + "|" + i.count)) return
        if (Convoy.active && Convoy.code == i.convoy) return // already in it
        waiting += i
        tryShow()
    }

    /** A turn coming up (under 200 m): the card waits. */
    private fun turnClose() = NavEngine.active && !NavEngine.arrived && NavEngine.distanceToNext in 0.0..200.0

    private val retry = Runnable { tryShow() }

    private fun tryShow() {
        main.removeCallbacks(retry)
        if (current != null || waiting.isEmpty()) return
        if (turnClose()) { main.postDelayed(retry, 2000); return }
        val i = waiting.removeAt(0)
        current = i; shownAt = System.currentTimeMillis()
        ping()
        changed.fire()
        main.postDelayed(hide, SHOW_MS)
    }

    private val hide = Runnable { close() }

    /** Gone (timed out, Join or Not now): the next waiting one, if any. */
    fun close() {
        main.removeCallbacks(hide)
        if (current == null) return
        current = null
        changed.fire()
        main.postDelayed(retry, 600)
    }

    /** Not now: the sender sees it (as from the notification). */
    fun notNow() { current?.let { Friends.answer(it.convoy, "no") }; close() }

    /** Join: the caller joins (phone: name first; car: straight in). */
    fun take(): FriendCore.Invite? { val i = current; close(); i?.let { Friends.cancelNotification(it.convoy) }; return i }

    /** One sound, one short buzz. */
    private fun ping() {
        val ctx = appCtx ?: return
        try {
            val uri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
            android.media.RingtoneManager.getRingtone(ctx, uri)?.play()
        } catch (_: Exception) {}
        try {
            val v = if (android.os.Build.VERSION.SDK_INT >= 31) ctx.getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator
                else @Suppress("DEPRECATION") ctx.getSystemService(android.os.Vibrator::class.java)
            v?.vibrate(android.os.VibrationEffect.createOneShot(120, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {}
    }

    /** How much of the 10 s is left (1 → 0), for the bar. */
    fun left(): Float = if (current == null) 0f else (1f - (System.currentTimeMillis() - shownAt) / SHOW_MS.toFloat()).coerceIn(0f, 1f)

    internal fun resetForTest() { popped.clear(); waiting.clear(); current = null; main.removeCallbacks(hide); main.removeCallbacks(retry) }
}
