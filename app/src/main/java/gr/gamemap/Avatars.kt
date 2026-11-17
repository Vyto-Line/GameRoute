package gr.gamemap

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * Avatars: 12 drawn pictures in a coloured circle (6 free, 4 for levels, 2 for prestige). Yours is
 * sent to friends as a short code with your "I'm here" ("fox:ea580c"), never as an image; their
 * phones draw it. Shown in lists (friends, convoy, the car's Friends panel), never on the map.
 */
object Avatars {
    /** [level] needed, or [rank] (prestige: 1 Bronze … 5 Diamond); both 0: free. */
    class Avatar(val id: String, val name: String, val color: Int, val level: Int = 0, val rank: Int = 0)

    val all = listOf(
        Avatar("helmet", "Racing helmet", 0xFFEF4444.toInt()),
        Avatar("car", "Hatchback", 0xFF3B82F6.toInt()),
        Avatar("compass", "Compass", 0xFF0D9488.toInt()),
        Avatar("peak", "Mountain", 0xFF7C3AED.toInt()),
        Avatar("wheel", "Steering wheel", 0xFFF59E0B.toInt()),
        Avatar("fox", "Fox", 0xFFEA580C.toInt()),
        Avatar("owl", "Night owl", 0xFF1E3A8A.toInt(), level = 10),
        Avatar("bolt", "Lightning racer", 0xFF0891B2.toInt(), level = 20),
        Avatar("dragon", "Road dragon", 0xFF15803D.toInt(), level = 30),
        Avatar("crown", "Crowned wheel", 0xFFA16207.toInt(), level = 40),
        Avatar("phoenix", "Bronze phoenix", 0xFF9A3412.toInt(), rank = 1),
        Avatar("comet", "Diamond comet", 0xFF312E81.toInt(), rank = 5),
    )

    /** Background colours to choose from. */
    val colors = intArrayOf(0xFFEF4444.toInt(), 0xFFEA580C.toInt(), 0xFFF59E0B.toInt(), 0xFF16A34A.toInt(),
        0xFF0D9488.toInt(), 0xFF3B82F6.toInt(), 0xFF7C3AED.toInt(), 0xFFDB2777.toInt())

    fun byId(id: String?) = all.firstOrNull { it.id == id }

    fun unlocked(a: Avatar) = (a.level == 0 || Explorer.level >= a.level || Xp.prestige > 0) && (a.rank == 0 || Xp.prestige >= a.rank)

    /** What unlocks it, for the picker ("Level 30", "Prestige: Bronze"). */
    fun lockText(a: Avatar) = when {
        a.rank > 0 -> L.t("Prestige: ${Xp.ranks.getOrElse(a.rank - 1) { "" }}")
        a.level > 0 -> L.t("Level ${a.level}")
        else -> ""
    }

    // ------------------------------------------------------------------ mine

    private lateinit var app: Context
    fun init(ctx: Context) { app = ctx.applicationContext }
    private fun prefs() = app.getSharedPreferences("gamemap", Context.MODE_PRIVATE)

    /** My avatar ("" = none chosen yet: my initial). */
    var mine: String
        get() = if (::app.isInitialized) prefs().getString("avatar", "").orEmpty() else ""
        set(v) { prefs().edit().putString("avatar", v).apply(); try { Friends.Presence.refresh() } catch (_: Exception) {} }

    /** My circle's colour (0 = the avatar's own). */
    var myColor: Int
        get() = if (::app.isInitialized) prefs().getInt("avatarColor", 0) else 0
        set(v) { prefs().edit().putInt("avatarColor", v).apply(); try { Friends.Presence.refresh() } catch (_: Exception) {} }

    /** The code friends get: "fox:ea580c" (or "" with none chosen). */
    fun myCode(): String {
        val a = byId(mine) ?: return ""
        val col = if (myColor != 0) myColor else a.color
        return a.id + ":" + Integer.toHexString(col and 0xFFFFFF)
    }

    /** A friend's code back to (avatar, colour); null for none or an unknown one. */
    fun parse(code: String?): Pair<Avatar, Int>? {
        if (code.isNullOrBlank()) return null
        val a = byId(code.substringBefore(':')) ?: return null
        val col = code.substringAfter(':', "").toIntOrNull(16)?.let { it or 0xFF000000.toInt() } ?: a.color
        return a to col
    }

    // ------------------------------------------------------------------ drawing

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }

    /** Draws avatar [a] in a circle of radius [r] at (cx, cy), on [bg]. */
    @Synchronized
    fun draw(c: Canvas, a: Avatar, bg: Int, cx: Float, cy: Float, r: Float) {
        fill.color = bg; fill.style = Paint.Style.FILL; c.drawCircle(cx, cy, r, fill)
        c.save()
        val s = r * 2 / 64f
        c.translate(cx - r, cy - r); c.scale(s, s)
        val white = Color.WHITE; val dark = 0xFF1F2937.toInt()
        fun p(build: Path.() -> Unit) = Path().apply(build)
        fun fillPath(path: Path, col: Int) { fill.color = col; c.drawPath(path, fill) }
        fun circle(x: Float, y: Float, rr: Float, col: Int) { fill.color = col; c.drawCircle(x, y, rr, fill) }
        fun stroke(col: Int, w: Float, draw: () -> Unit) { line.color = col; line.strokeWidth = w; draw() }
        when (a.id) {
            "helmet" -> {
                fillPath(p { moveTo(14f, 38f); cubicTo(14f, 18f, 46f, 14f, 50f, 32f); lineTo(50f, 42f); lineTo(22f, 42f); quadTo(16f, 42f, 14f, 38f); close() }, white)
                fill.color = dark; c.drawRect(30f, 30f, 52f, 38f, fill)
                stroke(0xFFB91C1C.toInt(), 3f) { c.drawLine(18f, 42f, 48f, 42f, line) }
            }
            "car" -> {
                fill.color = white; c.drawRoundRect(RectF(12f, 30f, 52f, 44f), 5f, 5f, fill)
                fillPath(p { moveTo(20f, 30f); lineTo(25f, 21f); lineTo(39f, 21f); lineTo(44f, 30f); close() }, white)
                fillPath(p { moveTo(24f, 29f); lineTo(27f, 23f); lineTo(37f, 23f); lineTo(40f, 29f); close() }, 0xFF93C5FD.toInt())
                circle(21f, 45f, 5f, dark); circle(43f, 45f, 5f, dark)
            }
            "compass" -> {
                stroke(white, 3f) { c.drawCircle(32f, 32f, 18f, line) }
                fillPath(p { moveTo(32f, 16f); lineTo(37f, 32f); lineTo(27f, 32f); close() }, 0xFFFCA5A5.toInt())
                fillPath(p { moveTo(32f, 48f); lineTo(27f, 32f); lineTo(37f, 32f); close() }, white)
            }
            "peak" -> {
                fillPath(p { moveTo(10f, 46f); lineTo(24f, 26f); lineTo(32f, 36f); lineTo(38f, 28f); lineTo(54f, 46f); close() }, white)
                fillPath(p { moveTo(24f, 26f); lineTo(28f, 32f); lineTo(24f, 34f); lineTo(20f, 32f); close() }, 0xFFC4B5FD.toInt())
            }
            "wheel" -> {
                stroke(white, 5f) { c.drawCircle(32f, 32f, 17f, line); c.drawLine(15f, 32f, 28f, 32f, line); c.drawLine(36f, 32f, 49f, 32f, line); c.drawLine(32f, 36f, 32f, 49f, line) }
                circle(32f, 32f, 4f, white)
            }
            "fox" -> {
                fillPath(p { moveTo(16f, 18f); lineTo(24f, 28f); lineTo(40f, 28f); lineTo(48f, 18f); lineTo(46f, 36f); lineTo(32f, 48f); lineTo(18f, 36f); close() }, white)
                fillPath(p { moveTo(26f, 34f); lineTo(32f, 40f); lineTo(38f, 34f); close() }, dark)
                circle(26f, 31f, 2f, dark); circle(38f, 31f, 2f, dark)
            }
            "owl" -> {
                fill.color = white; c.drawOval(RectF(17f, 22f, 47f, 50f), fill)
                circle(26f, 33f, 5f, 0xFFFDE047.toInt()); circle(38f, 33f, 5f, 0xFFFDE047.toInt())
                circle(26f, 33f, 2f, dark); circle(38f, 33f, 2f, dark)
                stroke(white, 3f) { c.drawLine(19f, 22f, 24f, 28f, line); c.drawLine(45f, 22f, 40f, 28f, line) }
                fillPath(p { moveTo(30f, 39f); lineTo(32f, 42f); lineTo(34f, 39f); close() }, 0xFFF59E0B.toInt())
            }
            "bolt" -> fillPath(p { moveTo(36f, 10f); lineTo(18f, 36f); lineTo(30f, 36f); lineTo(26f, 54f); lineTo(46f, 26f); lineTo(34f, 26f); close() }, 0xFFFDE047.toInt())
            "dragon" -> {
                fillPath(p { moveTo(14f, 40f); cubicTo(20f, 26f, 34f, 20f, 48f, 26f); lineTo(44f, 32f); lineTo(50f, 34f); lineTo(42f, 38f); cubicTo(40f, 46f, 30f, 50f, 20f, 46f); close() }, white)
                circle(40f, 30f, 2f, a.color)
                stroke(white, 3f) { c.drawPath(p { moveTo(20f, 26f); lineTo(16f, 18f); lineTo(24f, 22f); moveTo(28f, 22f); lineTo(26f, 14f); lineTo(32f, 20f) }, line) }
            }
            "crown" -> {
                stroke(white, 4f) { c.drawCircle(32f, 38f, 12f, line) }
                fillPath(p { moveTo(20f, 22f); lineTo(26f, 28f); lineTo(32f, 18f); lineTo(38f, 28f); lineTo(44f, 22f); lineTo(42f, 32f); lineTo(22f, 32f); close() }, 0xFFFDE047.toInt())
            }
            "phoenix" -> fillPath(p {
                moveTo(32f, 14f); cubicTo(36f, 22f, 44f, 24f, 50f, 22f); cubicTo(46f, 30f, 40f, 32f, 36f, 32f)
                cubicTo(42f, 34f, 46f, 40f, 44f, 46f); cubicTo(38f, 40f, 34f, 40f, 32f, 42f); cubicTo(30f, 40f, 26f, 40f, 20f, 46f)
                cubicTo(18f, 40f, 22f, 34f, 28f, 32f); cubicTo(24f, 32f, 18f, 30f, 14f, 22f); cubicTo(20f, 24f, 28f, 22f, 32f, 14f); close()
            }, 0xFFFDBA74.toInt())
            "comet" -> {
                stroke(0xCCA5F3FC.toInt(), 3f) { c.drawLine(34f, 30f, 12f, 52f, line); c.drawLine(38f, 32f, 20f, 54f, line); c.drawLine(30f, 26f, 10f, 44f, line) }
                circle(40f, 24f, 8f, 0xFFA5F3FC.toInt())
                fillPath(p { moveTo(40f, 18f); lineTo(42f, 22f); lineTo(46f, 24f); lineTo(42f, 26f); lineTo(40f, 30f); lineTo(38f, 26f); lineTo(34f, 24f); lineTo(38f, 22f); close() }, white)
            }
        }
        c.restore()
    }

    /** A friend's avatar if they've chosen one (else null: show their initial). */
    fun ofFriend(fid: String): Pair<Avatar, Int>? = parse(if (Friends.ready) Friends.avatars[fid] else null)
}
