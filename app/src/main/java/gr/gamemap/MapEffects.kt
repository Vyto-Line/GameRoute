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

internal fun MapRenderer.drawGrid(c: Canvas, a: Rect, d: Float) {
    stroke.color = Color.argb(45, 0, 240, 255); stroke.strokeWidth = 1 * d
    val step = 40 * d
    var x = a.left.toFloat(); while (x < a.right) { c.drawLine(x, a.top.toFloat(), x, a.bottom.toFloat(), stroke); x += step }
    var y = a.top.toFloat(); while (y < a.bottom) { c.drawLine(a.left.toFloat(), y, a.right.toFloat(), y, stroke); y += step }
    fill.shader = LinearGradient(
        0f, a.top.toFloat(), 0f, a.top + a.height() * 0.35f,
        Color.argb(160, 255, 43, 214), Color.TRANSPARENT, Shader.TileMode.CLAMP
    )
    c.drawRect(a.left.toFloat(), a.top.toFloat(), a.right.toFloat(), a.top + a.height() * 0.35f, fill)
    fill.shader = null
}

internal fun MapRenderer.drawScanlines(c: Canvas, a: Rect, d: Float, t: Theme) {
    stroke.color = Color.argb(50, 0, 0, 0); stroke.strokeWidth = 1.2f * d
    var y = a.top.toFloat(); while (y < a.bottom) { c.drawLine(a.left.toFloat(), y, a.right.toFloat(), y, stroke); y += 3 * d }
    return
    val r = hypot(a.width().toFloat(), a.height().toFloat()) / 2
    fill.shader = RadialGradient(
        a.exactCenterX(), a.exactCenterY(), r,
        intArrayOf(Color.TRANSPARENT, Color.argb(200, 0, 10, 0)), floatArrayOf(0.55f, 1f), Shader.TileMode.CLAMP
    )
    c.drawRect(a, fill)
    fill.shader = null
}

internal inline fun MapRenderer.overlayShader(key: String, make: () -> Shader): Shader =
    overlayShaders[key] ?: make().also { if (overlayShaders.size > 12) overlayShaders.clear(); overlayShaders[key] = it }

internal fun MapRenderer.drawWash(c: Canvas, a: Rect) {
    val dark = MapState.dark
    val tex = washTex?.takeIf { washDark == dark } ?: Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888).also { b ->
        val rnd = java.util.Random(11)
        for (y in 0 until 160) for (x in 0 until 160) {
            val v = rnd.nextInt(100)
            b.setPixel(x, y, when {
                v < 5 -> if (dark) Color.argb(26, 255, 245, 230) else Color.argb(30, 120, 100, 80)
                v > 94 -> if (dark) Color.argb(20, 0, 0, 0) else Color.argb(34, 255, 255, 255)
                else -> Color.TRANSPARENT
            })
        }
        washTex = b; washDark = dark
    }
    fill.shader = overlayShader("washTex$dark") { BitmapShader(tex, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) }
    c.drawRect(a, fill)
    fill.shader = overlayShader("washEdge$dark${a.width()}x${a.height()}") {
        val r = hypot(a.width().toFloat(), a.height().toFloat()) / 2
        val edge = if (dark) Color.argb(90, 30, 28, 36) else Color.argb(80, 255, 252, 244)
        RadialGradient(a.exactCenterX(), a.exactCenterY(), r, intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, edge),
            floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP)
    }
    c.drawRect(a, fill)
    fill.shader = null
}

internal fun MapRenderer.drawPaper(c: Canvas, a: Rect) {
    val tex = paperTex ?: Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).also { b ->
        val rnd = java.util.Random(7)
        for (y in 0 until 128) for (x in 0 until 128) {
            val v = rnd.nextInt(100)
            b.setPixel(x, y, when {
                v < 4 -> Color.argb(60, 90, 60, 30)
                v < 12 -> Color.argb(25, 120, 90, 50)
                v > 96 -> Color.argb(40, 255, 250, 235)
                else -> Color.TRANSPARENT
            })
        }
        paperTex = b
    }
    fill.shader = overlayShader("paperTex") { BitmapShader(tex, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) }
    c.drawRect(a, fill)
    fill.shader = overlayShader("paperEdge${a.width()}x${a.height()}") {
        val r = hypot(a.width().toFloat(), a.height().toFloat()) / 2
        RadialGradient(a.exactCenterX(), a.exactCenterY(), r,
            intArrayOf(Color.TRANSPARENT, Color.argb(70, 120, 80, 30), Color.argb(190, 80, 45, 15)),
            floatArrayOf(0.5f, 0.85f, 1f), Shader.TileMode.CLAMP)
    }
    c.drawRect(a, fill)
    fill.shader = null
}

/**
 * Violet Rain: a purple haze at the top and thin rain streaks. Faint on purpose: they never hide
 * a road or the route (the route and HUD are drawn on top).
 */
internal fun MapRenderer.drawRain(c: Canvas, a: Rect, d: Float) {
    val dark = MapState.dark
    val top = a.top.toFloat(); val hh = a.height() * 0.38f
    fill.shader = LinearGradient(0f, top, 0f, top + hh,
        intArrayOf(if (MapState.theme == Theme.TOKYO_NIGHT) Color.argb(110, 20, 24, 48) else if (dark) Color.argb(150, 42, 15, 74) else Color.argb(90, 150, 110, 210), Color.TRANSPARENT),
        null, Shader.TileMode.CLAMP)
    c.drawRect(a.left.toFloat(), top, a.right.toFloat(), top + hh, fill)
    fill.shader = null
    stroke.color = if (MapState.theme == Theme.TOKYO_NIGHT) Color.argb(48, 170, 190, 255) else if (dark) Color.argb(40, 217, 200, 255) else Color.argb(46, 90, 60, 150)
    stroke.strokeWidth = 1f * d
    // a drop crosses the screen in about two seconds (bigger, nearer drops a little faster),
    // falling a little sideways, and comes back in at the top somewhere else: a light rain
    val now = android.os.SystemClock.uptimeMillis()
    val w = a.width().toFloat(); val h = a.height().toFloat()
    val drops = rainDrops
    var i = 0
    while (i < drops.size) {
        val size = drops[i + 2]
        val len = size * 20 * d
        val period = (2600f - size * 700f) // ms per fall: 1550 to 2250
        val f = ((now + (drops[i + 1] * period).toLong()) % period.toLong()) / period
        val lap = (now + (drops[i + 1] * period).toLong()) / period.toLong()
        val x0 = ((drops[i] + lap * 0.618034) % 1.0).toFloat()
        val y = a.top - len + f * (h + len)
        val x = a.left + x0 * w + f * h * 0.18f - h * 0.09f
        c.drawLine(x, y, x + len * 0.18f, y + len, stroke)
        i += 3
    }
}

/**
 * Arctic: light snow drifting down and swaying, and a frosty haze at the top. Drawn faint and
 * under everything that matters (route, arrow, turn box come after it).
 */
internal fun MapRenderer.drawSnow(c: Canvas, a: Rect, d: Float) {
    val dark = MapState.dark
    val top = a.top.toFloat(); val hh = a.height() * 0.3f
    fill.shader = LinearGradient(0f, top, 0f, top + hh,
        intArrayOf(if (dark) Color.argb(110, 200, 220, 240) else Color.argb(150, 235, 244, 252), Color.TRANSPARENT),
        null, Shader.TileMode.CLAMP)
    c.drawRect(a.left.toFloat(), top, a.right.toFloat(), top + hh, fill)
    fill.shader = null
    val t = (android.os.SystemClock.uptimeMillis() % 120_000L) / 1000f
    val w = a.width().toFloat(); val h = a.height().toFloat()
    val f = snowFlakes
    var i = 0
    while (i < f.size) {
        val size = f[i + 2]
        val fall = (f[i + 1] + t * (0.035f + size * 0.025f)) % 1f   // big flakes fall a little faster
        val x = a.left + ((f[i] * w + kotlin.math.sin(t * 0.8f + f[i + 3]) * 14 * d) % w + w) % w
        val y = a.top + fall * h
        // on the white map the flakes need a cool edge to show at all
        fill.color = if (dark) Color.argb(170, 235, 242, 250) else Color.argb(230, 214, 228, 242)
        c.drawCircle(x, y, (1.6f + size * 1.6f) * d, fill)
        if (!dark) { stroke.color = Color.argb(170, 92, 128, 168); stroke.strokeWidth = 1f * d; c.drawCircle(x, y, (1.6f + size * 1.6f) * d, stroke) }
        i += 4
    }
}

/** Blueprint: a faint drafting grid, every fifth line a little stronger. */
internal fun MapRenderer.drawDrafting(c: Canvas, a: Rect, d: Float) {
    val dark = MapState.dark
    val step = 22 * d
    var i = 0; var x = a.left.toFloat()
    while (x < a.right) {
        stroke.color = if (dark) Color.argb(if (i % 5 == 0) 46 else 22, 207, 227, 255) else Color.argb(if (i % 5 == 0) 44 else 20, 30, 80, 166)
        stroke.strokeWidth = (if (i % 5 == 0) 1.2f else 0.8f) * d
        c.drawLine(x, a.top.toFloat(), x, a.bottom.toFloat(), stroke); x += step; i++
    }
    i = 0; var y = a.top.toFloat()
    while (y < a.bottom) {
        stroke.color = if (dark) Color.argb(if (i % 5 == 0) 46 else 22, 207, 227, 255) else Color.argb(if (i % 5 == 0) 44 else 20, 30, 80, 166)
        stroke.strokeWidth = (if (i % 5 == 0) 1.2f else 0.8f) * d
        c.drawLine(a.left.toFloat(), y, a.right.toFloat(), y, stroke); y += step; i++
    }
}

internal fun MapRenderer.drawSunGlow(c: Canvas, a: Rect) {
    fill.shader = LinearGradient(
        0f, a.top.toFloat(), 0f, a.top + a.height() * 0.5f,
        intArrayOf(Color.argb(150, 247, 160, 70), Color.argb(60, 232, 100, 80), Color.TRANSPARENT),
        floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP
    )
    c.drawRect(a.left.toFloat(), a.top.toFloat(), a.right.toFloat(), a.top + a.height() * 0.5f, fill)
    fill.shader = null
}
