package gr.gamemap

import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.net.URLEncoder

/** Davelis cave, Sounio, Acropolis Museum: what each source returns and what the app ranks first (from Ilioupoli). */
@RunWith(RobolectricTestRunner::class)
class SearchFixProbeTest {
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    @Test
    fun probe() {
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        Traffic.init(RuntimeEnvironment.getApplication()); Places.init(RuntimeEnvironment.getApplication())
        val key = Traffic.key
        val log = StringBuilder()
        val near = LatLng(37.9310, 23.7560) // Ilioupoli
        fun d(b: LatLng) = "%.1f km (%.4f,%.4f)".format(Geo.distance(near, b) / 1000, b.lat, b.lon)
        fun get(url: String) = try { Http.get(url, timeout = 10000) } catch (e: Exception) { "ERR $e" }
        for (q in listOf("σπηλια του νταβελη", "σουνιο", "Acropolis Museum", "μουσειο ακροπολης")) {
            log.append("\n=== \"$q\"\n")
            for (ta in listOf(true, false)) {
                val j = get("https://api.tomtom.com/search/2/search/${enc(q)}.json?key=$key&limit=8&language=el-GR&typeahead=$ta&idxSet=POI,PAD,Addr,Str,Geo&lat=${near.lat}&lon=${near.lon}")
                if (j.startsWith("ERR")) { log.append(" tomtom: ${j.take(200)}\n"); continue }
                val a = JSONObject(j).optJSONArray("results") ?: continue
                for (i in 0 until a.length()) {
                    val r = a.getJSONObject(i); val p = r.getJSONObject("position")
                    log.append(" tt${if (ta) "-ta" else ""} ${r.optString("type")}/${r.optString("entityType")} '${r.optJSONObject("poi")?.optString("name") ?: ""}' ${r.optJSONObject("address")?.optString("freeformAddress")} ${d(LatLng(p.getDouble("lat"), p.getDouble("lon")))}\n")
                }
            }
            for (pr in Places.photon(q, near, townsOnly = true)) log.append(" photonTowns '${pr.name}' t${pr.town} ${pr.address} ${d(pr.pos)}\n")
            for (pr in Places.tomtom(q, near, true)) log.append(" ttApp '${pr.name}' t${pr.town} ${pr.icon} ${pr.address} ${d(pr.pos)}\n")
            val j = get("https://photon.komoot.io/api/?q=${enc(q)}&limit=8&lat=${near.lat}&lon=${near.lon}&zoom=12&location_bias_scale=0.3")
            if (j.startsWith("ERR")) log.append(" photon: ${j.take(200)}\n") else {
                val a = JSONObject(j).getJSONArray("features")
                for (i in 0 until a.length()) {
                    val f = a.getJSONObject(i); val c = f.getJSONObject("geometry").getJSONArray("coordinates"); val pr = f.getJSONObject("properties")
                    log.append(" photon '${pr.optString("name")}' ${pr.optString("osm_key")}=${pr.optString("osm_value")} type=${pr.optString("type")} ${pr.optString("city")} ${d(LatLng(c.getDouble(1), c.getDouble(0)))}\n")
                }
            }
            for (pr in Places.local(q, near)) log.append(" local '${pr.name}' t${pr.town} ${pr.icon} (${pr.address.take(30)}) ${d(pr.pos)}\n")
            Thread.sleep(1200)
            for (full in listOf(false, true)) {
                val r = try { Places.find(q, near, full) } catch (e: Exception) { null }
                log.append(" APP${if (full) "-full" else ""}: " + r.orEmpty().take(8).joinToString("\n    ") { "${it.icon} ${it.name} t${it.town} (${it.address.take(25)}) ${d(it.pos)}" } + "\n")
            }
        }
        File(out, "searchfix.txt").writeText(log.toString())
    }
}
