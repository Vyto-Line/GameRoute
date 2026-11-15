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
        Convoy.changed += { core.name = Convoy.myName }
        if (core.started) schedule()
    }

    /** Friends opened for the first time (or again): make my code, and start checking. */
    fun start() {
        if (!ready) return
        core.code; schedule(); ensureLoop(); if (visible) Presence.start()
    }

    fun link(code: String = core.code) = PAGE + "#" + code
    fun shareText() = "Add me as a friend on GameRoute: ${core.code}\n${link()}"

    // ------------------------------------------------------------------ checking for news

    @Volatile private var visible = false
    @Volatile private var loop: Thread? = null

    /** The app is on screen (or not): check often, and show me online. */
    fun setVisible(v: Boolean) {
        visible = v
        if (v) { ensureLoop(); Presence.start() } else Presence.stop()
    }

    /** Checks again every ~20 s while the app is open, navigating or in a convoy. */
    fun ensureLoop() {
        if (!ready || !core.started || loop?.isAlive == true) return
        loop = Thread {
            while (visible || Convoy.active || NavEngine.active) {
                checkNow()
                try { Thread.sleep(20_000) } catch (e: InterruptedException) { break }
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
            if (news.requests.isNotEmpty() || news.invites.isNotEmpty() || news.accepted.isNotEmpty()) arrived.fire()
            Presence.refresh()
        }
        // notifications: always from the background job; in the app only while it's not on screen
        if (fromJob || !visible) {
            for (r in news.requests) notifyRequest(r)
            for (i in news.invites) if (core.allowInvites) notifyInvite(i)
            for (f in news.accepted) notifyAccepted(f)
        }
        return true
    }

    // ------------------------------------------------------------------ actions (any thread)

    private fun bg(f: () -> Unit) = Thread { try { synchronized(lock) { f() } } catch (_: Exception) {}; main.post { changed.fire() } }.start()

    fun request(code: String, name: String = ""): Boolean {
        val c = FriendCore.norm(code)
        if (c.length != 8 || c == FriendCore.norm(core.code)) return false
        bg { core.request(c, name) }
        return true
    }
    fun accept(r: FriendCore.Request) = bg { core.accept(r); main.post { Presence.refresh() } }
    fun decline(r: FriendCore.Request) = bg { core.decline(r) }
    fun remove(f: FriendCore.Friend) = bg { core.remove(f.fid); main.post { Presence.refresh() } }
    fun block(fid: String, name: String) = bg { core.block(fid, name); main.post { Presence.refresh() } }
    fun unblock(fid: String) = bg { core.unblock(fid) }
    fun newCode() = bg { core.newCode() }

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
    object Presence {
        private const val BROKER = "ssl://broker.emqx.io:8883"
        private val io = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "friends-online").apply { isDaemon = true } }
        @Volatile private var client: MqttClient? = null
        @Volatile private var watching: Map<String, FriendCore.Friend> = emptyMap()
        @Volatile private var on = false

        private fun me(online: Boolean): MqttMessage {
            val body = JSONObject().put("on", online).put("at", System.currentTimeMillis()).toString()
            return MqttMessage(Seal.seal(core.presenceKey(), body).toByteArray()).apply { qos = 0; isRetained = true }
        }

        private val beat = object : Runnable {
            override fun run() { if (!on) return; io.execute { publishMe() }; main.postDelayed(this, 120_000) }
        }

        fun start() {
            if (!ready || !core.started || on) return
            on = true
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
                            core.setOnline(f.fid, if (o != null && o.optBoolean("on")) o.optLong("at") else null)
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
        fun refresh() { if (on) io.execute { watch(force = false) } }

        private fun watch(force: Boolean) {
            val c = client ?: return
            val want = core.friends.associateBy { core.presenceTopic(it.secret) }
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
