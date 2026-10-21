package com.infinity.comicreader.ui

/**
 * What the brightness drag indicator shows: the signed value with a typographic minus,
 * and where its bar ends. [snapped] says when a drag has just landed on 0, for the tick.
 */
object BrightnessPill {
    const val MIN = -100
    const val MAX = 100

    /** How long the pill stays after the drag ends, in ms, before it fades. */
    const val LINGER_MS = 1000L

    /** "+12", "−20" or "0". */
    fun valueText(value: Int): String = when {
        value > 0 -> "+$value"
        value < 0 -> "−${-value}"
        else -> "0"
    }

    /** Where [value]'s bar ends on a track from [MIN] (0) to [MAX] (1); 0 sits at 0.5. */
    fun fraction(value: Int): Float = (value.coerceIn(MIN, MAX) - MIN).toFloat() / (MAX - MIN)

    /** True when a drag moved from [before] to exactly 0: it snapped into the hold at 0. */
    fun snapped(before: Int, now: Int): Boolean = before != 0 && now == 0
}
