package gr.gamemap

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import java.util.TimeZone

/**
 * Pharmacies on duty tonight (εφημερεύοντα), all of Greece. A job in the repo reads the pharmacists'
 * associations' duty lists four times a day (Attica from the association's own site, the rest from
 * vrisko.gr where the associations send theirs) into one small file on the 'duties' branch; the app
 * downloads it when it's older than two hours and someone looks at pharmacies (or it's evening).
 */
object Duties {
    class Duty(val name: String, val address: String, val area: String, val phone: String, val pos: LatLng,
               /** [start, end) in ms, sorted */ val shifts: List<LongArray>, val src: String) {
        fun shiftAt(now: Long): LongArray? = shifts.firstOrNull { now >= it[0] && now < it[1] }
        fun next(now: Long): LongArray? = shifts.firstOrNull { it[0] > now }
        fun onDuty(now: Long) = shiftAt(now) != null
    }

    private const val URL = "https://raw.githubusercontent.com/Vyto-Line/GameRoute/duties/duties.json"
    private const val FRESH_MS = 2 * 3_600_000L
    @Volatile var all: List<Duty> = emptyList(); private set
    /** When the list was made (ms), 0 = none yet. */
    @Volatile var updated = 0L; private set
    private var file: File? = null
    @Volatile private var loading = false
    /** Called (main thread or not) when a new list arrives. */
    val changed = ArrayList<() -> Unit>()
    private val ATH: TimeZone = TimeZone.getTimeZone("Europe/Athens")
    /** Tests set the clock (duty times are real dates). */
    @Volatile internal var testNow: Long? = null
    fun now(): Long = testNow ?: System.currentTimeMillis()

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "duties.json")
        file?.takeIf { it.exists() }?.let { parse(it.readText()) }
    }

    private fun parse(json: String): Boolean = try {
        val o = JSONObject(json)
        val a = o.getJSONArray("d")
        val out = ArrayList<Duty>(a.length())
        for (i in 0 until a.length()) {
            val d = a.getJSONObject(i)
            val s = d.getJSONArray("s")
            out += Duty(d.optString("n"), d.optString("a"), d.optString("r"), d.optString("t"),
                LatLng(d.getDouble("la"), d.getDouble("lo")),
                (0 until s.length()).map { val x = s.getJSONArray(it); longArrayOf(x.getLong(0) * 1000, x.getLong(1) * 1000) }.sortedBy { it[0] },
                d.optString("src"))
        }
        all = out; updated = o.optLong("updated") * 1000
        true
    } catch (e: Exception) { false }

    /** Gets a newer list in the background if this one is more than two hours old. */
    fun refresh(force: Boolean = false) {
        val f = file ?: return
        if (loading) return
        if (testNow != null) return
        if (!force && all.isNotEmpty() && f.exists() && System.currentTimeMillis() - f.lastModified() < FRESH_MS) return
        loading = true
        Bg.factory("duties").newThread {
            try {
                val body = Http.get(URL, timeout = 20_000)
                if (parse(body)) { f.writeText(body); changed.toList().forEach { it() } }
            } catch (_: Exception) {} finally { loading = false }
        }.start()
    }

    /** On duty right now near [p], nearest first. */
    fun onDutyNear(p: LatLng, radius: Double = 15_000.0, now: Long = now()): List<Duty> =
        all.asSequence().filter { it.onDuty(now) && Geo.distance(p, it.pos) <= radius }.sortedBy { Geo.distance(p, it.pos) }.toList()

    /** The duty list's pharmacy at [p] (within 60 m), if it's on the list. */
    fun at(p: LatLng, meters: Double = 60.0): Duty? = all.minByOrNull { Geo.distance(p, it.pos) }?.takeIf { Geo.distance(p, it.pos) <= meters }

    /** Whether the list covers [p] (some duty within 25 km): otherwise we say nothing about duty there. */
    fun covers(p: LatLng): Boolean = all.any { Geo.distance(p, it.pos) < 25_000 }

    private fun cal(t: Long) = Calendar.getInstance(ATH).apply { timeInMillis = t }
    fun hhmm(t: Long): String = cal(t).let { "%02d:%02d".format(it.get(Calendar.HOUR_OF_DAY), it.get(Calendar.MINUTE)) }
    private fun sameDay(a: Long, b: Long) = cal(a).let { x -> cal(b).let { y -> x.get(Calendar.YEAR) == y.get(Calendar.YEAR) && x.get(Calendar.DAY_OF_YEAR) == y.get(Calendar.DAY_OF_YEAR) } }

    /** "till 08:00" / "till 08:00 tomorrow" (for the shift going on now). */
    fun till(d: Duty, now: Long = now()): String? {
        val s = d.shiftAt(now) ?: return null
        val end = if (hhmm(s[1]) == "00:00") s[1] - 60_000 else s[1]
        return L.t("till ") + hhmm(end) + if (!sameDay(end, now) && cal(now).get(Calendar.HOUR_OF_DAY) >= 12) L.t(" tomorrow") else ""
    }

    /** "On duty tonight · 21:00 → 08:00" (or today / the next one). */
    fun hoursLine(d: Duty, now: Long = now()): String? {
        val s = d.shiftAt(now) ?: d.next(now) ?: return null
        val night = !sameDay(s[0], s[1]) || cal(s[1]).get(Calendar.HOUR_OF_DAY) >= 22
        val head = when {
            now >= s[0] -> L.t(if (night) "On duty tonight" else "On duty today")
            sameDay(s[0], now) -> L.t(if (night) "On duty tonight" else "On duty today") + " · " + L.t("from ") + hhmm(s[0])
            else -> L.t("On duty tomorrow")
        }
        return "$head · ${hhmm(s[0])} → ${hhmm(s[1])}"
    }

    /** Where the list came from, for the small print. */
    fun sourceLine(d: Duty?): String {
        val who = if (d?.src == "fsa") L.t("Pharmacists' Association of Attica") else L.t("the local pharmacists' associations (via vrisko.gr)")
        return L.t("Duty list: ") + who + (if (updated > 0) " · " + L.t("updated ") + hhmm(updated) else "")
    }

    /**
     * Outside the usual opening hours (before 8, from 21:00, Sundays): only pharmacies on duty are open.
     * In between we don't know a pharmacy's own hours, so we don't say it's closed.
     */
    fun usuallyClosed(now: Long = now()): Boolean {
        val c = cal(now); val h = c.get(Calendar.HOUR_OF_DAY)
        return h < 8 || h >= 21 || c.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY
    }

    /** "opens 08:00" / "opens Mon 08:00", for a pharmacy that isn't on duty while they're closed. */
    fun opensLine(now: Long = now()): String {
        val c = cal(now)
        if (c.get(Calendar.HOUR_OF_DAY) >= 21) c.add(Calendar.DAY_OF_YEAR, 1)
        val sunday = c.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY
        return L.t("opens ") + (if (sunday) L.t("Mon") + " " else "") + "08:00"
    }

    /** As a destination / list row. */
    fun place(d: Duty, now: Long = now()): Place =
        Place(d.name.ifBlank { L.t("Pharmacy") }, listOf(d.address, d.area).filter { it.isNotBlank() }.joinToString(", "), d.pos, "💊",
            extra = (till(d, now)?.let { "🌙 " + L.t("On duty") + " · " + it } ?: hoursLine(d, now)?.let { "🌙 $it" }).orEmpty())

    /** Typed in search: a pharmacy word (φαρμακ, pharmac, εφημερ, farmak). */
    fun isPharmacyQuery(q: String): Boolean {
        val s = Places.norm(q.trim())
        if (s.length < 4) return false
        // a word starting a pharmacy word ("φαρμ", "pharma"), or containing one ("εφημερευοντα φαρμακεια")
        return listOf("φαρμακ", "pharmac", "εφημερ", "farmak", "efimer", "διανυκτ").any { w -> s.split(' ').any { it.length >= 4 && (w.startsWith(it) || it.startsWith(w)) } }
    }

    internal fun loadForTest(json: String) { parse(json) }
}
