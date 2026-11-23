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

internal fun MainActivity.buildOffer() {
    offerTitle = TextView(this).apply { textSize = 20f; typeface = Typeface.DEFAULT_BOLD; text = "⚡ Faster route found" }
    offerSub = TextView(this).apply { textSize = 15f; typeface = Typeface.DEFAULT_BOLD }
    offerVia = TextView(this).apply { textSize = 14f }
    keepBtn = pill("Keep current") { NavEngine.declineOffer() }
    switchBtn = pill("Switch") { NavEngine.acceptOffer() }
    offerCard = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        elevation = 12 * d
        visibility = View.GONE
        isClickable = true
        val p = (14 * d).toInt()
        setPadding(p + p / 4, p, p + p / 4, p)
        addView(offerTitle); addView(offerSub); addView(offerVia)
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(keepBtn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = (10 * d).toInt() })
            addView(switchBtn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1.3f))
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = (12 * d).toInt() })
    }
}

internal fun MainActivity.renderOffer() {
    val o = NavEngine.offer ?: return
    val green = Color.rgb(61, 220, 132)
    offerCard.background = bg(panel, green, 2f, 18f)
    offerTitle.setTextColor(ink); offerTitle.typeface = theme.typeface
    val arrive = NavEngine.clock(System.currentTimeMillis() + (o.option.seconds * 1000).toLong())
    offerSub.text = "Saves ${(o.savingSeconds + 30) / 60} min · arrive $arrive"
    offerSub.setTextColor(readable(green))
    val first = o.option.steps.firstOrNull { it.type != "depart" }
    offerVia.text = listOfNotNull(
        o.option.via.takeIf { it.isNotBlank() }?.let { "Via $it" },
        first?.let { "Next: ${it.text}" },
    ).joinToString("\n")
    offerVia.setTextColor(dim(200))
    keepBtn.setTextColor(ink)
    keepBtn.background = bg(Color.TRANSPARENT, dim(200), 1.5f, 14f)
    val left = (NavEngine.OFFER_SECONDS - (System.currentTimeMillis() - o.createdAt) / 1000).coerceAtLeast(0)
    switchBtn.text = if (NavEngine.autoSwitch) "Switch · ${left}s" else "Switch"
    keepBtn.text = if (NavEngine.autoSwitch) "Keep current" else "Keep · ${left}s"
    switchBtn.setTextColor(Color.rgb(11, 36, 22))
    switchBtn.background = bg(green, radius = 14f)
}

internal fun MainActivity.renderTrip(trip: Explorer.Trip) {
    val t = theme
    tripCard.removeAllViews()
    val p = (20 * d).toInt()
    tripCard.setPadding(p, p, p, p)
    tripCard.background = bg(panel, accent, 2f, 22f)
    tripCard.addView(label(Explorer.completeTitle(t).uppercase(), 13f, bold = true).apply {
        setTextColor(onAccent(accent)); letterSpacing = 0.12f; gravity = Gravity.CENTER
        val q = (8 * d).toInt(); setPadding(q * 2, q, q * 2, q)
        background = bg(accent, radius = 8f)
    })
    tripCard.addView(label(trip.place, 22f, bold = true).apply {
        setTextColor(ink); typeface = t.typeface; gravity = Gravity.CENTER; setPadding(0, (14 * d).toInt(), 0, 0)
    })
    tripCard.addView(label("Arrived ${NavEngine.clock(trip.arrivedAt)}", 14f).apply { setTextColor(dim()); gravity = Gravity.CENTER })
    if (Convoy.active && Convoy.meAlong) tripCard.addView(label("Convoy: you drove along (not in the race)", 14f).apply { setTextColor(dim()); gravity = Gravity.CENTER; setPadding(0, (6 * d).toInt(), 0, 0) })
    Convoy.myPlace()?.let { (place, of) ->
        val nth = when (place) { 1 -> "1st 👑"; 2 -> "2nd"; 3 -> "3rd"; else -> "${place}th" }
        tripCard.addView(label("Convoy: $nth of $of", 15f, bold = true).apply { setTextColor(readable(accent)); gravity = Gravity.CENTER; setPadding(0, (6 * d).toInt(), 0, 0) })
        // the others still racing: follow them live (the card stays until everyone's in)
        tripCard.addView(pill("🏁  " + L.t("See the race")) { NavEngine.dismissTrip(); openSheet("convoy") }.apply {
            textSize = 15f; setTextColor(readable(accent)); background = bg(Color.TRANSPARENT, accent, 1.5f, 12f)
        }, LinearLayout.LayoutParams(MATCH_PARENT, (46 * d).toInt()).apply { topMargin = (8 * d).toInt() })
    }
    // the people I drove with who aren't friends yet: one tap to add
    if (Friends.ready && Convoy.active) {
        val c = Friends.core
        val others = Convoy.members.values.filter { it.fc.isNotBlank() && !c.isFriend(it.fid) && it.fid !in c.blocked }
        if (others.isNotEmpty()) {
            tripCard.addView(label("DROVE WITH YOU", 11.5f, bold = true).apply { setTextColor(dim()); letterSpacing = 0.1f; setPadding(0, (14 * d).toInt(), 0, (4 * d).toInt()) })
            for (m in others.take(4)) tripCard.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, (4 * d).toInt(), 0, (4 * d).toInt())
                addView(avatar(m.name, m.fid, false, 32), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = (10 * d).toInt() })
                addView(label(m.name, 15f, bold = true).apply { setTextColor(ink) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                if (c.requested(m.fc)) addView(label("Request sent", 13.5f).apply { setTextColor(dim()) })
                else addView(pill("＋ Add") { }.apply {
                    textSize = 14f; setTextColor(readable(accent)); background = bg(Color.TRANSPARENT, accent, 1.5f, 12f)
                    setOnClickListener { v -> sendFriendRequest(m.fc, m.name) { (v as TextView).text = L.t("Request sent"); v.background = null; v.isEnabled = false } }
                })
            })
        }
    }
    val speed = if (MapState.imperial) "${(trip.avgKmh * 0.621).toInt()} mph" else "${trip.avgKmh} km/h"
    tripCard.addView(label(listOfNotNull(NavEngine.formatDuration(trip.seconds.toDouble()), NavEngine.formatDistance(trip.meters),
        if (trip.savedSeconds >= 60) L.t("${trip.savedSeconds / 60} min saved") else L.t("avg $speed")).joinToString(" · "), 14f).apply {
        setTextColor(dim()); gravity = Gravity.CENTER; setPadding(0, (4 * d).toInt(), 0, 0)
    })
    // the XP: one line with the level bar; tap it for what each part earned
    val (have, of) = Explorer.progress()
    val up = Explorer.level > trip.levelBefore
    val gained = (if (up) have else minOf(trip.xp, have)).toFloat() / maxOf(of, 1)
    tripCard.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        val q = (12 * d).toInt(); setPadding(q, q * 3 / 4, q / 2, q * 3 / 4)
        background = bg(Color.argb(30, Color.red(accent), Color.green(accent), Color.blue(accent)), Color.argb(120, Color.red(accent), Color.green(accent), Color.blue(accent)), 1.2f, 14f)
        isClickable = true; setOnClickListener { showXpParts(trip) }
        contentDescription = L.t("+${trip.xp} XP. Tap for the details")
        addView(label(if (trip.xp == 0) L.t("No XP") else "+${trip.xp} XP", 22f, bold = true).apply { setTextColor(readable(accent)); typeface = t.typeface; setPadding(0, 0, q, 0) })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(object : View(context) {
                private val pt = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                override fun onDraw(c: Canvas) {
                    val r = height / 2f; val f = have.toFloat() / maxOf(of, 1)
                    pt.color = dim(50); c.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, pt)
                    pt.color = accent; c.drawRoundRect(0f, 0f, width * (f - gained).coerceAtLeast(0f), height.toFloat(), r, r, pt)
                    pt.color = Color.rgb(255, 46, 136); c.drawRoundRect(width * (f - gained).coerceAtLeast(0f), 0f, width * f, height.toFloat(), r, r, pt)
                }
            }, LinearLayout.LayoutParams(MATCH_PARENT, (8 * d).toInt()))
            addView(label(if (trip.xp == 0) L.t("trips under 200 m") else if (up) L.t("Level up! Level ${Explorer.level}") else L.t("Level ${Explorer.level} · ${of - have} to go"), 13f, bold = up).apply {
                setTextColor(if (up) readable(accent) else dim()); setPadding(0, (4 * d).toInt(), 0, 0)
            })
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(label("›", 22f).apply { setTextColor(dim()); setPadding(q / 2, 0, 0, 0) })
    }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = (14 * d).toInt() })
    if (up) pendingLevelFrom = trip.levelBefore
    // the Garage's arrival effect (you've stopped: the card only shows once you arrive)
    val fx = Xp.chosen("arrival").id
    if (fx != "arrival_classic" && tripFxFor !== trip && Perf.anim == Perf.FULL) {
        tripFxFor = trip
        tripOverlay.addView(ArrivalFx(this, fx), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }
    tripCard.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        // both the same height, one line each (a two-line Parking button broke its outline)
        val h = (52 * d).toInt()
        addView(pill("🅿  " + L.t("Parking")) {
            if (NavEngine.arrived) NavEngine.stop()
            NavEngine.dismissTrip(); showNearby("PARKING NEARBY", listOf("parking"))
        }.apply {
            setTextColor(ink); textSize = 15f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER
            setPadding((8 * d).toInt(), 0, (8 * d).toInt(), 0); minHeight = 0; minimumHeight = 0
            background = bg(Color.TRANSPARENT, dim(160), 1.5f, 14f)
            contentDescription = L.t("Parking nearby")
        }, LinearLayout.LayoutParams(0, h, 1f).apply { marginEnd = (10 * d).toInt() })
        // Done: the route is over too (no "Mission complete" bar left behind)
        addView(pill("Done") { if (NavEngine.arrived) NavEngine.stop(); NavEngine.dismissTrip() }.apply {
            setTextColor(onAccent(accent)); typeface = t.typeface; background = bg(accent, radius = 14f)
            maxLines = 1; gravity = Gravity.CENTER; setPadding((8 * d).toInt(), 0, (8 * d).toInt(), 0); minHeight = 0; minimumHeight = 0
        }, LinearLayout.LayoutParams(0, h, 1f))
    }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = (18 * d).toInt() })
}
