package gr.gamemap

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Themed map tiles, drawn on background threads from OpenFreeMap vector data and kept in
 * memory, and on the phone too (a finished square is saved, so after a restart it shows straight
 * away instead of being drawn again). Map data © OpenStreetMap contributors.
 */
object TileCache {
    val loaded = Signal()

    /** Why the last square couldn't be drawn (for tests and the crash log). */
    @Volatile var lastProblem: String? = null

    /** Pixel size of one tile; 512 gives crisp lines and text on dense screens. */
    var tilePx = 512
        private set

    /** Phones with little memory for the app: smaller caches, fewer slowdowns and out-of-memory crashes. */
    internal val lowMemory = Runtime.getRuntime().maxMemory() < 200L * 1024 * 1024

    private val mem = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / (if (lowMemory) 8 else 5)).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val failed = ConcurrentHashMap<String, Long>()

    /** Last time each tile was asked for: work nobody asked for recently is skipped. */
    private val wanted = ConcurrentHashMap<String, Long>()

    /** Newest request first, so the tiles on screen right now are drawn before older ones. */
    private class Lifo : LinkedBlockingDeque<Runnable>() {
        override fun offer(e: Runnable) = offerFirst(e)
    }
    private val threads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 3)
    private val pool = ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS, Lifo(), Bg.factory("tiles"))

    // ---- finished squares kept on the phone
    private var diskDir: java.io.File? = null
    private val saver = Bg.pool("tile-save", 1)
    /** Saved squares stay until the app's styles change (a new version) or 30 days pass. */
    private const val DISK_MAX = 200L * 1024 * 1024
    private const val DISK_DAYS = 30L

    private fun diskFile(k: String) = diskDir?.let { java.io.File(it, k.replace('/', '_') + ".webp") }

    private fun loadDisk(k: String): Bitmap? {
        val f = diskFile(k) ?: return null
        if (!f.exists()) return null
        if (System.currentTimeMillis() - f.lastModified() > DISK_DAYS * 86_400_000L) { f.delete(); return null }
        val theme = try { Theme.valueOf(k.substringBefore('/')) } catch (_: Exception) { return null }
        return try {
            val o = android.graphics.BitmapFactory.Options().apply {
                // the same kind of picture the drawing makes (half the memory for smooth styles)
                inPreferredConfig = if (theme.pixel == 1 && theme != Theme.BLOCKY) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
            }
            android.graphics.BitmapFactory.decodeFile(f.path, o)?.takeIf { it.width == tilePx / theme.pixel || theme == Theme.BLOCKY }
        } catch (_: Throwable) { null }
    }

    private fun saveDisk(k: String, bmp: Bitmap) {
        val f = diskFile(k) ?: return
        saver.execute {
            try {
                val tmp = java.io.File(f.path + ".tmp")
                tmp.outputStream().use { o ->
                    val fmt = if (android.os.Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
                    bmp.compress(fmt, 90, o)
                }
                tmp.renameTo(f)
            } catch (_: Throwable) {}
        }
    }

    /** Keeps the saved squares under DISK_MAX: the ones unused longest go first. */
    private fun trimDisk(dir: java.io.File) {
        val files = dir.listFiles() ?: return
        var total = files.sumOf { it.length() }
        if (total <= DISK_MAX) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= DISK_MAX * 3 / 4) break
            total -= f.length(); f.delete()
        }
    }

    fun init(ctx: Context) {
        val version = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).let { if (android.os.Build.VERSION.SDK_INT >= 28) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong() } } catch (_: Exception) { 0L }
        val root = java.io.File(ctx.cacheDir, "squares")
        val dir = java.io.File(root, "v$version")
        // squares drawn by an older version (its styles may have changed) are dropped
        root.listFiles()?.filter { it != dir }?.forEach { it.deleteRecursively() }
        dir.mkdirs()
        diskDir = dir
        saver.execute { trimDisk(dir) }
        VectorSource.init(ctx)
        Countries.init(ctx)
        Traffic.init(ctx)
        tilePx = if (ctx.resources.displayMetrics.density >= 1.5f) 512 else 256
    }

    /** Keeps only [fraction] of the drawn squares (most recently used). */
    fun trim(fraction: Double) = mem.trimToSize((mem.maxSize() * fraction).toInt())

    fun clear() {
        mem.evictAll()
        failed.clear()
    }

    private fun key(theme: Theme, z: Int, x: Int, y: Int) = "${theme.name}/${if (MapState.dark) "d" else "l"}/$z/$x/$y"

    /** Themed tile if ready; otherwise starts drawing it and returns null. */
    fun get(theme: Theme, z: Int, x: Int, y: Int): Bitmap? {
        val k = key(theme, z, x, y)
        mem.get(k)?.let { return it }
        val f = failed[k]
        if (f != null && System.currentTimeMillis() - f < 10_000) return null
        wanted[k] = System.currentTimeMillis()
        if (inFlight.add(k)) {
            pool.execute {
                try {
                    if (System.currentTimeMillis() - (wanted[k] ?: 0) > 3000) return@execute
                    // saved on the phone earlier: no drawing needed
                    loadDisk(k)?.let { saved ->
                        diskFile(k)?.setLastModified(System.currentTimeMillis())
                        mem.put(k, saved); failed.remove(k); loaded.fire()
                        return@execute
                    }
                    val bmp = if (theme == Theme.BLOCKY) BlockWorld.tile(z, x, y, tilePx, k.contains("/d/"))
                    else VectorRenderer.render(theme, z, x, y, tilePx, k.contains("/d/"))
                    if (bmp != null) {
                        saveDisk(k, bmp)
                        mem.put(k, bmp)
                        failed.remove(k)
                        loaded.fire()
                    } else {
                        failed[k] = System.currentTimeMillis(); lastProblem = "$k: no map data"
                    }
                } catch (t: Throwable) {
                    failed[k] = System.currentTimeMillis(); lastProblem = "$k: $t"
                } finally {
                    inFlight.remove(k)
                }
            }
        }
        return null
    }

    /**
     * Draw ahead: asks for a square only while the workers have nothing else to do, so the
     * squares on screen always come first. Returns true if it was asked for (or is ready).
     */
    fun prefetch(theme: Theme, z: Int, x: Int, y: Int): Boolean {
        if (mem.get(key(theme, z, x, y)) != null) return true
        if (pool.queue.isNotEmpty() || inFlight.size >= threads) return false
        get(theme, z, x, y)
        return true
    }

    /** Only what is already in memory (used for stand-ins while a tile is drawn). */
    fun peek(theme: Theme, z: Int, x: Int, y: Int): Bitmap? = mem.get(key(theme, z, x, y))

    /** Runs small background jobs (traffic downloads) on the same workers. */
    fun background(job: () -> Unit) = pool.execute(job)
}
