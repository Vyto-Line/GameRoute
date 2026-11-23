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

internal fun MainActivity.syncInvitePop() {
    val i = InvitePop.current
    if (i == null) { hideInvitePop(); return }
    if (i === inviteFor && inviteCard != null) return
    showInvitePop(i)
}

/** Where the card comes out: under the turn box while driving, else under Where to?. */
internal fun MainActivity.inviteTop(): Int =
    if (NavEngine.active && !NavEngine.arrived) (map.bannerBottom + 6 * d).toInt()
    else (topArea.top + searchCard.bottom + 6 * d).toInt().coerceAtLeast((40 * d).toInt())

internal fun MainActivity.showInvitePop(i: FriendCore.Invite) {
    inviteClip.removeAllViews()
    inviteFor = i
    val p = (10 * d).toInt()
    val card = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(p, p, p, p + (3 * d).toInt())
        background = bg(opaque(panel), accent, 1.5f, 14f)
        isClickable = true
        setOnClickListener { InvitePop.close(); openTogether() }
        contentDescription = L.t("${i.from} invites you to a convoy")
        addView(avatar(i.from, i.fid, true, 40))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding((10 * d).toInt(), 0, (6 * d).toInt(), 0)
            addView(label(L.t("${i.from} invites you"), 15.5f, bold = true).apply { setTextColor(ink); maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            val sub = "🏁 " + (if (i.dest.isNotBlank()) L.t("Convoy to ${i.dest}") else L.t("A convoy")) +
                (if (i.count > 1) " · " + L.t("${i.count} invites") else "") + (if (Convoy.active) " · " + L.t("you'll leave yours") else "")
            addView(label(sub, 13f).apply { setTextColor(dim()); maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(TextView(context).apply {
            text = "✕"; textSize = 16f; gravity = Gravity.CENTER; setTextColor(dim())
            contentDescription = L.t("Not now")
            background = bg(Color.TRANSPARENT, dim(110), 1.2f, 12f)
            setOnClickListener { InvitePop.notNow() }
        }, LinearLayout.LayoutParams((42 * d).toInt(), (42 * d).toInt()).apply { marginEnd = (8 * d).toInt() })
        addView(pill(L.t("Join")) { InvitePop.take()?.let { joinConvoy(it.convoy) } }.apply {
            setTextColor(onAccent(accent)); background = bg(accent, radius = 12f); textSize = 15f
            minHeight = (42 * d).toInt(); setPadding((18 * d).toInt(), 0, (18 * d).toInt(), 0)
        }, LinearLayout.LayoutParams(WRAP_CONTENT, (42 * d).toInt()))
    }
    // the 10 s running down
    val barColor = accent
    val bar = object : View(this) {
        private val pt = android.graphics.Paint().apply { color = barColor }
        override fun onDraw(c: Canvas) {
            val left = InvitePop.left()
            c.drawRect(0f, 0f, width * left, height.toFloat(), pt)
            if (left > 0f && inviteCard != null) postInvalidateDelayed(100)
        }
    }
    val holder = FrameLayout(this).apply {
        addView(card, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(bar, FrameLayout.LayoutParams(MATCH_PARENT, (3 * d).toInt(), Gravity.BOTTOM).apply {
            leftMargin = (8 * d).toInt(); rightMargin = (8 * d).toInt(); bottomMargin = (3 * d).toInt()
        })
    }
    inviteClip.addView(holder, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
        leftMargin = (12 * d).toInt(); rightMargin = (12 * d).toInt()
    })
    (inviteClip.layoutParams as FrameLayout.LayoutParams).topMargin = inviteTop()
    inviteClip.requestLayout()
    inviteClip.visibility = View.VISIBLE
    inviteCard = holder
    // out from under the bar above it (the clip hides it until it slides down)
    holder.translationY = -200 * d
    holder.post {
        holder.translationY = -holder.height.toFloat()
        holder.animate().translationY(0f).setDuration(260).start()
    }
}

internal fun MainActivity.hideInvitePop() {
    val v = inviteCard ?: return
    inviteCard = null; inviteFor = null
    v.animate().translationY(-v.height.toFloat()).setDuration(220).withEndAction {
        if (inviteCard == null) { inviteClip.removeAllViews(); inviteClip.visibility = View.GONE }
    }.start()
}

/** For tests: the card on screen now. */
internal fun MainActivity.invitePopForTest(): View? = inviteCard

/** Invite in the convoy: friends first, or the link for anyone else. */
internal fun MainActivity.showInviteMenu(anchor: View) {
    val p = (14 * d).toInt()
    lateinit var pop: android.widget.PopupWindow
    fun item(icon: String, title: String, sub: String?, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(p, p * 3 / 4, p * 3 / 2, p * 3 / 4); minimumHeight = (54 * d).toInt(); isClickable = true
        background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(dim(60)), null, null)
        addView(label(icon, 18f).apply { setPadding(0, 0, p, 0) })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(title, 15f, bold = true).apply { setTextColor(ink) })
            if (sub != null) addView(label(sub, 12.5f).apply { setTextColor(dim()) })
        })
        setOnClickListener { pop.dismiss(); onClick() }
    }
    val c = Friends.core
    val online = c.friends.filter { c.isOnline(it.fid) }.map { it.name }
    val sub = when {
        c.friends.isEmpty() -> L.t("No friends yet: add some")
        online.isEmpty() -> L.t("${c.friends.size} friends, none online")
        online.size <= 2 -> L.t("${online.joinToString(" and ")} online")
        else -> L.t("${online.size} online")
    }
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = bg(panel, accent, 1.5f, 14f); elevation = 16 * d
        setPadding(0, p / 3, 0, p / 3)
        addView(item("👥", "Invite friends", sub) { openSheet("friends") })
        addView(View(context).apply { setBackgroundColor(dim(50)) }, LinearLayout.LayoutParams(MATCH_PARENT, maxOf(1, d.toInt())).apply { marginStart = p; marginEnd = p })
        addView(item("▦", "Show QR code", "For people right next to you") { showConvoyQr() })
        addView(View(context).apply { setBackgroundColor(dim(50)) }, LinearLayout.LayoutParams(MATCH_PARENT, maxOf(1, d.toInt())).apply { marginStart = p; marginEnd = p })
        addView(item("🔗", "Share the link", "Send it in a message") { inviteToConvoy() })
    }
    L.translateTree(box); Icons.applyTree(box)
    pop = android.widget.PopupWindow(box, (290 * d).toInt(), WRAP_CONTENT, true).apply { elevation = 16 * d; isOutsideTouchable = true }
    pop.showAsDropDown(anchor, 0, -(anchor.height + 140 * d).toInt())
}

/**
 * The convoy's QR, big: scanned with any camera it opens GameRoute and joins (no app: the convoy
 * opens in the browser). The screen goes to full brightness while it shows (dim screens scan badly).
 */
internal fun MainActivity.showConvoyQr() {
    val link = Convoy.link() ?: return
    val p = (20 * d).toInt()
    val dlg = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        setPadding(p + p / 4, p, p + p / 4, p + p / 4); setBackgroundColor(panel)
    }
    box.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(label("Join the convoy", 24f, bold = true).apply { setTextColor(ink); typeface = theme.typeface }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(pill("✕") { dlg.dismiss() }.apply { setTextColor(ink); contentDescription = L.t("Close") })
    }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    box.addView(label("Scan with the phone's camera: it opens GameRoute and joins. No app? The convoy opens in the browser.", 15f).apply {
        setTextColor(dim()); setPadding(0, p / 3, 0, p)
    }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    val side = minOf(resources.displayMetrics.widthPixels - 3 * p, (320 * d).toInt())
    box.addView(android.widget.ImageView(this).apply {
        setImageBitmap(qr(link, side + (32 * d).toInt())); contentDescription = L.t("QR code for the convoy ${Convoy.pretty()}")
        background = bg(Color.WHITE, radius = 16f); scaleType = android.widget.ImageView.ScaleType.CENTER
    }, LinearLayout.LayoutParams(side + (32 * d).toInt(), side + (32 * d).toInt()))
    box.addView(label("CODE", 12f, bold = true).apply { setTextColor(dim()); letterSpacing = 0.12f; setPadding(0, p, 0, 0) })
    box.addView(label(Convoy.pretty() ?: "", 40f, bold = true).apply { setTextColor(ink); letterSpacing = 0.16f; typeface = Typeface.MONOSPACE })
    Convoy.destination?.let { box.addView(label("🏁  ${it.name}", 15f, bold = true).apply { setTextColor(ink); setPadding(0, p / 3, 0, 0); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }) }
    box.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
    box.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(pill("Share the link") { dlg.dismiss(); inviteToConvoy() }.apply { setTextColor(ink); background = bg(Color.TRANSPARENT, dim(120), 1.5f, 16f) },
            LinearLayout.LayoutParams(0, (52 * d).toInt(), 1f).apply { marginEnd = p / 2 })
        addView(pill("Done") { dlg.dismiss() }.apply { setTextColor(onAccent(accent)); background = bg(accent, radius = 16f) },
            LinearLayout.LayoutParams(0, (52 * d).toInt(), 1f))
    }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    L.translateTree(box); Icons.applyTree(box)
    dlg.setContentView(box)
    dlg.window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = 1f } } // only while it shows
    dlg.show()
}

/** In the convoy list: add someone as a friend (or that you are, or asked already). */
internal fun MainActivity.friendMark(memberId: String): View? {
    if (!Friends.ready) return null
    val m = Convoy.members[memberId] ?: return null
    if (m.fc.isBlank() || m.fid.isBlank()) return null
    val c = Friends.core
    val sz = (40 * d).toInt()
    return when {
        c.isFriend(m.fid) -> label("✓", 17f, bold = true).apply { setTextColor(readable(Color.rgb(47, 180, 100))); gravity = Gravity.CENTER; contentDescription = L.t("Friends") }
        c.requested(m.fc) -> label("⏱", 16f).apply { setTextColor(dim()); gravity = Gravity.CENTER; contentDescription = L.t("Request sent") }
        m.fid in c.blocked -> null
        else -> label("＋", 20f, bold = true).apply {
            setTextColor(readable(accent)); gravity = Gravity.CENTER; background = bg(Color.TRANSPARENT, accent, 1.5f, 10f)
            contentDescription = L.t("Add ${m.name} as a friend")
            setOnClickListener { sendFriendRequest(m.fc, m.name) { convoyKey = ""; refreshChrome() } }
        }
    }?.apply { layoutParams = LinearLayout.LayoutParams(sz, sz).apply { marginStart = (8 * d).toInt() } }
}

internal fun MainActivity.showBlocked() {
    val list = Friends.core.blockedNames()
    if (list.isEmpty()) { Toast.makeText(this, L.t("Nobody is blocked"), Toast.LENGTH_SHORT).show(); return }
    GameBuilder(this@showBlocked).setTitle("Blocked")
        .setItems(list.map { L.t("Unblock ${it.second}") }.toTypedArray()) { _, w -> Friends.unblock(list[w].first); showSettings() }
        .setNegativeButton("Close", null).showT()
}

/** Offline maps: download the map around here, list and delete downloads. */
internal fun MainActivity.renderOffline() {
    val areas = Offline.areas()
    val key = "${areas.size}|${Offline.downloading}|${Offline.done.get() / 20}|${Offline.waitingForWifi}|${Offline.wifiOnly}${Offline.autoUpdate}${Offline.alongRoutes}|${OfflineRouter.present}${OfflineRouter.downloading}${(OfflineRouter.progress * 50).toInt()}${Offline.roadsSize}|${theme.name}|${MapState.dark}"
    if (key == offlineKey && sheetHost.childCount > 0) return
    offlineKey = key
    Offline.changed -= offlineListener; Offline.changed += offlineListener
    // (rebuilt as a download goes on: keep where the list was scrolled to)
    val keepScroll = (0 until sheetHost.childCount).map { sheetHost.getChildAt(it) }.filterIsInstance<ScrollView>().firstOrNull()?.scrollY ?: 0
    sheetHost.removeAllViews()
    sheetHost.background = bg(panel, radius = 22f, topOnly = true)
    sheetHost.addView(sheetHeader("Offline maps"))
    val p = (12 * d).toInt()
    val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    sheetHost.addView(SheetScroll(0.78).apply { addView(body); post { scrollTo(0, keepScroll) } }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    body.addView(label("The map and the places of a region, kept on your phone: they show and search without signal and use no data. " +
        "Every trip also keeps the map along its route.", 14f).apply { setTextColor(dim()); setPadding(0, p / 2, 0, p / 2) })
    body.addView(label("${Offline.size(Offline.usedBytes())} used · ${Offline.size(Offline.freeBytes())} free on the phone", 13f, bold = true).apply {
        setTextColor(dim()); setPadding(0, 0, 0, p / 2)
    })
    // settings
    body.addView(switchRow("Download on Wi-Fi only", Offline.wifiOnly) { Offline.wifiOnly = it })
    body.addView(switchRow("Update downloaded maps automatically", Offline.autoUpdate) { Offline.autoUpdate = it })
    body.addView(switchRow("Keep the map along every route", Offline.alongRoutes) { Offline.alongRoutes = it })
    // what's downloading
    val dl = Offline.downloading
    if (dl != null) {
        val shown = Offline.regions.firstOrNull { it.id == dl }?.name ?: dl
        body.addView(label(if (Offline.waitingForWifi) "Waiting for Wi-Fi: $shown" else "Downloading $shown… ${Offline.done.get() * 100 / Offline.total.coerceAtLeast(1)}%", 15f, bold = true).apply {
            setTextColor(readable(accent)); setPadding(0, p, 0, 0)
        })
        body.addView(android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = Offline.total.coerceAtLeast(1); progress = Offline.done.get()
            progressTintList = android.content.res.ColorStateList.valueOf(accent)
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = p / 2; bottomMargin = p / 2 })
        body.addView(pill("Stop") { Offline.cancelled = true }.apply { setTextColor(ink) })
    }
    val date = java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault())
    // offline routing: the road file for Greece
    Offline.loadRoadsInfo()
    body.addView(label("OFFLINE ROUTING", 12f, bold = true).apply { setTextColor(dim()); letterSpacing = 0.06f; setPadding(0, p, 0, p / 3) })
    body.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(p, p / 2, p / 2, p / 2); background = bg(dim(if (OfflineRouter.present) 30 else 16), radius = 14f)
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(label((if (OfflineRouter.present) "✓ " else "") + L.t("Roads of Greece"), 15.5f, bold = true).apply { setTextColor(ink) })
            val sub = when {
                OfflineRouter.downloading -> L.t("Downloading… ${(OfflineRouter.progress * 100).toInt()}%")
                OfflineRouter.present -> "${Offline.size(OfflineRouter.bytes())} · ${date.format(java.util.Date(OfflineRouter.date()))} · " + L.t("routes and reroutes with no signal")
                Offline.roadsSize > 0 -> L.t("New routes and reroutes with no signal") + " · " + L.t("about ${Offline.size(Offline.roadsSize)}")
                else -> L.t("New routes and reroutes with no signal")
            }
            addView(label(sub, 13f).apply { setTextColor(dim()) })
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        when {
            OfflineRouter.downloading -> {}
            OfflineRouter.present -> addView(pill("Delete") {
                GameBuilder(this@renderOffline).setMessage(L.t("Delete the offline roads? Routes will need the internet again."))
                    .setPositiveButton("Delete") { _, _ -> OfflineRouter.delete(); Offline.changed.fire() }.setNegativeButton("Cancel", null).showT()
            }.apply { textSize = 14f; setTextColor(readable(Color.rgb(220, 60, 60))) })
            else -> addView(pill("⬇ Download") {
                if (Offline.wifiOnly && !Offline.onWifi()) Toast.makeText(this@renderOffline, L.t("It starts as soon as you're on Wi-Fi"), Toast.LENGTH_LONG).show()
                Offline.downloadRoads()
            }.apply { textSize = 14f; setTextColor(onAccent(accent)); background = bg(accent, radius = 14f) })
        }
    }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = p / 2 })
    // regions
    body.addView(label("REGIONS", 12f, bold = true).apply { setTextColor(dim()); letterSpacing = 0.06f; setPadding(0, p, 0, p / 3) })
    for (r in Offline.regions) {
        val have = Offline.downloaded(r)
        body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(p, p / 2, p / 2, p / 2); background = bg(dim(if (have != null) 30 else 16), radius = 14f)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(label((if (have != null) "✓ " else "") + r.name, 15.5f, bold = true).apply { setTextColor(ink) })
                addView(label(if (have != null) "${Offline.size(have.bytes)} · ${date.format(java.util.Date(have.at))}" + (if (Offline.autoUpdate) " · updates itself" else "")
                    else "about ${Offline.size(Offline.estimate(Offline.tileCount(r)))}", 13f).apply { setTextColor(dim()) })
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            if (have != null) addView(pill("Delete") {
                GameBuilder(this@renderOffline).setMessage(L.t("Delete the offline map of ${r.name}?"))
                    .setPositiveButton("Delete") { _, _ -> Offline.delete(have) }.setNegativeButton("Cancel", null).showT()
            }.apply { textSize = 14f; setTextColor(readable(Color.rgb(220, 60, 60))) })
            else if (dl == null) addView(pill("⬇ Download") {
                if (Offline.wifiOnly && !Offline.onWifi()) Toast.makeText(this@renderOffline, L.t("It starts as soon as you're on Wi-Fi"), Toast.LENGTH_LONG).show()
                Offline.download(r)
            }.apply { textSize = 14f; setTextColor(onAccent(accent)); background = bg(accent, radius = 14f) })
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = p / 2 })
    }
    // around the map's centre (a smaller piece)
    if (dl == null) {
        val center = map.center() ?: LocationTracker.position
        if (center != null) {
            body.addView(label("AROUND THE MAP'S CENTRE", 12f, bold = true).apply { setTextColor(dim()); letterSpacing = 0.06f; setPadding(0, p, 0, p / 3) })
            for ((km, title) in listOf(5 to "City centre", 15 to "Wider area")) {
                val n = Offline.tileCount(center, km)
                body.addView(pill("⬇  $title · ${km} km · about ${Offline.size(Offline.estimate(n) * 3)}") {
                    io.execute {
                        val name = try { Places.addressAt(center).let { a -> a.address.split(", ").firstOrNull()?.takeIf { it.isNotBlank() } ?: a.name } } catch (e: Exception) { null }
                            ?: "Area ${"%.2f".format(center.lat)}, ${"%.2f".format(center.lon)}"
                        Offline.download("$name ($km km)", center, km)
                    }
                }.apply {
                    textSize = 14f; setTextColor(ink); background = bg(Color.TRANSPARENT, accent, 1.5f, 14f)
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = p / 2 })
            }
        }
    }
    // areas downloaded by distance (not regions)
    val others = areas.filter { a -> Offline.regions.none { it.id == a.name } }
    if (others.isNotEmpty()) body.addView(label("ON THIS PHONE", 12f, bold = true).apply { setTextColor(dim()); letterSpacing = 0.06f; setPadding(0, p, 0, p / 3) })
    for (a in others) {
        body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(p, p / 2, p / 2, p / 2); background = bg(dim(22), radius = 14f)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(a.name, 16f, bold = true).apply { setTextColor(ink) })
                addView(label("${Offline.size(a.bytes)} · downloaded ${date.format(java.util.Date(a.at))}" + (if (Offline.autoUpdate) " · updates itself" else ""), 13f).apply { setTextColor(dim()) })
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(pill("Delete") {
                GameBuilder(this@renderOffline).setMessage("Delete the offline map of ${a.name}?")
                    .setPositiveButton("Delete") { _, _ -> Offline.delete(a) }.setNegativeButton("Cancel", null).showT()
            }.apply { textSize = 14f; setTextColor(readable(Color.rgb(220, 60, 60))) })
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = p / 2 })
    }
}

/** Your recap: this month or this year as a card; choose the lines; Share. */
internal fun MainActivity.renderRecap() {
    val key = "$recapYear|${recapShown.sorted()}|${theme.name}|${MapState.dark}|${Explorer.history().size}"
    if (key == recapKey && sheetHost.childCount > 0) return
    recapKey = key
    sheetHost.removeAllViews()
    sheetHost.background = bg(panel, radius = 22f, topOnly = true)
    sheetHost.addView(sheetHeader("Your recap"))
    val p = (12 * d).toInt()
    val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    sheetHost.addView(SheetScroll(0.78).apply { addView(body) }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    // month / year
    body.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; setPadding(0, p / 2, 0, p / 2)
        for ((yr, t) in listOf(false to "This month", true to "This year")) addView(pill(L.t(t)) { recapYear = yr; recapKey = ""; refreshChrome() }.apply {
            if (recapYear == yr) { setTextColor(onAccent(accent)); background = bg(accent, radius = 14f) } else { setTextColor(ink); background = bg(Color.TRANSPARENT, dim(120), 1.5f, 14f) }
        }, LinearLayout.LayoutParams(0, (42 * d).toInt(), 1f).apply { marginEnd = p / 2 })
    })
    val stats = Recap.stats(recapYear)
    val title = Xp.title()
    // the card itself
    body.addView(object : View(this) {
        override fun onDraw(c: Canvas) { Recap.draw(c, width.toFloat(), height.toFloat(), stats, recapShown, theme, Convoy.myName, title) }
    }, LinearLayout.LayoutParams(MATCH_PARENT, 0).apply { height = ((resources.displayMetrics.widthPixels - 2 * p) * 1.25f).toInt().coerceAtMost((440 * d).toInt()) })
    // what's on it
    body.addView(sectionTitle(L.t("ON THE CARD")))
    for ((k, label) in Recap.LINES) body.addView(switchRow(label, k in recapShown) { on -> if (on) recapShown += k else recapShown -= k; recapKey = ""; refreshChrome() })
    body.addView(label(L.t("Never on it: Home, Work, or where and when you drove."), 12.5f).apply { setTextColor(dim()); setPadding(p / 4, p / 3, 0, p / 2) })
    sheetHost.addView(pill("⤴  " + L.t("Share")) { shareRecap(stats, title) }.apply {
        setTextColor(onAccent(accent)); background = bg(accent, radius = 14f)
    }, LinearLayout.LayoutParams(MATCH_PARENT, (50 * d).toInt()).apply { topMargin = p / 2 })
}

/** The card as a picture (1080 × 1350), shared to the app you pick. */
internal fun MainActivity.shareRecap(stats: Recap.Stats, title: String) {
    try {
        val bmp = Bitmap.createBitmap(1080, 1350, Bitmap.Config.ARGB_8888)
        Recap.draw(Canvas(bmp), 1080f, 1350f, stats, recapShown, theme, Convoy.myName, title)
        val dir = java.io.File(cacheDir, "share").apply { mkdirs() }
        val f = java.io.File(dir, "recap.png")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.share", f)
        val send = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TEXT, L.t("My ${stats.period} on the road with GameRoute")).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, L.t("Share your recap")))
    } catch (e: Exception) { Toast.makeText(this, L.t("Couldn't share the card"), Toast.LENGTH_SHORT).show() }
}

internal fun MainActivity.renderTrips() {
    val list = Explorer.history()
    val key = "${list.size}|${list.firstOrNull()?.arrivedAt}|${theme.name}|${MapState.dark}"
    if (key == tripsKey && sheetHost.childCount > 0) return
    tripsKey = key
    sheetHost.removeAllViews()
    sheetHost.background = bg(panel, radius = 22f, topOnly = true)
    sheetHost.addView(sheetHeader("Trip history"))
    val p = (12 * d).toInt()
    if (list.isNotEmpty()) sheetHost.addView(pill("📅  " + L.t("Your recap")) { openSheet("recap") }.apply {
        setTextColor(onAccent(accent)); background = bg(accent, radius = 14f)
    }, LinearLayout.LayoutParams(MATCH_PARENT, (46 * d).toInt()).apply { topMargin = p / 2 })
    val km = list.sumOf { it.meters } / 1000
    val hours = list.sumOf { it.seconds } / 3600.0
    val saved = list.sumOf { it.savedSeconds } / 60
    sheetHost.addView(label(
        if (list.isEmpty()) "Your finished trips will show here."
        else "${list.size} trips · ${NavEngine.formatDistance(km * 1000)} · ${"%.1f".format(hours)} h driving" +
            (if (saved > 0) " · $saved min saved dodging traffic" else ""), 15f, bold = true,
    ).apply { setTextColor(readable(accent)); setPadding(0, p / 2, 0, p) })
    val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val day = java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.getDefault())
    var lastDay = ""
    for (t in list) {
        val dLabel = day.format(java.util.Date(t.arrivedAt))
        if (dLabel != lastDay) {
            lastDay = dLabel
            body.addView(label(dLabel.uppercase(), 12f, bold = true).apply { setTextColor(dim()); letterSpacing = 0.06f; setPadding(0, p, 0, p / 3) })
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(p, p * 3 / 4, p, p * 3 / 4)
            background = bg(dim(22), radius = 14f)
        }
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(t.place, 16f, bold = true).apply { setTextColor(ink); maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            addView(label("${NavEngine.clock(t.arrivedAt - t.seconds * 1000L)} → ${NavEngine.clock(t.arrivedAt)} · " +
                "${NavEngine.formatDuration(t.seconds.toDouble())} · ${NavEngine.formatDistance(t.meters)}", 13f).apply { setTextColor(dim()) })
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(label("+${t.xp} XP", 14f, bold = true).apply { setTextColor(readable(accent)) })
        body.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = p / 2 })
    }
    if (list.isNotEmpty()) body.addView(pill("Clear history") {
        GameBuilder(this@renderTrips).setMessage("Delete all ${list.size} trips from the history? Your XP stays.")
            .setPositiveButton("Delete") { _, _ -> Explorer.clearHistory(); tripsKey = ""; refreshChrome() }
            .setNegativeButton("Cancel", null).showT()
    }.apply { setTextColor(dim()) })
    sheetHost.addView(ScrollView(this).apply { addView(body) },
        LinearLayout.LayoutParams(MATCH_PARENT, minOf((resources.displayMetrics.heightPixels * 0.62f).toInt(), (560 * d).toInt())))
}

/** Your progress: level, title, streak, this week's challenges, the title ladder, prestige. */
internal fun MainActivity.renderProgress() {
    val key = "${Explorer.xp}|${Xp.prestige}|${theme.name}|${MapState.dark}|${Xp.streak}|${Xp.challenges().joinToString { "${it.have}" }}|${Xp.showToFriends}"
    if (key == progressKey && sheetHost.childCount > 0) return
    progressKey = key
    sheetHost.removeAllViews()
    sheetHost.background = bg(panel, radius = 22f, topOnly = true)
    sheetHost.addView(sheetHeader("Your progress"))
    val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    sheetHost.addView(SheetScroll(0.74).apply { addView(body) }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    val p = (12 * d).toInt()
    val (have, of) = Explorer.progress()
    val lvl = Explorer.level
    // level, title and the bar
    body.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, p / 2, 0, 0)
        addView(label("${lvl}", 30f, bold = true).apply {
            setTextColor(onAccent(accent)); gravity = Gravity.CENTER; typeface = theme.typeface; background = bg(accent, radius = 16f)
        }, LinearLayout.LayoutParams((64 * d).toInt(), (64 * d).toInt()).apply { marginEnd = p })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(Convoy.myName.ifBlank { L.t("You") }, 20f, bold = true).apply { setTextColor(ink); typeface = theme.typeface; maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            addView(label(Xp.stars + L.t(Xp.title()) + " · " + L.t(theme.title), 14f, bold = true).apply { setTextColor(readable(accent)); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }.rankMark())
            addView(label(L.t("Level $lvl · ${Explorer.xp} XP") + (if (Xp.prestige > 0) " · " + L.t("prestige ${Xp.prestige}") else ""), 13.5f).apply { setTextColor(dim()) })
            addView(bar(have.toFloat() / maxOf(of, 1), accent), LinearLayout.LayoutParams(MATCH_PARENT, (9 * d).toInt()).apply { topMargin = p / 2 })
            val next = Xp.nextUnlock(lvl)
            addView(label(L.t("${of - have} XP to level ${lvl + 1}") + (next?.takeIf { it.level == lvl + 1 }?.let { " · " + L.t("unlocks ${it.name}") } ?: ""), 12.5f).apply { setTextColor(dim()); setPadding(0, p / 4, 0, 0) })
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
    })
    // streak and the Garage
    body.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; setPadding(0, p, 0, 0)
        fun box(top: String, big: String, sub: String, col: Int, onClick: (() -> Unit)?) = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(p, p * 2 / 3, p, p * 2 / 3)
            background = bg(Color.argb(30, Color.red(col), Color.green(col), Color.blue(col)), col, 1.5f, 14f)
            addView(label(top, 11.5f, bold = true).apply { setTextColor(readable(col)); letterSpacing = 0.1f })
            addView(label(big, 19f, bold = true).apply { setTextColor(ink) })
            addView(label(sub, 12.5f).apply { setTextColor(dim()) })
            if (onClick != null) { isClickable = true; setOnClickListener { onClick() } }
        }
        val st = Xp.streak
        addView(box(L.t("STREAK"), if (st > 0) "🔥 " + L.t(if (st == 1) "1 day" else "$st days") else L.t("No streak"), L.t("+10 XP a day, up to +50"), Color.rgb(255, 176, 40), null),
            LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { marginEnd = p * 2 / 3 })
        addView(box(L.t("GARAGE"), L.t("${Xp.items.count { it.unlocked }} of ${Xp.items.size}"), L.t("Arrows, route colours, flags") + " ›", accent) { openSheet("garage") },
            LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
    })
    // this week
    body.addView(sectionTitle(L.t("THIS WEEK · NEW EVERY MONDAY")))
    for (c in Xp.challenges()) body.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(p, p * 2 / 3, p, p * 2 / 3); background = bg(dim(20), radius = 12f)
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(L.t(c.title), 15.5f, bold = true).apply { setTextColor(ink) })
            addView(bar(c.have.toFloat() / c.of, if (c.done) Color.rgb(47, 180, 100) else Color.rgb(255, 176, 40)), LinearLayout.LayoutParams(MATCH_PARENT, (6 * d).toInt()).apply { topMargin = p / 3 })
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = p })
        addView(label(if (c.done) "✓ ${c.reward} XP" else "${c.have}/${c.of} · ${c.reward} XP", 14f, bold = true).apply {
            setTextColor(readable(if (c.done) Color.rgb(47, 180, 100) else Color.rgb(255, 176, 40)))
        })
    }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = p / 2 })
    // the titles of this style
    body.addView(sectionTitle(L.t("TITLES IN ${L.t(theme.title).uppercase()}")))
    Xp.ladder(theme).forEachIndexed { i, t ->
        val at = Xp.titleLevels[i]
        val now = Xp.title() == t && (i + 1 >= Xp.titleLevels.size || lvl < Xp.titleLevels[i + 1])
        val got = lvl >= at
        body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(p / 2, p / 3, 0, p / 3)
            addView(View(context).apply { background = if (got) bg(if (now) accent else dim(160), radius = 7f) else bg(Color.TRANSPARENT, dim(120), 2f, 7f) },
                LinearLayout.LayoutParams((14 * d).toInt(), (14 * d).toInt()).apply { marginEnd = p })
            addView(label("Lv $at", 13f, bold = true).apply { setTextColor(if (now) readable(accent) else dim()); minWidth = (52 * d).toInt() })
            addView(label(L.t(t), 16f, bold = now).apply { setTextColor(if (got) ink else dim()) })
        })
    }
    // prestige: the ranks, what the next one brings, and the button at level 50
    body.addView(sectionTitle(L.t("PRESTIGE")))
    val (nextName, nextBoost, nextItems) = Xp.nextRank()
    if (Xp.prestige > 0) body.addView(label("◆ " + L.t(Xp.rankName()) + " · +${Xp.boostPct}% XP", 16f, bold = true).apply {
        setTextColor(readable(Xp.rankColor())); setPadding(p / 4, 0, 0, p / 3)
    })
    body.addView(label(if (Xp.canPrestige) L.t("You reached level 50. Prestige to start again at level 1 as $nextName: +$nextBoost% XP for good and new Garage rewards. Everything you unlocked stays.")
        else L.t("At level 50 you can prestige: start again at level 1 as $nextName, with +$nextBoost% XP for good and new Garage rewards. Everything you unlocked stays."), 13.5f).apply { setTextColor(dim()); setPadding(p / 4, 0, 0, p / 2) })
    // the five ranks in a row: done, next, later
    body.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, p / 2)
        Xp.ranks.forEachIndexed { i, rk ->
            val got = Xp.prestige >= i + 1
            val col = Xp.rankColors[i]
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                addView(label("◆", 22f, bold = true).apply { setTextColor(if (got) col else dim(90)); gravity = Gravity.CENTER })
                addView(label(L.t(rk), 11.5f, bold = got).apply { setTextColor(if (got) ink else dim()); gravity = Gravity.CENTER })
                addView(label("+${(i + 1) * 10}%", 11f).apply { setTextColor(dim()); gravity = Gravity.CENTER })
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
    }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    if (nextItems.isNotEmpty()) body.addView(label(L.t("$nextName rewards: ") + nextItems.joinToString(", ") { L.t(it.name) }, 13f).apply { setTextColor(dim()); setPadding(p / 4, 0, 0, p / 2) })
    if (Xp.canPrestige) body.addView(pill("◆  " + L.t("Prestige to $nextName")) {
        GameBuilder(this@renderProgress).setTitle(L.t("Become $nextName?")).setMessage(L.t("You start again at level 1 with +$nextBoost% XP for good. Everything in the Garage stays, and the $nextName rewards are added."))
            .setPositiveButton(L.t("Prestige")) { _, _ -> Xp.doPrestige(); progressKey = ""; refreshChrome(); showPrestigeReveal() }.setNegativeButton("Cancel", null).showT()
    }.apply { setTextColor(onAccent(accent)); background = bg(accent, radius = 14f) }, LinearLayout.LayoutParams(MATCH_PARENT, (50 * d).toInt()))
    // friends
    body.addView(switchRow("Show my level to friends", Xp.showToFriends) { Xp.showToFriends = it; Friends.Presence.refresh() })
    body.addView(label("Off: only you see it. On: your level and title show next to your name for friends and in convoys (never your trips or where you've been).", 12.5f).apply { setTextColor(dim()); setPadding(p / 4, 0, 0, p) })
}

/** A thin progress bar. */
internal fun MainActivity.bar(f: Float, col: Int) = object : View(this) {
    private val pt = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c: Canvas) {
        val r = height / 2f
        pt.color = dim(50); c.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, pt)
        pt.color = col; c.drawRoundRect(0f, 0f, width * f.coerceIn(0f, 1f), height.toFloat(), r, r, pt)
    }
}

/** The Garage: pick your arrow, route colour, destination flag and arrival effect. */
internal fun MainActivity.renderGarage() {
    val key = "${Xp.version}|${Xp.maxLevel}|$garageTab|${theme.name}|${MapState.dark}"
    if (key == garageKey && sheetHost.childCount > 0) return
    garageKey = key
    sheetHost.removeAllViews()
    sheetHost.background = bg(panel, radius = 22f, topOnly = true)
    sheetHost.addView(sheetHeader("Garage"))
    val p = (12 * d).toInt()
    sheetHost.addView(label(Xp.stars + L.t(Xp.title()) + " · " + L.t("level ${Explorer.level}") + " · " + L.t(theme.title), 13.5f).apply { setTextColor(dim()); setPadding(p / 4, 0, 0, p / 2) }.rankMark())
    // tabs
    sheetHost.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        val q = (4 * d).toInt(); setPadding(q, q, q, q); background = bg(dim(26), radius = 14f)
        for ((k, t) in listOf("arrow" to "Arrow", "route" to "Route", "flag" to "Flag", "arrival" to "Arrival")) {
            val sel = k == garageTab
            addView(TextView(context).apply {
                text = L.t(t); textSize = 14f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
                setTextColor(if (sel) onAccent(accent) else ink); if (sel) background = bg(accent, radius = 11f)
                setOnClickListener { garageTab = k; renderGarage() }
            }, LinearLayout.LayoutParams(0, (40 * d).toInt(), 1f))
        }
    })
    val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    sheetHost.addView(SheetScroll(0.6).apply { addView(body) }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = p / 2 })
    val look = theme.look
    val list = Xp.kindItems(garageTab)
    val chosen = Xp.chosen(garageTab)
    var row: LinearLayout? = null
    list.forEachIndexed { i, it ->
        val item = it
        if (i % 2 == 0) { row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }; body.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = p / 2 }) }
        val readable = Xp.readableOn(it, look)
        val usable = it.unlocked && readable
        val sel = it.id == chosen.id
        row?.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(p, p, p, p)
            background = bg(dim(20), if (sel) accent else dim(60), if (sel) 2.5f else 1f, 14f)
            alpha = if (usable) 1f else 0.5f
            addView(GarageSwatch(context, it, look), LinearLayout.LayoutParams(MATCH_PARENT, (64 * d).toInt()))
            addView(label(L.t(it.name), 15f, bold = true).apply { setTextColor(ink); setPadding(0, p / 2, 0, 0) })
            addView(label(when {
                sel -> L.t("In use")
                !it.unlocked && it.rank > 0 -> "◆ " + L.t(Xp.rankName(it.rank)) + " " + L.t("rank")
                !it.unlocked && it.level >= 50 -> L.t("Prestige")
                !it.unlocked -> "🔒 " + L.t("Level ${it.level}") + if (it.milestone) " · " + L.t("milestone") else ""
                !readable -> L.t("Hard to see on this style")
                else -> L.t("Tap to use")
            }, 12.5f, bold = sel).apply { setTextColor(if (sel) readable(accent) else dim()) })
            isClickable = usable
            setOnClickListener { if (usable) { Xp.choose(item); MapState.changed.fire(); renderGarage() } }
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { if (i % 2 == 0) marginEnd = p / 2 })
    }
    if (list.size % 2 == 1) row?.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
    body.addView(label(when (garageTab) {
        "route" -> "Only colours that stand out on the current style can be used, so the route is always easy to follow."
        "arrival" -> "Plays on the arrival card, once you've stopped. Never while driving."
        "flag" -> "Marks where you're going on the map."
        else -> "Your arrow on the map. Every shape points the way you drive."
    }, 12.5f).apply { setTextColor(dim()); setPadding(p / 4, p, 0, p / 2) })
}

/** After a level-up (shown once you've parked): the new level, title and unlocks. */
/**
 * Level up (after parking). Crossing a multiple of 5: first the celebration in this style's
 * look (Settings › Performance › Animations: Full only), then the card.
 */
/** The highest level already celebrated (remembered, so a level-up is never missed nor shown twice). */
internal fun MainActivity.celebratedLevel(): Int {
    val p = prefs()
    // (a new name: a test build marked a celebration as seen before it had played to the end)
    if (!p.contains("levelCelebrated")) p.edit().putInt("levelCelebrated", (Explorer.level - 1).coerceAtLeast(1)).apply()
    return p.getInt("levelCelebrated", Explorer.level)
}

internal fun MainActivity.showLevelUp(from: Int) {
    val to = Explorer.level
    if (to <= from || isFinishing) return
    if (to <= celebratedLevel() || celebrating) return
    celebrating = true
    val milestone = (from + 1..to).lastOrNull { it % 5 == 0 }
    if (milestone != null && Perf.anim == Perf.FULL) {
        val host = window.decorView as ViewGroup
        lateinit var view: Milestone
        view = Milestone(this, milestone, Xp.title(theme, milestone), theme) {
            host.post { (view.parent as? ViewGroup)?.removeView(view); levelSeen(milestone.coerceAtLeast(to)); showLevelUpCard(from) }
        }
        view.elevation = 60 * d
        host.addView(view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        return
    }
    levelSeen(to)
    showLevelUpCard(from)
}

internal fun MainActivity.levelSeen(level: Int) {
    celebrating = false
    prefs().edit().putInt("levelCelebrated", maxOf(level, prefs().getInt("levelCelebrated", 0))).apply()
}

/** Developer page: play the celebration for [level] now (nothing is counted). */
internal fun MainActivity.playCelebration(level: Int) {
    val host = window.decorView as ViewGroup
    lateinit var view: Milestone
    // then the card for that same level, with what it unlocks
    view = Milestone(this, level, Xp.title(theme, level), theme) { host.post { (view.parent as? ViewGroup)?.removeView(view); showLevelUpCard(level - 1, level, preview = true) } }
    view.elevation = 60 * d
    host.addView(view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
}

/** For tests: the celebration for [level], drawn at [ms]. */
internal fun MainActivity.milestoneForTest(level: Int, ms: Long): Milestone = Milestone(this, level, Xp.title(theme, level), theme) {}.apply { fixedMs = ms }

/** [preview]: the Developer page showing the card for [toLevel] (nothing is chosen or unlocked). */
internal fun MainActivity.showLevelUpCard(from: Int, toLevel: Int = Explorer.level, preview: Boolean = false) {
    val to = toLevel
    if (to <= from || isFinishing) return
    val p = (20 * d).toInt()
    lateinit var dlg: AlertDialog
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(p, p, p, p)
        addView(label(L.t("LEVEL UP"), 14f, bold = true).apply { setTextColor(readable(accent)); letterSpacing = 0.3f })
        addView(label("$to", 64f, bold = true).apply { setTextColor(ink); typeface = theme.typeface; gravity = Gravity.CENTER })
        val oldT = Xp.title(theme, from); val newT = Xp.title(theme, to)
        if (oldT != newT) {
            addView(label(L.t("New title"), 13f).apply { setTextColor(dim()) })
            addView(label(Xp.stars + L.t(newT), 22f, bold = true).apply { setTextColor(ink); typeface = theme.typeface }.rankMark())
            addView(label(L.t(oldT) + " → " + L.t(newT), 13f).apply { setTextColor(dim()) })
        } else addView(label(Xp.stars + L.t(newT), 18f, bold = true).apply { setTextColor(ink) }.rankMark())
        val unlocks = (from + 1..to).flatMap { Xp.unlockedAt(it) }
        if (unlocks.isNotEmpty()) {
            addView(label(L.t("UNLOCKED"), 12f, bold = true).apply { setTextColor(readable(accent)); letterSpacing = 0.12f; setPadding(0, p, 0, p / 3) })
            for (u in unlocks) addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(GarageSwatch(context, u, theme.look), LinearLayout.LayoutParams((70 * d).toInt(), (48 * d).toInt()).apply { marginEnd = p / 2 })
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(label(L.t(u.name), 16f, bold = true).apply { setTextColor(ink) })
                    addView(label(L.t(when (u.kind) { "arrow" -> "New arrow"; "route" -> "New route colour"; "flag" -> "New destination flag"; else -> "New arrival effect" }), 13f).apply { setTextColor(dim()) })
                })
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = p / 3 })
        }
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, p, 0, 0)
            addView(pill("Later") { dlg.dismiss() }.apply { setTextColor(ink); background = bg(Color.TRANSPARENT, dim(120), 1.5f, 14f) },
                LinearLayout.LayoutParams(0, (50 * d).toInt(), 1f).apply { marginEnd = p / 2 })
            if (preview) addView(label(L.t("Preview (test build)"), 13f).apply { setTextColor(dim()); gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(0, (50 * d).toInt(), 1.3f))
            else if (to >= 50) addView(pill("See prestige") { dlg.dismiss(); openSheet("progress") }
                .apply { setTextColor(onAccent(accent)); background = bg(accent, radius = 14f) }, LinearLayout.LayoutParams(0, (50 * d).toInt(), 1.3f))
            else if (unlocks.isNotEmpty()) addView(pill("Use it now") {
                dlg.dismiss(); val u = unlocks.last(); if (Xp.readableOn(u, theme.look)) Xp.choose(u); garageTab = u.kind; openSheet("garage")
            }.apply { setTextColor(onAccent(accent)); background = bg(accent, radius = 14f) }, LinearLayout.LayoutParams(0, (50 * d).toInt(), 1.3f))
        })
    }
    L.translateTree(box); Icons.applyTree(box)
    box.background = bg(panel, accent, 2f, 22f)
    dlg = AlertDialog.Builder(this).setView(box).create().apply { setCanceledOnTouchOutside(false) } // a stray tap after the celebration must not close it
    dlg.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
    dlg.show()
}

/** Right after a prestige: the new rank, its boost and its rewards (Use it now: the Garage). */
internal fun MainActivity.showPrestigeReveal() {
    val p = (20 * d).toInt()
    val col = Xp.rankColor()
    val rewards = Xp.items.filter { it.rank == Xp.prestige.coerceAtMost(5) }
    lateinit var dlg: AlertDialog
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(p, p, p, p)
        addView(label(L.t("PRESTIGE"), 14f, bold = true).apply { setTextColor(readable(accent)); letterSpacing = 0.3f })
        addView(label("◆", 64f, bold = true).apply { setTextColor(col); gravity = Gravity.CENTER })
        addView(label(L.t(Xp.rankName()), 30f, bold = true).apply { setTextColor(ink); typeface = theme.typeface; gravity = Gravity.CENTER })
        addView(label(L.t("+${Xp.boostPct}% XP on every drive, for good"), 15f).apply { setTextColor(dim()); gravity = Gravity.CENTER; setPadding(0, p / 4, 0, p / 2) })
        for (u in rewards) addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(GarageSwatch(context, u, theme.look), LinearLayout.LayoutParams((70 * d).toInt(), (48 * d).toInt()).apply { marginEnd = p / 2 })
            addView(label(L.t(u.name), 16f, bold = true).apply { setTextColor(ink) })
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = p / 3 })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, p, 0, 0)
            addView(pill("Later") { dlg.dismiss() }.apply { setTextColor(ink); background = bg(Color.TRANSPARENT, dim(120), 1.5f, 14f) },
                LinearLayout.LayoutParams(0, (50 * d).toInt(), 1f).apply { marginEnd = p / 2 })
            if (rewards.isNotEmpty()) addView(pill("Use it now") { dlg.dismiss(); Xp.choose(rewards.first()); garageTab = rewards.first().kind; openSheet("garage") }
                .apply { setTextColor(onAccent(accent)); background = bg(accent, radius = 14f) }, LinearLayout.LayoutParams(0, (50 * d).toInt(), 1.3f))
        })
    }
    L.translateTree(box); Icons.applyTree(box)
    box.background = bg(panel, col, 2.5f, 22f)
    dlg = AlertDialog.Builder(this).setView(box).create()
    dlg.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
    dlg.show()
}

/** What a trip earned, line by line. */
internal fun MainActivity.showXpParts(trip: Explorer.Trip) {
    val lines = if (trip.parts.isEmpty()) listOf("+${trip.xp} XP")
        else trip.parts.map { "${if (it.xp > 0) "+${it.xp}" else "0"}  ${L.t(it.label)}" + (if (it.note.isNotBlank()) " · " + L.t(it.note) else "") }
    GameBuilder(this@showXpParts).setTitle(L.t("+${trip.xp} XP on this trip"))
        .setMessage(lines.joinToString("\n") + "\n\n" + L.t("No XP for speed: race places count only after a clean drive."))
        .setPositiveButton("OK", null).showT()
}

internal fun MainActivity.showProfile() { openSheet("progress") }

@Suppress("unused")
internal fun MainActivity.showProfileOld() {
    val (have, of) = Explorer.progress()
    GameBuilder(this@showProfileOld)
        .setTitle("Explorer · level ${Explorer.level}")
        .setMessage("${Explorer.xp} XP in total ($have / $of to the next level)\n" +
            "${Explorer.foundCount} landmarks discovered\n\n" +
            "Every trip of 200 m or more earns XP (more for longer drives and for taking faster routes). " +
            "Pass close to museums, monuments, viewpoints and other landmarks to discover them: " +
            "+25 XP each. In the Catch 'Em Trail style they show on the map as blue ? markers.")
        .setPositiveButton("OK", null).showT()
}

internal fun MainActivity.showAbout() {
    GameBuilder(this@showAbout)
        .setTitle("GameRoute build ${shownBuild()}")
        .setMessage("Map data and speed cameras © OpenStreetMap contributors\nVector tiles: OpenFreeMap\nTraffic, incidents, speed limits: TomTom\nRouting: OSRM (TomTom as backup)\nSearch: Photon (komoot), Nominatim\nVoice: your phone's text-to-speech")
        .setPositiveButton("OK", null).showT()
}

internal fun MainActivity.checkForUpdates(manual: Boolean) {
    if (manual) Toast.makeText(this, L.t("Checking for updates…"), Toast.LENGTH_SHORT).show()
    io.execute {
        val r = try { Updater.check(this) } catch (e: Exception) { null }
        runOnUiThread {
            if (isFinishing) return@runOnUiThread
            if (r == null) {
                if (manual) Toast.makeText(this, L.t("You have the latest version (build ${shownBuild()})"), Toast.LENGTH_SHORT).show()
                return@runOnUiThread
            }
            GameBuilder(this@checkForUpdates)
                .setTitle("Update available")
                .setMessage("${r.tag} is ready (you have build ${shownBuild()}). Download and install it?")
                .setPositiveButton("Update") { _, _ -> downloadAndInstall(r) }
                .setNegativeButton("Later", null)
                .showT()
        }
    }
}

internal fun MainActivity.downloadAndInstall(r: Updater.Release) {
    Toast.makeText(this, L.t("Downloading update…"), Toast.LENGTH_LONG).show()
    io.execute {
        val ok = try { Updater.download(this, r) {}; true } catch (e: Exception) { false }
        runOnUiThread {
            if (!ok) {
                Toast.makeText(this, L.t("Download failed: check internet"), Toast.LENGTH_LONG).show()
                return@runOnUiThread
            }
            val viaUnknownAuto = Updater.hasUnknownAuto(this)
            GameBuilder(this@downloadAndInstall)
                .setTitle("Install update")
                .setMessage(
                    if (viaUnknownAuto)
                        "Make sure Shizuku is running, then tap Install in UnknownAuto. " +
                            "This keeps GameRoute visible in Android Auto."
                    else
                        "UnknownAuto isn't installed, so Android's normal installer will be used. " +
                            "The update will work on the phone, but Android Auto may hide GameRoute " +
                            "until you reinstall it through UnknownAuto."
                )
                .setPositiveButton("Continue") { _, _ ->
                    try { Updater.install(this) } catch (e: Exception) {
                        Toast.makeText(this, L.t("Couldn't open the installer: ${e.message}"), Toast.LENGTH_LONG).show()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }
}
