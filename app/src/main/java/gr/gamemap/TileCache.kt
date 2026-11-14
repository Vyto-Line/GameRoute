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
 * memory. Map data © OpenStreetMap contributors.
 */
object TileCache {
    val loaded = Signal()

    /** Pixel size of one tile; 512 gives crisp lines and text on dense screens. */
    var tilePx = 512
        private set

    private val mem = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 5).toInt()) {
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

    fun init(ctx: Context) {
        VectorSource.init(ctx)
        Countries.init(ctx)
        Traffic.init(ctx)
        tilePx = if (ctx.resources.displayMetrics.density >= 1.5f) 512 else 256
    }

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
                    val bmp = if (theme == Theme.BLOCKY) BlockWorld.tile(z, x, y, tilePx, k.contains("/d/"))
                    else VectorRenderer.render(theme, z, x, y, tilePx, k.contains("/d/"))
                    if (bmp != null) {
                        mem.put(k, bmp)
                        failed.remove(k)
                        loaded.fire()
                    } else {
                        failed[k] = System.currentTimeMillis()
                    }
                } catch (t: Throwable) {
                    failed[k] = System.currentTimeMillis()
                } finally {
                    inFlight.remove(k)
                }
            }
        }
        return null
    }

    /** Only what is already in memory (used for stand-ins while a tile is drawn). */
    fun peek(theme: Theme, z: Int, x: Int, y: Int): Bitmap? = mem.get(key(theme, z, x, y))

    /** Runs small background jobs (traffic downloads) on the same workers. */
    fun background(job: () -> Unit) = pool.execute(job)
}
