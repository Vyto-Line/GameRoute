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

/** The card under Where to? when location is off or GameRoute isn't allowed to use it. */
internal fun MainActivity.syncLocCard() {
    if (!locCardReady) return
    val st = LocationStatus.state
    if (st == LocationStatus.State.OK) { locCard.visibility = View.GONE; return }
    val off = st == LocationStatus.State.OFF
    val red = Color.rgb(255, 107, 107)
    val p = (12 * d).toInt()
    locCard.removeAllViews()
    locCard.background = bg(opaque(panel), red, 2f, 14f)
    locCard.setPadding(p, p, p, p)
    locCard.addView(TextView(this).apply { text = "⌖"; textSize = 24f; setTextColor(red); paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG },
        LinearLayout.LayoutParams((34 * d).toInt(), WRAP_CONTENT))
    locCard.addView(LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding((8 * d).toInt(), 0, (8 * d).toInt(), 0)
        addView(label(L.t(if (off) "Location is off" else "GameRoute can't use your location"), 15.5f, bold = true).apply { setTextColor(ink) })
        addView(label(L.t(if (off) "GameRoute can't find you or guide you" else "Allow it so GameRoute can find you"), 13f).apply { setTextColor(dim()) })
    }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
    locCard.addView(pill(L.t(if (off) "Turn on" else "Allow")) { fixLocation(off) }.apply {
        setTextColor(onAccent(accent)); background = bg(accent, radius = 12f); textSize = 15f
        minHeight = (42 * d).toInt(); setPadding((16 * d).toInt(), 0, (16 * d).toInt(), 0)
    }, LinearLayout.LayoutParams(WRAP_CONTENT, (42 * d).toInt()))
    locCard.visibility = View.VISIBLE
}

/**
 * In the evening and at night (and on Sundays), when a pharmacy on duty is within 3 km and you're
 * not on a route: one slim bar under Where to? ("2 pharmacies on duty near you"). ✕ hides it till tomorrow.
 */
internal fun MainActivity.syncDutyCard() {
    if (!dutyCardReady) return
    val now = Duties.now()
    val near = LocationTracker.position
    val prefs = getSharedPreferences("gamemap", Context.MODE_PRIVATE)
    val today = run { java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("Europe/Athens") }.format(java.util.Date(now - 8 * 3_600_000L)) }
    val list = if (near == null || NavEngine.active || NavEngine.planningOn(false) || Convoy.active || !Duties.usuallyClosed(now) ||
        prefs.getString("dutyBarHidden", "") == today || LocationStatus.state != LocationStatus.State.OK) emptyList()
        else Duties.onDutyNear(near, 3000.0, now)
    val key = if (list.isEmpty()) "" else "${list.size}|${list[0].name}|${NavEngine.formatDistance(Geo.distance(near!!, list[0].pos))}|${theme.name}|${MapState.dark}"
    if (key == dutyKey) return
    dutyKey = key
    if (list.isEmpty()) { dutyCard.visibility = View.GONE; return }
    val p = (12 * d).toInt()
    val moon = readable(PoiLayer.MOON)
    dutyCard.removeAllViews()
    dutyCard.background = bg(opaque(panel), moon, 2f, 14f)
    dutyCard.setPadding(p, p * 3 / 4, p / 2, p * 3 / 4)
    dutyCard.isClickable = true
    dutyCard.setOnClickListener { showPharmacies() }
    dutyCard.addView(label("🌙", 22f), LinearLayout.LayoutParams((34 * d).toInt(), WRAP_CONTENT))
    dutyCard.addView(LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding((6 * d).toInt(), 0, (6 * d).toInt(), 0)
        addView(label(L.t(if (list.size == 1) "A pharmacy on duty near you" else "${list.size} pharmacies on duty near you"), 15f, bold = true).apply { setTextColor(ink) })
        addView(label(L.t("Nearest ") + NavEngine.formatDistance(Geo.distance(near!!, list[0].pos)) + " · " + Duties.till(list[0], now).orEmpty(), 13f).apply { setTextColor(dim()) })
    }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
    dutyCard.addView(label(L.t("Show") + " ›", 15f, bold = true).apply { setTextColor(moon); setPadding(p / 2, p / 2, p / 2, p / 2) })
    dutyCard.addView(label("✕", 15f).apply {
        setTextColor(dim()); gravity = Gravity.CENTER; contentDescription = L.t("Hide till tomorrow"); isClickable = true
        setOnClickListener { prefs.edit().putString("dutyBarHidden", today).apply(); dutyKey = "x"; dutyCard.visibility = View.GONE }
    }, LinearLayout.LayoutParams((40 * d).toInt(), (40 * d).toInt()))
    dutyCard.visibility = View.VISIBLE
}

internal fun MainActivity.poisShownForTest(): List<PoiLayer.Poi> = map.rendererForTest.pois.shownNow()

internal fun MainActivity.dutyCardForTest(): View? = if (dutyCardReady && dutyCard.visibility == View.VISIBLE) dutyCard else null

internal fun MainActivity.fixLocation(off: Boolean) {
    try {
        if (off) startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        else if (shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) ||
            getSharedPreferences("gamemap", Context.MODE_PRIVATE).getInt("locAsked", 0) == 0) {
            getSharedPreferences("gamemap", Context.MODE_PRIVATE).edit().putInt("locAsked", 1).apply()
            permissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        } else startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")))
    } catch (e: Exception) { CrashLog.note("location settings: $e") }
}

/** For tests. */
internal fun MainActivity.locCardForTest(): View? = if (locCardReady && locCard.visibility == View.VISIBLE) locCard else null

/** After a crash: offer to send the saved report (shared like any text; nothing is sent by itself). */
internal fun MainActivity.offerCrashReport(report: String) {
    GameBuilder(this@offerCrashReport)
        .setTitle("GameRoute closed unexpectedly")
        .setMessage("A report of what happened was saved on your phone. Send it so the problem can be fixed?")
        .setPositiveButton("Send report") { _, _ ->
            CrashLog.clear()
            val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "GameRoute crash report")
                .putExtra(Intent.EXTRA_TEXT, report)
            try { startActivity(Intent.createChooser(send, "Send the report with…")) } catch (_: Exception) {}
        }
        .setNegativeButton("Not now") { _, _ -> CrashLog.clear() }
        .showT()
}
