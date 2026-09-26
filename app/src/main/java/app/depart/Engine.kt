package app.depart

import android.content.Context
import android.content.Intent
import android.location.Location
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ceil

/** The brain: decides what is due, runs traffic checks, fires reminders and alarms. */
object Engine {
    private val lock = Any()
    private const val MIN = Planner.MIN

    fun placeName(t: JSONObject, which: String): String {
        val p = t.optJSONObject(which) ?: return "?"
        return if (p.optBoolean("current")) "your location" else p.optString("name", "?")
    }

    fun findTrip(trips: JSONArray, id: String): JSONObject? =
        (0 until trips.length()).map { trips.getJSONObject(it) }.firstOrNull { it.optString("id") == id }

    fun errText(e: Exception): String = when {
        e is Traffic.HttpError && (e.code == 401 || e.code == 403) -> "TomTom rejected the key. Check it in Settings."
        e is Traffic.HttpError && e.code == 429 -> "TomTom's free daily limit is used up. It resets tomorrow."
        e is java.net.UnknownHostException -> "No internet connection."
        else -> e.message ?: "Unknown error"
    }

    /** Runs whenever the scheduled alarm fires. */
    fun tick(c: Context) {
        if (process(c) > 0) startChecks(c) else Scheduler.scheduleNext(c)
    }

    fun reschedule(c: Context) {
        Thread { try { tick(c) } catch (e: Exception) { Store.log(c, "Scheduling problem: ${e.message}") } }.start()
    }

    /** Fires reminders/alarms that are due. Returns how many traffic checks are due. */
    fun process(c: Context): Int = synchronized(lock) {
        val now = System.currentTimeMillis()
        val trips = Store.trips(c)
        val set = Store.settings(c)
        val occs = Store.occs(c)
        var due = 0
        for ((t, s) in Planner.forDay(trips, occs, set, Planner.startOfDay(now))) {
            val key = s.getString("key")
            val to = placeName(t, "to")
            var st = s.optString("status")
            val cks = Planner.checks(s)
            val a = s.getLong("A")
            val leave = s.getLong("leave")
            if (st == "idle" && cks.isNotEmpty() && now >= cks[0].getLong("at") - 5 * MIN && now < a) {
                st = "watch"; s.put("status", st)
                Store.log(c, "Started watching the trip to $to.")
            }
            if (st != "idle" && st != "watch") continue
            if (now >= a) {
                s.put("status", "missed")
                if (Ringer.ringingKey == key) Alerts.stopAlarm(c)
                if (st == "watch") Store.log(c, "Missed the ${Fmt.time(a)} arrival at $to.", true)
                continue
            }
            if (st != "watch") continue
            val fired = s.optJSONObject("fired") ?: JSONObject().also { s.put("fired", it) }
            due += cks.count { !it.optBoolean("done") && it.getLong("at") <= now && it.getLong("at") < leave }
            if (!fired.optBoolean("ready") && now >= leave - 10 * MIN && now < leave) {
                fired.put("ready", true)
                val mins = ceil((leave - now).toDouble() / MIN).toInt()
                Alerts.nudge(c, "Get ready", "Leave for $to in $mins min, at ${Fmt.time(leave)}.", false)
            }
            if (!fired.optBoolean("alarm") && now >= leave) {
                fired.put("alarm", true)
                Alerts.alarm(c, key, t, s, set)
            }
            val sz = s.optLong("snoozeAt")
            if (sz > 0 && now >= sz) {
                s.put("snoozeAt", 0)
                Alerts.alarm(c, key, t, s, set)
            }
        }
        Planner.prune(occs, now)
        Store.saveOccs(c, occs)
        due
    }

    fun startChecks(c: Context) {
        if (Perms.fineLocation(c)) {
            try {
                ContextCompat.startForegroundService(c, Intent(c, CheckService::class.java))
                return
            } catch (e: Exception) {
            }
        }
        runChecks(c, false)
    }

    /** Does every traffic check that is due right now. */
    fun runChecks(c: Context, precise: Boolean) {
        val now = System.currentTimeMillis()
        val key = Store.apiKey(c)
        val loc = if (precise) Loc.current(c) else Loc.lastKnown(c)
        synchronized(lock) {
            val trips = Store.trips(c)
            val set = Store.settings(c)
            val occs = Store.occs(c)
            for ((t, s) in Planner.forDay(trips, occs, set, Planner.startOfDay(now))) {
                val st = s.optString("status")
                if (st != "watch" && st != "idle") continue
                val leave = s.getLong("leave")
                val due = Planner.checks(s).filter { !it.optBoolean("done") && it.getLong("at") <= now && it.getLong("at") < leave }
                if (due.isEmpty()) continue
                val r = route(c, key, t, loc)
                due.forEach { it.put("done", true) }
                val ck = due.last()
                if (r == null) { ck.put("err", true); continue }
                apply(c, t, s, set, r, ck)
            }
            Store.saveOccs(c, occs)
        }
        process(c)
        Scheduler.scheduleNext(c)
    }

    private fun route(c: Context, key: String, t: JSONObject, loc: Location?): Traffic.Route? {
        val to = t.optJSONObject("to")
        val from = t.optJSONObject("from")
        if (key.isBlank()) {
            Store.log(c, "Add your free TomTom key in Settings so Depart can read traffic.", true); return null
        }
        if (to == null || !to.has("lat")) {
            Store.log(c, "${placeName(t, "to")} has no map point. Edit the trip and pick it from the search list.", true); return null
        }
        var oLat: Double? = null
        var oLng: Double? = null
        if (loc != null) { oLat = loc.latitude; oLng = loc.longitude }
        else if (from != null && from.has("lat")) { oLat = from.getDouble("lat"); oLng = from.getDouble("lng") }
        if (oLat == null || oLng == null) {
            Store.log(c, "Couldn't get your location for the traffic check.", true); return null
        }
        return try {
            Traffic.route(key, oLat, oLng, to.getDouble("lat"), to.getDouble("lng"))
        } catch (e: Exception) {
            Store.log(c, "Traffic check failed: ${errText(e)}", true); null
        }
    }

    private fun apply(c: Context, t: JSONObject, s: JSONObject, set: JSONObject, r: Traffic.Route, ck: JSONObject) {
        val drive = t.optInt("drive", 30)
        val buf = set.optInt("buffer", 0)
        val thr = set.optInt("threshold", 5)
        val a = s.getLong("A")
        val to = placeName(t, "to")
        val delay = r.travelMin - drive
        s.put("delay", delay)
        ck.put("delay", delay).put("travel", r.travelMin)
        val newLeave = a - (drive + delay + buf) * MIN
        val diff = ((s.optLong("lastLeave", newLeave) - newLeave) / MIN).toInt()
        Store.log(c, "Traffic check ${Fmt.time(ck.getLong("at"))}: ${Fmt.dur(r.travelMin)} to $to right now. Leave at ${Fmt.time(newLeave)}.")
        if (diff >= thr) Alerts.nudge(c, "Leave earlier", "Traffic to $to added $diff min. Leave at ${Fmt.time(newLeave)} to arrive by ${Fmt.time(a)}.", true)
        else if (diff <= -thr) Alerts.nudge(c, "Traffic eased", "You can leave for $to at ${Fmt.time(newLeave)}.", false)
        s.put("lastLeave", newLeave)
        s.put("leave", newLeave)
    }

    /** The "Check now" button: live check for the next trip, right away. */
    fun checkNow(c: Context): JSONObject {
        val now = System.currentTimeMillis()
        val loc = Loc.current(c, 20_000)
        val out = JSONObject().put("ok", false)
        synchronized(lock) {
            val trips = Store.trips(c)
            val set = Store.settings(c)
            val occs = Store.occs(c)
            var pick: Pair<JSONObject, JSONObject>? = null
            for (i in 0..7) {
                pick = Planner.forDay(trips, occs, set, Planner.addDays(Planner.startOfDay(now), i)).firstOrNull { (_, s) ->
                    val st = s.optString("status")
                    (st == "idle" || st == "watch") && s.getLong("A") > now
                }
                if (pick != null) break
            }
            val p = pick
            if (p == null) {
                out.put("msg", "No upcoming trip to check. Plan one first.")
            } else {
                val (t, s) = p
                val r = route(c, Store.apiKey(c), t, loc)
                if (r == null) {
                    out.put("msg", "The check failed. The Activity list says why.")
                } else {
                    if (Planner.dayKey(s.getLong("A")) == Planner.dayKey(now)) apply(c, t, s, set, r, JSONObject().put("at", now))
                    else Store.log(c, "Manual check: ${Fmt.dur(r.travelMin)} to ${placeName(t, "to")} right now.")
                    val extra = if (r.delayMin > 0) ", ${r.delayMin} min of it is traffic." else ", roads are clear."
                    out.put("ok", true).put("msg", "${Fmt.dur(r.travelMin)} to ${placeName(t, "to")} right now$extra")
                }
                Store.saveOccs(c, occs)
            }
        }
        reschedule(c)
        return out
    }

    fun leaving(c: Context, key: String) {
        Alerts.stopAlarm(c)
        if (key == "test") return
        synchronized(lock) {
            val occs = Store.occs(c)
            val s = occs.optJSONObject(key) ?: return
            val t = findTrip(Store.trips(c), s.optString("tripId")) ?: return
            val now = System.currentTimeMillis()
            val eta = now + (t.optInt("drive", 30) + s.optInt("delay")) * MIN
            s.put("status", "driving").put("departAt", now).put("arriveAt", eta).put("snoozeAt", 0)
            (s.optJSONObject("fired") ?: JSONObject().also { s.put("fired", it) }).put("alarm", true).put("ready", true)
            Store.saveOccs(c, occs)
            Store.log(c, "Left for ${placeName(t, "to")} at ${Fmt.time(now)}. Location stays on until you arrive.", true)
        }
        DriveService.start(c, key)
        Scheduler.scheduleNext(c)
    }

    fun snooze(c: Context, key: String) {
        Alerts.stopAlarm(c)
        if (key == "test") return
        synchronized(lock) {
            val occs = Store.occs(c)
            val s = occs.optJSONObject(key) ?: return
            val at = System.currentTimeMillis() + 3 * MIN
            s.put("snoozeAt", at)
            Store.saveOccs(c, occs)
            Store.log(c, "Snoozed until ${Fmt.time(at)}.")
        }
        Scheduler.scheduleNext(c)
    }

    fun stop(c: Context, key: String) {
        val was = Ringer.ringingKey
        Alerts.stopAlarm(c)
        if (key != "test" && was != null) Store.log(c, "Alarm stopped.")
    }

    fun arrived(c: Context, key: String) {
        var to = ""
        synchronized(lock) {
            val occs = Store.occs(c)
            val s = occs.optJSONObject(key)
            if (s != null && s.optString("status") == "driving") {
                val t = findTrip(Store.trips(c), s.optString("tripId"))
                to = if (t != null) placeName(t, "to") else "your destination"
                s.put("status", "arrived").put("arrivedAt", System.currentTimeMillis())
                Store.saveOccs(c, occs)
            }
        }
        DriveService.stop(c)
        if (to.isNotEmpty()) Alerts.nudge(c, "You made it", "Reached $to at ${Fmt.time(System.currentTimeMillis())}. Location is off now.", false)
        Scheduler.scheduleNext(c)
    }

    /** After editing a trip, forget its planned (not yet driven) days so they are rebuilt. */
    fun resetTrip(c: Context, id: String) {
        synchronized(lock) {
            val occs = Store.occs(c)
            val drop = occs.keys().asSequence().filter {
                it.startsWith("$id|") && (occs.optJSONObject(it)?.optString("status") ?: "") !in listOf("driving", "arrived")
            }.toList()
            drop.forEach { occs.remove(it) }
            Store.saveOccs(c, occs)
        }
    }
}
