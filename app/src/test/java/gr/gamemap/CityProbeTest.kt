package gr.gamemap

import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.net.URLEncoder

/** Towns typed from far away ("ναυπλιο" from Athens): what each service returns and what the app ranks first. */
@RunWith(RobolectricTestRunner::class)
class CityProbeTest {
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    @Test
    fun probe() {
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        Traffic.init(RuntimeEnvironment.getApplication()); Places.init(RuntimeEnvironment.getApplication())
        val key = Traffic.key
        val log = StringBuilder()
        val near = LatLng(37.9755, 23.7348)
        fun d(b: LatLng) = "%.0f km".format(Geo.distance(near, b) / 1000)
        fun get(url: String) = try { Http.get(url, timeout = 10000) } catch (e: Exception) { "ERR $e" }
        for (q in listOf("ναυπλιο", "Ναύπλιο", "nafplio", "ναυπλ", "κορινθος", "patra", "λαμια", "τριπολη", "καλαματα", "βολος")) {
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
            val j = get("https://photon.komoot.io/api/?q=${enc(q)}&limit=8&lat=${near.lat}&lon=${near.lon}&zoom=12&location_bias_scale=0.3")
            if (j.startsWith("ERR")) log.append(" photon: ${j.take(200)}\n") else {
                val a = JSONObject(j).getJSONArray("features")
                for (i in 0 until a.length()) {
                    val f = a.getJSONObject(i); val c = f.getJSONObject("geometry").getJSONArray("coordinates"); val pr = f.getJSONObject("properties")
                    log.append(" photon '${pr.optString("name")}' ${pr.optString("osm_key")}=${pr.optString("osm_value")} type=${pr.optString("type")} ${pr.optString("city")} ${d(LatLng(c.getDouble(1), c.getDouble(0)))}\n")
                }
            }
            for (full in listOf(false, true)) {
                val r = try { Places.find(q, near, full) } catch (e: Exception) { null }
                log.append(" APP${if (full) "-full" else ""}: " + r.orEmpty().take(6).joinToString(" | ") { "${it.icon} ${it.name} (${it.address.take(25)}) ${d(it.pos)}" } + "\n")
            }
        }
        File(out, "cityprobe.txt").writeText(log.toString())
    }
}
