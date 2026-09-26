package app.depart

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONObject

object Alerts {
    const val CH_NUDGE = "nudges"
    const val CH_ALARM = "alarm"
    const val CH_BG = "background"
    const val NID_ALARM = 100
    const val NID_CHECK = 101
    const val NID_DRIVE = 102
    private var nid = 200
    private val ACCENT = 0xFFE6915E.toInt()

    fun channels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        fun ch(id: String, name: String, imp: Int) =
            NotificationChannel(id, name, imp).apply { setSound(null, null); enableVibration(false) }
        nm.createNotificationChannel(ch(CH_NUDGE, "Leave reminders", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(ch(CH_ALARM, "Time to leave", NotificationManager.IMPORTANCE_HIGH).apply {
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
        nm.createNotificationChannel(ch(CH_BG, "Traffic checks and drives", NotificationManager.IMPORTANCE_LOW))
    }

    private fun post(c: Context, id: Int, n: Notification) {
        channels(c)
        try { NotificationManagerCompat.from(c).notify(id, n) } catch (e: SecurityException) {}
    }

    fun openApp(c: Context): PendingIntent = PendingIntent.getActivity(
        c, 0, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun action(c: Context, act: String, key: String, rc: Int): PendingIntent = PendingIntent.getBroadcast(
        c, rc, Intent(c, AlarmReceiver::class.java).setAction(act).putExtra("key", key),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /** A reminder: heads-up notification plus a short buzz. */
    fun nudge(c: Context, title: String, body: String, strong: Boolean) {
        Store.log(c, "$title. $body", strong)
        post(c, nid++, NotificationCompat.Builder(c, CH_NUDGE)
            .setSmallIcon(R.drawable.ic_stat).setColor(ACCENT)
            .setContentTitle(title).setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true).setContentIntent(openApp(c)).build())
        Ringer.nudge(c, Store.settings(c), strong)
    }

    /** The big one: full-screen alarm (or call screen) that keeps ringing until handled. */
    fun alarm(c: Context, key: String, t: JSONObject, s: JSONObject, set: JSONObject) {
        val style = t.optString("alert", "alarm")
        val to = Engine.placeName(t, "to")
        val delay = s.optInt("delay")
        val eta = System.currentTimeMillis() + (t.optInt("drive", 30) + delay) * Planner.MIN
        val body = (if (delay > 0) "Traffic adds $delay min. " else "Roads are clear. ") +
            "Leave now to arrive around ${Fmt.time(eta)}."
        if (key != "test") Store.log(c, "Alarm rang for $to.", true)
        val open = Intent(c, AlarmActivity::class.java).putExtra("key", key).putExtra("style", style)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
        val fs = PendingIntent.getActivity(c, 11, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(c, CH_ALARM)
            .setSmallIcon(R.drawable.ic_stat).setColor(ACCENT)
            .setContentTitle(if (style == "call") "Depart is calling: time to leave for $to" else "Time to leave for $to")
            .setContentText(body).setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(fs, true).setContentIntent(fs).setOngoing(true)
            .addAction(0, "Snooze 3 min", action(c, AlarmReceiver.SNOOZE, key, 12))
            .addAction(0, "I'm leaving", action(c, AlarmReceiver.LEAVING, key, 13))
            .setDeleteIntent(action(c, AlarmReceiver.STOP, key, 14))
            .build()
        post(c, NID_ALARM, n)
        Ringer.start(c, set, style == "call", key)
    }

    fun stopAlarm(c: Context) {
        Ringer.stop(c)
        NotificationManagerCompat.from(c).cancel(NID_ALARM)
    }

    /** Quiet ongoing notification Android requires while location is in use. */
    fun bg(c: Context, title: String, body: String, label: String? = null, act: PendingIntent? = null): Notification {
        channels(c)
        val b = NotificationCompat.Builder(c, CH_BG)
            .setSmallIcon(R.drawable.ic_stat).setColor(ACCENT)
            .setContentTitle(title).setContentText(body)
            .setOngoing(true).setContentIntent(openApp(c))
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (label != null && act != null) b.addAction(0, label, act)
        return b.build()
    }
}
