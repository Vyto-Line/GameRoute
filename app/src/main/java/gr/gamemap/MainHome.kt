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

/** Trailblazer: "N landmarks to discover nearby" with your level. */
internal fun MainActivity.renderExploreCard(near: LatLng?) {
    exploreCard?.let { homeSheet.removeView(it) }
    exploreCard = null
    if (theme != Theme.TRAILBLAZER) return
    val todo = near?.let { Explorer.near(it, 1500.0).filterNot { l -> Explorer.isFound(l) } } ?: emptyList()
    val card = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val p = (12 * d).toInt()
        setPadding(p, p, p, p)
        background = bg(Color.argb(40, Color.red(accent), Color.green(accent), Color.blue(accent)), radius = 16f)
        setOnClickListener {
            if (todo.isNotEmpty() && near != null) MapState.phone.requestFit(todo.map { it.pos } + near)
            else Toast.makeText(this@renderExploreCard, L.t("Drive or walk around to find landmarks"), Toast.LENGTH_SHORT).show()
        }
    }
    card.addView(label("★", 20f, bold = true).apply {
        gravity = Gravity.CENTER; setTextColor(onAccent(accent))
        background = bg(accent, radius = 20f, pill = true)
    }, LinearLayout.LayoutParams((40 * d).toInt(), (40 * d).toInt()).apply { marginEnd = (12 * d).toInt() })
    card.addView(LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(label(when {
            near == null -> "Landmarks appear once GPS finds you"
            todo.isEmpty() -> "No new landmarks nearby"
            todo.size == 1 -> "1 landmark to discover nearby"
            else -> "${todo.size} landmarks to discover nearby"
        }, 15f, bold = true).apply { setTextColor(ink) })
        val (have, of) = Explorer.progress()
        addView(label("Level ${Explorer.level} · $have / $of XP · ${Explorer.foundCount} found", 13f).apply { setTextColor(dim()) })
    }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
    card.addView(label("›", 22f).apply { setTextColor(dim()) })
    homeSheet.addView(card, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
        val m = (14 * d).toInt(); setMargins(m, (10 * d).toInt(), m, 0)
    })
    exploreCard = card
}

internal fun MainActivity.renderHomeSheet() {
    val fav = Places.savedPlaces()
    val rec = (fav + Places.recents().filter { r -> fav.none { Geo.distance(it.pos, r.pos) < 25 } }).take(8)
    homeTitle.text = if (fav.isEmpty()) "Recent" else "Saved & recent"
    homeSheet.background = bg(panel, radius = 22f, topOnly = true)
    homeTitle.setTextColor(ink); homeTitle.typeface = theme.typeface
    homeCards.removeAllViews()
    val near = LocationTracker.position
    // in a convoy: a card to get back to it (who's in, where to, and the next step)
    if (Convoy.active) homeCards.addView(LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val p = (12 * d).toInt(); setPadding(p, p - p / 4, p, p - p / 4)
        background = bg(Color.argb(46, Color.red(accent), Color.green(accent), Color.blue(accent)), accent, 2f, 14f)
        setOnClickListener { openSheet("convoy") }
        val n = Convoy.members.values.count { it.state != "left" } + 1
        addView(label("👥 " + (Convoy.destination?.let { L.t("To ${it.name}") } ?: L.t("Convoy")), 15f, bold = true).apply { setTextColor(ink); maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        addView(label(L.t("$n in it") + " · " + L.t(Convoy.status()), 13f).apply { setTextColor(dim()); maxLines = 1 })
        addView(label(L.t("Open") + " ›", 13f, bold = true).apply { setTextColor(readable(accent)); setPadding(0, (4 * d).toInt(), 0, 0) })
    }, LinearLayout.LayoutParams((176 * d).toInt(), WRAP_CONTENT).apply { marginEnd = (10 * d).toInt() })
    Parked.place()?.let { car ->
        homeCards.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (12 * d).toInt(); setPadding(p, p - p / 4, p, p - p / 4)
            background = bg(Color.argb(40, Color.red(accent), Color.green(accent), Color.blue(accent)), accent, 1.5f, 14f)
            setOnClickListener { showPlace(car, dropped = false) }
            addView(label("🚗 " + car.name, 15f, bold = true).apply { setTextColor(ink); maxLines = 1 })
            addView(label(listOfNotNull(near?.let { NavEngine.formatDistance(Geo.distance(it, car.pos)) }, car.address).joinToString(" · "), 13f).apply {
                setTextColor(dim()); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            })
        }, LinearLayout.LayoutParams((176 * d).toInt(), WRAP_CONTENT).apply { marginEnd = (10 * d).toInt() })
    }
    for (pl in rec) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (12 * d).toInt()
            setPadding(p, p - p / 4, p, p - p / 4)
            background = bg(Color.argb(28, Color.red(ink), Color.green(ink), Color.blue(ink)), dim(60), 1f, 14f)
            setOnClickListener { homeOpen = false; NavEngine.plan(this@renderHomeSheet, pl) }
        }
        val isSaved = fav.any { it === pl }
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(label((if (pl.icon != "🕘" && Places.isSaved(pl)) "${pl.icon} " else "") + pl.name, 15f, bold = true).apply { setTextColor(ink); maxLines = 1; ellipsize = TextUtils.TruncateAt.END },
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            if (!isSaved) addView(TextView(context).apply {
                text = "✕"; textSize = 13f; setTextColor(dim(170)); gravity = Gravity.CENTER
                contentDescription = "Remove from recent"
                setPadding((8 * d).toInt(), 0, 0, 0)
                setOnClickListener {
                    Places.removeRecent(pl)
                    card.animate().alpha(0f).scaleX(0.85f).scaleY(0.85f).setDuration(animMs(180)).withEndAction { lastHomeKey = ""; refreshChrome() }.start()
                }
            })
        })
        val dist = near?.let { NavEngine.formatDistance(Geo.distance(it, pl.pos)) }
        card.addView(label(listOfNotNull(pl.address.split(",").firstOrNull()?.takeIf { it.isNotBlank() }, dist).joinToString(" · "), 13f).apply {
            setTextColor(dim()); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        })
        Places.cachedEta(pl)?.let { e ->
            val mins = (e.seconds + 30) / 60
            val late = e.delaySeconds / 60
            card.addView(label(if (late >= 2) "● $mins min · +$late traffic" else "● $mins min · clear", 13f, bold = true).apply {
                setTextColor(readable(if (late >= 2) Color.rgb(255, 120, 80) else Color.rgb(47, 210, 122)))
                setPadding(0, (4 * d).toInt(), 0, 0)
            })
        }
        homeCards.addView(card, LinearLayout.LayoutParams((176 * d).toInt(), WRAP_CONTENT).apply { marginEnd = (10 * d).toInt() })
    }
    val anyCard = rec.isNotEmpty() || Parked.get() != null || Convoy.active
    // always laid out open; tucked away = slid down so only the bar shows
    homeTitle.visibility = if (anyCard) View.VISIBLE else View.GONE
    homeCards.visibility = if (anyCard) View.VISIBLE else View.GONE
    (homeCards.parent as? View)?.visibility = if (anyCard) View.VISIBLE else View.GONE
    // tucked away: one slim bar ("⌃ Saved & recent · 🚗 Car 350 m"), the car's distance kept in sight
    homeHandle.setTextColor(dim(210))
    homeHandle.text = listOfNotNull("⌃ " + L.t(if (fav.isEmpty()) "Recent" else "Saved & recent"),
        Parked.place()?.let { car -> "🚗 " + L.t("Car") + (near?.let { " " + NavEngine.formatDistance(Geo.distance(it, car.pos)) } ?: "") },
        if (Convoy.active) "👥 " + L.t("Convoy") else null).joinToString(" · ")
    homeHandle.contentDescription = L.t("Open Saved & recent")
    renderExploreCard(near)
    if (homeAnim?.isRunning != true && !homeDragging) homeSheet.post { setHomeFraction(if (homeOpen) 1f else 0f) }
    // live ETAs for recents, home and work (once a minute at most; TomTom answers are cached 5 min)
    if (near != null && Traffic.active && System.currentTimeMillis() - etaAskedAt > 60_000) {
        etaAskedAt = System.currentTimeMillis()
        val targets = (listOfNotNull(Places.saved("home"), Places.saved("work")) + Places.labelled().map { it.place() } + rec).filter { Places.cachedEta(it) == null }
        if (targets.isNotEmpty()) io.execute {
            var any = false
            for (t in targets) if (Places.refreshEta(t, near) != null) any = true
            if (any) runOnUiThread { etaVersion++; refreshChrome() }
        }
    }
}
