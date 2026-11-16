package gr.gamemap

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Shops, cafés, restaurants and every other business in Greece and Cyprus from Overture Maps
 * (Meta and Foursquare data; tools/places.py, refreshed monthly in CI), in 0.1° squares on the
 * data branch. The squares around the user are kept on the phone (a month), so search finds
 * places that OpenStreetMap and TomTom don't have, instantly and offline.
 */
object OpenPlaces {
    private const val BASE = "https://raw.githubusercontent.com/Vyto-Line/GameRoute/data/places/"
    private const val MAX_CELLS = 9
    private const val MONTH = 30L * 86_400_000

    /** One square: the raw lines, and per place a search text (by sound, both scripts). */
    private class Cell(val lines: Array<String>, val snd: Array<String>, val lat: DoubleArray, val lon: DoubleArray)

    private lateinit var dir: File
    private val cells = ConcurrentHashMap<String, Cell>()
    private val missing = ConcurrentHashMap<String, Long>() // squares with no data (sea, abroad), and failures
    private val order = java.util.Collections.synchronizedList(ArrayList<String>())

    /** Squares of downloaded regions (Offline maps): kept until deleted. */
    private lateinit var offDir: File

    fun init(ctx: Context) {
        dir = File(ctx.cacheDir, "places").apply { mkdirs() }
        offDir = File(ctx.filesDir, "places-offline").apply { mkdirs() }
    }

    /** Downloads one square into the offline store (Offline maps). */
    fun saveOffline(key: String) {
        if (!ready) return
        val f = File(offDir, "$key.tsv.gz")
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < MONTH) return
        val c = java.net.URL("$BASE$key.tsv.gz").openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 20000
        c.setRequestProperty("User-Agent", "GameRoute/1.0 (Android)")
        try {
            if (c.responseCode == 200) { val tmp = File(offDir, "$key.part"); c.inputStream.use { i -> tmp.outputStream().use { i.copyTo(it) } }; tmp.renameTo(f) }
        } finally { c.disconnect() }
    }
    val ready get() = ::dir.isInitialized

    private fun keyOf(lat: Double, lon: Double) = "${Math.floor(lat * 10).toInt()}_${Math.floor(lon * 10).toInt()}"

    /** The user's square and the three next to them on their side of it: ≥ 4.5 km all round. */
    fun around(p: LatLng): List<String> {
        val y = Math.floor(p.lat * 10).toInt(); val x = Math.floor(p.lon * 10).toInt()
        val dy = if (p.lat * 10 - y < 0.5) -1 else 1
        val dx = if (p.lon * 10 - x < 0.5) -1 else 1
        return listOf("${y}_$x", "${y + dy}_$x", "${y}_${x + dx}", "${y + dy}_${x + dx}")
    }

    /** The squares around [p] are loaded. */
    fun has(p: LatLng) = around(p).all { cells.containsKey(it) || missing.containsKey(it) }

    /** Loads (downloading if needed) the squares around [p]. Off the main thread. */
    fun prepare(p: LatLng, download: Boolean = true) {
        if (!ready) return
        for (k in around(p)) load(k, download)
    }

    private fun load(key: String, download: Boolean): Cell? {
        cells[key]?.let { order.remove(key); order += key; return it } // used again: kept longest
        missing[key]?.let { if (System.currentTimeMillis() - it < 6 * 3_600_000) return null }
        // a downloaded region's square first (works with no signal)
        val off = File(offDir, "$key.tsv.gz")
        val f = if (off.exists()) off else File(dir, "$key.tsv.gz")
        val fresh = f.exists() && (f === off || System.currentTimeMillis() - f.lastModified() < MONTH)
        if (!fresh && download) {
            try {
                val c = java.net.URL("$BASE$key.tsv.gz").openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 20000
                c.setRequestProperty("User-Agent", "GameRoute/1.0 (Android)")
                when (c.responseCode) {
                    200 -> { val tmp = File(dir, "$key.part"); c.inputStream.use { i -> tmp.outputStream().use { i.copyTo(it) } }; tmp.renameTo(f) }
                    404 -> { missing[key] = System.currentTimeMillis(); f.delete(); return null } // nothing there
                    else -> {}
                }
                c.disconnect()
            } catch (_: Exception) {}
        }
        if (!f.exists()) { if (download) missing[key] = System.currentTimeMillis(); return null }
        val cell = try { parse(java.util.zip.GZIPInputStream(f.inputStream()).bufferedReader().readText()) } catch (e: Exception) { f.delete(); return null }
        cells[key] = cell
        order += key
        while (order.size > MAX_CELLS) { val old = order.removeAt(0); cells.remove(old) }
        return cell
    }

    private fun parse(text: String): Cell {
        val lines = text.split('\n').filter { it.isNotEmpty() }.toTypedArray()
        val snd = arrayOfNulls<String>(lines.size)
        val lat = DoubleArray(lines.size); val lon = DoubleArray(lines.size)
        for ((i, l) in lines.withIndex()) {
            val f = l.split('\t')
            lat[i] = f.getOrNull(2)?.toDoubleOrNull() ?: 0.0
            lon[i] = f.getOrNull(3)?.toDoubleOrNull() ?: 0.0
            snd[i] = searchText(f[0] + " " + f.getOrElse(1) { "" })
        }
        @Suppress("UNCHECKED_CAST")
        return Cell(lines, snd as Array<String>, lat, lon)
    }

    /**
     * " word word … " by sound, as written and in Greek letters, plus the words run together
     * ("Crepa Land" also as "crepaland"), so any spelling of the name finds it.
     */
    fun searchText(name: String): String {
        val a = Places.sound(name); val b = Places.sound(Places.greeklish(name))
        val parts = LinkedHashSet<String>()
        for (s in listOf(a, b)) {
            val w = s.split(Regex("[\\s,.\\-()&'/|+:!]+")).filter { it.isNotEmpty() }
            parts += w
            if (w.size > 1) parts += w.joinToString("")
        }
        return " " + parts.joinToString(" ") + " "
    }

    /** Places whose name has the words of [q] (each word starting a word of the name), near [at]. */
    fun search(q: Places.Query, at: LatLng?, limit: Int = 12): List<Place> {
        if (q.words.isEmpty() || cells.isEmpty()) return emptyList()
        // a word must start a word of the name; spelling slips of one letter are let through for long words
        val forms = q.forms.map { fs -> fs.map { " $it" } }
        val joined = q.words.joinToString("").let { listOf(" " + Places.sound(it), " " + Places.sound(Places.greeklish(it))) }
        val out = ArrayList<Pair<Place, Double>>()
        for (c in cells.values.toList()) {
            for (i in c.snd.indices) {
                val s = c.snd[i]
                var m = 0
                for (fs in forms) if (fs.any { s.contains(it) }) m++
                val all = m == forms.size || (forms.size > 1 && joined.any { s.contains(it) })
                if (!all && !(forms.size >= 3 && m >= forms.size - 1)) continue
                val f = c.lines[i].split('\t')
                val pos = LatLng(c.lat[i], c.lon[i])
                val conf = f.getOrNull(6)?.toDoubleOrNull() ?: 0.5
                val d = if (at != null) Geo.distance(at, pos) else 0.0
                out += Place(f[0], f.getOrElse(5) { "" }, pos, icon(f.getOrElse(4) { "" }, f[0])) to (if (all) 1.0 else 0.6) * (0.7 + conf * 0.3) - Math.log10(1 + d / 1000) * 0.15
            }
        }
        return out.sortedByDescending { it.second }.map { it.first }.distinctBy { Places.norm(it.name) + (it.pos.lat * 500).toInt() + (it.pos.lon * 500).toInt() }.take(limit)
    }

    /** Overture's categories (and, where they're vague or missing, the name) → the app's icons. */
    fun icon(cat: String, name: String = ""): String {
        val c = cat.lowercase()
        if (c.isEmpty() || c == "restaurant" || c == "casual_eatery" || c == "food_and_beverage" || c == "shopping") {
            val n = Places.norm(name)
            val byName = when {
                listOf("crep", "κρεπ", "waffle", "βαφλ", "gelat", "ice cream", "παγωτ", "ζαχαροπλ", "patisser", "bakery", "φουρν", "αρτοποι", "donut", "cake").any { it in n } -> "🍰"
                listOf("coffee", "cafe", "καφε").any { it in n } -> "☕"
                listOf("pharmac", "φαρμακ").any { it in n } -> "💊"
                listOf("souvlak", "σουβλ", "gyro", "γυρο", "pizza", "πιτσ", "burger", "ταβερν", "taverna", "ψητοπωλ", "grill", "restaurant", "εστιατ", "sushi").any { it in n } -> "🍽"
                listOf("super market", "supermarket", "σουπερ μαρκετ", "market").any { it in n } -> "🛒"
                listOf("bar ", " bar", "μπαρ").any { it in " $n " } -> "🍸"
                listOf("hotel", "ξενοδοχ", "rooms", "apartments", "suites").any { it in n } -> "🏨"
                else -> null
            }
            if (byName != null) return byName
        }
        return when {
            c.isEmpty() -> "📍"
            "charging" in c -> "🔌"
            "gas_station" in c || "fuel" in c -> "⛽"
            "parking" in c -> "🅿"
            "pharmacy" in c || "drug" in c -> "💊"
            "hospital" in c || "clinic" in c || "doctor" in c || "medical" in c || "health" in c -> "🏥"
            "dent" in c -> "🦷"
            "coffee" in c || "cafe" in c || "tea" in c -> "☕"
            "bakery" in c || "dessert" in c || "pastry" in c || "ice_cream" in c || "sweet" in c || "candy" in c -> "🍰"
            "bar" == c || "pub" in c || "night" in c || "lounge" in c || "brewery" in c || "wine_bar" in c || "cocktail" in c -> "🍸"
            "restaurant" in c || "eatery" in c || "food_court" in c || "fast_food" in c || "pizza" in c || "burger" in c || "taverna" in c -> "🍽"
            "supermarket" in c || "grocery" in c || "food_and_beverage_store" in c || "convenience" in c -> "🛒"
            "hotel" in c || "lodging" in c || "accommodation" in c || "hostel" in c || "resort" in c -> "🏨"
            "museum" in c || "attraction" in c || "landmark" in c || "monument" in c || "historic" in c -> "🏛"
            "beach" in c -> "🏖"
            "park" in c || "garden" in c || "nature" in c -> "🌳"
            "school" in c || "university" in c || "college" in c || "education" in c -> "🎓"
            "gym" in c || "fitness" in c || "sport" in c -> "🏋"
            "beauty" in c || "hair" in c || "salon" in c || "barber" in c || "nail" in c || "spa" in c -> "💇"
            "bank" in c || "atm" in c || "financial" in c -> "🏦"
            "automotive" in c || "car_" in c || "auto_" in c || "tire" in c -> "🔧"
            "church" in c || "religious" in c || "place_of_worship" in c -> "⛪"
            "airport" in c -> "✈"
            "station" in c || "transit" in c || "bus" in c -> "🚉"
            "store" in c || "shop" in c || "boutique" in c || "retail" in c -> "🛍"
            else -> "📍"
        }
    }
}
