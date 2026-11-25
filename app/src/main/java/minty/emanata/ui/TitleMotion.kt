package minty.emanata.ui

import minty.emanata.data.TitleDisplay
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Where moving titles are at a given time, free of Android types. Every title
 * runs off one clock, so they move in sync: bounce cycles start together, and
 * last as long as the longest glide among the titles showing.
 */
object TitleMotion {
    /** Glide speed. */
    const val SPEED_DP_PER_S = 30f

    /** Rest at each end of a bounce. */
    const val PAUSE_MS = 2000L

    /** Between the end of a looping title and its next start. */
    const val MARQUEE_SEPARATOR = " • "

    /** [mode] as shown: moving titles hold still, cut in the middle, when the system removes animations. */
    fun effective(mode: TitleDisplay, animations: Boolean): TitleDisplay =
        if (mode.animated && !animations) TitleDisplay.MIDDLE else mode

    /** Milliseconds a glide over [overflow] px takes at [speed] px/ms. */
    fun travelMs(overflow: Float, speed: Float): Long = if (overflow <= 0f) 0L else ceil(overflow / speed).toLong()

    /** One bounce: rest, glide out for [travelMs], rest, glide back for [travelMs]. */
    fun bounceCycleMs(travelMs: Long, pauseMs: Long = PAUSE_MS): Long = 2 * (pauseMs + travelMs)

    /**
     * How far (px, from 0 to [overflow]) a bouncing title is scrolled [local] ms into
     * a cycle whose glides take [travelMs]: it rests, glides out at [speed] px/ms
     * and waits at its end for the longest glide to finish, rests, then glides back.
     */
    fun bounceOffset(local: Long, overflow: Float, speed: Float, travelMs: Long, pauseMs: Long = PAUSE_MS): Float {
        if (overflow <= 0f) return 0f
        val reach = min(overflow, travelMs * speed)
        return when {
            local < pauseMs -> 0f
            local < pauseMs + travelMs -> min(reach, (local - pauseMs) * speed)
            local < 2 * pauseMs + travelMs -> reach
            else -> max(0f, reach - (local - 2 * pauseMs - travelMs) * speed)
        }
    }

    /**
     * Milliseconds from [local] ms into a bounce cycle whose glides take [travelMs]
     * until a title whose own glide takes [ownMs] next moves: 0 while it moves, else
     * until its next glide starts or the cycle ends, whichever comes first.
     */
    fun bounceStillMs(local: Long, ownMs: Long, travelMs: Long, pauseMs: Long = PAUSE_MS): Long {
        val own = min(ownMs, travelMs)
        for (start in longArrayOf(pauseMs, 2 * pauseMs + travelMs)) {
            if (local < start) return start - local
            if (local < start + own) return 0L
        }
        return max(0L, bounceCycleMs(travelMs, pauseMs) - local)
    }

    /** How far a looping title is scrolled after [elapsed] ms, for a loop of [loop] px (title and separator). */
    fun marqueeOffset(elapsed: Long, speed: Float, loop: Float): Float =
        if (loop <= 0f) 0f else (elapsed * speed) % loop
}
