package gr.gamemap

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * If the app crashes (on the phone or in Android Auto), saves what happened to a file. The next
 * time the app opens it offers to send the report, so problems get fixed without guessing.
 * Nothing leaves the phone unless you choose to share it.
 */
object CrashLog {
    private var file: File? = null
    private var version = ""
    private val recent = ArrayDeque<String>()
    private val time = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "crash.txt")
        version = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName.orEmpty()
        } catch (e: Exception) { "?" }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try { save(thread, e) } catch (_: Throwable) {}
            previous?.uncaughtException(thread, e)
        }
    }

    /**
     * Watches the main thread: if it doesn't answer for 4 s (the app frozen, which makes Android
     * Auto give up on it), saves where it was stuck, like a crash, once per run. A freeze leaves no
     * crash behind on its own, so without this there would be nothing to go on.
     */
    @Volatile private var watching = false

    fun watchMainThread() {
        if (watching) return
        watching = true
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        Thread({
            var reported = false
            while (!reported) {
                val answered = java.util.concurrent.atomic.AtomicBoolean(false)
                main.post { answered.set(true) }
                try { Thread.sleep(4000) } catch (_: InterruptedException) { return@Thread }
                if (!answered.get()) {
                    val t = android.os.Looper.getMainLooper().thread
                    val e = RuntimeException("The app froze for 4+ s (the main thread did not answer)").apply { stackTrace = t.stackTrace }
                    try { save(t, e, "froze") } catch (_: Throwable) {}
                    reported = true
                }
            }
        }, "freeze-watch").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    /** A short note of what the app was doing (kept: the last 40), included in a report. */
    fun note(what: String) = synchronized(recent) {
        recent.addLast(time.format(Date()) + "  " + what)
        while (recent.size > 40) recent.removeFirst()
    }

    private fun save(thread: Thread, e: Throwable, what: String = "crashed") {
        val f = file ?: return
        val sw = StringWriter(); e.printStackTrace(PrintWriter(sw))
        val notes = synchronized(recent) { recent.joinToString("\n") }
        f.writeText(
            "GameRoute $version $what ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())}\n" +
            "Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
            "Thread: ${thread.name}\n\n$sw\nWhat it was doing:\n$notes\n"
        )
    }

    /** The report from the last crash, if there is one waiting. */
    fun pending(): String? = file?.takeIf { it.exists() }?.let { try { it.readText() } catch (e: Exception) { null } }

    fun clear() { file?.delete() }
}
