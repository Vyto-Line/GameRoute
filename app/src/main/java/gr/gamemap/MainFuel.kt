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

internal fun MainActivity.showFuelForTest() = showFuel()

internal fun MainActivity.showFuel() {
    val near = LocationTracker.position
    if (near == null) { Toast.makeText(this, L.t("Waiting for your location…"), Toast.LENGTH_SHORT).show(); return }
    val seq = ++querySeq
    lastResults = emptyList()
    fuelShown = emptyList()
    renderFuel(loading = true)
    io.execute {
        val types = if (Fuel.showChargers) listOf("fuel", "charging_station") else listOf("fuel")
        val local = try { Places.nearbyLocal(types, near) } catch (e: Exception) { emptyList<Place>() }
        if (local.isNotEmpty()) runOnUiThread { if (seq == querySeq && !isFinishing) { fuelShown = local; renderFuel(loading = true) } }
        val r = try { Places.fuelAndChargers(near, Fuel.showChargers) } catch (e: Exception) { null }
        runOnUiThread {
            if (seq != querySeq || isFinishing) return@runOnUiThread
            fuelShown = r ?: local
            renderFuel(loading = false, failed = r == null && local.isEmpty())
        }
    }
}

/** Fuel list in the search box: your fuels as chips, Cheapest / Nearest / On my route, prices big. */
internal fun MainActivity.renderFuel(loading: Boolean = false, failed: Boolean = false) {
    val all = fuelShown ?: return
    val near = LocationTracker.position ?: return
    val p = (12 * d).toInt()
    suggestList.removeAllViews()
    // title + Filters
    suggestList.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(p + p / 2, p, p / 2, 0)
        addView(label("FUEL NEARBY", 12f, bold = true).apply { setTextColor(dim(170)); letterSpacing = 0.1f }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val n = (if (Fuel.brands.isNotEmpty()) 1 else 0) + (if (!Fuel.showChargers) 1 else 0) + (if (Fuel.fastOnly) 1 else 0) +
            (if (Fuel.plugs.size < Fuel.plugKinds.size) 1 else 0)
        addView(pill(if (n > 0) "Filters · $n" else "Filters") { openSheet("fuelfilters") }.apply {
            textSize = 13f; setTextColor(readable(accent)); minHeight = (36 * d).toInt()
        })
    })
    // fuel chips
    val kinds = Fuel.fuelKinds.filter { it.first in Fuel.myFuels }.map { it.first } + (if (Fuel.showChargers) listOf("ev") else emptyList())
    suggestList.addView(android.widget.HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(p, p / 2, p, 0)
            for (k in kinds) {
                val on = k == Fuel.chosen
                addView(pill(if (k == "ev") "⚡ EV" else Fuel.nameOf(k)) { Fuel.chosen = k; renderFuel(loading) }.apply {
                    textSize = 14f; minHeight = (36 * d).toInt(); val q = (14 * d).toInt(); setPadding(q, 0, q, 0)
                    setTextColor(if (on) onAccent(accent) else ink)
                    background = if (on) bg(accent, radius = 18f, pill = true) else bg(Color.TRANSPARENT, dim(90), 1.5f, 18f, pill = true)
                }, LinearLayout.LayoutParams(WRAP_CONTENT, (36 * d).toInt()).apply { marginEnd = (8 * d).toInt() })
            }
        })
    })
    // sort
    val sorts = listOfNotNull("cheapest" to "Cheapest", "nearest" to "Nearest", if (NavEngine.active && !NavEngine.arrived) "route" to "On my route" else null)
    val cur = Fuel.sort.takeIf { s -> sorts.any { it.first == s } } ?: "cheapest"
    suggestList.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; val q = (3 * d).toInt(); setPadding(q, q, q, q)
        background = bg(dim(26), radius = 12f)
        for ((k, t) in sorts) addView(pill(t) { Fuel.sort = k; renderFuel(loading) }.apply {
            textSize = 13f; minHeight = (34 * d).toInt(); setPadding(0, 0, 0, 0)
            setTextColor(if (k == cur) ink else dim()); if (k == cur) background = bg(panel, radius = 10f)
        }, LinearLayout.LayoutParams(0, (34 * d).toInt(), 1f))
    }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { setMargins(p, p / 2 + p / 4, p, p / 3) })
    val list = Fuel.arrange(all, near).take(15)
    val kind = Fuel.chosen
    if (list.isEmpty()) suggestList.addView(label(when {
        loading -> "Searching nearby…"; failed -> "No connection: check mobile data"; else -> "Nothing found nearby"
    }, 15f).apply { setTextColor(dim(170)); setPadding(p + p / 2, p, p, p) })
    for (pl in list) suggestList.addView(fuelRow(pl, kind, near))
    if (loading && list.isNotEmpty()) suggestList.addView(label("Finding prices…", 13f).apply { setTextColor(dim(150)); setPadding(p + p / 2, p / 2, p, p) })
    if (list.any { it.areaAvg }) suggestList.addView(label("In Greece the price service gives area averages, not each station's own price.", 12f).apply {
        setTextColor(dim(150)); setPadding(p + p / 2, p / 2, p, p)
    })
    // price tags on the map for the stations in the list
    Fuel.pins = list.mapNotNull { pl ->
        val t = if (Fuel.isCharger(pl)) (if (pl.kw > 0) "⚡ ${pl.kw} kW" else "⚡") else Fuel.priceOf(pl, kind)?.let { "${Fuel.nameOf(kind).take(1)} €${"%.2f".format(java.util.Locale.US, it)}" }
        t?.let { pl.pos to it }
    }
    lastResults = list
    map.invalidate()
    suggestScroll.visibility = View.VISIBLE
    refreshChrome()
    suggestScroll.post {
        val max = (resources.displayMetrics.heightPixels * 0.5f).toInt() // leave room for the price tags on the map
        val lp = suggestScroll.layoutParams
        val want = if (suggestList.height > max) max else WRAP_CONTENT
        if (lp.height != want) { lp.height = want; suggestScroll.layoutParams = lp }
    }
}

internal fun MainActivity.fuelRow(pl: Place, kind: String, near: LatLng): View {
    val p = (12 * d).toInt()
    val charger = Fuel.isCharger(pl)
    return LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(p, p - p / 4, p + p / 2, p - p / 4)
        setOnClickListener { choose(pl) }
        addView(label(pl.icon, 20f).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams((40 * d).toInt(), WRAP_CONTENT))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(p / 2, 0, p / 2, 0)
            addView(label(pl.name, 16f, bold = true).apply { setTextColor(ink); maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            val det = Fuel.detour(pl)
            val where = listOfNotNull(NavEngine.formatDistance(Geo.distance(near, pl.pos)),
                det?.let { "+${maxOf(1, (it / 12 / 60).toInt() + 1)} min" }, pl.address.split(",").firstOrNull()?.takeIf { it.isNotBlank() })
            addView(label(where.joinToString(" · "), 13f).apply { setTextColor(dim(170)); maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            val others = if (charger) listOfNotNull(pl.plugs.mapNotNull { k -> Fuel.plugKinds.firstOrNull { it.first == k }?.second }.joinToString(", ").ifBlank { null })
                else Fuel.fuelKinds.filter { it.first != kind }.mapNotNull { (k, n, _) -> Fuel.priceOf(pl, k)?.let { "$n €${"%.3f".format(java.util.Locale.US, it)}" } }
            if (others.isNotEmpty()) addView(label(others.joinToString(" · "), 12f).apply { setTextColor(dim(200)); maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.END
            val price = if (charger) (if (pl.kw > 0) "${pl.kw} kW" else "⚡") else Fuel.priceOf(pl, kind)?.let { "€${"%.3f".format(java.util.Locale.US, it)}" } ?: "–"
            addView(label(price, 19f, bold = true).apply { setTextColor(ink); typeface = Typeface.create(theme.typeface, Typeface.BOLD) })
            addView(label(if (charger) "charger" else Fuel.nameOf(kind) + if (pl.areaAvg) " · area avg" else "", 11f).apply { setTextColor(dim(170)); gravity = Gravity.END })
        })
    }
}

internal fun MainActivity.renderFuelFilters() {
    sheetHost.removeAllViews()
    sheetHost.background = bg(panel, radius = 24f, topOnly = true)
    sheetHost.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(label("Fuel filters", 22f, bold = true).apply { setTextColor(ink); typeface = theme.typeface }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(pill("Reset") { Fuel.reset(); renderFuelFilters() }.apply { textSize = 14f; setTextColor(readable(accent)) })
        addView(pill("✕") { closeSheet(); if (fuelShown != null) renderFuel() }.apply { setTextColor(ink) })
    })
    val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    fun head(t: String) = body.addView(label(t, 12f, bold = true).apply { setTextColor(dim(170)); letterSpacing = 0.08f; setPadding((6 * d).toInt(), (14 * d).toInt(), 0, (4 * d).toInt()) })
    fun check(title: String, on: Boolean, flip: () -> Unit) = body.addView(switchRow(title, on) { flip() })
    head("MY FUELS (PRICES ON THE MAP)")
    for ((k, n, _) in Fuel.fuelKinds) check(when (k) { "sp95" -> "Unleaded 95"; "lpg" -> "LPG (autogas)"; else -> n }, k in Fuel.myFuels) {
        Fuel.myFuels = if (k in Fuel.myFuels) Fuel.myFuels - k else Fuel.myFuels + k
    }
    head("ELECTRIC")
    check("Show chargers", Fuel.showChargers) { Fuel.showChargers = !Fuel.showChargers }
    for ((k, n) in Fuel.plugKinds) check("Plug: $n", k in Fuel.plugs) { Fuel.plugs = if (k in Fuel.plugs) Fuel.plugs - k else Fuel.plugs + k }
    check("Fast chargers only (50 kW+)", Fuel.fastOnly) { Fuel.fastOnly = !Fuel.fastOnly }
    head("BRANDS (NONE TICKED = ALL)")
    for (b in Fuel.brandList) check(b, b in Fuel.brands) { Fuel.brands = if (b in Fuel.brands) Fuel.brands - b else Fuel.brands + b }
    sheetHost.addView(ScrollView(this).apply { addView(body) },
        LinearLayout.LayoutParams(MATCH_PARENT, minOf((resources.displayMetrics.heightPixels * 0.62f).toInt(), (560 * d).toInt())))
    sheetHost.addView(pill("Show stations") { closeSheet(); if (fuelShown != null) renderFuel() else showFuel() }.apply {
        setTextColor(onAccent(accent)); background = bg(accent, radius = 26f, pill = true)
    }, LinearLayout.LayoutParams(MATCH_PARENT, (52 * d).toInt()).apply { topMargin = (12 * d).toInt() })
}
