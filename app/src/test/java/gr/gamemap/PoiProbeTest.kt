package gr.gamemap

import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.net.URLEncoder

/** Where do map icons come from: Kotsovolos stores in the map tiles, OSM, TomTom; parking density. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PoiProbeTest {
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    @Test
    fun probe() {
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        Traffic.init(RuntimeEnvironment.getApplication())
        val log = StringBuilder()
        fun get(u: String) = try { Http.get(u, timeout = 25000) } catch (e: Exception) { "ERR $e" }
        // 1. OSM: Kotsovolos stores and their tags
        val ov = get("https://overpass-api.de/api/interpreter?data=" + enc("[out:json][timeout:25];nwr(37.85,23.65,38.10,23.85)[name~\"otsovolos|ωτσόβολος\",i];out center tags;"))
        try {
            val el = JSONObject(ov).getJSONArray("elements")
            log.append("OSM Kotsovolos: ${el.length()}\n")
            for (i in 0 until minOf(el.length(), 12)) { val e = el.getJSONObject(i); log.append("  ${e.optString("type")} ${e.optJSONObject("tags")}\n".take(400)) }
        } catch (e: Exception) { log.append("OSM: ${ov.take(200)}\n") }
        // 2. the map tiles: every POI near the first store, with class and subclass
        val c = try { JSONObject(ov).getJSONArray("elements").getJSONObject(0) } catch (e: Exception) { null }
        val lat = c?.optDouble("lat", c.optJSONObject("center")?.optDouble("lat") ?: 37.97) ?: 37.97
        val lon = c?.optDouble("lon", c.optJSONObject("center")?.optDouble("lon") ?: 23.73) ?: 23.73
        val n = 1 shl 14
        val tx = ((lon + 180) / 360 * n).toInt()
        val ty = ((1 - Math.log(Math.tan(Math.toRadians(lat)) + 1 / Math.cos(Math.toRadians(lat))) / Math.PI) / 2 * n).toInt()
        val vt = VectorSource.get(14, tx, ty)
        log.append("\ntile 14/$tx/$ty POIs (class/subclass: name):\n")
        val kinds = HashMap<String, Int>()
        vt?.features?.filter { it.layer == "poi" }?.forEach { f ->
            val k = "${f["class"]}/${f["subclass"]}"
            kinds[k] = (kinds[k] ?: 0) + 1
            if ((f["name"] ?: "").contains("otsovol", true) || (f["name"] ?: "").contains("ωτσόβολ", true) || f["class"] == "fuel")
                log.append("  ${f["class"]}/${f["subclass"]}: ${f["name"]} ${f.props}\n".take(300))
        }
        log.append("all kinds in the tile: ${kinds.entries.sortedByDescending { it.value }.joinToString { "${it.key}=${it.value}" }}\n")
        // 3. parking in a few central tiles: what kinds
        log.append("\nparking subclasses around Syntagma:\n")
        val pk = HashMap<String, Int>()
        for (dx in -1..1) for (dy in -1..1) {
            val t = VectorSource.get(14, 9273 + dx, 6324 + dy) ?: continue
            t.features.filter { it.layer == "poi" && it["class"] == "parking" }.forEach { f ->
                val k = "${f["subclass"]} named=${!f["name"].isNullOrBlank()} rank=${f["rank"]}"
                pk[k] = (pk[k] ?: 0) + 1
            }
        }
        pk.entries.sortedByDescending { it.value }.take(20).forEach { log.append("  ${it.key}: ${it.value}\n") }
        // 4. TomTom fuel search near the store
        val tt = get("https://api.tomtom.com/search/2/nearbySearch/.json?key=${Traffic.key}&lat=$lat&lon=$lon&radius=1500&limit=30&categorySet=7311")
        try {
            val a = JSONObject(tt).getJSONArray("results")
            log.append("\nTomTom fuel (7311) within 1.5 km:\n")
            for (i in 0 until a.length()) { val r = a.getJSONObject(i); log.append("  ${r.optJSONObject("poi")?.optString("name")} ${r.optJSONObject("poi")?.optJSONArray("categories")}\n") }
        } catch (e: Exception) { log.append("tomtom: ${tt.take(200)}\n") }
        File(out, "poiprobe.txt").writeText(log.toString())
    }
}
