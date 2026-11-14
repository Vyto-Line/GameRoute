package gr.gamemap

import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * Line icons instead of emoji (emoji look different on every phone): a text that starts with
 * one of these emoji ("🏠 Home", "☆") shows the matching drawn icon, in the text's colour.
 * Applied to the whole screen before it's drawn, like the translation, so every chip, row,
 * menu and dialog gets it.
 */
object Icons {
    private val map: Map<String, Int> = mapOf(
        "📍" to R.drawable.ic_i_pin, "⛽" to R.drawable.ic_i_fuel, "★" to R.drawable.ic_act_star, "☆" to R.drawable.ic_act_star,
        "⭐" to R.drawable.ic_act_star, "✓" to R.drawable.ic_i_check, "👥" to R.drawable.ic_act_convoy, "☰" to R.drawable.ic_menu,
        "⚡" to R.drawable.ic_i_charger, "🔌" to R.drawable.ic_i_charger, "🏠" to R.drawable.ic_act_home, "💼" to R.drawable.ic_act_work,
        "🏁" to R.drawable.ic_flag, "🍽" to R.drawable.ic_i_food, "🛣" to R.drawable.ic_i_road, "☕" to R.drawable.ic_i_cafe,
        "🚦" to R.drawable.ic_traffic_light, "🚗" to R.drawable.ic_i_car, "🛒" to R.drawable.ic_i_cart, "🏙" to R.drawable.ic_i_area,
        "📷" to R.drawable.ic_i_camera, "⚠" to R.drawable.ic_i_warning, "💥" to R.drawable.ic_i_warning, "🚧" to R.drawable.ic_i_warning,
        "💊" to R.drawable.ic_i_pharmacy, "🍰" to R.drawable.ic_i_dessert, "🏨" to R.drawable.ic_i_hotel, "🎓" to R.drawable.ic_i_school,
        "🍸" to R.drawable.ic_i_bar, "🍺" to R.drawable.ic_i_bar, "🏦" to R.drawable.ic_i_bank, "🦷" to R.drawable.ic_i_tooth,
        "⛪" to R.drawable.ic_i_church, "🏋" to R.drawable.ic_i_gym, "🔧" to R.drawable.ic_i_wrench, "🏥" to R.drawable.ic_i_hospital,
        "🛍" to R.drawable.ic_i_shop, "🏛" to R.drawable.ic_i_museum, "✈" to R.drawable.ic_i_plane, "🚉" to R.drawable.ic_i_train,
        "🏖" to R.drawable.ic_i_beach, "🌳" to R.drawable.ic_i_tree, "👤" to R.drawable.ic_i_person, "🚶" to R.drawable.ic_i_person,
        "🔗" to R.drawable.ic_i_link, "🕘" to R.drawable.ic_i_clock, "⛔" to R.drawable.ic_i_none, "🚫" to R.drawable.ic_i_none,
        "🔊" to R.drawable.ic_volume_on, "🔇" to R.drawable.ic_volume_off, "🎮" to R.drawable.ic_theme, "🗺" to R.drawable.ic_set_map,
        "👑" to R.drawable.ic_i_trophy, "❤" to R.drawable.ic_i_heart, "👪" to R.drawable.ic_i_family, "⚽" to R.drawable.ic_i_ball,
        "💇" to R.drawable.ic_i_scissors, "🅿" to R.drawable.ic_i_parking, "✕" to R.drawable.ic_i_close, "＋" to R.drawable.ic_i_plus,
    )

    /** The icon for an emoji (or null: it stays as it is). */
    fun res(emoji: String): Int? = map[emoji.replace("️", "")]

    /** The emoji a text starts with, and what follows it. */
    private fun split(s: String): Pair<String, String>? {
        if (s.isEmpty()) return null
        val cp = s.codePointAt(0)
        var end = Character.charCount(cp)
        while (end < s.length && (s[end] == '️' || s[end] == '‍')) end++
        val e = s.substring(0, end)
        if (res(e) == null) return null
        return e to s.substring(end).trimStart()
    }

    private val doneKey = R.id.ic_done

    fun applyTree(v: View) {
        if (v.visibility == View.GONE) return
        if (v is TextView && v !is EditText) convert(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) applyTree(v.getChildAt(i))
    }

    private fun convert(tv: TextView) {
        val cur = tv.text?.toString() ?: return
        val done = tv.getTag(doneKey) as? String
        if (done == cur || done == MARK + cur) return
        val (e, rest) = split(cur) ?: run {
            // new text without an emoji: take away the icon this put there before
            if (done?.startsWith(MARK) == true) tv.setCompoundDrawablesRelative(null, null, null, null)
            tv.setTag(doneKey, cur); return
        }
        val dr = ContextCompat.getDrawable(tv.context, res(e)!!)?.mutate() ?: return
        dr.setTint(tv.currentTextColor)
        if (rest.isEmpty()) {
            // an icon on its own (a list's icon column, a ✕ or ☆ button): centred
            val size = (tv.textSize * 1.2f).toInt()
            dr.setBounds(0, 0, size, size)
            tv.text = ""
            tv.setCompoundDrawables(null, dr, null, null)
        } else {
            val size = (tv.textSize * 1.15f).toInt()
            dr.setBounds(0, 0, size, size)
            tv.text = rest
            tv.compoundDrawablePadding = (size * 0.4f).toInt()
            tv.setCompoundDrawablesRelative(dr, null, null, null)
        }
        tv.setTag(doneKey, MARK + tv.text.toString())
    }

    private const val MARK = "\u0001"
}
