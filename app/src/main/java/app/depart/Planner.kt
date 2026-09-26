package app.depart

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

/**
 * Works out, for each trip on each day, when traffic checks happen and when to leave.
 * One "occurrence" = one trip on one date, keyed "tripId|yyyy-mm-dd".
 */
object Planner {
    const val MIN = 60_000L

    fun startOfDay(ms: Long): Long = Calendar.getInstance().apply {
        timeInMillis = ms
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun addDays(day: Long, n: Int): Long =
        Calendar.getInstance().apply { timeInMillis = day; add(Calendar.DAY_OF_YEAR, n) }.timeInMillis

    fun dayKey(ms: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = ms }
        return String.format(Locale.US, "%04d-%02d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    /** 0 = Sunday ... 6 = Saturday */
    fun weekday(ms: Long): Int = Calendar.getInstance().apply { timeInMillis = ms }.get(Calendar.DAY_OF_WEEK) - 1

    fun tripOn(t: JSONObject, day: Long): Boolean {
        if (!t.optBoolean("on", true)) return false
        return if (t.optBoolean("repeat")) {
            val d = t.optJSONArray("days") ?: return false
            val w = weekday(day)
            (0 until d.length()).any { d.optInt(it) == w }
        } else t.optString("date") == dayKey(day)
    }

    fun arriveAt(t: JSONObject, day: Long): Long {
        val p = t.optString("arrive", "09:00").split(":")
        return Calendar.getInstance().apply {
            timeInMillis = day
            set(Calendar.HOUR_OF_DAY, p[0].toIntOrNull() ?: 9)
            set(Calendar.MINUTE, p.getOrNull(1)?.toIntOrNull() ?: 0)
        }.timeInMillis
    }

    /** Returns (and creates if needed) the state for one trip on one day, with fresh leave time. */
    fun state(occs: JSONObject, t: JSONObject, day: Long, set: JSONObject): JSONObject {
        val key = t.getString("id") + "|" + dayKey(day)
        val a = arriveAt(t, day)
        val drive = t.optInt("drive", 30)
        val buf = set.optInt("buffer", 0)
        val lead = set.optInt("lead", 60)
        val usual = a - (drive + buf) * MIN
        val s = occs.optJSONObject(key)
            ?: JSONObject().put("delay", 0).put("status", "idle").put("fired", JSONObject()).also { occs.put(key, it) }
        val checks = s.optJSONArray("checks")
        val started = checks != null && (0 until checks.length()).any { checks.getJSONObject(it).optBoolean("done") }
        if (!started) {
            val arr = JSONArray()
            val n = maxOf(1, lead / 15)
            for (i in n downTo 1) arr.put(JSONObject().put("at", usual - 15L * i * MIN).put("done", false))
            s.put("checks", arr)
            s.put("lastLeave", usual)
        }
        s.put("key", key).put("tripId", t.getString("id")).put("A", a)
        s.put("leave", a - (drive + s.optInt("delay") + buf) * MIN)
        return s
    }

    fun forDay(trips: JSONArray, occs: JSONObject, set: JSONObject, day: Long): List<Pair<JSONObject, JSONObject>> =
        (0 until trips.length()).map { trips.getJSONObject(it) }
            .filter { tripOn(it, day) }
            .map { it to state(occs, it, day, set) }
            .sortedBy { it.second.getLong("A") }

    fun checks(s: JSONObject): List<JSONObject> {
        val a = s.optJSONArray("checks") ?: return emptyList()
        return (0 until a.length()).map { a.getJSONObject(it) }
    }

    fun prune(occs: JSONObject, now: Long) {
        val cutoff = dayKey(addDays(startOfDay(now), -7))
        val drop = occs.keys().asSequence().filter { it.substringAfter("|") < cutoff }.toList()
        drop.forEach { occs.remove(it) }
    }

    data class Next(val at: Long, val loud: Boolean)

    /** The next moment anything needs to happen (a check, a reminder, the alarm). */
    fun nextEvent(trips: JSONArray, occs: JSONObject, set: JSONObject, now: Long): Next? {
        var best: Next? = null
        fun offer(at: Long, loud: Boolean) {
            val b = best
            if (at > now && (b == null || at < b.at)) best = Next(at, loud)
        }
        val today = startOfDay(now)
        for (i in 0..7) {
            for ((_, s) in forDay(trips, occs, set, addDays(today, i))) {
                val st = s.optString("status")
                if (st != "idle" && st != "watch") continue
                val leave = s.getLong("leave")
                val fired = s.optJSONObject("fired") ?: JSONObject()
                val cks = checks(s)
                cks.firstOrNull()?.let { offer(it.getLong("at") - 5 * MIN, false) }
                cks.filter { !it.optBoolean("done") && it.getLong("at") < leave }.forEach { offer(it.getLong("at"), false) }
                if (!fired.optBoolean("ready")) offer(leave - 10 * MIN, false)
                if (!fired.optBoolean("alarm")) offer(leave, true)
                val sz = s.optLong("snoozeAt")
                if (sz > 0) offer(sz, true)
                offer(s.getLong("A"), false)
            }
            if (best != null && i >= 1) break
        }
        return best
    }
}
