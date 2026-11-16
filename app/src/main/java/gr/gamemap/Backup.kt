package gr.gamemap

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Backup & restore: your progress in one file you keep (Drive, e-mail, a USB stick): XP and level,
 * prestige, the Garage, titles, trips, saved places, friends, settings, and the roads you've driven.
 * Not in it: map downloads (they download again) and anything temporary.
 *
 * Restoring replaces what's on this phone, then the app starts again.
 */
object Backup {
    const val KIND = "gameroute-backup"
    const val VERSION = 1

    /** The phone's settings files that hold progress (not caches). */
    private fun prefsNames(ctx: Context): List<String> {
        val dir = File(ctx.applicationInfo.dataDir, "shared_prefs")
        return dir.listFiles()?.map { it.name.removeSuffix(".xml") }
            ?.filter { !it.startsWith("WebView") && !it.startsWith("androidx.") && !it.contains("crash", true) }.orEmpty().sorted()
    }

    /** Small files worth keeping (the roads you've driven, for the new-roads XP). */
    private val FILES = listOf("driven.bin")

    fun export(ctx: Context, out: OutputStream) {
        val root = JSONObject().put("kind", KIND).put("v", VERSION).put("at", System.currentTimeMillis())
        val prefs = JSONObject()
        for (name in prefsNames(ctx)) {
            val o = JSONObject()
            for ((k, v) in ctx.getSharedPreferences(name, Context.MODE_PRIVATE).all) {
                val e = when (v) {
                    is Boolean -> JSONObject().put("t", "b").put("v", v)
                    is Int -> JSONObject().put("t", "i").put("v", v)
                    is Long -> JSONObject().put("t", "l").put("v", v)
                    is Float -> JSONObject().put("t", "f").put("v", v.toDouble())
                    is String -> JSONObject().put("t", "s").put("v", v)
                    is Set<*> -> JSONObject().put("t", "ss").put("v", JSONArray(v.map { it.toString() }))
                    else -> null
                } ?: continue
                o.put(k, e)
            }
            prefs.put(name, o)
        }
        root.put("prefs", prefs)
        val files = JSONObject()
        for (f in FILES) {
            val file = File(ctx.filesDir, f)
            if (file.exists() && file.length() < 20L * 1024 * 1024) files.put(f, android.util.Base64.encodeToString(file.readBytes(), android.util.Base64.NO_WRAP))
        }
        root.put("files", files)
        out.write(root.toString().toByteArray())
        out.flush()
    }

    /** What a backup file holds (for "Restore from 12 Nov, level 23?"), or null if it isn't one. */
    fun peek(input: InputStream): JSONObject? = try {
        JSONObject(input.readBytes().decodeToString()).takeIf { it.optString("kind") == KIND }
    } catch (e: Exception) { null }

    /** Puts everything back from a backup (already read with [peek]). Returns false if it isn't one. */
    fun restore(ctx: Context, root: JSONObject): Boolean {
        if (root.optString("kind") != KIND || root.optInt("v") > VERSION) return false
        val prefs = root.optJSONObject("prefs") ?: return false
        for (name in prefs.keys()) {
            val o = prefs.getJSONObject(name)
            val e = ctx.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
            for (k in o.keys()) {
                val x = o.getJSONObject(k)
                when (x.optString("t")) {
                    "b" -> e.putBoolean(k, x.getBoolean("v"))
                    "i" -> e.putInt(k, x.getInt("v"))
                    "l" -> e.putLong(k, x.getLong("v"))
                    "f" -> e.putFloat(k, x.getDouble("v").toFloat())
                    "s" -> e.putString(k, x.getString("v"))
                    "ss" -> { val a = x.getJSONArray("v"); e.putStringSet(k, (0 until a.length()).map { a.getString(it) }.toSet()) }
                }
            }
            e.commit()
        }
        root.optJSONObject("files")?.let { files ->
            for (f in files.keys()) if (f in FILES) File(ctx.filesDir, f).writeBytes(android.util.Base64.decode(files.getString(f), android.util.Base64.NO_WRAP))
        }
        return true
    }

    /** "Level 23 · 412 trips · 12 Nov 2026" from a backup, for the question before restoring. */
    fun describe(root: JSONObject): String {
        val g = root.optJSONObject("prefs")?.optJSONObject("gamemap")
        val xp = g?.optJSONObject("xp")?.optInt("v") ?: 0
        val at = java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(root.optLong("at")))
        return L.t("Level ${Explorer.levelFor(xp)}") + " · " + at
    }
}
