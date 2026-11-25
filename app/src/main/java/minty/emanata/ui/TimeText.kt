package minty.emanata.ui

import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.lifecycle.LifecycleCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import minty.emanata.R
import minty.emanata.files.ConvertRates
import minty.emanata.files.Span
import minty.emanata.util.AppDispatchers
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

    /** "About 12 min" for [seconds], as part of a sentence. */
    fun span(context: Context, seconds: Double): String = text(context, seconds, R.string.span_under_minute, R.string.span_minutes, R.string.span_hours, R.string.span_hours_minutes)

    /**
     * How long work of [seconds] with the app in front takes ([takes]), and [away] seconds
     * with it away when that reads otherwise: "Takes about 12 min, about 40 min with the app away".
     */
    fun takes(context: Context, seconds: Double, away: Double): String {
        val front = takes(context, seconds)
        return if (Span.of(away) == Span.of(seconds)) front else context.getString(R.string.takes_away, front, span(context, away))
    }

    /**
     * Shows in [line] that the time is being estimated, then how long the work [forecast]
     * gives takes once known, with the app in front and away ([ConvertRates.slowdown]); in
     * [scope], so a screen gone hears nothing. Hidden when it cannot be estimated.
     */
    fun show(line: TextView, scope: LifecycleCoroutineScope, forecast: Future<List<Double>>) {
        line.setText(R.string.takes_estimating)
        line.visibility = View.VISIBLE
        scope.launch {
            val seconds = withContext(AppDispatchers.io) { runCatching { forecast.get().sum() }.getOrNull() }
            if (seconds == null) line.visibility = View.GONE else line.text = takes(line.context, seconds, seconds * ConvertRates.slowdown())
        }
    }
}
