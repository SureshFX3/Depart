package app.depart

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.ceil

/** Live traffic and place search through TomTom's free plan. */
object Traffic {
    data class Route(val travelMin: Int, val noTrafficMin: Int, val delayMin: Int)
    class HttpError(val code: Int) : Exception("HTTP $code")

    private const val BASE = "https://api.tomtom.com"
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun get(url: String, headers: Map<String, String> = emptyMap()): String {
        val con = URL(url).openConnection() as HttpURLConnection
        con.connectTimeout = 15_000
        con.readTimeout = 20_000
        headers.forEach { (k, v) -> con.setRequestProperty(k, v) }
        try {
            val code = con.responseCode
            if (code !in 200..299) throw HttpError(code)
            return con.inputStream.bufferedReader().use { it.readText() }
        } finally {
            con.disconnect()
        }
    }

    fun route(key: String, aLat: Double, aLng: Double, bLat: Double, bLng: Double): Route {
        val url = "$BASE/routing/1/calculateRoute/$aLat,$aLng:$bLat,$bLng/json?key=${enc(key)}" +
            "&traffic=true&travelMode=car&routeType=fastest&computeTravelTimeFor=all"
        val s = JSONObject(get(url)).getJSONArray("routes").getJSONObject(0).getJSONObject("summary")
        val travel = s.getInt("travelTimeInSeconds")
        val delay = s.optInt("trafficDelayInSeconds", 0)
        val none = s.optInt("noTrafficTravelTimeInSeconds", travel - delay)
        fun m(sec: Int) = ceil(sec / 60.0).toInt()
        return Route(m(travel), m(none), m(delay))
    }

    fun search(key: String, q: String, lat: Double?, lng: Double?): JSONArray {
        var url = "$BASE/search/2/search/${enc(q)}.json?key=${enc(key)}&limit=6&typeahead=true"
        if (lat != null && lng != null) url += "&lat=$lat&lon=$lng"
        val res = JSONObject(get(url)).optJSONArray("results") ?: JSONArray()
        val out = JSONArray()
        for (i in 0 until res.length()) {
            val r = res.getJSONObject(i)
            val pos = r.optJSONObject("position") ?: continue
            val free = r.optJSONObject("address")?.optString("freeformAddress") ?: ""
            val poi = r.optJSONObject("poi")?.optString("name") ?: ""
            val name = if (poi.isNotBlank()) poi else free.substringBefore(",").ifBlank { q }
            out.put(JSONObject().put("name", name).put("addr", free).put("lat", pos.getDouble("lat")).put("lng", pos.getDouble("lon")))
        }
        return out
    }

    fun reverse(key: String, lat: Double, lng: Double): String {
        val url = "$BASE/search/2/reverseGeocode/$lat,$lng.json?key=${enc(key)}"
        val a = JSONObject(get(url)).optJSONArray("addresses") ?: return ""
        if (a.length() == 0) return ""
        return a.getJSONObject(0).optJSONObject("address")?.optString("freeformAddress") ?: ""
    }
}
