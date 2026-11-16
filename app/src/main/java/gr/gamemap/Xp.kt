package gr.gamemap

import android.content.Context
import android.graphics.Color
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.Calendar

/**
 * XP and levels: what a trip earns (distance, a clean drive, new roads, convoys, a race place,
 * the daily streak, weekly challenges), the titles of each style, what each level unlocks in the
 * Garage, and prestige. Never for speed: a race place only counts after a clean drive.
 */
object Xp {
    private lateinit var appContext: Context
    private fun prefs() = appContext.getSharedPreferences("gamemap", Context.MODE_PRIVATE)
    val ready get() = ::appContext.isInitialized
    val changed = Signal()

    fun init(ctx: Context) { appContext = ctx.applicationContext }

    // ------------------------------------------------------------------ during a trip

    @Volatile private var newMeters = 0.0
    @Volatile private var overSince = 0L
    @Volatile var clean = true
        private set
    private var lastFix: LatLng? = null

    fun startTrip() { newMeters = 0.0; overSince = 0L; clean = true; lastFix = null }

    /**
     * A GPS fix while on a route: new roads (never driven before) and speeding (more than 10%
     * + 3 km/h over the limit for 15 s in a row means it wasn't a clean drive).
     */
    fun onFix(pos: LatLng, speedMps: Float, limitKmh: Int?, real: Boolean) {
        if (!ready || !real) { lastFix = null; return }
        val last = lastFix
        lastFix = pos
        if (last != null) {
            val step = Geo.distance(last, pos)
            if (step in 1.0..300.0 && markDriven(pos)) newMeters += step
        }
        val kmh = speedMps * 3.6f
        val now = System.currentTimeMillis()
        if (limitKmh != null && limitKmh > 0 && kmh > limitKmh * 1.1f + 3f) {
            if (overSince == 0L) overSince = now
            else if (now - overSince > 15_000) clean = false
        } else overSince = 0L
    }

    // the roads driven, as ~50 m squares, on the phone only
    private var cells: HashSet<Long>? = null
    private var cellsDirty = 0
    private fun cellFile() = File(appContext.filesDir, "driven.bin")
    private fun cellOf(p: LatLng): Long = ((p.lat * 2000).toLong() shl 32) or (((p.lon * 2000).toLong()) and 0xffffffffL)

    @Synchronized private fun loadCells(): HashSet<Long> {
        cells?.let { return it }
        val s = HashSet<Long>()
        try { DataInputStream(cellFile().inputStream().buffered()).use { val n = it.readInt(); repeat(n) { _ -> s += it.readLong() } } } catch (_: Exception) {}
        cells = s
        return s
    }

    @Synchronized private fun saveCells() {
        val s = cells ?: return
        try {
            val tmp = File(appContext.filesDir, "driven.tmp")
            DataOutputStream(tmp.outputStream().buffered()).use { o -> o.writeInt(s.size); for (c in s) o.writeLong(c) }
            tmp.renameTo(cellFile())
            cellsDirty = 0
        } catch (_: Exception) {}
    }

    /** True if this square wasn't driven before (and remembers it). */
    private fun markDriven(p: LatLng): Boolean {
        val s = loadCells()
        val added = synchronized(this) { s.add(cellOf(p)) }
        if (added && ++cellsDirty >= 50) saveCells()
        return added
    }

    // ------------------------------------------------------------------ the end of a trip

    /** One line of what a trip earned. */
    class Part(val label: String, val xp: Int, val note: String = "")

    /**
     * Everything a finished trip earns, added up and stored. [convoyDone]: arrived together on
     * the convoy's route; [place] / [of]: the race result (only counts after a clean drive).
     */
    fun finish(meters: Double, offerBonus: Int, landmarks: Int, convoyDone: Boolean, place: Int?, of: Int): List<Part> {
        if (!ready) return emptyList()
        saveCells()
        val parts = ArrayList<Part>()
        val base = 10 + (meters / 1000 * 5).toInt()
        parts += Part("Trip", base, NavEngine.formatDistance(meters))
        if (clean) parts += Part("Clean drive", (base * 0.2).toInt().coerceAtLeast(2), "no speeding stretches")
        val newXp = (newMeters / 100 * 2).toInt()
        if (newXp > 0) parts += Part("New roads", newXp, NavEngine.formatDistance(newMeters) + " never driven")
        if (landmarks > 0) parts += Part(if (landmarks == 1) "Landmark" else "Landmarks", landmarks * 25, "$landmarks new")
        if (offerBonus > 0) parts += Part("Faster route taken", offerBonus)
        if (convoyDone) parts += Part("Convoy finished", 30, "together")
        if (place != null && of >= 2 && place <= 3) {
            if (clean) parts += Part("Race · ${ordinal(place)} place", when (place) { 1 -> 50; 2 -> 30; else -> 15 }, "kept to the limits")
            else parts += Part("Race · ${ordinal(place)} place", 0, "no bonus: too fast for too long")
        }
        // the daily streak: the first trip of the day
        val today = dayNumber(System.currentTimeMillis())
        val lastDay = prefs().getInt("xpDay", -1)
        if (lastDay != today) {
            val streak = if (lastDay == today - 1) prefs().getInt("xpStreak", 0) + 1 else 1
            prefs().edit().putInt("xpDay", today).putInt("xpStreak", streak).apply()
            parts += Part("Daily streak", minOf(10 * streak, 50), "day $streak")
        }
        // weekly challenges
        rollWeek()
        if (newMeters >= 300) bump("roads")
        if (convoyDone) bump("convoy")
        if (clean) bump("clean")
        for (c in challenges()) if (c.done && !prefs().getBoolean("xpPaid_${c.key}", false)) {
            prefs().edit().putBoolean("xpPaid_${c.key}", true).apply()
            parts += Part("Weekly: ${c.title}", c.reward, "challenge done")
        }
        val total = parts.sumOf { it.xp }
        Explorer.addXp(total - landmarks * 25) // landmarks were added when found
        val lv = Explorer.level
        if (lv > maxLevel) prefs().edit().putInt("xpMaxLevel", lv).apply()
        changed.fire()
        return parts
    }

    private fun ordinal(n: Int) = when (n) { 1 -> "1st"; 2 -> "2nd"; 3 -> "3rd"; else -> "${n}th" }

    private fun dayNumber(ms: Long): Int {
        val c = Calendar.getInstance().apply { timeInMillis = ms }
        return c.get(Calendar.YEAR) * 400 + c.get(Calendar.DAY_OF_YEAR)
    }

    val streak: Int get() {
        if (!ready) return 0
        val today = dayNumber(System.currentTimeMillis())
        val last = prefs().getInt("xpDay", -1)
        return if (last == today || last == today - 1) prefs().getInt("xpStreak", 0) else 0
    }

    // ------------------------------------------------------------------ weekly challenges

    class Challenge(val key: String, val title: String, val have: Int, val of: Int, val reward: Int) {
        val done get() = have >= of
    }

    private fun weekId(): Int {
        val c = Calendar.getInstance().apply { firstDayOfWeek = Calendar.MONDAY; minimalDaysInFirstWeek = 4 }
        return c.get(Calendar.YEAR) * 100 + c.get(Calendar.WEEK_OF_YEAR)
    }

    private fun rollWeek() {
        val w = weekId()
        if (prefs().getInt("xpWeek", 0) == w) return
        prefs().edit().putInt("xpWeek", w).putInt("xpW_roads", 0).putInt("xpW_convoy", 0).putInt("xpW_clean", 0)
            .putBoolean("xpPaid_roads", false).putBoolean("xpPaid_convoy", false).putBoolean("xpPaid_clean", false).apply()
    }

    private fun bump(k: String) { prefs().edit().putInt("xpW_$k", prefs().getInt("xpW_$k", 0) + 1).apply() }

    fun challenges(): List<Challenge> {
        if (!ready) return emptyList()
        rollWeek()
        return listOf(
            Challenge("roads", "Drive 3 new roads", prefs().getInt("xpW_roads", 0).coerceAtMost(3), 3, 150),
            Challenge("convoy", "Finish a convoy", prefs().getInt("xpW_convoy", 0).coerceAtMost(1), 1, 100),
            Challenge("clean", "5 clean drives", prefs().getInt("xpW_clean", 0).coerceAtMost(5), 5, 200),
        )
    }

    // ------------------------------------------------------------------ prestige and unlocks

    /** How many times you started over at level 1 with a star. */
    val prestige: Int get() = if (ready) prefs().getInt("xpPrestige", 0) else 0
    /** The highest level ever reached (unlocks stay after a prestige). */
    val maxLevel: Int get() = if (ready) maxOf(prefs().getInt("xpMaxLevel", 1), Explorer.level) else Explorer.level
    val canPrestige: Boolean get() = Explorer.level >= 50

    fun doPrestige() {
        if (!canPrestige) return
        prefs().edit().putInt("xpMaxLevel", maxLevel).putInt("xpPrestige", prestige + 1).putInt("xp", 0).apply()
        Explorer.changed.fire(); changed.fire()
    }

    val stars: String get() = "★".repeat(prestige.coerceAtMost(5))

    // ------------------------------------------------------------------ titles

    private val ladders: Map<Theme, List<String>> = mapOf(
        Theme.OPEN_WORLD to listOf("Rookie", "Wanderer", "Pathfinder", "Road Captain", "Free Spirit", "Living Legend"),
        Theme.NEON_NOIR to listOf("Rookie", "Street Runner", "Night Rider", "Chrome Ghost", "Neon King", "Road Legend"),
        Theme.NEON_RACER to listOf("Rookie", "Midnight Cruiser", "Drift Spirit", "Turbo Ace", "Night Champion", "Midnight Legend"),
        Theme.BLOCKY to listOf("Newcomer", "Block Builder", "Cave Explorer", "Master Builder", "World Shaper", "Cube Legend"),
        Theme.PARCHMENT_QUEST to listOf("Squire", "Knight", "Lord", "Baron", "Duke", "Legend of the Realm"),
        Theme.LOOKOUT to listOf("Spotter", "Ranger", "Watchman", "Trail Warden", "Chief Ranger", "Guardian of the Hills"),
        Theme.RETRO to listOf("Player 1", "Level Runner", "Power-Up", "High Scorer", "Final Boss", "Hall of Fame"),
        Theme.TRAILBLAZER to listOf("Trainee", "Tracker", "Trailblazer", "Expedition Lead", "Master Explorer", "Legend of the Trail"),
        Theme.VIOLET_RAIN to listOf("Rookie", "Rain Runner", "Neon Drifter", "Violet Phantom", "Storm Chaser", "Legend of the Rain"),
        Theme.RALLY to listOf("Co-driver", "Privateer", "Stage Hunter", "Works Driver", "Rally Ace", "Rally Legend"),
        Theme.BLUEPRINT to listOf("Draftsman", "Surveyor", "Planner", "Engineer", "Chief Architect", "Master Planner"),
        Theme.NAUTICAL to listOf("Deckhand", "Bosun", "Navigator", "First Mate", "Captain", "Admiral"),
        Theme.ARCTIC to listOf("Trekker", "Snow Scout", "Ice Ranger", "Expedition Lead", "Polar Explorer", "Legend of the North"),
    )
    /** The level each title starts at. */
    val titleLevels = intArrayOf(1, 4, 8, 15, 25, 40)

    fun ladder(t: Theme): List<String> = ladders[t] ?: listOf("Rookie", "Driver", "Pathfinder", "Road Captain", "Road Master", "Road Legend")

    fun title(t: Theme = MapState.theme, level: Int = Explorer.level): String {
        val l = ladder(t)
        var i = 0
        while (i + 1 < titleLevels.size && level >= titleLevels[i + 1]) i++
        return l[i]
    }

    /** The next title and its level, or null at the top. */
    fun nextTitle(t: Theme = MapState.theme, level: Int = Explorer.level): Pair<String, Int>? {
        val i = titleLevels.indexOfFirst { it > level }
        return if (i < 0) null else ladder(t)[i] to titleLevels[i]
    }

    // ------------------------------------------------------------------ the Garage

    /** Something to unlock: [kind] arrow, route, flag or arrival. */
    class Item(val id: String, val kind: String, val name: String, val level: Int, val milestone: Boolean = false, val color: Int = 0) {
        val unlocked: Boolean get() = if (level >= 50) prestige > 0 || maxLevel >= 50 else maxLevel >= level
    }

    val items: List<Item> = listOf(
        Item("arrow_classic", "arrow", "Classic", 1),
        Item("arrow_chevron", "arrow", "Chevron", 3),
        Item("arrow_dart", "arrow", "Dart", 9),
        Item("arrow_comet", "arrow", "Shield", 10, milestone = true),
        Item("arrow_delta", "arrow", "Delta", 18),
        Item("arrow_gold", "arrow", "Gold", 20, milestone = true, color = Color.rgb(255, 196, 40)),
        Item("arrow_star", "arrow", "Prestige star", 50, milestone = true, color = Color.rgb(185, 140, 255)),
        Item("route_style", "route", "The style's own", 1),
        Item("route_electric", "route", "Electric blue", 4, color = Color.rgb(0, 176, 255)),
        Item("route_sunset", "route", "Sunset orange", 12, color = Color.rgb(255, 112, 40)),
        Item("route_mint", "route", "Mint", 25, color = Color.rgb(20, 214, 150)),
        Item("route_royal", "route", "Royal violet", 35, color = Color.rgb(140, 90, 255)),
        Item("flag_pin", "flag", "Pin", 1),
        Item("flag_checkered", "flag", "Checkered flag", 8),
        Item("flag_pennant", "flag", "Pennant", 14),
        Item("flag_trophy", "flag", "Trophy", 30, milestone = true),
        Item("arrival_classic", "arrival", "Classic", 1),
        Item("arrival_confetti", "arrival", "Confetti", 7),
        Item("arrival_fireworks", "arrival", "Fireworks", 16),
        Item("arrival_lights", "arrival", "Light show", 40, milestone = true),
    )

    fun kindItems(kind: String) = items.filter { it.kind == kind }

    /** What's in use for each kind (per phone, all styles). */
    fun chosen(kind: String): Item {
        val id = if (ready) prefs().getString("garage_$kind", null) else null
        return items.firstOrNull { it.id == id && it.unlocked } ?: kindItems(kind).first()
    }

    fun choose(item: Item) {
        if (!ready || !item.unlocked) return
        prefs().edit().putString("garage_${item.kind}", item.id).apply()
        version++
        changed.fire()
    }

    /** Goes up with every Garage change (the map redraws its route and arrow). */
    @Volatile var version = 0
        private set

    /** What a level unlocks (for the level-up card). */
    fun unlockedAt(level: Int): List<Item> = items.filter { it.level == level }

    /** The next unlock, for the progress bar ("unlocks the Checkered flag"). */
    fun nextUnlock(level: Int = maxLevel): Item? = items.filter { it.level > level && it.level < 50 }.minByOrNull { it.level }

    /** Contrast of two colours (1..21): route colours must stay readable on every style. */
    fun contrast(a: Int, b: Int): Double {
        fun lum(c: Int): Double {
            fun ch(v: Int): Double { val s = v / 255.0; return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4) }
            return 0.2126 * ch(Color.red(c)) + 0.7152 * ch(Color.green(c)) + 0.0722 * ch(Color.blue(c))
        }
        val l1 = lum(a); val l2 = lum(b)
        return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
    }

    /** A route colour can be used on this map (light or dark) only if it stands out from the land. */
    fun readableOn(item: Item, look: Look): Boolean = item.color == 0 || contrast(item.color, look.style.land) >= 3.0

    /** The route colour to draw: the Garage's choice if it's readable on this look, else the style's. */
    fun routeColor(look: Look): Int {
        val c = chosen("route")
        return if (c.color != 0 && readableOn(c, look)) c.color else look.route
    }

    fun routeGlow(look: Look): Int {
        val c = chosen("route")
        return if (c.color != 0 && readableOn(c, look)) Color.argb(Color.alpha(look.routeGlow), Color.red(c.color), Color.green(c.color), Color.blue(c.color)) else look.routeGlow
    }

    // ------------------------------------------------------------------ friends see my level (if I say so)

    var showToFriends: Boolean
        get() = ready && prefs().getBoolean("xpShowFriends", false)
        set(v) { if (ready) { prefs().edit().putBoolean("xpShowFriends", v).apply(); changed.fire() } }

    /** "Lv 12 · Night Rider" (with stars after a prestige), for others to see. */
    fun badge(): String = "${stars}Lv ${Explorer.level} · ${title()}"
}
