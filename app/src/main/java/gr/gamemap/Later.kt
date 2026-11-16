package gr.gamemap

/**
 * Messages that aren't urgent (a landmark found, friends' news, XP) wait here while you drive, and
 * show when you stop: nothing pops up on the screen at speed. Safety alerts and convoy news never
 * wait (they're shown by their own code, not through here).
 */
object Later {
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val items = ArrayList<String>()
    val changed = Signal()

    /** Driving right now: on a route and moving. */
    val driving: Boolean get() = NavEngine.active && !NavEngine.arrived && LocationTracker.speedMps > 2f

    val count: Int get() = synchronized(items) { items.size }

    /** Keeps [msg] for later (the same one twice is kept once). */
    fun add(msg: String) {
        synchronized(items) { if (msg in items) return; items += msg; if (items.size > 20) items.removeAt(0) }
        main.post { changed.fire() }
    }

    /** Everything kept, and forgets it. */
    fun take(): List<String> {
        val out = synchronized(items) { items.toList().also { items.clear() } }
        if (out.isNotEmpty()) main.post { changed.fire() }
        return out
    }
}
