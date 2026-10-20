package com.infinity.comicreader.ui

import kotlin.math.roundToInt

/**
 * Turns finger travel into a value in [min]..[max] that sticks at 0: crossing 0 holds
 * the value at exactly 0 for [hold] px of travel, then it goes on from 0, either way.
 *
 * The value follows a track: below 0 it is the travel over [pxPerUnit], then a flat
 * stretch [hold] px long at 0, then the travel past it. Travel beyond either end is
 * dropped, so turning back moves the value at once. A drag starting at 0 starts at the
 * end of the flat stretch it moves away from, so it never waits there.
 */
class SnapDrag(
    start: Int,
    private val pxPerUnit: Float,
    private val hold: Float,
    private val min: Int = -100,
    private val max: Int = 100
) {
    /** Where on the track the value is; null while a drag from 0 has not picked a side. */
    private var at: Float? = when {
        start < 0 -> start.coerceAtLeast(min) * pxPerUnit
        start > 0 -> hold + start.coerceAtMost(max) * pxPerUnit
        else -> null
    }

    /** The value after moving [delta] px toward larger values (negative: smaller). */
    fun moveBy(delta: Float): Int {
        val from = at ?: when {
            delta > 0f -> hold
            delta < 0f -> 0f
            else -> return 0
        }
        val to = (from + delta).coerceIn(min * pxPerUnit, hold + max * pxPerUnit)
        at = to
        return valueAt(to)
    }

    private fun valueAt(p: Float): Int = when {
        p < 0f -> (p / pxPerUnit).roundToInt()
        p > hold -> ((p - hold) / pxPerUnit).roundToInt()
        else -> 0
    }.coerceIn(min, max)
}
