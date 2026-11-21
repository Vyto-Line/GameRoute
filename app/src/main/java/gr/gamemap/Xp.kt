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
        // prestige rank: +10 % XP for every rank (Bronze +10 % … Diamond +50 %), for good
        val boost = boostPct
        if (boost > 0) {
            val base = parts.sumOf { it.xp }
            val extra = base * boost / 100
            if (extra > 0) parts += Part("${rankName()} boost +$boost%", extra, "prestige rank")
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

    /** Before a title: the rank mark after a prestige ("◆ "), nothing before. */
    val stars: String get() = if (prestige > 0) "◆ " else ""

    /** The prestige ranks: each start-over moves you up one (Diamond from the fifth on). */
    val ranks = listOf("Bronze", "Silver", "Gold", "Platinum", "Diamond")
    val rankColors = intArrayOf(Color.rgb(205, 127, 50), Color.rgb(196, 204, 216), Color.rgb(255, 196, 40), Color.rgb(160, 215, 225), Color.rgb(120, 230, 255))

    /** "Bronze", … "Diamond" (blank before the first prestige). */
    fun rankName(p: Int = prestige): String = if (p <= 0) "" else ranks[(p - 1).coerceAtMost(4)]
    fun rankColor(p: Int = prestige): Int = if (p <= 0) 0 else rankColors[(p - 1).coerceAtMost(4)]

    /** Extra XP from the rank, in percent: +10 per rank, up to +50. */
    val boostPct: Int get() = (prestige.coerceAtMost(5)) * 10

    /** What the next prestige brings: the rank, its boost and its Garage rewards. */
    fun nextRank(): Triple<String, Int, List<Item>> {
        val p = (prestige + 1).coerceAtMost(5)
        return Triple(rankName(prestige + 1), p * 10, items.filter { it.rank == p })
    }

    // ------------------------------------------------------------------ titles

    private val ladders: Map<Theme, List<String>> = mapOf(
        Theme.OPEN_WORLD to listOf("Rookie", "Wanderer", "Explorer", "Pathfinder", "Road Scout", "Road Captain", "Free Spirit", "Trailmaster", "Road Veteran", "Living Legend", "Open Road Icon"),
        Theme.NEON_NOIR to listOf("Rookie", "Street Runner", "Night Rider", "Courier", "Chrome Ghost", "Fixer", "Neon Ace", "Grid Runner", "Neon King", "Road Legend", "City Icon"),
        Theme.NEON_RACER to listOf("Rookie", "Midnight Cruiser", "Street Racer", "Drift Spirit", "Turbo Ace", "Nitro Pilot", "Night Champion", "Midnight Phantom", "Midnight King", "Midnight Legend", "Midnight Icon"),
        Theme.BLOCKY to listOf("Newcomer", "Block Builder", "Miner", "Cave Explorer", "Architect", "Master Builder", "Engineer", "World Shaper", "Realm Crafter", "Cube Legend", "Block Icon"),
        Theme.PARCHMENT_QUEST to listOf("Squire", "Knight", "Knight Errant", "Lord", "Baron", "Viscount", "Count", "Duke", "Prince", "King", "Legend of the Realm"),
        Theme.LOOKOUT to listOf("Spotter", "Ranger", "Watchman", "Trail Warden", "Peak Scout", "Fire Captain", "Chief Ranger", "Forest Marshal", "Mountain Warden", "Guardian of the Hills", "Keeper of the Forest"),
        Theme.RETRO to listOf("Player 1", "Level Runner", "Coin Collector", "Power-Up", "Extra Life", "High Scorer", "Combo Master", "Boss Hunter", "Final Boss", "Hall of Fame", "Arcade Legend"),
        Theme.TRAILBLAZER to listOf("Trainee", "Tracker", "Scout", "Trailblazer", "Ranger", "Expedition Lead", "Cartographer", "Pioneer", "Master Explorer", "Legend of the Trail", "World Walker"),
        Theme.VIOLET_RAIN to listOf("Rookie", "Rain Runner", "Night Courier", "Neon Drifter", "Violet Phantom", "Storm Chaser", "Thunder Rider", "Midnight Oracle", "Rain King", "Legend of the Rain", "Violet Icon"),
        Theme.RALLY to listOf("Co-driver", "Privateer", "Club Racer", "Stage Hunter", "Gravel Ace", "Works Driver", "Team Leader", "Rally Ace", "Champion", "Rally Legend", "Rally Icon"),
        Theme.BLUEPRINT to listOf("Draftsman", "Surveyor", "Junior Planner", "Planner", "Engineer", "Senior Engineer", "Architect", "Lead Architect", "Chief Architect", "Master Planner", "City Founder"),
        Theme.NAUTICAL to listOf("Deckhand", "Bosun", "Helmsman", "Navigator", "Second Mate", "First Mate", "Captain", "Commodore", "Rear Admiral", "Admiral", "Fleet Admiral"),
        Theme.TOKYO_NIGHT to listOf("Rookie", "Night Walker", "Alley Runner", "Neon Courier", "Late Express", "Signal Chaser", "Midnight Local", "Night Owl", "City Lights", "Legend of the Night", "Tokyo Icon"),
        Theme.WATERCOLOUR to listOf("Sketcher", "Dabbler", "Painter", "Wanderer", "Landscape Painter", "Plein Air", "Colourist", "Master Painter", "Gallery Artist", "Living Masterpiece", "Watercolour Icon"),
        Theme.ARCTIC to listOf("Trekker", "Snow Scout", "Ice Walker", "Ice Ranger", "Glacier Guide", "Expedition Lead", "Polar Explorer", "Aurora Seeker", "North Star", "Legend of the North", "Polar Icon"),
    )
    /** The level each title starts at: every 5 levels, up to prestige at 50. */
    val titleLevels = intArrayOf(1, 5, 10, 15, 20, 25, 30, 35, 40, 45, 50)

    fun ladder(t: Theme): List<String> = ladders[t] ?: listOf("Rookie", "Driver", "Regular", "Pathfinder", "Navigator", "Road Captain", "Road Master", "Veteran", "Expert", "Road Legend", "Road Icon")

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
    class Item(val id: String, val kind: String, val name: String, val level: Int, val milestone: Boolean = false, val color: Int = 0, val rank: Int = 0) {
        val unlocked: Boolean get() = if (rank > 0) prestige >= rank else if (level >= 50) prestige > 0 || maxLevel >= 50 else maxLevel >= level
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
        // prestige rewards: an arrow and a route in each rank's colour, only by reaching the rank
        Item("arrow_bronze", "arrow", "Bronze arrow", 50, milestone = true, color = Color.rgb(205, 127, 50), rank = 1),
        Item("route_bronze", "route", "Bronze", 50, color = Color.rgb(205, 127, 50), rank = 1),
        Item("arrow_silver", "arrow", "Silver arrow", 50, milestone = true, color = Color.rgb(196, 204, 216), rank = 2),
        Item("route_silver", "route", "Silver", 50, color = Color.rgb(176, 186, 200), rank = 2),
        Item("arrow_goldrank", "arrow", "Gold arrow", 50, milestone = true, color = Color.rgb(255, 205, 60), rank = 3),
        Item("route_goldrank", "route", "Gold", 50, color = Color.rgb(240, 180, 20), rank = 3),
        Item("arrow_platinum", "arrow", "Platinum arrow", 50, milestone = true, color = Color.rgb(160, 215, 225), rank = 4),
        Item("route_platinum", "route", "Platinum", 50, color = Color.rgb(120, 190, 205), rank = 4),
        Item("arrow_diamond", "arrow", "Diamond arrow", 50, milestone = true, color = Color.rgb(120, 230, 255), rank = 5),
        Item("route_diamond", "route", "Diamond", 50, color = Color.rgb(60, 210, 255), rank = 5),
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
    fun unlockedAt(level: Int): List<Item> = items.filter { it.level == level && it.rank == 0 }

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
    fun badge(): String = (if (prestige > 0) "◆ ${rankName()} · " else "") + "Lv ${Explorer.level} · ${title()}"
}
