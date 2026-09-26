package app.depart

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/** Keeps exactly one wake-up alarm set: the next moment something needs to happen. */
object Scheduler {
    fun scheduleNext(c: Context) {
        val now = System.currentTimeMillis()
        val next = Planner.nextEvent(Store.trips(c), Store.occs(c), Store.settings(c), now)
        val am = c.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            c, 7, Intent(c, AlarmReceiver::class.java).setAction(AlarmReceiver.TICK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.cancel(pi)
        if (next == null) return
        try {
            if (next.loud) {
                val show = PendingIntent.getActivity(c, 8, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
                am.setAlarmClock(AlarmManager.AlarmClockInfo(next.at, show), pi)
            } else if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.at, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.at, pi)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.at, pi)
        }
    }
}
