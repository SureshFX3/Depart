package app.depart

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** Shows the Depart interface (assets/index.html) and connects it to the native side. */
open class WebActivity : Activity() {
    lateinit var web: WebView

    open fun startUrl(): String = "file:///android_asset/index.html"

    @Suppress("DEPRECATION")
    private fun clearBars() {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Alerts.channels(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        clearBars()
        val root = FrameLayout(this).apply { setBackgroundColor(0xFF131418.toInt()) }
        WindowInsetsControllerCompat(window, root).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        web = WebView(this).apply {
            setBackgroundColor(0xFF131418.toInt())
            overScrollMode = View.OVER_SCROLL_NEVER
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            addJavascriptInterface(Bridge(this@WebActivity), "Android")
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val u = request.url
                    if (u.scheme == "http" || u.scheme == "https") {
                        try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (e: Exception) {}
                        return true
                    }
                    return false
                }
            }
        }
        root.addView(web, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val s = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(s.left, s.top, s.right, s.bottom)
            WindowInsetsCompat.CONSUMED
        }
        setContentView(root)
        web.loadUrl(startUrl())
    }

    fun js(code: String) {
        runOnUiThread { if (::web.isInitialized) web.evaluateJavascript(code, null) }
    }

    override fun onResume() {
        super.onResume()
        if (::web.isInitialized) {
            web.resumeTimers()
            js("window.onNative&&onNative('resume')")
        }
    }

    override fun onPause() {
        super.onPause()
        // Stops the screen's refresh timer while you're not looking at it (saves battery).
        if (::web.isInitialized) web.pauseTimers()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        js("window.onNative&&onNative('perm')")
        Engine.reschedule(applicationContext)
    }
}
