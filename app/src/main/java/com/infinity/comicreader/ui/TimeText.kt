package com.infinity.comicreader.ui

import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.lifecycle.LifecycleCoroutineScope
import com.infinity.comicreader.R
import com.infinity.comicreader.files.Span
import com.infinity.comicreader.util.AppDispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Future

/** Convert work's times as shown: how long it takes, before it starts, and the time left while it runs. */
internal object TimeText {
    /** "Takes about 12 min" for [seconds]. */
    fun takes(context: Context, seconds: Double): String = text(context, seconds, R.string.takes_under_minute, R.string.takes_minutes, R.string.takes_hours, R.string.takes_hours_minutes)

    /** "About 12 min left" for [seconds]. */
    fun left(context: Context, seconds: Double): String = text(context, seconds, R.string.left_under_minute, R.string.left_minutes, R.string.left_hours, R.string.left_hours_minutes)

    private fun text(context: Context, seconds: Double, under: Int, minutes: Int, hours: Int, both: Int): String {
        val span = Span.of(seconds)
        return when {
            span.underMinute -> context.getString(under)
            span.hours == 0 -> context.getString(minutes, span.minutes)
            span.minutes == 0 -> context.getString(hours, span.hours)
            else -> context.getString(both, span.hours, span.minutes)
        }
    }

    /**
     * Shows in [line] that the time is being estimated, then how long the work [forecast]
     * gives takes once known (in [scope], so a screen gone hears nothing); hidden when it
     * cannot be estimated.
     */
    fun show(line: TextView, scope: LifecycleCoroutineScope, forecast: Future<List<Double>>) {
        line.setText(R.string.takes_estimating)
        line.visibility = View.VISIBLE
        scope.launch {
            val seconds = withContext(AppDispatchers.io) { runCatching { forecast.get().sum() }.getOrNull() }
            if (seconds == null) line.visibility = View.GONE else line.text = takes(line.context, seconds)
        }
    }
}
