package gr.gamemap

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Friends on this phone: [FriendCore] with the phone's storage, ntfy for messages, MQTT for
 * "online", notifications, and a check about every 15 minutes while the app is closed (Android
 * runs it together with other apps' jobs: next to no battery).
 *
 * While GameRoute is open, navigating or in a convoy, new messages come within ~20 s.
 */
object Friends {
    const val PAGE = "https://vyto-line.github.io/GameRoute/friend/"
    private const val CHANNEL = "friends"
    private const val JOB = 4711

    private lateinit var app: Context
    lateinit var core: FriendCore
        private set
    val ready get() = ::core.isInitialized
    val changed = Signal()
    /** A new invite or request while the app is open (MainActivity asks, if it's safe to). */
    val arrived = Signal()
    private val main = Handler(Looper.getMainLooper())

    fun init(ctx: Context) {
        if (ready) return
        app = ctx.applicationContext
        val prefs = app.getSharedPreferences("friends", Context.MODE_PRIVATE)
        core = FriendCore(object : FriendCore.Store {
            override fun get(key: String) = prefs.getString(key, null)
            override fun put(key: String, value: String?) { prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply() }
        }, Ntfy)
        core.name = Convoy.myName
        Convoy.changed += {
            core.name = Convoy.myName
            // a code made before I had a name ("DRIVER-4821"): now it gets my name (friends stay)
            if (core.started && Convoy.myName.isNotBlank() && core.code.startsWith("DRIVER-") && !FriendCore.latin(Convoy.myName).uppercase().startsWith("DRIVER")) bg { core.newCode() }
        }
        if (core.started) schedule()
    }

    /** Friends opened for the first time (or again): make my code, and start checking. */
    fun start() {
        if (!ready) return
        core.code; schedule(); ensureLoop(); if (visible) { Presence.start(); Live.start() }
    }

    fun link(code: String = core.code) = PAGE + "#" + code
    fun shareText() = "Add me as a friend on GameRoute: ${core.code}\n${link()}"

    // ------------------------------------------------------------------ checking for news

    @Volatile private var onPhone = false
    @Volatile private var inCar = false
    /** GameRoute is on the phone's screen or on the car's: check often, show me online. */
    private val visible get() = onPhone || inCar

    @Volatile private var loop: Thread? = null

    /** The app is on screen (or not): check often, and show me online. */
    fun setVisible(v: Boolean) {
        onPhone = v
        if (v && ready && core.started) { main.removeCallbacks(statusCheck); main.post(statusCheck) }
        if (visible) { ensureLoop(); kick(); Presence.start(); Live.start() } else { Presence.stop(); Live.stop() }
    }

    /** Android Auto opened (or closed): the car shows invites itself, so it counts as on screen. */
    fun setInCar(v: Boolean) {
        if (!ready) return
        inCar = v
        if (visible) { ensureLoop(); kick(); Presence.start(); Live.start() } else { Presence.stop(); Live.stop() }
    }

    /** Check right now (the app came back, Friends opened, a request sent): no waiting for the next round. */
    fun kick() { ensureLoop(); loop?.interrupt() }

    /** Checks again every ~20 s while the app is open, navigating or in a convoy. */
    fun ensureLoop() {
        if (!ready || !core.started || loop?.isAlive == true) return
        loop = Thread {
            while (visible || Convoy.active || NavEngine.active) {
                checkNow()
                // a request of mine waiting for an answer, and the app open: look more often
                val waiting = visible && core.pending.any { it.status == "waiting" && System.currentTimeMillis() - it.at < 3600_000L }
                try { Thread.sleep(if (waiting) 8_000 else 20_000) } catch (e: InterruptedException) { /* kicked: check now */ }
            }
            loop = null
        }.apply { isDaemon = true; priority = Thread.MIN_PRIORITY; start() }
    }

    private val lock = Any()

    /** One check (on a background thread). Returns true if anything changed. */
    fun checkNow(fromJob: Boolean = false): Boolean {
        if (!ready || !core.started) return false
        val news = synchronized(lock) { try { core.check() } catch (e: Exception) { return false } }
        if (!news.anyChange) return false
        main.post {
            changed.fire()
            if (news.requests.isNotEmpty() || news.invites.isNotEmpty() || news.accepted.isNotEmpty() || news.declined.isNotEmpty()) arrived.fire()
            // on screen (phone or car): the small invite card
            if (visible && core.allowInvites) for (i in news.invites) InvitePop.offer(i)
            Presence.refresh()
        }
        // notifications: always from the background job; in the app only while it's not on screen
        if (fromJob || !visible) {
            for (r in news.requests) notifyRequest(r)
            for (i in news.invites) if (core.allowInvites) notifyInvite(i)
            for (f in news.accepted) notifyAccepted(f)
            for (x in news.declined) post(x.who.hashCode(), L.t("${x.who} declined your friend request"), "", open("friends", "open", x.who.hashCode()))
        }
        return true
    }

    // ------------------------------------------------------------------ actions (any thread)

    private fun bg(f: () -> Unit) = Thread { try { synchronized(lock) { f() } } catch (_: Exception) {}; main.post { changed.fire() } }.start()

    fun request(code: String, name: String = ""): Boolean {
        val c = FriendCore.norm(code)
        if (!FriendCore.valid(c) || c == FriendCore.norm(core.code)) return false
        bg { core.request(c, name) }
        main.postDelayed({ kick() }, 1500)
        return true
    }
    fun accept(r: FriendCore.Request) = bg { core.accept(r); main.post { Presence.refresh() } }
    fun decline(r: FriendCore.Request) = bg { core.decline(r) }
    fun remove(f: FriendCore.Friend) = bg { core.remove(f.fid); main.post { Presence.refresh() } }
    fun block(fid: String, name: String) = bg { core.block(fid, name); main.post { Presence.refresh() } }
    fun unblock(fid: String) = bg { core.unblock(fid) }
    fun newCode() = bg { core.newCode(); Live.reconnect() }

    /** Invites [f] to the convoy I'm in. */
    fun invite(f: FriendCore.Friend) {
        val c = Convoy.code ?: return
        val dest = Convoy.destination?.name.orEmpty()
        bg { core.invite(f, c, dest) }
    }

    fun answer(convoy: String, status: String) {
        if (status != "seen") cancelNotification(convoy)
        bg { core.answer(convoy, status) }
    }

    /** The app showed these invites: their senders see "Seen". */
    fun markSeen(list: List<FriendCore.Invite>) { for (i in list) if (!core.seen(i.convoy)) answer(i.convoy, "seen") }

    /** I left a convoy: my invites to it go away on my friends' phones. */
    fun leftConvoy(code: String) = bg { core.cancelInvites(code) }

    fun setShowOnline(v: Boolean) { core.showOnline = v; Presence.restart(); changed.fire() }

    /**
     * Sets Online / Do not disturb / Invisible, back to Online after [untilMs] (0 = never). Coming
     * back from Do not disturb shows the invites that waited (and tells their senders "Seen" only
     * once they're actually shown).
     */
    fun setStatus(status: String, untilMs: Long = 0L) {
        if (!ready) return
        val was = core.status
        core.status = status; core.statusUntil = untilMs
        lastStatus = status
        Presence.restart()
        main.removeCallbacks(statusCheck)
        if (untilMs > 0) main.postDelayed(statusCheck, (untilMs - System.currentTimeMillis()).coerceAtLeast(1000))
        if (was == FriendCore.DND && status == FriendCore.ONLINE) heldInvites()
        changed.fire()
    }

    /** "Back to Online after" came: the status is read again (the core switches back by itself). */
    private val statusCheck = Runnable {
        if (!ready) return@Runnable
        val before = lastStatus
        val now = core.status
        if (before != now) { Presence.restart(); if (before == FriendCore.DND) heldInvites(); changed.fire() }
        lastStatus = now
    }
    @Volatile private var lastStatus = FriendCore.ONLINE

    /** The invites that waited during Do not disturb: a notification for each, now. */
    private fun heldInvites() {
        if (!visible) for (i in core.invites) if (core.allowInvites) notifyInvite(i)
        main.post { arrived.fire() }
    }
    fun setAllowInvites(v: Boolean) { core.allowInvites = v; changed.fire() }

    // ------------------------------------------------------------------ notifications

    private fun channel(): NotificationManager {
        val nm = app.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Friends", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Friend requests and convoy invites"
            })
        return nm
    }

    private fun open(extra: String, value: String, req: Int): PendingIntent =
        PendingIntent.getActivity(app, req, Intent(app, MainActivity::class.java).putExtra(extra, value)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun notifyRequest(r: FriendCore.Request) = post(r.fid.hashCode(),
        L.t("${r.name} wants to be friends"), L.t("Tap to accept or decline"), open("friends", "open", r.fid.hashCode()))

    private fun notifyAccepted(f: FriendCore.Friend) = post(f.fid.hashCode(),
        L.t("${f.name} accepted your friend request"), L.t("You can invite them to convoys now"), open("friends", "open", f.fid.hashCode()))

    private fun notifyInvite(i: FriendCore.Invite) {
        val id = i.convoy.hashCode()
        val no = PendingIntent.getBroadcast(app, id, Intent(app, FriendAction::class.java).putExtra("convoy", i.convoy).putExtra("st", "no"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val text = (if (i.dest.isNotBlank()) i.dest else L.t("A convoy")) + (if (i.count > 1) " · " + L.t("${i.count} invites") else "")
        post(id, L.t("${i.from} invites you to a convoy"), text, open("friend_join", i.convoy, id + 1)) {
            it.addAction(android.app.Notification.Action.Builder(null, L.t("Join"), open("friend_join", i.convoy, id + 2)).build())
                .addAction(android.app.Notification.Action.Builder(null, L.t("Not now"), no).build())
        }
    }

    private fun post(id: Int, title: String, text: String, tap: PendingIntent, more: (android.app.Notification.Builder) -> Unit = {}) {
        try {
            val b = android.app.Notification.Builder(app, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_directions).setContentTitle(title).setContentText(text)
                .setContentIntent(tap).setAutoCancel(true).setCategory(android.app.Notification.CATEGORY_SOCIAL)
            more(b)
            channel().notify("friends", id, b.build())
        } catch (_: Exception) {} // (no permission to notify: the app shows it when opened)
    }

    fun cancelNotification(convoy: String) {
        try { app.getSystemService(NotificationManager::class.java).cancel("friends", convoy.hashCode()) } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------ the 15-minute check

    private fun schedule() {
        try {
            val js = app.getSystemService(JobScheduler::class.java)
            if (js.getPendingJob(JOB) != null) return
            js.schedule(JobInfo.Builder(JOB, ComponentName(app, FriendJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(15 * 60_000L)
                .setPersisted(true)
                .build())
        } catch (_: Exception) {}
    }

    /** Runs about every 15 minutes, also with the app closed: checks for invites and requests. */
    class FriendJob : JobService() {
        override fun onStartJob(params: JobParameters): Boolean {
            Thread {
                // (the app's own start-up has run already: this is its process)
                try { if (!ready) init(applicationContext); checkNow(fromJob = true) } catch (_: Exception) {}
                jobFinished(params, false)
            }.start()
            return true
        }
        override fun onStopJob(params: JobParameters) = true
    }

    /** "Not now" on an invite notification: answers without opening the app. */
    class FriendAction : BroadcastReceiver() {
        override fun onReceive(ctx: Context, i: Intent) {
            val cv = i.getStringExtra("convoy") ?: return
            if (!ready) init(ctx.applicationContext)
            val done = goAsync()
            Thread { try { core.answer(cv, i.getStringExtra("st") ?: "no") } catch (_: Exception) {}; done.finish() }.start()
            cancelNotification(cv)
        }
    }

    // ------------------------------------------------------------------ live

    /**
     * While GameRoute is on screen: one open connection to ntfy for my inbox and my code, so a
     * friend request (or an answer, or an invite) shows up in a second or two, not at the next
     * check. Any message just starts a check right away (the check reads and decrypts it).
     */
    object Live {
        @Volatile private var thread: Thread? = null
        @Volatile private var conn: HttpURLConnection? = null
        @Volatile private var wanted = false

        fun start() {
            if (!ready || !core.started) return
            // Settings › Performance › Live friend updates off: the 15-minute check still brings requests
            if (!Perf.liveFriends) { stop(); return }
            wanted = true
            if (thread?.isAlive == true) return
            thread = Thread {
                var wait = 2_000L
                while (wanted) {
                    val topics = try { core.myTopics() } catch (e: Exception) { break }
                    try {
                        val c = URL("https://ntfy.sh/${topics.joinToString(",")}/json").openConnection() as HttpURLConnection
                        c.connectTimeout = 10_000; c.readTimeout = 90_000 // ntfy sends a keepalive every 45 s
                        c.setRequestProperty("User-Agent", "GameRoute/1.0 (Android)")
                        conn = c
                        c.inputStream.bufferedReader().use { r ->
                            wait = 2_000L
                            while (wanted) {
                                val line = r.readLine() ?: break
                                if (line.contains("\"event\":\"message\"")) { kick(); main.postDelayed({ kick() }, 1500); main.postDelayed({ kick() }, 4000) }
                                // a new code: listen on the new topic
                                if (core.myTopics() != topics) break
                            }
                        }
                    } catch (e: Exception) {
                        if (!wanted) break
                        try { Thread.sleep(wait) } catch (_: InterruptedException) {}
                        wait = (wait * 2).coerceAtMost(60_000L)
                    } finally {
                        try { conn?.disconnect() } catch (_: Exception) {}
                        conn = null
                    }
                }
                thread = null
            }.apply { isDaemon = true; name = "friends-live"; start() }
        }

        /** Listen again (a new code means a new topic). */
        fun reconnect() { val c = conn; if (c != null) Thread { try { c.disconnect() } catch (_: Exception) {} }.start() }

        fun stop() {
            wanted = false
            val c = conn
            if (c != null) Thread { try { c.disconnect() } catch (_: Exception) {} }.start()
            thread?.interrupt()
        }
    }

    // ------------------------------------------------------------------ ntfy

    internal object Ntfy : FriendCore.Wire {
        override fun post(topic: String, body: String) {
            Http.post("https://ntfy.sh/$topic", body, "text/plain", timeout = 10_000)
        }

        override fun poll(topics: List<String>, since: String): List<Triple<String, String, String>> {
            val c = URL("https://ntfy.sh/${topics.joinToString(",")}/json?poll=1&since=$since").openConnection() as HttpURLConnection
            c.connectTimeout = 10_000; c.readTimeout = 20_000
            c.setRequestProperty("User-Agent", "GameRoute/1.0 (Android)")
            val out = ArrayList<Triple<String, String, String>>()
            c.inputStream.bufferedReader().use { r ->
                r.lineSequence().forEach { line ->
                    val m = try { JSONObject(line) } catch (e: Exception) { return@forEach }
                    if (m.optString("event") == "message") out += Triple(m.getString("id"), m.optString("topic"), m.optString("message"))
                }
            }
            return out
        }
    }

    // ------------------------------------------------------------------ online

    /**
     * "Online": while the app is on screen, a small encrypted "I'm here" kept by an MQTT broker on
     * my presence topic (every 2 min; the broker posts "gone" if the phone drops off). Friends'
     * topics are watched while the app is open. Only whether the app is open: never where.
     */
    /** Friends' level and title, for those who chose to show it ("Lv 12 · Night Rider"). */
    val levels = java.util.concurrent.ConcurrentHashMap<String, String>()
    fun levelOf(fid: String): String = levels[fid].orEmpty()

    /** Friends' avatars ("fox:ea580c"), as their phones last sent them (kept between runs). */
    val avatars = java.util.concurrent.ConcurrentHashMap<String, String>()
    private fun saveAvatars() {
        if (!::app.isInitialized) return
        val o = JSONObject(); for ((k, v) in avatars) o.put(k, v)
        app.getSharedPreferences("friends", Context.MODE_PRIVATE).edit().putString("avatars", o.toString()).apply()
    }
    private fun loadAvatars() {
        if (!::app.isInitialized) return
        val o = try { JSONObject(app.getSharedPreferences("friends", Context.MODE_PRIVATE).getString("avatars", null) ?: "{}") } catch (e: Exception) { JSONObject() }
        for (k in o.keys()) avatars.putIfAbsent(k, o.optString(k))
    }

    /**
     * What friends are up to, from their "I'm here": driving now, in a convoy (never where or to
     * where), and when they were last online. Invisible friends send nothing, so show nothing.
     */
    class Activity(val driving: Boolean, val convoy: Boolean, val lastOn: Long)
    val activity = java.util.concurrent.ConcurrentHashMap<String, Activity>()

    /** "Driving now", "In a convoy", "Online now", "Last seen 12 min ago", "Not online". */
    fun activityText(fid: String, now: Long = System.currentTimeMillis()): String {
        val a = activity[fid]
        if (ready && core.isOnline(fid)) return when {
            core.isBusy(fid) -> L.t("Do not disturb · invites wait")
            a?.convoy == true && a.driving -> L.t("Driving in a convoy")
            a?.convoy == true -> L.t("In a convoy")
            a?.driving == true -> L.t("Driving now")
            else -> L.t("Online now")
        }
        val last = a?.lastOn ?: 0L
        if (last <= 0L) return L.t("Not online")
        val mins = (now - last) / 60_000
        return when {
            mins < 1 -> L.t("Last seen just now")
            mins < 60 -> L.t("Last seen $mins min ago")
            mins < 24 * 60 -> L.t("Last seen ${mins / 60} h ago")
            else -> L.t("Last seen ${mins / (24 * 60)} days ago")
        }
    }

    /** Remembered between runs: "last seen" still works after a restart. */
    private fun saveLastSeen() {
        if (!::app.isInitialized) return
        val o = JSONObject(); for ((k, v) in activity) if (v.lastOn > 0) o.put(k, v.lastOn)
        app.getSharedPreferences("friends", Context.MODE_PRIVATE).edit().putString("lastOn", o.toString()).apply()
    }

    internal fun loadLastSeen() {
        if (!::app.isInitialized) return
        val o = try { JSONObject(app.getSharedPreferences("friends", Context.MODE_PRIVATE).getString("lastOn", null) ?: "{}") } catch (e: Exception) { JSONObject() }
        for (k in o.keys()) if (activity[k] == null) activity[k] = Activity(false, false, o.optLong(k))
    }

    /** For tests: a friend's activity as it would come with their "I'm here". */
    internal fun activityForTest(fid: String, on: Boolean, driving: Boolean, convoy: Boolean, at: Long) = took(fid, on, driving, convoy, at)

    private fun took(fid: String, on: Boolean, driving: Boolean, convoy: Boolean, at: Long) {
        val before = activity[fid]?.lastOn ?: 0L
        // their phone's time of the message (online: the last beat; going away: when they left)
        val stamp = at.coerceIn(0L, System.currentTimeMillis())
        activity[fid] = Activity(on && driving, on && convoy, maxOf(before, if (on && stamp == 0L) System.currentTimeMillis() else stamp))
        saveLastSeen()
    }

    object Presence {
        private const val BROKER = "ssl://broker.emqx.io:8883"
        private val io = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "friends-online").apply { isDaemon = true } }
        @Volatile private var client: MqttClient? = null
        @Volatile private var watching: Map<String, FriendCore.Friend> = emptyMap()
        @Volatile private var on = false

        private fun me(online: Boolean): MqttMessage {
            val o = JSONObject().put("on", online).put("at", System.currentTimeMillis())
            if (online && core.status == FriendCore.DND) o.put("dnd", true)
            if (Xp.showToFriends) o.put("lv", Xp.badge()) // only if I chose to show it
            Avatars.myCode().takeIf { it.isNotBlank() }?.let { o.put("av", it) } // a short code, never a picture
            // what I'm doing (never where): driving, in a convoy
            if (online && NavEngine.active && !NavEngine.arrived) o.put("dr", true)
            if (online && Convoy.active) o.put("cv", true)
            val body = o.toString()
            return MqttMessage(Seal.seal(core.presenceKey(), body).toByteArray()).apply { qos = 0; isRetained = true }
        }

        private val beat = object : Runnable {
            override fun run() { if (!on) return; io.execute { publishMe() }; main.postDelayed(this, 120_000) }
        }

        private var myKey = ""
        /** Driving started or stopped, a convoy joined or left: tell friends now, not at the next beat. */
        private val mine: () -> Unit = {
            val k = "${NavEngine.active && !NavEngine.arrived}${Convoy.active}"
            if (k != myKey) { myKey = k; if (on) io.execute { publishMe() } }
        }
        @Volatile private var hooked = false

        fun start() {
            if (!ready || !core.started || on) return
            on = true
            if (!hooked) { hooked = true; NavEngine.changed += mine; Convoy.changed += mine; loadLastSeen(); loadAvatars() }
            io.execute {
                if (!on) return@execute
                try {
                    val c = MqttClient(BROKER, "grf-" + java.util.UUID.randomUUID().toString().take(12), MemoryPersistence())
                    c.setCallback(object : MqttCallbackExtended {
                        override fun connectComplete(reconnect: Boolean, serverURI: String?) { if (reconnect) io.execute { watch(force = true); publishMe() } }
                        override fun connectionLost(cause: Throwable?) {}
                        override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                        override fun messageArrived(topic: String, m: MqttMessage) {
                            val f = watching[topic] ?: return
                            val o = Seal.open(core.presenceKey(f.secret), String(m.payload))?.let { JSONObject(it) }
                            core.setOnline(f.fid, if (o != null && o.optBoolean("on")) o.optLong("at") else null, o?.optBoolean("dnd") == true)
                            levels[f.fid] = o?.optString("lv").orEmpty()
                            o?.optString("av")?.takeIf { it.isNotBlank() }?.let { av -> if (avatars[f.fid] != av) { avatars[f.fid] = av; saveAvatars() } }
                            val on = o != null && o.optBoolean("on")
                            took(f.fid, on, o?.optBoolean("dr") == true, o?.optBoolean("cv") == true, o?.optLong("at") ?: 0L)
                            main.post { changed.fire() }
                        }
                    })
                    val opts = MqttConnectOptions().apply {
                        isCleanSession = true; isAutomaticReconnect = true; connectionTimeout = 10; keepAliveInterval = 60
                        if (core.showOnline) setWill(core.presenceTopic(), me(false).payload, 0, true)
                    }
                    c.connect(opts)
                    client = c
                    watching = emptyMap(); watch(force = true)
                    publishMe()
                } catch (e: Exception) { client = null }
            }
            main.removeCallbacks(beat); main.postDelayed(beat, 120_000)
        }

        private fun publishMe() {
            val c = client ?: return
            try { if (c.isConnected) c.publish(core.presenceTopic(), me(on && core.showOnline)) } catch (_: Exception) {}
        }

        /** Watches the friends' topics (again, when the list changed). */
        fun refresh() { if (on) io.execute { watch(force = false); publishMe() } }

        private fun watch(force: Boolean) {
            val c = client ?: return
            val want = core.friends.filter { it.secret != FriendCore.TEST_SECRET }.associateBy { core.presenceTopic(it.secret) }
            if (!force && want.keys == watching.keys) return
            val old = watching.keys
            try {
                (old - want.keys).forEach { c.unsubscribe(it) }
                watching = want
                (if (force) want.keys else want.keys - old).forEach { c.subscribe(it, 0) }
            } catch (_: Exception) {}
        }

        fun stop() {
            if (!on) return
            on = false
            main.removeCallbacks(beat)
            io.execute {
                val c = client ?: return@execute
                client = null
                try { if (c.isConnected) c.publish(core.presenceTopic(), me(false)) } catch (_: Exception) {}
                try { c.disconnect(2000) } catch (_: Exception) {}
                try { c.close() } catch (_: Exception) {}
            }
        }

        fun restart() { if (on) { stop(); start() } }
    }
}
