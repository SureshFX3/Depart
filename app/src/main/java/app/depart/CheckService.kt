package app.depart

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/** Lives for a few seconds per check: one location fix, one traffic request, then gone. */
class CheckService : Service() {
    companion object {
        @Volatile var running = false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = Alerts.bg(this, "Checking traffic", "Location is on for a few seconds.")
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(Alerts.NID_CHECK, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            else startForeground(Alerts.NID_CHECK, n)
        } catch (e: Exception) {
            stopSelf(startId)
            Thread { Engine.runChecks(applicationContext, false) }.start()
            return START_NOT_STICKY
        }
        running = true
        Thread {
            try {
                Engine.runChecks(applicationContext, true)
            } catch (e: Exception) {
                Store.log(applicationContext, "Check error: ${e.message}")
            } finally {
                running = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }.start()
        return START_NOT_STICKY
    }
}
