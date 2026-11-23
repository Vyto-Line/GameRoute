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

internal fun MainActivity.showSuggestions() {
    if (search.text.trim().length < 2) showRecents() else renderResults(lastResults, "Searching…")
}

internal fun MainActivity.showRecents() {
    val seq = ++querySeq
    lastResults = emptyList()
    val fav = Places.savedPlaces()
    val rec = Places.recents().filter { r -> fav.none { Geo.distance(it.pos, r.pos) < 25 } }
    if (fav.isNotEmpty()) renderSections(listOf("SAVED" to fav.take(6), "RECENT" to rec.take(4)))
    else renderResults(rec, if (rec.isEmpty()) "Type a place, address or business" else null, header = if (rec.isEmpty()) null else "RECENT", force = true)
    val near = LocationTracker.position ?: return
    Places.warmUp(near, force = true)
    io.execute {
        val around = try { Places.around(near) } catch (e: Exception) { emptyList() }
        if (around.isNotEmpty()) runOnUiThread {
            if (seq != querySeq || isFinishing) return@runOnUiThread
            renderSections(listOfNotNull(("SAVED" to fav.take(6)).takeIf { fav.isNotEmpty() }, "RECENT" to rec.take(4), "AROUND YOU" to around))
        }
    }
}

/** Several titled lists in the suggestions box. */
internal fun MainActivity.renderSections(sections: List<Pair<String, List<Place>>>) {
    val p = (12 * d).toInt()
    suggestList.removeAllViews()
    for ((title, places) in sections) {
        if (places.isEmpty()) continue
        suggestList.addView(TextView(this).apply {
            text = title; textSize = 12f; setTextColor(dim(170)); letterSpacing = 0.1f
            setPadding(p + p / 2, p, p, p / 3)
        })
        for (pl in places) suggestList.addView(resultRow(pl, removable = title == "RECENT"))
    }
    suggestScroll.visibility = View.VISIBLE
}

internal fun MainActivity.runQuery(q: String, full: Boolean) {
    if (q.trim().length < 2) return
    val seq = ++querySeq
    val near = LocationTracker.position
    if (full) hideKeyboard()
    io.execute {
        // 1) instant: names in the map data on the phone
        val local = try { Places.local(q, near) } catch (e: Exception) { emptyList<Place>() }
        if (local.isNotEmpty()) runOnUiThread {
            if (seq == querySeq && !isFinishing) { lastResults = local; renderResults(local, null, header = "NEARBY") }
        }
        // 2) the exact address and the search services, merged with the local names
        val r = Places.find(q, near, full, local)
        runOnUiThread {
            if (seq != querySeq || isFinishing) return@runOnUiThread
            val merged = r.orEmpty()
            lastResults = merged
            val notHere = Places.missingWord(q, merged)
            renderResults(merged, when {
                notHere != null -> L.t("No “$notHere” on the map here. Other matches:")
                merged.isNotEmpty() -> null
                r == null -> "No connection: check mobile data"
                else -> "Nothing found"
            }, missing = if (r != null && pickFor == null) q.trim() else null)
        }
    }
}

internal fun MainActivity.renderResults(places: List<Place>, message: String?, header: String? = null, force: Boolean = false, missing: String? = null) {
    if (!force && !search.hasFocus() && search.text.isEmpty() && places.isEmpty() && message == null) return
    val p = (12 * d).toInt()
    suggestList.removeAllViews()
    (header ?: pickFor?.let { when (it) { "home" -> "SET HOME"; "work" -> "SET WORK"; else -> "CONVOY DESTINATION" } })?.let {
        suggestList.addView(TextView(this).apply {
            text = it; textSize = 12f; setTextColor(dim(170)); letterSpacing = 0.1f
            setPadding(p + p / 2, p, p, p / 3)
        })
    }
    message?.let {
        suggestList.addView(TextView(this).apply { text = it; textSize = 15f; setTextColor(dim(170)); setPadding(p + p / 2, p, p, p) })
    }
    val near = LocationTracker.position
    // typed a pharmacy word: the ones on duty now on top, then everything else
    if (pickFor == null && header != "RECENT" && near != null && Duties.isPharmacyQuery(search.text.toString())) {
        val now = Duties.now()
        val on = Duties.onDutyNear(near, 15_000.0, now).take(3)
        if (on.isNotEmpty()) {
            val moon = readable(PoiLayer.MOON)
            suggestList.addView(label("🌙 " + L.t("ON DUTY NOW"), 12f, bold = true).apply { setTextColor(moon); letterSpacing = 0.1f; setPadding(p + p / 2, p, p, p / 3) })
            for (dt in on) suggestList.addView(pharmacyRow(Duties.place(dt, now), "🌙 " + Duties.till(dt, now), moon, dt.phone))
            suggestList.addView(label(L.t("All pharmacies nearby") + " ›", 15f, bold = true).apply {
                setTextColor(moon); setPadding(p + p / 2, p / 2, p, p / 2); isClickable = true
                setOnClickListener { hideKeyboard(); showPharmacies() }
            })
            if (places.isNotEmpty()) suggestList.addView(label(L.t("PLACES"), 12f, bold = true).apply { setTextColor(dim(170)); letterSpacing = 0.1f; setPadding(p + p / 2, p, p, p / 3) })
        }
    }
    for (pl in places) suggestList.addView(resultRow(pl, removable = header == "RECENT"))
    // not there (or not the right one): put it on the map yourself, saved under that name
    if (missing != null) suggestList.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(p, p, p + p / 2, p); isClickable = true
        addView(label("📍", 20f).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams((40 * d).toInt(), WRAP_CONTENT))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(label("Can't find it?", 15f, bold = true).apply { setTextColor(readable(accent)) })
            addView(label("Hold your finger on the map where it is, and save it as \"$missing\"", 13f).apply { setTextColor(dim()) })
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = p / 2 })
        setOnClickListener {
            pinName = missing
            search.setText(""); search.clearFocus(); hideKeyboard(); suggestScroll.visibility = View.GONE; refreshChrome()
            Toast.makeText(this@renderResults, L.t("Hold your finger on the map where it is"), Toast.LENGTH_LONG).show()
        }
    })
    suggestScroll.visibility = View.VISIBLE
    refreshChrome()
    // keep the list from covering the whole screen
    suggestScroll.post {
        val max = (resources.displayMetrics.heightPixels * 0.55f).toInt()
        val lp = suggestScroll.layoutParams
        val want = if (suggestList.height > max) max else WRAP_CONTENT
        if (lp.height != want) { lp.height = want; suggestScroll.layoutParams = lp }
    }
}

internal fun MainActivity.resultRow(pl: Place, removable: Boolean = false): View {
    val p = (12 * d).toInt()
    val near = LocationTracker.position
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(p, p - p / 4, p + p / 2, p - p / 4)
        isClickable = true
        setOnClickListener { choose(pl) }
    }
    row.addView(TextView(this).apply {
        text = pl.icon; textSize = 20f; gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams((40 * d).toInt(), WRAP_CONTENT)
    })
    row.addView(LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(p / 2, 0, p / 2, 0)
        addView(TextView(context).apply {
            text = pl.name; textSize = 16f; setTextColor(ink); typeface = Typeface.DEFAULT_BOLD
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        })
        if (pl.address.isNotBlank()) addView(TextView(context).apply {
            text = pl.address; textSize = 13f; setTextColor(dim(170))
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        })
        val stars = Reviews.stars(pl.rating, pl.ratings)
        if (pl.extra.isNotBlank() || stars != null) addView(TextView(context).apply {
            text = listOfNotNull(stars, pl.extra.takeIf { it.isNotBlank() }).joinToString("  ")
            textSize = 13f; setTextColor(readable(accent)); typeface = Typeface.DEFAULT_BOLD
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        })
    }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
    if (near != null) row.addView(TextView(this).apply {
        text = NavEngine.formatDistance(Geo.distance(near, pl.pos)); textSize = 13f; setTextColor(dim(170))
    })
    if (!removable && pl.icon != "🛣" && pl.icon != "🏙") {
        val saved = Places.isSaved(pl)
        row.addView(TextView(this).apply {
            text = if (saved) "★" else "☆"; textSize = 20f; gravity = Gravity.CENTER
            setTextColor(if (saved) readable(accent) else dim(170))
            contentDescription = if (saved) "Saved: edit" else "Save this place"
            setOnClickListener { showSaveDialog(pl) { text = if (Places.isSaved(pl)) "★" else "☆"; setTextColor(if (Places.isSaved(pl)) readable(accent) else dim(170)) } }
        }, LinearLayout.LayoutParams((44 * d).toInt(), (44 * d).toInt()))
    }
    if (removable) row.addView(removeX("Remove from recent") {
        Places.removeRecent(pl)
        row.animate().alpha(0f).translationX(row.width * 0.3f).setDuration(animMs(180)).withEndAction { showRecents() }.start()
    })
    return row
}

/** A round ✕ that takes something off a list. */
internal fun MainActivity.removeX(desc: String, onClick: () -> Unit) = TextView(this).apply {
    text = "✕"; textSize = 15f; gravity = Gravity.CENTER; setTextColor(dim(200))
    contentDescription = desc
    background = bg(dim(26), radius = 16f, pill = true)
    layoutParams = LinearLayout.LayoutParams((32 * d).toInt(), (32 * d).toInt()).apply { marginStart = (10 * d).toInt() }
    setOnClickListener { onClick() }
}

internal fun MainActivity.choose(pl: Place) {
    val key = pickFor
    closeSearch()
    search.setText("")
    if (key == "convoy") {
        val wasOnIt = Convoy.onConvoyRoute
        Convoy.setDestination(pl)
        if (wasOnIt) { NavEngine.start(this, pl); MapState.recenter() }
        openSheet("convoy")
    } else if (key != null) {
        Places.save(key, pl)
        Toast.makeText(this, L.t((if (key == "home") "Home" else "Work") + " saved: ${pl.name}"), Toast.LENGTH_SHORT).show()
        etaAskedAt = 0
        refreshChrome()
    } else {
        NavEngine.plan(this, pl)
    }
}

internal fun MainActivity.closeSearch() {
    querySeq++
    fuelShown = null; Fuel.pins = emptyList()
    pickFor = null
    suggestScroll.visibility = View.GONE
    search.clearFocus()
    hideKeyboard()
    refreshChrome()
}

internal fun MainActivity.hideKeyboard() {
    getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(search.windowToken, 0)
}
