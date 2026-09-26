package app.depart

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager

/** The full-screen "Time to leave" / call screen. Shows over the lock screen. */
class AlarmActivity : WebActivity() {
    override fun startUrl(): String =
        "file:///android_asset/index.html#alarm=" + Uri.encode(intent.getStringExtra("key") ?: "test") +
            "&style=" + (intent.getStringExtra("style") ?: "alarm")

    @Suppress("DEPRECATION")
    private fun wakeFlags() {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        wakeFlags()
        super.onCreate(savedInstanceState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        web.loadUrl(startUrl())
    }
}
