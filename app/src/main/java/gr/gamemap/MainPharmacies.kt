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

internal fun MainActivity.showPharmaciesForTest() = showPharmacies()

/** Pharmacies nearby: the ones on duty now first (from the duty list), then the others. */
internal fun MainActivity.showPharmacies() {
    val near = LocationTracker.position
    if (near == null) { Toast.makeText(this, L.t("Waiting for your location…"), Toast.LENGTH_SHORT).show(); return }
    Duties.refresh()
    val seq = ++querySeq
    lastResults = emptyList()
    pharmShown = null
    renderPharmacies(loading = true)
    io.execute {
        val local = try { Places.nearbyLocal(listOf("pharmacy"), near) } catch (e: Exception) { emptyList<Place>() }
        if (local.isNotEmpty()) runOnUiThread { if (seq == querySeq && !isFinishing) { pharmShown = local; renderPharmacies(loading = true) } }
        val r = try { Places.nearbyFull(listOf("pharmacy"), near, local) } catch (e: Exception) { null }
        runOnUiThread {
            if (seq != querySeq || isFinishing) return@runOnUiThread
            pharmShown = r ?: local
            renderPharmacies(loading = false)
        }
    }
}

internal fun MainActivity.renderPharmacies(loading: Boolean) {
    val near = LocationTracker.position ?: return
    val now = Duties.now()
    val p = (12 * d).toInt()
    val moon = readable(PoiLayer.MOON)
    val closed = Duties.usuallyClosed(now)
    suggestList.removeAllViews()
    fun head(t: String, c: Int) = suggestList.addView(label(t, 12f, bold = true).apply {
        setTextColor(c); letterSpacing = 0.1f; setPadding(p + p / 2, p, p, p / 3)
    })
    head(L.t("PHARMACIES NEARBY"), dim(170))
    val duties = Duties.onDutyNear(near, 15_000.0, now).take(6)
    if (Duties.covers(near)) {
        head("🌙 " + L.t("ON DUTY NOW"), moon)
        if (duties.isEmpty()) suggestList.addView(label(L.t("None on duty right now within 15 km"), 14f).apply { setTextColor(dim()); setPadding(p + p / 2, 0, p, p / 2) })
        duties.forEachIndexed { i, dt -> suggestList.addView(pharmacyRow(Duties.place(dt, now), "🌙 " + Duties.till(dt, now), moon, dt.phone, first = i == 0)) }
    } else if (Duties.all.isEmpty()) suggestList.addView(label(L.t(if (Duties.updated == 0L) "Getting the duty list…" else "No duty list for this area yet"), 14f).apply { setTextColor(dim()); setPadding(p + p / 2, 0, p, p / 2) })
    val others = pharmShown.orEmpty().filter { o -> duties.none { Geo.distance(it.pos, o.pos) < 60 } }
        .sortedBy { Geo.distance(near, it.pos) }
        .fold(ArrayList<Place>()) { acc, o -> if (acc.none { Geo.distance(it.pos, o.pos) < 40 }) acc += o; acc }.take(8)
    if (others.isNotEmpty()) {
        head(L.t(if (closed) "CLOSED NOW" else "OTHER PHARMACIES"), dim(170))
        for (o in others) {
            val next = Duties.at(o.pos)?.takeIf { !it.onDuty(now) }?.let { Duties.hoursLine(it, now) }
            val status = when {
                next != null -> "🌙 $next"
                closed -> L.t("Closed") + " · " + Duties.opensLine(now)
                else -> ""
            }
            suggestList.addView(pharmacyRow(o, status, dim(), null, faded = closed))
        }
    } else if (loading) suggestList.addView(label(L.t("Searching nearby…"), 14f).apply { setTextColor(dim()); setPadding(p + p / 2, p / 2, p, p / 2) })
    if (Duties.covers(near)) suggestList.addView(label(Duties.sourceLine(duties.firstOrNull()), 11.5f).apply { setTextColor(dim()); setPadding(p + p / 2, p / 2, p, p) })
    suggestScroll.visibility = View.VISIBLE
    refreshChrome()
    suggestScroll.post {
        val max = (resources.displayMetrics.heightPixels * 0.62f).toInt()
        val lp = suggestScroll.layoutParams
        val want = if (suggestList.height > max) max else WRAP_CONTENT
        if (lp.height != want) { lp.height = want; suggestScroll.layoutParams = lp }
    }
}

/** One pharmacy: cross, name, status (on duty till… / closed), distance and area, then Call and Go. */
internal fun MainActivity.pharmacyRow(pl: Place, status: String, statusColor: Int, phone: String?, first: Boolean = false, faded: Boolean = false): View {
    val p = (12 * d).toInt()
    val near = LocationTracker.position
    val moon = readable(PoiLayer.MOON)
    return LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(p, p * 3 / 4, p, p * 3 / 4)
        background = when {
            first -> bg(Color.argb(36, 177, 151, 252), moon, 2f, 14f)
            phone != null -> bg(Color.argb(24, 177, 151, 252), radius = 14f)
            else -> null
        }
        if (faded) alpha = 0.6f
        isClickable = true
        setOnClickListener { openPharmacy(pl) }
        addView(label("✚", 18f, bold = true).apply {
            gravity = Gravity.CENTER; setTextColor(Color.WHITE); background = bg(Color.rgb(47, 158, 68), radius = 19f)
        }, LinearLayout.LayoutParams((38 * d).toInt(), (38 * d).toInt()))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(p, 0, p / 2, 0)
            addView(label(pl.name, 16f, bold = true).apply { setTextColor(ink); maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            if (status.isNotBlank()) addView(label(status, 13.5f, bold = true).apply { setTextColor(statusColor); maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            val where = listOfNotNull(near?.let { NavEngine.formatDistance(Geo.distance(it, pl.pos)) }, pl.address.substringAfterLast(", ").takeIf { it.isNotBlank() && it != "Pharmacy" && it != pl.name }).joinToString(" · ")
            addView(label(where, 13f).apply { setTextColor(dim()); maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val sq = (42 * d).toInt()
        if (!phone.isNullOrBlank()) addView(label("📞", 17f).apply {
            gravity = Gravity.CENTER; background = bg(Color.TRANSPARENT, dim(120), 1.5f, 12f)
            contentDescription = L.t("Call"); isClickable = true
            setOnClickListener { dial(phone) }
        }, LinearLayout.LayoutParams(sq, sq).apply { marginEnd = p / 2 })
        addView(label("➤", 18f, bold = true).apply {
            gravity = Gravity.CENTER; setTextColor(onAccent(accent)); background = bg(if (faded) dim(60) else accent, radius = 12f)
            contentDescription = L.t("Go"); isClickable = true
            setOnClickListener { openPharmacy(pl) }
        }, LinearLayout.LayoutParams(sq, sq))
    }.also { it.layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { setMargins(p / 2, p / 6, p / 2, p / 6) } }
}

/** From the list: the pharmacy on the map with its card (Go, Call), not straight into a route. */
internal fun MainActivity.openPharmacy(pl: Place) {
    closeSearch(); search.setText(""); hideKeyboard()
    showPlace(pl, dropped = false)
    // the pharmacy in the middle of the map, above its card (not at the edge)
    MapState.phone.pan(pl.pos, MapState.phone.userBearing ?: 0f)
    refreshChrome()
}

internal fun MainActivity.dial(phone: String) {
    try { startActivity(Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:" + phone.filter { it.isDigit() || it == '+' }))) }
    catch (e: Exception) { Toast.makeText(this, phone, Toast.LENGTH_LONG).show() }
}
