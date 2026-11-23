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

internal fun MainActivity.dropPin(p: LatLng) {
    // the pin shows at once; its street address follows
    val name = pinName
    pinName = null
    showPlace(Place(name ?: "Dropped pin", "Finding the address…", p, "📍"), dropped = true)
    io.execute {
        val a = try { Places.addressAt(p) } catch (e: Exception) { null }
        runOnUiThread {
            if (placeShown?.pos != p) return@runOnUiThread
            // from "Can't find it?": the place under the name you searched for, ready to save
            val pl = if (name != null) Place(name, a?.let { listOf(it.name, it.address).filter { s -> s.isNotBlank() }.joinToString(", ") } ?: "", p, "📍") else a ?: return@runOnUiThread
            showPlace(pl, dropped = name == null)
            if (name != null) showSaveDialog(pl) { showPlace(Places.savedAt(p)?.place() ?: pl, dropped = false) }
        }
    }
}

internal fun MainActivity.closePlace() {
    placeShown = null
    map.selected = null
    placeCard.visibility = View.GONE
    refreshChrome()
}

internal fun MainActivity.showPlace(pl: Place, dropped: Boolean) {
    val first = placeShown?.pos != pl.pos
    placeShown = pl
    map.selected = pl
    if (first) {
        placeEta = null
        val near = LocationTracker.position
        if (near != null) io.execute {
            val e = Places.refreshEta(pl, near)
            val f = if (pl.icon == "⛽" && pl.extra.isBlank()) try { Fuel.forPlace(pl) } catch (_: Exception) { null } else null
            if (Reviews.enabled && !dropped && pl.icon != "👥") try { Reviews.details(pl) } catch (_: Exception) {}
            runOnUiThread {
                if (placeShown?.pos != pl.pos) return@runOnUiThread
                if (e != null) placeEta = "${(e.seconds + 30) / 60} min"
                showPlace(if (f != null) pl.copy(extra = f) else placeShown!!, dropped)
            }
        }
    }
    val p = (12 * d).toInt()
    placeCard.removeAllViews()
    placeCard.background = bg(panel, radius = 22f, topOnly = true)
    val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    head.addView(TextView(this).apply {
        text = pl.icon; textSize = 22f; gravity = Gravity.CENTER
        background = bg(Color.argb(40, Color.red(accent), Color.green(accent), Color.blue(accent)), radius = 24f)
    }, LinearLayout.LayoutParams((46 * d).toInt(), (46 * d).toInt()))
    head.addView(LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(p, 0, p / 2, 0)
        if (dropped) addView(label("DROPPED PIN", 12f, bold = true).apply { setTextColor(readable(accent)); letterSpacing = 0.08f })
        addView(label(pl.name, 20f, bold = true).apply { setTextColor(ink); typeface = theme.typeface; maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
        if (pl.address.isNotBlank()) addView(label(pl.address, 14f).apply { setTextColor(dim()); maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
    }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
    head.addView(TextView(this).apply {
        text = "✕"; textSize = 18f; setTextColor(ink); gravity = Gravity.CENTER
        setOnClickListener { closePlace() }
    }, LinearLayout.LayoutParams((44 * d).toInt(), (44 * d).toInt()))
    placeCard.addView(head)
    val near = LocationTracker.position
    val info = Reviews.cached(pl)
    val facts = listOfNotNull(
        Reviews.stars(info?.rating ?: pl.rating, info?.count ?: pl.ratings),
        near?.let { NavEngine.formatDistance(Geo.distance(it, pl.pos)) },
        placeEta,
    ).joinToString("  ·  ")
    // facts on the left, Reviews › on the right (not for a dropped pin)
    if (facts.isNotBlank() || (!dropped && pl.icon != "🚗" && pl.icon != "👥")) placeCard.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, p / 2, 0, 0)
        addView(label(facts, 15f, bold = true).apply { setTextColor(ink) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        if (!dropped && pl.icon != "🚗" && pl.icon != "👥") addView(label("Reviews ›", 14f, bold = true).apply {
            setTextColor(readable(accent)); setPadding(p, p, 0, p); setOnClickListener { showReviews(pl) }
        })
    })
    val pharmacy = pl.icon == "💊" && near != null && Duties.covers(pl.pos)
    val duty = if (pharmacy) Duties.at(pl.pos) else null
    if (pharmacy) placeCard.addView(dutyBox(pl, duty), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = p / 2 })
    else if (pl.extra.isNotBlank()) placeCard.addView(label(pl.extra, 14f).apply {
        setTextColor(ink); setPadding(p, p / 2, p, p / 2); background = bg(dim(26), radius = 10f)
    }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = p / 3 })

    if (pl.icon == "👥") {
        // a friend in the convoy: drive to where they are now
        val navigating = NavEngine.active && !NavEngine.arrived
        val friend = Convoy.members.values.firstOrNull { it.name == pl.name }
        // where they're going: the convoy's destination, or a route of their own
        val theirs = friend?.ownDest ?: Convoy.destination?.takeIf { friend?.state == "driving" }
        if (theirs != null && (!NavEngine.active || Geo.distance(theirs.pos, NavEngine.destination ?: LatLng(0.0, 0.0)) > 250))
            placeCard.addView(pill("➤  Join their route · ${theirs.name}") {
                closePlace(); NavEngine.start(this@showPlace, theirs); MapState.recenter()
            }.apply {
                setTextColor(onAccent(accent)); background = bg(accent, radius = 26f); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = p })
        placeCard.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, p, 0, 0)
            addView(pill(if (theirs != null) "Drive to them now" else "➤  Drive to ${pl.name}") {
                closePlace()
                val m = Convoy.members.values.firstOrNull { it.name == pl.name }
                NavEngine.start(this@showPlace, (m?.let { Convoy.placeOf(it) } ?: pl)); MapState.recenter()
            }.apply {
                if (theirs != null) { setTextColor(ink); background = bg(Color.TRANSPARENT, dim(120), 1.5f, 26f) }
                else { setTextColor(onAccent(accent)); background = bg(accent, radius = 26f) }
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1.6f).apply { marginEnd = if (navigating) p else 0 })
            if (navigating) addView(pill("＋  Stop") { closePlace(); NavEngine.addStop(pl) }.apply {
                setTextColor(ink); background = bg(Color.TRANSPARENT, dim(120), 1.5f, 26f)
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        })
        placeCard.addView(label("Takes you to where they are right now.", 12f).apply { setTextColor(dim()); setPadding(p / 3, p / 2, 0, 0) })
        appear(placeCard)
        homeSheet.visibility = View.GONE
        return
    }
    if (pl.icon == "🚗") {
        // your parked car: walk back to it (walking directions in Google Maps), or forget it
        placeCard.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, p, 0, 0)
            addView(pill("🚶  Walk there") { walkTo(pl.pos) }.apply {
                setTextColor(if (Ui.contrast(Color.WHITE, accent) >= 3) Color.WHITE else Color.BLACK); background = bg(accent, radius = 26f)
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1.6f).apply { marginEnd = p })
            addView(pill("Forget") { Parked.clear(); closePlace() }.apply {
                setTextColor(ink); background = bg(Color.TRANSPARENT, dim(120), 1.5f, 26f)
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        })
        appear(placeCard)
        homeSheet.visibility = View.GONE
        return
    }
    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, p, 0, 0) }
    val navigating = NavEngine.active && !NavEngine.arrived
    row.addView(pill(if (navigating) "＋  Add stop" else "➤  Go") {
        closePlace()
        if (navigating) NavEngine.addStop(pl) else NavEngine.start(this, pl)
    }.apply { setTextColor(if (Ui.contrast(Color.WHITE, accent) >= 3) Color.WHITE else Color.BLACK); background = bg(accent, radius = 26f) },
        LinearLayout.LayoutParams(0, WRAP_CONTENT, 1.6f).apply { marginEnd = p })
    val callable = duty?.phone?.takeIf { it.isNotBlank() && !navigating }
    row.addView(pill(if (callable != null) "📞  " + L.t("Call") else if (navigating) "Go instead" else "Routes") {
        if (callable != null) dial(callable)
        else { closePlace(); if (navigating) NavEngine.start(this, pl) else NavEngine.plan(this, pl) }
    }.apply {
        setTextColor(ink); background = bg(Color.TRANSPARENT, dim(120), 1.5f, 26f)
        // one line, shrinking a little with big text or long words
        maxLines = 1; setPadding(paddingLeft / 2, paddingTop, paddingRight / 2, paddingBottom)
        setAutoSizeTextTypeUniformWithConfiguration(11, 16, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
    }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
    placeCard.addView(row)
    if (pharmacy) placeCard.addView(label(Duties.sourceLine(duty ?: Duties.onDutyNear(pl.pos, 15_000.0, Duties.now()).firstOrNull() ?: Duties.at(pl.pos, 25_000.0)) + " · " + L.t("call ahead if it's urgent"), 11.5f).apply {
        setTextColor(dim()); setPadding(p / 3, p / 2, 0, 0)
    })
    // one row of labelled icons for everything else
    val savedNow = Places.isSaved(pl)
    val actions = mutableListOf<Triple<Int, String, () -> Unit>>(
        Triple(R.drawable.ic_act_star, if (savedNow) "Saved" else "Save") {
            showSaveDialog(pl) { showPlace(Places.savedAt(pl.pos)?.place() ?: pl, dropped) }
        },
        Triple(R.drawable.ic_act_share, "Share") { sharePlace(pl) },
        Triple(R.drawable.ic_act_home, "Home") {
            Places.save("home", pl); etaAskedAt = 0
            Toast.makeText(this, L.t("Home saved: ${pl.name}"), Toast.LENGTH_SHORT).show(); closePlace()
        },
        Triple(R.drawable.ic_act_work, "Work") {
            Places.save("work", pl); etaAskedAt = 0
            Toast.makeText(this, L.t("Work saved: ${pl.name}"), Toast.LENGTH_SHORT).show(); closePlace()
        },
    )
    if (!NavEngine.active && pl.icon != "👥") actions += Triple<Int, String, () -> Unit>(R.drawable.ic_act_convoy, "Convoy") {
        closePlace()
        // in a convoy already: this becomes where it goes; otherwise a new convoy there
        if (Convoy.active) suggestToConvoy(pl)
        else withConvoyName { Convoy.create(pl); openSheet("convoy") }
    }
    placeCard.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; setPadding(0, p, 0, 0)
        for ((icon, title, act) in actions) addView(iconAction(icon, title, on = title == "Saved", onClick = act),
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
    })
    appear(placeCard)
    homeSheet.visibility = View.GONE
}

/** A pharmacy's duty: on duty now (hours), on duty later today, or closed with the nearest one on duty. */
internal fun MainActivity.dutyBox(pl: Place, duty: Duties.Duty?): View {
    val p = (12 * d).toInt()
    val now = Duties.now()
    val moon = readable(PoiLayer.MOON)
    val on = duty?.onDuty(now) == true
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(p, p * 2 / 3, p, p * 2 / 3)
        background = bg(if (on) Color.argb(40, 177, 151, 252) else dim(22), radius = 12f)
    }
    val text = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val later = duty?.takeIf { !on }?.let { Duties.hoursLine(it, now) }
    when {
        on -> {
            box.addView(label("🌙", 20f), LinearLayout.LayoutParams((32 * d).toInt(), WRAP_CONTENT))
            text.addView(label(Duties.hoursLine(duty!!, now).orEmpty(), 15.5f, bold = true).apply { setTextColor(moon) })
            LocationTracker.position?.let { Duties.onDutyNear(it, 3000.0, now).size }?.takeIf { it > 1 }?.let { n ->
                text.addView(label(L.t("One of $n on duty near you"), 13f).apply { setTextColor(dim()) })
            }
        }
        else -> {
            val closed = Duties.usuallyClosed(now)
            text.addView(label(later ?: if (closed) L.t("Closed now") + " · " + Duties.opensLine(now) else L.t("Not on duty now"), 15f, bold = true)
                .apply { setTextColor(if (later != null) moon else ink) })
            val nearest = Duties.onDutyNear(pl.pos, 15_000.0, now).firstOrNull()
            if (nearest != null) text.addView(label(L.t("On duty nearby: ") + nearest.name + ", " +
                NavEngine.formatDistance(Geo.distance(LocationTracker.position ?: pl.pos, nearest.pos)) + " ›", 13.5f, bold = true).apply {
                setTextColor(moon); maxLines = 2; ellipsize = TextUtils.TruncateAt.END; isClickable = true
                setOnClickListener { showPlace(Duties.place(nearest, now), dropped = false) }
            })
        }
    }
    // tap: every pharmacy on duty now
    text.addView(label(L.t("All on duty now") + " ›", 13.5f, bold = true).apply { setTextColor(moon); setPadding(0, (4 * d).toInt(), 0, 0) })
    box.isClickable = true
    box.setOnClickListener { closePlace(); showPharmacies() }
    box.addView(text, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
    return box
}

/** Walking directions to [p]: Google Maps (walking) if it's there, otherwise any map app. */
internal fun MainActivity.walkTo(p: LatLng) {
    val ll = "${p.lat},${p.lon}"
    val gm = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("google.navigation:q=$ll&mode=w")).setPackage("com.google.android.apps.maps")
    try { startActivity(gm); return } catch (_: Exception) {}
    try { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$ll&travelmode=walking"))) }
    catch (_: Exception) { Toast.makeText(this, L.t("No app to open Google Maps"), Toast.LENGTH_SHORT).show() }
}

/** A round icon with a small label under it (the place card's action row). */
internal fun MainActivity.iconAction(icon: Int, title: String, on: Boolean = false, onClick: () -> Unit) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
    contentDescription = title
    setOnClickListener { onClick() }
    val tint = if (on) readable(accent) else ink
    addView(android.widget.ImageView(context).apply {
        setImageResource(icon); imageTintList = android.content.res.ColorStateList.valueOf(tint)
        val q = (11 * d).toInt(); setPadding(q, q, q, q)
        background = bg(if (on) Color.argb(50, Color.red(accent), Color.green(accent), Color.blue(accent)) else dim(30), radius = 23f)
    }, LinearLayout.LayoutParams((46 * d).toInt(), (46 * d).toInt()))
    addView(label(title, 12f, bold = true).apply {
        setTextColor(tint); setPadding(0, (4 * d).toInt(), 0, 0); maxLines = 1; gravity = Gravity.CENTER
        // long words (Greek "Κοινοποίηση") shrink to fit under their icon
        setAutoSizeTextTypeUniformWithConfiguration(8, 12, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
    }, LinearLayout.LayoutParams(MATCH_PARENT, (18 * d).toInt()))
}

/** Shows a dialog with its texts (title, message, buttons, list) in the app's language. */
internal fun MainActivity.AlertDialog.Builder.showT(): AlertDialog = show().also { dlg ->
    dlg.window?.decorView?.let { dv -> L.translateTree(dv); Icons.applyTree(dv); dv.viewTreeObserver.addOnPreDrawListener { L.translateTree(dv); Icons.applyTree(dv); true } }
}

/** This build's name as people see it: "109", "109.1", "110". */
internal fun MainActivity.shownBuild(): String = try { packageManager.getPackageInfo(packageName, 0).versionName.orEmpty().removePrefix("build-") } catch (_: Exception) { "" }
