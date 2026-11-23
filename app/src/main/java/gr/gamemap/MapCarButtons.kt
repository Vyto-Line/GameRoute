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

/** The id of the car button under (x, y), if any. */
internal fun MapRenderer.carButtonAt(x: Float, y: Float): String? {
    val hits = hitsShown
    // exact hit first, then a forgiving 12 dp halo (car screens are hard to hit precisely)
    hits.lastOrNull { it.first.contains(x, y) }?.let { return it.second }
    val pad = 12 * lastDensity
    return hits.lastOrNull { RectF(it.first).apply { inset(-pad, -pad) }.contains(x, y) }?.second
}

internal fun MapRenderer.glassLight(t: Theme) = Ui.luminance(Ui.panel(t)) > 0.4

/** Icon and text colour on a glass button. */
internal fun MapRenderer.glassInk(t: Theme) = if (glassLight(t)) Ui.ink(t) else Color.WHITE

internal fun MapRenderer.glassButton(c: Canvas, r: RectF, d: Float, t: Theme, fillCol: Int? = null) {
    // light styles: a more solid pane and dark icons, or white icons vanish on a bright map
    val light = glassLight(t)
    // solid: map icons must not show through the buttons
    val base = fillCol ?: Ui.panel(t).let { Color.argb(248, Color.red(it), Color.green(it), Color.blue(it)) }
    fill.color = base
    stroke.color = if (light) Ui.onPanel(t, t.route, 3.0) else withAlpha(t.route, 230)
    stroke.strokeWidth = (if (light) 2.2f else 1.8f) * d
    when (t.hud) {
        HudShape.ROUND, HudShape.PAPER -> {
            val rad = minOf(r.width(), r.height()) / 2
            c.drawRoundRect(r, rad, rad, fill)
            if (fillCol == null) c.drawRoundRect(r, rad, rad, stroke)
        }
        HudShape.GLASS -> {
            val rad = minOf(r.width(), r.height()) / 2
            c.drawRoundRect(r, rad, rad, fill)
            if (fillCol == null) {
                val sw = stroke.strokeWidth; val sc = stroke.color
                stroke.color = withAlpha(t.route, 55); stroke.strokeWidth = 5 * d; c.drawRoundRect(r, rad, rad, stroke)
                stroke.color = sc; stroke.strokeWidth = sw; c.drawRoundRect(r, rad, rad, stroke)
            }
        }
        HudShape.CHAMFER -> {
            val k = minOf(10 * d, r.height() / 3)
            val p = Path().apply {
                moveTo(r.left + k, r.top); lineTo(r.right, r.top); lineTo(r.right, r.bottom - k)
                lineTo(r.right - k, r.bottom); lineTo(r.left, r.bottom); lineTo(r.left, r.top + k); close()
            }
            c.drawPath(p, fill); if (fillCol == null) c.drawPath(p, stroke)
        }
        HudShape.PIXEL -> {
            val k = 4 * d
            c.drawRect(r.left + k, r.top, r.right - k, r.bottom, fill)
            c.drawRect(r.left, r.top + k, r.right, r.bottom - k, fill)
            if (fillCol == null) { stroke.isAntiAlias = false; c.drawRect(r, stroke); stroke.isAntiAlias = true }
        }
    }
}

/** Simple white line icons. */
internal fun MapRenderer.icon(c: Canvas, id: String, cx: Float, cy: Float, s: Float, col: Int) {
    iconPaint.color = col; iconPaint.strokeWidth = s * 0.16f
    iconPaint.style = Paint.Style.STROKE
    val h = s / 2
    val p = Path()
    when (id) {
        "style" -> { p.moveTo(cx, cy - h); p.lineTo(cx + h, cy - h * 0.4f); p.lineTo(cx, cy + h * 0.2f); p.lineTo(cx - h, cy - h * 0.4f); p.close()
            p.moveTo(cx - h, cy + h * 0.25f); p.lineTo(cx, cy + h * 0.85f); p.lineTo(cx + h, cy + h * 0.25f) }
        "friends" -> { p.addCircle(cx - h * 0.35f, cy - h * 0.35f, h * 0.34f, Path.Direction.CW)
            p.moveTo(cx - h * 0.95f, cy + h * 0.85f); p.quadTo(cx - h * 0.95f, cy + h * 0.05f, cx - h * 0.35f, cy + h * 0.05f); p.quadTo(cx + h * 0.25f, cy + h * 0.05f, cx + h * 0.25f, cy + h * 0.85f)
            p.addCircle(cx + h * 0.45f, cy - h * 0.25f, h * 0.27f, Path.Direction.CW)
            p.moveTo(cx + h * 0.45f, cy + h * 0.15f); p.quadTo(cx + h * 0.95f, cy + h * 0.15f, cx + h * 0.95f, cy + h * 0.8f) }
        "report" -> { p.moveTo(cx, cy - h); p.lineTo(cx + h, cy + h * 0.8f); p.lineTo(cx - h, cy + h * 0.8f); p.close()
            p.moveTo(cx, cy - h * 0.3f); p.lineTo(cx, cy + h * 0.25f); p.moveTo(cx, cy + h * 0.52f); p.lineTo(cx, cy + h * 0.56f) }
        "mute", "unmute" -> { p.moveTo(cx - h, cy - h * 0.35f); p.lineTo(cx - h * 0.45f, cy - h * 0.35f); p.lineTo(cx + h * 0.1f, cy - h * 0.85f)
            p.lineTo(cx + h * 0.1f, cy + h * 0.85f); p.lineTo(cx - h * 0.45f, cy + h * 0.35f); p.lineTo(cx - h, cy + h * 0.35f); p.close()
            if (id == "mute") { p.moveTo(cx + h * 0.45f, cy - h * 0.4f); p.quadTo(cx + h * 0.8f, cy, cx + h * 0.45f, cy + h * 0.4f) }
            else { p.moveTo(cx + h * 0.4f, cy - h * 0.35f); p.lineTo(cx + h, cy + h * 0.35f); p.moveTo(cx + h, cy - h * 0.35f); p.lineTo(cx + h * 0.4f, cy + h * 0.35f) } }
        "traffic" -> { val r = RectF(cx - h * 0.5f, cy - h, cx + h * 0.5f, cy + h); p.addRoundRect(r, h * 0.35f, h * 0.35f, Path.Direction.CW)
            // the three lights, filled in their own colours
            val keep = iconPaint.color
            iconPaint.style = Paint.Style.FILL
            for ((k, col) in listOf(-1 to Color.rgb(240, 62, 62), 0 to Color.rgb(250, 176, 5), 1 to Color.rgb(47, 180, 90))) {
                iconPaint.color = col; c.drawCircle(cx, cy + k * h * 0.6f, h * 0.22f, iconPaint)
            }
            iconPaint.style = Paint.Style.STROKE; iconPaint.color = keep }
        "zoomin" -> { p.moveTo(cx - h * 0.8f, cy); p.lineTo(cx + h * 0.8f, cy); p.moveTo(cx, cy - h * 0.8f); p.lineTo(cx, cy + h * 0.8f) }
        "zoomout" -> { p.moveTo(cx - h * 0.8f, cy); p.lineTo(cx + h * 0.8f, cy) }
        "menu" -> { for (k in -1..1) { p.moveTo(cx - h * 0.8f, cy + k * h * 0.55f); p.lineTo(cx + h * 0.8f, cy + k * h * 0.55f) } }
        "overview" -> { p.moveTo(cx - h, cy - h * 0.6f); p.lineTo(cx - h * 0.35f, cy - h * 0.85f); p.lineTo(cx + h * 0.35f, cy - h * 0.6f)
            p.lineTo(cx + h, cy - h * 0.85f); p.lineTo(cx + h, cy + h * 0.6f); p.lineTo(cx + h * 0.35f, cy + h * 0.85f)
            p.lineTo(cx - h * 0.35f, cy + h * 0.6f); p.lineTo(cx - h, cy + h * 0.85f); p.close()
            p.moveTo(cx - h * 0.35f, cy - h * 0.85f); p.lineTo(cx - h * 0.35f, cy + h * 0.6f); p.moveTo(cx + h * 0.35f, cy - h * 0.6f); p.lineTo(cx + h * 0.35f, cy + h * 0.85f) }
        "recenter" -> {
            // a target: ring, centre dot, four ticks
            p.addCircle(cx, cy, h * 0.62f, Path.Direction.CW)
            p.moveTo(cx, cy - h); p.lineTo(cx, cy - h * 0.78f); p.moveTo(cx, cy + h); p.lineTo(cx, cy + h * 0.78f)
            p.moveTo(cx - h, cy); p.lineTo(cx - h * 0.78f, cy); p.moveTo(cx + h, cy); p.lineTo(cx + h * 0.78f, cy)
            val keep = iconPaint.color
            c.drawPath(p, iconPaint); p.reset()
            iconPaint.style = Paint.Style.FILL; iconPaint.color = keep
            c.drawCircle(cx, cy, h * 0.24f, iconPaint)
            iconPaint.style = Paint.Style.STROKE }
        "search" -> { p.addCircle(cx - h * 0.15f, cy - h * 0.15f, h * 0.6f, Path.Direction.CW); p.moveTo(cx + h * 0.3f, cy + h * 0.3f); p.lineTo(cx + h * 0.85f, cy + h * 0.85f) }
        "home" -> { p.moveTo(cx - h, cy - h * 0.05f); p.lineTo(cx, cy - h * 0.85f); p.lineTo(cx + h, cy - h * 0.05f)
            p.moveTo(cx - h * 0.7f, cy - h * 0.25f); p.lineTo(cx - h * 0.7f, cy + h * 0.8f); p.lineTo(cx + h * 0.7f, cy + h * 0.8f); p.lineTo(cx + h * 0.7f, cy - h * 0.25f) }
        "work" -> { p.addRect(cx - h, cy - h * 0.45f, cx + h, cy + h * 0.8f, Path.Direction.CW); p.moveTo(cx - h * 0.35f, cy - h * 0.45f)
            p.lineTo(cx - h * 0.35f, cy - h * 0.8f); p.lineTo(cx + h * 0.35f, cy - h * 0.8f); p.lineTo(cx + h * 0.35f, cy - h * 0.45f) }
    }
    c.drawPath(p, iconPaint)
    iconPaint.style = Paint.Style.STROKE
}

internal fun MapRenderer.drawCarButtons(c: Canvas, a: Rect, d: Float, t: Theme) {
    val size = 48 * d
    val gap = 10 * d
    val x = a.right - 14 * d - size
    val white = Color.WHITE
    val ink = glassInk(t)
    fun btn(id: String, y: Float, iconId: String = id, filled: Boolean = false) {
        val r = RectF(x, y, x + size, y + size)
        glassButton(c, r, d, t, if (filled) withAlpha(t.route, 235) else null)
        icon(c, iconId, r.centerX(), r.centerY(), size * 0.46f, if (filled) Ui.ink(t).let { if (Ui.contrast(it, t.route) < 3) Color.BLACK else it } else ink)
        carHits += r to id
    }
    // Android Auto's ☰ sits above the stable area; when the host hides it (the visible area
    // then reaches the top), GameMap shows its own lookalike in the same place.
    val stripBottom = if (stableTop > 8 * d) stableTop.toFloat() else 76 * d
    val hostShown = a.top > 8 * d
    if (!hostShown) {
        val ms = minOf(56 * d, stripBottom - 8 * d).coerceAtLeast(40 * d)
        val cy = stripBottom / 2
        val r = RectF(x + size / 2 - ms / 2, cy - ms / 2, x + size / 2 + ms / 2, cy + ms / 2)
        glassButton(c, r, d, t)
        icon(c, "menu", r.centerX(), r.centerY(), ms * 0.42f, ink)
        carHits += r to "menu"
    }
    // right: style, traffic / voice, report (below the ☰, whichever one is showing)
    var y = maxOf(stripBottom, a.top.toFloat()) + gap
    // a short screen: all of them don't fit in one column (Friends hid behind +, 22 Nov):
    // the top ones move to a second column beside the first
    val nTop = if (NavEngine.active) 3 else 2
    val nBottom = 2 + (if (!view.following || view.userBearing != null || view.zoomedAway || (NavEngine.active && NavEngine.route.size > 1)) 1 else 0)
    val fits = y + (nTop + nBottom) * (size + gap) <= a.bottom - 14 * d + gap
    val topX = if (fits) x else x - size - gap
    fun btnTop(id: String, yy: Float, iconId: String = id) {
        val r = RectF(topX, yy, topX + size, yy + size)
        glassButton(c, r, d, t, null)
        icon(c, iconId, r.centerX(), r.centerY(), size * 0.46f, ink)
        carHits += r to id
    }
    btnTop("style", y); y += size + gap
    // (Show traffic moved to Settings › Map; driving, the sound button takes its place)
    if (NavEngine.active) { btnTop(if (Voice.muted) "unmute" else "mute", y); y += size + gap }
    // Friends (where Report used to be): how many are online, in a green badge
    btnTop("friends", y)
    val online = if (Friends.ready && Friends.core.started) Friends.core.friends.count { Friends.core.isOnline(it.fid) } +
        Friends.core.invites.count { it.convoy != Convoy.code } else 0
    if (online > 0) {
        val bx = topX + size - 4 * d; val by = y + 4 * d
        fill.color = Color.rgb(64, 200, 120); c.drawCircle(bx, by, 11 * d, fill)
        stroke.color = Color.rgb(12, 14, 20); stroke.strokeWidth = 2 * d; c.drawCircle(bx, by, 11 * d, stroke)
        text.textAlign = Paint.Align.CENTER; text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 13 * d; text.color = Color.rgb(12, 14, 20)
        c.drawText(if (online > 9) "9+" else "$online", bx, by + 4.5f * d, text)
    }
    // bottom right: zoom and recenter / overview
    var b = a.bottom - 14 * d - size
    when {
        !view.following || view.userBearing != null || view.zoomedAway -> { btn("recenter", b, filled = true); b -= size + gap }
        NavEngine.active && NavEngine.route.size > 1 -> { btn("overview", b); b -= size + gap }
    }
    btn("zoomout", b); b -= size + gap
    btn("zoomin", b)

    text.typeface = Typeface.DEFAULT_BOLD
    text.textAlign = Paint.Align.LEFT
    if (!(NavEngine.active && !NavEngine.arrived)) endAt.setEmpty()
    if (NavEngine.active && !NavEngine.arrived) {
        // End, next to the arrival panel
        text.textSize = 18 * d
        val w = text.measureText(gr.gamemap.L.t("End")) + 44 * d
        val left = if (etaRight > 0) etaRight + 10 * d else a.left + 160 * d
        val r = RectF(left, a.bottom - 14 * d - 66 * d, left + w, a.bottom - 14 * d)
        endAt.set(r)
        glassButton(c, r, d, t, Color.argb(225, 229, 72, 77))
        text.color = white
        c.drawText(gr.gamemap.L.t("End"), r.left + 22 * d, r.centerY() + 6.5f * d, text)
        carHits += r to "end"
    } else if (!NavEngine.active && selectedPlace == null && !carLobbyShown && carFriends == null && carPanel?.takeIf { !it.dialog } == null) {
        // "Where to?" and saved places, top left (a place's card takes the corner when open)
        val r = RectF(a.left + 14 * d, 12 * d, a.left + 14 * d + 300 * d, 12 * d + 50 * d)
        glassButton(c, r, d, t)
        icon(c, "search", r.left + 26 * d, r.centerY(), 20 * d, Ui.onPanel(t, t.route, 3.0))
        text.textSize = 18 * d; text.typeface = t.typeface; text.color = ink
        c.drawText(gr.gamemap.L.t("Where to?"), r.left + 50 * d, r.centerY() + 6.5f * d, text)
        carHits += r to "search"
        var cxp = r.left
        text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 15 * d
        for (k in listOf("home", "work")) {
            val pl = Places.saved(k) ?: continue
            val label = (if (k == "home") gr.gamemap.L.t("Home") else gr.gamemap.L.t("Work")) + (Places.cachedEta(pl)?.let { " · ${(it.seconds + 30) / 60} min" } ?: "")
            val w = text.measureText(label) + 52 * d
            val cr = RectF(cxp, r.bottom + 10 * d, cxp + w, r.bottom + 10 * d + 44 * d)
            glassButton(c, cr, d, t)
            icon(c, k, cr.left + 22 * d, cr.centerY(), 16 * d, ink)
            text.color = ink
            c.drawText(label, cr.left + 38 * d, cr.centerY() + 5.5f * d, text)
            carHits += cr to k
            cxp = cr.right + 10 * d
        }
        // your labels ("Dad", "Gym") next to them, as many as fit (the rest are in Search)
        for ((i, s) in Places.labelled().withIndex()) {
            val pl = s.place()
            val label = (s.label ?: continue) + (Places.cachedEta(pl)?.let { " · ${(it.seconds + 30) / 60} min" } ?: "")
            val w = text.measureText(label) + 30 * d + (if (s.icon != null) 22 * d else 0f)
            if (cxp + w > a.left + a.width() * 0.62f) break
            val cr = RectF(cxp, r.bottom + 10 * d, cxp + w, r.bottom + 10 * d + 44 * d)
            glassButton(c, cr, d, t)
            var tx = cr.left + 15 * d
            s.icon?.let { e -> text.textSize = 16 * d; c.drawText(e, tx, cr.centerY() + 6 * d, text); tx += 22 * d; text.textSize = 15 * d }
            text.color = ink
            c.drawText(label, tx, cr.centerY() + 5.5f * d, text)
            carHits += cr to "fav:$i"
            cxp = cr.right + 10 * d
        }
    }
    text.typeface = t.typeface
}

internal fun MapRenderer.carRoutesWidth(a: Rect, d: Float) = minOf(440 * d, a.width() * 0.42f)

/**
 * Car, choosing a route: GameRoute's own cards (in the style's colours and font, like the
 * phone's) instead of Android Auto's black list. Tap a card to pick it, Start to go, ✕ to
 * close. (Older cars that can't report taps keep Android Auto's list.)
 */
internal fun MapRenderer.drawCarRoutes(c: Canvas, a: Rect, d: Float, t: Theme) {
    val opts = NavEngine.options
    val w = carRoutesWidth(a, d)
    val left = a.left + 14 * d
    val ink = Ui.ink(t); val dim = Ui.dim(t, 200)
    val accent = Ui.onPanel(t, t.route, 3.0)
    val loading = opts.isEmpty() || opts.any { !NavEngine.timeKnown(it) }
    val note = NavEngine.endNote
    val gap = 8 * d
    val head = 64 * d
    val shown = if (loading) 0 else minOf(opts.size, 3)
    val noteH = if (note != null && !loading) 50 * d else 0f
    val startH = 60 * d
    // every route fits on the screen (no scrolling in a car): on short screens the cards get
    // shorter and drop the "via" line instead of the third route being cut off
    val room = (a.bottom - 14 * d - startH - 10 * d) - (a.top + 14 * d + head + 6 * d) - noteH
    val cardH = if (shown == 0) 84 * d else (room / shown - gap).coerceIn(50 * d, 84 * d)
    val compact = cardH < 76 * d
    val bodyH = if (loading) 56 * d else shown * (cardH + gap) + noteH
    val box = RectF(left, a.top + 14 * d, left + w, a.top + 14 * d + head + bodyH + 6 * d)
    // Start below the panel (or at the bottom, whichever is higher up)
    val sTop = minOf(box.bottom + 10 * d, a.bottom - 14 * d - startH)
    if (box.bottom > sTop - 10 * d) box.bottom = sTop - 10 * d
    panel(c, box, d, t, outline = false)
    // header: ✕, how many routes, where to
    text.textAlign = Paint.Align.LEFT
    text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 24 * d; text.color = accent
    c.drawText("✕", box.left + 16 * d, box.top + 40 * d, text)
    carHits += RectF(box.left, box.top, box.left + 58 * d, box.top + head) to "routecancel"
    text.typeface = t.typeface; text.textSize = 19 * d; text.color = ink
    val title = if (loading) gr.gamemap.L.t("Finding routes…") else gr.gamemap.L.t(if (opts.size == 1) "1 route" else "${opts.size} routes")
    c.drawText(title, box.left + 54 * d, box.top + 30 * d, text)
    text.typeface = Typeface.DEFAULT; text.textSize = 14 * d; text.color = dim
    c.drawText(ellipsize(NavEngine.planPlace?.name.orEmpty(), w - 70 * d), box.left + 54 * d, box.top + 50 * d, text)
    if (!loading) {
        val fastest = NavEngine.fastestIndex()
        val anyToll = opts.any { it.tollCost > 0 || it.hasToll }
        var y = box.top + head
        for (i in 0 until shown) {
            val o = opts[i]
            val r = RectF(box.left + 12 * d, y, box.right - 12 * d, y + cardH)
            if (r.bottom > box.bottom) break
            val on = i == NavEngine.selected
            if (on) {
                fill.color = withAlpha(t.route, 40); c.drawRoundRect(r, 10 * d, 10 * d, fill)
                stroke.color = accent; stroke.strokeWidth = 2.5f * d; c.drawRoundRect(r, 10 * d, 10 * d, stroke)
            } else {
                stroke.color = withAlpha(ink, 45); stroke.strokeWidth = 1.2f * d; c.drawRoundRect(r, 10 * d, 10 * d, stroke)
            }
            // 10 min   2.9 km · 23:22 · €2.40        FASTEST / +4 min traffic
            text.typeface = t.typeface; text.textSize = (if (compact) 21 else 24) * d; text.color = ink
            val tm = NavEngine.formatDuration(o.seconds)
            val line1 = if (compact) r.top + (cardH - 12 * d) * 0.55f + 4 * d else r.top + 32 * d
            c.drawText(tm, r.left + 14 * d, line1, text)
            val tw = text.measureText(tm)
            val toll = when { o.tollCost > 0 -> Tolls.money(o.tollCost); o.hasToll -> gr.gamemap.L.t("tolls"); anyToll -> gr.gamemap.L.t("No tolls"); else -> null }
            val sub = listOfNotNull(NavEngine.formatDistance(o.distance), NavEngine.optionArrival(o), toll).joinToString(" · ")
            val badge = when {
                o.trafficSeconds != null && i == fastest && opts.size > 1 -> gr.gamemap.L.t("FASTEST")
                o.delaySeconds >= 60 -> gr.gamemap.L.t("+${o.delaySeconds / 60} min traffic")
                else -> null
            }
            text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 12.5f * d
            val bw = badge?.let { text.measureText(it) } ?: 0f
            if (badge != null) {
                text.color = if (i == fastest) accent else Color.rgb(229, 72, 77)
                text.textAlign = Paint.Align.RIGHT
                c.drawText(badge, r.right - 12 * d, line1 - 2 * d, text)
                text.textAlign = Paint.Align.LEFT
            }
            text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 14.5f * d; text.color = dim
            c.drawText(ellipsize(sub, r.width() - tw - bw - 46 * d), r.left + 24 * d + tw, line1 - 1 * d, text)
            if (!compact) {
                text.typeface = Typeface.DEFAULT; text.textSize = 14 * d
                val via = if (o.via.isNotBlank()) gr.gamemap.L.t("via ${o.via}") else gr.gamemap.L.t("route ${i + 1}")
                c.drawText(ellipsize(via + if (o.tolls.size > 1) " · " + gr.gamemap.L.t("${o.tolls.size} toll stations") else "", r.width() - 28 * d), r.left + 14 * d, r.top + 54 * d, text)
            }
            // traffic along it
            val bar = RectF(r.left + 14 * d, r.bottom - (if (compact) 13 else 17) * d, r.right - 14 * d, r.bottom - (if (compact) 8 else 11) * d)
            fill.color = if (o.trafficSeconds != null) Color.rgb(47, 210, 122) else withAlpha(dim, 90)
            c.drawRoundRect(bar, 3 * d, 3 * d, fill)
            if (o.length > 0) for (st in o.traffic) {
                fill.color = when { st.value >= 4 -> Color.rgb(140, 20, 30); st.value == 3 -> Color.rgb(230, 50, 60); st.value == 2 -> Color.rgb(255, 140, 40); else -> Color.rgb(255, 200, 60) }
                val x0 = bar.left + (st.start / o.length * bar.width()).toFloat()
                val x1 = maxOf(bar.left + (st.end / o.length * bar.width()).toFloat(), x0 + bar.height())
                c.drawRect(x0, bar.top, minOf(x1, bar.right), bar.bottom, fill)
            }
            carHits += r to "route:$i"
            y += cardH + gap
        }
        if (note != null && y + 40 * d <= box.bottom) {
            text.typeface = Typeface.DEFAULT; text.textSize = 13.5f * d; text.color = Color.rgb(255, 170, 60)
            c.drawText(ellipsize("⚠ $note", w - 28 * d), box.left + 14 * d, y + 20 * d, text)
        }
    }
    // Start · 10 min
    val sr = RectF(left, sTop, left + w, sTop + startH)
    val ready = !loading
    // the route's colour, unless it hardly shows on this map (8-Bit): then the panel's, outlined
    val sf = if (t.pixel <= 1 && Ui.contrast(t.route, t.background) >= 1.8) t.route else Ui.panel(t)
    glassButton(c, sr, d, t, if (ready) withAlpha(sf, 245) else withAlpha(dim, 120))
    if (ready && sf != t.route) { stroke.color = accent; stroke.strokeWidth = 2.5f * d; c.drawRoundRect(sr, 6 * d, 6 * d, stroke) }
    text.textAlign = Paint.Align.CENTER; text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 21 * d
    text.color = if (Ui.contrast(Color.WHITE, sf) >= 3) Color.WHITE else Color.BLACK
    val pick = opts.getOrNull(NavEngine.selected)
    c.drawText(if (ready && pick != null) gr.gamemap.L.t("Start") + " · " + NavEngine.formatDuration(pick.seconds) else gr.gamemap.L.t("Start"),
        sr.centerX(), sr.centerY() + 7 * d, text)
    text.textAlign = Paint.Align.LEFT
    if (ready) carHits += sr to "routestart"
    text.typeface = t.typeface
}

/** Car: the arrival card with trip stats, XP, and Parking / Done buttons. */
internal fun MapRenderer.drawCarTrip(c: Canvas, a: Rect, d: Float, t: Theme) {
    val trip = NavEngine.lastTrip ?: return
    carHits.clear()
    val w = minOf(460 * d, a.width() - 28f * d)
    val h = 236 * d
    // fixed in the top-left corner (Android Auto's own bars come and go and move the visible area)
    val box = RectF(a.left + 14 * d, 12 * d, a.left + 14 * d + w, 12 * d + h)
    panel(c, box, d, t, outline = true)
    val ink = Ui.ink(t)
    CarParking.list?.let { drawParkingList(c, box, d, t, it); return }
    text.textAlign = Paint.Align.LEFT
    // ARRIVED · 13:29, the whole name (two lines when long), time and distance. No XP in the car:
    // it waits on the phone, where you look at it parked.
    text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 13 * d; text.color = Ui.onPanel(t, t.route, 3.0)
    val at = if (trip.arrivedAt > 0) " · " + NavEngine.clock(trip.arrivedAt) else ""
    c.drawText(gr.gamemap.L.t("ARRIVED") + at, box.left + 18 * d, box.top + 28 * d, text)
    text.typeface = t.typeface; text.textSize = 24 * d; text.color = ink
    val maxW = w - 36 * d
    val words = trip.place.split(' ')
    var first = ""
    var k = 0
    while (k < words.size && text.measureText((first + " " + words[k]).trim()) <= maxW) { first = (first + " " + words[k]).trim(); k++ }
    if (first.isEmpty()) { first = ellipsize(trip.place, maxW); k = words.size }
    val second = words.drop(k).joinToString(" ")
    c.drawText(first, box.left + 18 * d, box.top + 58 * d, text)
    if (second.isNotEmpty()) c.drawText(ellipsize(second, maxW), box.left + 18 * d, box.top + 88 * d, text)
    text.typeface = Typeface.DEFAULT; text.textSize = 18 * d; text.color = Ui.dim(t, 210)
    c.drawText(NavEngine.formatDuration(trip.seconds.toDouble()) + " · " + NavEngine.formatDistance(trip.meters),
        box.left + 18 * d, box.top + (if (second.isNotEmpty()) 120 else 92) * d, text)
    // buttons
    val by = box.bottom - 18 * d - 56 * d
    val half = (w - 36 * d - 12 * d) / 2
    val park = RectF(box.left + 18 * d, by, box.left + 18 * d + half, by + 56 * d)
    val done = RectF(park.right + 12 * d, by, park.right + 12 * d + half, by + 56 * d)
    glassButton(c, park, d, t)
    glassButton(c, done, d, t, withAlpha(t.route, 240))
    text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 18 * d; text.textAlign = Paint.Align.CENTER
    text.color = glassInk(t)
    c.drawText(gr.gamemap.L.t("Parking"), park.centerX(), park.centerY() + 6.5f * d, text)
    text.color = if (Ui.contrast(Color.WHITE, t.route) >= 3) Color.WHITE else Color.BLACK
    c.drawText(gr.gamemap.L.t("Done"), done.centerX(), done.centerY() + 6.5f * d, text)
    text.textAlign = Paint.Align.LEFT
    text.typeface = t.typeface
    carHits += park to "parking"
    carHits += done to "done"
}

/**
 * Car: the arrival card turned into a parking list (the 3 nearest car parks), drawn by
 * GameMap itself, so it opens at once instead of waiting for an Android Auto screen.
 */
internal fun MapRenderer.drawParkingList(c: Canvas, box: RectF, d: Float, t: Theme, list: List<Place>) {
    val ink = Ui.ink(t)
    text.textAlign = Paint.Align.LEFT
    text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 15 * d; text.color = Ui.onPanel(t, t.route, 3.0)
    c.drawText("🅿  PARKING NEARBY", box.left + 18 * d, box.top + 32 * d, text)
    // ✕ close
    val x = RectF(box.right - 52 * d, box.top + 6 * d, box.right - 8 * d, box.top + 50 * d)
    text.textAlign = Paint.Align.CENTER; text.textSize = 22 * d; text.color = ink
    c.drawText("✕", x.centerX(), x.centerY() + 8 * d, text)
    carHits += x to "parkclose"
    text.textAlign = Paint.Align.LEFT
    val near = LocationTracker.position
    val rowH = 58 * d
    var y = box.top + 50 * d
    if (list.isEmpty()) {
        text.typeface = t.typeface; text.textSize = 18 * d; text.color = Ui.dim(t, 200)
        c.drawText(if (CarParking.loading) gr.gamemap.L.t("Finding car parks…") else gr.gamemap.L.t("No car parks found nearby"), box.left + 18 * d, y + 40 * d, text)
        text.typeface = t.typeface
        return
    }
    list.take(3).forEachIndexed { i, p ->
        val r = RectF(box.left + 10 * d, y, box.right - 10 * d, y + rowH - 6 * d)
        glassButton(c, r, d, t, if (i == 0) withAlpha(t.route, 60) else null)
        val dist = near?.let { NavEngine.formatDistance(Geo.distance(it, p.pos)) } ?: ""
        text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 16 * d
        val dw = text.measureText(dist)
        text.color = Ui.dim(t, 220)
        c.drawText(dist, r.right - 14 * d - dw, r.centerY() + 6 * d, text)
        text.typeface = t.typeface; text.textSize = 18 * d; text.color = ink
        c.drawText(ellipsize(p.name, r.width() - dw - 44 * d), r.left + 14 * d, r.centerY() + 6.5f * d, text)
        carHits += r to "park:$i"
        y += rowH
    }
    text.typeface = t.typeface
}
