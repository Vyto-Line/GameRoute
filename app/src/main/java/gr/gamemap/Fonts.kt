package gr.gamemap

import android.content.Context
import android.graphics.Typeface

/** The styles' own fonts (assets/fonts, SIL Open Font License, with Greek), loaded once. */
object Fonts {
    private var ctx: Context? = null
    private val cache = HashMap<String, Typeface>()
    fun init(c: Context) { ctx = c.applicationContext }
    fun get(path: String): Typeface? = synchronized(cache) {
        cache[path] ?: try { ctx?.let { Typeface.createFromAsset(it.assets, path) }?.also { cache[path] = it } } catch (e: Exception) { null }
    }
}
