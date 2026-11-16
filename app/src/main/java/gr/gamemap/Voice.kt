package gr.gamemap

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Spoken directions through the phone's own text-to-speech voice (Android Settings ›
 * Text-to-speech). Music is turned down while it speaks; in the car it plays through the car.
 */
object Voice {
    val changed = Signal()
    private lateinit var appContext: Context
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null
    private var focus: AudioFocusRequest? = null

    // per-maneuver announcement state
    private var lastStep = -1
    private var saidFar = false
    private var saidPrep = false
    private var saidNow = false
    private var stepStartDist = 0.0

    fun init(ctx: Context) {
        appContext = ctx.applicationContext
    }

    private fun prefs() = appContext.getSharedPreferences("gamemap", Context.MODE_PRIVATE)

    var muted: Boolean
        get() = ::appContext.isInitialized && prefs().getBoolean("voiceMuted", false)
        set(v) {
            prefs().edit().putBoolean("voiceMuted", v).apply()
            if (v) tts?.stop()
            changed.fire()
        }

    /** "el" or "en"; defaults to the phone's language. */
    var lang: String
        get() {
            if (!::appContext.isInitialized) return Phrases.ENGLISH
            val def = if (Locale.getDefault().language == "el") Phrases.GREEK else Phrases.ENGLISH
            return prefs().getString("voiceLang", def) ?: def
        }
        set(v) {
            prefs().edit().putString("voiceLang", v).apply()
            ready = false
            tts?.shutdown(); tts = null
            changed.fire()
        }

    /** How much the voice talks: 0 = Normal, 1 = Fewer (one heads-up and the turn), 2 = Turns only. */
    var talk: Int
        get() = if (::appContext.isInitialized) prefs().getInt("voiceTalk", 0).coerceIn(0, 2) else 0
        set(v) { prefs().edit().putInt("voiceTalk", v.coerceIn(0, 2)).apply(); changed.fire() }

    /** The old timing (exact distances at moments set by speed), kept as a fallback in Advanced. */
    var classic: Boolean
        get() = ::appContext.isInitialized && prefs().getBoolean("voiceClassic", false)
        set(v) { prefs().edit().putBoolean("voiceClassic", v).apply(); changed.fire() }

    /** Lines said for the current maneuver (for tests): the heads-up distances, then 0 for the turn itself. */
    internal val saidMarks = ArrayList<Int>()

    /**
     * The heads-ups before a turn, in metres, by speed: in town 200 m; on faster roads 300 m; on the
     * motorway 1 km and 500 m. Fewer keeps the last one, Turns only none.
     */
    internal fun marks(speed: Float, talk: Int): List<Int> {
        val all = when {
            speed >= 22f -> listOf(1000, 500)   // about 80 km/h and up
            speed >= 14f -> listOf(300)         // about 50-80 km/h
            else -> listOf(200)
        }
        return when (talk) { 1 -> all.takeLast(1); 2 -> emptyList(); else -> all }
    }

    /** The turn itself is said this far ahead: about 5 s in town, 6 s on the motorway, at least 30 m. */
    internal fun nowAt(speed: Float): Double = maxOf(speed * (if (speed >= 22f) 6.0 else 5.0), 30.0)

    /** GPS fixes are a moment old and the voice takes a moment to start: say each line this much earlier. */
    internal fun lead(speed: Float): Double = speed * 1.2

    fun reset() {
        lastStep = -1
        saidFar = false; saidPrep = false; saidNow = false
    }

    /** Free the speech engine (app in the background with nothing to guide). */
    fun shutdown() {
        if (!::appContext.isInitialized) return
        abandonFocus()
        tts?.shutdown(); tts = null; ready = false; pending = null
    }

    fun stop() {
        tts?.stop()
        if (::appContext.isInitialized) abandonFocus()
        reset()
    }

    /** Called on every position update with the next maneuver. */
    fun progress(index: Int, step: NavStep, dist: Double, speed: Float, then: NavStep?) {
        if (index != lastStep) {
            lastStep = index
            saidFar = false; saidPrep = false; saidNow = false
            stepStartDist = dist
            saidMarks.clear()
        }
        if (step.direction == "arrive") return // the arrival has its own line
        // the road just changes its name (no turn): nothing worth saying
        if ((step.type == "continue" || step.type == "new name") && step.direction in setOf("straight", "continue")) return
        if (!classic) { progressNew(step, dist, speed, then); return }
        val near = maxOf(speed * 7.0, 40.0)
        val prep = maxOf(speed * 22.0, 250.0).coerceAtMost(1500.0)
        when {
            !saidNow && dist <= near -> {
                saidNow = true; saidPrep = true; saidFar = true
                val t = Phrases.instruction(step, lang, named = false)
                say(if (then != null) "$t, ${Phrases.then(then, lang, named = false)}" else t)
            }
            !saidPrep && dist <= prep && dist > near * 1.8 && stepStartDist > near * 2 -> {
                saidPrep = true; saidFar = true
                say(Phrases.ahead(step, dist, lang, named = false))
            }
            !saidFar && dist in 1800.0..2300.0 && speed > 17f -> {
                saidFar = true
                say(Phrases.ahead(step, dist, lang, named = false))
            }
        }
    }

    /**
     * Round distances at fixed moments: "In 200 metres, turn right", then "Turn right" about 5 s
     * before the turn. Each heads-up is said once, a little early, and only if there's time for it
     * before the turn itself (no "in 200 metres" right before "turn right").
     */
    private fun progressNew(step: NavStep, dist: Double, speed: Float, then: NavStep?) {
        val lead = lead(speed)
        val now = nowAt(speed)
        if (!saidNow && dist - lead <= now) {
            saidNow = true; saidPrep = true; saidFar = true
            saidMarks += 0
            val t = Phrases.instruction(step, lang, named = false)
            say(if (then != null) "$t, ${Phrases.then(then, lang, named = false)}" else t)
            return
        }
        if (saidNow) return
        val ms = marks(speed, talk)
        for ((i, m) in ms.withIndex()) {
            if (m in saidMarks) continue
            if (dist - lead > m) break                       // not there yet (and not the smaller ones either)
            val floor = ms.getOrNull(i + 1)?.toDouble() ?: (now * 1.6)
            // the turn began closer than this, or we're already past the next line's moment: skip it
            if (stepStartDist < m * 0.9 || dist - lead <= floor) { saidMarks += m; continue }
            saidMarks += m
            say(Phrases.ahead(step, m.toDouble(), lang, named = false))
            return
        }
    }

    /**
     * Another app is recording (Instagram, the camera, a call): a voice line would take the audio
     * focus and make it stop, so stay quiet.
     */
    private fun someoneRecording(): Boolean {
        if (Build.VERSION.SDK_INT < 24) return false
        val am = appContext.getSystemService(AudioManager::class.java) ?: return false
        return try {
            am.activeRecordingConfigurations.isNotEmpty() || am.mode == AudioManager.MODE_IN_CALL ||
                am.mode == AudioManager.MODE_IN_COMMUNICATION
        } catch (_: Exception) { false }
    }

    /** Tests: every line the voice is asked to say, before any check (null = off). */
    internal var heardForTest: MutableList<String>? = null

    fun say(text: String) {
        heardForTest?.add(text)
        if (!::appContext.isInitialized || muted) return
        // nothing to say unless the map is in use (open, in the car, or navigating)
        if (!LocationTracker.inUse && !NavEngine.active) return
        if (someoneRecording()) return
        val t = tts
        if (t == null || !ready) {
            pending = text
            if (t == null) create()
            return
        }
        val line = spoken(text)
        // the same line again within 20 s (e.g. right after a reroute): once is enough
        val now = System.currentTimeMillis()
        if (line == lastLine && now - lastLineAt < 20_000) return
        lastLine = line; lastLineAt = now
        if (!requestFocus()) return
        t.speak(line, TextToSpeech.QUEUE_FLUSH, Bundle(), "gm" + System.nanoTime())
    }

    private var lastLine = ""
    private var lastLineAt = 0L

    private val abbreviations = listOf(
        "Λεωφ." to "Λεωφόρος", "Λ." to "Λεωφόρος", "Αγ." to "Αγίου", "Πλ." to "Πλατεία", "Οδ." to "Οδός",
        "Εθν." to "Εθνικής", "Π." to "", "Κ." to "", "Ν." to "",
    )

    /** What the voice actually says: abbreviations spelled out, Greek names in Latin letters for an English voice. */
    internal fun spoken(text: String): String {
        var t = text
        for ((a, b) in abbreviations) t = t.replace(Regex("(?<![\\p{L}])" + Regex.escape(a)), b)
        t = t.replace(Regex("\\s{2,}"), " ")
        if (lang != Phrases.GREEK && t.any { it in 'Α'..'ω' || it in 'ά'..'ώ' }) t = latin(t)
        return t
    }

    /** Greek → Latin letters, the way the street signs spell them (Πατησίων → Patision). */
    internal fun latin(s: String): String {
        val n = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        val pairs = listOf("ου" to "ou", "ΟΥ" to "OU", "Ου" to "Ou", "αι" to "e", "Αι" to "E", "ει" to "i", "Ει" to "I", "οι" to "i", "Οι" to "I",
            "αυ" to "av", "Αυ" to "Av", "ευ" to "ev", "Ευ" to "Ev", "γγ" to "ng", "γκ" to "g", "Γκ" to "G", "μπ" to "b", "Μπ" to "B", "ντ" to "d", "Ντ" to "D",
            "θ" to "th", "Θ" to "Th", "χ" to "ch", "Χ" to "Ch", "ψ" to "ps", "Ψ" to "Ps", "ξ" to "x", "Ξ" to "X")
        var t = n
        for ((a, b) in pairs) t = t.replace(a, b)
        val one = "αβγδεζηικλμνοπρσςτυφωΑΒΓΔΕΖΗΙΚΛΜΝΟΠΡΣΤΥΦΩ"
        val to = arrayOf("a","v","g","d","e","z","i","i","k","l","m","n","o","p","r","s","s","t","i","f","o","A","V","G","D","E","Z","I","I","K","L","M","N","O","P","R","S","T","Y","F","O")
        val sb = StringBuilder()
        for (ch in t) { val k = one.indexOf(ch); sb.append(if (k >= 0) to[k] else ch.toString()) }
        return sb.toString()
    }

    private fun create() {
        tts = TextToSpeech(appContext) { status -> onInit(status) }
    }

    private fun onInit(status: Int) {
            val t = tts ?: return
            if (status != TextToSpeech.SUCCESS) return
            val loc = if (lang == Phrases.GREEK) Locale("el", "GR") else Locale.UK
            val r = t.setLanguage(loc)
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) t.setLanguage(Locale.US)
            t.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { abandonFocus() }
                // cut off by a newer line or stop(): give the focus back too
                override fun onStop(utteranceId: String?, interrupted: Boolean) { abandonFocus() }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { abandonFocus() }
            })
            ready = true
            pending?.let { pending = null; say(it) }
    }

    /** Short, duck-others focus for one line; false if the system said no (e.g. a call). */
    private fun requestFocus(): Boolean {
        if (Build.VERSION.SDK_INT < 26) return true
        val am = appContext.getSystemService(AudioManager::class.java) ?: return true
        focus?.let { am.abandonAudioFocusRequest(it) } // never leave an old request behind
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            ).build()
        focus = req
        return am.requestAudioFocus(req) != AudioManager.AUDIOFOCUS_REQUEST_FAILED
    }

    private fun abandonFocus() {
        if (Build.VERSION.SDK_INT < 26) return
        val am = appContext.getSystemService(AudioManager::class.java) ?: return
        focus?.let { am.abandonAudioFocusRequest(it) }
        focus = null
    }
}
