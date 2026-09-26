package app.depart

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Asks for ONE location fix and lets go. Nothing here keeps GPS running. */
object Loc {
    private fun lm(c: Context) = c.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    @SuppressLint("MissingPermission")
    fun lastKnown(c: Context): Location? {
        if (!Perms.fineLocation(c)) return null
        val m = lm(c)
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { runCatching { m.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun current(c: Context, timeoutMs: Long = 25_000): Location? {
        if (!Perms.fineLocation(c)) return null
        val m = lm(c)
        val provider = when {
            m.isProviderEnabled(LocationManager.GPS_PROVIDER) && Perms.has(c, Manifest.permission.ACCESS_FINE_LOCATION) -> LocationManager.GPS_PROVIDER
            m.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> return lastKnown(c)
        }
        val latch = CountDownLatch(1)
        var result: Location? = null
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                val cancel = CancellationSignal()
                val exec = Executors.newSingleThreadExecutor()
                m.getCurrentLocation(provider, cancel, exec) { l -> result = l; latch.countDown() }
                if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) cancel.cancel()
                exec.shutdown()
            } else {
                val listener = object : LocationListener {
                    override fun onLocationChanged(l: Location) { result = l; latch.countDown() }
                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
                    override fun onProviderEnabled(p: String) {}
                    override fun onProviderDisabled(p: String) { latch.countDown() }
                }
                m.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                latch.await(timeoutMs, TimeUnit.MILLISECONDS)
                m.removeUpdates(listener)
            }
        } catch (e: Exception) {
        }
        return result ?: lastKnown(c)
    }
}
