package gr.gamemap

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import java.util.Calendar

/**
 * Your recap: a month or a year on the road, as a card to share. Only totals and one favourite
 * drive: never Home or Work, never where you were or when (the card is made on the phone, and
 * shared only if you press Share).
 */
object Recap {
    class Stats(
        val period: String, val trips: Int, val km: Double, val hours: Double, val newRoadsKm: Double,
        val xp: Int, val favourite: String?, val longestKm: Double,
    )

    /** Lines you can show or hide on the card. */
    val LINES = listOf("km" to "Distance", "time" to "Time driving", "trips" to "Trips", "roads" to "New roads", "xp" to "XP earned", "fav" to "Favourite drive")

    /** This month (year = false) or this year, from the trip history. */
    fun stats(year: Boolean, now: Long = System.currentTimeMillis(), trips: List<Explorer.Trip> = Explorer.history()): Stats {
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val from = (cal.clone() as Calendar).apply {
            if (year) set(Calendar.DAY_OF_YEAR, 1) else set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val list = trips.filter { it.arrivedAt in from..now }
        val homeWork = setOfNotNull("Home", "Work", "Σπίτι", "Δουλειά", Places.saved("home")?.name, Places.saved("work")?.name)
        val fav = list.filter { it.place !in homeWork && it.place.isNotBlank() }
            .groupingBy { it.place }.eachCount().maxByOrNull { it.value }?.takeIf { it.value >= 2 }?.key
        val label = if (year) "${cal.get(Calendar.YEAR)}"
            else java.text.SimpleDateFormat("LLLL yyyy", if (L.greek) java.util.Locale("el") else java.util.Locale.ENGLISH).format(cal.time)
        return Stats(
            period = label.replaceFirstChar { it.uppercase() },
            trips = list.size,
            km = list.sumOf { it.meters } / 1000,
            hours = list.sumOf { it.seconds } / 3600.0,
            newRoadsKm = list.sumOf { t -> t.parts.filter { it.label == "New roads" }.sumOf { it.xp } } / 20.0, // 2 XP per 100 m
            xp = list.sumOf { it.xp },
            favourite = fav,
            longestKm = (list.maxOfOrNull { it.meters } ?: 0.0) / 1000,
        )
    }

    /** Draws the card (any size, 4:5 looks best) in the style's colours. */
    fun draw(c: Canvas, w: Float, h: Float, s: Stats, shown: Set<String>, theme: Theme, name: String, title: String) {
        val look = theme.look
        val dark = MapState.dark
        val bg = look.style.land
        val ink = if (Color.luminance(bg) > 0.45f) Color.rgb(24, 26, 32) else Color.WHITE
        val accent = Xp.routeColor(look)
        val u = w / 360f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, w, h, bg, blend(bg, accent, if (dark) 0.25f else 0.18f), Shader.TileMode.CLAMP)
        c.drawRoundRect(RectF(0f, 0f, w, h), 24 * u, 24 * u, p)
        p.shader = null
        // a faint road across the card, in the route colour
        p.style = Paint.Style.STROKE; p.strokeWidth = 10 * u; p.strokeCap = Paint.Cap.ROUND; p.color = withAlpha(accent, 70)
        val path = android.graphics.Path().apply { moveTo(-20 * u, h * 0.82f); cubicTo(w * 0.3f, h * 0.62f, w * 0.6f, h * 0.98f, w + 20 * u, h * 0.7f) }
        c.drawPath(path, p)
        p.style = Paint.Style.FILL
        val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = theme.typeface }
        t.color = accent; t.textSize = 13 * u; t.isFakeBoldText = true; t.letterSpacing = 0.12f
        c.drawText(L.t("YOUR RECAP").uppercase(), 24 * u, 40 * u, t)
        t.letterSpacing = 0f; t.color = ink; t.textSize = 30 * u
        c.drawText(s.period, 24 * u, 76 * u, t)
        t.isFakeBoldText = false; t.textSize = 14 * u; t.color = withAlpha(ink, 190)
        c.drawText(listOf(name.ifBlank { L.t("Driver") }, Xp.stars + L.t(title)).joinToString(" · "), 24 * u, 98 * u, t)
        // the numbers, two to a row
        val cells = ArrayList<Pair<String, String>>()
        if ("km" in shown) cells += String.format(java.util.Locale.US, "%,.0f km", s.km) to "Distance"
        if ("time" in shown) cells += (if (s.hours >= 1) String.format(java.util.Locale.US, "%.0f h", s.hours) else "${(s.hours * 60).toInt()} min") to "Time driving"
        if ("trips" in shown) cells += "${s.trips}" to "Trips"
        if ("roads" in shown) cells += String.format(java.util.Locale.US, "%.1f km", s.newRoadsKm) to "New roads"
        if ("xp" in shown) cells += String.format(java.util.Locale.US, "%,d", s.xp) to "XP earned"
        var i = 0
        val top = 130 * u; val cw = (w - 48 * u) / 2; val ch = 74 * u
        for ((big, small) in cells) {
            val x = 24 * u + (i % 2) * cw; val y = top + (i / 2) * ch
            t.color = ink; t.textSize = 30 * u; t.isFakeBoldText = true
            c.drawText(big, x, y + 32 * u, t)
            t.isFakeBoldText = false; t.textSize = 13 * u; t.color = withAlpha(ink, 170)
            c.drawText(L.t(small), x, y + 52 * u, t)
            i++
        }
        val fy = top + ((cells.size + 1) / 2) * ch + 14 * u
        if ("fav" in shown && s.favourite != null) {
            t.textSize = 13 * u; t.color = withAlpha(ink, 170)
            c.drawText(L.t("Favourite drive"), 24 * u, fy, t)
            t.textSize = 20 * u; t.color = ink; t.isFakeBoldText = true
            c.drawText(ellipsize(t, s.favourite, w - 48 * u), 24 * u, fy + 26 * u, t)
            t.isFakeBoldText = false
        }
        t.textSize = 12 * u; t.color = withAlpha(ink, 150); t.typeface = Typeface.DEFAULT_BOLD
        c.drawText("GameRoute · " + L.t(theme.title), 24 * u, h - 22 * u, t)
    }

    private fun ellipsize(t: Paint, s: String, max: Float): String {
        var x = s; while (x.length > 3 && t.measureText(x) > max) x = x.dropLast(2) + "…"; return x
    }

    private fun withAlpha(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))
    private fun blend(a: Int, b: Int, f: Float) = Color.rgb(
        (Color.red(a) * (1 - f) + Color.red(b) * f).toInt(), (Color.green(a) * (1 - f) + Color.green(b) * f).toInt(), (Color.blue(a) * (1 - f) + Color.blue(b) * f).toInt())
}
