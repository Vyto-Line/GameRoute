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

/** gamemap://convoy/<code> (from the convoy web page) joins the group. */
internal fun MainActivity.handleLink(i: Intent?) {
    if (i == null) return
    // from a friends notification: join the convoy they invited me to, or open Friends
    i.getStringExtra("friend_join")?.let { cv ->
        i.removeExtra("friend_join"); Friends.cancelNotification(cv); main.post { joinConvoy(cv) }; return
    }
    if (i.getStringExtra("friends") != null) { i.removeExtra("friends"); main.post { openSheet("friends") }; return }
    // the home screen widget: Home or Work (routes to choose from), or just the app
    i.getStringExtra("widget_go")?.let { k ->
        i.removeExtra("widget_go")
        Places.saved(k)?.let { pl -> main.postDelayed({ NavEngine.plan(this, pl) }, 300) } ?: main.post { Toast.makeText(this, L.t(if (k == "home") "Set Home first" else "Set Work first"), Toast.LENGTH_SHORT).show() }
        return
    }
    val u = i.data
    if (u != null && u.scheme == "gamemap" && u.host == "friend") {
        val code = u.lastPathSegment ?: u.fragment ?: return
        i.data = null
        main.post { addFriendFromLink(code) }
        return
    }
    if (u != null && u.scheme == "gamemap" && u.host == "convoy") {
        val code = u.lastPathSegment ?: u.fragment ?: return
        main.post { joinConvoy(code) }
        return
    }
    // a location from another app: a geo: or maps link, or a shared message with one inside
    val text = when (i.action) {
        Intent.ACTION_VIEW -> u?.toString()
        Intent.ACTION_SEND -> listOfNotNull(i.getStringExtra(Intent.EXTRA_TEXT), i.getStringExtra(Intent.EXTRA_SUBJECT)).joinToString("\n")
        else -> null
    } ?: return
    if (text.isBlank()) return
    i.action = null; i.data = null // handled once, not again on rotation
    openShared(text)
}

/** Shows the route to a location shared from another app (WhatsApp, Viber, Google Maps…). */
internal fun MainActivity.openShared(text: String) {
    Toast.makeText(this, L.t("Opening the shared location…"), Toast.LENGTH_SHORT).show()
    io.execute {
        val f = try { SharedLocation.find(text) } catch (e: Exception) { null }
        val place: Place? = when {
            f?.pos != null -> {
                val a = try { Places.addressAt(f.pos) } catch (e: Exception) { null }
                when {
                    f.label != null -> Place(f.label, a?.let { listOf(it.name, it.address).filter { x -> x.isNotBlank() }.joinToString(", ") } ?: "", f.pos, "📍")
                    a != null -> a.copy(pos = f.pos)
                    else -> Place(L.t("Shared location"), "", f.pos, "📍")
                }
            }
            f?.query != null -> try { Places.find(f.query, LocationTracker.position, full = true)?.firstOrNull() } catch (e: Exception) { null }
            else -> null
        }
        runOnUiThread {
            if (isFinishing) return@runOnUiThread
            if (place == null) { Toast.makeText(this, L.t("Couldn't find a place in what was shared"), Toast.LENGTH_LONG).show(); return@runOnUiThread }
            closeSearch(); if (placeShown != null) closePlace()
            if (NavEngine.active && !NavEngine.arrived) showPlace(place, dropped = false) // driving: Add stop / Go instead
            else NavEngine.plan(this, place)
        }
    }
}

/** Tapped Google Maps links open GameRoute (Android 12+: the person has to allow it in Android's settings). */
internal fun MainActivity.mapLinksOn(): Boolean {
    if (Build.VERSION.SDK_INT < 31) return true // older Android asks which app every time
    return try {
        val st = getSystemService(android.content.pm.verify.domain.DomainVerificationManager::class.java)
            ?.getDomainVerificationUserState(packageName) ?: return false
        st.isLinkHandlingAllowed && listOf("maps.google.com", "maps.app.goo.gl").any {
            st.hostToStateMap[it] == android.content.pm.verify.domain.DomainVerificationUserState.DOMAIN_STATE_SELECTED
        }
    } catch (e: Exception) { false }
}

/** How to make tapped map links open here, with the two Android pages one tap away. */
internal fun MainActivity.explainMapLinks() {
    if (Build.VERSION.SDK_INT < 31) {
        GameBuilder(this@explainMapLinks).setTitle("Open map links in GameRoute")
            .setMessage("When you tap a Google Maps, Apple Maps, Waze or geo: link, Android asks which app to use: pick GameRoute (and \"Always\" if you like).\n\nYou can also share any link or coordinates to GameRoute, or paste them in the search box.")
            .setPositiveButton("OK", null).showT()
        return
    }
    val mapsApp = "com.google.android.apps.maps"
    val hasMaps = try { packageManager.getPackageInfo(mapsApp, 0); true } catch (e: Exception) { false }
    val steps = (if (hasMaps) "1. Google Maps › Open by default: turn off \"Open supported links\".\n2. " else "") +
        "GameRoute › Open by default: turn on \"Open supported links\", tap \"Add link\" and tick the maps addresses.\n\n" +
        "Android lets only one app open these links${if (hasMaps) ", which is why Google Maps goes first" else ""}. Without this, you can still share a link to GameRoute or paste it in the search box."
    val b = GameBuilder(this@explainMapLinks).setTitle("Open map links in GameRoute").setMessage(L.t(steps))
        .setPositiveButton("GameRoute settings") { _, _ ->
            try { startActivity(Intent(android.provider.Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, android.net.Uri.parse("package:$packageName"))) }
            catch (e: Exception) { try { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName"))) } catch (_: Exception) {} }
        }
        .setNegativeButton("Close", null)
    if (hasMaps) b.setNeutralButton("Google Maps settings") { _, _ ->
        try { startActivity(Intent(android.provider.Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, android.net.Uri.parse("package:$mapsApp"))) }
        catch (e: Exception) { try { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$mapsApp"))) } catch (_: Exception) {} }
    }
    b.showT()
}

/** Change the name the convoy sees (Settings › Convoy, or the convoy screen). */
internal fun MainActivity.editConvoyName(then: (() -> Unit)? = null) {
    val input = EditText(this).apply {
        hint = "Your name"; setSingleLine(); setText(Convoy.myName); setSelection(text.length)
        filters = arrayOf(android.text.InputFilter.LengthFilter(16))
    }
    GameBuilder(this@editConvoyName).setTitle("Your name in convoys").setMessage("Shown next to your arrow on the others' maps.")
        .setView(input)
        .setPositiveButton("Save") { _, _ -> Convoy.myName = input.text.toString().ifBlank { "Driver" }; convoyKey = ""; refreshChrome(); then?.invoke() }
        .setNegativeButton("Cancel", null).showT()
}

internal fun MainActivity.withConvoyName(then: () -> Unit) {
    if (Convoy.myName.isNotBlank()) { then(); return }
    val input = EditText(this).apply { hint = "Your name"; setSingleLine() }
    GameBuilder(this@withConvoyName).setTitle("Your name in convoys").setMessage("Shown next to your arrow on the others' maps.")
        .setView(input)
        .setPositiveButton("OK") { _, _ -> Convoy.myName = input.text.toString().ifBlank { "Driver" }; then() }
        .setNegativeButton("Cancel", null).showT()
}

internal fun MainActivity.startConvoyFromPreview() {
    val dest = NavEngine.planPlace ?: return
    if (Convoy.active) { suggestToConvoy(dest); return }
    withConvoyName {
        Convoy.create(dest)
        openSheet("convoy") // (Invite is there; no share sheet popping up by itself)
    }
}

/**
 * Already in a convoy: the place goes to the others as a suggestion (each gets a notification
 * to confirm or cancel); never a second convoy.
 */
internal fun MainActivity.suggestToConvoy(pl: Place) {
    Convoy.setDestination(pl)
    val msg = if (Convoy.members.isEmpty()) L.t("Convoy's destination: ${pl.name}")
        else L.t("Suggested to the convoy: ${pl.name}") + "\n" + L.t("The others are asked to confirm.")
    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    openSheet("convoy")
}

internal fun MainActivity.inviteToConvoy() {
    val link = Convoy.link() ?: return
    val dest = Convoy.destination?.name ?: "our destination"
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, "Join my convoy to $dest in GameRoute: code ${Convoy.pretty()}\n$link")
    }
    startActivity(Intent.createChooser(send, "Invite to the convoy"))
}

internal fun MainActivity.joinConvoy(link: String) {
    withConvoyName {
        if (Convoy.join(link)) {
            // joined from an invite: who and how they drive (step 2 offers the same, picked)
            val inv = if (Friends.ready) Friends.core.invites.firstOrNull { it.convoy == Convoy.code } else null
            prefs().edit().putString("convoyInviteMode", if (inv != null) "${Convoy.code}|${inv.from}|${inv.mode}" else "").apply()
            openSheet("convoy")
        }
        else Toast.makeText(this, L.t("That isn't a convoy code or link"), Toast.LENGTH_SHORT).show()
    }
}

/**
 * Save a place, with an optional label and icon ("👤 Dad"): labelled places become shortcuts
 * on the home screen and in the car, and come first in search. Saved already: edit or remove.
 */
internal fun MainActivity.showSaveDialog(pl: Place, then: () -> Unit) {
    val old = Places.savedAt(pl.pos)
    val p = (16 * d).toInt()
    val icons = listOf("★", "👤", "❤️", "👪", "🏋", "🍽", "☕", "🎓", "⚽", "🛒")
    var icon = old?.icon ?: "★"
    val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(p + p / 2, p / 2, p + p / 2, 0) }
    box.addView(label(old?.name ?: pl.name, 15f, bold = true).apply { setTextColor(ink) })
    val addr = old?.address ?: pl.address
    if (addr.isNotBlank()) box.addView(label(addr, 13f).apply { setTextColor(dim()); maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
    box.addView(label("LABEL (OPTIONAL)", 11f, bold = true).apply { setTextColor(dim()); letterSpacing = 0.08f; setPadding(0, p, 0, p / 4) })
    val input = EditText(this).apply {
        hint = "e.g. Dad, Gym, Grandma"; setSingleLine(); setText(old?.label.orEmpty()); setSelection(text.length)
        setTextColor(ink); setHintTextColor(dim(110))
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
    }
    box.addView(input)
    box.addView(label("With a label it gets its own button on the home screen and in the car, and typing it in search finds it first.", 12f).apply {
        setTextColor(dim()); setPadding(0, p / 4, 0, p / 2)
    })
    val iconRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    fun paintIcons() { for (i in 0 until iconRow.childCount) (iconRow.getChildAt(i) as TextView).let { v ->
        val on = v.contentDescription == icon
        v.background = if (on) bg(Color.argb(60, Color.red(accent), Color.green(accent), Color.blue(accent)), accent, 2f, 20f) else null
    } }
    for (e in icons) iconRow.addView(TextView(this).apply {
        text = e; textSize = 20f; gravity = Gravity.CENTER; contentDescription = e; setTextColor(ink)
        setOnClickListener { icon = e; paintIcons() }
    }, LinearLayout.LayoutParams(0, (42 * d).toInt(), 1f))
    paintIcons()
    box.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(iconRow, LinearLayout.LayoutParams((icons.size * 42 * d).toInt(), WRAP_CONTENT)) })
    val dlg = GameBuilder(this@showSaveDialog)
        .setTitle(if (old != null) "Saved place" else "Save place")
        .setView(box)
        .setPositiveButton("Save") { _, _ ->
            val lab = input.text.toString().trim()
            Places.saveFavourite(pl, lab.ifBlank { null }, icon.takeIf { it != "★" || lab.isNotBlank() })
            Toast.makeText(this, L.t(if (lab.isNotBlank()) "Saved as $lab" else "Saved: ${pl.name}"), Toast.LENGTH_SHORT).show()
            lastHomeKey = ""; refreshChrome(); then()
        }
        .setNegativeButton("Cancel", null)
    if (old != null) dlg.setNeutralButton("Remove") { _, _ ->
        Places.unsave(pl)
        Toast.makeText(this, L.t("Removed from saved"), Toast.LENGTH_SHORT).show()
        lastHomeKey = ""; refreshChrome(); then()
    }
    dlg.showT()
}

/** Change ▾ on the convoy's destination: another place, or None (just share where we all are). */
internal fun MainActivity.showDestinationMenu(anchor: View) {
    val p = (14 * d).toInt()
    lateinit var pop: android.widget.PopupWindow
    fun item(icon: String, title: String, sub: String?, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(p, p * 3 / 4, p * 3 / 2, p * 3 / 4); minimumHeight = (52 * d).toInt(); isClickable = true
        background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(dim(60)), null, null)
        addView(label(icon, 18f).apply { setPadding(0, 0, p, 0) })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(title, 15f, bold = true).apply { setTextColor(ink) })
            if (sub != null) addView(label(sub, 12.5f).apply { setTextColor(dim()) })
        })
        setOnClickListener { pop.dismiss(); onClick() }
    }
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = bg(panel, accent, 1.5f, 14f); elevation = 16 * d
        setPadding(0, p / 3, 0, p / 3)
        addView(item("📍", "Choose another place", null) { closeSheet(); startPicking("convoy") })
        addView(View(context).apply { setBackgroundColor(dim(50)) }, LinearLayout.LayoutParams(MATCH_PARENT, maxOf(1, d.toInt())).apply { marginStart = p; marginEnd = p })
        addView(item("🚫", "None", "Just share where we all are") { Convoy.clearDestination(); convoyKey = ""; refreshChrome() })
    }
    L.translateTree(box); Icons.applyTree(box)
    pop = android.widget.PopupWindow(box, (280 * d).toInt(), WRAP_CONTENT, true).apply { elevation = 16 * d; isOutsideTouchable = true }
    pop.showAsDropDown(anchor, -(200 * d).toInt(), 0, Gravity.END)
}

/** Someone in the convoy chose a (new) destination: go there too? */
internal fun MainActivity.askProposal() {
    val p = Convoy.proposal ?: return
    if (p.at == proposalAsked) return
    proposalAsked = p.at
    Convoy.cancelNotification()
    val driving = NavEngine.active && !NavEngine.arrived
    val place = p.place
    if (place == null) {
        // they removed the destination
        val mine = Convoy.destination?.takeIf { Convoy.onConvoyRoute }
        GameBuilder(this@askProposal)
            .setTitle("${p.from} removed the destination")
            .setMessage(L.t("The convoy now just shares where everyone is.") +
                (if (mine != null) "\n\n" + L.t("You're driving to ${mine.name}: your route stays unless you end it.") else ""))
            .setPositiveButton("OK, remove") { _, _ -> Convoy.accept(); refreshChrome() }
            .setNegativeButton("Keep mine") { _, _ -> Convoy.decline() }
            .setCancelable(false)
            .showT()
        return
    }
    GameBuilder(this@askProposal)
        .setTitle("${p.from} suggests a place")
        .setMessage(place.name + (if (place.address.isNotBlank()) "\n" + place.address else "") +
            (if (driving) "\n\n" + L.t("Your route will change to it.") else ""))
        .setPositiveButton(if (driving) "Confirm · go there" else "Confirm") { _, _ -> Convoy.accept(); refreshChrome() }
        .setNegativeButton("Cancel") { _, _ -> Convoy.decline() }
        .setCancelable(false)
        .showT()
}

/** Takes the map to a friend in the convoy (me: back to my car) and shows their card. */
internal fun MainActivity.showFriend(id: String) {
    closeSheet()
    if (id == Convoy.myId) { MapState.phone.recenter(); return }
    val m = Convoy.members[id] ?: return
    val at = m.shown ?: m.pos ?: run { Toast.makeText(this, L.t("No position from them yet"), Toast.LENGTH_SHORT).show(); return }
    MapState.phone.pan(at, 0f)
    if (MapState.phone.targetZoom < 15.0) MapState.phone.zoomBy(15.5 - MapState.phone.targetZoom)
    showPlace(Convoy.placeOf(m), dropped = false)
}

internal fun MainActivity.confirmLeaveConvoy() {
    GameBuilder(this@confirmLeaveConvoy).setTitle("Leave the convoy?").setMessage("The others will stop seeing you.")
        .setPositiveButton("Leave") { _, _ -> Convoy.leave(); if (sheetKind == "convoy" || sheetKind == "quick") closeSheet() }
        .setNegativeButton("Stay", null).showT()
}

/** A coloured round letter for someone in the convoy. */
internal fun MainActivity.dot(name: String, color: Int, size: Int = 36, stale: Boolean = false) = label(name.trim().take(1).uppercase().ifBlank { "?" }, size * 0.42f, bold = true).apply {
    gravity = Gravity.CENTER; setTextColor(Color.rgb(17, 17, 17))
    background = bg(if (stale) Color.GRAY else color, radius = size / 2f)
    layoutParams = LinearLayout.LayoutParams((size * d).toInt(), (size * d).toInt()).apply { marginEnd = (12 * d).toInt() }
}

internal fun MainActivity.badge(text: String, color: Int, filled: Boolean = false) = label(text, 12.5f, bold = true).apply {
    val q = (4 * d).toInt(); setPadding(q * 2, q, q * 2, q)
    if (filled) { setTextColor(onAccent(color)); background = bg(color, radius = 8f) }
    else { setTextColor(readable(color)); background = bg(Color.TRANSPARENT, color, 1.5f, 8f) }
}

internal fun MainActivity.sectionTitle(t: String) = label(t, 12f, bold = true).apply { setTextColor(dim()); letterSpacing = 0.08f; setPadding((3 * d).toInt(), (14 * d).toInt(), 0, (6 * d).toInt()) }

internal fun MainActivity.renderConvoy() {
    val rows = Convoy.rows(accent)
    val fk = if (!Friends.ready) "" else Convoy.members.values.joinToString { m -> m.fc + Friends.core.isFriend(m.fid) + Friends.core.requested(m.fc) }
    val key = "${Convoy.code}|${Convoy.myName}|${Convoy.destination?.name}|${rows.joinToString { "${it.name}${it.kmh}${it.state}${it.eta / 60000}${(it.progress * 50).toInt()}${it.ready}${it.stale}${it.along}" }}|" +
        "${Convoy.sharers().size}|${Convoy.meSharing}|${Convoy.meAlong}|${Convoy.meReady}|${Convoy.counting}|${NavEngine.active}|${theme.name}|${MapState.dark}|$fk|${friendsWaiting()}|" +
        "${onlineFriends().joinToString { it.fid }}|$convoyJoinOpen|${Convoy.host}|${convoyModeChosen()}|$pendingMode|${NavEngine.arrived}|${Convoy.myPlace()}"
    if (key == convoyKey && sheetHost.childCount > 0) return
    convoyKey = key
    sheetHost.removeAllViews()
    sheetHost.background = bg(panel, radius = 22f, topOnly = true)
    val p = (12 * d).toInt()
    togetherHeader("convoy")
    if (!Convoy.active) { renderConvoyStart(p); return }

    val dest = Convoy.destination
    val underway = Convoy.underway
    val choosing = !NavEngine.active && dest != null && Convoy.atStart().isNotEmpty() && !underway
    val beforeStart = !NavEngine.active && dest != null && !underway
    // one question at a time: 1 where → 2 how → 3 the lobby; then driving; then the standings
    if (dest == null && !Convoy.meSharing && !NavEngine.active && !underway) { renderConvoyWhere(p); return }
    if (beforeStart && !convoyModeChosen()) { renderConvoyHow(p); return }
    if (raceStandingsShown()) { renderRaceStandings(p, rows); return }
    if (NavEngine.active && !NavEngine.arrived && Convoy.onConvoyRoute) { renderConvoyDriving(p, rows); return }

    val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    sheetHost.addView(SheetScroll(0.56).apply { addView(body) }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    if (beforeStart) body.addView(stepBar(3))

    // quick messages: one tap, everyone gets it (and hears it while driving)
    if (Convoy.members.isNotEmpty()) body.addView(android.widget.HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, p * 2 / 3)
            val icons = mapOf("fuel" to "⛽", "stop" to "⏸", "ahead" to "▶", "wait" to "✋")
            for ((k, _, _) in Convoy.quick) addView(pill((icons[k] ?: "") + " " + Convoy.messageText(k)) {
                Convoy.send(k)
                Toast.makeText(this@renderConvoy, L.t("Sent to the convoy"), Toast.LENGTH_SHORT).show()
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = p / 2 })
        })
    }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

    // where we're going, with Change (or Choose)
    body.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(p, p * 3 / 4, p / 2, p * 3 / 4); background = bg(dim(22), dim(70), 1f, 14f)
        addView(label("🏁", 20f).apply { gravity = Gravity.CENTER; setTextColor(readable(accent)) }, LinearLayout.LayoutParams((28 * d).toInt(), (28 * d).toInt()).apply { marginEnd = p })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(dest?.name ?: L.t("No destination yet"), 17f, bold = true).apply {
                setTextColor(if (dest == null) dim() else ink); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            })
            val here = LocationTracker.position
            val sub = when {
                dest == null -> L.t("Choose it now; everyone gets it")
                here != null -> L.t("${NavEngine.formatDistance(Geo.distance(here, dest.pos))} from you") + " · " + L.t("code ${Convoy.pretty()}")
                else -> L.t("code ${Convoy.pretty()}")
            }
            addView(label(sub, 13f).apply { setTextColor(dim()); maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        if (dest == null) addView(pill("📍 Choose") { closeSheet(); startPicking("convoy") }.apply {
            textSize = 14f; setTextColor(onAccent(accent)); background = bg(accent, radius = 14f)
        })
        else addView(pill("Change") { showDestinationMenu(this) }.apply { textSize = 14f; setTextColor(readable(accent)) })
    }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = p / 2 })

    // how far along everyone is (once someone is on the way)
    if (!beforeStart && dest != null) body.addView(object : View(this) {
        override fun onDraw(c: Canvas) {
            val pnt = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            val y = height / 2f; val x0 = 10 * d; val x1 = width - 34 * d
            pnt.color = dim(70); c.drawRoundRect(x0, y - 1.5f * d, x1, y + 1.5f * d, 2 * d, 2 * d, pnt)
            pnt.textSize = 14 * d; c.drawText("🏁", x1 + 6 * d, y + 5 * d, pnt)
            for (r in rows.sortedBy { if (it.me) 1 else 0 }) {
                pnt.color = if (r.stale || r.along) Color.GRAY else r.color
                c.drawCircle(x0 + ((x1 - x0) * r.progress).toFloat(), y, 8 * d, pnt)
            }
        }
    }, LinearLayout.LayoutParams(MATCH_PARENT, (34 * d).toInt()))

    // who's in it
    val sharers = Convoy.sharers()
    val count = rows.size + sharers.size + (if (Convoy.meSharing) 1 else 0)
    body.addView(sectionTitle(L.t("$count IN THE CONVOY")))
    val green = Color.rgb(47, 180, 100)
    for (r in rows) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, p / 3, 0, p / 3) }
        row.addView(dot(if (r.me) Convoy.myName.ifBlank { "You" } else r.name, r.color, 36, r.stale))
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(if (r.me) L.t("You") + (if (Convoy.host) " · " + L.t("host") else "") else r.name, 16f, bold = true).apply { setTextColor(ink); maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            val lv = if (r.me) (if (Xp.showToFriends) Xp.badge() else "") else Convoy.members[r.id]?.lv.orEmpty()
            if (lv.isNotBlank()) addView(label(lv, 12.5f, bold = true).apply { setTextColor(readable(accent)); maxLines = 1 })
            // at the start the badge says it all; on the way: how far ahead or behind
            val atStartRow = r.state == "ready" && dest != null && !NavEngine.active
            val sub = if (atStartRow) (if (r.stale) L.t("no signal") else "") else Convoy.relative(r)
            if (sub.isNotBlank()) addView(label(sub, 13f).apply { setTextColor(dim()); maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        if (r.state == "ready" && dest != null && !NavEngine.active) {
            // at the start: who races, who's ready, who drives along
            val ready = if (r.me) Convoy.meReady else r.ready
            row.addView(when {
                r.along -> badge(L.t("Along"), dim(160))
                ready -> badge(L.t("Ready ✓"), green, filled = true)
                else -> badge(L.t("Racing"), accent)
            })
        } else {
            row.addView(label(if (r.state == "arrived") "✓" else "${r.kmh} km/h", 14f, bold = true).apply {
                setTextColor(if (r.kmh == 0 && r.state == "driving") readable(Color.rgb(230, 60, 60)) else ink); setPadding(p, 0, p, 0)
            })
            row.addView(label(if (r.eta > 0) NavEngine.clock(r.eta) else "–", 15f, bold = true).apply { setTextColor(readable(green)) })
        }
        if (!r.me) friendMark(r.id)?.let { row.addView(it) }
        row.isClickable = true
        row.setOnClickListener { showFriend(r.id) }
        body.addView(row)
    }
    // only sharing their location: at the bottom of the list
    val here = LocationTracker.position
    for (m in (if (Convoy.meSharing) listOf<Convoy.Member?>(null) else emptyList()) + sharers) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, p / 3, 0, p / 3) }
        val col = if (m == null) rows.firstOrNull { it.me }?.color ?: accent else m.color
        row.addView(dot(m?.name ?: Convoy.myName.ifBlank { "You" }, col, 36, m?.stale == true))
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(m?.name ?: L.t("You"), 16f, bold = true).apply { setTextColor(ink) })
            val sub = when {
                m == null -> L.t("sharing your location")
                m.stale -> L.t("no signal")
                here != null && m.pos != null -> L.t("${NavEngine.formatDistance(Geo.distance(here, m.pos!!))} from you")
                else -> L.t("not driving")
            }
            addView(label(sub, 13f).apply { setTextColor(dim()); maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(badge("📍 " + L.t("Sharing"), Color.rgb(77, 171, 247)))
        if (m != null) { friendMark(m.id)?.let { row.addView(it) }; row.isClickable = true; row.setOnClickListener { showFriend(m.id) } }
        body.addView(row)
    }
    if (Convoy.members.isEmpty()) body.addView(label("Nobody else yet: invite friends or show the QR code.", 14f).apply { setTextColor(dim()); setPadding(0, p / 3, 0, 0) })
    // one way to invite: friends, QR, link
    body.addView(pill("👥  Invite · friends, QR or link") { openSheet("convoyinvite") }.apply {
        textSize = 15f; setTextColor(readable(accent))
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 14 * d; setStroke((1.5f * d).toInt(), accent, 7 * d, 5 * d)
        }
    }, LinearLayout.LayoutParams(MATCH_PARENT, (50 * d).toInt()).apply { topMargin = p * 2 / 3 })

    // me: how I'm driving, with Change (back to the "how" step; free until the 3-2-1)
    if (beforeStart) {
        val lobby = Convoy.lobby()
        body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(p, p * 2 / 3, p / 2, p * 2 / 3); background = bg(dim(22), dim(70), 1f, 14f)
            addView(label(L.t("You: ") + L.t(when { Convoy.meSharing -> "📍 Just sharing"; Convoy.meAlong -> "🚗 Driving together"; else -> "🏁 Racing" }), 15.5f, bold = true).apply { setTextColor(ink) },
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            if (!Convoy.counting) addView(pill("Change") { setConvoyModeChosen(false); pendingMode = null; convoyKey = ""; renderConvoy() }.apply { textSize = 14f; setTextColor(readable(accent)) })
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = p })
        // what's happening, and what's next
        val waiting = lobby.filter { !it.ready }.map { it.name }
        body.addView(label(when {
            Convoy.meSharing -> "The others see you on their map. You're not in the race and no route starts."
            Convoy.meAlong -> "Same route, no race: go whenever you like."
            lobby.isEmpty() -> "Nobody else is racing yet: invite people, or start on your own."
            Convoy.counting -> "3-2-1…"
            waiting.isNotEmpty() -> L.t("Waiting for ") + waiting.joinToString(", ") + L.t(" to be ready. Then 3-2-1 and go.")
            else -> "Everyone's ready: 3-2-1 and go."
        }, 14f).apply { setTextColor(dim()); gravity = Gravity.CENTER; setPadding(p / 4, p, p / 4, 0) })
    }

    // the one big button
    val lobby = Convoy.lobby()
    val action: Pair<String, (() -> Unit)?>? = when {
        dest == null -> "📍  Choose where to go" to { closeSheet(); startPicking("convoy") }
        !Convoy.onConvoyRoute && (NavEngine.active || underway) && !Convoy.meSharing -> "➤  Join the convoy's route" to { closeSheet(); NavEngine.start(this, dest); MapState.recenter() }
        NavEngine.active || Convoy.meSharing -> null
        Convoy.meAlong -> "START · no race" to { closeSheet(); NavEngine.start(this, dest); MapState.recenter() }
        !choosing || lobby.isEmpty() -> "START" to { closeSheet(); NavEngine.start(this, dest); MapState.recenter() }
        Convoy.counting -> "3-2-1…" to null
        Convoy.meReady -> {
            val waiting = lobby.filter { !it.ready }.map { it.name }
            (if (waiting.isEmpty()) "✓ Everyone's ready!" else "✓ " + L.t("Ready · waiting for ") + waiting.joinToString(", ")) to { Convoy.setReady(false) }
        }
        Convoy.host -> "START" to { Convoy.setReady(true) }
        else -> "READY UP" to { Convoy.setReady(true) }
    }
    if (action != null) {
        val (text, act) = action
        val done = text.startsWith("✓")
        sheetHost.addView(pill(text) { act?.invoke() }.apply {
            textSize = 17f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            if (done) { setTextColor(readable(green)); background = bg(Color.TRANSPARENT, green, 2f, 16f) }
            else { setTextColor(onAccent(accent)); background = bg(accent, radius = 16f); typeface = theme.typeface }
            isEnabled = act != null
        }, LinearLayout.LayoutParams(MATCH_PARENT, (56 * d).toInt()).apply { topMargin = p })
        if (done || (choosing && !Convoy.meAlong && lobby.isNotEmpty() && !Convoy.counting)) sheetHost.addView(label(when {
            done -> "Tap it again if you're not ready after all."
            Convoy.host -> "You're the host. The others tap READY UP; when everyone's ready a 3-2-1 starts on every phone."
            else -> "When everyone's ready, a 3-2-1 starts on every phone and the route starts by itself."
        }, 12.5f).apply { setTextColor(dim()); gravity = Gravity.CENTER; setPadding(0, p / 3, 0, 0) })
    }
    // your name, and the way out
    sheetHost.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(p / 4, p * 2 / 3, 0, 0)
        addView(label(L.t("You appear as \"${Convoy.myName.ifBlank { "Driver" }}\"") + " · " + L.t("Change"), 13f, bold = true).apply {
            setTextColor(readable(accent)); maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setOnClickListener { editConvoyName() }
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(pill("Leave") { confirmLeaveConvoy() }.apply { textSize = 14f; setTextColor(readable(Color.rgb(230, 70, 70))) })
    })
}

/** Step 2 is done for this convoy (each convoy asks once; Change asks again). */
internal fun MainActivity.convoyModeChosen() = Convoy.code != null && prefs().getString("convoyModeFor", "") == Convoy.code

internal fun MainActivity.setConvoyModeChosen(v: Boolean) { prefs().edit().putString("convoyModeFor", if (v) Convoy.code.orEmpty() else "").apply() }

internal fun MainActivity.currentMode() = when { Convoy.meSharing -> "share"; Convoy.meAlong -> "along"; else -> "race" }

/** 1 · Where — 2 · How — 3 · Who, with the step we're on lit. */
internal fun MainActivity.stepBar(step: Int) = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL; setPadding(0, (2 * d).toInt(), 0, (12 * d).toInt())
    listOf("Where", "How", "Who").forEachIndexed { i, t ->
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(View(context).apply { setBackgroundColor(if (i < step) accent else dim(60)) }, LinearLayout.LayoutParams(MATCH_PARENT, (4 * d).toInt()))
            addView(label("${i + 1} · " + L.t(t).uppercase(), 11f, bold = true).apply {
                setTextColor(if (i == step - 1) readable(accent) else dim()); letterSpacing = 0.08f; setPadding(0, (5 * d).toInt(), 0, 0)
            })
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = (6 * d).toInt() })
    }
}

/** A big choice row: icon, title, a line or two, and a round mark when it's the chosen one. */
internal fun MainActivity.choiceCard(icon: String, title: String, sub: String, selected: Boolean?, extra: String? = null, onClick: () -> Unit) = LinearLayout(this).apply {
    val p = (12 * d).toInt()
    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(p, p, p, p)
    background = if (selected == true) bg(dim(22), accent, 2f, 14f) else bg(dim(22), radius = 14f)
    isClickable = true; setOnClickListener { onClick() }
    contentDescription = L.t(title) + if (selected == true) ", " + L.t("selected") else ""
    addView(label(icon, 26f).apply { gravity = Gravity.CENTER; setTextColor(ink) }, LinearLayout.LayoutParams((40 * d).toInt(), WRAP_CONTENT).apply { marginEnd = p })
    addView(LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(label(L.t(title), 17f, bold = true).apply { setTextColor(ink) })
        addView(label(L.t(sub), 13.5f).apply { setTextColor(dim()) })
        if (extra != null) addView(label(L.t(extra), 12.5f, bold = true).apply { setTextColor(readable(accent)); setPadding(0, (4 * d).toInt(), 0, 0) })
    }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
    // round marks, whatever the style's corners: a ring, and a dot in it for the chosen one
    // ("pick one"; the theme's cut squares read as checkboxes)
    if (selected != null) addView(View(context).apply {
        fun oval(fillC: Int, strokeC: Int, w: Float) = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(fillC); if (w > 0) setStroke((w * d).toInt(), strokeC)
        }
        background = if (selected) android.graphics.drawable.LayerDrawable(arrayOf(oval(Color.TRANSPARENT, accent, 2.2f), oval(accent, 0, 0f))).apply {
            setLayerInset(1, (5.5f * d).toInt(), (5.5f * d).toInt(), (5.5f * d).toInt(), (5.5f * d).toInt())
        } else oval(Color.TRANSPARENT, dim(), 2f)
    }, LinearLayout.LayoutParams((22 * d).toInt(), (22 * d).toInt()))
    else addView(label("›", 22f).apply { setTextColor(dim()) })
}

internal fun MainActivity.bigButton(text: String, onClick: () -> Unit) = pill(text) { onClick() }.apply {
    textSize = 17f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
    setTextColor(onAccent(accent)); background = bg(accent, radius = 16f); typeface = theme.typeface
}

/** Step 1: where are you going together? */
internal fun MainActivity.renderConvoyWhere(p: Int) {
    val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    sheetHost.addView(SheetScroll(0.62).apply { addView(body) }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    body.addView(stepBar(1))
    body.addView(label(if (Convoy.host) "Where are you going together?" else "Where is the convoy going?", 19f, bold = true).apply { setTextColor(ink) })
    body.addView(label(if (Convoy.host) "Everyone in the convoy gets this place." else "Suggest a place: the others are asked to confirm.", 14f).apply { setTextColor(dim()); setPadding(0, p / 4, 0, p) })
    fun gap() = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = p * 2 / 3 }
    // where I'm going already (on a route, or a place I'm looking at): one tap
    val now = if (NavEngine.active && !NavEngine.arrived) NavEngine.destination?.let { Place(NavEngine.destinationName, "", it) } else NavEngine.planPlace
    if (now != null) body.addView(choiceCard("➤", now.name, L.t("Where you're going now"), null) {
        Convoy.setDestination(now); convoyKey = ""; renderConvoy()
    }, gap())
    body.addView(choiceCard("🔍", "Search a place", "A place, an address or a saved one", null) { closeSheet(); startPicking("convoy") }, gap())
    for ((k, icon) in listOf("home" to "🏠", "work" to "💼")) Places.saved(k)?.let { pl ->
        val here = LocationTracker.position
        body.addView(choiceCard(icon, pl.name, listOfNotNull(here?.let { NavEngine.formatDistance(Geo.distance(it, pl.pos)) }, pl.address.takeIf { it.isNotBlank() }).joinToString(" · "), null) {
            Convoy.setDestination(pl); convoyKey = ""; renderConvoy()
        }, gap())
    }
    body.addView(label("No shared place: just see each other on the map ›", 14f, bold = true).apply {
        setTextColor(readable(Color.rgb(77, 171, 247))); setPadding(p / 4, p / 2, 0, p / 2)
        setOnClickListener { Convoy.setSharing(true); setConvoyModeChosen(true); convoyKey = ""; renderConvoy() }
    })
    sheetHost.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END; setPadding(0, p / 2, 0, 0)
        addView(pill("Leave") { confirmLeaveConvoy() }.apply { textSize = 14f; setTextColor(readable(Color.rgb(230, 70, 70))) })
    })
}

/** Step 2 (and joining): how do you want to drive? Race · Drive together · Just share. */
internal fun MainActivity.renderConvoyHow(p: Int) {
    val dest = Convoy.destination
    val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    sheetHost.addView(SheetScroll(0.62).apply { addView(body) }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    // someone joining: whose convoy, and how the one who invited me drives (picked for me)
    val inv = if (Convoy.host) null else prefs().getString("convoyInviteMode", "").orEmpty().split("|").takeIf { it.size == 3 && it[0] == Convoy.code }
    val drivers = Convoy.members.size + 1
    if (Convoy.host) body.addView(stepBar(2))
    else {
        body.addView(label(L.t(if (inv != null) "${inv[1]}'s convoy" else "The convoy"), 20f, bold = true).apply { setTextColor(ink); gravity = Gravity.CENTER })
        body.addView(label("🏁 " + L.t("To ${dest?.name ?: "—"}") + " · " + L.t("$drivers drivers"), 14f).apply { setTextColor(dim()); gravity = Gravity.CENTER; setPadding(0, p / 4, 0, p) })
    }
    body.addView(label(if (Convoy.host) "How do you want to drive?" else "How do you want to come?", 19f, bold = true).apply { setTextColor(ink) })
    if (Convoy.host) body.addView(label(L.t("To ${dest?.name ?: "—"}") + " · " + L.t("each person picks their own when they join."), 14f).apply { setTextColor(dim()); setPadding(0, p / 4, 0, p) })
    val chosen = pendingMode ?: inv?.get(2)?.takeIf { it.isNotBlank() } ?: currentMode()
    fun gap() = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = p * 2 / 3 }
    val pick: (String) -> Unit = { m -> pendingMode = m; convoyKey = ""; renderConvoy() }
    body.addView(choiceCard("🏁", if (Convoy.host) "Race" else "Join the race", "3-2-1 start together. Same place, fastest wins. Places and times at the end.", chosen == "race",
        if (Convoy.host) "The default for people you invite" else null) { pick("race") }, gap())
    body.addView(choiceCard("🚗", if (Convoy.host) "Drive together" else "Drive together, no race", "Same place, same route, no race. You see each other the whole way.", chosen == "along") { pick("along") }, gap())
    body.addView(choiceCard("📍", "Just share where I am", "I'm going somewhere else: the others see me on the map, I see them.", chosen == "share") { pick("share") }, gap())
    val label = when {
        Convoy.host -> "Next: invite people ›"
        chosen == "race" -> "Join the race ›"
        chosen == "along" -> "Drive together ›"
        else -> "Share where I am ›"
    }
    sheetHost.addView(bigButton(L.t(label)) {
        when (chosen) { "race" -> { Convoy.setSharing(false); Convoy.setAlong(false) }; "along" -> Convoy.setAlong(true); else -> Convoy.setSharing(true) }
        setConvoyModeChosen(true); pendingMode = null; convoyKey = ""
        if (Convoy.host && Convoy.members.isEmpty()) openSheet("convoyinvite") else renderConvoy()
    }, LinearLayout.LayoutParams(MATCH_PARENT, (56 * d).toInt()).apply { topMargin = p })
}

/** While driving: quick messages first, everyone in order with the gap, See everyone / Invite / Leave. */
internal fun MainActivity.renderConvoyDriving(p: Int, rows: List<Convoy.Row>) {
    val dest = Convoy.destination
    val racing = !Convoy.meAlong && !Convoy.meSharing && Convoy.members.values.any { !it.along && !it.sharing }
    val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    sheetHost.addView(SheetScroll(0.6).apply { addView(body) }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    val racers = rows.filter { !it.along }
    val myPlace = racers.indexOfFirst { it.me } + 1
    body.addView(label((if (racing) "🏁 " + L.t("Race to ${dest?.name ?: ""}") else "🚗 " + L.t("Together to ${dest?.name ?: ""}")), 19f, bold = true).apply { setTextColor(ink); typeface = theme.typeface })
    body.addView(label(listOfNotNull(if (racing && myPlace > 0) L.t(ordinal(myPlace)) else null, NavEngine.formatDistance(NavEngine.remainingDistance) + " " + L.t("to go")).joinToString(" · "), 13.5f).apply {
        setTextColor(dim()); setPadding(0, p / 4, 0, p * 2 / 3)
    })
    // quick messages: four big tiles
    val icons = mapOf("fuel" to "⛽", "stop" to "☕", "ahead" to "👉", "wait" to "✋")
    body.addView(android.widget.GridLayout(this).apply {
        columnCount = 4
        for ((k, _, _) in Convoy.quick) addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(0, p / 2, 0, p / 2)
            background = bg(dim(22), radius = 12f); isClickable = true
            contentDescription = Convoy.messageText(k)
            setOnClickListener { Convoy.send(k); Toast.makeText(this@renderConvoyDriving, L.t("Sent to the convoy"), Toast.LENGTH_SHORT).show() }
            addView(label(icons[k] ?: "💬", 22f).apply { gravity = Gravity.CENTER })
            addView(label(Convoy.messageText(k), 12f, bold = true).apply { setTextColor(ink); gravity = Gravity.CENTER; maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        }, android.widget.GridLayout.LayoutParams(android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED), android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)).apply {
            width = 0; setMargins(p / 4, 0, p / 4, 0)
        })
    }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = p * 2 / 3 })
    // everyone, in order (the racers by place, then those driving together)
    var place = 0
    for (r in rows) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(p / 2, p / 3, p / 2, p / 3) }
        if (r.me) row.background = bg(dim(26), radius = 10f)
        if (!r.along) place++
        row.addView(label(if (!r.along && racing) "$place" else "", 15f, bold = true).apply { setTextColor(readable(accent)) }, LinearLayout.LayoutParams((20 * d).toInt(), WRAP_CONTENT))
        row.addView(dot(if (r.me) Convoy.myName.ifBlank { "You" } else r.name, r.color, 32, r.stale))
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(if (r.me) L.t("You") else r.name, 15.5f, bold = true).apply { setTextColor(ink) })
            val sub = (if (r.me) "" else L.t(Convoy.relative(r))) + (if (r.along && !r.me) "" else "")
            if (sub.isNotBlank()) addView(label(sub, 12.5f).apply { setTextColor(dim()); maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(label(if (r.state == "arrived") "🏁" else "${r.kmh} km/h", 14f, bold = true).apply { setTextColor(readable(Color.rgb(61, 220, 132))) })
        if (!r.me) { row.isClickable = true; row.setOnClickListener { showFriend(r.id) } }
        body.addView(row)
    }
    sheetHost.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; setPadding(0, p, 0, 0)
        addView(pill("👀 See all") {
            closeSheet()
            val pts = Convoy.members.values.mapNotNull { it.shown ?: it.pos } + listOfNotNull(LocationTracker.position)
            MapState.phone.requestFit(pts)
        }.apply { textSize = 14f; setTextColor(readable(accent)); background = bg(Color.TRANSPARENT, accent, 1.5f, 12f) }, LinearLayout.LayoutParams(0, (46 * d).toInt(), 1f))
        addView(pill("👥 Invite") { openSheet("convoyinvite") }.apply { textSize = 14f; setTextColor(readable(accent)); background = bg(Color.TRANSPARENT, accent, 1.5f, 12f) },
            LinearLayout.LayoutParams(0, (46 * d).toInt(), 1f).apply { marginStart = p / 2 })
        addView(pill("Leave") { confirmLeaveConvoy() }.apply { textSize = 14f; setTextColor(readable(Color.rgb(230, 70, 70))) },
            LinearLayout.LayoutParams(WRAP_CONTENT, (46 * d).toInt()).apply { marginStart = p / 2 })
    })
}

internal fun MainActivity.ordinal(n: Int) = when (n) { 1 -> "You're 1st"; 2 -> "You're 2nd"; 3 -> "You're 3rd"; else -> "You're ${n}th" }

/** I finished a race and the convoy is still on: the standings (live until everyone's in). */
internal fun MainActivity.raceStandingsShown() = Convoy.active && NavEngine.arrived && Convoy.myPlace() != null

internal fun MainActivity.renderRaceStandings(p: Int, rows: List<Convoy.Row>) {
    val (mine, total) = Convoy.myPlace() ?: return
    val racers = rows.filter { !it.along }
    val arrived = racers.filter { it.state == "arrived" }.sortedBy { it.arrivedAt }
    val stillDriving = racers.filter { it.state != "arrived" }
    val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    sheetHost.addView(SheetScroll(0.62).apply { addView(body) }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    val medal = when (mine) { 1 -> "🥇"; 2 -> "🥈"; 3 -> "🥉"; else -> "🏁" }
    body.addView(label(medal, 44f).apply { gravity = Gravity.CENTER })
    body.addView(label(L.t(when (mine) { 1 -> "You won!"; 2 -> "You finished 2nd"; 3 -> "You finished 3rd"; else -> "You finished ${mine}th" }), 22f, bold = true).apply { setTextColor(ink); gravity = Gravity.CENTER; typeface = theme.typeface })
    val first = arrived.firstOrNull()
    val me = arrived.firstOrNull { it.me }
    if (me != null && first != null && !first.me) body.addView(label("+" + fmtGap((me.arrivedAt - first.arrivedAt) / 1000) + " · " + L.t("Winner: ") + first.name, 14f).apply { setTextColor(dim()); gravity = Gravity.CENTER })
    body.addView(sectionTitle(if (stillDriving.isEmpty()) L.t("FINAL ORDER · $total RACERS") else L.t("LIVE · ${stillDriving.size} STILL DRIVING")))
    val start = arrived.minOfOrNull { it.arrivedAt } ?: 0L
    arrived.forEachIndexed { i, r ->
        body.addView(standingRow("${i + 1}", r, L.t("Arrived ") + NavEngine.clock(r.arrivedAt) + (if (i > 0 && start > 0) " · +" + fmtGap((r.arrivedAt - start) / 1000) else ""), "🏁", p))
    }
    for (r in stillDriving) body.addView(standingRow("", r, if (r.stale) L.t("no signal") else NavEngine.formatDistance(r.left.coerceAtLeast(0.0)) + " " + L.t("to go") +
        (if (r.eta > 0) " · ~" + NavEngine.clock(r.eta) else ""), "${r.kmh} km/h", p))
    body.addView(label(if (stillDriving.isEmpty()) "Everyone's in." else "Stays open until everyone's in, then shows the final order.", 13f).apply {
        setTextColor(dim()); gravity = Gravity.CENTER; setPadding(0, p, 0, 0)
    })
    sheetHost.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; setPadding(0, p, 0, 0)
        addView(bigButton(L.t("Done")) { closeSheet() }, LinearLayout.LayoutParams(0, (52 * d).toInt(), 1f))
        addView(pill("Leave") { confirmLeaveConvoy() }.apply { textSize = 14f; setTextColor(readable(Color.rgb(230, 70, 70))) },
            LinearLayout.LayoutParams(WRAP_CONTENT, (52 * d).toInt()).apply { marginStart = p / 2 })
    })
}

internal fun MainActivity.fmtGap(s: Long) = if (s < 60) "$s s" else "${s / 60} min ${s % 60} s"

internal fun MainActivity.standingRow(place: String, r: Convoy.Row, sub: String, right: String, p: Int) = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(p / 2, p / 3, p / 2, p / 3)
    if (r.me) background = bg(dim(26), radius = 10f)
    addView(label(place, 15f, bold = true).apply { setTextColor(readable(accent)) }, LinearLayout.LayoutParams((20 * d).toInt(), WRAP_CONTENT))
    addView(dot(if (r.me) Convoy.myName.ifBlank { "You" } else r.name, r.color, 32, r.stale))
    addView(LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(label(if (r.me) L.t("You") else r.name, 15.5f, bold = true).apply { setTextColor(ink) })
        addView(label(sub, 12.5f).apply { setTextColor(dim()); maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
    }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
    addView(label(right, 14f, bold = true).apply { setTextColor(readable(Color.rgb(61, 220, 132))) })
}

/** Not in a convoy: Start one, Join one, or invite a friend who's online. */
internal fun MainActivity.renderConvoyStart(p: Int) {
    val cbody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    sheetHost.addView(SheetScroll(0.75).apply { addView(cbody) }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    cbody.addView(label("Drive somewhere together and see each other on the map.", 14f).apply { setTextColor(dim()); setPadding(p / 4, p / 2, 0, p) })
    fun big(icon: String, title: String, sub: String, primary: Boolean, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(p + p / 3, p, p, p); isClickable = true
        background = if (primary) bg(accent, radius = 16f) else bg(dim(22), accent, 1.5f, 16f)
        setOnClickListener { onClick() }
        val fg = if (primary) onAccent(accent) else ink
        addView(label(icon, 24f).apply { gravity = Gravity.CENTER; setTextColor(fg) }, LinearLayout.LayoutParams((32 * d).toInt(), (32 * d).toInt()).apply { marginEnd = p })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(title, 17f, bold = true).apply { setTextColor(fg); typeface = theme.typeface })
            addView(label(sub, 13.5f).apply { setTextColor(if (primary) fg else dim()) }) // (faded, it was too faint on bright accents)
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(label("›", 22f).apply { setTextColor(fg) })
    }
    cbody.addView(big("🏁", L.t("Start a convoy"), L.t("Pick where you're going, then invite people. Race or drive together: you choose next."), true) {
        // step 1 (Where) opens in the sheet: search is one of the choices there, not a jump
        withConvoyName { Convoy.create(null); setConvoyModeChosen(false); pendingMode = null; convoyKey = ""; openSheet("convoy"); renderConvoy() }
    }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    cbody.addView(big("▦", L.t("Join one"), L.t("Type a code, or scan the QR with your camera"), false) {
        convoyJoinOpen = !convoyJoinOpen; convoyKey = ""; renderConvoy()
    }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = p * 2 / 3 })
    if (convoyJoinOpen) {
        lateinit var joinBtn: TextView
        val input = EditText(this).apply {
            hint = "K7P 2QX"; setSingleLine(); setTextColor(ink); setHintTextColor(dim(110))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            textSize = 28f; letterSpacing = 0.18f; typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER
            background = bg(dim(22), dim(90), 1.5f, 14f)
            val q = (12 * d).toInt(); setPadding(q, q, q, q)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { v, _, _ -> joinConvoy(v.text.toString()); true }
            addTextChangedListener(object : android.text.TextWatcher {
                private var busy = false
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(e: android.text.Editable) {
                    if (busy) return
                    val raw = e.toString()
                    // a pasted link stays as it is; a typed code is shown as "K7P 2QX"
                    if (!raw.contains("/") && !raw.contains(".")) {
                        val code = raw.uppercase().filter { it.isLetterOrDigit() }.take(6)
                        val pretty = if (code.length > 3) code.take(3) + " " + code.drop(3) else code
                        if (pretty != raw) { busy = true; e.replace(0, e.length, pretty); busy = false }
                    }
                    val ready = raw.contains("/") || raw.filter { it.isLetterOrDigit() }.length >= 6
                    joinBtn.alpha = if (ready) 1f else 0.45f; joinBtn.isEnabled = ready
                }
            })
        }
        cbody.addView(input, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = p * 2 / 3 })
        joinBtn = pill("Join") { joinConvoy(input.text.toString()) }.apply {
            setTextColor(onAccent(accent)); background = bg(accent, radius = 14f); alpha = 0.45f; isEnabled = false
        }
        cbody.addView(joinBtn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = p / 2 })
        cbody.addView(pill("Paste an invite link") {
            val clip = getSystemService(android.content.ClipboardManager::class.java)?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
            if (clip.isNullOrBlank()) Toast.makeText(this, L.t("Nothing to paste"), Toast.LENGTH_SHORT).show() else joinConvoy(clip)
        }.apply { textSize = 14f; setTextColor(readable(accent)) }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        cbody.addView(label("Got a QR code? Point your phone's camera at it: GameRoute opens and joins.", 12.5f).apply {
            setTextColor(dim()); gravity = Gravity.CENTER; setPadding(0, p / 3, 0, 0)
        })
        input.requestFocus()
    }
    // friends online: Invite starts a convoy for you and sends it
    val online = onlineFriends()
    if (online.isNotEmpty()) {
        cbody.addView(sectionTitle(L.t("FRIENDS ONLINE")))
        for (f in online.take(4)) cbody.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, p / 3, 0, p / 3)
            addView(avatar(f.name, f.fid, true, 36), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = p })
            addView(label(f.name, 16f, bold = true).apply { setTextColor(ink) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(pill("Invite") { inviteFriend(f); openSheet("convoy") }.apply {
                textSize = 14f; setTextColor(readable(accent)); background = bg(Color.TRANSPARENT, accent, 1.5f, 12f)
            })
        })
        cbody.addView(label("Invite starts a convoy for you and sends it to them.", 12.5f).apply { setTextColor(dim()); setPadding(p / 4, p / 3, 0, 0) })
    }
    cbody.addView(label(L.t("You appear as \"${Convoy.myName.ifBlank { "Driver" }}\"") + " · " + L.t("Change"), 13f, bold = true).apply {
        setTextColor(readable(accent)); setPadding(p / 4, p, 0, 0); setOnClickListener { editConvoyName() }
    })
}

/** Invite: the QR and code (people next to you), the link (a message), and your friends. */
internal fun MainActivity.renderConvoyInvite() {
    if (!Convoy.active) { openSheet("convoy"); return }
    val c = if (Friends.ready && Friends.core.started) Friends.core else null
    val key = "${Convoy.code}|${theme.name}|${MapState.dark}|${c?.friends?.joinToString { it.fid + c.isOnline(it.fid) }}|${c?.sent?.joinToString { it.fid + it.status }}|${Convoy.members.values.joinToString { it.fid }}"
    if (key == inviteKey && sheetHost.childCount > 0) return
    inviteKey = key
    sheetHost.removeAllViews()
    sheetHost.background = bg(panel, radius = 22f, topOnly = true)
    sheetHost.addView(sheetHeader("Invite", sheetTitle(sheetStack.lastOrNull() ?: "convoy") to { if (sheetStack.isEmpty()) switchSheet("convoy") else sheetBack() }))
    val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    sheetHost.addView(SheetScroll(0.72).apply { addView(body) }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    val p = (12 * d).toInt()
    val link = Convoy.link() ?: return
    body.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(p, p, p, p); background = bg(dim(22), radius = 16f)
        val side = (128 * d).toInt()
        addView(android.widget.ImageView(context).apply {
            setImageBitmap(qr(link, side)); scaleType = android.widget.ImageView.ScaleType.CENTER
            background = bg(Color.WHITE, radius = 10f); contentDescription = L.t("QR code for the convoy ${Convoy.pretty()}, tap to make it bigger")
            setOnClickListener { showConvoyQr() }
        }, LinearLayout.LayoutParams(side, side).apply { marginEnd = p })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(label("CODE", 12f, bold = true).apply { setTextColor(dim()); letterSpacing = 0.1f })
            addView(label(Convoy.pretty() ?: "", 26f, bold = true).apply { setTextColor(ink); letterSpacing = 0.12f; typeface = Typeface.MONOSPACE; maxLines = 1 })
            addView(pill("🔗 Share link") { inviteToConvoy() }.apply {
                textSize = 14f; setTextColor(readable(accent)); background = bg(Color.TRANSPARENT, accent, 1.5f, 12f)
            }, LinearLayout.LayoutParams(MATCH_PARENT, (42 * d).toInt()).apply { topMargin = p / 2 })
            addView(pill("Copy code") {
                val cm = getSystemService(android.content.ClipboardManager::class.java)
                cm?.setPrimaryClip(android.content.ClipData.newPlainText("Convoy code", Convoy.pretty()))
                Toast.makeText(this@renderConvoyInvite, L.t("Code copied"), Toast.LENGTH_SHORT).show()
            }.apply { textSize = 13.5f; setTextColor(readable(accent)) }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
    })
    body.addView(label("Anyone next to you can scan the QR with their camera (tap it to make it bigger).", 12.5f).apply { setTextColor(dim()); setPadding(p / 4, p / 2, 0, 0) })
    body.addView(sectionTitle(L.t("FRIENDS")))
    val friends = c?.friends.orEmpty().sortedByDescending { c?.isOnline(it.fid) == true }
    if (friends.isEmpty()) {
        body.addView(label("No friends yet. Add the people you drive with, then invite them in one tap.", 14f).apply { setTextColor(dim()) })
        body.addView(pill("＋ Add a friend") { openSheet("addfriend") }.apply { textSize = 15f; setTextColor(readable(accent)) })
    }
    val inIt = Convoy.members.values.map { it.fid }.toSet()
    for (f in friends) {
        val on = c?.isOnline(f.fid) == true
        val sent = c?.sent?.lastOrNull { it.fid == f.fid && it.convoy == Convoy.code }
        body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, p / 3, 0, p / 3)
            addView(avatar(f.name, f.fid, on, 36), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = p })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(f.name, 16f, bold = true).apply { setTextColor(ink) })
                addView(label(if (on) L.t("online") else L.t("offline"), 13f).apply { setTextColor(if (on) readable(Color.rgb(47, 180, 100)) else dim()) })
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            when {
                f.fid in inIt -> addView(label("In the convoy", 13.5f, bold = true).apply { setTextColor(dim()) })
                sent != null -> addView(label(when (sent.status) { "seen" -> "Sent · seen"; "joined" -> "Joined"; "no" -> "Not now"; else -> "Sent" }, 13.5f, bold = true).apply {
                    setTextColor(readable(Color.rgb(255, 176, 40)))
                })
                else -> addView(pill("Invite") { inviteFriend(f); inviteKey = "" }.apply {
                    textSize = 14f
                    if (on) { setTextColor(onAccent(accent)); background = bg(accent, radius = 12f) }
                    else { setTextColor(readable(accent)); background = bg(Color.TRANSPARENT, dim(120), 1.5f, 12f) }
                })
            }
        })
    }
    if (friends.isNotEmpty()) body.addView(label("Offline friends get the invite the next time they open GameRoute (it expires after 2 h).", 12.5f).apply {
        setTextColor(dim()); setPadding(p / 4, p / 2, 0, 0)
    })
}
