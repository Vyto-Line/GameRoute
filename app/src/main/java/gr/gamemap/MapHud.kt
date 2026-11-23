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

internal fun MapRenderer.drawSpeed(c: Canvas, a: Rect, d: Float, t: Theme) {
    if (LocationTracker.last == null) return
    val imp = MapState.imperial
    val kmh = Math.round(LocationTracker.speedMps * (if (imp) 2.23694f else 3.6f))
    val limitKmh = SpeedLimit.now()
    val limit = limitKmh?.let { if (imp) ((it * 0.621371 / 5).roundToInt() * 5) else it }
    // over the limit: orange up to 5 km/h over, red past that (always judged in km/h)
    val level = SpeedLimit.level(Math.round(LocationTracker.speedMps * 3.6f), limitKmh)
    val over = level > 0
    val overColor = if (level == 2) RED_OVER else ORANGE_OVER
    val pad = 14 * d
    text.textAlign = Paint.Align.CENTER
    text.textSize = 30 * d
    val big = kmh.toString()
    val bw = maxOf(text.measureText(big), text.measureText("88")) + 24 * d
    val box = RectF(a.left + pad, a.bottom - pad - 58 * d, a.left + pad + bw, a.bottom - pad)
    speedRect.set(box.left, box.top, box.right + (if (limit != null) 8 * d + 42 * d else 0f), box.bottom)
    speedColor = if (over) overColor else 0
    panel(c, box, d, t, outline = over, outlineColor = if (over) overColor else null)
    text.color = if (over) overColor else Ui.ink(t)
    c.drawText(big, box.centerX(), box.top + 33 * d, text)
    text.textSize = 11 * d
    c.drawText(if (imp) "MPH" else gr.gamemap.L.t("KM/H"), box.centerX(), box.bottom - 9 * d, text)
    if (limit != null) {
        val r = 21 * d
        val sx = box.right + 8 * d + r; val sy = box.bottom - r - 4 * d
        fill.color = Color.WHITE; c.drawCircle(sx, sy, r, fill)
        stroke.color = Color.rgb(208, 2, 27); stroke.strokeWidth = 4.5f * d; c.drawCircle(sx, sy, r - 2.2f * d, stroke)
        text.color = Color.rgb(17, 20, 24); text.textSize = (if (limit >= 100) 14 else 16) * d
        val tf = text.typeface; text.typeface = Typeface.DEFAULT_BOLD
        c.drawText(limit.toString(), sx, sy + 5.5f * d, text)
        text.typeface = tf
    }
}

/** The fuel list's stations as price tags on the phone map ("D €1.74"); the cheapest is green. */
internal fun MapRenderer.drawPricePins(c: Canvas, cam: Camera, w: Int, h: Int, d: Float, t: Theme) {
    val pins = Fuel.pins
    val cheapest = pins.minByOrNull { it.second.substringAfter("€", "9").toDoubleOrNull() ?: 9.0 }
    text.textAlign = Paint.Align.CENTER; text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 12.5f * d
    for ((pos, label) in pins) {
        val (x, y) = toScreen(pos, cam)
        if (x < -60 || y < -40 || x > w + 60 || y > h + 40) continue
        val tw = text.measureText(label) + 16 * d
        val r = RectF(x - tw / 2, y - 34 * d, x + tw / 2, y - 12 * d)
        fill.color = when {
            label.startsWith("⚡") -> Color.rgb(124, 58, 237)
            cheapest != null && label == cheapest.second && pos == cheapest.first -> Color.rgb(30, 158, 90)
            else -> Color.rgb(232, 89, 12)
        }
        c.drawRoundRect(r, 10 * d, 10 * d, fill)
        path.reset(); path.moveTo(x - 5 * d, r.bottom - 1); path.lineTo(x + 5 * d, r.bottom - 1); path.lineTo(x, r.bottom + 6 * d); path.close()
        c.drawPath(path, fill)
        text.color = Color.WHITE
        c.drawText(label, x, r.centerY() + 4.5f * d, text)
    }
    text.typeface = t.typeface
}

/** "Toll in 800 m · €2.50" before a toll station on the route. */
internal fun MapRenderer.drawTollChip(c: Canvas, a: Rect, d: Float, t: Theme, price: Double, dist: Double) {
    val label = gr.gamemap.L.t("Toll in ${NavEngine.formatDistance(dist)} · ${Tolls.money(price)}")
    text.textAlign = Paint.Align.LEFT; text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 13.5f * d
    val w = text.measureText(label) + 36 * d
    val x = if (car) a.left + 12 * d else a.centerX() - w / 2
    val y = maxOf(bannerBottom, a.top.toFloat()) + 8 * d
    val r = RectF(x, y, x + w, y + 32 * d)
    fill.color = Color.argb(240, 255, 244, 214); c.drawRoundRect(r, 16 * d, 16 * d, fill)
    stroke.color = Color.rgb(230, 160, 20); stroke.strokeWidth = 1.5f * d; c.drawRoundRect(r, 16 * d, 16 * d, stroke)
    text.color = Color.rgb(120, 70, 0)
    c.drawText("€", r.left + 12 * d, r.centerY() + 5 * d, text)
    c.drawText(label, r.left + 26 * d, r.centerY() + 5 * d, text)
    text.typeface = t.typeface
    bannerBottom = r.bottom; bannerRight = r.right
}

/** Convoy race start: "Everyone's ready", then a big 3, 2, 1 and GO in the middle of the map. */
internal fun MapRenderer.drawCountdown(c: Canvas, a: Rect, d: Float, t: Theme) {
    val left = Convoy.goAt - System.currentTimeMillis()
    val n = Math.ceil(left / 1000.0).toInt()
    val label = when {
        n > 3 -> gr.gamemap.L.t("Everyone's ready!")
        n >= 1 -> n.toString()
        else -> gr.gamemap.L.t("GO!")
    }
    // each number pops in big and settles
    val f = if (left > 0) ((left % 1000) / 1000f) else ((1500 + left) / 1500f).coerceIn(0f, 1f)
    val big = n in 1..3 || n <= 0
    val scale = if (big) 1f + 0.35f * f * f else 1f
    val cx = a.exactCenterX(); val cy = a.top + a.height() * 0.42f
    val r = (if (car) 54f else 70f) * d
    fill.color = Color.argb(225, 20, 22, 28)
    if (big) c.drawCircle(cx, cy, r * scale, fill)
    val col = if (n <= 0) Color.rgb(47, 210, 122) else t.route
    stroke.color = col; stroke.strokeWidth = 5 * d
    if (big) c.drawCircle(cx, cy, r * scale - 2.5f * d, stroke)
    text.textAlign = Paint.Align.CENTER; text.typeface = Typeface.DEFAULT_BOLD
    text.color = if (n <= 0) col else Color.WHITE
    if (big) {
        text.textSize = (if (n <= 0) 34f else 64f) * d * (if (car) 0.8f else 1f) * scale
        c.drawText(label, cx, cy + text.textSize * 0.36f, text)
    } else {
        text.textSize = 20 * d
        val w = text.measureText(label) + 40 * d
        val rr = RectF(cx - w / 2, cy - 26 * d, cx + w / 2, cy + 26 * d)
        c.drawRoundRect(rr, 26 * d, 26 * d, fill)
        stroke.color = t.route; stroke.strokeWidth = 2.5f * d; c.drawRoundRect(rr, 26 * d, 26 * d, stroke)
        c.drawText("🏁 $label", cx, cy + 7 * d, text)
    }
    text.typeface = t.typeface
}

/** The menu as a side drawer from the left (55b): two columns with headings, each row with its value. */
internal fun MapRenderer.drawDrawer(c: Canvas, a: Rect, d: Float, t: Theme, p: CarPanel, ink: Int, dimInk: Int, accentInk: Int) {
    val cols = p.columns!!.filter { it.isNotEmpty() }
    val w = minOf(360 * d * cols.size, a.width() * 0.62f)
    val r = RectF(a.left.toFloat() - 20 * d, a.top.toFloat() + 6 * d, a.left + w, a.bottom - 6f * d)
    panel(c, r, d, t, outline = true)
    carHits += RectF(r) to "cp:none"   // taps on the drawer's empty parts don't reach the map
    val pad = 12 * d
    val back = RectF(a.left + pad, r.top + pad, a.left + pad + 48 * d, r.top + pad + 48 * d)
    glassButton(c, back, d, t)
    text.textAlign = Paint.Align.CENTER; text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 26 * d; text.color = ink
    c.drawText("‹", back.centerX(), back.centerY() + 9 * d, text)
    carHits += back to "cp:back"
    text.textAlign = Paint.Align.LEFT; text.typeface = t.typeface; text.textSize = 22 * d; text.color = accentInk
    c.drawText(ellipsize(p.title, w - 90 * d), back.right + 12 * d, back.centerY() + 8 * d, text)
    val top = back.bottom + 8 * d
    val colW = (r.right - a.left - pad * (cols.size + 1)) / cols.size
    for ((ci, col) in cols.withIndex()) {
        val heads = col.count { it.id.startsWith("head:") }
        val rowsN = col.size - heads
        // rows shrink a little when a column is long (context rows at the top: End navigation…)
        val rowH = ((r.bottom - pad - top - heads * 24 * d) / maxOf(1, rowsN)).coerceIn(44 * d, 60 * d)
        val x0 = a.left + pad + ci * (colW + pad)
        var y = top
        for (row in col) {
            if (row.id.startsWith("head:")) {
                text.textAlign = Paint.Align.LEFT; text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 12.5f * d; text.color = dimInk
                text.letterSpacing = 0.12f; c.drawText(row.title, x0 + 12 * d, y + 17 * d, text); text.letterSpacing = 0f
                y += 24 * d; continue
            }
            if (y + rowH > r.bottom - pad / 2) break
            val rr = RectF(x0, y, x0 + colW, y + rowH - 6 * d)
            if (row.highlight || row.moon) {
                val col2 = if (row.moon) PoiLayer.MOON else t.route
                fill.color = withAlpha(col2, 34); c.drawRoundRect(rr, 10 * d, 10 * d, fill)
                stroke.color = col2; stroke.strokeWidth = 2 * d; c.drawRoundRect(rr, 10 * d, 10 * d, stroke)
            }
            carHits += rr to "cp:" + row.id
            text.textAlign = Paint.Align.CENTER; text.typeface = Typeface.DEFAULT; text.textSize = 21 * d; text.color = ink
            c.drawText(row.icon, rr.left + 22 * d, rr.centerY() + 7.5f * d, text)
            val rightText = row.right ?: "›"
            text.textAlign = Paint.Align.RIGHT; text.typeface = Typeface.DEFAULT_BOLD
            text.textSize = if (row.right != null) 16 * d else 21 * d; text.color = if (row.right != null) accentInk else dimInk
            c.drawText(ellipsize(rightText, colW * 0.4f), rr.right - 10 * d, rr.centerY() + 6.5f * d, text)
            val rw = minOf(text.measureText(rightText), colW * 0.4f) + 20 * d
            val tx = rr.left + 44 * d; val tw = rr.right - tx - rw
            text.textAlign = Paint.Align.LEFT; text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 17 * d
            text.color = if (row.red) Color.rgb(255, 99, 99) else ink
            c.drawText(ellipsize(row.title, tw), tx, if (row.sub != null) rr.centerY() - 2 * d else rr.centerY() + 6 * d, text)
            row.sub?.let { s ->
                text.typeface = if (row.moon || row.highlight) Typeface.DEFAULT_BOLD else Typeface.DEFAULT; text.textSize = 13.5f * d
                text.color = if (row.moon) (if (Ui.contrast(PoiLayer.MOON, Ui.panel(t)) >= 3) PoiLayer.MOON else accentInk) else if (row.highlight) accentInk else dimInk
                c.drawText(ellipsize(s, tw), tx, rr.centerY() + 16 * d, text)
            }
            y += rowH
        }
    }
    text.textAlign = Paint.Align.LEFT
}

/** [s] in lines no wider than [w] (with the current text size). */
internal fun MapRenderer.wrap(s: String, w: Float): List<String> {
    val out = ArrayList<String>(); var line = ""
    for (word in s.split(' ')) {
        val tryLine = if (line.isEmpty()) word else "$line $word"
        if (text.measureText(tryLine) <= w) line = tryLine else { if (line.isNotEmpty()) out += line; line = word }
    }
    if (line.isNotEmpty()) out += line
    return out
}

/** At the convoy start after pressing Ready: who we're still waiting for. */
internal fun MapRenderer.drawLobbyChip(c: Canvas, a: Rect, d: Float, t: Theme) {
    val lobby = Convoy.lobby()
    if (lobby.isEmpty()) return
    val waiting = lobby.filter { !it.ready }.map { it.name }
    val label = gr.gamemap.L.t("Ready ${lobby.count { it.ready } + 1}/${lobby.size + 1}") +
        (if (waiting.isEmpty()) "" else " · " + gr.gamemap.L.t("waiting for ") + waiting.joinToString(", "))
    text.textAlign = Paint.Align.LEFT; text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 13.5f * d
    val shown = ellipsize(label, a.width() - 80 * d)
    val w = text.measureText(shown) + 46 * d
    val x = if (car) a.left + 12 * d else a.centerX() - w / 2
    val y = maxOf(bannerBottom, a.top.toFloat()) + 8 * d
    val r = RectF(x, y, x + w, y + 32 * d)
    fill.color = Color.argb(235, 40, 44, 52); c.drawRoundRect(r, 16 * d, 16 * d, fill)
    stroke.color = Color.rgb(47, 210, 122); stroke.strokeWidth = 1.5f * d; c.drawRoundRect(r, 16 * d, 16 * d, stroke)
    text.color = Color.WHITE
    c.drawText("🏁", r.left + 10 * d, r.centerY() + 5 * d, text)
    c.drawText(shown, r.left + 34 * d, r.centerY() + 5 * d, text)
    text.typeface = t.typeface
    bannerBottom = r.bottom; bannerRight = r.right
}

/** The road you're on, in a small label just under your arrow (under the Weak GPS note when that shows). */
internal fun MapRenderer.drawCurrentRoad(c: Canvas, a: Rect, d: Float, t: Theme, px: Float, py: Float, weak: Boolean) {
    val now = System.currentTimeMillis()
    if (now - roadAt > 1000) { roadAt = now; roadName = try { NavEngine.currentRoad() } catch (_: Exception) { "" } }
    val name = roadName
    if (name.isBlank()) return
    // slim, so it hides as little of the map's own street names as it can
    text.textAlign = Paint.Align.CENTER; text.textSize = (if (car) 15f else 13.5f) * d
    val tf = text.typeface; text.typeface = t.typeface
    val shown = ellipsize(name, minOf(360 * d, a.width() * 0.5f))
    val w = text.measureText(shown) + 22 * d
    val h = (if (car) 29 else 25) * d
    val top = (py + (if (weak) 72 else 32) * d).coerceAtMost(a.bottom - h - 4 * d)
    var left = (px - w / 2).coerceIn(a.left + 6 * d, a.right - 6 * d - w)
    var r = RectF(left, top, left + w, top + h)
    // never under End or the arrival box (small car screens: End sat on the name, 22 Nov)
    val blockers = listOfNotNull(endAt.takeIf { !it.isEmpty }, etaAt.takeIf { !it.isEmpty })
    if (blockers.any { RectF.intersects(it, r) }) {
        val right = blockers.maxOf { it.right } + 8 * d
        r = if (right + w < a.right - 80 * d) RectF(right, top, right + w, top + h)
            else RectF(left, py - 46 * d - h, left + w, py - 46 * d)   // else above the arrow
    }
    roadNameRect.set(r)
    panel(c, r, d, t, outline = false)
    text.color = Ui.ink(t)
    c.drawText(shown, r.centerX(), r.centerY() + text.textSize * 0.35f, text)
    text.typeface = tf; text.textAlign = Paint.Align.LEFT
}

/** Weak GPS: a dashed amber ring round the arrow and a small note under it. */
internal fun MapRenderer.drawWeakGps(c: Canvas, x: Float, y: Float, d: Float, a: Rect) {
    val amber = Color.rgb(255, 179, 71)
    stroke.color = amber; stroke.strokeWidth = 1.6f * d
    stroke.pathEffect = android.graphics.DashPathEffect(floatArrayOf(6 * d, 5 * d), 0f)
    c.drawCircle(x, y, 30 * d, stroke)
    stroke.pathEffect = null
    fill.color = Color.argb(46, 255, 179, 71); c.drawCircle(x, y, 30 * d, fill)
    val label = gr.gamemap.L.t("Weak GPS · position may be off")
    val tf = text.typeface
    text.textAlign = Paint.Align.LEFT; text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 12.5f * d
    val w = text.measureText(label) + 26 * d
    val left = (x - w / 2).coerceIn(a.left + 6 * d, a.right - 6 * d - w)
    val top = y + 38 * d
    val r = RectF(left, top, left + w, top + 26 * d)
    fill.color = Color.argb(235, 30, 26, 44); c.drawRoundRect(r, 13 * d, 13 * d, fill)
    stroke.color = amber; stroke.strokeWidth = 1.2f * d; c.drawRoundRect(r, 13 * d, 13 * d, stroke)
    text.color = amber; c.drawText(label, r.left + 13 * d, r.centerY() + 4.3f * d, text)
    text.typeface = tf
}

internal fun MapRenderer.drawNoGps(c: Canvas, a: Rect, d: Float, t: Theme, message: String = "No GPS: estimating") {
    val label = gr.gamemap.L.t(message)
    text.textAlign = Paint.Align.LEFT; text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 13 * d
    val w = text.measureText(label) + 34 * d
    val x = if (car) a.left + 12 * d else a.centerX() - w / 2
    val y = maxOf(bannerBottom, a.top.toFloat()) + 8 * d
    val r = RectF(x, y, x + w, y + 30 * d)
    fill.color = Color.argb(235, 40, 44, 52); c.drawRoundRect(r, 15 * d, 15 * d, fill)
    fill.color = Color.rgb(250, 176, 5); c.drawCircle(r.left + 15 * d, r.centerY(), 4.5f * d, fill)
    text.color = Color.WHITE; c.drawText(label, r.left + 25 * d, r.centerY() + 4.5f * d, text)
    text.typeface = t.typeface
    bannerBottom = r.bottom; bannerRight = r.right
}

internal fun MapRenderer.arrowFrac(target: Float): Float {
    if (shownArrowFrac < 0f || Math.abs(target - shownArrowFrac) < 0.002f) shownArrowFrac = target
    else { shownArrowFrac += (target - shownArrowFrac) * 0.06f; animating = true }
    return shownArrowFrac
}

internal fun MapRenderer.carConvoyRectForTest() = RectF(carConvoyRect)

internal fun MapRenderer.drawConvoyMembers(c: Canvas, cam: Camera, a: Rect, d: Float, bearing: Float) {
    val now = System.currentTimeMillis()
    val hits = ArrayList<Pair<RectF, Convoy.Member>>()
    friendGliding = false
    for (m in Convoy.members.values) {
        if (m.state == "left") continue
        if (car && m.sharing) continue // only sharing their location: not on the car screen
        val p = m.display(now) ?: continue
        val (x, y) = toScreen(p, cam)
        if (x < -40 * d || y < -40 * d || x > a.right + 40 * d || y > a.bottom + 40 * d) continue
        if (m.moving) friendGliding = true
        c.save(); c.translate(x, y); c.rotate(m.shownHeading - bearing)
        val s = 12 * d
        path.reset()
        path.moveTo(0f, -s * 1.2f); path.lineTo(s * 0.85f, s * 0.85f); path.lineTo(0f, s * 0.4f); path.lineTo(-s * 0.85f, s * 0.85f); path.close()
        // signal lost for a while: the arrow fades (it comes back with their next update)
        val lost = m.lostSecs
        val fade = if (m.stale) 255 else if (lost > 0) 110 else 255
        fill.color = if (m.stale) Color.GRAY else m.color; fill.alpha = fade; c.drawPath(path, fill)
        stroke.color = Color.WHITE; stroke.alpha = fade; stroke.strokeWidth = 2.2f * d; c.drawPath(path, stroke)
        fill.alpha = 255; stroke.alpha = 255
        c.restore()
        // name tag
        val label = "${m.name}  ${if (m.state == "arrived") "✓" else if (lost > 0 && !m.stale) gr.gamemap.L.t(Convoy.lostText(lost)) else m.kmh.toString()}"
        text.textAlign = Paint.Align.LEFT; text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 12.5f * d
        val w = text.measureText(label) + 14 * d
        val r = RectF(x + 12 * d, y - 26 * d, x + 12 * d + w, y - 6 * d)
        // keep the tag on screen: flip to the left side near the right edge, below near the top
        if (r.right > a.right - 6 * d) r.offset(-(w + 24 * d), 0f)
        if (r.left < a.left + 6 * d) r.offset(a.left + 6 * d - r.left, 0f)
        if (r.top < a.top + 6 * d) r.offset(0f, 32 * d)
        fill.color = if (m.stale) Color.GRAY else m.color; c.drawRoundRect(r, 10 * d, 10 * d, fill)
        text.color = Color.rgb(17, 20, 24)
        c.drawText(label, r.left + 7 * d, r.centerY() + 4.5f * d, text)
        // taps: the arrow (generously) and the name tag
        hits += RectF(x - 24 * d, y - 24 * d, x + 24 * d, y + 24 * d) to m
        hits += RectF(r).apply { inset(-6 * d, -6 * d) } to m
    }
    friendHits = hits
}

/** The convoy friend under a screen point (their arrow or name tag), if any. */
internal fun MapRenderer.friendAt(x: Float, y: Float): Convoy.Member? = friendHits.lastOrNull { it.first.contains(x, y) }?.second

/** A quick message from the convoy ("Nikos: Need fuel"), for 8 s under the convoy box or strip. */
internal fun MapRenderer.drawConvoyMessage(c: Canvas, a: Rect, d: Float, t: Theme) {
    val (who, key, _) = Convoy.showMessage() ?: return
    val label = "$who: ${Convoy.messageText(key)}"
    text.textAlign = Paint.Align.LEFT; text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 15 * d
    val w = text.measureText(label) + 44 * d
    var top = maxOf(bannerBottom, convoyStrip.bottom, carConvoyBottom) + 8 * d
    val left = if (car) a.left + 12 * d else a.exactCenterX() - w / 2
    // the car: below Where to? and the Home / Work chips too (it sat on the chips, 22 Nov)
    if (car) {
        var moved = true; var guard = 0
        while (moved && guard++ < 8) {
            moved = false
            for ((hr, _) in carHits) if (hr.left < left + w && hr.right > left && hr.top < top + 36 * d && hr.bottom > top) { top = hr.bottom + 8 * d; moved = true }
        }
    }
    val r = RectF(left, top, left + w, top + 36 * d)
    messageAt.set(r)
    panel(c, r, d, t, outline = true)
    fill.color = t.route; c.drawCircle(r.left + 18 * d, r.centerY(), 6 * d, fill)
    text.color = Ui.ink(t); c.drawText(label, r.left + 32 * d, r.centerY() + 5 * d, text)
    bannerBottom = r.bottom; bannerRight = r.right
    animating = true // it goes away by itself after 8 s
}

/**
 * Phone: the convoy button under the turn card: my place in the race (or "Together"), who's
 * in it, and the nearest gap. A tap opens the convoy panel.
 */
internal fun MapRenderer.drawConvoyStrip(c: Canvas, a: Rect, d: Float, t: Theme) {
    val rows = Convoy.rows(t.route)
    val racing = !Convoy.meAlong && !Convoy.meSharing && rows.any { !it.me && !it.along }
    val racers = rows.filter { !it.along }
    val place = racers.indexOfFirst { it.me } + 1
    val head = when {
        racing && place > 0 -> "🏁 " + gr.gamemap.L.t(when (place) { 1 -> "1st"; 2 -> "2nd"; 3 -> "3rd"; else -> "${place}th" })
        else -> "🚗 " + gr.gamemap.L.t("Together")
    }
    // the nearest one (by the gap along the route), else the first other
    val others = rows.filter { !it.me }
    // in a race: the nearest racer (the ones driving together don't count for the gap)
    val near = others.filter { it.state != "arrived" && !it.stale && (!racing || !it.along) }.minByOrNull { Convoy.gapTo(it)?.let { g -> Math.abs(g) } ?: Double.MAX_VALUE } ?: others.firstOrNull()
    val gap = near?.let { it.name + " " + gr.gamemap.L.t(Convoy.relative(it)) }.orEmpty()
    val h = 40 * d; val pad = 12 * d
    text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 14 * d
    val headW = text.measureText(head)
    val av = 24 * d; val avStep = 18 * d
    val shown = others.take(4)
    val avW = if (shown.isEmpty()) 0f else av + (shown.size - 1) * avStep
    text.typeface = Typeface.DEFAULT; text.textSize = 13 * d
    val maxW = a.width() - 24 * d
    val fixed = pad + headW + 17 * d + avW + (if (avW > 0) 8 * d else 0f) + pad
    val gapText = ellipsize(gap, (maxW - fixed).coerceAtLeast(0f))
    val w = minOf(maxW, fixed + text.measureText(gapText))
    val r = RectF(a.left + 12 * d, bannerBottom + 8 * d, a.left + 12 * d + w, bannerBottom + 8 * d + h)
    panel(c, r, d, t, outline = true)
    val cy = r.centerY()
    var x = r.left + pad
    text.textAlign = Paint.Align.LEFT; text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 14 * d
    text.color = Ui.onPanel(t, t.route, 3.0)
    c.drawText(head, x, cy + 5 * d, text); x += headW + 8 * d
    fill.color = Ui.dim(t, 70); c.drawRect(x, cy - 10 * d, x + 1 * d, cy + 10 * d, fill); x += 9 * d
    // avatars, overlapping a little
    for ((i, row) in shown.withIndex()) {
        val cx = x + av / 2 + i * avStep
        fill.color = if (row.stale) Color.GRAY else row.color
        c.drawCircle(cx, cy, av / 2, fill)
        stroke.color = Ui.panel(t); stroke.strokeWidth = 2 * d; c.drawCircle(cx, cy, av / 2, stroke)
        text.textAlign = Paint.Align.CENTER; text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 11 * d; text.color = Color.rgb(17, 20, 24)
        c.drawText(row.name.trim().split(" ").last().take(1).uppercase().ifBlank { "?" }, cx, cy + 4 * d, text)
    }
    x += avW + (if (avW > 0) 8 * d else 0f)
    text.textAlign = Paint.Align.LEFT; text.typeface = Typeface.DEFAULT; text.textSize = 13 * d; text.color = Ui.dim(t, 210)
    c.drawText(gapText, x, cy + 5 * d, text)
    text.typeface = t.typeface
    convoyStrip.set(r)
    bannerBottom = r.bottom; bannerRight = maxOf(bannerRight, r.right)
}

internal fun MapRenderer.drawBanner(c: Canvas, a: Rect, d: Float, t: Theme, pad: Float = 12 * d) {
    if (!NavEngine.active) return
    val step = NavEngine.currentStep
    // update 100: the pace-note turn box (every style), whenever there is a turn to show
    if (!NavEngine.arrived && !NavEngine.loading && NavEngine.error == null && step != null) { drawPaceNote(c, a, d, t, pad, step); return }
    val (title, sub) = when {
        NavEngine.arrived -> Explorer.completeTitle(t).uppercase() to NavEngine.destinationName
        NavEngine.error != null -> (NavEngine.error ?: "") to NavEngine.destinationName
        NavEngine.loading && NavEngine.rerouting -> gr.gamemap.L.t("New route…") to NavEngine.destinationName
        NavEngine.loading || step == null -> gr.gamemap.L.t("Plotting route…") to NavEngine.destinationName
        else -> {
            val st = step!!
            NavEngine.formatDistance(NavEngine.distanceToNext) to
                (if (st.road.isNotBlank() && st.type != "arrive") Names.shown(st.road) else st.text)
        }
    }
    // the small line ("Turn left", "Keep right") only when it adds something: never a bare
    // "Continue" under a straight arrow
    val plain = step != null && (step.type in setOf("continue", "new name", "depart") || step.direction == "straight")
    val detail = if (!NavEngine.arrived && !NavEngine.loading && NavEngine.error == null && step != null && !plain &&
        step.road.isNotBlank() && step.type != "arrive") step.text.substringBefore(" onto ") else null
    val lanes = step?.lanes?.takeIf { NavEngine.distanceToNext < 900 && !NavEngine.loading }
    val mainH = if (detail != null) 96 * d else 80 * d
    val lanesH = if (lanes != null) 44 * d else 0f
    val box = RectF(a.left + pad, a.top + pad, minOf(a.right - pad, a.left + pad + 440 * d), a.top + pad + mainH + lanesH)
    panel(c, box, d, t, outline = true)

    val iconX = box.left + 42 * d; val iconY = box.top + mainH / 2
    when {
        NavEngine.arrived -> drawTurnArrow(c, iconX, iconY, 22 * d, "arrive", t)
        !NavEngine.loading && NavEngine.error == null && step != null -> drawTurnArrow(c, iconX, iconY, 22 * d, step.direction, t)
    }
    val tx = box.left + 82 * d
    val maxW = box.right - tx - 12 * d
    text.textAlign = Paint.Align.LEFT
    text.color = Ui.ink(t)
    // the title shrinks to fit the card (long words like "MISSION COMPLETE" in wide fonts)
    text.textSize = 30 * d
    val tw = text.measureText(title)
    if (tw > maxW) text.textSize = (30 * d * maxW / tw).coerceAtLeast(18 * d)
    c.drawText(ellipsize(title, maxW), tx, box.top + 36 * d, text)
    text.textSize = 18 * d
    text.typeface = if (t.serif) Typeface.create(Typeface.SERIF, Typeface.BOLD) else Typeface.DEFAULT_BOLD
    c.drawText(ellipsize(sub, maxW), tx, box.top + 62 * d, text)
    if (detail != null) {
        text.textSize = 14 * d
        text.typeface = if (t.serif) Typeface.create(Typeface.SERIF, Typeface.NORMAL) else Typeface.DEFAULT
        text.color = Ui.dim(t, 190)
        c.drawText(ellipsize(detail, maxW), tx, box.top + 84 * d, text)
    }
    text.typeface = t.typeface
    if (lanes != null) drawLanes(c, RectF(box.left, box.top + mainH, box.right, box.bottom), lanes, step?.direction ?: "straight", d, t)
    bannerBottom = box.bottom

    // "Then …" tab under the banner
    val then = NavEngine.followingStep
    if (then != null && !NavEngine.arrived) {
        text.textSize = 15 * d
        text.typeface = Typeface.DEFAULT_BOLD
        val label = ellipsize(if (then.road.isNotBlank() && then.type != "arrive") Names.shown(then.road) else then.text, box.width() - 110 * d)
        val w = text.measureText(label) + 82 * d
        val tab = RectF(box.left + 14 * d, box.bottom - 2 * d, box.left + 14 * d + w, box.bottom + 38 * d)
        panel(c, tab, d, t, outline = false)
        text.color = Ui.dim(t, 190)
        c.drawText(gr.gamemap.L.t("Then"), tab.left + 12 * d, tab.centerY() + 5.5f * d, text)
        drawTurnArrow(c, tab.left + 62 * d, tab.centerY(), 9 * d, then.direction, t)
        text.color = Ui.ink(t)
        c.drawText(label, tab.left + 76 * d, tab.centerY() + 5.5f * d, text)
        text.typeface = t.typeface
        bannerBottom = tab.bottom
    }
}

/**
 * The turn box, rally pace-note style, in the style's own panel, colours and letters:
 * [distance + trip km] [a sketch of the junction, the way out in the route colour]
 * [EXIT 2 / TURN RIGHT, then the road you turn into], and the next turn on a line underneath
 * when it comes soon after. Lanes, when known, under it.
 */
internal fun MapRenderer.drawPaceNote(c: Canvas, a: Rect, d: Float, t: Theme, pad: Float, step: NavStep) {
    val then = NavEngine.followingStep
    val lanes = step.lanes?.takeIf { NavEngine.distanceToNext < 900 }
    val mainH = 96 * d
    val thenH = if (then != null) 30 * d else 0f
    val lanesH = if (lanes != null) 44 * d else 0f
    val box = RectF(a.left + pad, a.top + pad, minOf(a.right - pad, a.left + pad + 400 * d), a.top + pad + mainH + thenH + lanesH)
    panel(c, box, d, t, outline = true)
    val ink = Ui.ink(t)
    val accent = Ui.onPanel(t, t.route, 3.0)
    val line = withAlpha(ink, 60)
    val c1 = box.left + 80 * d; val c2 = c1 + 96 * d
    stroke.color = line; stroke.strokeWidth = 1 * d
    c.drawLine(c1, box.top + 8 * d, c1, box.top + mainH - 8 * d, stroke)
    c.drawLine(c2, box.top + 8 * d, c2, box.top + mainH - 8 * d, stroke)
    if (then != null || lanes != null) c.drawLine(box.left + 8 * d, box.top + mainH, box.right - 8 * d, box.top + mainH, stroke)
    // 1) distance and the trip counter
    val dist = NavEngine.formatDistance(NavEngine.distanceToNext)
    val num = dist.substringBeforeLast(' '); val unit = dist.substringAfterLast(' ', "")
    text.textAlign = Paint.Align.CENTER; text.typeface = t.typeface
    text.textSize = 32 * d; text.color = accent
    val nw = text.measureText(num); if (nw > 70 * d) text.textSize = 32 * d * 70 * d / nw
    c.drawText(num, (box.left + c1) / 2, box.top + 44 * d, text)
    text.textSize = 14 * d; text.color = ink
    c.drawText(unit, (box.left + c1) / 2, box.top + 62 * d, text)
    text.textSize = 12 * d; text.color = Ui.dim(t, 170)
    c.drawText(hundredthsKm(NavEngine.tripSoFar), (box.left + c1) / 2, box.top + 84 * d, text)
    // 2) the junction
    drawJunction(c, (c1 + c2) / 2, box.top + mainH / 2, 78 * d, step, ink, accent, d)
    // 3) what to do, and the road you turn into
    val tx = c2 + 12 * d; val maxW = box.right - tx - 10 * d
    val roundabout = (step.type == "roundabout" || step.type == "rotary") && step.exit in 1..12
    val what = (if (roundabout) gr.gamemap.L.t("Exit ${step.exit}") else step.text.substringBefore(" onto ").substringBefore(" στην ")).uppercase()
    val road = step.road.takeIf { it.isNotBlank() && step.type != "arrive" }?.let { Names.shown(it) } ?: step.text
    text.textAlign = Paint.Align.LEFT
    text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 12 * d; text.color = Ui.dim(t, 190)
    c.drawText(ellipsize(what, maxW), tx, box.top + 30 * d, text)
    text.typeface = t.typeface; text.color = ink
    text.textSize = 26 * d
    val rw = text.measureText(road); if (rw > maxW) text.textSize = (26 * d * maxW / rw).coerceAtLeast(17 * d)
    c.drawText(ellipsize(road, maxW), tx, box.top + 64 * d, text)
    // 4) the next turn, soon after
    if (then != null) {
        val y = box.top + mainH + thenH / 2
        text.textSize = 14 * d; text.typeface = Typeface.DEFAULT_BOLD
        text.color = Ui.dim(t, 170)
        c.drawText(gr.gamemap.L.t("Then"), box.left + 14 * d, y + 5 * d, text)
        val gap = NavEngine.followingGap?.let { NavEngine.formatDistance(it) } ?: ""
        text.color = ink
        val gx = box.left + 14 * d + text.measureText(gr.gamemap.L.t("Then")) + 10 * d
        c.drawText(gap, gx, y + 5 * d, text)
        val sx = gx + text.measureText(gap) + 16 * d
        drawJunction(c, sx, y, 24 * d, then, ink, ink, d, mini = true)
        val label = (if (then.road.isNotBlank() && then.type != "arrive") Names.shown(then.road) else then.text)
        c.drawText(ellipsize(label, box.right - sx - 24 * d), sx + 16 * d, y + 5 * d, text)
    }
    text.typeface = t.typeface
    if (lanes != null) drawLanes(c, RectF(box.left, box.top + mainH + thenH, box.right, box.bottom), lanes, step.direction, d, t)
    bannerBottom = box.bottom
}

/** Compass angle (0 = straight on, + to the right) of the way out of a junction. */
internal fun MapRenderer.exitAngle(s: NavStep): Float {
    if ((s.type == "roundabout" || s.type == "rotary") && s.exit in 1..12) return (90f - (s.exit - 1) * 90f).coerceAtLeast(-150f)
    val dir = s.direction
    return when {
        dir == "uturn" -> 180f
        dir.contains("sharp left") -> -135f
        dir.contains("slight left") -> -45f
        dir.contains("left") -> -90f
        dir.contains("sharp right") -> 135f
        dir.contains("slight right") -> 45f
        dir.contains("right") -> 90f
        else -> 0f
    }
}

/**
 * A rally roadbook sketch: you come in from the bottom (a dot), the junction in the middle (a
 * ring for a roundabout), the other ways faint, the way out in [accent] with an arrowhead.
 */
internal fun MapRenderer.drawJunction(c: Canvas, cx: Float, cy: Float, size: Float, s: NavStep, ink: Int, accent: Int, d: Float, mini: Boolean = false) {
    val h = size / 2
    val roundabout = (s.type == "roundabout" || s.type == "rotary") && s.exit in 1..12
    val arrive = s.direction == "arrive"
    val jx = cx; val jy = cy - h * 0.12f
    val w = if (mini) 2.4f * d else 4f * d
    stroke.strokeCap = Paint.Cap.ROUND; stroke.strokeJoin = Paint.Join.ROUND
    // the way in
    stroke.color = ink; stroke.strokeWidth = w
    c.drawLine(cx, cy + h * 0.78f, jx, jy + (if (roundabout) h * 0.28f else 0f), stroke)
    fill.color = ink; c.drawCircle(cx, cy + h * 0.78f, w * 1.3f, fill)
    if (arrive) { fill.color = accent; c.drawCircle(jx, jy, w * 2.2f, fill); stroke.strokeCap = Paint.Cap.BUTT; return }
    val out = exitAngle(s)
    // the other ways, faint (not for a mini sketch)
    if (!mini) {
        stroke.color = withAlpha(ink, 90); stroke.strokeWidth = w * 0.6f
        for (o in if (roundabout) listOf(-90f, 0f, 90f) else listOf(-90f, 0f, 90f)) {
            if (kotlin.math.abs(o - out) < 20) continue
            val r0 = if (roundabout) h * 0.28f else 0f
            val rad = Math.toRadians(o.toDouble())
            c.drawLine(jx + (sin(rad) * r0).toFloat(), jy - (cos(rad) * r0).toFloat(), jx + (sin(rad) * h * 0.7f).toFloat(), jy - (cos(rad) * h * 0.7f).toFloat(), stroke)
        }
    }
    if (roundabout) { stroke.color = ink; stroke.strokeWidth = w * 0.8f; c.drawCircle(jx, jy, h * 0.28f, stroke) }
    // the way out
    stroke.color = accent; stroke.strokeWidth = w * 1.4f
    if (out == 180f) {
        path.reset(); path.moveTo(jx + h * 0.2f, jy); path.cubicTo(jx + h * 0.2f, jy - h * 0.6f, jx - h * 0.35f, jy - h * 0.6f, jx - h * 0.35f, jy + h * 0.3f)
        stroke.style = Paint.Style.STROKE; c.drawPath(path, stroke)
        stroke.strokeCap = Paint.Cap.BUTT; return
    }
    val rad = Math.toRadians(out.toDouble())
    val r0 = if (roundabout) h * 0.28f else 0f
    val sx = jx + (sin(rad) * r0).toFloat(); val sy = jy - (cos(rad) * r0).toFloat()
    val ex = jx + (sin(rad) * h * 0.78f).toFloat(); val ey = jy - (cos(rad) * h * 0.78f).toFloat()
    c.drawLine(sx, sy, ex, ey, stroke)
    // arrowhead
    val ah = (if (mini) 4f else 9f) * d
    val l = rad - Math.toRadians(150.0); val r = rad + Math.toRadians(150.0)
    path.reset()
    path.moveTo(ex + (sin(l) * ah).toFloat(), ey - (cos(l) * ah).toFloat()); path.lineTo(ex, ey); path.lineTo(ex + (sin(r) * ah).toFloat(), ey - (cos(r) * ah).toFloat())
    c.drawPath(path, stroke)
    stroke.strokeCap = Paint.Cap.BUTT; stroke.strokeJoin = Paint.Join.MITER
}

internal fun MapRenderer.ellipsize(s: String, maxW: Float): String {
    if (text.measureText(s) <= maxW) return s
    // too long: shortened once, then remembered (it was redone letter by letter every frame)
    val key = EllKey(s, maxW.toInt(), text.textSize, text.typeface)
    ellMemo[key]?.let { return it }
    var line = s
    while (line.length > 3 && text.measureText(line) > maxW) line = line.dropLast(2) + "…"
    if (ellMemo.size > 300) ellMemo.clear()
    ellMemo[key] = line
    return line
}

/** "0.09 km", remade only when it changes (every 10 m). */
internal fun MapRenderer.hundredthsKm(m: Double): String {
    val h = Math.round(m / 10)
    if (h != tripTextM) { tripTextM = h; tripText = "${h / 100}.${(h % 100).toString().padStart(2, '0')} km" }
    return tripText
}

/** One small arrow per lane: bright for lanes to use, faint for the others. */
internal fun MapRenderer.drawLanes(c: Canvas, r: RectF, lanes: List<Lane>, maneuver: String, d: Float, t: Theme) {
    fill.color = withAlpha(Ui.ink(t), 22)
    c.drawRect(r.left + 8 * d, r.top, r.right - 8 * d, r.bottom - 6 * d, fill)
    val cell = minOf(40 * d, (r.width() - 24 * d) / lanes.size)
    var x = r.centerX() - cell * lanes.size / 2 + cell / 2
    val y = r.centerY() - 3 * d
    for (lane in lanes) {
        val dir = lane.directions.firstOrNull { lane.valid && it == maneuver }
            ?: lane.directions.firstOrNull { lane.valid && it != "none" }
            ?: lane.directions.firstOrNull() ?: "straight"
        drawLaneArrow(c, x, y, 13 * d, if (dir == "none") "straight" else dir, if (lane.valid) Ui.ink(t) else withAlpha(Ui.ink(t), 70), d)
        x += cell
    }
}

internal fun MapRenderer.drawLaneArrow(c: Canvas, x: Float, y: Float, s: Float, dir: String, color: Int, d: Float) {
    stroke.color = color; stroke.strokeWidth = 3 * d; stroke.strokeCap = Paint.Cap.ROUND; stroke.strokeJoin = Paint.Join.ROUND
    if (dir == "uturn") { drawUTurn(c, x, y, s, stroke); stroke.strokeCap = Paint.Cap.BUTT; stroke.strokeJoin = Paint.Join.MITER; return }
    val ang = when {
        dir.contains("sharp left") -> -135f
        dir.contains("slight left") -> -40f
        dir.contains("left") -> -90f
        dir.contains("sharp right") -> 135f
        dir.contains("slight right") -> 40f
        dir.contains("right") -> 90f
        else -> 0f
    }
    path.reset()
    path.moveTo(x, y + s)
    if (ang == 0f) {
        path.lineTo(x, y - s)
        c.drawPath(path, stroke)
        path.reset(); path.moveTo(x - s * 0.45f, y - s * 0.5f); path.lineTo(x, y - s); path.lineTo(x + s * 0.45f, y - s * 0.5f)
    } else {
        path.lineTo(x, y)
        val a = Math.toRadians((ang - 90).toDouble())
        val ex = x + (s * cos(a)).toFloat(); val ey = y + (s * sin(a)).toFloat()
        path.lineTo(ex, ey)
        c.drawPath(path, stroke)
        val back = a + Math.PI
        path.reset()
        path.moveTo(ex + (s * 0.5f * cos(back + 0.6)).toFloat(), ey + (s * 0.5f * sin(back + 0.6)).toFloat())
        path.lineTo(ex, ey)
        path.lineTo(ex + (s * 0.5f * cos(back - 0.6)).toFloat(), ey + (s * 0.5f * sin(back - 0.6)).toFloat())
    }
    c.drawPath(path, stroke)
    stroke.strokeCap = Paint.Cap.BUTT; stroke.strokeJoin = Paint.Join.MITER
}

internal fun MapRenderer.alertIcon(c: Canvas, x: Float, y: Float, glyph: String, ring: Int, d: Float) {
    fill.color = Color.WHITE; c.drawCircle(x, y, 14 * d, fill)
    stroke.color = ring; stroke.strokeWidth = 3 * d; c.drawCircle(x, y, 13 * d, stroke)
    text.textAlign = Paint.Align.CENTER; text.textSize = 15 * d; text.color = Color.BLACK
    c.drawText(glyph, x, y + 5.5f * d, text)
}

/** Speed cameras, TomTom incidents and drivers' reports as small round icons. */
internal fun MapRenderer.drawRoadAlerts(c: Canvas, cam: Camera, center: LatLng, a: Rect, d: Float) {
    val mpp = Geo.metersPerPixel(center.lat, cam.world) / cam.scale
    val radius = kotlin.math.hypot(a.width().toDouble(), a.height().toDouble()) * mpp
    fun visit(p: LatLng, glyph: String, ring: Int) {
        if (Geo.distance(center, p) > radius) return
        val (x, y) = toScreen(p, cam)
        if (x < a.left - 20 || x > a.right + 20 || y < a.top - 20 || y > a.bottom + 20) return
        alertIcon(c, x, y, glyph, ring, d)
    }
    val tf = text.typeface
    text.typeface = Typeface.DEFAULT
    for (cm in Alerts.cameras(center)) visit(cm.pos, cm.glyph, if (cm.redLight) Color.rgb(220, 50, 50) else Color.rgb(30, 110, 230))
    for (i in Alerts.incidents) visit(i.pos, Alerts.incidentGlyph(i.category), Color.rgb(220, 50, 50))
    for (r in Alerts.reports) visit(r.pos, Alerts.typeOf(r.type).glyph, Color.rgb(255, 150, 0))
    text.typeface = tf
}

/** Trailblazer: landmarks to discover (glowing) and ones already found (gold). */
internal fun MapRenderer.drawLandmarks(c: Canvas, cam: Camera, center: LatLng, a: Rect, d: Float) {
    val mpp = Geo.metersPerPixel(center.lat, cam.world) / cam.scale
    val radius = kotlin.math.hypot(a.width().toDouble(), a.height().toDouble()) * mpp
    // the nearest few, so a busy city centre doesn't turn into a sea of markers
    val list = Explorer.near(center, radius).sortedBy { Geo.distance(center, it.pos) }.take(10)
    if (list.isEmpty()) return
    val pulse = 0.35f
    text.textAlign = Paint.Align.CENTER
    val tf = text.typeface
    text.typeface = Typeface.DEFAULT_BOLD
    for (l in list) {
        val (x, y) = toScreen(l.pos, cam)
        if (x < a.left - 30 || x > a.right + 30 || y < a.top - 30 || y > a.bottom + 30) continue
        if (Explorer.isFound(l)) {
            fill.color = Color.rgb(255, 196, 0); c.drawCircle(x, y, 9 * d, fill)
            stroke.color = Color.WHITE; stroke.strokeWidth = 2 * d; c.drawCircle(x, y, 9 * d, stroke)
            stroke.color = Color.rgb(90, 60, 0); stroke.strokeWidth = 2 * d
            c.drawLine(x - 4 * d, y, x - 1 * d, y + 3 * d, stroke); c.drawLine(x - 1 * d, y + 3 * d, x + 4.5f * d, y - 3.5f * d, stroke)
        } else {
            fill.color = Color.argb((110 * (1 - pulse)).toInt(), 0, 170, 255)
            c.drawCircle(x, y, (14 + 16 * pulse) * d, fill)
            fill.color = Color.rgb(0, 120, 255); c.drawCircle(x, y, 13 * d, fill)
            stroke.color = Color.WHITE; stroke.strokeWidth = 2.5f * d; c.drawCircle(x, y, 13 * d, stroke)
            text.color = Color.WHITE; text.textSize = 15 * d
            c.drawText("?", x, y + 5.5f * d, text)
        }
    }
    text.typeface = tf
}

/** "Landmark discovered · +25 XP" for a few seconds after passing one. */
internal fun MapRenderer.drawDiscovery(c: Canvas, a: Rect, d: Float, t: Theme) {
    val l = Explorer.lastFound ?: return
    val age = android.os.SystemClock.uptimeMillis() - Explorer.lastFoundAt
    if (age > 4500) return
    animating = true
    val alpha = if (age > 3800) ((4500 - age) / 700f).coerceIn(0f, 1f) else 1f
    text.textAlign = Paint.Align.LEFT
    text.typeface = Typeface.DEFAULT_BOLD
    text.textSize = 15 * d
    val title = gr.gamemap.L.t("Landmark discovered · +25 XP")
    val name = ellipsize(l.name, a.width() - 120 * d)
    val w = maxOf(text.measureText(title), text.measureText(name)) + 74 * d
    val h = 58 * d
    val left = a.exactCenterX() - w / 2
    val top = a.top + a.height() * 0.30f
    val r = RectF(left, top, left + w, top + h)
    fill.color = Color.argb((235 * alpha).toInt(), 12, 40, 90)
    c.drawRoundRect(r, 16 * d, 16 * d, fill)
    stroke.color = Color.argb((255 * alpha).toInt(), 255, 196, 0); stroke.strokeWidth = 2 * d
    c.drawRoundRect(r, 16 * d, 16 * d, stroke)
    fill.color = Color.argb((255 * alpha).toInt(), 255, 196, 0)
    c.drawCircle(r.left + 30 * d, r.centerY(), 15 * d, fill)
    text.color = Color.argb((255 * alpha).toInt(), 40, 28, 0)
    text.textAlign = Paint.Align.CENTER
    c.drawText("★", r.left + 30 * d, r.centerY() + 6 * d, text)
    text.textAlign = Paint.Align.LEFT
    text.color = Color.argb((255 * alpha).toInt(), 255, 214, 90)
    c.drawText(title, r.left + 56 * d, r.top + 24 * d, text)
    text.color = Color.argb((255 * alpha).toInt(), 255, 255, 255)
    c.drawText(name, r.left + 56 * d, r.top + 45 * d, text)
    text.typeface = t.typeface
}

internal fun MapRenderer.drawJamChip(c: Canvas, a: Rect, d: Float, t: Theme, hud: Boolean) {
    if (NavEngine.offer != null || NavEngine.arrived) return
    // in the car the chips end left of the button column (they covered the buttons: photo, 21 Nov)
    val rightEdge = (if (car) carHits.filter { it.first.left > a.exactCenterX() }.minOfOrNull { it.first.left }?.minus(10 * d) else null)
        ?: (a.right - 12 * d)
    // speed camera / incident / report ahead
    Alerts.ahead?.let { al ->
        text.textSize = 15 * d
        text.typeface = Typeface.DEFAULT_BOLD
        text.textAlign = Paint.Align.LEFT
        val label = "${al.label} · ${NavEngine.formatDistance(al.dist)}"
        val w = text.measureText(label) + 52 * d
        val h = 40 * d
        val top = bannerBottom + 10 * d
        val left = if (hud) rightEdge - w else a.left + 12 * d
        val r = RectF(left, top, left + w, top + h)
        fill.color = Color.rgb(255, 244, 214); c.drawRoundRect(r, h / 2, h / 2, fill)
        stroke.color = Color.rgb(255, 150, 0); stroke.strokeWidth = 2 * d; c.drawRoundRect(r, h / 2, h / 2, stroke)
        text.typeface = Typeface.DEFAULT
        text.textSize = 17 * d
        text.textAlign = Paint.Align.CENTER
        c.drawText(al.glyph, r.left + 22 * d, r.centerY() + 6 * d, text)
        text.typeface = Typeface.DEFAULT_BOLD
        text.textSize = 15 * d
        text.textAlign = Paint.Align.LEFT
        text.color = Color.rgb(110, 60, 0)
        c.drawText(label, r.left + 40 * d, r.centerY() + 5.5f * d, text)
        bannerBottom = r.bottom; bannerRight = r.right
        alertChipAt.set(r)
        text.typeface = t.typeface
    }
    if (!NavEngine.active) return
    val (dist, delay) = NavEngine.jamAhead ?: return
    val mins = (delay + 30) / 60
    val label = gr.gamemap.L.t((if (dist < 100) "Jam ahead" else "Jam in ${NavEngine.formatDistance(dist)}") + (if (mins >= 1) " · +$mins min" else ""))
    text.textSize = 15 * d
    text.typeface = Typeface.DEFAULT_BOLD
    text.textAlign = Paint.Align.LEFT
    val w = text.measureText(label) + 44 * d
    val h = 38 * d
    val top = bannerBottom + 10 * d
    val left = if (hud && a.width() < 700 * d) rightEdge - w else a.left + 12 * d
    val r = RectF(left, top, left + w, top + h)
    fill.color = Color.rgb(251, 227, 227)
    c.drawRoundRect(r, h / 2, h / 2, fill)
    // warning triangle
    path.reset()
    val tx = r.left + 20 * d; val ty = r.centerY()
    path.moveTo(tx, ty - 8 * d); path.lineTo(tx + 9 * d, ty + 7 * d); path.lineTo(tx - 9 * d, ty + 7 * d); path.close()
    fill.color = Color.rgb(196, 30, 36); c.drawPath(path, fill)
    text.color = Color.rgb(122, 16, 16)
    c.drawText(label, r.left + 34 * d, r.centerY() + 5.5f * d, text)
    text.typeface = t.typeface
}

internal fun MapRenderer.drawTurnArrow(c: Canvas, x: Float, y: Float, s: Float, dir: String, t: Theme) {
    if (dir == "arrive") {
        fill.color = Color.rgb(255, 214, 0); c.drawCircle(x, y, s * 0.6f, fill)
        stroke.color = t.route; stroke.strokeWidth = s * 0.15f; c.drawCircle(x, y, s * 0.9f, stroke)
        return
    }
    if (dir == "uturn") {
        // a real U-turn arrow (not a straight one pointing down)
        stroke.color = t.route; stroke.strokeWidth = s * 0.42f
        drawUTurn(c, x, y, s, stroke)
        return
    }
    val angle = when {
        dir.contains("sharp left") -> -135f
        dir.contains("slight left") -> -45f
        dir.contains("left") -> -90f
        dir.contains("sharp right") -> 135f
        dir.contains("slight right") -> 45f
        dir.contains("right") -> 90f
        else -> 0f
    }
    c.save()
    c.rotate(angle, x, y)
    path.reset()
    path.moveTo(x, y - s)
    path.lineTo(x + s * 0.7f, y - s * 0.2f)
    path.lineTo(x + s * 0.25f, y - s * 0.2f)
    path.lineTo(x + s * 0.25f, y + s)
    path.lineTo(x - s * 0.25f, y + s)
    path.lineTo(x - s * 0.25f, y - s * 0.2f)
    path.lineTo(x - s * 0.7f, y - s * 0.2f)
    path.close()
    fill.color = t.route; c.drawPath(path, fill)
    c.restore()
}

/** A dropped pin: its tip on the point. */
internal fun MapRenderer.drawDroppedPin(c: Canvas, x: Float, y: Float, d: Float, color: Int = Color.rgb(232, 89, 12)) {
    val r = 13 * d
    path.reset()
    path.moveTo(x, y)
    path.cubicTo(x - r * 0.4f, y - r * 1.2f, x - r * 1.3f, y - r * 1.6f, x - r * 1.3f, y - r * 2.4f)
    path.arcTo(x - r * 1.3f, y - r * 3.7f, x + r * 1.3f, y - r * 1.1f, 180f, 180f, false)
    path.cubicTo(x + r * 1.3f, y - r * 1.6f, x + r * 0.4f, y - r * 1.2f, x, y)
    path.close()
    fill.color = Color.argb(70, 0, 0, 0); c.drawCircle(x, y, 4 * d, fill)
    fill.color = color; c.drawPath(path, fill)
    stroke.color = Color.WHITE; stroke.strokeWidth = 2.5f * d; c.drawPath(path, stroke)
    fill.color = Color.WHITE; c.drawCircle(x, y - r * 2.4f, r * 0.5f, fill)
}

internal fun MapRenderer.drawCompass(c: Canvas, a: Rect, bearing: Float, d: Float, t: Theme) {
    val r = 22 * d
    var x = a.left + 14 * d + r
    var y = maxOf(a.top + 14 * d, bannerBottom + 12 * d, carConvoyRect.bottom + 12 * d, convoyStrip.bottom + 12 * d) + r
    // a tall race box on a short car screen: beside the box rather than down into the speed box
    if (!carConvoyRect.isEmpty && y + r > a.bottom - 130 * d) { x = carConvoyRect.right + 12 * d + r; y = carConvoyRect.top + r }
    // the car: below anything drawn in its corner this frame (Where to? and the Home / Work /
    // label chips, a place's card, the lobby): it was only kept clear of the turn and race boxes
    if (car) {
        var moved = true; var guard = 0
        while (moved && guard++ < 8) {
            moved = false
            for (hr in carHits.map { it.first } + listOfNotNull(inviteAt.takeIf { !it.isEmpty })) if (hr.left < x + r && hr.right > x - r && hr.top < y + r + 6 * d && hr.bottom > y - r - 6 * d) {
                y = hr.bottom + 12 * d + r; moved = true
            }
        }
    }
    compassBounds.set(x - r, y - r, x + r, y + r)
    fill.color = Ui.panel(t); c.drawCircle(x, y, r, fill)
    stroke.color = t.route; stroke.strokeWidth = 2 * d; c.drawCircle(x, y, r, stroke)
    c.save()
    c.rotate(-bearing, x, y)
    path.reset(); path.moveTo(x, y - r * 0.75f); path.lineTo(x + r * 0.28f, y); path.lineTo(x - r * 0.28f, y); path.close()
    fill.color = Color.rgb(230, 50, 40); c.drawPath(path, fill)
    path.reset(); path.moveTo(x, y + r * 0.75f); path.lineTo(x + r * 0.28f, y); path.lineTo(x - r * 0.28f, y); path.close()
    fill.color = Ui.ink(t); c.drawPath(path, fill)
    c.restore()
}

internal fun MapRenderer.drawAttribution(c: Canvas, a: Rect, d: Float, t: Theme) {
    text.textAlign = Paint.Align.RIGHT
    text.textSize = 9 * d
    text.color = Color.argb(170, Color.red(Ui.ink(t)), Color.green(Ui.ink(t)), Color.blue(Ui.ink(t)))
    c.drawText(if (Traffic.active) "© OpenStreetMap · OpenFreeMap · TomTom" else "© OpenStreetMap · OpenFreeMap", a.right - 8 * d, a.bottom - 6 * d, text)
}
