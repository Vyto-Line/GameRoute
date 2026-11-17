package gr.gamemap

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Friends, without accounts or a server of our own (the logic only: storage and the network are
 * passed in, so tests can run two phones in one place).
 *
 * - Everyone has a permanent hidden id ([fid]) and a private inbox: a secret only friends know.
 *   Friends write to each other's inbox (ntfy keeps messages 12 h, so a phone that was off gets
 *   them later); every message is end-to-end encrypted with a key from that secret.
 * - The friend code (KX7P-4821) is only how someone finds you the first time: a request goes to a
 *   topic made from the code, encrypted with a key from it. A new code: friends stay (they know the
 *   inbox), old links stop working.
 * - Location is never sent to friends: only names, requests, convoy invites and their answers.
 */
class FriendCore(private val store: Store, private val wire: Wire, private val clock: () -> Long = { System.currentTimeMillis() }) {

    interface Store { fun get(key: String): String?; fun put(key: String, value: String?) }

    /** ntfy, or a fake in tests. */
    interface Wire {
        fun post(topic: String, body: String)
        /** Messages on [topics] after [since] (a message id, or e.g. "12h"): (id, topic, body). */
        fun poll(topics: List<String>, since: String): List<Triple<String, String, String>>
    }

    class Friend(val fid: String, var name: String, val secret: String, val since: Long, val code: String = "")
    class Request(val fid: String, val name: String, val secret: String, val at: Long, val nonce: String, val code: String)
    /** A request I sent: by code, so I don't know who they are until they answer. [status]: waiting, accepted, declined. */
    class Pending(val nonce: String, val code: String, val name: String, val at: Long, val status: String = "waiting")
    /** A convoy invite I got (several for the same convoy count as one). */
    class Invite(val iid: String, val fid: String, val from: String, val convoy: String, val dest: String, val at: Long, val count: Int)
    /** A convoy invite I sent, and how far it got: sent, seen, joined, no. */
    class Sent(val fid: String, val iid: String, val convoy: String, val at: Long, val status: String)
    /** Someone I drove with in a convoy (to add them later). */
    class Recent(val fid: String, val name: String, val code: String, val dest: String, val at: Long)

    // ------------------------------------------------------------------ identity

    private val rnd = SecureRandom()

    val fid: String get() = store.get("fid") ?: hex(bytes(8)).also { store.put("fid", it) }
    private val secret: String get() = store.get("secret") ?: Seal.b64(bytes(16)).also { store.put("secret", it) }
    var name: String = "Driver"

    /** Friends used on this phone (a code was made): only then is anything checked, so no battery otherwise. */
    val started: Boolean get() = store.get("code") != null

    /**
     * My friend code, as people read it: my name and 4 digits, "VYTO-4821". (Codes from the first
     * version, "KX7P-4821", are replaced once: friends stay, they know my inbox.)
     */
    val code: String get() {
        val c = store.get("code")
        if (c != null && store.get("codeV") == "2") return c
        return newCodeText().also { store.put("code", it); store.put("codeV", "2") }
    }

    /** A new code: requests to the old one stop coming; friends stay (they write to my inbox). */
    fun newCode(): String { val c = newCodeText(); store.put("code", c); store.put("codeV", "2"); return c }

    private fun newCodeText(): String {
        val letters = latin(name).uppercase().filter { it in 'A'..'Z' }.take(6).ifEmpty { "DRIVER" }
        return letters + "-" + (1000 + rnd.nextInt(9000))
    }

    /**
     * Your status: Online, Do not disturb (friends see you as busy; invites wait silently and show
     * when you're back) or Invisible (you look offline; invites to you are not delivered at all, and
     * nothing tells the sender). You can still see friends and invite them in every status.
     * [statusUntil]: back to Online by itself after this moment (0 = never).
     */
    var status: String
        get() {
            val s = store.get("status") ?: (if (store.get("showOnline") == "0") INVISIBLE else ONLINE)
            val until = store.get("statusUntil")?.toLongOrNull() ?: 0L
            if (s != ONLINE && until > 0 && clock() >= until) { store.put("status", ONLINE); store.put("statusUntil", "0"); store.put("showOnline", "1"); return ONLINE }
            return s
        }
        set(v) { store.put("status", v); store.put("showOnline", if (v == INVISIBLE) "0" else "1") }
    var statusUntil: Long
        get() = store.get("statusUntil")?.toLongOrNull() ?: 0L
        set(v) = store.put("statusUntil", v.toString())

    var showOnline: Boolean
        get() = status != INVISIBLE
        set(v) { status = if (v) ONLINE else INVISIBLE }
    var allowInvites: Boolean
        get() = store.get("allowInvites") != "0"
        set(v) = store.put("allowInvites", if (v) "1" else "0")

    // ------------------------------------------------------------------ lists

    val friends: List<Friend> get() = arr("friends").map { Friend(it.getString("fid"), it.optString("n", "Driver"), it.getString("s"), it.optLong("since"), it.optString("c")) }
    val requests: List<Request> get() = arr("requests").map { Request(it.getString("fid"), it.optString("n", "Driver"), it.getString("s"), it.optLong("at"), it.optString("nonce"), it.optString("c")) }
    /** Requests I sent: waiting ones, and answered ones for a day (so I see "Accepted" / "Declined"). */
    val pending: List<Pending> get() = arr("pending").map { Pending(it.getString("nonce"), it.optString("code"), it.optString("n"), it.optLong("at"), it.optString("st", "waiting")) }
        .filter { it.status == "waiting" || clock() - it.at < 24 * 3600_000L }
    val blocked: Set<String> get() = arr("blocked").map { it.getString("fid") }.toSet()
    val recent: List<Recent> get() = arr("recent").map { Recent(it.getString("fid"), it.optString("n"), it.optString("c"), it.optString("d"), it.optLong("at")) }

    /** Current invites only: older than 2 h, or their convoy ended, and they're gone. Do not disturb: none shown (they wait). */
    val invites: List<Invite> get() {
        if (status == DND) return emptyList()
        val now = clock()
        return arr("invites").map { Invite(it.getString("iid"), it.getString("fid"), it.optString("n"), it.getString("cv"), it.optString("d"), it.optLong("at"), it.optInt("k", 1)) }
            .filter { now - it.at < INVITE_TTL && it.fid !in blocked && friend(it.fid) != null }
    }
    val sent: List<Sent> get() = arr("sent").map { Sent(it.getString("fid"), it.getString("iid"), it.getString("cv"), it.optLong("at"), it.optString("st", "sent")) }
        .filter { clock() - it.at < INVITE_TTL }

    fun friend(fid: String) = friends.firstOrNull { it.fid == fid }
    fun isFriend(fid: String) = friend(fid) != null
    fun requested(code: String) = pending.any { it.status == "waiting" && norm(it.code) == norm(code) }
    fun sentTo(fid: String, convoy: String) = sent.lastOrNull { it.fid == fid && it.convoy == convoy }

    /** Online: their app was open in the last few minutes (and they let friends see it). */
    private val online = HashMap<String, Long>()
    private val busy = HashSet<String>()
    fun setOnline(fid: String, at: Long?, dnd: Boolean = false) {
        synchronized(online) { if (at == null) online.remove(fid) else online[fid] = at; if (dnd && at != null) busy += fid else busy -= fid }
    }
    /** Online but set to Do not disturb: their invites wait until they're back. */
    fun isBusy(fid: String) = synchronized(online) { fid in busy } && isOnline(fid)
    fun isOnline(fid: String) = synchronized(online) { online[fid]?.let { clock() - it < ONLINE_FOR } ?: false }

    // ------------------------------------------------------------------ sending

    /** Asks the person with [code] to be friends ([name]: who I think they are, e.g. from a convoy). */
    fun request(code: String, name: String = ""): Boolean {
        val c = norm(code)
        if (!valid(c) || c == norm(this.code)) return false
        val nonce = hex(bytes(6))
        removeWhere("pending") { norm(it.optString("code")) == c } // asked again: one entry
        edit("pending") { a -> a.put(JSONObject().put("nonce", nonce).put("code", pretty(c)).put("n", name).put("at", clock())) }
        val body = JSONObject().put("t", "req").put("fid", fid).put("n", name()).put("s", secret).put("c", code()).put("nonce", nonce).put("at", clock())
        wire.post(requestTopic(c), Seal.seal(requestKey(c), body.toString()))
        return true
    }

    fun accept(r: Request) {
        removeWhere("requests") { it.getString("fid") == r.fid }
        addFriend(r.fid, r.name, r.secret, r.code)
        send(r.secret, JSONObject().put("t", "acc").put("nonce", r.nonce).put("s", secret).put("c", code()))
    }

    /** No: they see "Declined" (nothing else about me). */
    fun decline(r: Request) {
        removeWhere("requests") { it.getString("fid") == r.fid }
        send(r.secret, JSONObject().put("t", "dec").put("nonce", r.nonce))
    }

    /** Invites [f] to the convoy with [convoy] code (going to [dest]); returns the invite's id. */
    fun invite(f: Friend, convoy: String, dest: String): String {
        val iid = hex(bytes(6))
        removeWhere("sent") { it.getString("fid") == f.fid && it.getString("cv") == convoy }
        edit("sent") { a -> a.put(JSONObject().put("fid", f.fid).put("iid", iid).put("cv", convoy).put("at", clock()).put("st", "sent")) }
        send(f.secret, JSONObject().put("t", "inv").put("iid", iid).put("cv", convoy).put("d", dest))
        return iid
    }

    /** I left the convoy (or it ended): my invites to it disappear on my friends' phones. */
    fun cancelInvites(convoy: String) {
        val mine = sent.filter { it.convoy == convoy }
        for (s in mine) friend(s.fid)?.let { send(it.secret, JSONObject().put("t", "invx").put("cv", convoy)) }
        removeWhere("sent") { it.getString("cv") == convoy }
    }

    /** Tells whoever invited me to [convoy]: seen / joined / no. "joined" and "no" also clear it here. */
    fun answer(convoy: String, status: String) {
        val mine = invites.filter { it.convoy == convoy }
        for (i in mine) friend(i.fid)?.let { send(it.secret, JSONObject().put("t", "invst").put("iid", i.iid).put("cv", convoy).put("st", status)) }
        if (status != "seen") removeWhere("invites") { it.getString("cv") == convoy }
        else edit("invites") { a -> for (k in 0 until a.length()) if (a.getJSONObject(k).getString("cv") == convoy) a.getJSONObject(k).put("seen", true) }
    }

    fun seen(convoy: String) = arr("invites").any { it.getString("cv") == convoy && it.optBoolean("seen") }

    fun remove(fid: String) {
        friend(fid)?.let { send(it.secret, JSONObject().put("t", "rm")) }
        removeWhere("friends") { it.getString("fid") == fid }
        removeWhere("invites") { it.getString("fid") == fid }
        removeWhere("sent") { it.getString("fid") == fid }
    }

    fun block(fid: String, name: String) {
        remove(fid)
        removeWhere("requests") { it.getString("fid") == fid }
        if (fid !in blocked) edit("blocked") { a -> a.put(JSONObject().put("fid", fid).put("n", name)) }
    }

    fun unblock(fid: String) = removeWhere("blocked") { it.getString("fid") == fid }
    fun blockedNames(): List<Pair<String, String>> = arr("blocked").map { it.getString("fid") to it.optString("n", "Driver") }

    /** How many convoys I was in with [fid] (counted on this phone only). */
    fun drivesWith(fid: String): Int = try { JSONObject(store.get("drives") ?: "{}").optInt(fid, 0) } catch (e: Exception) { 0 }

    /** My own name for a friend (only on my phone): blank = the name they chose. */
    fun nickname(fid: String): String = try { JSONObject(store.get("nicks") ?: "{}").optString(fid) } catch (e: Exception) { "" }
    fun setNickname(fid: String, nick: String) {
        val o = try { JSONObject(store.get("nicks") ?: "{}") } catch (e: Exception) { JSONObject() }
        if (nick.isBlank()) o.remove(fid) else o.put(fid, nick.trim().take(30))
        store.put("nicks", o.toString())
    }
    /** What I call a friend: my nickname, or their name. */
    fun shownName(f: Friend): String = nickname(f.fid).ifBlank { f.name }

    /** Someone in my convoy (who uses friends): kept for "Drove with you". */
    fun droveWith(fid: String, name: String, code: String, dest: String) {
        if (fid == this.fid || code.isBlank()) return
        // one more drive together (once per convoy)
        val seen = arr("recent").any { it.getString("fid") == fid && it.optString("c") == code }
        if (!seen) {
            val o = try { JSONObject(store.get("drives") ?: "{}") } catch (e: Exception) { JSONObject() }
            o.put(fid, o.optInt(fid, 0) + 1); store.put("drives", o.toString())
        }
        removeWhere("recent") { it.getString("fid") == fid }
        edit("recent") { a -> a.put(JSONObject().put("fid", fid).put("n", name).put("c", code).put("d", dest).put("at", clock())) }
        val a = arr("recent"); if (a.size > 12) store.put("recent", JSONArray(a.takeLast(12)).toString())
    }

    private fun send(toSecret: String, o: JSONObject) {
        if (toSecret == TEST_SECRET) return // a made-up test friend: nothing ever leaves the phone
        o.put("fid", fid).put("n", name()).put("at", clock())
        wire.post(inboxTopic(toSecret), Seal.seal(inboxKey(toSecret), o.toString()))
    }

    // ------------------------------------------------------------------ receiving

    /** What changed when checking: for notifications. */
    class News(val requests: List<Request>, val invites: List<Invite>, val accepted: List<Friend>, val anyChange: Boolean, val declined: List<Declined> = emptyList())

    /** Fetches and handles everything new (requests to my code, my inbox). */
    fun check(): News {
        // the last message seen; after ~12 h ntfy has forgotten it, so ask for the last 12 h then
        val at = store.get("sinceAt")?.toLongOrNull() ?: 0L
        val since = store.get("since")?.takeIf { clock() - at < 11 * 3600_000L } ?: "12h"
        val list = wire.poll(listOf(inboxTopic(secret), requestTopic(norm(code))), since)
        val reqs = ArrayList<Request>(); val invs = ArrayList<Invite>(); val acc = ArrayList<Friend>(); val dec = ArrayList<Declined>()
        var any = false
        for ((id, topic, body) in list) {
            store.put("since", id); store.put("sinceAt", clock().toString())
            val r = take(topic, body) ?: continue
            any = true
            when (r) { is Request -> reqs += r; is Invite -> invs += r; is Friend -> acc += r; is Declined -> dec += r }
        }
        return News(reqs, invs, acc, any, dec)
    }

    /** One message: returns what's new for a notification (a request, an invite, a new friend), or Unit. */
    internal fun take(topic: String, body: String): Any? {
        if (topic == requestTopic(norm(code))) {
            val o = Seal.open(requestKey(norm(code)), body)?.let { JSONObject(it) } ?: return null
            if (o.optString("t") != "req") return null
            val from = o.optString("fid"); if (from.isBlank() || from == fid || from in blocked) return null
            if (clock() - o.optLong("at") > INVITE_TTL * 6) return null // 12 h: ntfy's limit anyway
            // already friends (e.g. they moved phones, or added me again): just say yes again
            if (isFriend(from)) { send(o.getString("s"), JSONObject().put("t", "acc").put("nonce", o.optString("nonce")).put("s", secret).put("c", code())); return null }
            if (requests.any { it.fid == from }) return null
            val r = Request(from, o.optString("n", "Driver").take(24), o.getString("s"), o.optLong("at"), o.optString("nonce"), o.optString("c"))
            edit("requests") { a -> a.put(JSONObject().put("fid", r.fid).put("n", r.name).put("s", r.secret).put("at", r.at).put("nonce", r.nonce).put("c", r.code)) }
            return r
        }
        if (topic != inboxTopic(secret)) return null
        val o = Seal.open(inboxKey(secret), body)?.let { JSONObject(it) } ?: return null
        val from = o.optString("fid"); if (from.isBlank() || from == fid || from in blocked) return null
        val name = o.optString("n", "Driver").take(24)
        when (o.optString("t")) {
            "acc" -> {
                val nonce = o.optString("nonce")
                if (pending.none { it.nonce == nonce && it.status == "waiting" }) return if (isFriend(from)) Unit else null
                answered(nonce, "accepted", name)
                if (isFriend(from)) return Unit
                return addFriend(from, name, o.getString("s"), o.optString("c"))
            }
            "dec" -> {
                val nonce = o.optString("nonce")
                val p = pending.firstOrNull { it.nonce == nonce && it.status == "waiting" } ?: return null
                answered(nonce, "declined", "")
                return Declined(p.name.ifBlank { pretty(p.code) })
            }
        }
        val f = friend(from) ?: return null // everything else only from friends
        if (f.name != name) edit("friends") { a -> for (k in 0 until a.length()) if (a.getJSONObject(k).getString("fid") == from) a.getJSONObject(k).put("n", name) }
        when (o.optString("t")) {
            "inv" -> {
                val cv = o.optString("cv"); if (cv.isBlank() || clock() - o.optLong("at") > INVITE_TTL) return null
                // invisible: not delivered (no "seen" either, so nothing gives me away)
                if (status == INVISIBLE) return null
                val old = arr("invites").firstOrNull { it.getString("cv") == cv }
                val k = (old?.optInt("k", 1) ?: 0) + 1
                removeWhere("invites") { it.getString("cv") == cv }
                edit("invites") { a -> a.put(JSONObject().put("iid", o.optString("iid")).put("fid", from).put("n", name).put("cv", cv).put("d", o.optString("d")).put("at", o.optLong("at")).put("k", k)) }
                return invites.firstOrNull { it.convoy == cv }
            }
            "invx" -> { removeWhere("invites") { it.getString("cv") == o.optString("cv") && it.getString("fid") == from }; return Unit }
            "invst" -> {
                val st = o.optString("st"); if (st !in setOf("seen", "joined", "no")) return null
                edit("sent") { a ->
                    for (k in 0 until a.length()) {
                        val s = a.getJSONObject(k)
                        // never back: joined stays joined even if a late "seen" comes
                        if (s.getString("fid") == from && s.getString("iid") == o.optString("iid") && rank(st) > rank(s.optString("st"))) s.put("st", st)
                    }
                }
                return Unit
            }
            "rm" -> {
                removeWhere("friends") { it.getString("fid") == from }
                removeWhere("invites") { it.getString("fid") == from }
                removeWhere("sent") { it.getString("fid") == from }
                return Unit
            }
        }
        return null
    }

    /** Someone said no to my request (for a note). */
    class Declined(val who: String)

    private fun answered(nonce: String, st: String, name: String) = edit("pending") { a ->
        for (k in 0 until a.length()) {
            val p = a.getJSONObject(k)
            if (p.getString("nonce") == nonce) { p.put("st", st).put("at", clock()); if (name.isNotBlank()) p.put("n", name) }
        }
    }

    private fun rank(st: String) = when (st) { "seen" -> 1; "joined", "no" -> 2; else -> 0 }

    private fun addFriend(fid: String, name: String, s: String, code: String): Friend {
        removeWhere("friends") { it.getString("fid") == fid }
        edit("friends") { a -> a.put(JSONObject().put("fid", fid).put("n", name).put("s", s).put("since", clock()).put("c", code)) }
        return friend(fid)!!
    }

    // ------------------------------------------------------------------ topics and keys

    /** Where my friends' updates on whether I'm online go (MQTT, kept by the broker). */
    fun presenceTopic(s: String = secret) = "gameroute/p/" + hash("gr-presence-v1:$s").take(24)
    fun presenceKey(s: String = secret) = inboxKey(s)

    /** The topics news comes on (my inbox and requests to my code), for listening live. */
    fun myTopics(): List<String> = listOf(inboxTopic(secret), requestTopic(norm(code)))

    private fun inboxTopic(s: String) = "gmf" + hash("gr-inbox-v1:$s").take(24)
    private fun inboxKey(s: String) = MessageDigest.getInstance("SHA-256").digest("gr-inbox-key-v1:$s".toByteArray())
    private fun requestTopic(c: String) = "gmr" + hash("gr-request-v1:$c").take(24)

    @Volatile private var keyCache: Pair<String, ByteArray>? = null
    /** Slow on purpose (like the convoy key): codes can't be tried quickly. */
    private fun requestKey(c: String): ByteArray {
        keyCache?.let { if (it.first == c) return it.second }
        val spec = PBEKeySpec(c.toCharArray(), "gameroute-friend-v1".toByteArray(), 30_000, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded.also { keyCache = c to it }
    }

    private fun name() = name.ifBlank { "Driver" }
    private fun code() = code

    // ------------------------------------------------------------------ storage helpers

    private fun arr(key: String): List<JSONObject> = try {
        val a = JSONArray(store.get(key) ?: "[]"); (0 until a.length()).map { a.getJSONObject(it) }
    } catch (e: Exception) { emptyList() }

    @Synchronized private fun edit(key: String, f: (JSONArray) -> Unit) {
        val a = try { JSONArray(store.get(key) ?: "[]") } catch (e: Exception) { JSONArray() }
        f(a); store.put(key, a.toString())
    }

    @Synchronized private fun removeWhere(key: String, f: (JSONObject) -> Boolean) {
        store.put(key, JSONArray(arr(key).filterNot(f)).toString())
    }

    private fun bytes(n: Int) = ByteArray(n).also { rnd.nextBytes(it) }

    // ------------------------------------------------------------------ test builds only (Dev)


    /** A made-up friend on this phone only ("test-…"): nothing about them is ever sent. */
    internal fun addTestFriend(fid: String, name: String) {
        if (isFriend(fid)) return
        edit("friends") { it.put(JSONObject().put("fid", fid).put("n", name).put("s", TEST_SECRET).put("since", clock()).put("c", "TEST")) }
    }
    internal fun addTestInvite(fid: String, from: String, convoy: String, dest: String) {
        edit("invites") { it.put(JSONObject().put("iid", "test-" + clock()).put("fid", fid).put("n", from).put("cv", convoy).put("d", dest).put("at", clock()).put("k", 1)) }
    }
    /** For tests: an invite gone. */
    internal fun removeInviteForTest(convoy: String) = removeWhere("invites") { it.optString("cv") == convoy }

    /** Every made-up friend and their invites, gone. */
    fun removeTestFriends() {
        removeWhere("friends") { it.optString("fid").startsWith("test-") }
        removeWhere("invites") { it.optString("fid").startsWith("test-") }
        removeWhere("sent") { it.optString("fid").startsWith("test-") }
    }
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun hash(s: String) = hex(MessageDigest.getInstance("SHA-256").digest(s.toByteArray()))

    companion object {
        /** Made-up test friends (test builds) have this "secret": nothing is ever sent to them. */
        const val TEST_SECRET = "test-local-only"
        const val INVITE_TTL = 2 * 3600_000L
        const val ONLINE_FOR = 5 * 60_000L
        const val ONLINE = "online"; const val DND = "dnd"; const val INVISIBLE = "invisible"
        /** "kx7p 4821", "KX7P-4821" and a link with it all become "KX7P4821". */
        fun norm(code: String) = code.substringAfterLast('#').substringAfterLast('/').uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }
        /** A name (1–6 letters) and 4 digits: "VYTO4821". */
        fun valid(c: String) = Regex("[A-Z]{1,6}[0-9]{4}").matches(norm(c))
        /** "vyto 4821" → "VYTO-4821". */
        fun pretty(c: String) = norm(c).let { n -> val i = n.indexOfFirst { it.isDigit() }; if (i > 0) n.take(i) + "-" + n.drop(i) else n }

        /** Greek letters in Latin ones, for the code ("Βύτο" → "VYTO"). */
        fun latin(s: String): String {
            val m = mapOf('α' to "a", 'ά' to "a", 'β' to "v", 'γ' to "g", 'δ' to "d", 'ε' to "e", 'έ' to "e", 'ζ' to "z", 'η' to "i", 'ή' to "i",
                'θ' to "th", 'ι' to "i", 'ί' to "i", 'ϊ' to "i", 'ΐ' to "i", 'κ' to "k", 'λ' to "l", 'μ' to "m", 'ν' to "n", 'ξ' to "x", 'ο' to "o", 'ό' to "o",
                'π' to "p", 'ρ' to "r", 'σ' to "s", 'ς' to "s", 'τ' to "t", 'υ' to "y", 'ύ' to "y", 'ϋ' to "y", 'ΰ' to "y", 'φ' to "f", 'χ' to "ch",
                'ψ' to "ps", 'ω' to "o", 'ώ' to "o")
            return s.lowercase().map { m[it] ?: it.toString() }.joinToString("")
        }
    }
}
