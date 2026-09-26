package app.depart

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat

/** Keeps location on only while you drive, and switches it off when you arrive. */
class DriveService : Service(), LocationListener {
    companion object {
        @Volatile var running = false

        fun start(c: Context, key: String) {
            try {
                ContextCompat.startForegroundService(c, Intent(c, DriveService::class.java).putExtra("key", key))
            } catch (e: Exception) {
                Store.log(c, "Couldn't keep location on for the drive. Tap I've arrived when you get there.")
            }
        }

        fun stop(c: Context) {
            c.stopService(Intent(c, DriveService::class.java))
        }
    }

    private var key = ""
    private var dest: Location? = null
    private var until = 0L
    private val h = Handler(Looper.getMainLooper())
    private val watchdog = object : Runnable {
        override fun run() {
            if (System.currentTimeMillis() > until) {
                val k = key
                Thread { Engine.arrived(applicationContext, k) }.start()
            } else h.postDelayed(this, 60_000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        key = intent?.getStringExtra("key") ?: key
        val s = Store.occs(this).optJSONObject(key)
        val t = s?.let { Engine.findTrip(Store.trips(this), it.optString("tripId")) }
        val to = if (t != null) Engine.placeName(t, "to") else "your destination"
        val n = Alerts.bg(this, "Driving to $to", "Location stays on until you arrive, then turns off.",
            "I've arrived", Alerts.action(this, AlarmReceiver.ARRIVED, key, 15))
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(Alerts.NID_DRIVE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            else startForeground(Alerts.NID_DRIVE, n)
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        t?.optJSONObject("to")?.let { p ->
            if (p.has("lat")) dest = Location("dest").apply { latitude = p.getDouble("lat"); longitude = p.getDouble("lng") }
        }
        until = (s?.optLong("arriveAt") ?: System.currentTimeMillis()) + 90 * Planner.MIN
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            lm.removeUpdates(this)
            val prov = if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) LocationManager.GPS_PROVIDER else LocationManager.NETWORK_PROVIDER
            lm.requestLocationUpdates(prov, 20_000L, 50f, this, Looper.getMainLooper())
        } catch (e: Exception) {
        }
        h.removeCallbacks(watchdog)
        h.postDelayed(watchdog, 60_000)
        return START_REDELIVER_INTENT
    }

    override fun onLocationChanged(l: Location) {
        val d = dest ?: return
        if (l.distanceTo(d) < 200f) {
            dest = null
            val k = key
            Thread { Engine.arrived(applicationContext, k) }.start()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    override fun onDestroy() {
        running = false
        h.removeCallbacksAndMessages(null)
        try { (getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(this) } catch (e: Exception) {}
        super.onDestroy()
    }
}
