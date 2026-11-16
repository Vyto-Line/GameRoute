package gr.gamemap

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.LruCache
import java.util.Random
import java.util.concurrent.ConcurrentHashMap

/**
 * The "Blocky" world: real OpenStreetMap buildings, roads, water and land cover from
 * OpenFreeMap vector tiles (free, no key, fast CDN), turned into a grid of textured blocks.
 *
 * Each data tile (zoom 14, or the tile itself when zoomed further out) is rasterised once
 * into a 1024×1024 class grid; every map tile samples it one block (2×2 cells) at a time,
 * so a block is about 3.8 m wide at street level.
 */
object BlockWorld {
    private const val DATA_Z = VectorSource.MAX_Z
    private const val CELLS = 1024
    private const val CELLS_PER_BLOCK = 2
    private const val BLOCKS = CELLS / CELLS_PER_BLOCK

    // materials (stored in the red channel of the class grid)
    private const val GRASS = 1
    private const val LEAVES = 2
    private const val WATER = 3
    private const val SAND = 4
    private const val STONE = 5
    private const val STONE_BRICKS = 6
    private const val GRAVEL = 7
    private const val DIRT_PATH = 8
    private const val SMOOTH_STONE = 9
    private const val COBBLE = 10
    private const val FARMLAND = 11
    private const val BUILDING = 12
    private const val SNOW = 13
    private const val PITCH = 14

    private val grids = object : LruCache<String, IntArray>(6) {}
    private val locks = ConcurrentHashMap<String, Any>()

    /** A Blocky tile at any zoom, or null if the map data can't be loaded. */
    fun tile(z: Int, x: Int, y: Int, size: Int, night: Boolean = false): Bitmap? {
        val dz = minOf(z, DATA_Z)
        val sub = z - dz
        val ax = x shr sub; val ay = y shr sub
        val grid = grid(dz, ax, ay) ?: return null
        val blocksPerTile = BLOCKS shr sub
        val offX = (x - (ax shl sub)) * blocksPerTile
        val offY = (y - (ay shl sub)) * blocksPerTile
        val s = maxOf(1, size / blocksPerTile)
        val w = s * blocksPerTile
        val out = IntArray(w * w)
        for (by in 0 until blocksPerTile) for (bx in 0 until blocksPerTile) {
            val gx = offX + bx; val gy = offY + by
            val cls = sample(grid, gx, gy)
            val mat = Color.red(cls); val variant = Color.green(cls)
            val north = Color.red(sample(grid, gx, maxOf(0, gy - 1)))
            // Map-style shading: higher than the block to the north = lit, lower = in shadow.
            val shade = when {
                height(mat) > height(north) -> 1.12f
                height(mat) < height(north) -> 0.70f
                else -> 1f
            }
            val globalX = x.toLong() * blocksPerTile + bx; val globalY = y.toLong() * blocksPerTile + by
            val jitter = 0.95f + (hash(globalX, globalY, z.toLong()) % 100) / 1000f
            val tex = texture(mat, variant)
            // at night some buildings have a lit window in the middle of the block
            val lit = night && mat == BUILDING && hash(globalX, globalY, 7L) % 100 < 22
            for (v in 0 until s) for (u in 0 until s) {
                val c = if (s >= 4) tex[(v * 16 / s) * 16 + (u * 16 / s)] else tex[8 * 16 + 8]
                var px = scale(c, shade * jitter)
                if (night) {
                    px = Color.rgb((Color.red(px) * 0.34f).toInt(), (Color.green(px) * 0.40f).toInt(), (Color.blue(px) * 0.55f + 14).toInt().coerceAtMost(255))
                    val mid = s / 2; val win = maxOf(1, s / 5)
                    if (lit && u in mid - win until mid + win && v in mid - win until mid + win) px = Color.rgb(255, 214, 120)
                    if (mat == WATER && (u + v * 3 + globalX.toInt()) % 23 == 0) px = Color.rgb(90, 110, 170)
                }
                out[(by * s + v) * w + bx * s + u] = px
            }
        }
        return Bitmap.createBitmap(out, w, w, Bitmap.Config.ARGB_8888)
    }

    private fun sample(grid: IntArray, bx: Int, by: Int): Int {
        val cx = (bx * CELLS_PER_BLOCK + CELLS_PER_BLOCK / 2).coerceIn(0, CELLS - 1)
        val cy = (by * CELLS_PER_BLOCK + CELLS_PER_BLOCK / 2).coerceIn(0, CELLS - 1)
        return grid[cy * CELLS + cx]
    }

    private fun height(m: Int) = when (m) {
        BUILDING -> 3
        LEAVES -> 1
        WATER -> -1
        else -> 0
    }

    // ---------------------------------------------------------------- data

    private fun grid(z: Int, x: Int, y: Int): IntArray? {
        val key = "$z/$x/$y"
        grids.get(key)?.let { return it }
        synchronized(locks.getOrPut(key) { Any() }) {
            grids.get(key)?.let { return it }
            val vt = VectorSource.get(z, x, y) ?: return null
            val g = try { rasterise(vt, z) } catch (e: Exception) { return null }
            grids.put(key, g)
            return g
        }
    }

    // ---------------------------------------------------------------- rasterising

    private fun rasterise(tile: VectorTile, z: Int): IntArray {
        val bmp = Bitmap.createBitmap(CELLS, CELLS, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paint = Paint().apply { isAntiAlias = false; isDither = false; strokeCap = Paint.Cap.SQUARE; strokeJoin = Paint.Join.MITER }
        // Roads keep roughly real widths: a cell is ~1.9 m at zoom 14 and doubles per level out.
        val widthScale = 1f / (1 shl (DATA_Z - z))

        fun path(f: VectorTile.Feature, part: FloatArray, into: Path) {
            val k = CELLS.toFloat() / f.extent
            into.moveTo(part[0] * k, part[1] * k)
            var i = 2
            while (i < part.size) { into.lineTo(part[i] * k, part[i + 1] * k); i += 2 }
        }

        fun fill(f: VectorTile.Feature, mat: Int) {
            if (f.type != 3) return
            // Rings with positive area start a new polygon; negative ones are its holes.
            var p: Path? = null
            var first = 0f
            fun draw() {
                val pp = p ?: return
                val variant = if (mat == BUILDING) ((first.toInt() * 31) and 0x7fffffff) % 7 else 0
                paint.style = Paint.Style.FILL
                paint.color = Color.rgb(mat, variant, 0)
                c.drawPath(pp, paint)
            }
            for (part in f.parts) {
                if (part.size < 6) continue
                if (signedArea(part) >= 0 || p == null) {
                    draw()
                    p = Path().apply { fillType = Path.FillType.EVEN_ODD }
                    first = part[0] * 7 + part[1]
                }
                path(f, part, p!!)
                p!!.close()
            }
            draw()
        }

        fun line(f: VectorTile.Feature, mat: Int, width14: Float) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = maxOf(1.5f, width14 * widthScale)
            paint.color = Color.rgb(mat, 0, 0)
            val pp = Path()
            for (part in f.parts) path(f, part, pp)
            c.drawPath(pp, paint)
        }

        val byLayer = tile.features.groupBy { it.layer }
        fun layer(name: String) = byLayer[name] ?: emptyList()

        c.drawColor(Color.rgb(GRASS, 0, 0))
        for (f in layer("landcover")) when (f["class"]) {
            "wood" -> fill(f, LEAVES)
            "sand", "beach" -> fill(f, SAND)
            "farmland" -> fill(f, FARMLAND)
            "rock" -> fill(f, COBBLE)
            "ice" -> fill(f, SNOW)
        }
        for (f in layer("landuse")) when (f["class"]) {
            "commercial", "retail" -> fill(f, SMOOTH_STONE)
            "industrial", "railway", "quarry", "garages", "military" -> fill(f, COBBLE)
            "pitch", "stadium", "track" -> fill(f, PITCH)
            "playground", "theme_park" -> fill(f, SAND)
        }
        for (f in layer("water")) fill(f, WATER)
        for (f in layer("waterway")) if (f.type == 2) line(f, WATER, when (f["class"]) { "river" -> 6f; "canal" -> 5f; else -> 2f })

        val roads = layer("transportation") // tunnels too: driving through one, the road is still drawn
        // area features (pedestrian squares) first
        for (f in roads) if (f.type == 3) fill(f, SMOOTH_STONE)
        val order = listOf("path", "track", "rail", "transit", "service", "minor", "tertiary", "secondary", "primary", "trunk", "motorway")
        for (cls in order) for (f in roads) if (f.type == 2 && f["class"] == cls) when (cls) {
            "motorway", "trunk" -> line(f, STONE_BRICKS, 7f)
            "primary" -> line(f, STONE, 6f)
            "secondary", "tertiary" -> line(f, STONE, 5f)
            "minor" -> line(f, if (f["subclass"] == "pedestrian") SMOOTH_STONE else GRAVEL, 4f)
            "service" -> line(f, GRAVEL, 3f)
            "rail", "transit" -> line(f, GRAVEL, 1.5f)
            else -> line(f, DIRT_PATH, 2f)
        }
        for (f in layer("building")) fill(f, BUILDING)

        val px = IntArray(CELLS * CELLS)
        bmp.getPixels(px, 0, CELLS, 0, 0, CELLS, CELLS)
        bmp.recycle()
        return px
    }

    private fun signedArea(p: FloatArray): Float {
        var a = 0f
        var i = 0
        val n = p.size
        while (i < n) {
            val j = (i + 2) % n
            a += p[i] * p[j + 1] - p[j] * p[i + 1]
            i += 2
        }
        return a / 2
    }

    // ---------------------------------------------------------------- textures (16×16, procedural)

    private val textures = ConcurrentHashMap<Int, IntArray>()

    private fun texture(mat: Int, variant: Int): IntArray {
        val key = if (mat == BUILDING) 100 + variant else mat
        return textures.getOrPut(key) { makeTexture(key) }
    }

    private fun makeTexture(key: Int): IntArray {
        val r = Random(key * 7919L)
        val t = IntArray(256)
        fun noisy(base: Int, amount: Int) = scale(base, 1f + (r.nextInt(amount * 2 + 1) - amount) / 100f)
        for (v in 0 until 16) for (u in 0 until 16) {
            t[v * 16 + u] = when (key) {
                GRASS -> noisy(Color.rgb(95, 159, 53), 10)
                PITCH -> noisy(Color.rgb(118, 178, 70), 6)
                SNOW -> noisy(Color.rgb(240, 248, 250), 2)
                LEAVES -> if (r.nextInt(5) == 0) Color.rgb(28, 70, 22) else noisy(Color.rgb(56, 118, 42), 14)
                WATER -> if ((v + u / 4) % 5 == 0 && r.nextInt(3) > 0) Color.rgb(98, 150, 240) else noisy(Color.rgb(56, 104, 214), 5)
                SAND -> noisy(Color.rgb(219, 207, 163), 6)
                STONE -> noisy(Color.rgb(125, 125, 125), 9)
                SMOOTH_STONE -> if (u == 0 || v == 0) Color.rgb(150, 150, 150) else noisy(Color.rgb(170, 170, 170), 3)
                STONE_BRICKS -> mortar(u, v, Color.rgb(120, 120, 120), Color.rgb(84, 84, 84), r, 8)
                GRAVEL -> when (r.nextInt(6)) { 0 -> Color.rgb(95, 88, 86); 1 -> Color.rgb(160, 152, 150); else -> noisy(Color.rgb(128, 122, 120), 6) }
                DIRT_PATH -> if (r.nextInt(7) == 0) Color.rgb(120, 92, 58) else noisy(Color.rgb(148, 120, 74), 7)
                COBBLE -> if (r.nextInt(4) == 0) Color.rgb(80, 80, 80) else noisy(Color.rgb(118, 118, 118), 14)
                FARMLAND -> if (v % 4 == 0) Color.rgb(74, 46, 26) else noisy(Color.rgb(108, 72, 42), 8)
                // building roofs
                100 -> planks(u, v, Color.rgb(162, 130, 78), r)          // oak
                101 -> planks(u, v, Color.rgb(114, 84, 50), r)           // spruce
                102 -> mortar(u, v, Color.rgb(150, 74, 60), Color.rgb(186, 170, 160), r, 4) // bricks
                103 -> mortar(u, v, Color.rgb(122, 122, 122), Color.rgb(88, 88, 88), r, 8)  // stone bricks
                104 -> noisy(Color.rgb(161, 83, 37), 4)                  // terracotta
                105 -> noisy(Color.rgb(222, 222, 216), 3)                // white concrete
                else -> noisy(Color.rgb(208, 196, 176), 4)               // sandstone
            }
        }
        return t
    }

    private fun planks(u: Int, v: Int, base: Int, r: Random): Int = when {
        v % 4 == 3 -> scale(base, 0.70f)
        u == (v / 4 * 5 + 3) % 16 -> scale(base, 0.80f)
        else -> scale(base, 1f + (r.nextInt(9) - 4) / 100f)
    }

    private fun mortar(u: Int, v: Int, brick: Int, line: Int, r: Random, h: Int): Int {
        val row = v / h
        val off = if (row % 2 == 0) 0 else 4
        return if (v % h == h - 1 || (u + off) % 8 == 7) line else scale(brick, 1f + (r.nextInt(11) - 5) / 100f)
    }

    private fun scale(c: Int, f: Float) = Color.rgb(
        (Color.red(c) * f).toInt().coerceIn(0, 255),
        (Color.green(c) * f).toInt().coerceIn(0, 255),
        (Color.blue(c) * f).toInt().coerceIn(0, 255),
    )

    private fun hash(a: Long, b: Long, c: Long): Int {
        var h = a * 73856093L xor b * 19349663L xor c * 83492791L
        h = h xor (h ushr 13); h *= 0x5bd1e995L; h = h xor (h ushr 15)
        return ((h and 0x7fffffff) % 1000).toInt()
    }
}
