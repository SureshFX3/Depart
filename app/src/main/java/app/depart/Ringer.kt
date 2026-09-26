package app.depart

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import org.json.JSONObject

/** Sound and vibration, following the user's alert settings. */
object Ringer {
    @Volatile var ringingKey: String? = null
    private var tone: Ringtone? = null
    private val main = Handler(Looper.getMainLooper())

    @Suppress("DEPRECATION")
    private fun vibrator(c: Context): Vibrator =
        if (Build.VERSION.SDK_INT >= 31) c.getSystemService(VibratorManager::class.java).defaultVibrator
        else c.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

    private fun amp(strength: String) = if (strength == "normal") 170 else 255

    // Timings alternate: pause, buzz, pause, buzz...
    private fun loopPattern(strength: String, call: Boolean): LongArray = when {
        call -> longArrayOf(0, 1000, 600, 1000, 1400)
        strength == "max" -> longArrayOf(0, 1400, 120, 1400, 120, 1400, 400)
        strength == "strong" -> longArrayOf(0, 1000, 250, 1000, 250, 1000, 700)
        else -> longArrayOf(0, 600, 500, 600, 1500)
    }

    private fun oncePattern(strength: String, strong: Boolean): LongArray = when {
        strength == "max" || strong -> longArrayOf(0, 600, 150, 600, 150, 600)
        strength == "strong" -> longArrayOf(0, 450, 200, 450)
        else -> longArrayOf(0, 250, 200, 250)
    }

    @Suppress("DEPRECATION")
    private fun vibrate(c: Context, timings: LongArray, strength: String, repeat: Int) {
        val v = vibrator(c)
        if (!v.hasVibrator()) return
        val a = amp(strength)
        val amps = IntArray(timings.size) { if (it % 2 == 1) a else 0 }
        val effect = if (v.hasAmplitudeControl()) VibrationEffect.createWaveform(timings, amps, repeat)
        else VibrationEffect.createWaveform(timings, repeat)
        if (Build.VERSION.SDK_INT >= 33) v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        else v.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
    }

    /** Returns (sound allowed, vibration allowed). */
    private fun gate(c: Context, set: JSONObject): Pair<Boolean, Boolean> {
        val mode = set.optString("mode", "both")
        val ringer = c.getSystemService(AudioManager::class.java).ringerMode
        val ignore = set.optBoolean("ignoreSilent", true)
        val sound = (mode == "both" || mode == "sound") && (ignore || ringer == AudioManager.RINGER_MODE_NORMAL)
        val vib = (mode == "both" || mode == "vibrate") && (ignore || ringer != AudioManager.RINGER_MODE_SILENT)
        return sound to vib
    }

    fun start(c: Context, set: JSONObject, call: Boolean, key: String) {
        stop(c)
        ringingKey = key
        val (sound, vib) = gate(c, set)
        val strength = set.optString("strength", "strong")
        if (vib) vibrate(c, loopPattern(strength, call), strength, 0)
        if (sound) {
            val uri = RingtoneManager.getActualDefaultRingtoneUri(c, if (call) RingtoneManager.TYPE_RINGTONE else RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            tone = RingtoneManager.getRingtone(c, uri)?.also {
                it.audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                if (Build.VERSION.SDK_INT >= 28) it.isLooping = true
                it.play()
            }
        }
        val app = c.applicationContext
        main.removeCallbacksAndMessages(null)
        main.postDelayed({ stop(app) }, 5 * 60_000L) // never ring forever
    }

    fun stop(c: Context) {
        main.removeCallbacksAndMessages(null)
        try { tone?.stop() } catch (e: Exception) {}
        tone = null
        ringingKey = null
        try { vibrator(c).cancel() } catch (e: Exception) {}
    }

    fun nudge(c: Context, set: JSONObject, strong: Boolean) {
        val (sound, vib) = gate(c, set)
        val strength = set.optString("strength", "strong")
        if (vib) vibrate(c, oncePattern(strength, strong), strength, -1)
        if (sound) {
            RingtoneManager.getRingtone(c, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))?.apply {
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                play()
            }
        }
    }
}
