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
import gr.gamemap.MainActivity.GameDialog
import gr.gamemap.MainActivity.GameBuilder
import gr.gamemap.MainActivity.SettingsGroup
import gr.gamemap.MainActivity.YouRing
import gr.gamemap.MainActivity.AvatarView
import gr.gamemap.MainActivity.SheetScroll
import gr.gamemap.MainActivity.GarageSwatch
import gr.gamemap.MainActivity.ArrivalFx
import gr.gamemap.MainActivity.Companion.SEARCH_TOKEN

// Part of MainActivity, moved out in the clean-up of 23 Nov 2026 (see tools/split_main.py).

internal fun MainActivity.sheetTitle(kind: String) = when (kind) {
    "you" -> "You"; "settings" -> "Settings"; "more" -> "More"; "convoy", "friends", "addfriend" -> "Together"
    "progress" -> "Progress"; "garage" -> "Garage"; "trips" -> "Trips"; "recap" -> "Your recap"; "style" -> "Map style"; "offline" -> "Offline maps"
    "convoyinvite" -> "Invite"; else -> "Back"
}

/** Back one step (to the sheet this one came from), or closed if it was the first. */
internal fun MainActivity.sheetBack() {
    if (sheetKind == "settings" && settingsPage != null) { settingsPage = null; refreshChrome(); return }
    val prev = sheetStack.removeLastOrNull() ?: run { closeSheet(); return }
    sheetNoPush = true
    try { openSheet(prev) } finally { sheetNoPush = false }
}

/** The same sheet group (Together's two tabs): no step back in between. */
internal fun MainActivity.switchSheet(kind: String) { sheetNoPush = true; try { openSheet(kind) } finally { sheetNoPush = false } }

internal fun MainActivity.openSheet(kind: String) {
    // opened from another sheet: remember it, for the back button
    if (!sheetNoPush && sheetOverlay.visibility == View.VISIBLE && sheetKind.isNotEmpty() && sheetKind != kind) {
        if (sheetStack.lastOrNull() != sheetKind) sheetStack += sheetKind
        if (sheetStack.size > 6) sheetStack.removeAt(0)
    }
    if (sheetOverlay.visibility != View.VISIBLE) sheetStack.clear()
    if (kind != sheetKind) { settingsPage = null; settingsQuery = "" }
    if (kind != sheetKind) styleScroll = 0
    // a sheet opened from another sheet (e.g. Settings › Convoy) must draw itself, not keep the old one's views
    quickKey = ""; convoyKey = ""; offlineKey = ""; tripsKey = ""; friendsKey = ""; moreKey = ""; inviteKey = ""; progressKey = ""; garageKey = ""; youKey = ""; styleKey = ""; settingsKey = ""; reportKey = ""
    if ((kind == "friends" || kind == "addfriend") && Friends.ready && Friends.core.started) Friends.kick()
    sheetKind = kind
    if (sheetOverlay.visibility != View.VISIBLE) {
        // the sheet slides up over a fading-in shade
        sheetOverlay.alpha = 0f; sheetOverlay.visibility = View.VISIBLE
        sheetOverlay.animate().alpha(1f).setDuration(animMs(160)).start()
        sheetHost.translationY = 60 * d
        sheetHost.animate().translationY(0f).setDuration(animMs(240)).setInterpolator(android.view.animation.DecelerateInterpolator(1.8f)).start()
    }
    refreshChrome()
    if (kind == "fuelfilters") renderFuelFilters()
}

internal fun MainActivity.closeSheet() {
    sheetStack.clear()
    sheetOverlay.visibility = View.GONE
    sheetKind = ""
    sheetHost.removeAllViews()
}

/** "‹ You": back to where this sheet was opened from. */
internal fun MainActivity.backPill(to: String, onClick: () -> Unit) = TextView(this).apply {
    text = "‹ " + L.t(to); textSize = 15f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; maxLines = 1
    setTextColor(readable(accent)); background = bg(dim(26), radius = 12f)
    val q = (10 * d).toInt(); setPadding(q, q / 2, q + q / 3, q / 2); minHeight = (40 * d).toInt()
    contentDescription = L.t("Back to $to")
    setOnClickListener { onClick() }
}

/** A sheet's title, centred, with the way back on the left and ✕ on the right. */
internal fun MainActivity.sheetHeader(title: String, back: Pair<String, () -> Unit>? = sheetStack.lastOrNull()?.let { from -> sheetTitle(from) to { sheetBack() } }) = FrameLayout(this).apply {
    val side = if (back != null) (104 * d).toInt() else (52 * d).toInt()
    addView(label(title, 22f, bold = true).apply {
        setTextColor(ink); typeface = theme.typeface; gravity = Gravity.CENTER; maxLines = 1
        setPadding(side, 0, side, 0)
        // a long title next to the back button shrinks to fit instead of being cut
        setAutoSizeTextTypeUniformWithConfiguration(14, 22, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
    }, FrameLayout.LayoutParams(MATCH_PARENT, (44 * d * maxOf(1f, resources.configuration.fontScale)).toInt(), Gravity.CENTER_VERTICAL))
    back?.let { (to, go) -> addView(backPill(to, go), FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.START or Gravity.CENTER_VERTICAL)) }
    addView(pill("✕") { closeSheet() }.apply { setTextColor(ink) }, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL))
}

/** Shows a card with a short slide up and fade in (only when it wasn't showing already). */
internal fun MainActivity.appear(v: View, from: Float = 36f) {
    if (v.visibility == View.VISIBLE) return
    v.visibility = View.VISIBLE
    v.alpha = 0f; v.translationY = from * d
    v.animate().alpha(1f).translationY(0f).setDuration(animMs(200)).setInterpolator(android.view.animation.DecelerateInterpolator(1.6f)).start()
}

internal fun MainActivity.showStylePicker() = openSheet("style")

internal fun MainActivity.renderStylePicker() {
    // only when something it shows changed: rebuilt on every map or GPS change, the sheet
    // flickered on slower phones (all the previews laid out again, many times a second)
    val key = "${theme.name}|${MapState.theme}|${MapState.dark}|${MapState.lightMode}|${MapState.carTheme}|${L.greek}"
    if (key == styleKey && sheetHost.childCount > 0) return
    styleKey = key
    sheetHost.removeAllViews()
    sheetHost.background = bg(panel, radius = 24f, topOnly = true)
    sheetHost.addView(sheetHeader("Map style"))
    // Auto / Light / Dark
    val seg = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        val p = (4 * d).toInt(); setPadding(p, p, p, p)
        background = bg(Color.argb(30, Color.red(ink), Color.green(ink), Color.blue(ink)), radius = 14f)
    }
    for (m in MapState.LightMode.entries) {
        val sel = MapState.lightMode == m
        seg.addView(TextView(this).apply {
            text = when (m) { MapState.LightMode.AUTO -> "Auto"; MapState.LightMode.LIGHT -> "Light"; MapState.LightMode.DARK -> "Dark" }
            textSize = 15f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setTextColor(if (sel) onAccent(accent) else ink)
            if (sel) background = bg(accent, radius = 11f)
            setOnClickListener { MapState.setLightMode(m) }
        }, LinearLayout.LayoutParams(0, (42 * d).toInt(), 1f))
    }
    sheetHost.addView(seg, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = (8 * d).toInt() })
    if (MapState.lightMode == MapState.LightMode.AUTO) sheetHost.addView(label("Switches at sunrise and sunset", 13f).apply {
        setTextColor(dim()); setPadding((4 * d).toInt(), (6 * d).toInt(), 0, 0)
    })
    // theme grid, 3 per row: scrolls, so every style (and the car's style below) can be reached
    // on short screens and with big text
    val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    sheetHost.addView(SheetScroll(0.66).apply { addView(grid); post { scrollTo(0, styleScroll) }
        setOnScrollChangeListener { _, _, y, _, _ -> styleScroll = y } }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    val dark = MapState.dark
    var row: LinearLayout? = null
    Theme.entries.forEachIndexed { i, th ->
        if (i % 3 == 0) {
            row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            grid.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = (10 * d).toInt() })
        }
        val sel = th == MapState.theme
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (3 * d).toInt(); setPadding(p, p, p, p)
            background = bg(Color.argb(24, Color.red(ink), Color.green(ink), Color.blue(ink)), if (sel) accent else dim(50), if (sel) 3f else 1f, 14f)
            setOnClickListener { MapState.setTheme(th) }
        }
        val img = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            val id = resources.getIdentifier("thumb_${th.name.lowercase()}_${if (dark) "d" else "l"}", "drawable", packageName)
            if (id != 0) setImageResource(id) else setBackgroundColor(th.lookFor(dark).style.land)
            clipToOutline = true
        }
        card.addView(img, LinearLayout.LayoutParams(MATCH_PARENT, (88 * d).toInt()))
        card.addView(label(th.title, 12f, bold = true).apply {
            setTextColor(ink); setPadding((6 * d).toInt(), (6 * d).toInt(), (4 * d).toInt(), (4 * d).toInt()); maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        })
        row?.addView(card, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
            if (i % 3 != 2) marginEnd = (8 * d).toInt()
        })
    }
    // fill an incomplete last row so cards keep their width
    val rest = (3 - Theme.entries.size % 3) % 3
    repeat(rest) { row?.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)) }
    // car style
    grid.addView(settingRow("Android Auto style", MapState.carTheme?.title ?: "Same as phone") { chooseCarStyle() },
        LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = (12 * d).toInt(); bottomMargin = (12 * d).toInt() })
}

internal fun MainActivity.chooseCarStyle() {
    val names = arrayOf("Same as phone") + Theme.entries.map { it.title }.toTypedArray()
    val cur = MapState.carTheme?.let { it.ordinal + 1 } ?: 0
    GameBuilder(this@chooseCarStyle).setTitle("Android Auto style")
        .setSingleChoiceItems(names, cur) { dlg, i ->
            MapState.setCarTheme(if (i == 0) null else Theme.entries[i - 1]); dlg.dismiss()
        }.showT()
}

internal fun MainActivity.settingRow(title: String, value: String?, onClick: () -> Unit) = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    minimumHeight = (52 * d).toInt()
    val p = (14 * d).toInt(); setPadding(p, 0, p, 0)
    setOnClickListener { onClick() }
    // a long value goes under the title (side by side it squeezed the title to a letter a line)
    if (value != null && value.length > 24) {
        val p2 = (10 * d).toInt(); setPadding(p, p2, p, p2)
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(title, 15f).apply { setTextColor(ink) })
            addView(label(value, 13f).apply { setTextColor(dim()) })
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(label("›", 14f).apply { setTextColor(dim()) })
        return@apply
    }
    addView(label(title, 15f).apply { setTextColor(ink) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
    if (value != null) addView(label("$value  ›", 14f).apply { setTextColor(dim()) })
}

internal fun MainActivity.switchRow(title: String, on: Boolean, onChange: (Boolean) -> Unit) = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    minimumHeight = (52 * d).toInt()
    val p = (14 * d).toInt(); setPadding(p, 0, p / 2, 0)
    addView(label(title, 15f).apply { setTextColor(ink) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
    val sw = android.widget.Switch(context).apply {
        isChecked = on
        val onCol = Ui.onPanel(theme, accent, 3.0)
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        // off = plain grey, on = the theme's colour: easy to tell apart in every theme
        val lightP = Ui.luminance(panel) > 0.4
        val offThumb = if (lightP) Color.rgb(250, 250, 250) else Color.rgb(185, 185, 185)
        val offTrack = if (lightP) Color.argb(120, 0, 0, 0) else Color.argb(110, 255, 255, 255)
        thumbTintList = android.content.res.ColorStateList(states, intArrayOf(onCol, offThumb))
        trackTintList = android.content.res.ColorStateList(states, intArrayOf(
            Color.argb(150, Color.red(onCol), Color.green(onCol), Color.blue(onCol)), offTrack))
        setOnCheckedChangeListener { _, v -> onChange(v) }
    }
    addView(sw)
    setOnClickListener { sw.toggle() }
}

internal fun MainActivity.group(title: String, vararg rows: View): List<View> {
    val head = label(title.uppercase(), 12f, bold = true).apply {
        setTextColor(dim(170)); letterSpacing = 0.08f; setPadding((6 * d).toInt(), (14 * d).toInt(), 0, (6 * d).toInt())
        if (title.isBlank()) { text = ""; setPadding(0, (6 * d).toInt(), 0, 0) }
    }
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = bg(Color.argb(26, Color.red(ink), Color.green(ink), Color.blue(ink)), radius = 14f)
        rows.forEachIndexed { i, r ->
            if (i > 0) addView(View(context).apply { setBackgroundColor(dim(40)) }, LinearLayout.LayoutParams(MATCH_PARENT, 1))
            addView(r, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
    }
    return listOf(head, box)
}

internal fun MainActivity.renderReport() {
    val key = "${theme.name}|${MapState.dark}|${L.greek}"
    if (key == reportKey && sheetHost.childCount > 0) return
    reportKey = key
    sheetHost.removeAllViews()
    sheetHost.background = bg(panel, radius = 24f, topOnly = true)
    sheetHost.addView(sheetHeader("Report"))
    // marking a camera works without the shared database: it's kept on this phone
    sheetHost.addView(label("CAMERA HERE (saved on this phone)", 12f, bold = true).apply {
        setTextColor(dim()); letterSpacing = 0.06f; setPadding((4 * d).toInt(), (4 * d).toInt(), 0, (6 * d).toInt())
    })
    sheetHost.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        for ((k, glyph, title) in listOf(Triple("speed", "📷", "Speed camera"), Triple("redlight", "🚦", "Red-light camera"))) {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
                minimumHeight = (60 * d).toInt()
                background = bg(Color.argb(28, Color.red(ink), Color.green(ink), Color.blue(ink)), dim(60), 1f, 16f)
                addView(label(glyph, 24f)); addView(label("  $title", 15f, bold = true).apply { setTextColor(ink) })
                setOnClickListener {
                    val ok = Alerts.markCamera(k)
                    Toast.makeText(this@renderReport, L.t(if (ok) "$title marked: you'll be warned here" else "Waiting for your location"), Toast.LENGTH_SHORT).show()
                    if (ok) closeSheet()
                }
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { if (k == "speed") marginEnd = (10 * d).toInt() })
        }
    })
    // road reports shared with other drivers: off until GameRoute has its own server
    if (!Alerts.reportsReady) {
        sheetHost.addView(label("Marked cameras stay on this phone and warn you every time you pass.", 13f).apply {
            setTextColor(dim()); setPadding((4 * d).toInt(), (12 * d).toInt(), 0, (4 * d).toInt())
        })
        return
    }
    sheetHost.addView(label("DRIVER REPORTS", 12f, bold = true).apply {
        setTextColor(dim()); letterSpacing = 0.06f; setPadding((4 * d).toInt(), (16 * d).toInt(), 0, 0)
    })
    if (LocationTracker.position == null) {
        sheetHost.addView(label("Waiting for your location…", 15f).apply { setTextColor(dim()) })
    }
    var row: LinearLayout? = null
    Alerts.reportTypes.forEachIndexed { i, rt ->
        if (i % 3 == 0) {
            row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            sheetHost.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = (10 * d).toInt() })
        }
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            minimumHeight = (96 * d).toInt()
            background = bg(Color.argb(28, Color.red(ink), Color.green(ink), Color.blue(ink)), dim(60), 1f, 16f)
            setOnClickListener {
                Alerts.report(rt.key) { ok ->
                    Toast.makeText(this@renderReport, L.t(if (ok) "Thanks! ${rt.title} reported" else "Couldn't send the report: check internet"), Toast.LENGTH_SHORT).show()
                }
                closeSheet()
            }
        }
        tile.addView(label(rt.glyph, 30f).apply { gravity = Gravity.CENTER })
        tile.addView(label(rt.title, 14f, bold = true).apply { setTextColor(ink); gravity = Gravity.CENTER })
        row?.addView(tile, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { if (i % 3 != 2) marginEnd = (10 * d).toInt() })
    }
    sheetHost.addView(label("Reports show for other drivers for an hour, longer if they confirm them.", 13f).apply {
        setTextColor(dim()); setPadding((4 * d).toInt(), (12 * d).toInt(), 0, 0)
    })
}

/**
 * Everything the settings pages show, in one number: every saved setting (friends' busy data
 * left out: it changes every few minutes and isn't shown there) and the switches kept in memory.
 */
internal fun MainActivity.settingsState(): Int {
    var h = 17
    val dir = java.io.File(applicationInfo.dataDir, "shared_prefs")
    for (f in dir.list().orEmpty().sorted()) {
        val name = f.removeSuffix(".xml")
        if (name == "friends") continue
        h = h * 31 + getSharedPreferences(name, Context.MODE_PRIVATE).all.hashCode()
    }
    h = h * 31 + "${Voice.muted}${Traffic.showLayer}${Perf.mode}${Perf.effects}${Perf.animations}${Perf.smoothness}${Perf.liveFriends}${Perf.saveGpsStopped}${Offline.downloading}".hashCode()
    return h
}

internal fun MainActivity.renderQuick() {
    // rebuilt only when something on it changes (not on every convoy update)
    val key = "${Convoy.active}|${Convoy.members.size}|${NavEngine.route.size > 1}|${theme.name}|${MapState.dark}|${Traffic.showLayer}"
    if (key == quickKey && sheetHost.childCount > 0) return
    quickKey = key
    sheetHost.removeAllViews()
    sheetHost.background = bg(panel, radius = 24f, topOnly = true)
    sheetHost.addView(sheetHeader("While driving"))
    val rows = listOfNotNull(
        if (Convoy.active) settingRow("Leave convoy", "${Convoy.members.size + 1} drivers") { confirmLeaveConvoy() } else null,
        if (NavEngine.route.size > 1) settingRow("Route overview", null) {
            closeSheet()
            val r = NavEngine.route
            MapState.phone.showOverview(r.filterIndexed { i, _ -> i % 5 == 0 } + r.last())
        } else null,
        settingRow("Map style", theme.title) { openSheet("style") },
        settingRow("Save my parking spot here", null) {
            val here = LocationTracker.position
            if (here == null) Toast.makeText(this, L.t("Waiting for your location…"), Toast.LENGTH_SHORT).show()
            else { Parked.save(here); Toast.makeText(this, L.t("Parking spot saved"), Toast.LENGTH_SHORT).show(); closeSheet() }
        },
        settingRow("Settings", null) { openSheet("settings") },
    )
    group("", *rows.toTypedArray()).forEach { sheetHost.addView(it) }
}

internal fun MainActivity.showSettings() = openSheet("settings")

/** Settings › Performance › Animations: Full as designed, Reduced quicker, Off none. */
internal fun MainActivity.animMs(ms: Long): Long = when (Perf.anim) { Perf.FULL -> ms; Perf.REDUCED -> ms / 2; else -> 0L }

internal fun MainActivity.openReportForTest() = openSheet("report")

internal fun MainActivity.openSheetForTest(kind: String) = openSheet(kind)

/** Tests: the settings pages (null = the main list) and their keys. */
internal fun MainActivity.settingsPageForTest(page: String?) { settingsPage = page; refreshChrome() }

internal fun MainActivity.settingsKeysForTest(): List<String> = settingsGroups().map { it.key }

internal fun MainActivity.refreshForTest() { lastHomeKey = ""; refreshChrome() }

internal fun MainActivity.settingsGroups() = listOf(
    SettingsGroup("map", "Map", "Style, light or dark, Android Auto style, places, offline maps", R.drawable.ic_set_map, Color.rgb(59, 91, 219)),
    SettingsGroup("nav", "Navigation", "Voice, faster routes, tolls and motorways, live traffic", R.drawable.ic_set_nav, Color.rgb(30, 158, 90)),
    SettingsGroup("alerts", "Alerts", "Speed and red-light cameras, accidents, cameras you marked", R.drawable.ic_set_alert, Color.rgb(194, 65, 12)),
    SettingsGroup("sharing", "Sharing", "Your name, convoy, live arrival", R.drawable.ic_set_share, Color.rgb(124, 58, 237)),
    SettingsGroup("app", "App", "Your progress, Garage, trip history, privacy, about" + if (Updater.ENABLED) ", updates" else "", R.drawable.ic_set_app, Color.rgb(13, 110, 97)),
    SettingsGroup("perf", "Performance", "Smooth or battery, map effects, animations, map smoothness", R.drawable.ic_set_perf, Color.rgb(202, 138, 4)),
    SettingsGroup("advanced", "Advanced", "Units, map links, star ratings and reviews, welcome screens", R.drawable.ic_set_adv, Color.rgb(100, 100, 110)),
) + (if (BuildConfig.TEST_BUILD) listOf(
    // test builds only (never in a public release): tools for trying things out
    SettingsGroup("dev", "Developer", "Test builds only: simulate drives, test friends, XP, celebrations, log", R.drawable.ic_set_adv, Color.rgb(219, 39, 119)),
) else emptyList())

internal fun MainActivity.renderSettings() {
    // typing in the search box: don't rebuild under the keyboard
    if (settingsPage == null && sheetHost.findFocus() is EditText) return
    // only when a setting (or the page, or the look) changed: see renderStylePicker
    val key = "$settingsPage|$settingsQuery|${theme.name}|${MapState.dark}|${L.greek}|${settingsState()}"
    if (key == settingsKey && sheetHost.childCount > 0) return
    settingsKey = key
    sheetHost.removeAllViews()
    sheetHost.background = bg(panel, radius = 24f, topOnly = true)
    val maxH = minOf((resources.displayMetrics.heightPixels * 0.72f).toInt(), (640 * d).toInt())
    val page = settingsPage
    val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    if (page == null) {
        sheetHost.addView(sheetHeader("Settings"))
        // search every setting by its name (English or Greek)
        val found = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val groupsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun fill(q: String) {
            found.removeAllViews()
            val qq = q.trim().lowercase()
            groupsBox.visibility = if (qq.isEmpty()) View.VISIBLE else View.GONE
            if (qq.isEmpty()) return
            for (g in settingsGroups()) {
                val hits = settingsRowsFor(g.key).filter { row ->
                    val t = (row as? ViewGroup)?.let { firstText(it) }.orEmpty()
                    t.lowercase().contains(qq) || L.t(t).lowercase().contains(qq)
                }
                if (hits.isEmpty()) continue
                found.addView(label(L.t(g.title).uppercase(), 12f, bold = true).apply { setTextColor(dim()); letterSpacing = 0.08f; setPadding((4 * d).toInt(), (12 * d).toInt(), 0, (4 * d).toInt()) })
                group("", *hits.toTypedArray()).forEach { found.addView(it) }
            }
            if (found.childCount == 0) found.addView(label(L.t("No setting called \"$q\""), 14f).apply { setTextColor(dim()); setPadding((4 * d).toInt(), (12 * d).toInt(), 0, 0) })
            L.translateTree(found); Icons.applyTree(found)
        }
        body.addView(EditText(this).apply {
            hint = L.t("Search settings"); setSingleLine(); textSize = 16f
            setTextColor(ink); setHintTextColor(dim(130))
            background = bg(dim(22), dim(80), 1f, 22f, pill = true)
            val q = (12 * d).toInt(); setPadding(q + q / 2, q, q, q)
            setText(settingsQuery)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(e: Editable?) { settingsQuery = e?.toString().orEmpty(); fill(settingsQuery) }
            })
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = (6 * d).toInt() })
        body.addView(found)
        body.addView(groupsBox)
        for (g in settingsGroups()) groupsBox.addView(settingsEntry(g), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = (8 * d).toInt() })
        fill(settingsQuery)
        body.addView(label("GameRoute build ${shownBuild()}", 12f).apply {
            setTextColor(dim(150)); gravity = Gravity.CENTER; setPadding(0, (14 * d).toInt(), 0, (4 * d).toInt())
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        sheetHost.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return
    }
    if (page == "whatsnew") {
        // What's new: a list, back to Advanced (no pop-up after updates; the NEW tag is enough)
        sheetHost.addView(sheetHeader("What's new", "Advanced" to { settingsPage = "advanced"; refreshChrome() }))
        fun heading(t: String) = body.addView(label(L.t(t).uppercase(), 12.5f, bold = true).apply {
            setTextColor(dim(150)); letterSpacing = 0.08f; setPadding((12 * d).toInt(), (16 * d).toInt(), 0, (6 * d).toInt())
        })
        fun line(b: String, en: String, el: String) = body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val q = (12 * d).toInt(); setPadding(q, q / 3, q, q / 3)
            addView(label(b, 14f, bold = true).apply { setTextColor(readable(accent)) }, LinearLayout.LayoutParams((44 * d).toInt(), WRAP_CONTENT))
            addView(label(if (L.greek) el else en, 14f).apply { setTextColor(ink) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        // after 100: one line each, then 100 in full, then every earlier build in one line (no icons)
        val newer = History.builds.filter { (it.first.substringBefore('.').toIntOrNull() ?: 0) > 100 }
        if (newer.isNotEmpty()) { heading("Since build 100"); newer.forEach { line(it.first, it.second, it.third) } }
        heading("Build 100")
        for ((ic, t, sub) in WHATS_NEW) body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            val q = (12 * d).toInt(); setPadding(q, q * 3 / 4, q, q * 3 / 4)
            addView(label(ic, 22f).apply { gravity = Gravity.CENTER; background = bg(dim(26), radius = 12f) }, LinearLayout.LayoutParams((44 * d).toInt(), (44 * d).toInt()).apply { marginEnd = q })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(L.t(t), 16.5f, bold = true).apply { setTextColor(ink) })
                addView(label(L.t(sub), 13.5f).apply { setTextColor(dim()) })
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        heading("Earlier builds")
        History.builds.filter { (it.first.substringBefore('.').toIntOrNull() ?: 0) < 100 }.forEach { line(it.first, it.second, it.third) }
        prefs().edit().putBoolean("seenWhatsNew115", true).apply()
        sheetHost.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        body.post { (body.parent as? View)?.let { sv -> if (body.height > maxH) { sv.layoutParams.height = maxH; sv.requestLayout() } } }
        return
    }
    val g = settingsGroups().first { it.key == page }
    sheetHost.addView(sheetHeader(g.title, "Settings" to { settingsPage = null; refreshChrome() }))
    val rows = settingsRowsFor(page)
    group("", *rows.toTypedArray()).forEach { body.addView(it) }
    sheetHost.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { height = WRAP_CONTENT })
    body.post { (body.parent as? View)?.let { sv -> if (body.height > maxH) { sv.layoutParams.height = maxH; sv.requestLayout() } } }
}

/** The first text in a settings row (its name). */
internal fun MainActivity.firstText(v: ViewGroup): String? {
    for (i in 0 until v.childCount) {
        val c = v.getChildAt(i)
        if (c is TextView && c.text.isNotBlank()) return c.text.toString()
        if (c is ViewGroup) firstText(c)?.let { return it }
    }
    return null
}

/** The rows of one settings page (also searched from the main page). */
internal fun MainActivity.settingsRowsFor(page: String): List<View> {
    val voice = if (Voice.muted) "Off" else if (Voice.lang == Phrases.GREEK) "Ελληνικά" else "English"
    return when (page) {
        "map" -> listOf(
            settingRow("Map style", theme.title) { openSheet("style") },
            settingRow("Light / dark", MapState.lightMode.label) { chooseLightMode() },
            settingRow("Android Auto style", MapState.carTheme?.title ?: "Same as phone") { chooseCarStyle() },
            switchRow("Car: dark map when headlights are on", MapState.followHeadlights) { MapState.followHeadlights = it },
            switchRow("Places on the map (fuel, parking, food…)", MapState.showPlaces) { MapState.showPlaces = it },
            switchRow("Show traffic (phone and car)", Traffic.showLayer) { Traffic.showLayer = it; MapState.changed.fire() },
            switchRow("The road you're on (under the arrow)", NavEngine.showRoadName) { NavEngine.showRoadName = it },
            settingRow("Offline maps", Offline.areas().let { a -> if (a.isEmpty()) "Regions, Wi-Fi only, updates" else "${a.size} · ${Offline.size(a.sumOf { it.bytes })}" }) { openSheet("offline") },
        )
        "dev" -> if (!BuildConfig.TEST_BUILD) emptyList() else listOf(
            settingRow("Play a level celebration", "Levels 5 to 50, in this style") {
                val lv = (5..50 step 5).toList()
                GameBuilder(this@settingsRowsFor).setTitle("Play a level celebration")
                    // played over the Developer page: when it's done you're back here, not on the map
                    .setItems(lv.map { "Level $it" }.toTypedArray()) { _, i -> playCelebration(lv[i]) }.showT()
            },
            settingRow("Show a level card", "What each level unlocks") {
                val lv = (2..50).toList()
                GameBuilder(this@settingsRowsFor).setTitle("Show a level card")
                    .setItems(lv.map { l -> "Level $l" + if (Xp.unlockedAt(l).isNotEmpty()) " · " + Xp.unlockedAt(l).joinToString { L.t(it.name) } else "" }.toTypedArray()) { _, i ->
                        showLevelUpCard(lv[i] - 1, lv[i], preview = true)
                    }.showT()
            },
            settingRow("What the app noted lately", "Reroute times, GPS, car screen") {
                val notes = CrashLog.recentNotes().ifBlank { "Nothing yet." }
                GameBuilder(this@settingsRowsFor).setTitle("Lately").setMessage(notes)
                    .setNeutralButton("Copy") { _, _ ->
                        getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(android.content.ClipData.newPlainText("GameRoute log", notes))
                        Toast.makeText(this, L.t("Copied"), Toast.LENGTH_SHORT).show()
                    }.setPositiveButton("OK", null).showT()
            },
            settingRow("Reset what's been seen", "Welcome screens, What's new, this level's celebration · the app reopens") {
                // your current level celebrates again; the app reopens on the welcome screens
                prefs().edit().putInt("levelCelebrated", (Explorer.level - 1).coerceAtLeast(1))
                    .remove("seenWhatsNew115").remove("welcomeDone").commit()
                Toast.makeText(this, L.t("Reset"), Toast.LENGTH_SHORT).show()
                closeSheet(); recreate()
            },
            settingRow("Test a crash", "Saves a report, like a real crash") {
                GameBuilder(this@settingsRowsFor).setTitle("Test a crash?").setMessage("The app closes and offers the report next time.")
                    .setNegativeButton("Cancel", null).setPositiveButton("Crash") { _, _ -> throw RuntimeException("Test crash from the Developer page") }.showT()
            },
            // ---- driving, no car needed
            settingRow("Simulate a drive", if (Dev.simActive) "Driving now · tap to stop" else "Fake GPS to Home (or 2.5 km away) · earns nothing") {
                if (Dev.simActive) { Dev.stopSim(); showSettings(); return@settingRow }
                Dev.simulate(this, Places.saved("home")); closeSheet()
            },
            settingRow("While simulating…", "Leave the route, weak GPS, tunnel, no internet, offer, arrive") { devDriveTools() },
            settingRow("Fast forward", if (Dev.fastForward == 1) "Off · the road's speed" else "${Dev.fastForward}× faster · past the limits") { chooseFastForward() },
            // ---- friends and convoy, no friends needed
            settingRow("Add 4 test friends", "Driving, in a convoy, busy, offline · this phone only") {
                Dev.addTestFriends(); Toast.makeText(this, "Test friends added", Toast.LENGTH_SHORT).show()
            },
            settingRow("Send me a test invite", "A convoy invite from Nikos (test)") {
                Dev.testInvite(); Toast.makeText(this, "Invite on its way", Toast.LENGTH_SHORT).show()
            },
            settingRow("Start a test convoy", if (Dev.convoyOn) "On · tap to stop" else "You and 3 made-up drivers drive together, with quick messages") {
                if (Dev.convoyOn) Dev.stopTestConvoy() else { Dev.startTestConvoy(this); closeSheet(); return@settingRow }; showSettings()
            },
            settingRow("Remove test friends", "And their invites") {
                Dev.removeTestFriends(); Toast.makeText(this, "Removed", Toast.LENGTH_SHORT).show()
            },
            // ---- XP and levels (your real progress is backed up first)
            settingRow("Add XP", "Level ${Explorer.level} · ${Explorer.xp} XP") {
                GameBuilder(this@settingsRowsFor).setTitle("Add XP").setMessage(devBackupNote())
                    .setItems(arrayOf("+100 XP", "+1,000 XP")) { _, i -> devXp { Dev.addXp(this, if (i == 0) 100 else 1000) } }.showT()
            },
            settingRow("Set level", "1 to 50") {
                val lv = (1..50).toList()
                GameBuilder(this@settingsRowsFor).setTitle("Set level")
                    .setItems(lv.map { "Level $it" }.toTypedArray()) { _, i -> devXp { Dev.setLevel(this, lv[i]) } }.showT()
            },
            settingRow("Try prestige", "Level 50, then prestige: back to level 1 with the next rank's ◆") {
                GameBuilder(this@settingsRowsFor).setTitle("Try prestige?").setMessage(devBackupNote() + "\n\nYou go to level 50 and prestige straight away: level 1, the next rank, its rewards.")
                    .setNegativeButton("Cancel", null).setPositiveButton("Prestige") { _, _ ->
                        Dev.setLevel(this, 50); Xp.doPrestige()
                        prefs().edit().putInt("levelCelebrated", Explorer.level).apply()
                        progressKey = ""; showSettings(); showPrestigeReveal()
                    }.showT()
            },
            settingRow("Restore my real progress", if (Dev.hasBackup(this)) "Backed up before testing · the app restarts" else "Nothing to restore: no XP tools used yet") {
                if (!Dev.hasBackup(this)) return@settingRow
                GameBuilder(this@settingsRowsFor).setTitle("Restore your real progress?").setMessage("XP and levels go back to how they were before testing. The app restarts.")
                    .setNegativeButton("Cancel", null).setPositiveButton("Restore") { _, _ ->
                        if (Dev.restoreBackup(this)) {
                            val again = packageManager.getLaunchIntentForPackage(packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                            if (again != null) startActivity(again)
                            finishAffinity(); Runtime.getRuntime().exit(0)
                        }
                    }.showT()
            },
            settingRow("This build", "build ${shownBuild()} · test") { },
        )
        "nav" -> listOf(
            settingRow("Spoken directions", voice) { chooseVoice() },
            settingRow("How much the voice talks", L.t(TALK[Voice.talk])) { chooseTalk() },
            settingRow("Faster routes", if (NavEngine.autoSwitch) "Switch automatically" else "Ask me") { chooseAutoSwitch() },
            switchRow("Avoid tolls", NavEngine.avoidTolls) { NavEngine.avoidTolls = it },
            switchRow("Avoid motorways", NavEngine.avoidMotorways) { NavEngine.avoidMotorways = it },
            switchRow("Live traffic", Traffic.enabled) { Traffic.setEnabled(it) },
        )
        "alerts" -> listOf(
            switchRow("Speed cameras", Alerts.camerasOn) { Alerts.camerasOn = it },
            switchRow("Red light cameras", Alerts.redLightOn) { Alerts.redLightOn = it },
            switchRow("Accidents & roadworks", Alerts.incidentsOn) { Alerts.incidentsOn = it },
            settingRow("Cameras you marked", "${Alerts.myCameras().size}") {
                val n = Alerts.myCameras().size
                GameBuilder(this@settingsRowsFor).setTitle("Cameras you marked")
                    .setMessage(if (n == 0) "None yet. While driving, tap ⚠ › Speed camera or Red-light camera to mark one where you are."
                        else "$n camera${if (n == 1) "" else "s"} saved on this phone. GameRoute warns you before each one.")
                    .apply { if (n > 0) setNeutralButton("Delete all") { _, _ -> Alerts.clearMyCameras(); showSettings() } }
                    .setPositiveButton("OK", null).showT()
            },
        )
        "sharing" -> listOf(
            settingRow("Your name in convoys", Convoy.myName.ifBlank { "Not set" }) { editConvoyName { showSettings() } },
            settingRow("Convoy", if (Convoy.active) "On · ${Convoy.members.size + 1} drivers" else "Start or join") { openSheet("convoy") },
            settingRow("Friends", if (Friends.core.started) L.t("${Friends.core.friends.size} friends") else L.t("Add friends")) { openSheet("friends") },
            switchRow("Show my level to friends", Xp.showToFriends) { Xp.showToFriends = it; Friends.Presence.refresh() },
        ) + (if (!Friends.core.started) emptyList() else listOf(
            settingRow("Your status", statusName(Friends.core.status)) { chooseStatus() },
            switchRow("Friends can invite me to convoys", Friends.core.allowInvites) { Friends.setAllowInvites(it) },
            settingRow("My friend code", Friends.core.code) {
                getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(android.content.ClipData.newPlainText("Friend code", Friends.core.code))
                Toast.makeText(this, L.t("Code copied"), Toast.LENGTH_SHORT).show()
            },
            settingRow("Make a new friend code", null) {
                GameBuilder(this@settingsRowsFor).setTitle("Make a new friend code?").setMessage("Your friends stay. Old links and QR codes stop working.")
                    .setPositiveButton("Make a new code") { _, _ -> Friends.newCode(); main.postDelayed({ showSettings() }, 300) }
                    .setNegativeButton("Cancel", null).showT()
            },
            settingRow("Blocked", "${Friends.core.blocked.size}") { showBlocked() },
        ))
        "app" -> listOfNotNull(
            settingRow("Trip history", "${Explorer.history().size} trips") { openSheet("trips") },
            settingRow("Your progress", "${Xp.stars}${L.t(Xp.title())} · " + L.t("level ${Explorer.level}")) { openSheet("progress") },
            settingRow("Garage", L.t("${Xp.items.count { it.unlocked }} of ${Xp.items.size} unlocked")) { openSheet("garage") },
            if (Updater.ENABLED) settingRow("Check for updates", "build ${shownBuild()}") { checkForUpdates(manual = true) } else null,
            settingRow("Backup & restore", L.t("Keep your progress in a file you save")) { chooseBackup() },
            settingRow("Language", L.label) { chooseLanguage() },
            settingRow("Privacy policy", null) {
                try { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://vyto-line.github.io/GameRoute/privacy/"))) } catch (_: Exception) {}
            },
            settingRow("About & map credits", null) { showAbout() },
        )
        "perf" -> listOfNotNull(
            settingRow("Mode", L.t(PERF_MODE[Perf.mode]) + if (Perf.mode == Perf.AUTO) " · " + L.t(if (Perf.saving) "saving battery now" else "smooth now") else "") { choosePerf("mode") },
            switchRow("Map effects: rain, snow, glow, scanlines, drafting grid", Perf.effects) { Perf.effects = it },
            settingRow("Animations", L.t(PERF_ANIM[Perf.animations])) { choosePerf("anim") },
            settingRow("Map smoothness while driving", L.t(PERF_SMOOTH[Perf.smoothness])) { choosePerf("smooth") },
            switchRow("Live friend updates (off: checked every 15 min)", Perf.liveFriends) { Perf.liveFriends = it; Friends.Live.start() },
            switchRow("Save GPS when stopped", Perf.saveGpsStopped) { Perf.saveGpsStopped = it },
            settingRow(L.t("Last drive") + ": " + (Perf.lastDrive() ?: L.t("Measured on your next drive of 5 minutes or more")), null) {},
            if (Perf.struggling) settingRow(L.t("Effects paused") + ": " + L.t("The map was slow on this phone, so effects are off until the app restarts"), null) {} else null,
        )
        else -> listOf(
            settingRow("Units", if (MapState.imperial) "Miles" else "Kilometres") { MapState.setImperial(!MapState.imperial) },
            settingRow("Open map links in GameRoute", if (mapLinksOn()) "On" else "Off") { explainMapLinks() },
            settingRow("Star ratings & reviews", if (Reviews.enabled) "Google key set" else "Off: Reviews opens Google Maps") { chooseGoogleKey() },
            settingRow("Show the welcome screens again", null) { closeSheet(); showWelcome() },
            switchRow("Classic voice timing (exact distances)", Voice.classic) { Voice.classic = it },
            settingRow(L.t("What's new") + if (!prefs().getBoolean("seenWhatsNew115", false)) "  · " + L.t("NEW") else "", L.t("Version ") + BuildConfig.VERSION_NAME.removePrefix("build-")) { settingsPage = "whatsnew"; refreshChrome() },
        )
    }
}

/** One group in the settings list: coloured icon, name, what's inside, ›. */
internal fun MainActivity.settingsEntry(g: SettingsGroup) = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
    val p = (12 * d).toInt(); setPadding(p, p, p, p)
    background = bg(Color.argb(26, Color.red(ink), Color.green(ink), Color.blue(ink)), radius = 14f)
    setOnClickListener { settingsPage = g.key; refreshChrome() }
    val tint = Ui.onPanel(theme, g.tint, 3.0)
    addView(android.widget.ImageView(context).apply {
        setImageResource(g.icon); imageTintList = android.content.res.ColorStateList.valueOf(tint)
        val q = (8 * d).toInt(); setPadding(q, q, q, q)
        background = bg(Color.argb(40, Color.red(tint), Color.green(tint), Color.blue(tint)), radius = 12f)
    }, LinearLayout.LayoutParams((40 * d).toInt(), (40 * d).toInt()).apply { marginEnd = (12 * d).toInt() })
    addView(LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(label(g.title, 16f, bold = true).apply { setTextColor(ink) })
        addView(label(g.summary, 13f).apply { setTextColor(dim()) })
    }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
    addView(label("›", 22f).apply { setTextColor(dim()); setPadding((8 * d).toInt(), 0, 0, 0) })
}

internal fun MainActivity.chooseGoogleKey() {
    val p = (20 * d).toInt()
    val input = android.widget.EditText(this).apply {
        setText(Reviews.key); hint = "Google Maps Platform API key"; setSingleLine()
    }
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(p, p / 2, p, 0)
        addView(TextView(context).apply {
            textSize = 14f
            text = "Stars and reviews come from Google. Paste a Google Maps Platform key with " +
                "\"Places API (New)\" turned on (console.cloud.google.com). Google gives a free monthly " +
                "allowance; a personal app normally stays inside it.\n\nWithout a key, \"Reviews\" opens the " +
                "place in Google Maps."
        })
        addView(input)
    }
    GameBuilder(this@chooseGoogleKey).setTitle("Star ratings & reviews").setView(box)
        .setPositiveButton("Save") { _, _ -> Reviews.key = input.text.toString(); showSettings() }
        .setNeutralButton("Remove") { _, _ -> Reviews.key = ""; showSettings() }
        .setNegativeButton("Cancel", null)
        .showT()
}

internal fun MainActivity.chooseLanguage() {
    val opts = arrayOf("Auto (phone's language)", "English", "Ελληνικά")
    val cur = when (L.mode) { "en" -> 1; "el" -> 2; else -> 0 }
    GameBuilder(this@chooseLanguage).setTitle("Language")
        .setSingleChoiceItems(opts, cur) { dlg, k ->
            dlg.dismiss()
            L.setMode(when (k) { 1 -> "en"; 2 -> "el"; else -> "auto" })
            recreate()
        }.showT()
}

internal fun MainActivity.chooseVoice() {
    val opts = arrayOf("English", "Ελληνικά", "Off")
    val cur = if (Voice.muted) 2 else if (Voice.lang == Phrases.GREEK) 1 else 0
    GameBuilder(this@chooseVoice).setTitle("Spoken directions")
        .setSingleChoiceItems(opts, cur) { dlg, k ->
            when (k) {
                0 -> { Voice.lang = Phrases.ENGLISH; Voice.muted = false }
                1 -> { Voice.lang = Phrases.GREEK; Voice.muted = false }
                else -> Voice.muted = true
            }
            dlg.dismiss()
            if (k < 2) Voice.say(if (k == 1) "Οι οδηγίες θα ακούγονται στα ελληνικά" else "Directions will be spoken in English")
        }.showT()
}

/** Test builds: the drive simulator's tricks. */
internal fun MainActivity.devDriveTools() {
    if (!Dev.simActive) { Toast.makeText(this, "Start a simulated drive first", Toast.LENGTH_SHORT).show(); return }
    val opts = arrayOf("Leave the route here", "Weak GPS (30 s)", "Tunnel: no GPS (30 s)", "No internet (30 s)", "Show a faster-route offer", "Arrive now", "Stop the simulation", "Fast forward…")
    GameBuilder(this@devDriveTools).setTitle("While simulating").setItems(opts) { _, i ->
        when (i) {
            0 -> Dev.leaveRoute(); 1 -> Dev.weakGps(); 2 -> Dev.tunnel(); 3 -> Dev.noSignal()
            4 -> Dev.showOffer(); 5 -> Dev.arriveNow(); 6 -> Dev.stopSim()
            7 -> { chooseFastForward(); return@setItems }
        }
        closeSheet()
    }.showT()
}

/** Test builds: how much faster than the road the simulated drive goes. */
internal fun MainActivity.chooseFastForward() {
    val opts = listOf(1, 2, 4, 8)
    GameBuilder(this@chooseFastForward).setTitle("Fast forward").setMessage("The simulated drive (and the test convoy) go this many times faster than the road allows. The voice may skip some turns at 8×.")
        .setItems(opts.map { if (it == 1) "Off · the road's speed" else "$it× faster" }.toTypedArray()) { _, i ->
            Dev.fastForward = opts[i]
            if (sheetKind.isNotEmpty()) showSettings()
        }.showT()
}

/** An XP tool, then its celebration straight away (over the Developer page, which stays open). */
internal fun MainActivity.devXp(change: () -> Unit) {
    val before = Explorer.level
    change()
    val after = Explorer.level
    // going down: the levels above count as new again
    if (after < prefs().getInt("levelCelebrated", after)) prefs().edit().putInt("levelCelebrated", after).apply()
    showSettings()
    if (after > before) {
        prefs().edit().putInt("levelCelebrated", before).apply()
        celebrating = false
        showLevelUp(before)
    }
}

internal fun MainActivity.devBackupNote() = if (Dev.hasBackup(this)) "Your real progress is already backed up." else "Your real progress is backed up first. Restore it any time from this page."

/** Backup & restore: save everything to a file you keep, or bring it back from one. */
internal fun MainActivity.chooseBackup() {
    val opts = arrayOf(
        L.t("Save a backup") + "\n" + L.t("Level, prestige, Garage, trips, places, friends and settings, in one file"),
        L.t("Restore from a backup") + "\n" + L.t("Replaces what's on this phone, then the app restarts"))
    GameBuilder(this@chooseBackup).setTitle(L.t("Backup & restore")).setItems(opts) { _, k ->
        try {
            if (k == 0) {
                val name = "GameRoute backup " + java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date()) + ".json"
                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json").putExtra(Intent.EXTRA_TITLE, name), REQ_BACKUP_SAVE)
            } else {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_BACKUP_OPEN)
            }
        } catch (e: Exception) { Toast.makeText(this, L.t("No app here can save or open files"), Toast.LENGTH_LONG).show() }
    }.showT()
}

internal fun MainActivity.prefs() = getSharedPreferences("gamemap", Context.MODE_PRIVATE)

internal fun MainActivity.choosePerf(what: String) {
    val (title, opts, cur) = when (what) {
        "mode" -> Triple("Mode", arrayOf(
            L.t("Auto") + "\n" + L.t("Smooth, saves battery when Battery Saver is on or below 20%"),
            L.t("Smooth") + "\n" + L.t("Always smooth"),
            L.t("Battery") + "\n" + L.t("Always saves battery: no effects, fewer frames")), Perf.mode)
        "anim" -> Triple("Animations", arrayOf(
            L.t("Full") + "\n" + L.t("Celebrations in your style, smooth screen changes"),
            L.t("Reduced") + "\n" + L.t("Simple cards, quick fades"),
            L.t("Off") + "\n" + L.t("Nothing moves, everything appears at once")), Perf.animations)
        else -> Triple("Map smoothness while driving", arrayOf(
            L.t("High") + "\n" + L.t("Up to 60 frames a second, smoothest"),
            L.t("Normal") + "\n" + L.t("30 while driving, 20 when slow"),
            L.t("Low") + "\n" + L.t("15, best for older phones and long trips")), Perf.smoothness)
    }
    GameBuilder(this@choosePerf).setTitle(L.t(title))
        .setSingleChoiceItems(opts, cur) { dlg, k ->
            when (what) { "mode" -> Perf.mode = k; "anim" -> Perf.animations = k; else -> Perf.smoothness = k }
            dlg.dismiss(); showSettings()
        }.showT()
}

/** Normal: every heads-up and the turn. Fewer: one heads-up and the turn. Turns only: just the turn. */
internal fun MainActivity.chooseTalk() {
    val opts = arrayOf(L.t("Normal") + "\n" + L.t("In 200 m, then at the turn (motorway: 1 km, 500 m)"),
        L.t("Fewer") + "\n" + L.t("One heads-up, then at the turn"),
        L.t("Turns only") + "\n" + L.t("Only at the turn"))
    GameBuilder(this@chooseTalk).setTitle(L.t("How much the voice talks"))
        .setSingleChoiceItems(opts, Voice.talk) { dlg, k -> Voice.talk = k; dlg.dismiss(); showSettings() }
        .showT()
}

internal fun MainActivity.chooseAutoSwitch() {
    val opts = arrayOf("Switch automatically", "Ask me (keep my route)")
    GameBuilder(this@chooseAutoSwitch).setTitle("When a faster route is found")
        .setSingleChoiceItems(opts, if (NavEngine.autoSwitch) 0 else 1) { dlg, k ->
            NavEngine.autoSwitch = k == 0; dlg.dismiss(); refreshChrome()
        }.showT()
}

internal fun MainActivity.chooseLightMode() {
    val modes = MapState.LightMode.entries
    GameBuilder(this@chooseLightMode)
        .setTitle("Light / dark mode")
        .setSingleChoiceItems(modes.map { it.label }.toTypedArray(), MapState.lightMode.ordinal) { dlg, m ->
            MapState.setLightMode(modes[m]); dlg.dismiss()
        }.showT()
}
