package app.depart

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject

/** Everything the interface can ask the phone to do. Called from JavaScript as Android.xxx(). */
class Bridge(private val a: WebActivity) {
    private val c: Context = a.applicationContext

    private fun cb(id: Int, data: JSONObject) {
        a.js("window.__cb&&__cb($id," + JSONObject.quote(data.toString()) + ")")
    }

    private fun bg(f: () -> Unit) {
        Thread {
            try { f() } catch (e: Exception) { Store.log(c, "Something went wrong: ${e.message}") }
        }.start()
    }

    private fun closeIfAlarm() {
        if (a is AlarmActivity) a.runOnUiThread { a.finish() }
    }

    @JavascriptInterface
    fun state(): String {
        val now = System.currentTimeMillis()
        val trips = Store.trips(c)
        val set = Store.settings(c)
        val occs = Store.occs(c)
        val list = JSONArray()
        val today = Planner.startOfDay(now)
        for (i in -6..13) for ((_, s) in Planner.forDay(trips, occs, set, Planner.addDays(today, i))) list.put(s)
        return JSONObject()
            .put("now", now).put("trips", trips).put("settings", set).put("places", Store.places(c))
            .put("occs", list).put("log", Store.logs(c))
            .put("gps", CheckService.running || DriveService.running)
            .put("ringing", Ringer.ringingKey ?: JSONObject.NULL)
            .put("perms", Perms.json(c)).put("hasKey", Store.apiKey(c).isNotBlank())
            .put("version", BuildConfig.VERSION_NAME).put("repo", BuildConfig.UPDATE_REPO)
            .toString()
    }

    @JavascriptInterface
    fun saveTrip(json: String) {
        val t = JSONObject(json)
        val id = t.getString("id")
        val old = Store.trips(c)
        val out = JSONArray()
        var found = false
        for (i in 0 until old.length()) {
            val x = old.getJSONObject(i)
            if (x.optString("id") == id) { out.put(t); found = true } else out.put(x)
        }
        if (!found) out.put(t)
        Store.saveTrips(c, out)
        Engine.resetTrip(c, id)
        Engine.reschedule(c)
    }

    @JavascriptInterface
    fun deleteTrip(id: String) {
        val old = Store.trips(c)
        val out = JSONArray()
        for (i in 0 until old.length()) { val x = old.getJSONObject(i); if (x.optString("id") != id) out.put(x) }
        Store.saveTrips(c, out)
        Engine.resetTrip(c, id)
        Engine.reschedule(c)
    }

    @JavascriptInterface
    fun toggleTrip(id: String, on: Boolean) {
        val arr = Store.trips(c)
        for (i in 0 until arr.length()) { val x = arr.getJSONObject(i); if (x.optString("id") == id) x.put("on", on) }
        Store.saveTrips(c, arr)
        Engine.reschedule(c)
    }

    @JavascriptInterface
    fun saveSettings(json: String) {
        Store.saveSettings(c, JSONObject(json))
        Engine.reschedule(c)
    }

    @JavascriptInterface
    fun savePlaces(json: String) { Store.savePlaces(c, JSONArray(json)) }

    @JavascriptInterface
    fun setKey(k: String) { Store.setApiKey(c, k.trim()) }

    @JavascriptInterface
    fun search(q: String, id: Int) = bg {
        val out = JSONObject()
        try {
            val l = Loc.lastKnown(c)
            out.put("ok", true).put("results", Traffic.search(Store.apiKey(c), q, l?.latitude, l?.longitude))
        } catch (e: Exception) {
            out.put("ok", false).put("msg", Engine.errText(e))
        }
        cb(id, out)
    }

    @JavascriptInterface
    fun here(id: Int) = bg {
        val l = Loc.current(c, 20_000)
        if (l == null) {
            cb(id, JSONObject().put("ok", false).put("msg", "Couldn't get your location. Check that location is on and allowed."))
        } else {
            val addr = try { Traffic.reverse(Store.apiKey(c), l.latitude, l.longitude) } catch (e: Exception) { "" }
            cb(id, JSONObject().put("ok", true).put("lat", l.latitude).put("lng", l.longitude).put("addr", addr).put("accuracy", l.accuracy.toDouble()))
        }
    }

    @JavascriptInterface
    fun measure(fromJson: String, toJson: String, id: Int) = bg {
        val out = JSONObject().put("ok", false)
        try {
            val f = JSONObject(fromJson)
            val t = JSONObject(toJson)
            val origin: Pair<Double, Double> = if (f.optBoolean("current")) {
                val l = Loc.current(c, 15_000) ?: throw IllegalStateException("Couldn't get your location.")
                Pair(l.latitude, l.longitude)
            } else {
                Pair(f.getDouble("lat"), f.getDouble("lng"))
            }
            val r = Traffic.route(Store.apiKey(c), origin.first, origin.second, t.getDouble("lat"), t.getDouble("lng"))
            out.put("ok", true).put("travel", r.travelMin).put("noTraffic", r.noTrafficMin)
        } catch (e: Exception) {
            out.put("msg", Engine.errText(e))
        }
        cb(id, out)
    }

    @JavascriptInterface
    fun checkNow(id: Int) = bg { cb(id, Engine.checkNow(c)) }

    @JavascriptInterface
    fun leaving(key: String) = bg { Engine.leaving(c, key); closeIfAlarm() }

    @JavascriptInterface
    fun snooze(key: String) = bg { Engine.snooze(c, key); closeIfAlarm() }

    @JavascriptInterface
    fun stopAlarm(key: String) = bg { Engine.stop(c, key); closeIfAlarm() }

    @JavascriptInterface
    fun arrived(key: String) = bg { Engine.arrived(c, key) }

    @JavascriptInterface
    fun closeAlarm() { closeIfAlarm() }

    @JavascriptInterface
    fun testAlert(style: String) = bg {
        val t = JSONObject().put("alert", style).put("drive", 60).put("to", JSONObject().put("name", "Home"))
        Alerts.alarm(c, "test", t, JSONObject().put("delay", 8), Store.settings(c))
    }

    @JavascriptInterface
    fun navigate(lat: Double, lng: Double, name: String) {
        a.runOnUiThread {
            val nav = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$lat,$lng&mode=d"))
                .setPackage("com.google.android.apps.maps")
            try {
                a.startActivity(nav)
            } catch (e: Exception) {
                try {
                    a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$lat,$lng&travelmode=driving")))
                } catch (_: Exception) {}
            }
        }
    }

    @JavascriptInterface
    fun openUrl(url: String) {
        a.runOnUiThread { try { a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (e: Exception) {} }
    }

    @JavascriptInterface
    fun ask(which: String) { a.runOnUiThread { Perms.ask(a, which) } }

    @JavascriptInterface
    fun checkUpdate(id: Int) = bg { cb(id, Updater.check()) }

    @JavascriptInterface
    fun installUpdate(url: String, id: Int) = bg { Updater.install(a, url) { cb(id, it) } }
}
