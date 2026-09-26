package app.depart

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Everything Depart remembers, kept on the phone only. */
object Store {
    private const val DEFAULT_SETTINGS =
        """{"lead":60,"buffer":0,"threshold":5,"mode":"both","strength":"strong","ignoreSilent":true}"""

    private fun p(c: Context) = c.getSharedPreferences("depart", Context.MODE_PRIVATE)
    private fun str(c: Context, k: String, d: String): String = p(c).getString(k, d) ?: d
    private fun put(c: Context, k: String, v: String) {
        p(c).edit().putString(k, v).commit()
    }

    fun trips(c: Context) = JSONArray(str(c, "trips", "[]"))
    fun saveTrips(c: Context, a: JSONArray) = put(c, "trips", a.toString())

    fun settings(c: Context): JSONObject {
        val d = JSONObject(DEFAULT_SETTINGS)
        val s = JSONObject(str(c, "settings", "{}"))
        s.keys().forEach { d.put(it, s.get(it)) }
        return d
    }
    fun saveSettings(c: Context, o: JSONObject) = put(c, "settings", o.toString())

    fun places(c: Context) = JSONArray(str(c, "places", "[]"))
    fun savePlaces(c: Context, a: JSONArray) = put(c, "places", a.toString())

    fun occs(c: Context) = JSONObject(str(c, "occs", "{}"))
    fun saveOccs(c: Context, o: JSONObject) = put(c, "occs", o.toString())

    fun apiKey(c: Context) = str(c, "tomtom", "")
    fun setApiKey(c: Context, k: String) = put(c, "tomtom", k)

    fun logs(c: Context) = JSONArray(str(c, "log", "[]"))

    @Synchronized
    fun log(c: Context, msg: String, hot: Boolean = false) {
        val old = logs(c)
        val a = JSONArray().put(JSONObject().put("t", System.currentTimeMillis()).put("msg", msg).put("hot", hot))
        for (i in 0 until minOf(old.length(), 39)) a.put(old.get(i))
        put(c, "log", a.toString())
    }
}
