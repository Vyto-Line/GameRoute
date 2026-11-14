package gr.gamemap

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.LruCache
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Useful places drawn on the map as small round icons (fuel, parking, food, cafés, pharmacies,
 * supermarkets), tappable. Built to stay light: the places of each map tile are picked out once
 * and kept, icons are pre-drawn images, at most [MAX] are shown and never on top of each other.
 */
class PoiLayer {
    enum class Kind(val color: Int, val label: String, val amenity: String, val icon: String) {
        FUEL(Color.rgb(232, 89, 12), "Fuel", "fuel", "⛽"),
        CHARGER(Color.rgb(0, 150, 136), "EV charger", "charging_station", "🔌"),
        PARKING(Color.rgb(31, 111, 235), "Car park", "parking", "🅿"),
        FOOD(Color.rgb(194, 37, 92), "Food", "restaurant", "🍽"),
        CAFE(Color.rgb(139, 94, 60), "Café", "cafe", "☕"),
        PHARMACY(Color.rgb(47, 158, 68), "Pharmacy", "pharmacy", "💊"),
        SHOP(Color.rgb(112, 72, 232), "Supermarket", "supermarket", "🛒"),
    }

    class Poi(val name: String, val kind: Kind, val pos: LatLng, val rank: Int) {
        /** As a destination. */
        fun place() = Place(name.ifBlank { kind.label }, kind.label, pos, kind.icon)
    }

    companion object {
        const val MIN_ZOOM = 15.5
        private const val MAX = 28
        /** At most this many car parks at once (unnamed street parking is everywhere in a city). */
        private const val MAX_PARKING = 6

        /** Places of each data tile, picked out once ("z/x/y" → list). */
        private val perTile = object : LruCache<String, List<Poi>>(160) {}

        private fun kindOf(cls: String?, sub: String?): Kind? = when {
            // (the map data files EV chargers under "fuel" too: they're often at shops and car parks)
            cls == "fuel" && sub == "charging_station" -> Kind.CHARGER
            cls == "fuel" -> Kind.FUEL
            cls == "parking" && sub != "bicycle_parking" && sub != "motorcycle_parking" -> Kind.PARKING
            cls == "restaurant" || cls == "fast_food" -> Kind.FOOD
            cls == "cafe" -> Kind.CAFE
            cls == "pharmacy" -> Kind.PHARMACY
            cls == "grocery" && sub == "supermarket" -> Kind.SHOP
            else -> null
        }

        fun of(z: Int, x: Int, y: Int, vt: VectorTile): List<Poi> {
            val key = "$z/$x/$y"
            perTile.get(key)?.let { return it }
            val n = (1 shl z).toDouble()
            val out = ArrayList<Poi>()
            for (f in vt.features) {
                if (f.layer != "poi" || f.type != 1) continue
                val kind = kindOf(f["class"], f["subclass"]) ?: continue
                val part = f.parts.firstOrNull() ?: continue
                if (part.size < 2) continue
                val px = part[0]; val py = part[1]
                if (px < 0 || py < 0 || px > f.extent || py > f.extent) continue
                val pos = LatLng(Geo.lat((y + py / f.extent.toDouble()) / n, 1.0), Geo.lon((x + px / f.extent.toDouble()) / n, 1.0))
                out += Poi(f["name"].orEmpty(), kind, pos, f["rank"]?.toIntOrNull() ?: 50)
            }
            out.sortBy { it.rank }
            perTile.put(key, out)
            return out
        }
    }

    // ---- icons, drawn once per kind and size
    private val icons = HashMap<String, Bitmap>()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    private fun icon(kind: Kind, sizePx: Int): Bitmap = icons.getOrPut("${kind.name}/$sizePx") {
        val b = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val r = sizePx / 2f
        p.style = Paint.Style.FILL; p.color = Color.argb(70, 0, 0, 0)
        c.drawCircle(r, r + sizePx * 0.03f, r * 0.94f, p)
        p.color = Color.WHITE; c.drawCircle(r, r, r * 0.92f, p)
        p.color = kind.color; c.drawCircle(r, r, r * 0.76f, p)
        p.style = Paint.Style.STROKE; p.color = Color.WHITE
        p.strokeWidth = sizePx * 0.075f; p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
        val s = r * 0.42f // glyph half size
        path.reset()
        when (kind) {
            Kind.FUEL -> { // pump
                path.addRect(r - s * 0.8f, r - s, r + s * 0.3f, r + s, Path.Direction.CW)
                path.moveTo(r - s * 0.8f, r - s * 0.2f); path.lineTo(r + s * 0.3f, r - s * 0.2f)
                path.moveTo(r + s * 0.3f, r - s * 0.6f); path.lineTo(r + s * 0.9f, r - s * 0.1f); path.lineTo(r + s * 0.9f, r + s * 0.7f)
            }
            Kind.PARKING -> { // P
                path.moveTo(r - s * 0.45f, r + s); path.lineTo(r - s * 0.45f, r - s)
                path.lineTo(r + s * 0.15f, r - s)
                path.quadTo(r + s * 0.85f, r - s, r + s * 0.85f, r - s * 0.35f)
                path.quadTo(r + s * 0.85f, r + s * 0.3f, r + s * 0.15f, r + s * 0.3f)
                path.lineTo(r - s * 0.45f, r + s * 0.3f)
            }
            Kind.FOOD -> { // fork and knife
                path.moveTo(r - s * 0.5f, r - s); path.lineTo(r - s * 0.5f, r + s)
                path.moveTo(r - s * 0.85f, r - s); path.lineTo(r - s * 0.85f, r - s * 0.2f); path.lineTo(r - s * 0.15f, r - s * 0.2f); path.lineTo(r - s * 0.15f, r - s)
                path.moveTo(r + s * 0.55f, r + s); path.lineTo(r + s * 0.55f, r - s); path.quadTo(r + s * 0.1f, r - s * 0.4f, r + s * 0.55f, r)
            }
            Kind.CAFE -> { // cup
                path.moveTo(r - s * 0.85f, r - s * 0.4f); path.lineTo(r + s * 0.45f, r - s * 0.4f)
                path.lineTo(r + s * 0.35f, r + s * 0.6f); path.lineTo(r - s * 0.75f, r + s * 0.6f); path.close()
                path.moveTo(r + s * 0.45f, r - s * 0.2f); path.quadTo(r + s * 1.05f, r - s * 0.15f, r + s * 0.4f, r + s * 0.3f)
            }
            Kind.CHARGER -> { // lightning bolt
                path.moveTo(r + s * 0.15f, r - s); path.lineTo(r - s * 0.55f, r + s * 0.1f); path.lineTo(r + s * 0.05f, r + s * 0.1f)
                path.lineTo(r - s * 0.15f, r + s); path.lineTo(r + s * 0.55f, r - s * 0.1f); path.lineTo(r - s * 0.05f, r - s * 0.1f); path.close()
            }
            Kind.PHARMACY -> { // cross
                path.moveTo(r, r - s); path.lineTo(r, r + s); path.moveTo(r - s, r); path.lineTo(r + s, r)
            }
            Kind.SHOP -> { // basket
                path.moveTo(r - s, r - s * 0.2f); path.lineTo(r + s, r - s * 0.2f); path.lineTo(r + s * 0.7f, r + s * 0.9f)
                path.lineTo(r - s * 0.7f, r + s * 0.9f); path.close()
                path.moveTo(r - s * 0.45f, r - s * 0.2f); path.lineTo(r - s * 0.1f, r - s); path.moveTo(r + s * 0.45f, r - s * 0.2f); path.lineTo(r + s * 0.1f, r - s)
            }
        }
        c.drawPath(path, p)
        b
    }

    // ---- what was drawn, for taps (double-buffered: taps may come from another thread)
    private class Hits(val x: FloatArray = FloatArray(MAX), val y: FloatArray = FloatArray(MAX), val poi: Array<Poi?> = arrayOfNulls(MAX)) { var n = 0 }
    private var building = Hits()
    @Volatile private var shown = Hits()
    @Volatile var hitRadius = 24f
        private set

    /** The place under a screen point (generous: car screens and fingers aren't precise). */
    fun at(x: Float, y: Float): Poi? {
        val h = shown
        var best: Poi? = null; var bestD = hitRadius * hitRadius
        for (i in 0 until h.n) {
            val dx = h.x[i] - x; val dy = h.y[i] - y
            val d = dx * dx + dy * dy
            if (d <= bestD) { bestD = d; best = h.poi[i] }
        }
        return best
    }

    private val icon = Paint().apply { isFilterBitmap = true }

    /** What was on screen last frame (by position): kept first, so icons don't swap as the map moves. */
    private var lastShown = HashSet<Long>()
    private var nowShown = HashSet<Long>()
    private class Cand(val poi: Poi, val sx: Float, val sy: Float, val key: Long, val prio: Int)
    private val cands = ArrayList<Cand>()

    private fun keyOf(p: LatLng) = ((p.lat * 1e5).toLong() shl 32) xor (p.lon * 1e5).toLong()

    /** Which kind wins when two would overlap. */
    private fun kindPrio(k: Kind) = when (k) {
        Kind.FUEL -> 0; Kind.PHARMACY -> 1; Kind.CHARGER -> 2; Kind.SHOP -> 3; Kind.PARKING -> 4; Kind.CAFE -> 5; Kind.FOOD -> 6
    }

    /**
     * Draws the icons on the (unrotated) screen. [only] limits the kinds (while navigating:
     * fuel and parking); [selected] gets a ring.
     */
    fun draw(c: Canvas, cam: MapRenderer.Camera, zoom: Double, w: Int, h: Int, d: Float, only: Set<Kind>?, selected: LatLng?) {
        val out = building
        out.n = 0
        if (zoom < MIN_ZOOM) { lastShown.clear(); publish(); return }
        val size = (30 * d).toInt()
        val half = size / 2f
        hitRadius = 26 * d
        val dz = VectorSource.MAX_Z
        val nz = 1 shl dz
        // data tiles around the camera centre
        val worldDz = TileCache.tilePx * Math.pow(2.0, dz.toDouble())
        val k = worldDz / cam.world
        val cxw = cam.px * k; val cyw = cam.py * k
        val reach = Math.hypot(w.toDouble(), h.toDouble()) * 0.6 / cam.scale * k
        val t0x = floor((cxw - reach) / TileCache.tilePx).toInt(); val t1x = floor((cxw + reach) / TileCache.tilePx).toInt()
        val t0y = floor((cyw - reach) / TileCache.tilePx).toInt().coerceAtLeast(0); val t1y = floor((cyw + reach) / TileCache.tilePx).toInt().coerceAtMost(nz - 1)
        if ((t1x - t0x + 1) * (t1y - t0y + 1) > 30) { publish(); return }
        val a = Math.toRadians(-cam.bearing.toDouble())
        val ca = cos(a); val sa = sin(a)
        val chargers = Fuel.showChargers
        val dest = if (NavEngine.active) NavEngine.destination else null
        cands.clear()
        for (ty in t0y..t1y) for (tx in t0x..t1x) {
            val vt = VectorSource.peek(dz, tx.mod(nz), ty) ?: continue
            for (poi in of(dz, tx.mod(nz), ty, vt)) {
                if (only != null && poi.kind !in only) continue
                when (poi.kind) {
                    // busy kinds only up close (a city centre has a café and a pharmacy on every corner)
                    Kind.FOOD, Kind.CAFE, Kind.SHOP, Kind.PHARMACY -> if (zoom < 17.0) continue
                    // car parks: up close, or near where you're going (the centre has one on every block)
                    Kind.PARKING -> if (dest != null) { if (Geo.distance(dest, poi.pos) > 800) continue } else if (zoom < 17.0) continue
                    Kind.CHARGER -> if (!chargers || zoom < 16.5) continue
                    Kind.FUEL -> {}
                }
                val wx = (Geo.x(poi.pos.lon, cam.world) - cam.px) * cam.scale
                val wy = (Geo.y(poi.pos.lat, cam.world) - cam.py) * cam.scale
                val sx = (cam.cx + wx * ca - wy * sa).toFloat()
                val sy = (cam.cy + wx * sa + wy * ca).toFloat()
                if (sx < -half || sy < -half || sx > w + half || sy > h + half) continue
                val key = keyOf(poi.pos)
                val prio = (if (key in lastShown) 0 else 100_000) + kindPrio(poi.kind) * 1000 + poi.rank.coerceIn(0, 999)
                cands += Cand(poi, sx, sy, key, prio)
            }
        }
        cands.sortBy { it.prio }
        // placed best first; never on top of each other (a real distance, not a grid that moves with the map)
        val minD = size * 1.1f
        val minD2 = minD * minD
        var parking = 0
        nowShown.clear()
        var ringX = Float.NaN; var ringY = 0f; var ringKind: Kind? = null
        for (cd in cands) {
            if (out.n >= MAX) break
            if (cd.poi.kind == Kind.PARKING && parking >= MAX_PARKING) continue
            var free = true
            for (i in 0 until out.n) {
                val dx = out.x[i] - cd.sx; val dy = out.y[i] - cd.sy
                if (dx * dx + dy * dy < minD2) { free = false; break }
            }
            if (!free) continue
            c.drawBitmap(icon(cd.poi.kind, size), cd.sx - half, cd.sy - half, icon)
            out.x[out.n] = cd.sx; out.y[out.n] = cd.sy; out.poi[out.n] = cd.poi; out.n++
            nowShown += cd.key
            if (cd.poi.kind == Kind.PARKING) parking++
            if (selected != null && Geo.distance(selected, cd.poi.pos) < 3) { ringX = cd.sx; ringY = cd.sy; ringKind = cd.poi.kind }
        }
        val t = lastShown; lastShown = nowShown; nowShown = t
        if (!ringX.isNaN()) {
            p.style = Paint.Style.STROKE; p.strokeWidth = 3 * d; p.color = ringKind!!.color
            c.drawCircle(ringX, ringY, half + 6 * d, p)
        }
        publish()
    }

    /** What's on screen now (tests). */
    fun shownNow(): List<Poi> { val h = shown; return (0 until h.n).mapNotNull { h.poi[it] } }

    private fun publish() {
        val done = building
        building = shown
        shown = done
    }
}
