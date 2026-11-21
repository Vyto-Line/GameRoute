package gr.gamemap

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface

/**
 * The newer destination flags (Garage, levels 22-45 and prestige), drawn standing on the spot
 * (x, y) in units of [u] pixels: the same drawing on the map, in the car and in the Garage.
 */
object Flags {
    val ids = setOf("flag_pitboard", "flag_banner", "flag_star", "flag_x", "flag_beacon", "flag_rank")
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val s = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
    private val path = Path()
    private val r = RectF()
    private val GOLD = Color.rgb(255, 210, 63)

    private fun pole(c: Canvas, x: Float, y: Float, h: Float, u: Float) {
        s.color = Color.rgb(40, 40, 46); s.strokeWidth = 3.4f * u; c.drawLine(x, y, x, y - h * u, s)
        s.color = Color.rgb(214, 220, 232); s.strokeWidth = 2.2f * u; c.drawLine(x, y, x, y - h * u, s)
        p.style = Paint.Style.FILL; p.color = Color.rgb(40, 40, 46); c.drawCircle(x, y, 2.6f * u, p)
    }

    private fun star(cx: Float, cy: Float, rOut: Float, rIn: Float): Path {
        path.reset()
        for (i in 0 until 10) {
            val a = Math.toRadians(-90.0 + i * 36.0)
            val rr = if (i % 2 == 0) rOut else rIn
            val px = cx + (rr * Math.cos(a)).toFloat(); val py = cy + (rr * Math.sin(a)).toFloat()
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close(); return path
    }

    /** Draws flag [id] with its foot at (x, y). [rank] = prestige rank colour for the rank pennant. */
    fun draw(c: Canvas, id: String, x: Float, y: Float, u: Float, rank: Int = 0) {
        p.style = Paint.Style.FILL; p.shader = null
        when (id) {
            "flag_pitboard" -> {
                pole(c, x, y, 22f, u)
                r.set(x - 17 * u, y - 50 * u, x + 17 * u, y - 22 * u)
                p.color = Color.rgb(17, 17, 17); c.drawRoundRect(r, 2 * u, 2 * u, p)
                s.color = GOLD; s.strokeWidth = 2 * u; c.drawRoundRect(r, 2 * u, 2 * u, s)
                t.color = GOLD; t.textSize = 11 * u; c.drawText("P1", x, y - 37 * u, t)
                t.color = Color.WHITE; t.textSize = 7 * u; c.drawText("BOX", x, y - 26.5f * u, t)
            }
            "flag_banner" -> {
                pole(c, x, y, 48f, u)
                p.color = GOLD; c.drawCircle(x, y - 49 * u, 2.6f * u, p)
                path.reset(); path.moveTo(x - 10 * u, y - 44 * u); path.lineTo(x + 10 * u, y - 44 * u); path.lineTo(x + 10 * u, y - 14 * u)
                path.lineTo(x, y - 20 * u); path.lineTo(x - 10 * u, y - 14 * u); path.close()
                p.color = Color.rgb(123, 44, 191); c.drawPath(path, p)
                s.color = GOLD; s.strokeWidth = 1.5f * u; c.drawPath(path, s)
                p.color = GOLD; c.drawPath(star(x, y - 31 * u, 6.5f * u, 2.7f * u), p)
            }
            "flag_star" -> {
                p.color = Color.argb(90, 0, 0, 0); r.set(x - 9 * u, y - 2.5f * u, x + 9 * u, y + 2.5f * u); c.drawOval(r, p)
                val st = star(x, y - 30 * u, 15 * u, 6.5f * u)
                p.color = Color.rgb(255, 225, 77); c.drawPath(st, p)
                s.color = Color.WHITE; s.strokeWidth = 1.6f * u; c.drawPath(st, s)
                p.color = Color.WHITE
                c.drawCircle(x + 13 * u, y - 44 * u, 1.5f * u, p); c.drawCircle(x - 14 * u, y - 36 * u, 1.2f * u, p); c.drawCircle(x + 10 * u, y - 18 * u, 1f * u, p)
            }
            "flag_x" -> {
                p.color = Color.argb(70, 0, 0, 0); r.set(x - 16 * u, y - 5 * u, x + 16 * u, y + 5 * u); c.drawOval(r, p)
                s.strokeWidth = 7 * u; s.color = Color.rgb(122, 21, 32)
                c.drawLine(x - 12 * u, y - 8 * u, x + 12 * u, y + 10 * u, s); c.drawLine(x + 12 * u, y - 8 * u, x - 12 * u, y + 10 * u, s)
                s.strokeWidth = 5.5f * u; s.color = Color.rgb(230, 57, 70)
                c.drawLine(x - 12 * u, y - 10 * u, x + 12 * u, y + 8 * u, s); c.drawLine(x + 12 * u, y - 10 * u, x - 12 * u, y + 8 * u, s)
            }
            "flag_beacon" -> {
                val top = y - 110 * u
                p.shader = LinearGradient(0f, y, 0f, top, Color.argb(230, 125, 249, 255), Color.argb(0, 125, 249, 255), Shader.TileMode.CLAMP)
                c.drawRect(x - 6 * u, top, x + 6 * u, y, p)
                p.shader = LinearGradient(0f, y, 0f, top, Color.argb(200, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP)
                c.drawRect(x - 2 * u, top, x + 2 * u, y, p)
                p.shader = null
                s.color = Color.rgb(125, 249, 255); s.strokeWidth = 2.2f * u
                r.set(x - 14 * u, y - 4.5f * u, x + 14 * u, y + 4.5f * u); c.drawOval(r, s)
                s.color = Color.argb(120, 125, 249, 255); s.strokeWidth = 1.2f * u
                r.set(x - 22 * u, y - 7 * u, x + 22 * u, y + 7 * u); c.drawOval(r, s)
            }
            "flag_rank" -> {
                pole(c, x, y, 40f, u)
                val col = if (rank != 0) rank else GOLD
                path.reset(); path.moveTo(x + 1 * u, y - 40 * u); path.lineTo(x + 25 * u, y - 40 * u); path.lineTo(x + 19 * u, y - 33 * u)
                path.lineTo(x + 25 * u, y - 26 * u); path.lineTo(x + 1 * u, y - 26 * u); path.close()
                p.color = col; c.drawPath(path, p)
                s.color = Color.WHITE; s.strokeWidth = 1.2f * u; c.drawPath(path, s)
                path.reset(); path.moveTo(x + 10 * u, y - 37 * u); path.lineTo(x + 14 * u, y - 33 * u); path.lineTo(x + 10 * u, y - 29 * u); path.lineTo(x + 6 * u, y - 33 * u); path.close()
                p.color = Color.rgb(125, 249, 255); c.drawPath(path, p); c.drawPath(path, s)
            }
        }
    }
}
