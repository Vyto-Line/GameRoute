package gr.gamemap

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

/**
 * Street and place names, laid out on screen every frame: constant size whatever the zoom,
 * always upright, never overlapping, and each street named once in a neighbourhood.
 */
class Labels {

    private class Cand(
        val name: String,
        /** Points in world pixels at zoom [z]. */
        val pts: DoubleArray,
        val rank: Int,
        val place: Boolean,
        val textSize: Float,
        val minX: Double, val minY: Double, val maxX: Double, val maxY: Double,
    ) {
        /** Same label from frame to frame (and across tile reloads). */
        val id = "$name@${(pts[0] / 48).toInt()},${(pts[1] / 48).toInt()}"
    }

    // Steady labels while driving: names shown last frame keep their spot first, new ones
    // fade in, and a street name only flips to read upright with some margin.
    private var shownAt = HashMap<String, Long>()
    private var nowShown = HashMap<String, Long>()
    private val flipped = HashMap<String, Boolean>()
    private val order = ArrayList<Cand>(256)
    private var frameNow = 0L

    private var cacheKey = ""
    private var cands: List<Cand> = emptyList()

    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    private val placed = ArrayList<RectF>(64)
    private val boxPool = ArrayList<RectF>(64)
    private var boxUsed = 0
    private fun box(l: Float, t: Float, r: Float, b: Float): RectF {
        if (boxUsed == boxPool.size) boxPool += RectF()
        return boxPool[boxUsed++].apply { set(l, t, r, b) }
    }
    private val placedNames = ArrayList<Pair<String, FloatArray>>(64)
    private val pm = PathMeasure()
    private val bmpPaint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)

    /** Each name drawn once (text + halo) into a small image, then pasted every frame. */
    private val images = object : android.util.LruCache<String, android.graphics.Bitmap>(10 * 1024 * 1024) {
        override fun sizeOf(key: String, value: android.graphics.Bitmap) = value.byteCount
    }

    private fun image(name: String): android.graphics.Bitmap {
        val key = "$name|${text.textSize}|${text.color}|${halo.color}|${text.letterSpacing}|${text.typeface.hashCode()}"
        images.get(key)?.let { return it }
        val pad = halo.strokeWidth + 2
        text.textAlign = Paint.Align.LEFT; halo.textAlign = Paint.Align.LEFT
        val fm = text.fontMetrics
        val w = (text.measureText(name) + pad * 2).toInt().coerceAtLeast(1)
        val h = (fm.descent - fm.ascent + pad * 2).toInt().coerceAtLeast(1)
        val b = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawText(name, pad, pad - fm.ascent, halo)
        c.drawText(name, pad, pad - fm.ascent, text)
        images.put(key, b)
        return b
    }
    private var ink = 0
    private var countryInk = 0
    private val pos = FloatArray(2)
    private val tan = FloatArray(2)
    private val screen = FloatArray(512)

    fun draw(c: Canvas, cam: MapRenderer.Camera, z: Int, area: Rect, d: Float, theme: Theme) {
        val st = theme.style
        // zoomed out to countries: names in every style (also those that hide street names)
        val overview = z <= 7
        val plain = !st.labels || theme.pixel > 1 || st.label == 0
        if (plain && !overview) return
        val tilePx = TileCache.tilePx
        val dz = VectorSource.dataZoom(z)

        // data tiles under the view
        val reach = hypot(area.width().toDouble(), area.height().toDouble()) / cam.scale
        val dataPx = tilePx * 2.0.pow(z - dz) // one data tile, in world pixels at zoom z
        val ax0 = ((cam.px - reach) / dataPx).toInt(); val ax1 = ((cam.px + reach) / dataPx).toInt()
        val ay0 = ((cam.py - reach) / dataPx).toInt(); val ay1 = ((cam.py + reach) / dataPx).toInt()
        val tiles = ArrayList<Triple<Int, Int, VectorTile>>()
        val keyB = StringBuilder("$z/${gr.gamemap.L.greek}/")
        for (ay in ay0..ay1) for (ax in ax0..ax1) {
            val vt = VectorSource.peek(dz, ax, ay) ?: continue
            tiles += Triple(ax, ay, vt); keyB.append(ax).append(',').append(ay).append(';')
        }
        val key = keyB.toString()
        if (key != cacheKey) { cacheKey = key; cands = (if (overview) countries(z, cam.world) else emptyList()) + collect(tiles, z, dz, dataPx) }

        text.typeface = if (theme.serif) Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC)
        else Typeface.create("sans-serif-condensed", Typeface.BOLD)
        text.color = if (plain) (if (MapState.dark) android.graphics.Color.WHITE else android.graphics.Color.rgb(28, 28, 32)) else st.label
        halo.typeface = text.typeface
        halo.color = if (plain) (if (MapState.dark) android.graphics.Color.argb(220, 0, 0, 0) else android.graphics.Color.argb(230, 255, 255, 255)) else st.labelHalo
        ink = text.color
        countryInk = androidx.core.graphics.ColorUtils.blendARGB(ink, halo.color or (0xFF shl 24), 0.28f)
        halo.strokeWidth = 3.5f * d

        placed.clear(); placedNames.clear(); boxUsed = 0
        val a = Math.toRadians(-cam.bearing.toDouble())
        val ca = cos(a); val sa = sin(a)
        // world-pixel box of the screen, for quick rejection
        val vMinX = cam.px - reach; val vMaxX = cam.px + reach; val vMinY = cam.py - reach; val vMaxY = cam.py + reach
        var count = 0
        frameNow = android.os.SystemClock.uptimeMillis()
        order.clear()
        for (cd in cands) if (shownAt.containsKey(cd.id)) order += cd
        for (cd in cands) if (!shownAt.containsKey(cd.id)) order += cd
        nowShown.clear()

        for (cd in order) {
            if (count >= (if (overview) 44 else 32)) break
            if (cd.maxX < vMinX || cd.minX > vMaxX || cd.maxY < vMinY || cd.minY > vMaxY) continue
            val n = minOf(cd.pts.size, screen.size)
            var i = 0
            while (i < n) {
                val wx = (cd.pts[i] - cam.px) * cam.scale; val wy = (cd.pts[i + 1] - cam.py) * cam.scale
                screen[i] = (cam.cx + wx * ca - wy * sa).toFloat()
                screen[i + 1] = (cam.cy + wx * sa + wy * ca).toFloat()
                i += 2
            }
            text.textSize = cd.textSize * d; halo.textSize = text.textSize
            // countries in spaced capitals, a shade softer; cities and streets as written
            val country = cd.rank < 0
            text.letterSpacing = if (country) 0.14f else 0f; halo.letterSpacing = text.letterSpacing
            text.color = if (country) countryInk else ink
            val ok = if (cd.place) placePoint(c, cd, area, d) else placeStreet(c, cd, n, area, d)
            if (ok) { count++; nowShown[cd.id] = shownAt[cd.id] ?: frameNow }
        }
        val t = shownAt; shownAt = nowShown; nowShown = t
        text.letterSpacing = 0f; halo.letterSpacing = 0f
        if (flipped.size > 600) flipped.clear()
        bmpPaint.alpha = 255
    }

    /** New names fade in over a quarter second instead of popping. */
    private fun alphaFor(cd: Cand): Int {
        val since = frameNow - (shownAt[cd.id] ?: frameNow)
        return if (shownAt.containsKey(cd.id)) (80 + since * 175 / 250).toInt().coerceAtMost(255) else 80
    }

    private fun placePoint(c: Canvas, cd: Cand, area: Rect, d: Float): Boolean {
        val x = screen[0]; val y = screen[1]
        text.textAlign = Paint.Align.CENTER; halo.textAlign = Paint.Align.CENTER
        val w = text.measureText(cd.name) / 2 + 4 * d
        val box = box(x - w, y - text.textSize, x + w, y + text.textSize * 0.35f)
        if (!inside(box, area) || !free(box)) return false
        val img = image(cd.name)
        bmpPaint.alpha = alphaFor(cd)
        c.drawBitmap(img, x - img.width / 2f, y - img.height / 2f - text.textSize * 0.3f, bmpPaint)
        return true
    }

    private fun placeStreet(c: Canvas, cd: Cand, n: Int, area: Rect, d: Float): Boolean {
        // screen length
        var len = 0f
        var i = 2
        while (i < n) { len += hypot(screen[i] - screen[i - 2], screen[i + 1] - screen[i - 1]); i += 2 }
        val tw = text.measureText(cd.name)
        if (len < tw * 1.15f) return false
        // the middle of the street and its direction there (no Path objects: this runs every frame)
        var walked = 0f
        var mx = screen[0]; var my = screen[1]; var tx = 1f; var ty = 0f
        i = 2
        while (i < n) {
            val sx = screen[i] - screen[i - 2]; val sy = screen[i + 1] - screen[i - 1]
            val seg = hypot(sx, sy)
            if (walked + seg >= len / 2 && seg > 0) {
                val f = (len / 2 - walked) / seg
                mx = screen[i - 2] + sx * f; my = screen[i - 1] + sy * f
                tx = sx / seg; ty = sy / seg
                break
            }
            walked += seg; i += 2
        }
        // read left to right; once turned, only turn back when clearly needed (no flip-flopping
        // on near-vertical streets while the map rotates)
        val was = flipped[cd.id] ?: false
        val flip = if (was) tx < 0.25f else tx < -0.25f
        flipped[cd.id] = flip
        if (flip) { tx = -tx; ty = -ty }
        // same street already named close by?
        for ((nm, at) in placedNames) if (nm == cd.name && hypot(at[0] - mx, at[1] - my) < 260 * d) return false
        val hw = tw / 2; val h = text.textSize * 0.7f
        val ex = kotlin.math.abs(tx * hw) + kotlin.math.abs(ty * h); val ey = kotlin.math.abs(ty * hw) + kotlin.math.abs(tx * h)
        val box = box(mx - ex, my - ey, mx + ex, my + ey)
        if (!inside(box, area) || !free(box)) return false
        placedNames += cd.name to floatArrayOf(mx, my)
        val img = image(cd.name)
        bmpPaint.alpha = alphaFor(cd)
        c.save()
        c.translate(mx, my)
        c.rotate(Math.toDegrees(kotlin.math.atan2(ty.toDouble(), tx.toDouble())).toFloat())
        c.drawBitmap(img, -img.width / 2f, -img.height / 2f, bmpPaint)
        c.restore()
        return true
    }

    private fun inside(r: RectF, a: Rect) = r.left >= a.left && r.right <= a.right && r.top >= a.top && r.bottom <= a.bottom

    private fun free(r: RectF): Boolean {
        for (o in placed) if (RectF.intersects(o, r)) return false
        placed += r
        return true
    }

    /** Gathers names from the loaded data tiles, most important first. */
    private fun collect(tiles: List<Triple<Int, Int, VectorTile>>, z: Int, dz: Int, dataPx: Double): List<Cand> {
        val out = ArrayList<Cand>()
        for ((ax, ay, vt) in tiles) {
            for (f in vt.features) {
                val name = f["name"] ?: continue
                val k = dataPx / f.extent
                fun world(part: FloatArray): DoubleArray {
                    val r = DoubleArray(part.size)
                    var i = 0
                    while (i < part.size) { r[i] = ax * dataPx + part[i] * k; r[i + 1] = ay * dataPx + part[i + 1] * k; i += 2 }
                    return r
                }
                if (f.layer == "place" && f.type == 1) {
                    val (rank, size, minZ, maxZ) = when (f["class"]) {
                        "country" -> continue // from the built-in list (every country, at every zoom out)
                        "city" -> Quad(0, 17f, 4, 15)
                        "town" -> Quad(1, 15f, 9, 16)
                        "village" -> Quad(2, 13.5f, 12, 17)
                        "suburb" -> Quad(2, 13.5f, 12, 16)
                        "quarter", "neighbourhood" -> Quad(3, 12f, 14, 18)
                        else -> continue
                    }
                    if (z < minZ || z > maxZ) continue
                    val pt = f.parts.firstOrNull() ?: continue
                    if (pt[0] < 0 || pt[1] < 0 || pt[0] > f.extent || pt[1] > f.extent) continue // buffer copy
                    val w = world(pt)
                    // zoomed out (countries, capitals): in the app's language, not the local script
                    val shown = if (f["class"] == "country" || z < 9) {
                        if (gr.gamemap.L.greek) f["name:el"] ?: f["name"] ?: name
                        else f["name:en"] ?: f["name_en"] ?: f["name:latin"] ?: name
                    } else name
                    // capitals are for countries; towns read as written ("Larissa", not "LARISSA")
                    out += Cand(shown, w, rank, true, size, w[0], w[1], w[0], w[1])
                } else if (f.layer == "transportation_name" && f.type == 2 && z >= 15) {
                    val rank = when (f["class"]) {
                        "motorway", "trunk" -> 4; "primary" -> 5; "secondary" -> 6; "tertiary" -> 7
                        "minor" -> 8; "service" -> if (z >= 16) 9 else continue
                        "path" -> if (z >= 17) 10 else continue
                        else -> continue
                    }
                    val part = f.parts.firstOrNull() ?: continue
                    if (part.size < 4) continue
                    val w = world(part)
                    var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
                    var i = 0
                    while (i < w.size) {
                        minX = minOf(minX, w[i]); maxX = maxOf(maxX, w[i]); minY = minOf(minY, w[i + 1]); maxY = maxOf(maxY, w[i + 1]); i += 2
                    }
                    out += Cand(name, w, rank, false, 12.5f, minX, minY, maxX, maxY)
                }
            }
        }
        // important first; among streets, longer ones first
        return out.sortedWith(compareBy<Cand> { it.rank }.thenByDescending { (it.maxX - it.minX) + (it.maxY - it.minY) })
    }

    /**
     * Every country's name at its label point (Natural Earth), big ones first: the map data
     * zoomed out only carries a few, depending on what's loaded. Three copies, for the world's wrap.
     */
    private fun countries(z: Int, world: Double): List<Cand> {
        val out = ArrayList<Cand>()
        for (k in Countries.list) {
            if (z + 1.3 < k.minLabel) continue
            val name = (if (gr.gamemap.L.greek) k.el else k.en).uppercase()
            val size = when { k.rank <= 2 -> 15f; k.rank == 3 -> 14f; k.rank == 4 -> 13f; else -> 12f }
            val y = Geo.y(k.lat, world)
            for (wrap in -1..1) {
                val x = Geo.x(k.lon, world) + wrap * world
                out += Cand(name, doubleArrayOf(x, y), -10 + k.rank, true, size, x, y, x, y)
            }
        }
        return out
    }

    private data class Quad(val a: Int, val b: Float, val c: Int, val d: Int)
}

/** Country names and label points, from the app's assets (Natural Earth, public domain). */
object Countries {
    class C(val minLabel: Double, val rank: Int, val en: String, val el: String, val lon: Double, val lat: Double)
    @Volatile var list: List<C> = emptyList()
        private set

    fun init(ctx: android.content.Context) {
        if (list.isNotEmpty()) return
        list = try {
            ctx.assets.open("countries.tsv").bufferedReader().readLines().mapNotNull { line ->
                val f = line.split('\t')
                if (f.size < 6) null else C(f[0].toDouble(), f[1].toInt(), f[2], f[3], f[4].toDouble(), f[5].toDouble())
            }
        } catch (e: Exception) { emptyList() }
    }
}
