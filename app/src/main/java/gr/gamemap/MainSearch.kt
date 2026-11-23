@file:Suppress("unused")

package gr.gamemap
import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.util.concurrent.Executors
import kotlin.math.abs

// Part of MainActivity, moved out in the clean-up of 23 Nov 2026 (see tools/split_main.py).

internal fun MainActivity.buildSearch() {
    val p = (12 * d).toInt()
    search = EditText(this).apply {
        hint = "Where to?"
        setSingleLine()
        textSize = 17f
        background = null
        imeOptions = EditorInfo.IME_ACTION_SEARCH
        setPadding(p / 2, p, p / 2, p)
        setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEARCH) { runQuery(text.toString(), full = true); true } else false
        }
        setOnFocusChangeListener { _, has -> if (has) showSuggestions(); refreshChrome() }
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                clearBtn.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                val q = s?.toString().orEmpty()
                main.removeCallbacksAndMessages(SEARCH_TOKEN)
                if (q.trim().length < 2) { if (search.hasFocus()) showRecents() }
                else main.postAtTime({ runQuery(q, full = false) }, SEARCH_TOKEN, android.os.SystemClock.uptimeMillis() + 550)
            }
        })
    }
    clearBtn = TextView(this).apply {
        text = "✕"; textSize = 18f; visibility = View.GONE
        setPadding(p, p, p, p)
        setOnClickListener { search.setText("") }
    }
    searchIcon = TextView(this).apply {
        text = "⌕"; textSize = 22f; gravity = Gravity.CENTER
        minWidth = (44 * d).toInt(); minHeight = (44 * d).toInt()
        setPadding(p / 2, 0, 0, 0)
        setOnClickListener { if (search.hasFocus() || suggestScroll.visibility == View.VISIBLE || pickFor != null) closeSearch() else search.requestFocus() }
    }
    menuBtn = TextView(this).apply {
        text = "☰"; textSize = 20f; gravity = Gravity.CENTER
        contentDescription = "Settings"
        setOnClickListener { showSettings() }
        layoutParams = LinearLayout.LayoutParams((46 * d).toInt(), (46 * d).toInt()).apply { marginEnd = (5 * d).toInt() }
    }
    peopleIcon = TextView(this).apply { text = "👥"; textSize = 19f; gravity = Gravity.CENTER }
    peopleBadge = TextView(this).apply {
        textSize = 10.5f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
        val q = (4 * d).toInt(); setPadding(q, 0, q, 0); minWidth = (19 * d).toInt(); visibility = View.GONE
    }
    peopleDot = View(this).apply { visibility = View.GONE }
    peopleBtn = android.widget.FrameLayout(this).apply {
        contentDescription = "Friends"
        setOnClickListener { openTogether() }
        addView(peopleIcon, android.widget.FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        addView(peopleBadge, android.widget.FrameLayout.LayoutParams(WRAP_CONTENT, (19 * d).toInt(), Gravity.END or Gravity.TOP))
        addView(peopleDot, android.widget.FrameLayout.LayoutParams((11 * d).toInt(), (11 * d).toInt(), Gravity.END or Gravity.BOTTOM).apply {
            rightMargin = (3 * d).toInt(); bottomMargin = (3 * d).toInt()
        })
        layoutParams = LinearLayout.LayoutParams((46 * d).toInt(), (46 * d).toInt()).apply { marginEnd = (5 * d).toInt() }
    }
    searchCard = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        elevation = 6 * d
        minimumHeight = (56 * d).toInt()
        addView(searchIcon)
        addView(search, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(clearBtn)
        // one button for everything that's yours (progress, Garage, friends, trips, settings)
        youBtn = YouRing(context).apply {
            contentDescription = L.t("You: progress, Garage, friends, trips and settings")
            setOnClickListener { openSheet("you") }
        }
        addView(youBtn, LinearLayout.LayoutParams((48 * d).toInt(), (48 * d).toInt()).apply { marginEnd = (5 * d).toInt() })
    }
    chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, (10 * d).toInt(), 0, (6 * d).toInt()) }
    chipsScroll = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        clipToPadding = false
        addView(chips)
    }
    suggestList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    suggestScroll = ScrollView(this).apply {
        visibility = View.GONE
        elevation = 6 * d
        addView(suggestList)
    }
    topArea = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(searchCard, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        locCard = LinearLayout(this@buildSearch).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE; elevation = 5 * d }
        addView(locCard, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = (8 * d).toInt() })
        dutyCard = LinearLayout(this@buildSearch).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE; elevation = 5 * d }
        addView(dutyCard, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = (8 * d).toInt() })
        addView(chipsScroll, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(suggestScroll, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = (8 * d).toInt() })
    }
}

internal fun MainActivity.renderChips() {
    chips.removeAllViews()
    val solid = opaque(panel)
    fun chip(text: String, style: String = "", onLong: (() -> Unit)? = null, onClick: () -> Unit): TextView {
        val v = TextView(this).apply {
            this.text = text
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            val p = (12 * d).toInt()
            setPadding(p + p / 4, (8 * d).toInt(), p + p / 4, (8 * d).toInt())
            minHeight = (40 * d).toInt()
            maxLines = 1
            elevation = 3 * d
            when (style) {
                // the convoy: always there, easy to spot on any map
                "convoy" -> { setTextColor(ink); background = bg(solid, accent, 2.2f, 12f, pill = true) }
                // in a convoy: filled, with how it's going
                "live" -> { setTextColor(onAccent(accent)); background = bg(accent, onAccent(accent).let { Color.argb(90, Color.red(it), Color.green(it), Color.blue(it)) }, 1f, 12f, pill = true) }
                "hot" -> { setTextColor(ink); background = bg(solid, readable(accent), 1.2f, 12f, pill = true) }
                else -> { setTextColor(ink); background = bg(solid, dim(80), 1f, 12f, pill = true) }
            }
            setOnClickListener { onClick() }
            if (onLong != null) setOnLongClickListener { onLong(); true }
        }
        chips.addView(v, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = (8 * d).toInt(); gravity = Gravity.CENTER_VERTICAL })
        return v
    }
    // ≡ More: always first, in the same place on every phone (fuel, parking, food, convoy, saved places)
    chips.addView(TextView(this).apply {
        text = "≡"; textSize = 20f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        setTextColor(readable(accent)); elevation = 3 * d
        background = bg(solid, dim(80), 1f, 20f, pill = true)
        contentDescription = L.t("More: fuel, parking, food, convoy, saved places")
        setOnClickListener { openSheet("more") }
        minHeight = (40 * d).toInt(); minWidth = (46 * d).toInt(); includeFontPadding = false
    }, LinearLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT).apply { marginEnd = (8 * d).toInt() })
    // the convoy, right after it: its live status while I'm in one
    if (Convoy.active) {
        val n = Convoy.members.values.count { it.state != "left" } + 1
        chip("👥 " + L.t("Convoy") + " · $n · " + L.t(Convoy.status()), "live") { openSheet("convoy") }.apply {
            contentDescription = L.t("Convoy, $n drivers, ${Convoy.status()}")
        }
    } else chip("👥 " + L.t("Convoy"), "convoy") { openSheet("convoy") }
    // a friend's convoy invite: time-sensitive, so it stays in the row
    if (Friends.ready && Friends.core.started) {
        val inv = Friends.core.invites.firstOrNull { it.convoy != Convoy.code }
        if (inv != null) chip("👥 " + L.t("${inv.from} invites you"), "hot") { openSheet("friends") }
    }
    // in a convoy but not on its route: one tap onto it
    if (Convoy.active && !Convoy.onConvoyRoute) Convoy.destination?.let { d0 ->
        chip("➤ " + L.t("Join route") + " · ${d0.name}", "hot") { NavEngine.start(this, d0); MapState.recenter() }
    }
    val homeP = Places.saved("home"); val workP = Places.saved("work")
    for (k in listOf("home", "work")) {
        val p = if (k == "home") homeP else workP
        val name = if (k == "home") "🏠 Home" else "💼 Work"
        val eta = p?.let { Places.cachedEta(it) }?.let { " · ${NavEngine.formatDuration(it.seconds.toDouble())}" } ?: ""
        chip(if (p == null) "$name  +" else name + eta, onLong = { startPicking(k) }) {
            if (p == null) startPicking(k) else NavEngine.plan(this, p)
        }
    }
    // the two places you go to most at this time of day (the rest are in More)
    for (pl in frequentPlaces(2)) {
        val eta = Places.cachedEta(pl)?.let { " · ${NavEngine.formatDuration(it.seconds.toDouble())}" } ?: ""
        val lab = Places.labelled().firstOrNull { Geo.distance(it.place().pos, pl.pos) < 60 }
        chip("${lab?.icon ?: pl.icon.takeIf { it != "🕘" } ?: "★"} ${lab?.label ?: pl.name}$eta", onLong = { showSaveDialog(pl) {} }) { NavEngine.plan(this, pl) }
    }
}

/** A colour without its see-through part (chips must stay readable over any map). */
internal fun MainActivity.opaque(c: Int) = Color.rgb(Color.red(c), Color.green(c), Color.blue(c))

/**
 * The places you go to most around this time (±2 h, weekdays apart from weekends), from your
 * trips; saved places with a label first if there's no history yet. Never Home or Work (they
 * have their own chips).
 */
internal fun MainActivity.frequentPlaces(n: Int): List<Place> {
    val home = Places.saved("home"); val work = Places.saved("work")
    fun isHomeWork(p: Place) = listOfNotNull(home, work).any { Geo.distance(it.pos, p.pos) < 120 }
    val known = (Places.labelled().map { it.place() } + Places.savedPlaces() + Places.recents()).filterNot { isHomeWork(it) }
    if (known.isEmpty()) return emptyList()
    val now = java.util.Calendar.getInstance()
    val hourNow = now.get(java.util.Calendar.HOUR_OF_DAY) + now.get(java.util.Calendar.MINUTE) / 60.0
    val weekendNow = now.get(java.util.Calendar.DAY_OF_WEEK).let { it == java.util.Calendar.SATURDAY || it == java.util.Calendar.SUNDAY }
    val score = HashMap<Place, Double>()
    val cal = java.util.Calendar.getInstance()
    val nowMs = System.currentTimeMillis()
    for (t in Explorer.history()) {
        val pl = known.firstOrNull { it.name.equals(t.place, ignoreCase = true) } ?: continue
        cal.timeInMillis = t.arrivedAt
        val h = cal.get(java.util.Calendar.HOUR_OF_DAY) + cal.get(java.util.Calendar.MINUTE) / 60.0
        var dh = kotlin.math.abs(h - hourNow); if (dh > 12) dh = 24 - dh
        val weekend = cal.get(java.util.Calendar.DAY_OF_WEEK).let { it == java.util.Calendar.SATURDAY || it == java.util.Calendar.SUNDAY }
        val days = (nowMs - t.arrivedAt) / 86_400_000.0
        var w = when { dh <= 1 -> 3.0; dh <= 2 -> 2.0; dh <= 4 -> 0.6; else -> 0.2 }
        if (weekend == weekendNow) w *= 1.5
        w *= kotlin.math.exp(-days / 45.0) // recent habits count more
        score[pl] = (score[pl] ?: 0.0) + w
    }
    val ranked = score.entries.sortedByDescending { it.value }.map { it.key }.toMutableList()
    // nothing learned yet: your labelled places
    for (p in Places.labelled().map { it.place() }) if (ranked.size < n && !isHomeWork(p) && ranked.none { Geo.distance(it.pos, p.pos) < 60 }) ranked += p
    val out = ArrayList<Place>()
    for (p in ranked) if (out.size < n && out.none { Geo.distance(it.pos, p.pos) < 60 }) out += p
    return out
}

/** ≡ More: what used to crowd the chip row. */
internal fun MainActivity.renderMore() {
    val dutyN = LocationTracker.position?.let { Duties.onDutyNear(it, 5000.0).size } ?: 0
    val key = "${theme.name}|${MapState.dark}|${Convoy.active}|$dutyN|${Places.labelled().joinToString { it.label.orEmpty() }}|${Places.saved("home")?.name}|${Places.saved("work")?.name}"
    if (key == moreKey && sheetHost.childCount > 0) return
    moreKey = key
    sheetHost.removeAllViews()
    sheetHost.background = bg(panel, radius = 22f, topOnly = true)
    sheetHost.addView(sheetHeader("More"))
    val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    sheetHost.addView(SheetScroll(0.72).apply { addView(body) }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    val p = (12 * d).toInt()
    fun section(t: String) = body.addView(label(t, 12f, bold = true).apply { setTextColor(dim()); letterSpacing = 0.08f; setPadding(p / 4, p, 0, p / 2) })
    /** [hot]: that tile's title gets the violet look and [hotSub] under it (pharmacies on duty). */
    fun tiles(vararg items: Triple<String, String, () -> Unit>, hot: String? = null, hotSub: String? = null) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val small = items.size >= 4
        items.forEachIndexed { i, (icon, title, act) ->
            val isHot = title == hot && hotSub != null
            val moon = readable(PoiLayer.MOON)
            row.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(if (small) p * 3 / 4 else p, p, p / 2, p)
                background = if (isHot) bg(Color.argb(40, 177, 151, 252), moon, 2f, 14f) else bg(dim(22), dim(60), 1f, 14f)
                isClickable = true
                setOnClickListener { act() }
                addView(label(icon, 22f).apply { setTextColor(readable(accent)) }, LinearLayout.LayoutParams((30 * d).toInt(), (30 * d).toInt()))
                addView(label(title, if (small) 14f else 15f, bold = true).apply { setTextColor(ink); setPadding(0, p / 3, 0, 0); maxLines = 2 })
                if (isHot) addView(label(hotSub!!, 12f, bold = true).apply { setTextColor(moon); maxLines = 2 })
            }, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { if (i < items.size - 1) marginEnd = (if (small) p / 2 else p * 2 / 3) })
        }
        body.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }
    section("NEARBY")
    tiles(
        Triple("⛽", "Fuel") { closeSheet(); showFuel() },
        Triple("🅿", "Parking") { closeSheet(); showNearby("PARKING NEARBY", listOf("parking")) },
        Triple("🍽", "Food") { closeSheet(); showNearby("FOOD NEARBY", listOf("restaurant", "fast_food", "cafe")) },
        Triple("💊", "Pharmacy") { closeSheet(); showPharmacies() },
        hot = "Pharmacy", hotSub = if (dutyN > 0) "🌙 " + L.t(if (dutyN == 1) "1 on duty" else "$dutyN on duty") else null,
    )
    section("TOGETHER")
    if (Convoy.active) tiles(Triple("👥", "Your convoy") { openSheet("convoy") }, Triple("🙂", "Friends") { openSheet("friends") })
    else tiles(
        Triple("👥", "Start a convoy") { convoyJoinOpen = false; openSheet("convoy") },
        Triple("▦", "Join with a code") { convoyJoinOpen = true; openSheet("convoy") },
        Triple("🙂", "Friends") { openSheet("friends") },
    )
    section("SAVED")
    val saved = listOfNotNull(Places.saved("home")?.let { "🏠" to it }, Places.saved("work")?.let { "💼" to it }) +
        Places.labelled().map { (it.icon ?: "★") to it.place() }
    if (saved.isEmpty()) body.addView(label("Nothing saved yet. Find a place, then tap Save on its card.", 14f).apply { setTextColor(dim()); setPadding(p / 4, 0, 0, p / 2) })
    for ((icon, pl) in saved) body.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(p / 2, p * 2 / 3, p / 2, p * 2 / 3); isClickable = true
        setOnClickListener { closeSheet(); NavEngine.plan(this@renderMore, pl) }
        addView(label(icon, 18f).apply { gravity = Gravity.CENTER; setTextColor(readable(accent)) }, LinearLayout.LayoutParams((26 * d).toInt(), (26 * d).toInt()).apply { marginEnd = p })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(pl.name, 16f, bold = true).apply { setTextColor(ink); maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            val sub = listOfNotNull(pl.address.split(",").firstOrNull()?.takeIf { it.isNotBlank() },
                LocationTracker.position?.let { NavEngine.formatDistance(Geo.distance(it, pl.pos)) }).joinToString(" · ")
            if (sub.isNotBlank()) addView(label(sub, 13f).apply { setTextColor(dim()); maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(label("›", 20f).apply { setTextColor(dim()) })
    })
    body.addView(pill("＋  Add a place") {
        closeSheet(); search.requestFocus()
        Toast.makeText(this, L.t("Search for it, then tap Save on its card"), Toast.LENGTH_LONG).show()
    }.apply { textSize = 15f; setTextColor(readable(accent)) }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = p / 3 })
}

internal fun MainActivity.startPicking(key: String) {
    pickFor = key
    search.hint = when (key) { "home" -> "Search for your home address"; "work" -> "Search for your work address"; else -> "Where is the convoy going?" }
    search.setText("")
    search.requestFocus()
    getSystemService(InputMethodManager::class.java).showSoftInput(search, 0)
    showRecents()
    refreshChrome()
}

internal fun MainActivity.showNearby(title: String, types: List<String>) {
    val near = LocationTracker.position
    if (near == null) { Toast.makeText(this, L.t("Waiting for your location…"), Toast.LENGTH_SHORT).show(); return }
    val seq = ++querySeq
    lastResults = emptyList()
    renderResults(emptyList(), "Searching nearby…", header = title, force = true)
    io.execute {
        val local = try { Places.nearbyLocal(types, near) } catch (e: Exception) { emptyList<Place>() }
        if (local.isNotEmpty()) runOnUiThread {
            if (seq == querySeq && !isFinishing) { lastResults = local; renderResults(local, null, header = title, force = true) }
        }
        val r = try { Places.nearbyFull(types, near, local) } catch (e: Exception) { null }
        runOnUiThread {
            if (seq != querySeq || isFinishing) return@runOnUiThread
            lastResults = r ?: emptyList()
            renderResults(lastResults, when {
                r == null -> "No connection: check mobile data"
                r.isEmpty() -> "Nothing found nearby"
                else -> null
            }, header = title, force = true)
        }
    }
}
