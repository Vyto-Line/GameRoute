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

internal fun MainActivity.fab(label: String, desc: String, onClick: () -> Unit) = TextView(this).apply {
    text = label
    textSize = 20f
    gravity = Gravity.CENTER
    elevation = 5 * d
    contentDescription = desc
    setOnClickListener { onClick() }
    val s = (50 * d).toInt()
    layoutParams = LinearLayout.LayoutParams(s, s).apply { topMargin = (10 * d).toInt() }
    fabs += this
}

internal fun MainActivity.buildControls() {
    styleBtn = fab("🎮", "Map style") { showStylePicker() }
    recenterBtn = fab("◎", "Recenter") { MapState.phone.recenter() }
    muteBtn = fab("🔊", "Mute voice") { Voice.muted = !Voice.muted }
    overviewBtn = fab("🗺", "Route overview") {
        val r = NavEngine.route
        if (r.size > 1) MapState.phone.showOverview(r.filterIndexed { i, _ -> i % 5 == 0 } + r.last())
    }
    navMenuBtn = fab("☰", "More") { openSheet("quick") }
    reportBtn = fab("⚠", "Report") { openSheet("report") }
    trafficBtn = fab("🚦", "Traffic on the map") {
        Traffic.showLayer = !Traffic.showLayer
        Toast.makeText(this, L.t(if (Traffic.showLayer) "Traffic shown" else "Traffic hidden"), Toast.LENGTH_SHORT).show()
    }
    controls = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        // (Map style sits at the bottom right, by the recenter button)
        addView(trafficBtn); addView(reportBtn); addView(muteBtn); addView(overviewBtn); addView(navMenuBtn)
    }
    askText = label("", 16f, bold = true)
    askCard = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        elevation = 12 * d
        visibility = View.GONE
        isClickable = true
        val p = (14 * d).toInt(); setPadding(p, p, p, p)
        addView(askText)
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(pill("Not there") { askShown?.let { Alerts.vote(it, false) }; Alerts.clearAsk() },
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = (10 * d).toInt() })
            addView(pill("Still there") { askShown?.let { Alerts.vote(it, true) }; Alerts.clearAsk() },
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = (10 * d).toInt() })
    }
}

/** "Via EKO ✕" under the arrival time while there are stops on the way. */
internal fun MainActivity.renderStops() {
    val key = NavEngine.stops.joinToString { it.name } + theme.name + MapState.dark
    if (key == stopsKey) return
    stopsKey = key
    stopsRow.removeAllViews()
    stopsRow.visibility = if (NavEngine.stops.isEmpty()) View.GONE else View.VISIBLE
    for (st in NavEngine.stops) stopsRow.addView(TextView(this).apply {
        text = "Stop: ${st.name}   ✕"; textSize = 15f; typeface = Typeface.DEFAULT_BOLD
        setTextColor(ink); maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE
        val q = (12 * d).toInt(); setPadding(q, (7 * d).toInt(), q, (7 * d).toInt())
        background = bg(Color.argb(60, 255, 196, 0), radius = 18f)
        contentDescription = "Stop at ${st.name}. Tap to remove"
        setOnClickListener {
            GameBuilder(this@renderStops).setMessage("Remove the stop at ${st.name}?")
                .setPositiveButton("Remove") { _, _ -> NavEngine.removeStop(st) }.setNegativeButton("Keep", null).showT()
        }
    }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = (6 * d).toInt() })
}

internal fun MainActivity.buildNavCard() {
    stopsRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
    etaBig = TextView(this).apply {
        textSize = 28f; typeface = Typeface.DEFAULT_BOLD; maxLines = 1
        // long words ("Mission complete" in a wide font) shrink to one line instead of wrapping
        setAutoSizeTextTypeUniformWithConfiguration(16, 28, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
    }
    etaTraffic = TextView(this).apply { textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setPadding((10 * d).toInt(), 0, 0, 0); maxLines = 1 }
    etaSmall = TextView(this).apply { textSize = 15f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    shareBtn = pill("Share") { shareEta() }
    convoyBtn = pill("● Convoy") { openSheet("convoy") }.apply { visibility = View.GONE }
    endBtn = pill("End") { NavEngine.stop() }
    navCard = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        elevation = 8 * d
        visibility = View.GONE
        isClickable = true
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.BOTTOM
                addView(etaBig); addView(etaTraffic)
            })
            addView(etaSmall)
            addView(stopsRow)
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(convoyBtn, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = (8 * d).toInt() })
        addView(shareBtn, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = (8 * d).toInt() })
        addView(endBtn)
    }
}

internal fun MainActivity.shareEta() {
    val opts = if (LiveShare.active) arrayOf("Send the live link again", "Stop sharing live", "Send arrival time only")
        else arrayOf("Share live location (link)", "Send arrival time only")
    GameBuilder(this@shareEta).setTitle("Share your trip")
        .setItems(opts) { _, i ->
            when (opts[i]) {
                "Share live location (link)", "Send the live link again" -> {
                    val link = LiveShare.start()
                    val mins = (NavEngine.remainingSeconds / 60).toInt().coerceAtLeast(1)
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_TEXT, "On my way to ${NavEngine.destinationName}, arriving around ${NavEngine.arrivalClock()} (about $mins min). Follow me live: $link")
                    }
                    startActivity(android.content.Intent.createChooser(send, "Share live location"))
                    refreshChrome()
                }
                "Stop sharing live" -> { LiveShare.stop(); refreshChrome(); Toast.makeText(this, L.t("Live sharing stopped"), Toast.LENGTH_SHORT).show() }
                else -> shareEtaText()
            }
        }.show()
}

internal fun MainActivity.shareEtaText() {
    val mins = (NavEngine.remainingSeconds / 60).toInt().coerceAtLeast(1)
    val msg = "On my way to ${NavEngine.destinationName}: arriving around ${NavEngine.arrivalClock()} (about $mins min)."
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"; putExtra(android.content.Intent.EXTRA_TEXT, msg)
    }
    startActivity(android.content.Intent.createChooser(send, "Share arrival time"))
}

internal fun MainActivity.buildPreview() {
    previewTitle = TextView(this).apply { textSize = 20f; typeface = Typeface.DEFAULT_BOLD }
    previewSub = TextView(this).apply { textSize = 13f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    previewClose = pill("✕") { NavEngine.cancelPlan() }
    previewSave = pill("☆") { NavEngine.planPlace?.let { p -> showSaveDialog(p) { renderPreview() } } }.apply {
        textSize = 20f; contentDescription = "Save this place"
    }
    previewBack = pill("←") { NavEngine.cancelPlan(); search.requestFocus(); showSuggestions() }
    previewList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    laterBtn = pill("Leave later") { pickDeparture() }
    convoyNewBtn = pill("👥") { startConvoyFromPreview() }
    startBtn = pill("Start") { NavEngine.startPlanned() }
    preview = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        elevation = 10 * d
        visibility = View.GONE
        isClickable = true
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(previewBack)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(previewTitle); addView(previewSub)
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(previewSave)
            addView(previewClose)
        })
        addView(previewList, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = (8 * d).toInt() })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(laterBtn, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = (10 * d).toInt() })
            addView(convoyNewBtn, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = (10 * d).toInt() })
            addView(startBtn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = (12 * d).toInt() })
    }
}

internal fun MainActivity.pickDeparture() {
    if (NavEngine.departAt != null) { NavEngine.setDeparture(null); return }
    val cal = java.util.Calendar.getInstance().apply { add(java.util.Calendar.MINUTE, 30) }
    android.app.TimePickerDialog(this, { _, h, m ->
        val t = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, h); set(java.util.Calendar.MINUTE, m); set(java.util.Calendar.SECOND, 0)
            if (timeInMillis < System.currentTimeMillis() - 60_000) add(java.util.Calendar.DAY_OF_MONTH, 1)
        }
        NavEngine.setDeparture(t.timeInMillis)
    }, cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE), true).show()
}

/** Fuel prices and stars of the destination, with a Reviews button (loaded once per place). */
internal fun MainActivity.placeInfo(place: Place): View? {
    if (infoAsked != place) {
        infoAsked = place
        infoFuel = place.extra.ifBlank { null }
        val wantsFuel = infoFuel == null && place.icon == "⛽"
        if (wantsFuel || Reviews.enabled) io.execute {
            val f = if (wantsFuel) try { Fuel.forPlace(place) } catch (e: Exception) { null } else null
            if (Reviews.enabled) try { Reviews.details(place) } catch (e: Exception) {}
            runOnUiThread { if (infoAsked == place && !isFinishing) { if (f != null) infoFuel = f; if (NavEngine.planningOn(false)) renderPreview() } }
        }
    }
    val info = Reviews.cached(place)
    val stars = Reviews.stars(info?.rating ?: place.rating, info?.count ?: place.ratings)
    val fuel = infoFuel
    val reviewable = place.icon != "🛣" && place.icon != "🏙" && place.address != "Street" && place.address != "Area"
    // nothing to show (no stars, no prices): no row at all, rather than a lone "Reviews"
    if (stars == null && fuel == null) return null
    val p = (12 * d).toInt()
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(p, p / 2, p / 2, p / 2)
    }
    val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    top.addView(TextView(this).apply {
        text = stars ?: if (Reviews.enabled && info == null) "Loading rating…" else if (fuel != null) "⛽ Prices" else "Reviews"
        textSize = 15f; typeface = Typeface.DEFAULT_BOLD
        setTextColor(if (stars != null) Color.rgb(255, 193, 7).let { if (Ui.contrast(it, panel) < 3) readable(it) else it } else ink)
    }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
    if (reviewable) top.addView(TextView(this).apply {
        text = "Reviews ›"; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setTextColor(readable(accent))
        setPadding(p, p / 2, p / 2, p / 2)
        isClickable = true
        setOnClickListener { showReviews(place) }
    })
    box.addView(top)
    if (fuel != null) box.addView(TextView(this).apply {
        text = fuel; textSize = 14f; setTextColor(ink)
    })
    return box
}
