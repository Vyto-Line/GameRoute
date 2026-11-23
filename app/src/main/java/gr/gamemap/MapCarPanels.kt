@file:Suppress("unused")

package gr.gamemap
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import android.content.Context
import gr.gamemap.MapRenderer.Camera
import gr.gamemap.MapRenderer.ArrowLine
import gr.gamemap.MapRenderer.TrafficPaths
import gr.gamemap.MapRenderer.CachedPath
import gr.gamemap.MapRenderer.EllKey
import gr.gamemap.MapRenderer.Companion.trafficWorker
import gr.gamemap.MapRenderer.Companion.NAV_PLACES
import gr.gamemap.MapRenderer.Companion.profile
import gr.gamemap.MapRenderer.Companion.allocProbe
import gr.gamemap.MapRenderer.Companion.allocs
import gr.gamemap.MapRenderer.Companion.times

// Part of MapRenderer, moved out in the clean-up of 23 Nov 2026 (see tools/split_main.py).

internal fun MapRenderer.drawCarFlash(c: Canvas, a: Rect, d: Float, t: Theme, msg: String) {
    text.textAlign = Paint.Align.CENTER; text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 19 * d
    val w = text.measureText(msg) + 48 * d
    val r = RectF(a.centerX() - w / 2, a.bottom - 140 * d, a.centerX() + w / 2, a.bottom - 88 * d)
    panel(c, r, d, t, outline = true)
    text.color = Ui.ink(t)
    c.drawText(msg, r.centerX(), r.centerY() + 7 * d, text)
    text.textAlign = Paint.Align.LEFT
}

internal fun MapRenderer.drawCarPanel(c: Canvas, a: Rect, d: Float, t: Theme, p: CarPanel) {
    val ink = Ui.ink(t); val dimInk = withAlpha(ink, 170); val accentInk = Ui.onPanel(t, t.route, 3.0)
    val onRoute = if (Ui.contrast(Color.WHITE, t.route) >= 3) Color.WHITE else Color.BLACK
    fun button(rr: RectF, label: String, id: String, fillCol: Int? = null, inkCol: Int = ink, size: Float = 16f) {
        glassButton(c, rr, d, t, fillCol)
        text.textAlign = Paint.Align.CENTER; text.typeface = Typeface.DEFAULT_BOLD; text.textSize = size * d; text.color = inkCol
        c.drawText(ellipsize(label, rr.width() - 16 * d), rr.centerX(), rr.centerY() + size * 0.37f * d, text)
        carHits += rr to id
    }
    if (p.dialog) {
        // a question over the dimmed map
        fill.color = Color.argb(150, 8, 4, 16); c.drawRect(a, fill)
        val w = minOf(620 * d, a.width() - 40 * d); val h = 290 * d
        val r = RectF(a.centerX() - w / 2, a.centerY() - h / 2, a.centerX() + w / 2, a.centerY() + h / 2)
        panel(c, r, d, t, outline = true)
        carHits += RectF(a) to "cp:none" // taps beside it do nothing (it asks first)
        text.textAlign = Paint.Align.CENTER; text.typeface = t.typeface; text.textSize = 26 * d; text.color = ink
        c.drawText(ellipsize(p.title, w - 40 * d), r.centerX(), r.top + 56 * d, text)
        text.typeface = Typeface.DEFAULT; text.textSize = 17 * d; text.color = dimInk
        var ly = r.top + 98 * d
        for (line in wrap(p.message, w - 60 * d).take(3)) { c.drawText(line, r.centerX(), ly, text); ly += 24 * d }
        val bw = (w - 3 * 20 * d) / 2; val by = r.bottom - 24 * d - 64 * d
        p.rows.take(2).forEachIndexed { i, b ->
            val rr = RectF(r.left + 20 * d + i * (bw + 20 * d), by, r.left + 20 * d + i * (bw + 20 * d) + bw, by + 64 * d)
            if (b.red) button(rr, b.title, "cp:" + b.id, Color.rgb(208, 2, 27), Color.WHITE, 19f) else button(rr, b.title, "cp:" + b.id, null, ink, 19f)
        }
        text.textAlign = Paint.Align.LEFT
        return
    }
    if (p.columns != null) { drawDrawer(c, a, d, t, p, ink, dimInk, accentInk); return }
    val w = minOf(if (p.tiles.isNotEmpty()) 600 * d else 560 * d, a.width() * 0.55f)
    val top = maxOf(bannerBottom, a.top.toFloat()) + 10 * d
    val r = RectF(a.left + 12 * d, top, a.left + 12 * d + w, a.bottom - 12 * d)
    panel(c, r, d, t, outline = true)
    val pad = 14 * d
    val back = RectF(r.left + pad, r.top + pad, r.left + pad + 48 * d, r.top + pad + 48 * d)
    glassButton(c, back, d, t)
    text.textAlign = Paint.Align.CENTER; text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 26 * d; text.color = ink
    c.drawText("‹", back.centerX(), back.centerY() + 9 * d, text)
    carHits += back to "cp:back"
    var titleRight = r.right - pad
    p.head?.let { h ->
        text.textSize = 16 * d; text.typeface = Typeface.DEFAULT_BOLD
        val hw = text.measureText(h.title) + 36 * d
        val hb = RectF(r.right - pad - hw, back.top, r.right - pad, back.bottom)
        button(hb, h.title, "cp:" + h.id, t.route, onRoute)
        titleRight = hb.left - 10 * d
    }
    text.textAlign = Paint.Align.LEFT; text.typeface = t.typeface; text.textSize = 22 * d; text.color = ink
    c.drawText(ellipsize(p.title, titleRight - back.right - 14 * d), back.right + 12 * d, back.centerY() + 8 * d, text)
    var y = back.bottom + 12 * d
    val bottom = r.bottom - pad - (if (p.note != null) 24 * d else 0f)
    if (p.loading || (p.rows.isEmpty() && p.tiles.isEmpty())) {
        text.typeface = Typeface.DEFAULT; text.textSize = 17 * d; text.color = dimInk
        c.drawText(if (p.loading) gr.gamemap.L.t("Looking…") else p.empty, r.left + pad + 4 * d, y + 30 * d, text)
        if (p.loading) animating = true
    } else if (p.tiles.isNotEmpty()) {
        // big tiles: 2 across (4 or fewer) or 3 across
        val cols = if (p.tiles.size <= 4) 2 else 3
        val rowsN = (p.tiles.size + cols - 1) / cols
        val gap = 10 * d
        val tw = (r.width() - 2 * pad - (cols - 1) * gap) / cols
        val th = ((bottom - y - (rowsN - 1) * gap) / rowsN).coerceAtMost(130 * d)
        p.tiles.forEachIndexed { i, tile ->
            val x0 = r.left + pad + (i % cols) * (tw + gap); val y0 = y + (i / cols) * (th + gap)
            val rr = RectF(x0, y0, x0 + tw, y0 + th)
            fill.color = withAlpha(ink, 22); c.drawRoundRect(rr, 10 * d, 10 * d, fill)
            if (tile.highlight) { stroke.color = t.route; stroke.strokeWidth = 2 * d; c.drawRoundRect(rr, 10 * d, 10 * d, stroke) }
            text.textAlign = Paint.Align.CENTER; text.typeface = Typeface.DEFAULT; text.textSize = minOf(36 * d, th * 0.32f); text.color = ink
            c.drawText(tile.icon, rr.centerX(), rr.centerY() - 2 * d, text)
            text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 17 * d
            c.drawText(ellipsize(tile.title, tw - 12 * d), rr.centerX(), rr.centerY() + 26 * d, text)
            carHits += rr to "cp:" + tile.id
        }
    } else {
        val rowH = 62 * d
        val per = maxOf(1, ((bottom - y) / rowH).toInt())
        val pages = (p.rows.size + per - 1) / per
        val page = carPanelPage.coerceIn(0, maxOf(0, pages - 1))
        // more than fit: the last row's place shows ▼ / ▲ to page through
        val many = pages > 1
        val shown = if (!many) p.rows else p.rows.drop(page * (per - 1)).take(per - 1)
        for (row in shown) {
            val rr = RectF(r.left + pad, y, r.right - pad, y + rowH - 8 * d)
            fill.color = withAlpha(ink, 22); c.drawRoundRect(rr, 10 * d, 10 * d, fill)
            if (row.moon) { fill.color = withAlpha(PoiLayer.MOON, 40); c.drawRoundRect(rr, 10 * d, 10 * d, fill) }
            if (row.highlight || row.moon) { stroke.color = if (row.moon) PoiLayer.MOON else t.route; stroke.strokeWidth = 2 * d; c.drawRoundRect(rr, 10 * d, 10 * d, stroke) }
            carHits += rr to "cp:" + row.id
            text.textAlign = Paint.Align.CENTER; text.typeface = Typeface.DEFAULT; text.textSize = 22 * d; text.color = ink
            c.drawText(row.icon, rr.left + 26 * d, rr.centerY() + 8 * d, text)
            text.textSize = 17 * d; text.typeface = Typeface.DEFAULT_BOLD
            val rightText = row.right ?: "›"
            text.textAlign = Paint.Align.RIGHT; text.color = if (row.right != null) accentInk else dimInk
            text.textSize = if (row.right != null) 17 * d else 22 * d
            c.drawText(rightText, rr.right - 14 * d, rr.centerY() + 7 * d, text)
            val rw = text.measureText(rightText) + 24 * d
            val tx = rr.left + 52 * d; val tw = rr.right - tx - rw
            text.textAlign = Paint.Align.LEFT; text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 17 * d; text.color = if (row.red) Color.rgb(255, 99, 99) else if (row.faded) dimInk else ink
            c.drawText(ellipsize(row.title, tw), tx, if (row.sub != null) rr.centerY() - 2 * d else rr.centerY() + 6 * d, text)
            row.sub?.let { s ->
                text.typeface = if (row.moon) Typeface.DEFAULT_BOLD else Typeface.DEFAULT; text.textSize = 14 * d
                text.color = if (row.moon) (if (Ui.contrast(PoiLayer.MOON, Ui.panel(t)) >= 3) PoiLayer.MOON else accentInk) else dimInk
                c.drawText(ellipsize(s, tw), tx, rr.centerY() + 17 * d, text)
            }
            y += rowH
        }
        if (many) {
            val half = (r.width() - 2 * pad - 10 * d) / 2
            val up = RectF(r.left + pad, y, r.left + pad + half, y + rowH - 8 * d)
            val down = RectF(up.right + 10 * d, y, r.right - pad, y + rowH - 8 * d)
            button(up, "▲", "cp:page-", null, if (page > 0) ink else withAlpha(ink, 70), 20f)
            button(down, "▼  ${page + 1}/$pages", "cp:page+", null, if (page < pages - 1) ink else withAlpha(ink, 70), 18f)
        }
    }
    p.note?.let { n ->
        text.textAlign = Paint.Align.LEFT; text.typeface = Typeface.DEFAULT; text.textSize = 14 * d; text.color = dimInk
        c.drawText(ellipsize(n, r.width() - 2 * pad), r.left + pad, r.bottom - pad - 4 * d, text)
    }
    text.textAlign = Paint.Align.LEFT
}

/** Car: location is off (or not allowed) on the phone: where the invite card goes, until it's back. */
internal fun MapRenderer.drawCarLocationOff(c: Canvas, a: Rect, d: Float, t: Theme) {
    val leftEdge = maxOf(bannerRight, if (!NavEngine.active) a.left + 14 * d + 300 * d else a.left.toFloat()) + 14 * d
    val rightEdge = a.right - 84 * d
    val room = rightEdge - leftEdge
    val wide = room >= 440 * d
    val w = if (wide) minOf(620 * d, room) else minOf(620 * d, a.width() - 110 * d)
    val x0 = if (wide) leftEdge + (room - w) / 2 else a.left + 14 * d
    val y0 = if (wide) a.top + 12 * d else maxOf(bannerBottom, a.top + 12 * d + 112 * d) + 10 * d
    val r = RectF(x0, y0, x0 + w, y0 + 78 * d)
    panel(c, r, d, t, outline = true, outlineColor = Color.rgb(255, 107, 107))
    // a location mark, crossed out
    val cx = r.left + 36 * d; val cy = r.centerY()
    stroke.color = Color.rgb(255, 107, 107); stroke.strokeWidth = 3 * d
    c.drawCircle(cx, cy, 11 * d, stroke)
    c.drawLine(cx - 17 * d, cy - 17 * d, cx + 17 * d, cy + 17 * d, stroke)
    val off = LocationStatus.state == LocationStatus.State.OFF
    text.textAlign = Paint.Align.LEFT; text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 19 * d; text.color = Ui.ink(t)
    val tx = r.left + 66 * d; val tw = r.right - tx - 14 * d
    c.drawText(ellipsize(gr.gamemap.L.t(if (off) "Location is off on your phone" else "GameRoute can't use your location"), tw), tx, r.top + 33 * d, text)
    text.typeface = Typeface.DEFAULT; text.textSize = 15 * d; text.color = withAlpha(Ui.ink(t), 175)
    c.drawText(ellipsize(gr.gamemap.L.t(if (off) "Turn it on on the phone (when you're parked) to be guided" else "Allow it on the phone (when you're parked) to be guided"), tw), tx, r.top + 58 * d, text)
    text.typeface = t.typeface
}

internal fun MapRenderer.drawCarInvite(c: Canvas, a: Rect, d: Float, t: Theme, i: FriendCore.Invite) {
    // between what's top left (the turn box, or Where to?) and the buttons on the right; if
    // that gap is too narrow, under them, across the map (it was squeezed: the name was cut to
    // two letters and Not now drew over it)
    val leftEdge = maxOf(bannerRight, if (!NavEngine.active) a.left + 14 * d + 300 * d else a.left.toFloat()) + 14 * d
    val rightEdge = a.right - 84 * d
    val room = rightEdge - leftEdge
    val wide = room >= 440 * d
    val w = if (wide) minOf(600 * d, room) else minOf(600 * d, a.width() - 110 * d)
    val x0 = if (wide) leftEdge + (room - w) / 2 else a.left + 14 * d
    val y0 = if (wide) a.top + 12 * d else maxOf(bannerBottom, a.top + 12 * d + 112 * d) + 10 * d
    // slides down from above, and back up at the end
    val since = System.currentTimeMillis() - InvitePop.shownAt
    val left = InvitePop.left()
    val slide = minOf(1f, since / 260f, (left * InvitePop.SHOW_MS) / 220f).coerceIn(0f, 1f)
    // too narrow for the name beside the buttons (the name was cut to "Ni…", 22 Nov): two rows,
    // who and where on top, Not now / Join underneath
    val twoRows = w < 520 * d
    val h = if (twoRows) 136 * d else 76 * d
    val top = y0 - (1f - slide) * (y0 + h - a.top)
    val r = RectF(x0, top, x0 + w, top + h)
    inviteAt.set(r)
    panel(c, r, d, t, outline = true)
    // their avatar, or their initial
    val cx = r.left + 14 * d + 24 * d; val cy = if (twoRows) r.top + 38 * d else r.centerY()
    val av = try { Avatars.ofFriend(i.fid) } catch (_: Exception) { null }
    if (av != null && av.first.id.isNotBlank()) Avatars.draw(c, av.first, av.second, cx, cy, 24 * d)
    else {
        fill.color = Color.rgb(77, 171, 247); c.drawCircle(cx, cy, 24 * d, fill)
        text.textAlign = Paint.Align.CENTER; text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 20 * d; text.color = Color.WHITE
        c.drawText(i.from.trim().take(1).uppercase().ifBlank { "?" }, cx, cy + 7 * d, text)
    }
    val join: RectF; val no: RectF
    if (twoRows) {
        val bw = (r.width() - 34 * d) / 2
        no = RectF(r.left + 12 * d, r.bottom - 64 * d, r.left + 12 * d + bw, r.bottom - 12 * d)
        join = RectF(no.right + 10 * d, no.top, r.right - 12 * d, no.bottom)
    } else {
        join = RectF(r.right - 112 * d, r.top + 12 * d, r.right - 12 * d, r.bottom - 12 * d)
        no = RectF(join.left - 118 * d, join.top, join.left - 10 * d, join.bottom)
    }
    text.textAlign = Paint.Align.LEFT; text.typeface = Typeface.DEFAULT_BOLD
    val tx = cx + 36 * d; val tw = (if (twoRows) r.right - 14 * d else no.left - 10 * d) - tx
    text.textSize = 19 * d; text.color = Ui.ink(t)
    c.drawText(ellipsize(gr.gamemap.L.t("${i.from} invites you"), tw), tx, if (twoRows) cy - 5 * d else r.top + 33 * d, text)
    text.textSize = 15 * d; text.color = withAlpha(Ui.ink(t), 170); text.typeface = t.typeface
    c.drawText(ellipsize("🏁 " + (if (i.dest.isNotBlank()) gr.gamemap.L.t("Convoy to ${i.dest}") else gr.gamemap.L.t("A convoy")), tw), tx, if (twoRows) cy + 19 * d else r.top + 57 * d, text)
    // Not now / Join
    fill.color = withAlpha(Ui.ink(t), 40); c.drawRoundRect(no, 12 * d, 12 * d, fill)
    text.textAlign = Paint.Align.CENTER; text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 17 * d; text.color = Ui.ink(t)
    c.drawText(gr.gamemap.L.t("Not now"), no.centerX(), no.centerY() + 6 * d, text)
    fill.color = t.route; c.drawRoundRect(join, 12 * d, 12 * d, fill)
    text.color = if (Ui.contrast(Color.WHITE, t.route) >= 3) Color.WHITE else Color.BLACK
    c.drawText(gr.gamemap.L.t("Join"), join.centerX(), join.centerY() + 6 * d, text)
    // the 10 s running down
    fill.color = t.route
    c.drawRect(r.left + 10 * d, r.bottom - 4 * d, r.left + 10 * d + (r.width() - 20 * d) * left, r.bottom - 1.5f * d, fill)
    text.textAlign = Paint.Align.LEFT; text.typeface = t.typeface
    carHits += no to "inv:no"; carHits += join to "inv:join"
}

internal fun MapRenderer.drawCarProposal(c: Canvas, a: Rect, d: Float, t: Theme, p: Convoy.Proposal) {
    val w = minOf(440 * d, a.width() - 40 * d)
    val x = a.centerX() - w / 2
    val y = maxOf(bannerBottom, a.top.toFloat()) + 10 * d
    val r = RectF(x, y, x + w, y + 64 * d)
    panel(c, r, d, t, outline = true)
    text.textAlign = Paint.Align.LEFT; text.typeface = Typeface.DEFAULT_BOLD
    text.textSize = 12 * d; text.color = Ui.onPanel(t, t.route, 3.0)
    c.drawText(ellipsize(gr.gamemap.L.t(if (p.place == null) "${p.from.uppercase()} REMOVED THE DESTINATION" else "${p.from.uppercase()} CHOSE WHERE THE CONVOY GOES"), w - 170 * d), r.left + 14 * d, r.top + 22 * d, text)
    text.textSize = 17 * d; text.color = Ui.ink(t)
    c.drawText(ellipsize(p.place?.name ?: gr.gamemap.L.t("Just share where we all are"), w - 170 * d), r.left + 14 * d, r.top + 47 * d, text)
    val go = RectF(r.right - 150 * d, r.top + 10 * d, r.right - 62 * d, r.bottom - 10 * d)
    fill.color = t.route; c.drawRoundRect(go, 12 * d, 12 * d, fill)
    text.textAlign = Paint.Align.CENTER; text.textSize = 17 * d
    text.color = if (Ui.contrast(Color.WHITE, t.route) >= 3) Color.WHITE else Color.BLACK
    c.drawText(gr.gamemap.L.t(if (p.place == null) "OK" else "Go"), go.centerX(), go.centerY() + 6 * d, text)
    val no = RectF(r.right - 54 * d, r.top + 10 * d, r.right - 10 * d, r.bottom - 10 * d)
    fill.color = withAlpha(Ui.ink(t), 40); c.drawRoundRect(no, 12 * d, 12 * d, fill)
    text.color = Ui.ink(t); c.drawText("✕", no.centerX(), no.centerY() + 6 * d, text)
    text.textAlign = Paint.Align.LEFT; text.typeface = t.typeface
    carHits += go to "convoy:accept"; carHits += no to "convoy:decline"
    bannerBottom = r.bottom; bannerRight = r.right
}

/**
 * Car: the convoy lobby on the map. Where to, who's in (ready ✓ / joining / not ready), + Invite
 * (the car's Friends list) and the big button: START for the host (or anyone alone), READY UP
 * for the others; once ready, it shows who we're waiting for (tap to take it back).
 */
internal fun MapRenderer.drawCarLobby(c: Canvas, a: Rect, d: Float, t: Theme) {
    val dest = Convoy.destination ?: return
    val lobby = Convoy.lobby()
    val others = Convoy.members.values.filter { it.state != "left" && !it.sharing }.sortedBy { it.name.lowercase() }.take(4)
    val w = minOf(420 * d, a.width() * 0.42f)
    val rowH = 30 * d
    val r = RectF(a.left + 12 * d, maxOf(bannerBottom, a.top.toFloat()) + 10 * d, a.left + 12 * d + w, 0f)
    r.bottom = r.top + 64 * d + (others.size + 1) * rowH + 66 * d
    panel(c, r, d, t, outline = true)
    val ink = Ui.ink(t); val accentInk = Ui.onPanel(t, t.route, 3.0)
    text.textAlign = Paint.Align.LEFT; text.typeface = Typeface.DEFAULT_BOLD
    // the steps (3 · Who lit), then where and how with Change (back to step 2)
    text.textSize = 12 * d; text.color = accentInk
    c.drawText("1 · " + gr.gamemap.L.t("WHERE") + "   2 · " + gr.gamemap.L.t("HOW") + "   3 · " + gr.gamemap.L.t("WHO") + "  ·  " + (Convoy.pretty() ?: ""), r.left + 16 * d, r.top + 24 * d, text)
    val racing = !Convoy.meAlong
    text.textSize = 18 * d; text.color = ink
    val change = gr.gamemap.L.t("Change")
    text.textSize = 15 * d; val cw = text.measureText(change)
    text.textSize = 18 * d
    c.drawText(ellipsize((if (racing) "🏁 " + gr.gamemap.L.t("Race to ${dest.name}") else "🚗 " + gr.gamemap.L.t("Together to ${dest.name}")), w - 48 * d - cw), r.left + 16 * d, r.top + 50 * d, text)
    if (!Convoy.counting) {
        text.textAlign = Paint.Align.RIGHT; text.textSize = 15 * d; text.color = accentInk
        c.drawText(change, r.right - 16 * d, r.top + 50 * d, text)
        carHits += RectF(r.right - 28 * d - cw, r.top + 28 * d, r.right - 4 * d, r.top + 62 * d) to "convoy:how"
        text.textAlign = Paint.Align.LEFT
    }
    val ok = Color.rgb(64, 200, 120); val wait = Color.rgb(255, 176, 60)
    fun row(i: Int, name: String, state: String, col: Int, mode: String = "") {
        val y = r.top + 64 * d + i * rowH + rowH / 2
        fill.color = col; c.drawCircle(r.left + 22 * d, y, 6 * d, fill)
        text.textAlign = Paint.Align.LEFT; text.textSize = 16 * d; text.color = ink
        c.drawText(ellipsize(name, w * 0.34f), r.left + 38 * d, y + 5.5f * d, text)
        // how each one drives
        if (mode.isNotEmpty()) { text.textSize = 13.5f * d; text.color = Ui.dim(t, 200); c.drawText(gr.gamemap.L.t(mode), r.left + 38 * d + w * 0.36f, y + 5 * d, text) }
        text.textAlign = Paint.Align.RIGHT; text.textSize = 14 * d; text.color = col
        c.drawText(gr.gamemap.L.t(state), r.right - 16 * d, y + 5 * d, text)
    }
    val meState = if (Convoy.meReady) "READY ✓" else if (Convoy.host) "host" else "not ready"
    row(0, gr.gamemap.L.t(if (Convoy.host) "You (host)" else "You"), meState, if (Convoy.meReady) ok else wait, if (Convoy.meAlong) "🚗 Together" else "🏁 Racing")
    others.forEachIndexed { i, m ->
        val st = when { m.stale -> "no signal"; m.ready -> "READY ✓"; m.state == "ready" && m in lobby -> "not ready"; m.state == "driving" -> "driving"; else -> "joining…" }
        row(i + 1, m.name, st, if (m.ready) ok else if (m.stale) Color.GRAY else wait, if (m.along) "🚗 Together" else "🏁 Racing")
    }
    // buttons
    val by = r.bottom - 58 * d
    val inv = RectF(r.left + 12 * d, by, r.left + 12 * d + (w - 36 * d) * 0.4f, by + 46 * d)
    stroke.color = t.route; stroke.strokeWidth = 1.6f * d; c.drawRoundRect(inv, 12 * d, 12 * d, stroke)
    text.textAlign = Paint.Align.CENTER; text.textSize = 16 * d; text.color = accentInk
    c.drawText("+ " + gr.gamemap.L.t("Invite"), inv.centerX(), inv.centerY() + 6 * d, text)
    carHits += inv to "friends"
    val go = RectF(inv.right + 12 * d, by, r.right - 12 * d, by + 46 * d)
    val waiting = lobby.filter { !it.ready }
    val label = when {
        lobby.isEmpty() -> "START"
        Convoy.meReady -> if (waiting.isEmpty()) "✓ Everyone's ready" else "✓ Waiting for ${waiting.size}"
        Convoy.host -> "START"
        else -> "READY UP"
    }
    val done = label.startsWith("✓")
    fill.color = if (done) withAlpha(ok, 60) else t.route; c.drawRoundRect(go, 12 * d, 12 * d, fill)
    text.textSize = if (done) 15 * d else 18 * d
    text.color = if (done) ink else if (Ui.contrast(Color.WHITE, t.route) >= 3) Color.WHITE else Color.BLACK
    c.drawText(ellipsize(gr.gamemap.L.t(label), go.width() - 16 * d), go.centerX(), go.centerY() + 6 * d, text)
    carHits += go to "convoy:go"
    text.textAlign = Paint.Align.LEFT; text.typeface = t.typeface
    bannerBottom = r.bottom; bannerRight = r.right
}

internal fun MapRenderer.drawCarFriends(c: Canvas, a: Rect, d: Float, t: Theme) {
    val F = Friends; val C = Convoy; val L = gr.gamemap.L
    val ink = Ui.ink(t); val dimInk = withAlpha(ink, 170); val accentInk = Ui.onPanel(t, t.route, 3.0)
    val w = minOf(560 * d, a.width() * 0.5f)
    val top = maxOf(bannerBottom, a.top.toFloat()) + 10 * d
    val r = RectF(a.left + 12 * d, top, a.left + 12 * d + w, a.bottom - 12 * d)
    panel(c, r, d, t, outline = true)
    val pad = 14 * d
    text.textAlign = Paint.Align.LEFT
    // header: ‹, the title, one button on the right
    val back = RectF(r.left + pad, r.top + pad, r.left + pad + 48 * d, r.top + pad + 48 * d)
    glassButton(c, back, d, t)
    text.textAlign = Paint.Align.CENTER; text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 26 * d; text.color = ink
    c.drawText("‹", back.centerX(), back.centerY() + 9 * d, text)
    carHits += back to "fp:back"
    fun button(rr: RectF, label: String, id: String, fillCol: Int? = null, inkCol: Int = ink) {
        glassButton(c, rr, d, t, fillCol)
        text.textAlign = Paint.Align.CENTER; text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 16 * d; text.color = inkCol
        c.drawText(ellipsize(label, rr.width() - 16 * d), rr.centerX(), rr.centerY() + 6 * d, text)
        carHits += rr to id
    }
    val onRoute = if (Ui.contrast(Color.WHITE, t.route) >= 3) Color.WHITE else Color.BLACK
    val convoyView = carFriends == "convoy" && C.active
    val title = if (convoyView) L.t("Convoy") + (C.pretty()?.let { " · $it" } ?: "") else L.t("Friends")
    val headBtn = when {
        convoyView -> L.t("Message") to "fp:msg"
        C.active -> L.t("Convoy") + " ›" to "fp:convoy"
        else -> L.t("Start a convoy") to "fp:start"
    }
    text.textSize = 16 * d; text.typeface = Typeface.DEFAULT_BOLD
    val hbW = text.measureText(headBtn.first) + 36 * d
    val hb = RectF(r.right - pad - hbW, back.top, r.right - pad, back.bottom)
    button(hb, headBtn.first, headBtn.second, t.route, onRoute)
    text.textAlign = Paint.Align.LEFT; text.typeface = t.typeface; text.textSize = 22 * d; text.color = ink
    c.drawText(ellipsize(title, hb.left - back.right - 24 * d), back.right + 12 * d, back.centerY() + 8 * d, text)
    var y = back.bottom + 12 * d
    val rowH = 60 * d
    fun section(label: String) {
        text.textAlign = Paint.Align.LEFT; text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 12.5f * d; text.color = dimInk
        text.letterSpacing = 0.1f
        c.drawText(label.uppercase(), r.left + pad + 2 * d, y + 14 * d, text)
        text.letterSpacing = 0f
        y += 22 * d
    }
    fun row(name: String, sub: String, seed: String, btn: Pair<String, String>?, rowId: String?, btnFill: Int? = null, btnInk: Int = ink) {
        val rr = RectF(r.left + pad, y, r.right - pad, y + rowH - 8 * d)
        fill.color = withAlpha(ink, 22); c.drawRoundRect(rr, 10 * d, 10 * d, fill)
        if (rowId != null) carHits += rr to rowId
        val cx = rr.left + 30 * d; val cy = rr.centerY()
        val av = Avatars.ofFriend(seed)
        if (av != null) Avatars.draw(c, av.first, av.second, cx, cy, 19 * d)
        else {
            fill.color = Color.HSVToColor(floatArrayOf((seed.hashCode() and 0xffff) % 360f, 0.55f, 0.78f))
            c.drawCircle(cx, cy, 19 * d, fill)
            text.textAlign = Paint.Align.CENTER; text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 17 * d; text.color = Color.WHITE
            c.drawText(name.take(1).uppercase(), cx, cy + 6 * d, text)
        }
        var bw = 0f
        btn?.let { (label, id) ->
            text.textSize = 16 * d
            bw = text.measureText(label) + 30 * d
            button(RectF(rr.right - 8 * d - bw, rr.top + 6 * d, rr.right - 8 * d, rr.bottom - 6 * d), label, id, btnFill, btnInk)
        }
        val tx = cx + 30 * d; val tw = rr.right - tx - bw - 18 * d
        text.textAlign = Paint.Align.LEFT; text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 17 * d; text.color = ink
        c.drawText(ellipsize(name, tw), tx, cy - 2 * d, text)
        text.typeface = Typeface.DEFAULT; text.textSize = 14 * d; text.color = dimInk
        c.drawText(ellipsize(sub, tw), tx, cy + 17 * d, text)
        y += rowH
    }
    val room = { r.bottom - pad - y }
    if (convoyView) {
        // the two big ones: drive there, leave
        val dest = C.destination
        val bh = 50 * d
        val leaveW = (r.width() - 2 * pad) * 0.36f
        if (dest != null && !NavEngine.active) {
            button(RectF(r.left + pad, y, r.right - pad - leaveW - 10 * d, y + bh), "▶ " + L.t("Drive to ${dest.name}"), "fp:drive", t.route, onRoute)
            button(RectF(r.right - pad - leaveW, y, r.right - pad, y + bh), L.t("Leave"), "fp:leave", Color.rgb(229, 72, 77), Color.WHITE)
        } else button(RectF(r.left + pad, y, r.right - pad, y + bh), L.t("Leave the convoy"), "fp:leave", Color.rgb(229, 72, 77), Color.WHITE)
        y += bh + 12 * d
        val members = C.members.values.filter { it.state != "left" && !it.sharing }.sortedBy { it.name.lowercase() }
        section(L.t("In your convoy"))
        if (members.isEmpty()) {
            text.textAlign = Paint.Align.LEFT; text.typeface = Typeface.DEFAULT; text.textSize = 15 * d; text.color = dimInk
            c.drawText(L.t("Nobody else yet: invite friends from Friends"), r.left + pad + 2 * d, y + 18 * d, text)
        }
        val fit = (room() / rowH).toInt().coerceAtLeast(0)
        members.take(fit).forEach { m ->
            val st = when { m.stale -> L.t("no signal"); m.lostSecs > 0 -> L.t(C.lostText(m.lostSecs)); m.state == "arrived" -> L.t("arrived"); m.state == "ready" -> L.t(if (m.ready) "ready ✓" else "joined"); else -> L.t("${m.kmh} km/h") }
            row(m.name, st + " · " + L.t("tap to see on the map"), m.id, null, "fp:member:${m.id}")
        }
        text.textAlign = Paint.Align.LEFT; text.typeface = t.typeface
        return
    }
    if (!F.ready || !F.core.started) {
        text.textAlign = Paint.Align.LEFT; text.typeface = Typeface.DEFAULT; text.textSize = 15 * d; text.color = dimInk
        c.drawText(L.t("Add friends on your phone: You › Friends"), r.left + pad, y + 20 * d, text)
        text.typeface = t.typeface
        return
    }
    val core = F.core
    // invites to me, then online friends, then the rest; as many as fit, the rest behind "+N more"
    val inv = core.invites.filter { it.convoy != C.code }
    val fr = core.friends.sortedWith(compareByDescending<FriendCore.Friend> { core.isOnline(it.fid) }.thenBy { core.shownName(it).lowercase() })
    val online = fr.filter { core.isOnline(it.fid) }; val offline = fr.filter { !core.isOnline(it.fid) }
    val total = inv.size + fr.size
    if (total == 0) {
        text.textAlign = Paint.Align.LEFT; text.typeface = Typeface.DEFAULT; text.textSize = 15 * d; text.color = dimInk
        c.drawText(L.t("Add friends on your phone: You › Friends"), r.left + pad, y + 20 * d, text)
        text.typeface = t.typeface
        return
    }
    val sections = (if (inv.isNotEmpty()) 1 else 0) + (if (online.isNotEmpty()) 1 else 0) + (if (offline.isNotEmpty()) 1 else 0)
    // (the last row may use the panel's bottom padding: one more friend instead of empty space)
    var fit = ((room() - sections * 22 * d) / rowH + 0.2f).toInt()
    if (fit < total) fit = ((room() - sections * 22 * d - 44 * d) / rowH + 0.2f).toInt() // leave room for "+N more"
    fit = fit.coerceAtLeast(1)
    var shown = 0
    if (inv.isNotEmpty() && shown < fit) {
        section(L.t("Invites"))
        for (i in inv) { if (shown >= fit) break
            row(L.t("${i.from} invites you"), if (i.dest.isNotBlank()) L.t("Convoy to ${i.dest}") else L.t("No destination yet"), i.from, L.t("Join") to "fp:join:${i.convoy}", null, t.route, onRoute); shown++ }
    }
    fun friendRow(f: FriendCore.Friend) {
        val sent = C.code?.let { core.sentTo(f.fid, it) }
        val btn = if (sent != null) L.t("Invited ✓") to "fp:none" else "+ " + L.t("Invite") to "fp:inv:${f.fid}"
        row(core.shownName(f), Friends.activityText(f.fid), f.fid, btn, null)
    }
    if (online.isNotEmpty() && shown < fit) { section(L.t("Online")); for (f in online) { if (shown >= fit) break; friendRow(f); shown++ } }
    if (offline.isNotEmpty() && shown < fit) { section(L.t("Not online")); for (f in offline) { if (shown >= fit) break; friendRow(f); shown++ } }
    if (shown < total) {
        val more = RectF(r.left + pad, y + 2 * d, r.right - pad, y + 42 * d)
        button(more, L.t("+${total - shown} more"), "fp:more")
    }
    text.textAlign = Paint.Align.LEFT; text.typeface = t.typeface
}

/** Car: the convoy box under the turn box (name · how far ahead, % of the race, speed, arrival). */
internal fun MapRenderer.drawCarConvoy(c: Canvas, a: Rect, d: Float, t: Theme) {
    val rows = Convoy.rows(t.route).take(5)
    val w = minOf(400 * d, a.width() - 28f * d)
    val rowH = 26 * d
    val r = RectF(a.left + 12 * d, bannerBottom + 8 * d, a.left + 12 * d + w, bannerBottom + 8 * d + 46 * d + rows.size * rowH)
    carConvoyBottom = r.bottom; carConvoyRect.set(r)
    panel(c, r, d, t, outline = true)
    text.textAlign = Paint.Align.LEFT; text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 11 * d
    text.color = Ui.onPanel(t, t.route, 3.0)
    c.drawText(gr.gamemap.L.t("CONVOY") + " · ${Convoy.destination?.name?.uppercase()?.take(24) ?: ""}", r.left + 12 * d, r.top + 17 * d, text)
    // progress line
    val x0 = r.left + 14 * d; val x1 = r.right - 30 * d; val ly = r.top + 31 * d
    fill.color = withAlpha(Ui.ink(t), 60); c.drawRoundRect(x0, ly - 1.5f * d, x1, ly + 1.5f * d, 2 * d, 2 * d, fill)
    text.textSize = 12 * d; text.typeface = Typeface.DEFAULT; c.drawText("🏁", x1 + 6 * d, ly + 5 * d, text)
    for (row in rows.sortedBy { if (it.me) 1 else 0 }) {
        val cx = x0 + ((x1 - x0) * row.progress).toFloat()
        if (row.along) {
            stroke.color = Ui.dim(t, 190); stroke.strokeWidth = 1.5f * d
            stroke.pathEffect = android.graphics.DashPathEffect(floatArrayOf(2.5f * d, 2f * d), 0f)
            c.drawCircle(cx, ly, 5.5f * d, stroke); stroke.pathEffect = null
            continue
        }
        fill.color = if (row.stale) Color.GRAY else row.color
        c.drawCircle(cx, ly, 6.5f * d, fill)
    }
    var y = r.top + 46 * d
    // columns from the right: arrival time, speed (with its unit), how much of the race (%)
    val etaX = r.right - 12 * d; val speedX = r.right - 70 * d; val pctX = r.right - 142 * d
    for (row in rows) {
        fill.color = if (row.stale) Color.GRAY else row.color
        c.drawCircle(r.left + 18 * d, y + 13 * d, 5 * d, fill)
        text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 15 * d; text.color = if (row.along) Ui.dim(t, 200) else Ui.ink(t)
        val name = if (row.me) gr.gamemap.L.t("You") else row.name
        c.drawText(ellipsize(name, 90 * d), r.left + 30 * d, y + 18 * d, text)
        val nw = text.measureText(ellipsize(name, 90 * d))
        text.typeface = Typeface.DEFAULT; text.textSize = 13 * d; text.color = Ui.dim(t, 190)
        // the "850 m ahead" part gives way first on a narrow screen (the numbers never do)
        c.drawText(ellipsize(gr.gamemap.L.t(" · " + Convoy.relative(row)), pctX - 48 * d - (r.left + 30 * d + nw)), r.left + 30 * d + nw, y + 18 * d, text)
        text.textAlign = Paint.Align.RIGHT
        // % of the race: the race figure, in bold and the route's colour (✓ arrived, – not racing)
        val known = row.me || row.left >= 0
        val pct = (row.progress * 100).toInt().coerceIn(0, 99)
        if (row.along) {
            // driving along: no race figure
            text.typeface = Typeface.DEFAULT; text.textSize = 12 * d; text.color = Ui.dim(t, 170)
            c.drawText(gr.gamemap.L.t("along"), pctX, y + 18 * d, text)
        } else {
            text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 16 * d
            text.color = if (row.state == "arrived") Ui.onPanel(t, Color.rgb(47, 210, 122), 3.0) else Ui.onPanel(t, t.route, 3.0)
            c.drawText(when { row.state == "arrived" -> "✓"; row.state == "own" || !known -> "–"; else -> "$pct%" }, pctX, y + 18 * d, text)
        }
        // speed with a small "km/h", so the two numbers can't be mixed up
        if (row.state != "arrived") {
            text.typeface = Typeface.DEFAULT; text.textSize = 10 * d; text.color = Ui.dim(t, 170)
            val unit = if (MapState.imperial) "mph" else "km/h"
            c.drawText(unit, speedX, y + 18 * d, text)
            val uw = text.measureText(unit) + 3 * d
            text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 15 * d
            text.color = if (row.kmh == 0 && row.state == "driving") Color.rgb(255, 120, 120) else if (row.along) Ui.dim(t, 200) else Ui.ink(t)
            val v = if (MapState.imperial) (row.kmh / 1.609).toInt() else row.kmh
            c.drawText("$v", speedX - uw, y + 18 * d, text)
        }
        text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 15 * d
        text.color = Ui.onPanel(t, Color.rgb(47, 210, 122), 3.0)
        c.drawText(if (row.eta > 0) NavEngine.clock(row.eta) else "–", etaX, y + 18 * d, text)
        text.textAlign = Paint.Align.LEFT
        carHits += RectF(r.left, y, r.right, y + rowH) to "friend:${row.id}"
        y += rowH
    }
    text.typeface = t.typeface
    bannerBottom = r.bottom; bannerRight = r.right
}

/** Car: the card of a tapped place (top-left, under the turn box when navigating), with Go. */
internal fun MapRenderer.drawCarPlace(c: Canvas, a: Rect, d: Float, t: Theme) {
    val pl = selectedPlace ?: return
    val w = minOf(420 * d, a.width() - 28f * d)
    val top = if (NavEngine.active) bannerBottom + 10 * d else 12 * d
    val hasExtra = pl.extra.isNotBlank()
    val h = (if (hasExtra) 186 else 160) * d
    val box = RectF(a.left + 14 * d, top, a.left + 14 * d + w, top + h)
    panel(c, box, d, t, outline = true)
    val ink = Ui.ink(t)
    text.textAlign = Paint.Align.LEFT
    text.typeface = Typeface.DEFAULT; text.textSize = 24 * d
    c.drawText(pl.icon, box.left + 18 * d, box.top + 42 * d, text)
    text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 21 * d; text.color = ink
    c.drawText(ellipsize(pl.name, w - 120 * d), box.left + 58 * d, box.top + 34 * d, text)
    val near = LocationTracker.position
    val sub = listOfNotNull(pl.address.takeIf { it.isNotBlank() }, near?.let { NavEngine.formatDistance(Geo.distance(it, pl.pos)) },
        Reviews.stars(pl.rating, pl.ratings)).joinToString(" · ")
    text.typeface = Typeface.DEFAULT; text.textSize = 15 * d; text.color = Ui.dim(t, 200)
    c.drawText(ellipsize(gr.gamemap.L.t(sub), w - 120 * d), box.left + 58 * d, box.top + 56 * d, text)
    if (hasExtra) {
        text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 15 * d; text.color = Ui.onPanel(t, t.route, 3.0)
        c.drawText(ellipsize(gr.gamemap.L.t(Fuel.brief(pl.extra)), w - 36 * d), box.left + 18 * d, box.top + 84 * d, text)
    }
    // ✕
    val x = RectF(box.right - 54 * d, box.top + 6 * d, box.right - 8 * d, box.top + 52 * d)
    text.textAlign = Paint.Align.CENTER; text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 22 * d; text.color = ink
    c.drawText("✕", x.centerX(), x.centerY() + 8 * d, text)
    carHits += x to "placeclose"
    // Go (or, while navigating: Add stop | Go instead)
    val navigating = NavEngine.active && !NavEngine.arrived
    val gy0 = box.bottom - 18 * d - 58 * d; val gy1 = box.bottom - 18 * d
    val go = if (navigating) RectF(box.left + 18 * d, gy0, box.centerX() - 6 * d, gy1) else RectF(box.left + 18 * d, gy0, box.right - 18 * d, gy1)
    glassButton(c, go, d, t, withAlpha(t.route, 240))
    text.textSize = 20 * d
    text.color = if (Ui.contrast(Color.WHITE, t.route) >= 3) Color.WHITE else Color.BLACK
    c.drawText(if (navigating) gr.gamemap.L.t("＋ Add stop") else gr.gamemap.L.t("Go"), go.centerX(), go.centerY() + 7 * d, text)
    carHits += go to if (navigating) "placestop" else "placego"
    if (navigating) {
        val alt = RectF(box.centerX() + 6 * d, gy0, box.right - 18 * d, gy1)
        glassButton(c, alt, d, t)
        text.color = glassInk(t); text.textSize = 18 * d
        c.drawText(gr.gamemap.L.t("Go instead"), alt.centerX(), alt.centerY() + 6 * d, text)
        carHits += alt to "placego"
    }
    text.textAlign = Paint.Align.LEFT
    text.typeface = t.typeface
}

/** Car: "17 min · 18:02 · 4.5 km" next to the speed box, in the theme's see-through panel. */
internal fun MapRenderer.drawCarEta(c: Canvas, a: Rect, d: Float, t: Theme) {
    if (!NavEngine.active || NavEngine.arrived || NavEngine.loading || NavEngine.error != null) return
    val delay = NavEngine.trafficDelaySeconds / 60
    val big = NavEngine.formatDuration(NavEngine.remainingSeconds)
    val small = "${NavEngine.arrivalClock()} · ${NavEngine.formatDistance(NavEngine.remainingDistance)}" + (if (delay >= 1) " · +$delay min" else "")
    text.textAlign = Paint.Align.LEFT
    text.typeface = t.typeface
    text.textSize = 26 * d
    val w1 = text.measureText(big)
    val tf = text.typeface
    text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 15 * d
    val w2 = text.measureText(small)
    val w = maxOf(w1, w2) + 28 * d
    val h = 66 * d
    val left = a.left + 14 * d + 136 * d
    val box = RectF(left, a.bottom - 14 * d - h, left + w, a.bottom - 14 * d)
    etaRight = box.right; etaAt.set(box)
    panel(c, box, d, t, outline = false)
    text.typeface = tf; text.textSize = 26 * d
    text.color = Ui.onPanel(t, when { delay >= 10 -> Color.rgb(255, 110, 110); delay >= 3 -> Color.rgb(255, 184, 107); else -> Color.rgb(123, 227, 160) })
    c.drawText(gr.gamemap.L.t(big), box.left + 14 * d, box.top + 30 * d, text)
    text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 15 * d
    text.color = Ui.ink(t)
    c.drawText(gr.gamemap.L.t(small), box.left + 14 * d, box.bottom - 13 * d, text)
    text.typeface = t.typeface
}
