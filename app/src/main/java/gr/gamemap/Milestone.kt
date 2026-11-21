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
 *  - Free Roam: a pin drops on the map and rings ripple out.
 *  - Midnight Run: speed lines, the rev counter swings to the redline.
 *  - Blockworld: blocks fall and build the number.
 *  - Fire Tower: the lookout's beacon sweeps across and lights the number.
 *  - 8-Bit: coins pop out of a ? block, a pixel score counts up.
 *  - Arctic: an aurora, falling snow, the number frosts over.
 *  - Catch 'Em Trail: binoculars find the number on a hilltop (5, 15…) or campfire sparks gather into it (10, 20…).
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
        theme == Theme.NEON_NOIR || theme == Theme.BLUEPRINT || theme == Theme.HIGH_CONTRAST -> "neon"
        theme == Theme.OPEN_WORLD -> "pin"
        theme == Theme.NEON_RACER -> "tacho"
        theme == Theme.BLOCKY -> "blocks"
        theme == Theme.LOOKOUT -> "beacon"
        theme == Theme.RETRO -> "coins"
        theme == Theme.ARCTIC -> "aurora"
        theme == Theme.TOKYO_NIGHT -> "tokyo"
        theme == Theme.WATERCOLOUR -> "paint"
        // Catch 'Em Trail: binoculars and the campfire take turns (5, 15, 25…: binoculars)
        theme == Theme.TRAILBLAZER -> if ((level / 5) % 2 == 1) "binoc" else "camp"
        else -> "burst"
    }

    /** How long it plays before the level card. */
    private val endMs = if (kind == "binoc" || kind == "camp") 4600f else 3200f

    init {
        isClickable = true
        contentDescription = L.t("Level $level") + ", " + L.t(title)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_UP && SystemClock.uptimeMillis() - start > 700) finish()
        return true
    }

    private fun finish() {
        if (done) return
        done = true
        // never from inside a frame being drawn: removing this view mid-draw crashed the app
        post { onDone() }
    }

    override fun onDraw(c: Canvas) {
        val t = (fixedMs ?: (SystemClock.uptimeMillis() - start)).toFloat()
        val w = width.toFloat(); val h = height.toFloat()
        val look = theme.look
        val dark = MapState.dark
        val bg = when (kind) {
            "rally" -> Color.rgb(246, 235, 211)
            "compass" -> Color.rgb(239, 230, 210)
            "pin" -> Color.rgb(14, 20, 32)
            "tacho" -> Color.rgb(5, 6, 12)
            "blocks" -> Color.rgb(11, 13, 18)
            "beacon" -> Color.rgb(10, 11, 16)
            "coins" -> Color.rgb(13, 10, 26)
            "aurora" -> Color.rgb(6, 16, 28)
            "binoc" -> Color.rgb(11, 18, 32)
            "camp" -> Color.rgb(10, 16, 48)
            "tokyo" -> Color.rgb(6, 7, 13)
            "paint" -> Color.rgb(247, 241, 227)
            else -> if (dark || kind != "burst") Color.rgb(10, 9, 20) else Color.rgb(246, 248, 252)
        }
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
            "pin" -> drawPin(c, w, h, cx, cy, t, num)
            "tacho" -> drawTacho(c, w, h, cx, cy, t, num)
            "blocks" -> drawBlocks(c, w, h, cx, cy, t)
            "beacon" -> drawBeacon(c, w, h, cx, cy, t, num)
            "coins" -> drawCoins(c, w, h, cx, cy, t, num)
            "aurora" -> drawAurora(c, w, h, cx, cy, t, num)
            "binoc" -> drawBinoculars(c, w, h, cx, cy, t, num)
            "camp" -> drawCampfire(c, w, h, cx, cy, t)
            "tokyo" -> drawTokyo(c, w, h, cx, cy, t, num)
            "paint" -> drawPaint(c, w, h, cx, cy, t, num)
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
        text.textSize = 15 * d; text.color = if (kind == "rally" || kind == "compass" || kind == "paint") Color.argb(200, 0, 0, 0) else Color.argb(200, 255, 255, 255)
        text.typeface = Typeface.DEFAULT_BOLD
        c.drawText(L.t(if (level >= 50) "Level 50: prestige is ready" else "LEVEL UP"), cx, h * 0.12f, text)
        text.textSize = (if (t > endMs) 15 else 13) * d
        text.alpha = if (t > endMs) (150 + 100 * sin((t - endMs) / 300f)).toInt().coerceIn(60, 255) else 140
        c.drawText(L.t("Tap to continue"), cx, h * 0.93f, text); text.alpha = 255
        if (fixedMs == null) {
            // played out: it stays (the rain keeps falling, the fire keeps burning) until a tap, so
            // there's time to read the new title; then the level card with what was unlocked
            postInvalidateOnAnimation()
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

    // ------------------------------------------------------------------ the styles' own celebrations

    /** 3 x 5 pixel digits (blocks, embers): "X" = a block. */
    private val digits = mapOf(
        '0' to listOf("XXX", "X.X", "X.X", "X.X", "XXX"), '1' to listOf(".X.", "XX.", ".X.", ".X.", "XXX"),
        '2' to listOf("XXX", "..X", "XXX", "X..", "XXX"), '3' to listOf("XXX", "..X", "XXX", "..X", "XXX"),
        '4' to listOf("X.X", "X.X", "XXX", "..X", "..X"), '5' to listOf("XXX", "X..", "XXX", "..X", "XXX"),
        '6' to listOf("XXX", "X..", "XXX", "X.X", "XXX"), '7' to listOf("XXX", "..X", "..X", "..X", "..X"),
        '8' to listOf("XXX", "X.X", "XXX", "X.X", "XXX"), '9' to listOf("XXX", "X.X", "XXX", "..X", "XXX"),
    )

    /** The cells of the level number in pixel digits, centred on (cx, top), [size] apart. */
    private fun cells(cx: Float, top: Float, size: Float): List<Pair<Float, Float>> {
        val s = "$level"
        val wCells = s.length * 3 + (s.length - 1)
        val left = cx - wCells * size / 2
        val out = ArrayList<Pair<Float, Float>>()
        s.forEachIndexed { i, ch ->
            val g = digits[ch] ?: return@forEachIndexed
            for (r in 0 until 5) for (col in 0 until 3) if (g[r][col] == 'X') out += (left + (i * 4 + col) * size) to (top + r * size)
        }
        return out
    }

    private fun bigNumber(c: Canvas, num: String, cx: Float, cy: Float, col: Int, glow: Int? = null, size: Float = 130f) {
        text.textSize = size * d; text.color = col
        if (glow != null) text.setShadowLayer(24 * d, 0f, 0f, glow)
        c.drawText(num, cx, cy, text); text.clearShadowLayer()
    }

    private fun ease(k: Float) = 1 - (1 - k) * (1 - k)

    /** Free Roam: a pin drops on the map, rings ripple out from it. */
    private fun drawPin(c: Canvas, w: Float, h: Float, cx: Float, cy: Float, t: Float, num: String) {
        stroke.color = Color.argb(40, 120, 150, 210); stroke.strokeWidth = 3 * d
        var x = -h; while (x < w) { c.drawLine(x, h, x + h * 0.7f, 0f, stroke); x += 30 * d }
        val py = h * 0.58f
        val purple = Color.rgb(168, 85, 247)
        for (i in 0 until 3) {
            val rk = (((t - 700 - i * 500) % 1500f) / 1500f).let { if (t < 700 + i * 500) -1f else it }
            if (rk < 0) continue
            stroke.color = Color.argb(((1 - rk) * 230).toInt(), 168, 85, 247); stroke.strokeWidth = 3 * d
            c.drawCircle(cx, py, 10 * d + rk * 160 * d, stroke)
        }
        val k = (t / 900f).coerceIn(0f, 1f)
        val bounce = if (k < 0.7f) -(1 - k / 0.7f) * h * 0.6f else -sin((k - 0.7f) / 0.3f * PI.toFloat()) * 18 * d
        c.save(); c.translate(cx, py - 4 * d + bounce)
        val pin = Path().apply { moveTo(0f, 0f); cubicTo(-30 * d, -38 * d, -30 * d, -78 * d, 0f, -78 * d); cubicTo(30 * d, -78 * d, 30 * d, -38 * d, 0f, 0f); close() }
        fill.color = purple; c.drawPath(pin, fill)
        fill.color = Color.WHITE; c.drawCircle(0f, -52 * d, 10 * d, fill)
        c.restore()
        if (t > 500) bigNumber(c, num, cx, cy, purple)
        if (t > 1000) fadeTitle(c, cx, h * 0.72f, Color.WHITE, ((t - 1000) / 500f).coerceIn(0f, 1f))
    }

    /** Midnight Run: speed lines rush past, the rev counter swings to the redline. */
    private fun drawTacho(c: Canvas, w: Float, h: Float, cx: Float, cy: Float, t: Float, num: String) {
        val cyan = Color.rgb(34, 211, 238)
        stroke.strokeWidth = 2 * d
        for (i in 0 until 26) {
            val x = drops[i * 3] * w; val ln = (60 + drops[i * 3 + 1] * 90) * d
            val y = ((drops[i * 3 + 2] * h + t * 1.6f) % (h + ln)) - ln
            stroke.color = Color.argb(120, 34, 211, 238); c.drawLine(x, y, x, y + ln, stroke)
        }
        val gy = h * 0.62f; val r = 110 * d
        val box = RectF(cx - r, gy - r, cx + r, gy + r)
        stroke.strokeWidth = 14 * d; stroke.strokeCap = Paint.Cap.BUTT
        stroke.color = Color.rgb(31, 41, 55); c.drawArc(box, 180f, 180f, false, stroke)
        val k = ease((t / 1200f).coerceIn(0f, 1f))
        stroke.color = cyan; c.drawArc(box, 180f, 180f * k, false, stroke)
        stroke.color = Color.rgb(239, 68, 68); c.drawArc(box, 330f, 30f, false, stroke)
        stroke.strokeCap = Paint.Cap.ROUND
        val a = Math.toRadians((180.0 + 170.0 * k))
        stroke.color = Color.WHITE; stroke.strokeWidth = 4 * d
        c.drawLine(cx, gy, cx + (cos(a) * (r - 18 * d)).toFloat(), gy + (sin(a) * (r - 18 * d)).toFloat(), stroke)
        fill.color = Color.WHITE; c.drawCircle(cx, gy, 7 * d, fill)
        if (t > 400) bigNumber(c, num, cx, cy, Color.WHITE, cyan)
        if (t > 1200) { text.setShadowLayer(12 * d, 0f, 0f, cyan); fadeTitle(c, cx, h * 0.76f, cyan, ((t - 1200) / 500f).coerceIn(0f, 1f)); text.clearShadowLayer() }
    }

    /** Blockworld: blocks fall one by one and build the number. */
    private fun drawBlocks(c: Canvas, w: Float, h: Float, cx: Float, cy: Float, t: Float) {
        fill.color = Color.rgb(53, 107, 49); c.drawRect(0f, h * 0.86f, w, h, fill)
        fill.color = Color.rgb(95, 161, 79); c.drawRect(0f, h * 0.86f, w, h * 0.86f + 12 * d, fill)
        val size = 26 * d
        val list = cells(cx, h * 0.2f, size)
        list.forEachIndexed { i, (x, y) ->
            val k = ((t - i * 40f) / 500f).coerceIn(0f, 1f)
            if (k <= 0f) return@forEachIndexed
            val yy = y - (1 - k * k) * h
            fill.color = Color.rgb(239, 68, 68); c.drawRect(x, yy, x + size - 2 * d, yy + size - 2 * d, fill)
            fill.color = Color.rgb(185, 28, 28); c.drawRect(x + size - 7 * d, yy, x + size - 2 * d, yy + size - 2 * d, fill)
            c.drawRect(x, yy + size - 7 * d, x + size - 2 * d, yy + size - 2 * d, fill)
            fill.color = Color.rgb(248, 113, 113); c.drawRect(x, yy, x + size - 7 * d, yy + 5 * d, fill)
        }
        val done = list.size * 40f + 500f
        if (t > done) fadeTitle(c, cx, h * 0.2f + 5 * size + 60 * d, Color.WHITE, ((t - done) / 500f).coerceIn(0f, 1f))
    }

    /** Fire Tower: the lookout's beacon sweeps across and lights the number. */
    private fun drawBeacon(c: Canvas, w: Float, h: Float, cx: Float, cy: Float, t: Float, num: String) {
        val lx = cx; val ly = h * 0.68f
        val ang = Math.toRadians(-160.0 + 140.0 * (0.5 - 0.5 * cos(t / 1400.0 * PI)))
        val len = h
        val beam = Path().apply {
            moveTo(lx, ly)
            lineTo(lx + (cos(ang - 0.12) * len).toFloat(), ly + (sin(ang - 0.12) * len).toFloat())
            lineTo(lx + (cos(ang + 0.12) * len).toFloat(), ly + (sin(ang + 0.12) * len).toFloat()); close()
        }
        fill.shader = LinearGradient(lx, ly, lx + (cos(ang) * len).toFloat(), ly + (sin(ang) * len).toFloat(), Color.argb(150, 255, 214, 120), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawPath(beam, fill); fill.shader = null
        val tower = Path().apply { moveTo(lx - 10 * d, ly); lineTo(lx + 10 * d, ly); lineTo(lx + 24 * d, h * 0.92f); lineTo(lx - 24 * d, h * 0.92f); close() }
        fill.color = Color.rgb(59, 47, 34); c.drawPath(tower, fill)
        fill.color = Color.rgb(255, 214, 120)
        fill.setShadowLayer(18 * d, 0f, 0f, Color.rgb(255, 176, 32)); c.drawRoundRect(RectF(lx - 14 * d, ly - 14 * d, lx + 14 * d, ly + 2 * d), 4 * d, 4 * d, fill); fill.clearShadowLayer()
        // the number lights up as the beam passes over it
        val lit = ((t - 600) / 500f).coerceIn(0f, 1f)
        if (lit > 0) bigNumber(c, num, cx, cy, Color.argb((lit * 255).toInt(), 255, 245, 214), Color.rgb(255, 176, 32))
        if (t > 1100) fadeTitle(c, cx, cy + 70 * d, Color.rgb(255, 214, 120), ((t - 1100) / 500f).coerceIn(0f, 1f))
    }

    /** 8-Bit: coins pop out of a ? block, a pixel score counts up. */
    private fun drawCoins(c: Canvas, w: Float, h: Float, cx: Float, cy: Float, t: Float, num: String) {
        val by = h * 0.74f; val bs = 60 * d
        // stepped motion, like an old console
        val step = (t / 80f).toInt() * 80f
        for (i in 0 until 5) {
            val ph = ((step - i * 150f) % 1000f) / 1000f
            if (step < i * 150f) continue
            val up = if (ph < 0.5f) ph * 2 else 2 - ph * 2
            val x = cx + (i - 2) * 26 * d; val y = by - 20 * d - up * 100 * d
            fill.color = Color.rgb(250, 204, 21); c.drawOval(RectF(x - 11 * d, y - 15 * d, x + 11 * d, y + 15 * d), fill)
            stroke.color = Color.rgb(161, 98, 7); stroke.strokeWidth = 3 * d; c.drawOval(RectF(x - 11 * d, y - 15 * d, x + 11 * d, y + 15 * d), stroke)
        }
        val bump = if (step % 1000f < 160f) -8 * d else 0f
        fill.color = Color.rgb(217, 119, 6); c.drawRect(cx - bs / 2, by + bump, cx + bs / 2, by + bs + bump, fill)
        fill.color = Color.rgb(146, 64, 14); c.drawRect(cx + bs / 2 - 7 * d, by + bump, cx + bs / 2, by + bs + bump, fill); c.drawRect(cx - bs / 2, by + bs - 7 * d + bump, cx + bs / 2, by + bs + bump, fill)
        text.textSize = 36 * d; text.color = Color.WHITE; c.drawText("?", cx, by + bs * 0.68f + bump, text)
        val lime = Color.rgb(163, 230, 53)
        text.textSize = 130 * d; text.typeface = Typeface.MONOSPACE; text.isFakeBoldText = true
        text.color = Color.rgb(54, 83, 20); c.drawText(num, cx + 6 * d, cy + 6 * d, text)
        text.color = lime; c.drawText(num, cx, cy, text); text.isFakeBoldText = false
        val score = (min(1f, t / 1500f) * level * 420).toInt()
        text.textSize = 18 * d; text.color = Color.rgb(250, 204, 21); c.drawText("SCORE " + "%06d".format(score), cx, cy + 110 * d, text)
        text.typeface = theme.typeface ?: Typeface.DEFAULT_BOLD
        if (t > 600) fadeTitle(c, cx, cy + 70 * d, Color.WHITE, ((t - 600) / 400f).coerceIn(0f, 1f))
    }

    /** Arctic: an aurora ripples, snow falls, the number frosts over. */
    /** Tokyo Night (5, 15, 25…): shop lights and rain, a neon sign flickers on with the number. */
    private fun drawTokyo(c: Canvas, w: Float, h: Float, cx: Float, cy: Float, t: Float, num: String) {
        val cols = intArrayOf(Color.rgb(255, 77, 109), Color.rgb(255, 209, 102), Color.rgb(76, 201, 240), Color.rgb(247, 37, 133), Color.rgb(124, 255, 203))
        // a pink glow rising from the street
        fill.shader = LinearGradient(0f, h * 0.55f, 0f, h, Color.TRANSPARENT, Color.argb(50, 247, 37, 133), Shader.TileMode.CLAMP)
        c.drawRect(0f, h * 0.55f, w, h, fill); fill.shader = null
        for (i in 0 until 46) {
            val x = drops[i * 3] * w; val y = h * 0.45f + drops[i * 3 + 1] * h * 0.55f
            val tw = 0.6f + 0.4f * sin(t / 500f + i)
            val col = cols[i % cols.size]
            fill.color = Color.argb((60 * tw).toInt(), Color.red(col), Color.green(col), Color.blue(col)); c.drawCircle(x, y, 9 * d, fill)
            fill.color = Color.argb((255 * tw).toInt(), Color.red(col), Color.green(col), Color.blue(col)); c.drawCircle(x, y, 2.6f * d, fill)
        }
        stroke.color = Color.argb(90, 159, 180, 255); stroke.strokeWidth = 1 * d
        for (i in 0 until 50) {
            val x = drops[i * 3 + 2] * w; val ln = (16 + drops[i * 3] * 16) * d
            val y = ((drops[i * 3 + 1] * h + t * (0.7f + drops[i * 3] * 0.5f)) % (h + ln)) - ln
            c.drawLine(x, y, x - 3 * d, y + ln, stroke)
        }
        val pink = Color.rgb(255, 46, 99)
        // the sign: flickers on at 0.4 s, settles by 1.2 s
        val on = t > 400 && !(t in 520f..600f) && !(t in 700f..760f) && !(t in 900f..930f)
        if (t > 400) {
            val box = RectF(cx - 105 * d, cy - 135 * d, cx + 105 * d, cy + 40 * d)
            val a = if (on) 255 else 70
            stroke.color = Color.argb(a / 4, 255, 46, 99); stroke.strokeWidth = 12 * d; c.drawRoundRect(box, 18 * d, 18 * d, stroke)
            stroke.color = Color.argb(a, 255, 46, 99); stroke.strokeWidth = 3 * d; c.drawRoundRect(box, 18 * d, 18 * d, stroke)
            text.textSize = 130 * d
            text.color = Color.argb(a, 255, 255, 255)
            if (on) text.setShadowLayer(22 * d, 0f, 0f, pink)
            c.drawText(num, cx, cy, text); text.clearShadowLayer()
        }
        if (t > 1300) fadeTitle(c, cx, cy + 90 * d, Color.rgb(76, 201, 240), ((t - 1300) / 500f).coerceIn(0f, 1f))
    }

    /** Watercolour (5, 15, 25…): blots of paint bloom and drip, the number is painted in. */
    private fun drawPaint(c: Canvas, w: Float, h: Float, cx: Float, cy: Float, t: Float, num: String) {
        val blots = listOf(Triple(-0.28f, -0.25f, Color.rgb(242, 161, 170)), Triple(0.22f, -0.32f, Color.rgb(169, 204, 227)),
            Triple(-0.05f, 0.05f, Color.rgb(184, 216, 168)), Triple(0.3f, 0.08f, Color.rgb(247, 208, 138)), Triple(-0.3f, 0.18f, Color.rgb(195, 177, 225)))
        blots.forEachIndexed { i, (ox, oy, col) ->
            val k = ease(((t - i * 120f) / 1300f).coerceIn(0f, 1f))
            if (k <= 0f) return@forEachIndexed
            val x = cx + ox * w * 0.9f; val y = cy - 40 * d + oy * h * 0.35f; val r = (85 + i * 6) * d * k
            fill.shader = android.graphics.RadialGradient(x, y, r, intArrayOf(Color.argb(150, Color.red(col), Color.green(col), Color.blue(col)), Color.argb(110, Color.red(col), Color.green(col), Color.blue(col)), Color.TRANSPARENT), floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(x, y, r, fill); fill.shader = null
            // a drip runs down from the lower blots
            if (oy > 0f || i == 0) {
                val dk = ((t - 900 - i * 150f) / 1600f).coerceIn(0f, 1f)
                if (dk > 0f) {
                    fill.color = Color.argb(150, Color.red(col), Color.green(col), Color.blue(col))
                    val dx = x + (i - 2) * 6 * d
                    c.drawRoundRect(RectF(dx - 3.5f * d, y + r * 0.6f, dx + 3.5f * d, y + r * 0.6f + dk * 90 * d), 3.5f * d, 3.5f * d, fill)
                    c.drawCircle(dx, y + r * 0.6f + dk * 90 * d, 5 * d, fill)
                }
            }
        }
        // the number, painted in from the left
        val ink = Color.rgb(46, 64, 87)
        val k = ((t - 400) / 900f).coerceIn(0f, 1f)
        if (k > 0f) {
            text.textSize = 150 * d; text.color = ink
            val tw = text.measureText(num)
            c.save(); c.clipRect(cx - tw / 2 - 10 * d, 0f, cx - tw / 2 + (tw + 20 * d) * k, h); c.drawText(num, cx, cy, text); c.restore()
        }
        if (t > 1400) fadeTitle(c, cx, cy + 90 * d, Color.rgb(209, 73, 91), ((t - 1400) / 500f).coerceIn(0f, 1f))
    }

    private fun drawAurora(c: Canvas, w: Float, h: Float, cx: Float, cy: Float, t: Float, num: String) {
        val sh = sin(t / 900f) * 50 * d
        fun blob(x: Float, y: Float, r: Float, col: Int) {
            fill.shader = android.graphics.RadialGradient(x, y, r, col, Color.TRANSPARENT, Shader.TileMode.CLAMP)
            c.drawCircle(x, y, r, fill); fill.shader = null
        }
        blob(w * 0.3f + sh, h * 0.14f, 220 * d, Color.argb(140, 52, 211, 153))
        blob(w * 0.72f - sh, h * 0.1f, 200 * d, Color.argb(130, 129, 140, 248))
        fill.color = Color.argb(220, 224, 242, 254)
        for (i in 0 until 40) {
            val x = drops[i * 3] * w + sin(t / 700f + i) * 10 * d
            val y = ((drops[i * 3 + 1] * h + t * (0.05f + drops[i * 3 + 2] * 0.08f)) % h)
            c.drawCircle(x, y, (1.5f + drops[i * 3 + 2] * 2.5f) * d, fill)
        }
        val k = ((t - 300) / 900f).coerceIn(0f, 1f)
        if (k > 0) {
            text.textSize = 130 * d
            stroke.color = Color.rgb(125, 211, 252); stroke.strokeWidth = 3 * d
            val sp = text.style; text.style = Paint.Style.STROKE; text.strokeWidth = 4 * d; text.color = Color.rgb(125, 211, 252)
            c.drawText(num, cx, cy, text); text.style = sp
            // frost fills it from the bottom up
            c.save(); c.clipRect(0f, cy - 100 * d * k, w, cy + 30 * d)
            bigNumber(c, num, cx, cy, Color.rgb(224, 242, 254), Color.rgb(125, 211, 252)); c.restore()
        }
        if (t > 1200) fadeTitle(c, cx, cy + 70 * d, Color.rgb(125, 211, 252), ((t - 1200) / 500f).coerceIn(0f, 1f))
    }

    /** Catch 'Em Trail (5, 15, 25…): binoculars sweep a landscape and stop on the number on a hilltop. */
    private fun drawBinoculars(c: Canvas, w: Float, h: Float, cx: Float, cy: Float, t: Float, num: String) {
        val lr = minOf(w * 0.31f, 118 * d); val lcY = h * 0.48f
        val lens = Path().apply { addCircle(cx - lr * 0.6f, lcY, lr, Path.Direction.CW); addCircle(cx + lr * 0.6f, lcY, lr, Path.Direction.CW) }
        val landW = w * 3f; val target = landW * 0.73f
        val pan = ease(((t) / 2600f).coerceIn(0f, 1f)) * (target - cx)
        val zoom = 1f + 0.6f * ease(((t - 3000) / 700f).coerceIn(0f, 1f))
        c.save(); c.clipPath(lens)
        c.scale(zoom, zoom, cx, lcY); c.translate(-pan, 0f)
        val top = lcY - lr * 1.2f; val bottom = lcY + lr * 1.3f
        fill.shader = LinearGradient(0f, top, 0f, lcY + 30 * d, Color.rgb(125, 211, 252), Color.rgb(224, 242, 254), Shader.TileMode.CLAMP)
        c.drawRect(-w, top, landW + w, bottom, fill); fill.shader = null
        val hill = Path().apply {
            moveTo(-w, lcY + 70 * d)
            var x = -w; while (x < landW + w) { quadTo(x + 90 * d, lcY + 10 * d + ((x / d).toInt() % 3) * 14 * d, x + 180 * d, lcY + 60 * d); x += 180 * d }
            lineTo(landW + w, bottom); lineTo(-w, bottom); close()
        }
        fill.color = Color.rgb(134, 194, 122); c.drawPath(hill, fill)
        fill.color = Color.rgb(77, 154, 74); c.drawRect(-w, lcY + 80 * d, landW + w, bottom, fill)
        for (i in 0 until 12) {
            val tx = i * landW / 12f + 30 * d; if (kotlin.math.abs(tx - target) < 70 * d) continue
            fill.color = Color.rgb(91, 58, 30); c.drawRect(tx - 3 * d, lcY + 50 * d, tx + 3 * d, lcY + 66 * d, fill)
            fill.color = Color.rgb(47, 125, 50); c.drawCircle(tx, lcY + 44 * d, 14 * d, fill)
        }
        // the find, on its own hill, right in the middle of the view when the sweep stops
        fill.color = Color.rgb(134, 194, 122); c.drawCircle(target, lcY + 120 * d, 100 * d, fill)
        fill.shader = android.graphics.RadialGradient(target, lcY, 70 * d, Color.rgb(253, 230, 138), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawCircle(target, lcY, 70 * d, fill); fill.shader = null
        text.textSize = 56 * d; text.color = Color.rgb(67, 56, 202); c.drawText(num, target, lcY + 20 * d, text)
        c.restore()
        stroke.color = Color.rgb(51, 65, 85); stroke.strokeWidth = 6 * d
        // the outer rim only (no lines across the middle)
        c.save(); c.clipOutPathCompat(lens); c.drawPath(lens, stroke); c.restore()
        text.textSize = 22 * d; text.color = Color.rgb(199, 210, 254)
        c.drawText(L.t("Something on the hill…"), cx, lcY - lr - 30 * d, text)
        if (t > 3500) {
            val k = ((t - 3500) / 400f).coerceIn(0f, 1f)
            c.save(); c.scale(k, k, cx, lcY + lr + 60 * d)
            text.textSize = 26 * d; text.color = Color.WHITE; c.drawText(L.t("Rare find · Level $level").uppercase(), cx, lcY + lr + 50 * d, text)
            text.textSize = 22 * d; text.color = Color.rgb(129, 140, 248); c.drawText(L.t(title).uppercase(), cx, lcY + lr + 84 * d, text)
            c.restore()
        }
    }

    private fun Canvas.clipOutPathCompat(p: Path) {
        if (android.os.Build.VERSION.SDK_INT >= 26) clipOutPath(p) else @Suppress("DEPRECATION") clipPath(p, android.graphics.Region.Op.DIFFERENCE)
    }

    /** Catch 'Em Trail (10, 20, 30…): a night by the campfire, sparks rise and gather into the number. */
    private fun drawCampfire(c: Canvas, w: Float, h: Float, cx: Float, cy: Float, t: Float) {
        fill.shader = LinearGradient(0f, 0f, 0f, h, Color.rgb(10, 16, 48), Color.rgb(16, 26, 58), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, fill); fill.shader = null
        for (i in 0 until 40) {
            val tw = 0.6f + 0.6f * (0.5f + 0.5f * sin(t / 500f + i * 1.7f))
            fill.color = Color.WHITE; c.drawCircle(drops[i * 3] * w, drops[i * 3 + 1] * h * 0.55f, 1.3f * d * tw, fill)
        }
        fill.color = Color.rgb(20, 48, 31); c.drawRect(0f, h * 0.82f, w, h, fill)
        val tent = Path().apply { moveTo(w * 0.1f, h * 0.84f); lineTo(w * 0.28f, h * 0.71f); lineTo(w * 0.46f, h * 0.84f); close() }
        fill.color = Color.rgb(59, 91, 219); c.drawPath(tent, fill)
        val door = Path().apply { moveTo(w * 0.24f, h * 0.84f); lineTo(w * 0.28f, h * 0.76f); lineTo(w * 0.32f, h * 0.84f); close() }
        fill.color = Color.rgb(30, 42, 90); c.drawPath(door, fill)
        val fx = w * 0.62f; val fy = h * 0.83f
        fill.color = Color.rgb(107, 68, 35)
        c.save(); c.rotate(12f, fx, fy); c.drawRoundRect(RectF(fx - 30 * d, fy - 4 * d, fx + 30 * d, fy + 6 * d), 5 * d, 5 * d, fill); c.restore()
        c.save(); c.rotate(-12f, fx, fy); c.drawRoundRect(RectF(fx - 30 * d, fy - 4 * d, fx + 30 * d, fy + 6 * d), 5 * d, 5 * d, fill); c.restore()
        val fl = 1f + 0.15f * sin(t / 60f)
        fill.shader = android.graphics.RadialGradient(fx, fy - 20 * d, 40 * d, Color.argb(90, 251, 146, 60), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawCircle(fx, fy - 20 * d, 80 * d, fill); fill.shader = null
        val flame = Path().apply { moveTo(fx - 18 * d, fy); quadTo(fx - 20 * d, fy - 30 * d * fl, fx, fy - 56 * d * fl); quadTo(fx + 20 * d, fy - 30 * d * fl, fx + 18 * d, fy); close() }
        fill.color = Color.rgb(249, 115, 22); c.drawPath(flame, fill)
        val inner = Path().apply { moveTo(fx - 9 * d, fy); quadTo(fx - 10 * d, fy - 18 * d * fl, fx, fy - 32 * d * fl); quadTo(fx + 10 * d, fy - 18 * d * fl, fx + 9 * d, fy); close() }
        fill.color = Color.rgb(253, 224, 71); c.drawPath(inner, fill)
        // sparks: up from the fire, a little wander, then into place as the number
        val size = 24 * d
        val spots = cells(cx, h * 0.2f, size)
        spots.forEachIndexed { i, (sx, sy) ->
            val k = ((t - i * 45f) / 1800f).coerceIn(0f, 1f)
            if (k <= 0f) return@forEachIndexed
            val e = ease(k)
            val wob = sin(k * PI.toFloat()) * (if (i % 2 == 0) 40 else -40) * d
            val x = fx + (sx + size / 2 - fx) * e + wob
            val y = fy - 40 * d + (sy + size / 2 - (fy - 40 * d)) * e
            val r = (4 + 5 * e) * d
            fill.shader = android.graphics.RadialGradient(x, y, r * 1.6f, Color.rgb(255, 247, 204), Color.argb(0, 251, 146, 60), Shader.TileMode.CLAMP)
            c.drawCircle(x, y, r * 1.6f, fill); fill.shader = null
        }
        val done = spots.size * 45f + 1800f
        if (t > done) {
            val k = ((t - done) / 500f).coerceIn(0f, 1f)
            text.textSize = 26 * d; text.color = Color.WHITE; text.alpha = (k * 255).toInt()
            c.drawText(L.t(title).uppercase() + " · " + L.t("Level $level").uppercase(), cx, h * 0.2f + 5 * size + 50 * d, text)
            text.textSize = 15 * d; text.color = Color.rgb(254, 215, 170); text.alpha = (k * 255).toInt()
            c.drawText(L.t("A night under the stars"), cx, h * 0.2f + 5 * size + 78 * d, text); text.alpha = 255
        }
    }
}
