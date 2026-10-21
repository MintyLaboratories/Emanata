package com.infinity.comicreader.ui

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Tells a tap-and-hold on the seek bar from a drag: a touch that stays within [slop]
 * pixels of where it went down until [HOLD_MS] passes is a hold. The caller times it
 * and asks [held] once the time is up.
 */
class SeekHold(private val slop: Float) {
    private var downX = 0f
    private var downY = 0f

    /** True from a touch going down until it moves past [slop] or lifts. */
    var still = false
        private set

    fun down(x: Float, y: Float) {
        downX = x
        downY = y
        still = true
    }

    /** A move to ([x], [y]); false once the touch has left [slop], after which it never holds. */
    fun move(x: Float, y: Float): Boolean {
        if (still && hypot(x - downX, y - downY) > slop) still = false
        return still
    }

    fun up() {
        still = false
    }

    /** True when the time is up and the touch never moved: open the preview strip. */
    fun held(): Boolean = still.also { still = false }

    companion object {
        /** How long a touch is held still before it opens the preview strip. */
        const val HOLD_MS = 400L

        /**
         * True when a touch that moved the seek bar from item [from] to [to] went down on its
         * thumb, [thumbHalf] pixels either side of it on a track [track] pixels long for
         * [max] steps: a hold there keeps the item it started on.
         */
        fun onThumb(from: Int, to: Int, thumbHalf: Int, track: Int, max: Int): Boolean {
            if (max <= 0 || track <= 0) return true
            return abs(to - from).toFloat() * track / max <= thumbHalf
        }
    }
}
