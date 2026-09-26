package app.depart

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AlarmReceiver : BroadcastReceiver() {
    companion object {
        const val TICK = "app.depart.TICK"
        const val LEAVING = "app.depart.LEAVING"
        const val SNOOZE = "app.depart.SNOOZE"
        const val STOP = "app.depart.STOP"
        const val ARRIVED = "app.depart.ARRIVED"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val c = context.applicationContext
        val key = intent.getStringExtra("key") ?: ""
        val action = intent.action
        val pending = goAsync()
        Thread {
            try {
                when (action) {
                    TICK -> Engine.tick(c)
                    LEAVING -> Engine.leaving(c, key)
                    SNOOZE -> Engine.snooze(c, key)
                    STOP -> Engine.stop(c, key)
                    ARRIVED -> Engine.arrived(c, key)
                }
            } catch (e: Exception) {
                Store.log(c, "Background error: ${e.message}")
            } finally {
                pending.finish()
            }
        }.start()
    }
}
