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

/** Where I was last, when location is off: a faint grey circle with a "?". */
internal fun MapRenderer.drawLastKnown(c: Canvas, x: Float, y: Float, d: Float, t: Theme) {
    val r = 15 * d
    fill.color = Color.argb(70, 140, 146, 156); c.drawCircle(x, y, r * 1.9f, fill)
    fill.color = Color.argb(200, 122, 128, 137); c.drawCircle(x, y, r, fill)
    stroke.color = Color.argb(220, 255, 255, 255); stroke.strokeWidth = 2 * d; c.drawCircle(x, y, r, stroke)
    text.textAlign = Paint.Align.CENTER; text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 17 * d; text.color = Color.WHITE
    c.drawText("?", x, y + 6 * d, text)
    text.textAlign = Paint.Align.LEFT; text.typeface = t.typeface
}

internal fun MapRenderer.drawPlayer(c: Canvas, x: Float, y: Float, rotation: Float, d: Float, t: Theme) {
    val s = 15 * d
    c.save()
    c.rotate(rotation, x, y)
    path.reset()
    // the Garage's arrow: always pointing the way you drive, always outlined
    val kind = Xp.chosen("arrow")
    when (kind.id) {
        "arrow_chevron" -> {
            path.moveTo(x, y - s); path.lineTo(x + s * 0.85f, y + s * 0.55f); path.lineTo(x + s * 0.45f, y + s * 0.78f)
            path.lineTo(x, y + s * 0.08f); path.lineTo(x - s * 0.45f, y + s * 0.78f); path.lineTo(x - s * 0.85f, y + s * 0.55f)
        }
        "arrow_dart" -> { path.moveTo(x, y - s * 1.15f); path.lineTo(x + s * 0.5f, y + s * 0.85f); path.lineTo(x, y + s * 0.5f); path.lineTo(x - s * 0.5f, y + s * 0.85f) }
        "arrow_delta" -> { path.moveTo(x, y - s); path.lineTo(x + s * 0.95f, y + s * 0.85f); path.lineTo(x - s * 0.95f, y + s * 0.85f) }
        "arrow_comet" -> { // a shield
            path.moveTo(x, y - s * 1.05f); path.lineTo(x + s * 0.8f, y - s * 0.35f); path.lineTo(x + s * 0.6f, y + s * 0.55f)
            path.lineTo(x, y + s * 0.95f); path.lineTo(x - s * 0.6f, y + s * 0.55f); path.lineTo(x - s * 0.8f, y - s * 0.35f)
        }
        else -> { path.moveTo(x, y - s); path.lineTo(x + s * 0.75f, y + s * 0.8f); path.lineTo(x, y + s * 0.4f); path.lineTo(x - s * 0.75f, y + s * 0.8f) }
    }
    path.close()
    fill.isAntiAlias = t.pixel == 1
    // (no coloured circle behind it either: just the arrow on the map)
    // no disc behind the arrow (it's see-through in every style). An arrow too close to the map's own
    // colours (8-Bit: green on green roads; Neon: pink on pink) gets a thick outline in its outline
    // colour instead, so it never gets lost
    val body = if (kind.color != 0) kind.color else t.player
    val edge = if (kind.color != 0) Color.rgb(40, 28, 0) else t.playerOutline
    val st = t.style
    val faint = minOf(Ui.contrast(body, st.land), Ui.contrast(body, st.major), Ui.contrast(body, st.minor)) < 2.2
    stroke.strokeJoin = Paint.Join.ROUND
    if (faint) { stroke.color = edge; stroke.strokeWidth = 7f * d; c.drawPath(path, stroke) }
    fill.color = body; c.drawPath(path, fill)
    stroke.color = edge; stroke.strokeWidth = 2.5f * d; c.drawPath(path, stroke)
    stroke.strokeJoin = Paint.Join.MITER
    if (kind.id == "arrow_comet") { // the shield's arrow mark
        stroke.color = t.playerOutline; stroke.strokeWidth = 2.2f * d
        c.drawLine(x, y + s * 0.45f, x, y - s * 0.5f, stroke); c.drawLine(x - s * 0.32f, y - s * 0.18f, x, y - s * 0.5f, stroke); c.drawLine(x + s * 0.32f, y - s * 0.18f, x, y - s * 0.5f, stroke)
    }
    if (kind.id == "arrow_star") { // prestige: a small star in the middle
        fill.color = Color.WHITE
        val st = Path(); for (k in 0 until 10) { val a = Math.toRadians(-90.0 + k * 36); val rr = if (k % 2 == 0) s * 0.32f else s * 0.14f
            val px2 = x + (rr * cos(a)).toFloat(); val py2 = y + s * 0.1f + (rr * sin(a)).toFloat(); if (k == 0) st.moveTo(px2, py2) else st.lineTo(px2, py2) }
        st.close(); c.drawPath(st, fill)
    }
    fill.isAntiAlias = true
    c.restore()
}

internal fun MapRenderer.drawRadarFrame(c: Canvas, cx: Float, cy: Float, r: Float, bearing: Float, d: Float) {
    fill.shader = RadialGradient(
        cx, cy, r, intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(150, 0, 0, 0)),
        floatArrayOf(0f, 0.8f, 1f), Shader.TileMode.CLAMP
    )
    c.drawCircle(cx, cy, r, fill)
    fill.shader = null
    stroke.color = Color.argb(230, 20, 20, 20); stroke.strokeWidth = 9 * d
    c.drawCircle(cx, cy, r + 3 * d, stroke)
    stroke.color = Color.argb(200, 220, 220, 220); stroke.strokeWidth = 2 * d
    c.drawCircle(cx, cy, r - 1 * d, stroke)
    val a = Math.toRadians((-bearing - 90).toDouble())
    val nx = cx + (r * cos(a)).toFloat(); val ny = cy + (r * sin(a)).toFloat()
    fill.color = Color.argb(240, 30, 30, 30); c.drawCircle(nx, ny, 13 * d, fill)
    text.color = Color.WHITE; text.textSize = 15 * d; text.textAlign = Paint.Align.CENTER
    c.drawText("N", nx, ny + 5.5f * d, text)
}

/** A hand-cut card: rounded corners, each a little different (Watercolour). */
internal fun MapRenderer.paperPath(r: RectF, k: Float): Path {
    val kk = minOf(k, r.height() / 2.2f)
    return Path().apply { addRoundRect(r, floatArrayOf(kk * 0.9f, kk * 0.8f, kk * 1.15f, kk * 1.0f, kk * 0.75f, kk * 0.95f, kk * 1.2f, kk * 1.05f), Path.Direction.CW) }
}

/** A HUD panel in the theme's shape: rounded, cut-corner, or pixel-stepped. */
internal fun MapRenderer.panel(c: Canvas, r: RectF, d: Float, t: Theme, outline: Boolean, outlineColor: Int? = null) {
    // slightly see-through so the map shows behind, still solid enough to read
    fill.color = Ui.panel(t).let { Color.argb(218, Color.red(it), Color.green(it), Color.blue(it)) }
    stroke.color = outlineColor ?: t.route; stroke.strokeWidth = 2 * d
    when (t.hud) {
        HudShape.ROUND -> {
            c.drawRoundRect(r, 12 * d, 12 * d, fill)
            if (outline) c.drawRoundRect(r, 12 * d, 12 * d, stroke)
        }
        HudShape.GLASS -> {
            c.drawRoundRect(r, 10 * d, 10 * d, fill)
            val col = outlineColor ?: t.route
            stroke.color = withAlpha(col, 60); stroke.strokeWidth = 5 * d; c.drawRoundRect(r, 10 * d, 10 * d, stroke)
            stroke.color = withAlpha(col, if (outline) 255 else 200); stroke.strokeWidth = 1.4f * d; c.drawRoundRect(r, 10 * d, 10 * d, stroke)
            stroke.strokeWidth = 2 * d
        }
        HudShape.PAPER -> {
            val p = paperPath(r, 14 * d)
            // a soft shadow under the card, then the card
            val keep = fill.color
            fill.color = Color.argb(40, 40, 30, 20); c.save(); c.translate(0f, 2.5f * d); c.drawPath(p, fill); c.restore()
            fill.color = keep; c.drawPath(p, fill)
            if (outline) { stroke.strokeWidth = 1.8f * d; c.drawPath(p, stroke); stroke.strokeWidth = 2 * d }
            else { stroke.color = Color.argb(50, 90, 70, 50); stroke.strokeWidth = 1 * d; c.drawPath(p, stroke); stroke.strokeWidth = 2 * d }
        }
        HudShape.CHAMFER -> {
            val k = minOf(14 * d, r.height() / 3)
            val p = Path().apply {
                moveTo(r.left + k, r.top); lineTo(r.right, r.top)
                lineTo(r.right, r.bottom - k); lineTo(r.right - k, r.bottom)
                lineTo(r.left, r.bottom); lineTo(r.left, r.top + k); close()
            }
            c.drawPath(p, fill)
            if (outline) {
                c.drawPath(p, stroke)
                fill.color = outlineColor ?: t.route
                c.drawRect(r.left + k + 4 * d, r.top - 2 * d, r.left + k + 44 * d, r.top + 2 * d, fill)
            }
        }
        HudShape.PIXEL -> {
            val k = 4 * d
            c.drawRect(r.left + k, r.top, r.right - k, r.bottom, fill)
            c.drawRect(r.left, r.top + k, r.right, r.bottom - k, fill)
            if (outline) {
                stroke.strokeWidth = k
                stroke.isAntiAlias = false
                c.drawRect(r.left + k / 2, r.top + k / 2, r.right - k / 2, r.bottom - k / 2, stroke)
                stroke.isAntiAlias = true
            }
        }
    }
}
