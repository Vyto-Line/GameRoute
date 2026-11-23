package gr.gamemap

import java.lang.reflect.Method

/**
 * Tests call MainActivity's functions by name. Since the clean-up (23 Nov 2026) most of them live
 * in MainXxx.kt files as extensions (static methods taking the activity first): this finds either.
 * Use like Method: ActivityMethod.of("closeSheet").apply { isAccessible = true }.invoke(activity)
 */
class ActivityMethod private constructor(private val m: Method, private val ext: Boolean) {
    var isAccessible: Boolean
        get() = m.isAccessible
        set(v) { m.isAccessible = v }

    fun invoke(target: Any?, vararg args: Any?): Any? = if (ext) m.invoke(null, target, *args) else m.invoke(target, *args)

    companion object {
        private val parts = listOf("Location", "Search", "Pharmacies", "Fuel", "Home", "Driving", "Place", "Arrival",
            "Suggestions", "Sheets", "Convoy", "Friends", "Invite")

        fun of(name: String, vararg types: Class<*>?): ActivityMethod {
            try { return ActivityMethod(MainActivity::class.java.getDeclaredMethod(name, *types), false) } catch (_: NoSuchMethodException) {}
            val all = arrayOf<Class<*>?>(MainActivity::class.java, *types)
            for (p in parts) {
                val k = try { Class.forName("gr.gamemap.Main${p}Kt") } catch (_: ClassNotFoundException) { continue }
                try { return ActivityMethod(k.getDeclaredMethod(name, *all), true) } catch (_: NoSuchMethodException) {}
            }
            throw NoSuchMethodException("MainActivity.$name")
        }
    }
}
