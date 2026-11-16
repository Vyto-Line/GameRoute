package gr.gamemap

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * Every 5 levels (5, 10 … 50): a 3-second celebration on the phone, in the look of your map style,
 * shown once you've parked (never while driving). Tap anywhere to skip; then the usual level-up
 * card with the new title and unlocks follows. Level 50 is the big one (rays and confetti) and
 * leads to prestige.
 *
 *  - Neon styles: a flash, the number glitches in (pink/cyan split), the title types itself.
 *  - Violet Rain: rain and lightning, the number drops in like a raindrop and ripples.
 *  - Rally Roadbook: a chequered band sweeps across, the title is stamped on.
 *  - Nautical Chart and Realm Map: a compass spins and settles, the title is inked in.
 *  - The others: the number pops out of a burst of the style's colours.
 */
class Milestone(ctx: Context, private val level: Int, private val title: String, private val theme: Theme, private val onDone: () -> Unit) : View(ctx) {
    private val start = SystemClock.uptimeMillis()
    private val d = ctx.resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val rnd = java.util.Random(level.toLong())
    private val drops = FloatArray(60 * 3) { rnd.nextFloat() }
    private var done = false

    /** For tests: draw as if this many ms had passed. */
    internal var fixedMs: Long? = null

    private val kind: String = when {
        level >= 50 -> "max"
        theme == Theme.VIOLET_RAIN -> "rain"
        theme == Theme.RALLY -> "rally"
        theme == Theme.NAUTICAL || theme == Theme.PARCHMENT_QUEST -> "compass"
        theme == Theme.NEON_NOIR || theme == Theme.NEON_RACER || theme == Theme.BLUEPRINT || theme == Theme.HIGH_CONTRAST -> "neon"
        else -> "burst"
    }

    init {
        isClickable = true
        contentDescription = L.t("Level $level") + ", " + L.t(title)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_UP) finish()
        return true
    }

    private fun finish() {
        if (done) return
        done = true
        onDone()
    }

    override fun onDraw(c: Canvas) {
        val t = (fixedMs ?: (SystemClock.uptimeMillis() - start)).toFloat()
        val w = width.toFloat(); val h = height.toFloat()
        val look = theme.look
        val dark = MapState.dark
        val bg = if (kind == "rally") Color.rgb(246, 235, 211) else if (kind == "compass") Color.rgb(239, 230, 210)
            else if (dark || kind != "burst") Color.rgb(10, 9, 20) else Color.rgb(246, 248, 252)
        val ink = if (kind == "rally") Color.rgb(42, 30, 20) else if (kind == "compass") Color.rgb(22, 50, 79)
            else if (Color.luminance(bg) > 0.5f) Color.rgb(28, 30, 36) else Color.WHITE
        val accent = when (kind) {
            "rally" -> Color.rgb(200, 64, 31)
            "compass" -> Color.rgb(178, 58, 58)
            "max" -> Color.rgb(255, 215, 90)
            else -> look.route
        }
        c.drawColor(Color.argb((min(1f, t / 250f) * 245).toInt(), Color.red(bg), Color.green(bg), Color.blue(bg)))
        val cx = w / 2; val cy = h * 0.36f
        val num = "$level"
        text.typeface = theme.typeface ?: Typeface.DEFAULT_BOLD
        when (kind) {
            "neon" -> {
                // scanlines, a flash at 0.5 s, the number with a pink/cyan split for a moment
                stroke.color = Color.argb(14, Color.red(accent), Color.green(accent), Color.blue(accent)); stroke.strokeWidth = 1f
                var y = 0f; while (y < h) { c.drawLine(0f, y, w, y, stroke); y += 4 * d }
                if (t in 480f..700f) c.drawColor(Color.argb(((700 - t) / 220 * 180).toInt(), Color.red(accent), Color.green(accent), Color.blue(accent)))
                if (t > 500) {
                    val jit = if (t < 1100) ((rnd.nextFloat() - 0.5f) * 14 * d) else 0f
                    text.textSize = 140 * d
                    if (t < 1200) {
                        text.color = Color.argb(200, 255, 46, 136); c.drawText(num, cx - 8 * d + jit, cy, text)
                        text.color = Color.argb(200, 34, 230, 255); c.drawText(num, cx + 8 * d - jit, cy, text)
                    }
                    text.color = Color.WHITE; c.drawText(num, cx + jit / 2, cy, text)
                }
                if (t > 1300) typed(c, cx, cy + 70 * d, accent, ((t - 1300) / 900f).coerceIn(0f, 1f))
            }
            "rain" -> {
                // rain all the time, lightning twice, the number drops in and ripples
                stroke.color = Color.argb(150, 181, 124, 255); stroke.strokeWidth = 1.5f * d
                for (i in 0 until 60) {
                    val x = drops[i * 3] * w; val sp = 0.9f + drops[i * 3 + 1]; val ln = (24 + drops[i * 3 + 2] * 30) * d
                    val y = ((drops[i * 3 + 2] * h + t * sp) % (h + ln)) - ln
                    c.drawLine(x, y, x - 4 * d, y + ln, stroke)
                }
                if (t in 600f..700f || t in 820f..900f) c.drawColor(Color.argb(170, 233, 217, 255))
                if (t > 650) {
                    val k = ((t - 650) / 450f).coerceIn(0f, 1f)
                    val ease = 1 - (1 - k) * (1 - k)
                    text.textSize = 140 * d; text.color = accent
                    text.setShadowLayer(26 * d, 0f, 0f, accent)
                    c.drawText(num, cx, cy - (1 - ease) * 120 * d, text)
                    text.clearShadowLayer()
                    if (k >= 1) {
                        val rk = ((t - 1100) / 900f).coerceIn(0f, 1f)
                        stroke.color = Color.argb(((1 - rk) * 230).toInt(), Color.red(accent), Color.green(accent), Color.blue(accent)); stroke.strokeWidth = 2 * d
                        c.drawOval(RectF(cx - 120 * d * (0.2f + rk), cy + 26 * d - 24 * d * (0.2f + rk), cx + 120 * d * (0.2f + rk), cy + 26 * d + 24 * d * (0.2f + rk)), stroke)
                    }
                }
                if (t > 1300) fadeTitle(c, cx, cy + 70 * d, Color.rgb(237, 228, 255), ((t - 1300) / 500f).coerceIn(0f, 1f))
            }
            "rally" -> {
                // graph paper, the level counts in, the title stamped on, a chequered band sweeping across
                stroke.color = Color.argb(70, 200, 180, 140); stroke.strokeWidth = 1f
                var g = 0f; while (g < w) { c.drawLine(g, 0f, g, h, stroke); g += 26 * d }
                g = 0f; while (g < h) { c.drawLine(0f, g, w, g, stroke); g += 26 * d }
                if (t > 300) { text.textSize = 140 * d; text.color = ink; c.drawText(num, cx, cy, text) }
                if (t > 1100) {
                    val k = ((t - 1100) / 250f).coerceIn(0f, 1f)
                    val s = 2.2f - 1.2f * k
                    c.save(); c.translate(cx, cy + 80 * d); c.rotate(-8f); c.scale(s, s)
                    text.textSize = 30 * d; text.typeface = Typeface.MONOSPACE; text.isFakeBoldText = true
                    val tw = text.measureText(L.t(title).uppercase()) / 2 + 16 * d
                    stroke.color = accent; stroke.strokeWidth = 3.5f * d; stroke.alpha = (k * 255).toInt()
                    c.drawRoundRect(RectF(-tw, -32 * d, tw, 14 * d), 6 * d, 6 * d, stroke)
                    text.color = accent; text.alpha = (k * 255).toInt()
                    c.drawText(L.t(title).uppercase(), 0f, 0f, text)
                    c.restore(); text.isFakeBoldText = false; stroke.alpha = 255; text.alpha = 255
                }
                val sweep = ((t - 0) / 900f).coerceIn(0f, 1f)
                if (sweep < 1f) {
                    val bx = -200 * d + sweep * (w + 400 * d)
                    val sq = 20 * d
                    var yy = 0f; var row = 0
                    while (yy < h) {
                        for (k in 0 until 8) {
                            fill.color = if ((k + row) % 2 == 0) Color.rgb(17, 17, 17) else Color.WHITE
                            c.drawRect(bx + k * sq, yy, bx + (k + 1) * sq, yy + sq, fill)
                        }
                        yy += sq; row++
                    }
                }
            }
            "compass" -> {
                // a compass spins and settles on north; the rank is inked in left to right
                val r = 90 * d
                fill.color = Color.rgb(251, 246, 234); c.drawCircle(cx, cy - 10 * d, r, fill)
                stroke.color = ink; stroke.strokeWidth = 3 * d; c.drawCircle(cx, cy - 10 * d, r, stroke)
                val k = (t / 1500f).coerceIn(0f, 1f)
                val spin = -540f * (1 - k) * (1 - k) * (1 - k) + (if (k < 1) 12f * sin(k * 9f) * (1 - k) else 0f)
                c.save(); c.rotate(spin, cx, cy - 10 * d)
                val p = Path()
                p.moveTo(cx, cy - 10 * d - r * 0.85f); p.lineTo(cx + 12 * d, cy - 10 * d); p.lineTo(cx, cy - 10 * d + r * 0.85f); p.lineTo(cx - 12 * d, cy - 10 * d); p.close()
                fill.color = ink; c.drawPath(p, fill)
                p.reset(); p.moveTo(cx, cy - 10 * d - r * 0.85f); p.lineTo(cx + 12 * d, cy - 10 * d); p.lineTo(cx, cy - 10 * d); p.close()
                fill.color = accent; c.drawPath(p, fill)
                c.restore()
                text.textSize = 22 * d; text.color = ink; c.drawText(L.t("Level $level"), cx, cy + r + 30 * d, text)
                if (t > 1500) {
                    val ik = ((t - 1500) / 800f).coerceIn(0f, 1f)
                    text.textSize = 40 * d
                    val tw = text.measureText(L.t(title))
                    c.save(); c.clipRect(cx - tw / 2 - 4 * d, cy + r + 40 * d, cx - tw / 2 + (tw + 8 * d) * ik, cy + r + 100 * d)
                    c.drawText(L.t(title), cx, cy + r + 84 * d, text); c.restore()
                }
            }
            "max" -> {
                // level 50: light rays turning, confetti, the 50 pops in
                c.save(); c.rotate(t / 50f, cx, cy)
                for (i in 0 until 24) {
                    val a = i * 15.0 * PI / 180
                    fill.shader = LinearGradient(cx, cy, cx + (cos(a) * h).toFloat(), cy + (sin(a) * h).toFloat(), Color.argb(90, 255, 215, 90), Color.TRANSPARENT, Shader.TileMode.CLAMP)
                    val p = Path(); p.moveTo(cx, cy)
                    p.lineTo(cx + (cos(a - 0.03) * h).toFloat(), cy + (sin(a - 0.03) * h).toFloat())
                    p.lineTo(cx + (cos(a + 0.03) * h).toFloat(), cy + (sin(a + 0.03) * h).toFloat()); p.close()
                    c.drawPath(p, fill)
                }
                fill.shader = null; c.restore()
                val cols = intArrayOf(Color.rgb(255, 215, 90), Color.rgb(34, 230, 255), Color.rgb(255, 46, 136), Color.rgb(124, 247, 194))
                for (i in 0 until 60) {
                    fill.color = cols[i % 4]
                    val x = drops[i * 3] * w + sin((t / 400f) + i) * 12 * d
                    val y = ((drops[i * 3 + 1] * h * 0.5f + t * (0.25f + drops[i * 3 + 2] * 0.3f)) % (h + 20 * d)) - 20 * d
                    c.drawRect(x, y, x + 8 * d, y + 12 * d, fill)
                }
                val k = ((t - 200) / 500f).coerceIn(0f, 1f)
                val s = if (k < 0.7f) k / 0.7f * 1.15f else 1.15f - (k - 0.7f) / 0.3f * 0.15f
                c.save(); c.scale(s, s, cx, cy - 40 * d)
                text.textSize = 150 * d; text.color = accent; text.setShadowLayer(30 * d, 0f, 0f, accent)
                c.drawText(num, cx, cy, text); text.clearShadowLayer(); c.restore()
                text.textSize = 15 * d; text.color = accent; c.drawText(L.t("MAX LEVEL"), cx, cy - 170 * d, text)
                if (t > 1200) fadeTitle(c, cx, cy + 70 * d, Color.WHITE, ((t - 1200) / 500f).coerceIn(0f, 1f))
            }
            else -> {
                // a burst in the style's colours, the number pops
                val k = (t / 600f).coerceIn(0f, 1f)
                for (i in 0 until 16) {
                    val a = i * 22.5 * PI / 180
                    val r0 = 40 * d + k * 140 * d
                    fill.color = Color.argb(((1 - k) * 220).toInt(), Color.red(accent), Color.green(accent), Color.blue(accent))
                    c.drawCircle(cx + (cos(a) * r0).toFloat(), cy - 40 * d + (sin(a) * r0).toFloat(), 7 * d, fill)
                }
                val pop = 1 - exp(-t / 160.0).toFloat()
                c.save(); c.scale(0.4f + 0.6f * pop, 0.4f + 0.6f * pop, cx, cy - 40 * d)
                text.textSize = 140 * d; text.color = accent; c.drawText(num, cx, cy, text); c.restore()
                if (t > 900) fadeTitle(c, cx, cy + 70 * d, ink, ((t - 900) / 500f).coerceIn(0f, 1f))
            }
        }
        text.textSize = 15 * d; text.color = if (kind == "rally" || kind == "compass") Color.argb(200, 0, 0, 0) else Color.argb(200, 255, 255, 255)
        text.typeface = Typeface.DEFAULT_BOLD
        c.drawText(L.t(if (level >= 50) "Level 50: prestige is ready" else "LEVEL UP"), cx, h * 0.12f, text)
        text.textSize = 13 * d; text.alpha = 140
        c.drawText(L.t("Tap to continue"), cx, h * 0.93f, text); text.alpha = 255
        if (fixedMs == null) {
            if (t > 3200) finish() else postInvalidateOnAnimation()
        }
    }

    private fun typed(c: Canvas, x: Float, y: Float, col: Int, k: Float) {
        val s = L.t(title).uppercase()
        val n = (s.length * k).toInt().coerceIn(0, s.length)
        text.textSize = 30 * d; text.color = col
        text.setShadowLayer(14 * d, 0f, 0f, col)
        c.drawText(s.take(n) + if (k < 1 && (SystemClock.uptimeMillis() / 300) % 2 == 0L) "▌" else "", x, y, text)
        text.clearShadowLayer()
    }

    private fun fadeTitle(c: Canvas, x: Float, y: Float, col: Int, k: Float) {
        text.textSize = 30 * d; text.color = col; text.alpha = (k * 255).toInt()
        c.drawText(L.t(title).uppercase(), x, y + (1 - k) * 16 * d, text); text.alpha = 255
    }
}
