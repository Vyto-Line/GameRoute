package gr.gamemap

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Convoy: a group driving to the same place. Everyone in it sees the others on the map with
 * their speed, arrival time and how far along the route they are.
 *
 * No accounts or server of our own: each convoy is a private topic on the free ntfy.sh (end-to-end encrypted with a key from the code, see Seal)
 * relay, plus two public MQTT brokers for real time (ConvoyLink). Members post a small update every 2 s while
 * driving and listen to the others. At the start everyone presses "I'm ready"; when all have, a 3-2-1 countdown
 * runs on every phone and navigation starts by itself.
 */
object Convoy {
    class Member(
        val id: String,
        var name: String,
        var pos: LatLng? = null,
        var heading: Float = 0f,
        var kmh: Int = 0,
        var eta: Long = 0,
        var left: Double = -1.0,
        var total: Double = -1.0,
        var state: String = "ready", // ready, driving, arrived, left
        var arrivedAt: Long = 0,
        var seen: Long = 0,
    ) {
        /** Their friend id and code (to add them as a friend from the convoy); blank on old versions. */
        @Volatile var fid = ""
        @Volatile var fc = ""
        /** On a route of their own: where to (so the others can join them). */
        @Volatile var ownDest: Place? = null
        /** Pressed "I'm ready" at the start (and when: the race starts 4 s after the last one). */
        @Volatile var ready = false
        @Volatile var readyAt = 0L
        /** Drives the same route but isn't in the race (no %, not ranked, the countdown doesn't wait). */
        @Volatile var along = false
        /** Only shares their location: not on the trip (not in the lists' race, not on the car screen). */
        @Volatile var sharing = false
        /** Their level and title, if they chose to show it. */
        @Volatile var lv = ""
        /** 0 at the start … 1 at the destination. */
        val progress: Double get() = if (state == "arrived") 1.0 else if (total > 0 && left >= 0) (1 - left / total).coerceIn(0.0, 1.0) else 0.0
        /** Updates come every second while moving (5 s when stopped), so 25 s without one = signal lost. */
        val stale: Boolean get() = System.currentTimeMillis() - seen > (if (state == "driving" || kmh >= 3) 25_000 else 150_000)

        /**
         * Signal lost for this many seconds (0 = fine): driving and no update for 6 s. The arrow fades
         * and the lists say "Signal lost 12 s"; the next update brings them back by itself.
         */
        val lostSecs: Int get() {
            if (state != "driving" && kmh < 3) return 0
            val gap = System.currentTimeMillis() - seen
            return if (gap > 6_000) (gap / 1000).toInt() else 0
        }

        /**
         * Their clock against mine (ms to add to their time stamps): the smallest gap between when
         * they sent and when it came, over the last updates (the real-time line takes ~0.1 s, so the
         * smallest gap is nearly all clock difference). Countdowns and gliding use it, so every
         * phone counts down to the same moment even when phone clocks differ by seconds.
         */
        private val gaps = LongArray(20) { Long.MAX_VALUE }
        private var gapN = 0
        @Volatile var skew = 0L
            private set
        fun clockSample(theirTs: Long, now: Long) {
            if (theirTs <= 0) return
            gaps[gapN++ % gaps.size] = now - theirTs
            skew = gaps.min().takeIf { it != Long.MAX_VALUE } ?: 0L
        }
        /** Time stamp of the newest update taken (older ones arriving late are ignored). */
        @Volatile var lastTs = 0L

        // ---- gliding between updates
        @Volatile var fixAt = 0L          // when the last position was measured (their clock ≈ ours)
        @Volatile var shown: LatLng? = null
        @Volatile private var shownAt = 0L
        @Volatile var shownHeading = 0f
        private var routeOf: Any? = null
        private var routeFix = -1L
        private var routeAt = -1.0

        /** Where they probably are now: carried on along the road (or their heading) at their speed. */
        fun predicted(now: Long): LatLng? {
            val p = pos ?: return null
            if (state == "arrived" || kmh < 3 || stale) return p
            val secs = ((now - fixAt) / 1000.0).coerceIn(0.0, 8.0)
            val metres = kmh / 3.6 * secs
            // on my route (same destination, usually the same roads): follow its bends;
            // the route position is looked up once per update, not every frame
            val route = NavEngine.current
            if (routeOf !== route || routeFix != fixAt) { routeOf = route; routeFix = fixAt; routeAt = NavEngine.routeDistanceOf(p) ?: -1.0 }
            if (routeAt >= 0) NavEngine.routePointAt(routeAt + metres)?.let { return it }
            return Geo.offset(p, heading, metres.coerceAtMost(kmh / 3.6 * 6))
        }

        /**
         * The point to draw. The estimate moves on smoothly between updates; the drawn arrow follows
         * it with a short lag (0.3 s), so a new update never makes it jump, only gently correct.
         */
        fun display(now: Long): LatLng? {
            val target = predicted(now) ?: return null
            val s = shown
            val dt = (now - shownAt).coerceAtLeast(0)
            shownAt = now
            if (s == null || dt > 3000 || Geo.distance(s, target) > 300) {
                shown = target; shownHeading = heading; return target
            }
            val k = 1 - kotlin.math.exp(-dt / 300.0)
            val out = LatLng(s.lat + (target.lat - s.lat) * k, s.lon + (target.lon - s.lon) * k)
            shownHeading = (shownHeading + Geo.angleDiff(shownHeading, heading) * k.toFloat() + 360f) % 360f
            shown = out
            return out
        }

        val moving: Boolean get() = state != "arrived" && kmh >= 3 && !stale
        /** Their colour: different for everyone in the convoy (see [colorOf]). */
        val color: Int get() = colorOf(id)
    }

    const val PAGE = "https://vyto-line.github.io/GameRoute/convoy/"
    private val palette = intArrayOf(
        Color.rgb(47, 210, 122), Color.rgb(255, 146, 43), Color.rgb(77, 171, 247),
        Color.rgb(255, 107, 154), Color.rgb(255, 212, 59), Color.rgb(34, 211, 238), Color.rgb(151, 117, 250),
        Color.rgb(250, 82, 82), Color.rgb(148, 216, 45), Color.rgb(200, 160, 120),
    )

    /**
     * Everyone in the convoy (me too) gets a different colour: the drivers' ids in order, one
     * colour each. Every phone sees the same ids, so everyone sees the same colours.
     */
    @Volatile private var colorIds: Pair<Int, List<String>> = 0 to emptyList()
    fun colorOf(id: String): Int {
        val key = members.keys.hashCode() * 31 + members.size
        val ids = colorIds.takeIf { it.first == key }?.second
            ?: (members.keys + myId).sorted().also { colorIds = key to it }
        val i = ids.indexOf(id)
        return palette[if (i >= 0) i % palette.size else Math.floorMod(id.hashCode(), palette.size)]
    }
    val myColor: Int get() = colorOf(myId)

    private lateinit var appContext: Context
    private fun prefs() = appContext.getSharedPreferences("gamemap", Context.MODE_PRIVATE)
    val changed = Signal()
    private val main = Handler(Looper.getMainLooper())

    @Volatile var code: String? = null
        private set
    val active get() = code != null
    @Volatile var destination: Place? = null
        private set
    /** Others in the convoy (not me), by id. */
    val members = ConcurrentHashMap<String, Member>()

    /** This phone's id in convoys (random, kept). */
    val myId: String by lazy {
        prefs().getString("convoyId", null) ?: randomCode(10).also { prefs().edit().putString("convoyId", it).apply() }
    }
    var myName: String
        get() = prefs().getString("convoyName", "").orEmpty()
        set(v) {
            prefs().edit().putString("convoyName", v.trim().take(16)).apply()
            if (code != null) publish(hello(myState()))
            changed.fire()
        }

    fun init(ctx: Context) {
        appContext = ctx.applicationContext
        proposed += { notifyProposal() }
        // a convoy still on when the app was closed (e.g. phone restarted mid-drive)
        val c = prefs().getString("convoyCode", null)
        if (c != null) {
            val d = prefs().getString("convoyDest", null)?.let { s ->
                try { val o = JSONObject(s); Place(o.getString("n"), o.optString("a"), LatLng(o.getDouble("lat"), o.getDouble("lon"))) } catch (e: Exception) { null }
            }
            code = c; destination = d; destAt = prefs().getLong("convoyDestAt", 0); joinedAt = prefs().getLong("convoyJoinedAt", 0)
            host = prefs().getString("convoyHost", null) == c
            listen(c)
            main.post { NavService.update(appContext) } // still in a convoy from before: keep sharing
        }
    }

    private fun randomCode(n: Int): String {
        val abc = "abcdefghjkmnpqrstuvwxyz23456789" // no 0/o, 1/l/i
        val r = SecureRandom()
        return (1..n).map { abc[r.nextInt(abc.length)] }.joinToString("")
    }

    fun link(): String? = code?.let { PAGE + "#" + it }

    /**
     * I'm driving to the convoy's destination. A route somewhere else is my own: the convoy
     * still sees where I am, but the convoy screens (strip, standings) stay out of the way.
     */
    val onConvoyRoute: Boolean get() {
        val d = destination ?: return false
        val mine = NavEngine.destination ?: return false
        return NavEngine.active && Geo.distance(d.pos, mine) < 250
    }

    /** What I tell the others: at the start (ready), on the convoy's route, arrived, or on my own route. */
    private fun myState(): String = when {
        !NavEngine.active -> "ready"
        !onConvoyRoute -> "own"
        NavEngine.arrived -> "arrived"
        else -> "driving"
    }

    /** The code as people read it out: "K7P 2QX". */
    fun pretty(): String? = code?.uppercase()?.let { if (it.length == 6) it.take(3) + " " + it.drop(3) else it }

    /** True while someone in the convoy is moving (their arrow glides, so keep drawing). */
    val anyMoving: Boolean get() = members.values.any { it.moving }

    /** I started this convoy: my button says START, the others' say READY UP. */
    @Volatile var host = false
        private set

    /** One short word for the convoy chip: how it's going. */
    fun status(): String = when {
        code == null -> ""
        counting -> "3-2-1"
        onConvoyRoute && NavEngine.arrived -> "arrived"
        onConvoyRoute || underway -> "driving"
        destination == null -> "no destination"
        meReady -> "ready"
        else -> "waiting"
    }

    /** Starts a new convoy (to [dest], or the destination comes later); returns the invite link. */
    fun create(dest: Place?): String {
        leave(quiet = true)
        val c = randomCode(6)
        host = true; prefs().edit().putString("convoyHost", c).apply()
        setCode(c, dest)
        publish(hello("ready"))
        return link()!!
    }

    /** Joins from a link or a code; the destination arrives with the first messages. */
    fun join(linkOrCode: String): Boolean {
        val c = linkOrCode.trim().substringAfterLast('#').substringAfterLast('/').filter { it.isLetterOrDigit() }.lowercase()
        if (c.length < 6) return false
        if (c == code) return true
        leave(quiet = true)
        host = false
        setCode(c, null)
        publish(hello("ready"))
        // invited to it by friends: they see "Joined"
        if (Friends.ready && Friends.core.invites.any { it.convoy == c }) Friends.answer(c, "joined")
        return true
    }

    /** When the destination was chosen: a newer choice (anyone's) wins on every phone. */
    @Volatile private var destAt = 0L
    /** When I joined this convoy (destinations chosen before that are just taken). */
    @Volatile private var joinedAt = 0L

    /** Someone chose a (new) destination: waiting for me to accept or decline it. */
    /** [place] null = they removed the destination (the convoy just shares where everyone is). */
    class Proposal(val place: Place?, val from: String, val at: Long)
    @Volatile var proposal: Proposal? = null
        private set
    private val declined = java.util.Collections.synchronizedSet(HashSet<Long>())
    /** Fires once per new proposal (screens ask; a notification if the app isn't open). */
    val proposed = Signal()

    /** Go with the others' choice: it becomes the destination, and a running route goes there. */
    fun accept() {
        val p = proposal ?: return
        proposal = null
        cancelNotification()
        val wasOnIt = onConvoyRoute
        destination = p.place; destAt = p.at; saveDest()
        meReady = false
        // a new place: a route there replaces mine. Removed: my route stays (I end it if I want)
        if (p.place != null && NavEngine.active && !NavEngine.arrived) { NavEngine.start(appContext, p.place); MapState.recenter() }
        else if (p.place == null && wasOnIt) publish(hello(myState()))
        else publish(hello("ready"))
        changed.fire()
    }

    fun decline() {
        val p = proposal ?: return
        declined += p.at
        proposal = null
        cancelNotification()
        changed.fire()
    }

    /** The phone's notification for a proposal: it shows even when the app isn't open. */
    private fun notifyProposal() {
        val p = proposal ?: return
        try {
            val nm = appContext.getSystemService(android.app.NotificationManager::class.java) ?: return
            if (android.os.Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(
                android.app.NotificationChannel("convoy", L.t("Convoy"), android.app.NotificationManager.IMPORTANCE_HIGH))
            val open = android.app.PendingIntent.getActivity(appContext, 3,
                android.content.Intent(appContext, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP),
                android.app.PendingIntent.FLAG_IMMUTABLE)
            val n = androidx.core.app.NotificationCompat.Builder(appContext, "convoy")
                .setSmallIcon(android.R.drawable.ic_menu_directions)
                .setContentTitle(L.t(if (p.place == null) "${p.from} removed the destination" else "${p.from} suggests a place"))
                .setContentText(L.t("${p.place?.name ?: L.t("No destination")}: tap to confirm or cancel"))
                .setContentIntent(open).setAutoCancel(true)
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                .build()
            nm.notify(NOTE_ID, n)
        } catch (_: Exception) {}
    }

    fun cancelNotification() {
        try { appContext.getSystemService(android.app.NotificationManager::class.java)?.cancel(NOTE_ID) } catch (_: Exception) {}
    }

    private const val NOTE_ID = 31

    /** No destination: the convoy just shares where everyone is (anyone can choose one later). */
    fun clearDestination() {
        if (code == null || destination == null) return
        destination = null; destAt = System.currentTimeMillis()
        proposal = null; cancelNotification()
        meReady = false
        saveDest()
        publish(hello(myState()))
        changed.fire()
    }

    /** Sets or changes where the convoy goes; everyone gets it with the next update. */
    fun setDestination(dest: Place) {
        if (code == null) return
        destination = dest; destAt = System.currentTimeMillis()
        proposal = null; cancelNotification() // my own choice is the newest
        saveDest()
        publish(hello(myState()))
        changed.fire()
    }

    private fun saveDest() {
        val d = destination
        val e = prefs().edit().putLong("convoyDestAt", destAt)
        if (d != null) e.putString("convoyDest", JSONObject().put("n", d.name).put("a", d.address).put("lat", d.pos.lat).put("lon", d.pos.lon).toString())
        else e.remove("convoyDest")
        e.apply()
    }

    private fun setCode(c: String, dest: Place?) {
        code = c; destination = dest; members.clear(); destAt = if (dest != null) System.currentTimeMillis() else 0
        joinedAt = System.currentTimeMillis(); proposal = null; declined.clear()
        prefs().edit().putLong("convoyJoinedAt", joinedAt).apply()
        val e = prefs().edit().putString("convoyCode", c)
        if (dest != null) e.putString("convoyDest", JSONObject().put("n", dest.name).put("a", dest.address).put("lat", dest.pos.lat).put("lon", dest.pos.lon).toString())
        else e.remove("convoyDest")
        e.apply()
        listen(c)
        main.post { LocationTracker.updateMode(); NavService.update(appContext); Friends.start() } // GPS on, and kept on with the app closed
        changed.fire()
    }

    fun leave(quiet: Boolean = false) {
        val c = code ?: return
        if (!quiet) publish(JSONObject().put("t", "bye").put("id", myId).put("ts", System.currentTimeMillis()), c)
        if (Friends.ready) Friends.leftConvoy(c) // my invites to it disappear on my friends' phones
        code = null; destination = null; members.clear()
        meReady = false; meAlong = false; meSharing = false; goAt = 0; main.removeCallbacks(countdown); proposal = null
        host = false
        prefs().edit().remove("convoyCode").remove("convoyDest").remove("convoyHost").apply()
        listener?.interrupt(); listener = null
        ConvoyLink.stop(if (quiet) null else myId)
        main.post { LocationTracker.updateMode(); NavService.update(appContext) }
        changed.fire()
    }

    // ------------------------------------------------------------- sending

    private val sender = Bg.pool("convoy-send", 1)
    @Volatile private var sentAt = 0L
    private var routeTotal = -1.0

    private fun hello(state: String): JSONObject {
        val o = JSONObject().put("t", "pos").put("id", myId).put("n", myName.ifBlank { "Driver" })
            .put("st", state).put("ts", System.currentTimeMillis())
        // so the others can add me as a friend (inside the encrypted convoy only)
        if (Friends.ready) o.put("fid", Friends.core.fid).put("fc", Friends.core.code)
        destination?.let { o.put("dn", it.name).put("da", it.address).put("dlat", it.pos.lat).put("dlon", it.pos.lon).put("dt", destAt) }
            ?: if (destAt > 0) o.put("dt", destAt).put("dx", true) else null // removed (and when)
        LocationTracker.position?.let { o.put("lat", it.lat).put("lon", it.lon).put("h", LocationTracker.heading.toDouble()) }
        o.put("s", Math.round(LocationTracker.speedMps * 3.6f))
        if (meReady && state == "ready") o.put("rd", true).put("ra", meReadyAt)
        if (meAlong) o.put("al", true) // driving along, not racing (kept all the way)
        if (meSharing) o.put("sh", true) // only sharing my location
        if (Xp.showToFriends) o.put("lv", Xp.badge()) // my level, only if I chose to show it
        // a route of my own: where to, so the others can join me
        if (NavEngine.active && !onConvoyRoute) NavEngine.destination?.let {
            o.put("on", NavEngine.destinationName).put("olat", it.lat).put("olon", it.lon)
        }
        if (onConvoyRoute) { // (a route of my own: only where I am and my speed)
            val left = NavEngine.remainingDistance
            if (routeTotal < left) routeTotal = left
            o.put("left", left).put("tot", routeTotal)
                .put("eta", System.currentTimeMillis() + (NavEngine.remainingSeconds * 1000).toLong())
        }
        return o
    }

    /**
     * On each location fix (once a second in a convoy): sent every second while moving, every 5 s
     * when stopped on a route, every 20 s otherwise (the real-time line carries these; the ntfy
     * backup gets one a minute, plus changes such as arriving).
     */
    fun tick() {
        if (code == null) return
        val now = System.currentTimeMillis()
        val driving = NavEngine.active && !NavEngine.arrived
        // moving: every second (with or without a route), so the others see you in real time
        val moving = LocationTracker.speedMps > 1.5f
        val gap = when { moving -> 1_000; driving -> 5_000; else -> 20_000 }
        if (meReady && goAt == 0L) main.post { checkGo() } // someone at the start may have dropped out
        if (now - sentAt < gap) return
        publish(hello(myState()))
    }

    /** Called when this driver arrives: tell the group right away. */
    fun arrived() {
        if (code == null || !onConvoyRoute) return // arriving somewhere of my own isn't the convoy's finish
        publish(hello("arrived").put("at", System.currentTimeMillis()))
    }

    fun startedDriving() {
        routeTotal = -1.0
        meReady = false
        // was only sharing my location, now on the convoy's route: I drive along
        if (meSharing && onConvoyRoute) { meSharing = false; meAlong = true }
        if (code != null) { sentAt = 0; tick() }
    }

    @Volatile private var ntfyAt = 0L
    @Volatile private var ntfyState = ""

    private fun publish(o: JSONObject, topic: String? = code) {
        val t = topic ?: return
        if (t.length > 6 && t.startsWith("test")) return // a made-up test convoy (test builds): stays on this phone
        val now = System.currentTimeMillis()
        sentAt = now
        // ntfy (backup, and the web page's fallback) allows 250 messages a day: one a minute,
        // plus anything that matters at once (arrived, left, a name change)
        val st = o.optString("t") + o.optString("st") + o.optBoolean("rd") + o.optString("n")
        val important = o.optString("t") == "bye" || st != ntfyState
        val viaNtfy = important || now - ntfyAt > 60_000
        if (viaNtfy) { ntfyAt = now; ntfyState = st }
        sender.execute {
            val sealed = try { Seal.seal(Seal.convoyKey(t), o.toString()) } catch (e: Exception) { return@execute }
            if (t == code) ConvoyLink.publish(myId, sealed)
            if (viaNtfy) try { Http.post("https://ntfy.sh/" + Seal.convoyTopic(t), sealed, "text/plain", timeout = 8000) } catch (_: Exception) {}
        }
    }

    // ------------------------------------------------------------- listening

    @Volatile private var listener: Thread? = null

    private fun listen(c: String) {
        // the real-time line (MQTT); ntfy below stays as the backup
        sender.execute {
            val key = try { Seal.convoyKey(c) } catch (e: Exception) { return@execute }
            ConvoyLink.start("gameroute/" + Seal.convoyTopic(c)) { msg ->
                if (code == c) Seal.open(key, msg)?.let { try { take(JSONObject(it)) } catch (_: Exception) {} }
            }
        }
        listener?.interrupt()
        val t = Thread {
            var since = "12h"
            val key = try { Seal.convoyKey(c) } catch (e: Exception) { return@Thread }
            val topic = Seal.convoyTopic(c)
            while (code == c && !Thread.currentThread().isInterrupted) {
                try {
                    val conn = URL("https://ntfy.sh/$topic/json?since=$since").openConnection() as HttpURLConnection
                    conn.connectTimeout = 10_000; conn.readTimeout = 90_000 // ntfy sends a keepalive every 45 s
                    conn.setRequestProperty("User-Agent", "GameRoute/1.0 (Android)")
                    conn.inputStream.bufferedReader().use { r ->
                        while (code == c) {
                            val line = r.readLine() ?: break
                            val m = try { JSONObject(line) } catch (e: Exception) { continue }
                            if (m.optString("event") != "message") continue
                            since = m.optString("id", since)
                            Seal.open(key, m.optString("message"))?.let { try { take(JSONObject(it)) } catch (_: Exception) {} }
                        }
                    }
                } catch (e: InterruptedException) { return@Thread } catch (_: Exception) {}
                try { Thread.sleep(5_000) } catch (e: InterruptedException) { return@Thread }
            }
        }
        t.isDaemon = true; t.priority = Thread.MIN_PRIORITY
        listener = t; t.start()
    }

    internal fun take(o: JSONObject) {
        val id = o.optString("id"); if (id.isBlank()) return
        // the destination travels with every message (so whoever joins late gets it too)
        // When someone chooses a new one, the others are asked first (it changes their route).
        val dt = o.optLong("dt", 0)
        if (o.has("dlat") && id != myId) {
            val p = Place(o.optString("dn", "Destination"), o.optString("da"), LatLng(o.getDouble("dlat"), o.getDouble("dlon")))
            val known = destination
            val same = known != null && Geo.distance(known.pos, p.pos) < 30
            when {
                // the convoy's destination from before I joined: just take it
                known == null && dt <= joinedAt + 2000 -> { destination = p; destAt = dt; saveDest() }
                dt <= destAt || same -> if (same && dt > destAt) { destAt = dt; saveDest() }
                dt in declined || proposal?.at == dt -> {}
                else -> {
                    proposal = Proposal(p, o.optString("n", members[id]?.name ?: "Driver"), dt)
                    main.post { proposed.fire(); changed.fire() }
                }
            }
        } else if (o.optBoolean("dx") && id != myId && destination != null && dt > destAt && dt !in declined && proposal?.at != dt) {
            // they removed the destination: ask too (my route to it stays either way)
            proposal = Proposal(null, o.optString("n", members[id]?.name ?: "Driver"), dt)
            main.post { proposed.fire(); changed.fire() }
        }
        if (id == myId) { soon(); return }
        if (o.optString("t") == "bye") { members[id]?.state = "left"; members.remove(id); main.post { changed.fire() }; return }
        val m = members.getOrPut(id) { Member(id, o.optString("n", "Driver")) }
        // the same update comes over two brokers and ntfy, and late ones can overtake: newest wins
        val ts = o.optLong("ts", 0)
        if (ts != 0L && ts <= m.lastTs) return
        if (ts != 0L) m.lastTs = ts
        val nowMs = System.currentTimeMillis()
        m.clockSample(ts, nowMs)
        // a quick message ("Need fuel"): shown for a few seconds and said aloud while driving
        if (o.optString("t") == "msg") { heard(m, o.optString("k"), ts + m.skew); return }
        m.name = o.optString("n", m.name)
        val fc = o.optString("fc")
        if (fc.isNotBlank() && fc != m.fc) {
            m.fid = o.optString("fid"); m.fc = fc
            // remembered for "Drove with you" (Add a friend), once per person
            if (Friends.ready) Friends.core.droveWith(m.fid, m.name, fc, destination?.name.orEmpty())
            main.post { changed.fire() }
        }
        if (o.has("lat")) {
            val now = System.currentTimeMillis()
            m.pos = LatLng(o.getDouble("lat"), o.getDouble("lon"))
            // measured when they sent it, on my clock (their clock corrected by the measured difference)
            m.fixAt = (ts + m.skew).takeIf { it in (now - 10_000)..now } ?: now
        }
        m.heading = o.optDouble("h", m.heading.toDouble()).toFloat()
        m.kmh = o.optInt("s", m.kmh)
        if (o.has("eta")) m.eta = o.getLong("eta")
        if (o.has("left")) m.left = o.getDouble("left")
        if (o.has("tot")) m.total = maxOf(m.total, o.getDouble("tot"))
        val st = o.optString("st", m.state)
        if (st == "arrived" && m.state != "arrived") m.arrivedAt = o.optLong("at", o.optLong("ts"))
        m.state = st
        m.ownDest = if (st == "own" && o.has("olat")) Place(o.optString("on", "Their destination"), "", LatLng(o.getDouble("olat"), o.getDouble("olon"))) else null
        m.ready = st == "ready" && o.optBoolean("rd", false)
        m.readyAt = if (m.ready) o.optLong("ra", ts) + m.skew else 0 // on my clock: everyone counts down to the same moment
        m.along = o.optBoolean("al", false)
        m.sharing = o.optBoolean("sh", false)
        m.lv = o.optString("lv")
        m.seen = nowMs // when it reached me (my clock)
        soon()
    }

    /**
     * Updates come every second from everyone (twice: two brokers): the screens are told at
     * most 4 times a second, not once per message. (The map's arrows glide on their own.)
     */
    @Volatile private var firePending = false
    private val fireNow = Runnable { firePending = false; checkGo(); changed.fire() }
    private fun soon() {
        if (firePending) return
        firePending = true
        main.postDelayed(fireNow, 250)
    }

    /** A friend as a place to drive to (where they are now). */
    fun placeOf(m: Member): Place {
        val at = m.shown ?: m.pos ?: LatLng(0.0, 0.0)
        val info = listOfNotNull(
            if (m.state == "arrived") "arrived" else if (m.stale) "no signal" else "${m.kmh} km/h",
            destination?.let { if (m.state == "driving" && m.eta > 0) "arrives ${NavEngine.clock(m.eta)}" else null },
        ).joinToString(" · ")
        return Place(m.name, "In your convoy · $info", at, "👥")
    }

    // ------------------------------------------------------------- the race start

    /** I pressed "I'm ready". */
    @Volatile var meReady = false
        private set
    @Volatile private var meReadyAt = 0L
    /** When everyone is ready: the moment of GO (the screens count 3-2-1 down to it); 0 = none. */
    @Volatile var goAt = 0L
        private set
    private var startedFor = 0L
    private var saidAt = -1L
    private val said = HashSet<Long>()

    /** I drive along: the same route, not in the race. */
    @Volatile var meAlong = false
        private set

    /** I only share my location: not on the trip. */
    @Volatile var meSharing = false
        private set

    /** The racers still at the start: not driving yet, connected, and racing (not driving along). */
    fun lobby(): List<Member> = members.values.filter { it.state == "ready" && !it.stale && !it.along && !it.sharing }

    /** Everyone else still at the start, racing or driving along (not those only sharing). */
    fun atStart(): List<Member> = members.values.filter { it.state == "ready" && !it.stale && !it.sharing }

    /** The others only sharing their location (they're on my phone's map, nowhere else on the trip). */
    fun sharers(): List<Member> = members.values.filter { it.sharing && it.state != "left" }

    /** Someone racing is on their way already (the start is over: just join the route). */
    val underway: Boolean get() = members.values.any { it.state == "driving" && !it.stale && !it.along && !it.sharing }

    /** Just share my location (or stop: back to Race). Not during the countdown. */
    fun setSharing(on: Boolean) {
        if (code == null || counting || on == meSharing) return
        meSharing = on
        if (on) { meReady = false; meReadyAt = 0; meAlong = false }
        publish(hello(myState()))
        checkGo(); changed.fire()
    }

    /** Race (false) or drive along (true): free to switch until the countdown starts. */
    fun setAlong(on: Boolean) {
        if (code == null || NavEngine.active || counting || (on == meAlong && !meSharing)) return
        meAlong = on; meSharing = false
        if (on) { meReady = false; meReadyAt = 0 }
        publish(hello(myState()))
        checkGo(); changed.fire()
    }

    /** Counting down to GO right now (or showing GO). */
    val counting: Boolean get() = goAt != 0L && System.currentTimeMillis() < goAt + 1500

    fun setReady(on: Boolean) {
        if (code == null || NavEngine.active) return
        if (on) { meAlong = false; meSharing = false } // ready = racing
        meReady = on; meReadyAt = if (on) System.currentTimeMillis() else 0
        publish(hello("ready"))
        checkGo(); changed.fire()
    }

    /**
     * Everyone at the start ready (me too, and at least one other)? Then GO is 4 s after the last
     * one pressed: every phone works out the same moment from the same messages, so all start together.
     */
    private fun checkGo() {
        val now = System.currentTimeMillis()
        val l = lobby()
        val all = code != null && destination != null && meReady && !meAlong && !meSharing && !NavEngine.active && l.isNotEmpty() && l.all { it.ready }
        if (!all) {
            if (goAt != 0L && now < goAt) { goAt = 0; main.removeCallbacks(countdown); changed.fire() } // someone changed their mind
            return
        }
        val at = maxOf(meReadyAt, l.maxOf { it.readyAt }) + 4000
        if (goAt == 0L || now >= goAt + 1500) goAt = maxOf(at, now + 1500) // (a late message: still a short countdown)
        else if (at > goAt + 500) goAt = at // someone pressed again
        main.removeCallbacks(countdown); main.post(countdown)
    }

    private val countdown: Runnable = object : Runnable {
        override fun run() {
            val g = goAt; if (g == 0L) return
            val now = System.currentTimeMillis()
            val n = Math.ceil((g - now) / 1000.0).toLong()
            if (n != saidAt) { saidAt = n; changed.fire() } // (the map animates the numbers by itself)
            if (n in 0..3 && n == saidAt && !said.contains(n)) {
                said += n
                val gr = Voice.lang == Phrases.GREEK
                Voice.say(when (n) { 3L -> if (gr) "Τρία" else "Three"; 2L -> if (gr) "Δύο" else "Two"; 1L -> if (gr) "Ένα" else "One"; else -> if (gr) "Πάμε!" else "Go!" })
            }
            if (now >= g && startedFor != g) {
                startedFor = g
                val dest = destination
                if (dest != null && code != null && !NavEngine.active) { NavEngine.start(appContext, dest); MapState.recenter() }
            }
            if (now < g + 1500) main.postDelayed(this, 100) else { saidAt = -1; said.clear(); changed.fire() }
        }
    }

    // ------------------------------------------------------------- tests

    internal fun debugHost(on: Boolean) { host = on }
    internal fun debugStart(dest: Place, code: String = "k7p2qx") { this.code = code; destination = dest; members.clear(); routeTotal = -1.0 }

    internal fun debugMember(name: String, pos: LatLng, kmh: Int, eta: Long, left: Double, total: Double, state: String, heading: Float = 0f) {
        val m = Member("t-$name", name, pos, heading, kmh, eta, left, total, state, 0, System.currentTimeMillis())
        m.fixAt = System.currentTimeMillis()
        members[m.id] = m
        changed.fire()
    }

    // ------------------------------------------------------------- for the screens

    /** Me plus the others, furthest along first. */
    data class Row(val name: String, val color: Int, val me: Boolean, val kmh: Int, val eta: Long, val progress: Double,
                   val left: Double, val state: String, val stale: Boolean, val arrivedAt: Long, val ready: Boolean = false,
                   val id: String = "", val along: Boolean = false)

    @Volatile private var rowsCache: Pair<Long, List<Row>>? = null

    fun rows(myColor: Int): List<Row> {
        val now = System.currentTimeMillis()
        rowsCache?.let { (at, r) -> if (now - at < 500) return r }
        return computeRows(myColor).also { rowsCache = now to it }
    }

    private fun computeRows(myColor: Int): List<Row> {
        val mine = onConvoyRoute
        val me = Row("You", colorOf(myId), true, Math.round(LocationTracker.speedMps * 3.6f),
            if (mine) System.currentTimeMillis() + (NavEngine.remainingSeconds * 1000).toLong() else 0,
            if (mine && NavEngine.arrived) 1.0 else if (mine && routeTotal > 0) (1 - NavEngine.remainingDistance / routeTotal).coerceIn(0.0, 1.0) else 0.0,
            if (mine) NavEngine.remainingDistance else -1.0,
            myState(), false,
            if (NavEngine.arrived) NavEngine.arrivedAtMs else 0, meReady, myId, meAlong)
        val others = members.values.filter { !it.sharing }.map { Row(it.name, it.color, false, it.kmh, it.eta, it.progress, it.left, it.state, it.stale, it.arrivedAt, it.ready, it.id, it.along) }
        // the racers first, furthest along first; anyone driving along after them
        return (if (meSharing) others else others + me).sortedWith(compareBy<Row> { it.along }.thenByDescending { it.progress }.thenBy { it.eta })
    }

    /** "1.3 km ahead" / "0.8 km behind" relative to me. */
    fun relative(r: Row): String {
        if (r.state == "arrived") return "arrived"
        if (r.stale) return "no signal"
        if (r.along) return if (r.state == "ready") "driving along · not started" else "driving along"
        if (r.state == "ready") return (if (r.me && lobby().isNotEmpty()) "racing · " else "") + when {
            r.ready -> "ready ✓"
            underway -> "not started" // the others are on their way
            else -> "not ready yet"
        }
        if (r.state == "own") return if (r.me) "on your own route" else "on their own route"
        if (r.me) return if (onConvoyRoute) NavEngine.formatDistance(NavEngine.remainingDistance) + " to go" else "" // the name says "You" already
        if (!onConvoyRoute) return NavEngine.formatDistance(r.left.coerceAtLeast(0.0)) + " to go"
        members[r.id]?.lostSecs?.takeIf { it > 0 }?.let { return lostText(it) }
        if (r.kmh == 0) return "stopped"
        val d = gapTo(r) ?: return NavEngine.formatDistance(r.left.coerceAtLeast(0.0)) + " to go"
        return if (d >= 0) "${NavEngine.formatDistance(d)} ahead" else "${NavEngine.formatDistance(-d)} behind"
    }

    /** "Signal lost 12 s" / "Signal lost 2 min". */
    fun lostText(secs: Int): String = if (secs < 60) "signal lost $secs s" else "signal lost ${secs / 60} min"

    /**
     * How far they are ahead of me (negative: behind), measured along the same road the same way on
     * every phone: where they are now (moved on from their last update at their speed) and where I
     * am, both on the convoy route. Their phone does the same the other way round, so the two
     * numbers match (850 m ahead here = 850 m behind there). Off the route: from what's left to go.
     */
    fun gapTo(r: Row): Double? {
        val m = members[r.id] ?: return null
        val now = System.currentTimeMillis()
        val p = m.predicted(now) ?: return null
        val theirs = NavEngine.routeDistanceOf(p)
        if (theirs != null) return theirs - NavEngine.along
        return if (r.left >= 0) NavEngine.remainingDistance - r.left else null
    }

    // ------------------------------------------------------------- quick messages

    /** The quick messages: one tap, no typing (key, English, Greek). */
    val quick = listOf(
        Triple("fuel", "Need fuel", "Χρειάζομαι καύσιμο"),
        Triple("stop", "Stopping soon", "Σταματάω σε λίγο"),
        Triple("ahead", "Go ahead", "Προχωρήστε"),
        Triple("wait", "Wait for me", "Περιμένετε με"),
    )

    /** The newest quick message: who, which, when (my clock). */
    @Volatile var lastMessage: Triple<String, String, Long>? = null
        private set

    fun messageText(key: String): String = quick.firstOrNull { it.first == key }?.let { if (L.greek) it.third else it.second } ?: key

    /** Sends a quick message to everyone in the convoy. */
    fun send(key: String) {
        if (code == null || quick.none { it.first == key }) return
        publish(JSONObject().put("t", "msg").put("id", myId).put("n", myName.ifBlank { "Driver" }).put("k", key).put("ts", System.currentTimeMillis()))
        lastMessage = Triple(L.t("You"), key, System.currentTimeMillis())
        changed.fire()
    }

    private fun heard(m: Member, key: String, at: Long) {
        if (quick.none { it.first == key }) return
        if (System.currentTimeMillis() - at > 60_000) return // an old one (the backup line, late)
        lastMessage = Triple(m.name, key, System.currentTimeMillis())
        main.post {
            changed.fire()
            // said aloud while driving (and always in the car): eyes stay on the road
            if (NavEngine.active || LocationTracker.speedMps > 3f) {
                val gr = Voice.lang == Phrases.GREEK
                Voice.say("${m.name}: " + quick.first { it.first == key }.let { if (gr) it.third else it.second })
            }
        }
    }

    /** The quick message to show now (8 s after it came), or null. */
    fun showMessage(): Triple<String, String, Long>? = lastMessage?.takeIf { System.currentTimeMillis() - it.third < 8_000 }

    internal fun debugMessage(name: String, key: String) { lastMessage = Triple(name, key, System.currentTimeMillis()) }

    /** Finishing order once everyone (or me) has arrived: (place, total). */
    fun myPlace(): Pair<Int, Int>? {
        if (!active || meAlong || meSharing) return null
        // only the racers are ranked
        val done = rows(0).filter { it.state == "arrived" && !it.along }.sortedBy { it.arrivedAt }
        val i = done.indexOfFirst { it.me }
        return if (i < 0) null else (i + 1) to (members.values.count { !it.along && !it.sharing } + 1)
    }
}
