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

/** You: progress, Garage, friends, trips, and the app's settings, in one place. */
/** Green Online, red Do not disturb, grey Invisible. */
internal fun MainActivity.statusColor(s: String) = when (s) { FriendCore.DND -> Color.rgb(240, 82, 82); FriendCore.INVISIBLE -> Color.rgb(140, 148, 160); else -> Color.rgb(64, 200, 120) }

internal fun MainActivity.statusName(s: String) = L.t(when (s) { FriendCore.DND -> "Do not disturb"; FriendCore.INVISIBLE -> "Invisible"; else -> "Online" })

/** "● Online ▾": tap to change. */
internal fun MainActivity.statusChip(s: String, onClick: () -> Unit) = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
    val col = statusColor(s)
    setPadding((10 * d).toInt(), (4 * d).toInt(), (10 * d).toInt(), (4 * d).toInt())
    background = bg(Color.TRANSPARENT, col, 1.5f, 16f)
    minimumHeight = (32 * d).toInt()
    addView(View(context).apply { background = bg(col, radius = 5f) }, LinearLayout.LayoutParams((10 * d).toInt(), (10 * d).toInt()).apply { marginEnd = (7 * d).toInt() })
    addView(label(statusName(s), 14.5f, bold = true).apply { setTextColor(ink) })
    addView(label(" ▾", 13f).apply { setTextColor(dim()) })
    isClickable = true; setOnClickListener { onClick() }
    contentDescription = L.t("Your status") + ": " + statusName(s)
}

/** Online / Do not disturb / Invisible, and when to go back to Online by itself. */
internal fun MainActivity.chooseStatus() {
    if (!Friends.ready) return
    val keys = arrayOf(FriendCore.ONLINE, FriendCore.DND, FriendCore.INVISIBLE)
    val opts = arrayOf(
        L.t("Online") + "\n" + L.t("Friends see you online. Requests and convoy invites arrive straight away."),
        L.t("Do not disturb") + "\n" + L.t("Friends see you as busy. Requests and invites wait silently until you switch back."),
        L.t("Invisible") + "\n" + L.t("You look offline to everyone. Invites to you aren't delivered. You can still see friends and invite them."))
    GameBuilder(this@chooseStatus).setTitle(L.t("Your status"))
        .setDots(intArrayOf(Color.rgb(76, 211, 138), Color.rgb(255, 92, 92), Color.rgb(138, 143, 153)))
        .setSingleChoiceItems(opts, keys.indexOf(Friends.core.status).coerceAtLeast(0)) { dlg, k ->
            dlg.dismiss()
            if (keys[k] == FriendCore.ONLINE) { Friends.setStatus(FriendCore.ONLINE); youKey = ""; refreshChrome(); return@setSingleChoiceItems }
            // back to Online after…
            val now = System.currentTimeMillis()
            val tonight = java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, 23); set(java.util.Calendar.MINUTE, 59) }.timeInMillis
            val untils = longArrayOf(now + 3_600_000L, if (tonight > now + 600_000) tonight else now + 86_400_000L, 0L)
            GameBuilder(this@chooseStatus).setTitle(L.t("Back to Online after"))
                .setItems(arrayOf(L.t("1 hour"), L.t("Tonight"), L.t("Never (until I change it)"))) { _, u ->
                    Friends.setStatus(keys[k], untils[u]); youKey = ""; refreshChrome()
                }.showT()
        }.showT()
}

internal fun MainActivity.renderYou() {
    val c = if (Friends.ready && Friends.core.started) Friends.core else null
    val key = "${Convoy.myName}|${Explorer.xp}|${Xp.prestige}|${theme.name}|${MapState.dark}|${Xp.streak}|${c?.status}|${c?.friends?.joinToString { it.fid + c.isOnline(it.fid) + c.isBusy(it.fid) + c.nickname(it.fid) }}|" +
        "${c?.requests?.size}|${c?.invites?.size}|${Xp.challenges().joinToString { "${it.have}" }}|${Offline.areas().size}"
    if (key == youKey && sheetHost.childCount > 0) return
    youKey = key
    sheetHost.removeAllViews()
    sheetHost.background = bg(panel, radius = 22f, topOnly = true)
    val p = (12 * d).toInt()
    val (have, of) = Explorer.progress()
    // who I am: the ring, my title, level and streak
    sheetHost.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, p / 2, 0, p / 2)
        sheetStack.lastOrNull()?.let { from -> addView(backPill(sheetTitle(from)) { sheetBack() }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = p / 2 }) }
        addView(YouRing(context, big = true).apply { setOnClickListener { chooseAvatar() }; contentDescription = L.t("Your avatar") }, LinearLayout.LayoutParams((64 * d).toInt(), (64 * d).toInt()).apply { marginEnd = p })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // your name first (tap to change it), then your title in this style, then the level
            addView(label(Convoy.myName.ifBlank { L.t("Your name") }, 24f, bold = true).apply {
                setTextColor(if (Convoy.myName.isBlank()) dim() else ink); typeface = theme.typeface; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                contentDescription = L.t("Your name, tap to change it")
                setOnClickListener { editConvoyName { youKey = ""; refreshChrome() } }
            })
            addView(label(Xp.stars + L.t(Xp.title()), 14.5f, bold = true).apply { setTextColor(readable(accent)); maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, (2 * d).toInt(), 0, 0) }.rankMark())
            addView(label(L.t("Level ${Explorer.level}") + " · $have / $of XP", 13.5f).apply { setTextColor(dim()) })
            if (Xp.streak > 0) addView(label("🔥 " + L.t(if (Xp.streak == 1) "1-day streak" else "${Xp.streak}-day streak"), 13.5f, bold = true).apply { setTextColor(readable(Color.rgb(255, 176, 40))) })
            // your status: who can reach you (Online / Do not disturb / Invisible)
            if (c != null) addView(statusChip(c.status) { chooseStatus() }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = (6 * d).toInt() })
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(pill("✕") { closeSheet() }.apply { setTextColor(ink) })
    })
    val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    sheetHost.addView(SheetScroll(0.7).apply { addView(body) }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    // four tiles
    val waiting = friendsWaiting()
    body.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; setPadding(0, p / 2, 0, 0)
        listOf(Triple("✓", "Progress", "progress"), Triple("🏠", "Garage", "garage"), Triple("👥", "Friends", "friends"), Triple("🕘", "Trips", "trips")).forEachIndexed { i, (ic, t, k) ->
            addView(android.widget.FrameLayout(context).apply {
                background = bg(dim(22), if (k == "friends" && waiting > 0) accent else dim(60), if (k == "friends" && waiting > 0) 1.8f else 1f, 14f)
                isClickable = true; setOnClickListener { openSheet(k) }
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, p * 3 / 4, 0, p * 2 / 3)
                    addView(label(ic, 22f).apply { setTextColor(readable(accent)); gravity = Gravity.CENTER }, LinearLayout.LayoutParams((28 * d).toInt(), (28 * d).toInt()).apply { gravity = Gravity.CENTER_HORIZONTAL })
                    addView(label(t, 14f, bold = true).apply { setTextColor(ink); setPadding(0, p / 3, 0, 0); gravity = Gravity.CENTER }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                }, android.widget.FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                if (k == "friends" && waiting > 0) addView(label("$waiting", 11.5f, bold = true).apply {
                    setTextColor(Color.WHITE); gravity = Gravity.CENTER; background = bg(Color.rgb(230, 60, 60), radius = 9f)
                }, android.widget.FrameLayout.LayoutParams((20 * d).toInt(), (18 * d).toInt(), Gravity.END or Gravity.TOP).apply { topMargin = (5 * d).toInt(); rightMargin = (5 * d).toInt() })
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { if (i < 3) marginEnd = p / 2 })
        }
    })
    // friends: what's waiting first, then everyone as circles
    if (c != null) {
        val online = c.friends.count { c.isOnline(it.fid) }
        body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(sectionTitle(L.t("FRIENDS") + if (online > 0) " · " + L.t("$online ONLINE") else ""), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            if (c.friends.isNotEmpty()) addView(label(L.t("See all ${c.friends.size}") + " ›", 14f, bold = true).apply {
                setTextColor(readable(accent)); setPadding(p / 2, (14 * d).toInt(), p / 3, 0); maxLines = 1; setOnClickListener { openSheet("friends") }
            })
        })
        // waiting: a card each, the text on top and the buttons under it (fits any text size)
        fun waitingCard(avatarOf: Pair<String, String>?, title: String, sub: String, no: () -> Unit, yesText: String, yes: () -> Unit) = body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(p, p * 2 / 3, p, p * 2 / 3); background = bg(Color.argb(30, Color.red(accent), Color.green(accent), Color.blue(accent)), accent, 1.5f, 14f)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                avatarOf?.let { (n, id) -> addView(avatar(n, id, false, 38), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = p }) }
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(label(title, 15.5f, bold = true).apply { setTextColor(ink) })
                    addView(label(sub, 12.5f).apply { setTextColor(dim()); maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END; setPadding(0, p / 2, 0, 0)
                addView(pill("Not now") { no() }.apply { textSize = 14f; setTextColor(dim()); minHeight = (40 * d).toInt(); val q = (10 * d).toInt(); setPadding(q + q / 2, 0, q + q / 2, 0) },
                    LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = p / 2 })
                addView(pill(yesText) { yes() }.apply {
                    textSize = 14f; setTextColor(onAccent(accent)); background = bg(accent, radius = 12f)
                    minHeight = (40 * d).toInt(); val q = (10 * d).toInt(); setPadding(q * 2, 0, q * 2, 0)
                })
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = p / 2 })
        for (rq in c.requests.take(2)) waitingCard(rq.name to rq.fid, L.t("${rq.name} wants to be friends"), since(rq.at), { Friends.decline(rq) }, "Accept") { Friends.accept(rq) }
        for (inv in c.invites.filter { it.convoy != Convoy.code }.take(1)) waitingCard(null, L.t("${inv.from} invites you to a convoy"), inv.dest.ifBlank { L.t("No destination yet") },
            { Friends.answer(inv.convoy, "no") }, "Join") { closeSheet(); joinConvoy(inv.convoy) }
        body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            for (f in c.friends.sortedByDescending { c.isOnline(it.fid) }.take(4)) {
                val on = c.isOnline(f.fid)
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                    isClickable = true; setOnClickListener { showFriendCard(f) }
                    addView(avatar(c.shownName(f), f.fid, on, 46), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL })
                    addView(label(c.shownName(f), 13.5f, bold = true).apply { setTextColor(ink); maxLines = 1; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER; setPadding(0, (4 * d).toInt(), 0, 0) }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                    val busy = on && c.isBusy(f.fid)
                    addView(label(if (busy) L.t("busy") else if (on) L.t("online") else L.t("offline"), 11.5f).apply { setTextColor(if (busy) readable(Color.rgb(230, 70, 70)) else if (on) readable(Color.rgb(47, 180, 100)) else dim()); gravity = Gravity.CENTER })
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            }
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                isClickable = true; setOnClickListener { openSheet("addfriend") }
                addView(label("＋", 20f).apply {
                    gravity = Gravity.CENTER; setTextColor(readable(accent))
                    background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setStroke((1.5f * d).toInt(), accent, 5 * d, 4 * d) }
                }, LinearLayout.LayoutParams((46 * d).toInt(), (46 * d).toInt()).apply { gravity = Gravity.CENTER_HORIZONTAL })
                addView(label("Add", 13.5f, bold = true).apply { setTextColor(ink); gravity = Gravity.CENTER; setPadding(0, (4 * d).toInt(), 0, 0) }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            repeat((4 - c.friends.size).coerceAtLeast(0)) { addView(View(context), LinearLayout.LayoutParams(0, 1, 1f)) }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = p / 3 })
        if (c.friends.isNotEmpty()) body.addView(label("Tap a friend to invite them to a convoy.", 12.5f).apply { setTextColor(dim()); setPadding(p / 4, p / 2, 0, 0) })
    }
    // this week: the next challenge
    Xp.challenges().firstOrNull { !it.done }?.let { ch ->
        body.addView(sectionTitle(L.t("THIS WEEK")))
        body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(p, p * 2 / 3, p, p * 2 / 3); background = bg(dim(20), radius = 12f)
            isClickable = true; setOnClickListener { openSheet("progress") }
            addView(label(L.t(ch.title), 15.5f, bold = true).apply { setTextColor(ink) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(label("${ch.have}/${ch.of} · ${ch.reward} XP", 14f, bold = true).apply { setTextColor(readable(Color.rgb(255, 176, 40))) })
        })
    }
    // the app
    body.addView(sectionTitle(L.t("APP")))
    fun row(icon: Int, title: String, sub: String?, onClick: () -> Unit) = body.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(p / 4, p * 2 / 3, 0, p * 2 / 3)
        isClickable = true; setOnClickListener { onClick() }
        addView(android.widget.ImageView(context).apply {
            setImageResource(icon); imageTintList = android.content.res.ColorStateList.valueOf(readable(accent))
            val q = (7 * d).toInt(); setPadding(q, q, q, q); background = bg(Color.argb(36, Color.red(accent), Color.green(accent), Color.blue(accent)), radius = 10f)
        }, LinearLayout.LayoutParams((36 * d).toInt(), (36 * d).toInt()).apply { marginEnd = p })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(title, 16f, bold = true).apply { setTextColor(ink) })
            if (sub != null) addView(label(sub, 13f).apply { setTextColor(dim()); maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(label("›", 20f).apply { setTextColor(dim()) })
    })
    row(R.drawable.ic_set_map, "Offline maps", Offline.areas().let { a -> if (a.isEmpty()) L.t("Download regions and roads") else "${a.size} · ${Offline.size(a.sumOf { it.bytes })}" }) { openSheet("offline") }
    row(R.drawable.ic_theme, "Map style", L.t(theme.title)) { openSheet("style") }
    row(R.drawable.ic_set_app, "Settings", null) { showSettings() }
    row(R.drawable.ic_set_adv, "Help & about", null) { showAbout() }
}

/** Requests and invites waiting (not the convoy I'm in already). */
internal fun MainActivity.friendsWaiting(): Int {
    if (!Friends.ready || !Friends.core.started) return 0
    return Friends.core.requests.size + Friends.core.invites.count { it.convoy != Convoy.code }
}

internal fun MainActivity.onlineFriends(): List<FriendCore.Friend> =
    if (!Friends.ready || !Friends.core.started) emptyList() else Friends.core.friends.filter { Friends.core.isOnline(it.fid) }

internal fun MainActivity.updatePeopleBadge() {
    if (!peopleBadgeReady) return
    val n = friendsWaiting()
    peopleBadge.text = if (n > 9) "9+" else n.toString()
    peopleBadge.visibility = if (n > 0) View.VISIBLE else View.GONE
    val online = Friends.ready && Friends.core.started && Friends.core.friends.any { Friends.core.isOnline(it.fid) }
    peopleDot.visibility = if (online) View.VISIBLE else View.GONE
    peopleBtn.contentDescription = L.t("Friends") + (if (n > 0) " · " + L.t("$n waiting") else "") + (if (online) " · " + L.t("friends online") else "")
}

/** The people button: Together (Friends first; the convoy if I'm in one and nothing's waiting). */
internal fun MainActivity.openTogether() = openSheet(if (Convoy.active && friendsWaiting() == 0) "convoy" else "friends")

/** Convoy and Friends are one screen, "Together", with two tabs. */
internal fun MainActivity.togetherHeader(tab: String) {
    sheetHost.addView(sheetHeader("Together"))
    val p = (4 * d).toInt()
    val waiting = friendsWaiting()
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; setPadding(p, p, p, p); background = bg(dim(22), radius = 14f)
    }
    for ((key, title) in listOf("convoy" to "Convoy", "friends" to "Friends")) {
        val on = key == tab
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; minimumHeight = (44 * d).toInt()
            if (on) background = bg(accent, radius = 12f)
            isClickable = true; setOnClickListener { if (!on) switchSheet(key) }
            contentDescription = L.t(title)
            addView(label(if (key == "convoy") "👥 $title" else title, 15f, bold = true).apply { setTextColor(if (on) onAccent(accent) else ink) })
            if (key == "friends" && waiting > 0) addView(label(if (waiting > 9) "9+" else "$waiting", 11.5f, bold = true).apply {
                setTextColor(Color.WHITE); gravity = Gravity.CENTER; minWidth = (20 * d).toInt()
                val q = (5 * d).toInt(); setPadding(q, 0, q, 0); background = bg(Color.rgb(230, 60, 60), radius = 10f)
            }, LinearLayout.LayoutParams(WRAP_CONTENT, (20 * d).toInt()).apply { marginStart = (8 * d).toInt() })
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { if (key == "convoy") marginEnd = p })
    }
    sheetHost.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = (6 * d).toInt(); bottomMargin = (6 * d).toInt() })
}

internal fun MainActivity.since(at: Long): String {
    val m = ((System.currentTimeMillis() - at) / 60_000).coerceAtLeast(0)
    return when { m < 1 -> "just now"; m < 60 -> "$m min ago"; else -> "${m / 60} h ago" }
}

/** A round badge with someone's initial (their colour from their id). */
internal fun MainActivity.avatar(name: String, fid: String, online: Boolean, size: Int = 40) = android.widget.FrameLayout(this).apply {
    val pal = intArrayOf(Color.rgb(255, 146, 43), Color.rgb(77, 171, 247), Color.rgb(247, 131, 172), Color.rgb(99, 230, 190),
        Color.rgb(177, 151, 252), Color.rgb(255, 212, 59), Color.rgb(47, 210, 122))
    val col = pal[Math.floorMod(fid.hashCode(), pal.size)]
    // their avatar if they've chosen one (and aren't Invisible), else their initial
    val av = Avatars.ofFriend(fid)
    if (av != null) addView(AvatarView(context, av.first, av.second), android.widget.FrameLayout.LayoutParams((size * d).toInt(), (size * d).toInt()))
    else addView(label(name.trim().take(1).uppercase().ifBlank { "?" }, size * 0.4f, bold = true).apply {
        gravity = Gravity.CENTER; setTextColor(Color.rgb(17, 17, 17)); background = bg(col, radius = size / 2f)
    }, android.widget.FrameLayout.LayoutParams((size * d).toInt(), (size * d).toInt()))
    // green online, red Do not disturb (their invites wait)
    val busy = online && Friends.ready && Friends.core.isBusy(fid)
    if (online) addView(View(context).apply { background = bg(if (busy) Color.rgb(240, 82, 82) else Color.rgb(72, 199, 116), panel, 2f, 7f) },
        android.widget.FrameLayout.LayoutParams((13 * d).toInt(), (13 * d).toInt(), Gravity.END or Gravity.BOTTOM))
}

/** You › tap your circle: pick an avatar and its colour (locked ones say what unlocks them). */
internal fun MainActivity.chooseAvatar() {
    val p = (12 * d).toInt()
    lateinit var dlg: GameDialog
    val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    fun build() {
        box.removeAllViews()
        val mine = Avatars.byId(Avatars.mine)
        val col = if (Avatars.myColor != 0) Avatars.myColor else mine?.color ?: Avatars.colors[1]
        box.addView(label(L.t("Friends see it in their list and in convoys"), 13.5f).apply { setTextColor(dim()); gravity = Gravity.CENTER; setPadding(0, 0, 0, p) })
        var row: LinearLayout? = null
        Avatars.all.forEachIndexed { i, a ->
            if (i % 4 == 0) { row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }; box.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = p }) }
            val open = Avatars.unlocked(a)
            val v = AvatarView(this, a, if (a.id == mine?.id) col else a.color, locked = !open, chosen = a.id == mine?.id).apply {
                contentDescription = L.t(a.name) + if (open) "" else ", " + Avatars.lockText(a)
                setOnClickListener {
                    if (!open) { Toast.makeText(this@chooseAvatar, L.t(a.name) + " · " + Avatars.lockText(a), Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                    Avatars.mine = a.id; youKey = ""; youBtn.invalidate(); build()
                }
            }
            row!!.addView(v, LinearLayout.LayoutParams(0, (66 * d).toInt(), 1f))
        }
        box.addView(label(L.t("BACKGROUND COLOUR"), 12.5f, bold = true).apply { setTextColor(dim()); letterSpacing = 0.1f; setPadding(p / 2, p / 2, 0, p / 2) })
        box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            for (cc in Avatars.colors) addView(View(context).apply {
                background = if (cc == col) bg(cc, Color.WHITE, 3f, 18f) else bg(cc, radius = 18f)
                setOnClickListener { Avatars.myColor = cc; if (Avatars.mine.isBlank()) Avatars.mine = Avatars.all[5].id; youKey = ""; youBtn.invalidate(); build() }
            }, LinearLayout.LayoutParams(0, (36 * d).toInt(), 1f).apply { marginStart = p / 3; marginEnd = p / 3 })
        })
        Avatars.all.firstOrNull { !Avatars.unlocked(it) }?.let { next ->
            box.addView(label("🔒 " + L.t(next.name) + " · " + Avatars.lockText(next), 12.5f).apply { setTextColor(dim()); setPadding(p / 2, p, 0, 0) })
        }
        if (mine != null) box.addView(label(L.t("Use my initial instead"), 14f, bold = true).apply {
            setTextColor(readable(accent)); gravity = Gravity.CENTER; setPadding(0, p, 0, 0)
            setOnClickListener { Avatars.mine = ""; youKey = ""; youBtn.invalidate(); build() }
        })
        L.translateTree(box)
    }
    build()
    dlg = GameBuilder(this).setTitle("Your avatar").setView(box).setPositiveButton("Done") { _, _ -> refreshChrome() }.showT()
}

internal fun MainActivity.friendsBody(): LinearLayout {
    val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    sheetHost.addView(SheetScroll(0.72).apply { addView(body) }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    return body
}

internal fun MainActivity.friendsSection(body: LinearLayout, title: String) =
    body.addView(label(title.uppercase(), 12f, bold = true).apply { setTextColor(dim()); letterSpacing = 0.08f; setPadding((4 * d).toInt(), (16 * d).toInt(), 0, (4 * d).toInt()) })

internal fun MainActivity.shareFriendLink() {
    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, Friends.shareText()) }, L.t("Send your friend link")))
}

/** Friends: my code, invites, requests, who's online, one tap to invite to a convoy. */
internal fun MainActivity.renderFriends() {
    Friends.start()
    val c = Friends.core
    val cv = Convoy.code
    val fr = c.friends.sortedWith(compareBy({ !c.isOnline(it.fid) }, { it.name.lowercase() }))
    val key = "${c.code}|${fr.joinToString { it.fid + it.name + c.isOnline(it.fid) + Friends.activityText(it.fid) + (cv?.let { x -> c.sentTo(it.fid, x)?.status }) }}|" +
        "${c.requests.joinToString { it.fid }}|${c.invites.joinToString { it.convoy + it.count }}|${c.pending.joinToString { it.nonce + it.status }}|$cv|${Convoy.active}|${theme.name}|${MapState.dark}"
    if (key == friendsKey && sheetHost.childCount > 0) return
    friendsKey = key
    sheetHost.removeAllViews()
    sheetHost.background = bg(panel, radius = 22f, topOnly = true)
    togetherHeader("friends")
    val p = (12 * d).toInt()
    val body = friendsBody()
    // my code, to give to someone
    body.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(p, p / 2, p / 2, p / 2); background = bg(dim(22), radius = 14f)
        addView(label("YOUR CODE", 11f, bold = true).apply { setTextColor(dim()); letterSpacing = 0.08f; setPadding(0, 0, p, 0) })
        addView(label(c.code, 20f, bold = true).apply {
            setTextColor(ink); letterSpacing = 0.06f; typeface = Typeface.MONOSPACE; maxLines = 1
            setAutoSizeTextTypeUniformWithConfiguration(13, 20, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(pill("Share") { shareFriendLink() }.apply { textSize = 14f; setTextColor(readable(accent)) })
    }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = p / 2 })
    body.addView(pill("＋  Add a friend") { openSheet("addfriend") }.apply {
        setTextColor(onAccent(accent)); background = bg(accent, radius = 22f)
    }, LinearLayout.LayoutParams(MATCH_PARENT, (50 * d).toInt()).apply { topMargin = p })
    // convoy invites (current ones only; several to the same convoy are one)
    val inv = c.invites
    if (inv.isNotEmpty()) {
        friendsSection(body, "Invites")
        for (i in inv) body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(p, p, p, p); background = bg(dim(22), accent, 1.5f, 14f)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(avatar(i.from, i.fid, c.isOnline(i.fid)), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = p })
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(label(i.from + (if (i.dest.isNotBlank()) " · " + L.t("convoy to ${i.dest}") else " · " + L.t("convoy")), 15f, bold = true).apply { setTextColor(ink) })
                    addView(label((if (i.count > 1) L.t("${i.count} invites") + " · " + L.t("latest") + " " else "") + L.t(since(i.at)), 13f).apply { setTextColor(dim()) })
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; setPadding(0, p, 0, 0)
                addView(pill("Join the convoy") { closeSheet(); joinConvoy(i.convoy) }.apply { textSize = 15f; setTextColor(onAccent(accent)); background = bg(accent, radius = 14f) },
                    LinearLayout.LayoutParams(0, WRAP_CONTENT, 1.3f).apply { marginEnd = p })
                addView(pill("Not now") { Friends.answer(i.convoy, "no") }.apply { textSize = 15f; setTextColor(ink); background = bg(Color.TRANSPARENT, dim(120), 1.5f, 14f) },
                    LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            })
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = p / 2 })
        Friends.markSeen(inv)
    }
    // requests to me
    if (c.requests.isNotEmpty()) {
        friendsSection(body, "Requests")
        for (r in c.requests) body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, p / 2, 0, p / 2)
            addView(avatar(r.name, r.fid, false), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = p })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(r.name, 15f, bold = true).apply { setTextColor(ink) })
                addView(label("wants to add you", 13f).apply { setTextColor(dim()) })
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(pill("Accept") { Friends.accept(r) }.apply { textSize = 14f; setTextColor(onAccent(accent)); background = bg(accent, radius = 12f) })
            addView(pill("✕") { Friends.decline(r) }.apply { textSize = 14f; setTextColor(ink); contentDescription = "Decline" })
        })
    }
    // friends: online first; Invite (or how far my invite to this convoy got)
    if (fr.isEmpty()) body.addView(label("No friends yet. Share your code, or add people you drove with in a convoy.", 14f).apply { setTextColor(dim()); setPadding(0, p, 0, 0) })
    // what Invite does: it starts a convoy (said once, above the list, while there isn't one)
    else if (!Convoy.active) body.addView(label("👥 Inviting a friend starts a convoy. Then you choose where you're going and how you drive.", 13.5f).apply {
        setTextColor(dim()); setPadding(p / 4, p * 2 / 3, p / 4, 0)
    })
    // just started one by inviting: the next step, one tap away
    else if (Convoy.host && Convoy.destination == null && !Convoy.meSharing) body.addView(label("👥 Convoy started: choose where you're going ›", 14f, bold = true).apply {
        setTextColor(readable(accent)); setPadding(p / 4, p * 2 / 3, p / 4, 0)
        setOnClickListener { openSheet("convoy") }
    })
    val on = fr.filter { c.isOnline(it.fid) }; val off = fr - on.toSet()
    for ((title, list) in listOf("Online · ${on.size}" to on, "Offline" to off)) {
        if (list.isEmpty()) continue
        friendsSection(body, title)
        for (f in list) body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, p / 2, 0, p / 2)
            isClickable = true; setOnClickListener { showFriendCard(f) }
            val online = c.isOnline(f.fid)
            addView(avatar(f.name, f.fid, online), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = p })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(c.shownName(f), 15f, bold = true).apply { setTextColor(ink) })
                Friends.levelOf(f.fid).takeIf { it.isNotBlank() }?.let { addView(label(it, 12.5f, bold = true).apply { setTextColor(readable(accent)) }) }
                val busy = online && c.isBusy(f.fid)
                addView(label(Friends.activityText(f.fid), 13f).apply { setTextColor(if (busy) readable(Color.rgb(230, 70, 70)) else if (online) readable(Color.rgb(47, 180, 100)) else dim()) })
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            val st = cv?.let { c.sentTo(f.fid, it)?.status }
            if (st != null) addView(label(when (st) { "seen" -> "✓✓ Seen"; "joined" -> "✓ Joined"; "no" -> "Not now"; else -> "✓ Sent" }, 13.5f, bold = true).apply {
                setTextColor(when (st) { "joined" -> readable(Color.rgb(47, 180, 100)); "seen" -> readable(Color.rgb(77, 140, 230)); else -> dim() }); setPadding(p, 0, p / 2, 0)
                if (st == "no") setOnClickListener { inviteFriend(f) } // ask again
            })
            else addView(pill("👥 Invite") { inviteFriend(f) }.apply { textSize = 14f; setTextColor(readable(accent)); background = bg(Color.TRANSPARENT, accent, 1.5f, 12f) })
        })
    }
    if (c.pending.isNotEmpty()) {
        friendsSection(body, "Requests you sent")
        for (q in c.pending) body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding((4 * d).toInt(), p / 3, 0, p / 3)
            addView(label(q.name.ifBlank { FriendCore.pretty(q.code) }, 14.5f, bold = true).apply { setTextColor(ink) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(label(when (q.status) { "accepted" -> "✓ Accepted"; "declined" -> "Declined"; else -> L.t("Waiting for an answer") + "…" }, 13.5f, bold = q.status != "waiting").apply {
                setTextColor(when (q.status) { "accepted" -> readable(Color.rgb(47, 180, 100)); "declined" -> readable(Color.rgb(230, 80, 80)); else -> dim() })
            })
        })
    }
    body.addView(label("Your location is never shared with friends: only inside a convoy you join.", 12f).apply { setTextColor(dim()); setPadding(0, p, 0, 0) })
}

/** Add a friend: my QR and link, their code, or someone I drove with. */
internal fun MainActivity.renderAddFriend() {
    val c = Friends.core
    val recent = c.recent.filter { !c.isFriend(it.fid) && it.fid !in c.blocked }.reversed()
    val key = "add|${c.code}|${recent.joinToString { it.fid + c.requested(it.code) }}|${theme.name}|${MapState.dark}"
    if (key == friendsKey && sheetHost.childCount > 0) return
    friendsKey = key
    sheetHost.removeAllViews()
    sheetHost.background = bg(panel, radius = 22f, topOnly = true)
    sheetHost.addView(sheetHeader("Add a friend"))
    val p = (12 * d).toInt()
    val body = friendsBody()
    body.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(p, p, p, p); background = bg(dim(22), radius = 14f)
        addView(android.widget.ImageView(context).apply {
            setImageBitmap(qr(Friends.link(), (128 * d).toInt())); contentDescription = L.t("QR code for ${c.code}")
            // (the white border is in the code itself; no scaling, sharp modules)
            background = bg(Color.WHITE, radius = 8f); scaleType = android.widget.ImageView.ScaleType.CENTER
        }, LinearLayout.LayoutParams((128 * d).toInt(), (128 * d).toInt()).apply { marginEnd = p })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(c.code, 19f, bold = true).apply { setTextColor(ink); typeface = Typeface.MONOSPACE; letterSpacing = 0.05f })
            addView(label("Let a friend scan this with their camera, or send them the link.", 13f).apply { setTextColor(dim()); setPadding(0, p / 3, 0, p / 2) })
            addView(pill("Send link") { shareFriendLink() }.apply { textSize = 14f; setTextColor(onAccent(accent)); background = bg(accent, radius = 12f) })
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
    }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = p / 2 })
    friendsSection(body, "Or type their code")
    lateinit var add: TextView
    val input = EditText(this).apply {
        hint = "NIKOS-2290"; setSingleLine(); setTextColor(ink); setHintTextColor(dim(110))
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        textSize = 22f; letterSpacing = 0.1f; typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER
        background = bg(dim(22), dim(90), 1.5f, 14f); val q = (10 * d).toInt(); setPadding(q, q, q, q)
        addTextChangedListener(object : android.text.TextWatcher {
            private var busy = false
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, n: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, n: Int) {}
            override fun afterTextChanged(e: android.text.Editable) {
                if (busy) return
                val raw = e.toString()
                if (!raw.contains("/")) {
                    // letters, then the 4 digits: "nikos2290" shows as "NIKOS-2290"
                    val k = FriendCore.norm(FriendCore.latin(raw)).take(10)
                    val pretty = FriendCore.pretty(k)
                    if (pretty != raw) { busy = true; e.replace(0, e.length, pretty); busy = false }
                }
                val ok = FriendCore.valid(e.toString())
                add.alpha = if (ok) 1f else 0.45f; add.isEnabled = ok
            }
        })
    }
    body.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(input, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = p })
        add = pill("Add") { sendFriendRequest(input.text.toString(), "") { input.setText("") } }.apply {
            setTextColor(onAccent(accent)); background = bg(accent, radius = 14f); alpha = 0.45f; isEnabled = false
        }
        addView(add)
    })
    if (recent.isNotEmpty()) {
        friendsSection(body, "You drove with")
        for (r in recent) body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, p / 2, 0, p / 2)
            addView(avatar(r.name, r.fid, false), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = p })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(r.name, 15f, bold = true).apply { setTextColor(ink) })
                if (r.dest.isNotBlank()) addView(label(L.t("Convoy to ${r.dest}"), 13f).apply { setTextColor(dim()) })
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            if (c.requested(r.code)) addView(label("Request sent", 13.5f).apply { setTextColor(dim()) })
            else addView(pill("Add") { sendFriendRequest(r.code, r.name) {} }.apply { textSize = 14f; setTextColor(readable(accent)); background = bg(Color.TRANSPARENT, accent, 1.5f, 12f) })
        })
    }
    body.addView(label("They get a request and choose Accept or Decline. Nothing is shared until they accept.", 12.5f).apply { setTextColor(dim()); setPadding(0, p, 0, 0) })
}

/** A QR code (black on white, so every camera reads it, in every theme). */
/**
 * A QR code that cameras read: black on white with the 4-module white border scanners need
 * (without it many don't see the code at all), whole pixels per module (sharp edges).
 */
internal fun MainActivity.qr(text: String, size: Int): android.graphics.Bitmap = qrBitmap(text, size)

internal fun MainActivity.sendFriendRequest(code: String, name: String, then: () -> Unit) {
    Friends.start()
    val k = FriendCore.norm(code)
    when {
        k == FriendCore.norm(Friends.core.code) -> Toast.makeText(this, L.t("That's your own code"), Toast.LENGTH_SHORT).show()
        Friends.core.friends.any { FriendCore.norm(it.code) == k } -> Toast.makeText(this, L.t("You're friends already"), Toast.LENGTH_SHORT).show()
        else -> withConvoyName {
            if (Friends.request(k, name)) {
                Toast.makeText(this, L.t("Friend request sent") + (if (name.isNotBlank()) " · $name" else ""), Toast.LENGTH_SHORT).show()
                then()
            } else Toast.makeText(this, L.t("That isn't a friend code"), Toast.LENGTH_SHORT).show()
        }
    }
}

/** gamemap://friend/<code>, from a friend's link or QR. */
internal fun MainActivity.addFriendFromLink(code: String) {
    val k = FriendCore.norm(code)
    if (!FriendCore.valid(k)) { Toast.makeText(this, L.t("That isn't a friend code"), Toast.LENGTH_SHORT).show(); return }
    Friends.start()
    if (k == FriendCore.norm(Friends.core.code)) { Toast.makeText(this, L.t("That's your own code"), Toast.LENGTH_SHORT).show(); return }
    GameBuilder(this@addFriendFromLink).setTitle("Add a friend?")
        .setMessage(L.t("Send a friend request to ${FriendCore.pretty(k)}?") + "\n\n" + L.t("They choose Accept or Decline. Your location is never shared with friends."))
        .setPositiveButton("Send request") { _, _ -> sendFriendRequest(k, "") { openSheet("friends") } }
        .setNegativeButton("Cancel", null).showT()
}

/** Invites a friend to my convoy (starts one first if I'm not in one: going where I'm going). */
internal fun MainActivity.inviteFriend(f: FriendCore.Friend) {
    withConvoyName {
        val started = !Convoy.active
        if (started) {
            val dest = if (NavEngine.active && !NavEngine.arrived) NavEngine.destination?.let { Place(NavEngine.destinationName, "", it) } else NavEngine.planPlace
            Convoy.create(dest)
        }
        Friends.invite(f)
        Toast.makeText(this, if (started) L.t("Convoy started · invite sent to ${f.name}") else L.t("Invite sent to ${f.name}"), Toast.LENGTH_SHORT).show()
        friendsKey = ""; refreshChrome()
    }
}

/** Tap a friend: invite, remove or block (no location: friends only see each other in a convoy). */
internal fun MainActivity.showFriendCard(f: FriendCore.Friend) {
    val p = (16 * d).toInt()
    lateinit var dlg: GameDialog
    val online = Friends.core.isOnline(f.fid)
    val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(p + p / 2, p, p + p / 2, p / 2) }
    box.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(avatar(f.name, f.fid, online, 54), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = p })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(Friends.core.shownName(f), 22f, bold = true).apply { setTextColor(ink); typeface = theme.typeface })
            if (Friends.core.nickname(f.fid).isNotBlank()) addView(label(L.t("Calls themselves ${f.name}"), 12.5f).apply { setTextColor(dim()) })
            Friends.levelOf(f.fid).takeIf { it.isNotBlank() }?.let { addView(label(it, 14f, bold = true).apply { setTextColor(readable(accent)) }) }
            addView(label(if (online) "Online" else "Offline", 14f, bold = online).apply { setTextColor(if (online) readable(Color.rgb(47, 180, 100)) else dim()) })
        })
    })
    val date = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(f.since))
    val drives = Friends.core.drivesWith(f.fid)
    box.addView(label(L.t("Friends since") + " " + date + (if (drives > 0) " · " + L.t(if (drives == 1) "1 drive together" else "$drives drives together") else ""), 13.5f).apply { setTextColor(dim()); setPadding(0, p, 0, p) })
    box.addView(pill(if (Convoy.active) "👥 Invite to your convoy" else "👥 Start a convoy and invite") { dlg.dismiss(); inviteFriend(f) }.apply { setTextColor(onAccent(accent)); background = bg(accent, radius = 16f) },
        LinearLayout.LayoutParams(MATCH_PARENT, (52 * d).toInt()))
    box.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; setPadding(0, p * 3 / 4, 0, 0)
        addView(pill("Rename") {
            dlg.dismiss()
            val input = EditText(this@showFriendCard).apply { setText(Friends.core.shownName(f)); setSingleLine(); selectAll() }
            GameBuilder(this@showFriendCard).setTitle(L.t("Your name for ${f.name}")).setMessage(L.t("Only you see it."))
                .setView(LinearLayout(this@showFriendCard).apply { val q = (20 * d).toInt(); setPadding(q, 0, q, 0); addView(input, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)) })
                .setPositiveButton("Save") { _, _ -> Friends.core.setNickname(f.fid, input.text.toString().takeIf { it != f.name }.orEmpty()); friendsKey = ""; youKey = ""; refreshChrome() }
                .setNegativeButton("Cancel", null).showT()
        }.apply { textSize = 14.5f; setTextColor(readable(accent)); background = bg(Color.TRANSPARENT, accent, 1.5f, 14f) },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = p / 2 })
        addView(pill("Remove") {
            dlg.dismiss()
            GameBuilder(this@showFriendCard).setTitle(L.t("Remove ${f.name}?")).setMessage("You can add each other again later.")
                .setPositiveButton("Remove") { _, _ -> Friends.remove(f) }.setNegativeButton("Cancel", null).showT()
        }.apply { textSize = 14.5f; setTextColor(ink); background = bg(Color.TRANSPARENT, dim(120), 1.5f, 14f) },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = p / 2 })
        addView(pill("Block") {
            dlg.dismiss()
            GameBuilder(this@showFriendCard).setTitle(L.t("Block ${f.name}?")).setMessage("They're removed, and their requests and invites won't reach you. You can unblock them in Settings › Sharing.")
                .setPositiveButton("Block") { _, _ -> Friends.block(f.fid, f.name) }.setNegativeButton("Cancel", null).showT()
        }.apply { textSize = 14.5f; setTextColor(readable(Color.rgb(230, 80, 80))); background = bg(Color.TRANSPARENT, Color.rgb(160, 70, 70), 1.5f, 14f) },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
    })
    dlg = GameBuilder(this@showFriendCard).setView(box).showT()
}

/** A friend invited me to a convoy: ask (not while driving: then the chip on the map shows it). */
internal fun MainActivity.maybeAskInvite() {
    if (!Friends.ready || !Friends.core.allowInvites || isFinishing) return
    if (Convoy.proposal != null) return
    val i = Friends.core.invites.firstOrNull { it.convoy != Convoy.code } ?: return
    val k = i.convoy + i.count
    if (k == inviteAsked) return
    inviteAsked = k
    Friends.cancelNotification(i.convoy)
    // the small card (out from under Where to?, or the turn box while driving)
    InvitePop.offer(i)
    Friends.markSeen(listOf(i))
}
