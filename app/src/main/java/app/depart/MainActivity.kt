package app.depart

import android.os.Bundle

class MainActivity : WebActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Engine.reschedule(applicationContext)
    }
}
