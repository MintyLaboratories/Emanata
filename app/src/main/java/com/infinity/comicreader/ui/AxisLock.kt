package com.infinity.comicreader.ui

import kotlin.math.abs

/** The axis a drag is locked to once it first moves past the touch slop. */
enum class AxisLock {
    NONE, HORIZONTAL, VERTICAL;

    companion object {
        /**
         * The axis for a drag that has moved ([dx], [dy]) from where it went down: [NONE]
         * within [slop], else the larger component's, horizontal on a tie.
         */
        fun decide(dx: Float, dy: Float, slop: Float): AxisLock = when {
            dx * dx + dy * dy <= slop * slop -> NONE
            abs(dy) > abs(dx) -> VERTICAL
            else -> HORIZONTAL
        }
    }
}
