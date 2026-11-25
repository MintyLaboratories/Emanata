package minty.emanata.ui

import android.content.res.Resources
import minty.emanata.R
import minty.emanata.reader.AutoScroll

/** How an auto-scroll speed reads: "3 screens/min · 20 s a page". */
internal object AutoScrollText {

    fun of(res: Resources, speed: Float): String {
        val screens = res.getQuantityString(R.plurals.auto_speed_screens, if (speed == 1f) 1 else 2, AutoScroll.format(speed))
        return res.getString(R.string.auto_speed_value, screens, pageTime(res, AutoScroll.pageMs(speed)))
    }

    /** [ms] as "1.3 s", "40 s", "1 min 20 s" or "4 min": tenths of a second only below 10 s. */
    fun pageTime(res: Resources, ms: Long): String {
        val tenths = (ms + 50) / 100
        if (tenths < 600) {
            val seconds = if (tenths < 100 && tenths % 10 != 0L) "${tenths / 10}.${tenths % 10}" else ((tenths + 5) / 10).toString()
            return res.getString(R.string.duration_seconds, seconds)
        }
        val seconds = (ms + 500) / 1000
        val minutes = (seconds / 60).toInt()
        val rest = (seconds % 60).toInt()
        return if (rest == 0) res.getString(R.string.duration_minutes, minutes)
        else res.getString(R.string.duration_minutes_seconds, minutes, rest)
    }
}
