package gr.gamemap

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * Draws one map tile for a theme from OpenFreeMap vector data: land use, water, buildings,
 * roads at roughly real widths (with casings or neon glow), and street and place names.
 */
object VectorRenderer {

    private const val EARTH = 40_075_016.686

    /** Road classes: real width in metres, thinnest width in pixels, first zoom shown. */
    private class RoadKind(val meters: Float, val minPx: Float, val minZoom: Int)

    private val kinds = mapOf(
        "motorway" to RoadKind(22f, 2.6f, 5), "trunk" to RoadKind(20f, 2.4f, 6),
        "primary" to RoadKind(16f, 2.0f, 8), "secondary" to RoadKind(14f, 1.7f, 10),
        "tertiary" to RoadKind(12f, 1.5f, 11), "minor" to RoadKind(9f, 1.1f, 13),
        "service" to RoadKind(6f, 0.8f, 14), "track" to RoadKind(4f, 0.8f, 14),
        "path" to RoadKind(3f, 0.8f, 15), "rail" to RoadKind(4f, 1.0f, 12), "transit" to RoadKind(4f, 1.0f, 13),
    )
    private val drawOrder = listOf("path", "track", "rail", "transit", "service", "minor", "tertiary", "secondary", "primary", "trunk", "motorway")

    fun render(theme: Theme, z: Int, x: Int, y: Int, size: Int, dark: Boolean = MapState.dark): Bitmap? {
        val dz = VectorSource.dataZoom(z)
        val sub = z - dz
        val ax = x shr sub; val ay = y shr sub
        val vt = VectorSource.get(dz, ax, ay) ?: return null
        val s = theme.lookFor(dark).style // fixed when the tile was requested, even if the mode flips meanwhile
        val out = size / theme.pixel
        val aa = theme.pixel == 1

        val bmp = Bitmap.createBitmap(out, out, if (aa) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(s.land)

        // Part of the data tile this map tile covers, in data-tile units.
        val extent = vt.features.firstOrNull()?.extent ?: 4096
        val span = extent.toFloat() / (1 shl sub)
        val ox = (x - (ax shl sub)) * span; val oy = (y - (ay shl sub)) * span
        val k = out / span // output pixels per data unit
        c.scale(k, k)
        c.translate(-ox, -oy)
        val margin = span * 0.1f
        val l = ox - margin; val t = oy - margin; val r = ox + span + margin; val b = oy + span + margin

        // metres per output pixel at this tile's latitude
        val worldTiles = 1 shl z
        val lat = Geo.lat((y + 0.5) * 256.0, 256.0 * worldTiles)
        val mpp = (EARTH * cos(lat * PI / 180) / worldTiles / out).toFloat()

        val fill = Paint().apply { isAntiAlias = aa; style = Paint.Style.FILL }
        val line = Paint().apply {
            isAntiAlias = aa; style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }

        val byLayer = vt.features.groupBy { it.layer }
        fun layer(n: String) = (byLayer[n] ?: emptyList()).filter { it.intersects(l, t, r, b) }

        fun area(f: VectorTile.Feature, color: Int) {
            if (f.type != 3 || color == 0) return
            fill.color = color
            c.drawPath(pathOf(f), fill)
        }
        /** Watercolour: water and greens painted twice, a soft bled edge and the wash inside. */
        fun wash(f: VectorTile.Feature, color: Int) {
            if (!(s.soft && aa) || f.type != 3 || color == 0) return area(f, color)
            fill.color = color; fill.alpha = 110
            fill.maskFilter = android.graphics.BlurMaskFilter(4f, android.graphics.BlurMaskFilter.Blur.NORMAL)
            c.drawPath(pathOf(f), fill)
            fill.maskFilter = null; fill.alpha = 255
            fill.color = color; fill.alpha = 200
            c.drawPath(pathOf(f), fill)
            fill.alpha = 255
        }

        fun px(width: Float) = width / k // output pixels → data units

        // ---- ground cover
        for (f in layer("landcover")) area(f, when (f["class"]) {
            "wood" -> s.wood
            "grass", "wetland" -> s.park
            "sand", "beach" -> s.sand
            "farmland" -> s.farm
            "rock", "ice" -> s.urban
            else -> 0
        })
        for (f in layer("landuse")) area(f, when (f["class"]) {
            "commercial", "retail", "industrial", "railway", "garages", "military", "quarry" -> s.urban
            "pitch", "stadium", "track", "playground" -> s.pitch
            "cemetery" -> s.park
            else -> 0
        })
        for (f in layer("park")) wash(f, s.park)

        // ---- water
        val water = layer("water")
        for (f in water) wash(f, s.water)
        s.waterEdge?.let { edge ->
            if (z >= 12) {
                line.color = edge; line.strokeWidth = px(if (aa) 1.2f else 1f); line.alpha = 170
                for (f in water) if (f.type == 3) c.drawPath(pathOf(f), line)
                line.alpha = 255
            }
        }
        for (f in layer("waterway")) if (f.type == 2) {
            val m = when (f["class"]) { "river" -> 14f; "canal" -> 10f; else -> 3f }
            if (f["class"] != "river" && z < 13) continue
            line.color = s.water
            line.strokeWidth = px(max(if (f["class"] == "river") 1.5f else 0.8f, m / mpp))
            c.drawPath(pathOf(f), line)
        }

        // ---- country borders (on top of land and sea, under the roads)
        for (f in layer("boundary")) {
            if (f.type != 2 || f["admin_level"] != "2" || f["maritime"] == "1") continue
            // a muted violet-grey that shows on both light and dark land and on the sea
            line.color = if (dark) Color.rgb(150, 150, 190) else Color.rgb(125, 105, 150); line.alpha = if (z < 8) 210 else 160
            line.strokeWidth = px(if (z < 6) 1.3f else 1.8f)
            line.pathEffect = if (aa) android.graphics.DashPathEffect(if (f["disputed"] == "1") floatArrayOf(px(3f), px(4f)) else floatArrayOf(px(9f), px(4f)), 0f) else null
            c.drawPath(pathOf(f), line)
        }
        line.pathEffect = null; line.alpha = 255

        // ---- buildings
        if (z >= 14) {
            val buildings = layer("building")
            for (f in buildings) area(f, s.building)
            s.buildingEdge?.let { edge ->
                if (z >= 15) {
                    line.color = edge; line.strokeWidth = px(if (z >= 17) 1.4f else 0.9f)
                    for (f in buildings) if (f.type == 3) c.drawPath(pathOf(f), line)
                }
            }
            // lit windows (night styles): a few small dots around each building's middle
            s.windows?.let { cols ->
                if (z >= 15 && aa) {
                    val dot = px(if (z >= 17) 2.2f else 1.6f)
                    for (f in buildings) {
                        if (f.type != 3) continue
                        val ring = f.parts.firstOrNull() ?: continue
                        if (ring.size < 8) continue
                        var sx = 0f; var sy = 0f; var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
                        var j = 0
                        while (j < ring.size) { val x = ring[j]; val y = ring[j + 1]; sx += x; sy += y; if (x < minX) minX = x; if (x > maxX) maxX = x; if (y < minY) minY = y; if (y > maxY) maxY = y; j += 2 }
                        val n = ring.size / 2
                        val cx = sx / n; val cy = sy / n
                        val bw = maxX - minX; val bh = maxY - minY
                        if (bw < dot * 4 || bh < dot * 4) continue
                        var hsh = (ring[0].toInt() * 73856093) xor (ring[1].toInt() * 19349663)
                        val lights = (hsh ushr 3) % 3 // 0, 1 or 2
                        for (q in 0 until lights) {
                            hsh = hsh * 1103515245 + 12345
                            fill.color = cols[(hsh ushr 8).mod(cols.size)]
                            val ox = (((hsh ushr 12) and 255) / 255f - 0.5f) * bw * 0.4f
                            val oy = (((hsh ushr 20) and 255) / 255f - 0.5f) * bh * 0.4f
                            c.drawRect(cx + ox, cy + oy, cx + ox + dot, cy + oy + dot, fill)
                        }
                    }
                }
            }
        }

        // ---- roads
        val roads = layer("transportation").filter { it["brunnel"] != "tunnel" && it.type == 2 }
        for (f in layer("transportation")) if (f.type == 3 && z >= 15) area(f, s.minor) // pedestrian squares
        fun roadColor(cls: String) = when (cls) {
            "motorway", "trunk" -> s.motorway
            "primary", "secondary", "tertiary" -> s.major
            "minor", "service" -> s.minor
            "rail", "transit" -> s.rail
            else -> s.path
        }
        // zoomed out (regions, countries) the motorways are thin threads, not neon spaghetti
        val thin = when { z <= 5 -> 0.4f; z == 6 -> 0.5f; z == 7 -> 0.65f; z == 8 -> 0.8f; else -> 1f }
        fun width(kind: RoadKind) = max(kind.minPx * thin, kind.meters * 0.85f / mpp)
        val visible = drawOrder.mapNotNull { cls -> kinds[cls]?.takeIf { z >= it.minZoom }?.let { cls to it } }

        // glow or casing underneath everything, so junctions stay clean
        if (s.glow && aa) {
            // two soft passes instead of a blur filter (blurs are very slow in software)
            for ((alpha, extra) in listOf(40 to 9f, 70 to 4f)) for ((cls, kind) in visible) {
                if (cls == "path" || cls == "track" || cls == "service") continue
                line.color = roadColor(cls); line.alpha = alpha
                line.strokeWidth = px(width(kind) + extra * thin)
                for (f in roads) if (f["class"] == cls) c.drawPath(pathOf(f), line)
            }
            line.alpha = 255
        }
        s.casing?.let { cas ->
            line.color = cas
            for ((cls, kind) in visible) {
                if (cls == "rail" || cls == "transit" || cls == "path" || cls == "track") continue
                line.strokeWidth = px(width(kind) + 2.2f * thin)
                for (f in roads) if (f["class"] == cls) c.drawPath(pathOf(f), line)
            }
        }
        // tunnels: under the other roads, faded, with dashed edges (driving through one, the road is still there)
        val tunnels = layer("transportation").filter { it["brunnel"] == "tunnel" && it.type == 2 }
        if (tunnels.isNotEmpty()) {
            for ((cls, kind) in visible) {
                if (cls == "rail" || cls == "transit" || cls == "path" || cls == "track") continue
                if (roadColor(cls) == 0) continue // a style that draws its roads another way (8-Bit)
                val mine = tunnels.filter { it["class"] == cls }
                if (mine.isEmpty()) continue
                val w = width(kind)
                line.color = s.casing ?: roadColor(cls); line.alpha = 200
                line.strokeWidth = px(w + 2.2f * thin)
                line.pathEffect = if (aa) android.graphics.DashPathEffect(floatArrayOf(px(5f), px(3.5f)), 0f) else null
                for (f in mine) c.drawPath(pathOf(f), line)
                line.pathEffect = null
                line.color = roadColor(cls); line.alpha = 120
                line.strokeWidth = px(w * 0.8f)
                for (f in mine) c.drawPath(pathOf(f), line)
            }
            line.alpha = 255
        }
        for ((cls, kind) in visible) {
            line.color = roadColor(cls)
            line.strokeWidth = px(width(kind))
            val dashed = cls == "path" || cls == "track" || ((cls == "rail" || cls == "transit") && s.casing != null)
            line.pathEffect = if (dashed && aa) android.graphics.DashPathEffect(floatArrayOf(px(4f), px(3f)), 0f) else null
            for (f in roads) if (f["class"] == cls) c.drawPath(pathOf(f), line)
        }
        line.pathEffect = null

        // shop lights along both sides of the streets (Tokyo Night), zoomed in
        s.streetLights?.let { cols ->
            if (z >= 15 && aa) {
                val step = px(if (z >= 17) 18f else 26f)
                val dot = px(if (z >= 17) 1.7f else 1.3f)
                for ((cls, kind) in visible) {
                    if (cls != "minor" && cls != "primary" && cls != "secondary" && cls != "tertiary") continue
                    val off = px(width(kind) / 2 + 2.2f)
                    for (f in roads) {
                        if (f["class"] != cls) continue
                        for (part in f.parts) {
                            var carry = 0f
                            var j = 0
                            while (j + 3 < part.size) {
                                val x0 = part[j]; val y0 = part[j + 1]; val x1 = part[j + 2]; val y1 = part[j + 3]
                                val len = kotlin.math.hypot(x1 - x0, y1 - y0)
                                if (len > 0f) {
                                    val nx = -(y1 - y0) / len; val ny = (x1 - x0) / len
                                    var t = step - carry
                                    while (t < len) {
                                        val px0 = x0 + (x1 - x0) * t / len; val py0 = y0 + (y1 - y0) * t / len
                                        var hsh = (px0.toInt() * 73856093) xor (py0.toInt() * 19349663)
                                        hsh = hsh * 1103515245 + 12345
                                        if ((hsh ushr 4) % 5 != 0) { // most spots lit, some dark
                                            val side = if ((hsh ushr 9) and 1 == 0) 1f else -1f
                                            val col = cols[(hsh ushr 12).mod(cols.size)]
                                            val lx = px0 + nx * off * side; val ly = py0 + ny * off * side
                                            fill.color = col; fill.alpha = 45; c.drawCircle(lx, ly, dot * 3f, fill)
                                            fill.alpha = 255; c.drawCircle(lx, ly, dot, fill)
                                        }
                                        t += step
                                    }
                                    carry = (carry + len) % step
                                }
                                j += 2
                            }
                        }
                    }
                }
            }
        }

        return bmp
    }

    private fun pathOf(f: VectorTile.Feature): Path {
        f.path?.let { return it }
        val p = Path()
        if (f.type == 3) p.fillType = Path.FillType.EVEN_ODD
        for (part in f.parts) {
            if (part.size < 4) continue
            p.moveTo(part[0], part[1])
            var i = 2
            while (i < part.size) { p.lineTo(part[i], part[i + 1]); i += 2 }
            if (f.type == 3) p.close()
        }
        f.path = p
        return p
    }

    @Suppress("unused")
    private val unused = Color.TRANSPARENT
}
