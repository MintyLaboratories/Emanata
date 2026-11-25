package minty.emanata.ui

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
         * True when a touch at [x] lands on the thumb of a seek bar at [progress] of [max]:
         * within [thumbHalf] pixels of its center on a track [track] pixels long starting
         * [start] pixels in, running right to left with [rtl]. Such a touch is kept from
         * the seek bar until it moves or lifts, so a hold there never moves the page.
         */
        fun onThumb(x: Float, progress: Int, max: Int, start: Int, track: Int, thumbHalf: Int, rtl: Boolean): Boolean {
            if (max <= 0 || track <= 0) return true
            val f = progress.toFloat() / max
            val center = start + track * (if (rtl) 1f - f else f)
            return abs(x - center) <= thumbHalf
        }
    }
}
