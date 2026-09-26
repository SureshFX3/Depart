package app.depart

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Fmt {
    fun time(ms: Long): String = SimpleDateFormat("h:mm a", Locale.US).format(Date(ms))

    fun dur(min: Int): String {
        val m = if (min < 0) 0 else min
        if (m < 60) return "$m min"
        val h = m / 60
        val r = m % 60
        return if (r == 0) "$h h" else "$h h $r min"
    }
}
