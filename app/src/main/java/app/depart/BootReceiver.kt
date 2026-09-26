package app.depart

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Re-arms the next alarm after a reboot or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val c = context.applicationContext
        val pending = goAsync()
        Thread {
            try { Engine.tick(c) } catch (e: Exception) {} finally { pending.finish() }
        }.start()
    }
}
