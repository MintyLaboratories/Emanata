package minty.emanata.reader

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Auto-scroll speeds, one definition for both reading modes: screen heights per
 * minute. A strip scrolls that far each minute; paged reading turns the page each
 * time one screen height would have scrolled past, so a page stays `60 / speed` s.
 */
object AutoScroll {

    /** The speeds offered, slowest first, in screen heights per minute. */
    val SPEEDS = floatArrayOf(0.25f, 0.5f, 0.75f, 1f, 1.5f, 2f, 3f, 4f, 5f, 6f, 8f, 10f, 12f, 15f, 20f, 30f, 45f)

    const val DEFAULT = 3f

    /** Quickest paged tick, in ms: the bar updates at most 10 times a second. */
    const val MIN_TICK_MS = 100L

    /** Slowest paged tick, in ms: the bar updates at least 4 times a second. */
    const val MAX_TICK_MS = 250L

    /** Index in [SPEEDS] of the speed nearest [speed]. */
    fun indexOf(speed: Float): Int = SPEEDS.indices.minBy { abs(SPEEDS[it] - speed) }

    /** The offered speed nearest [speed]. */
    fun nearest(speed: Float): Float = SPEEDS[indexOf(speed)]

    /** How long a page stays in paged reading at [speed], in ms. */
    fun pageMs(speed: Float): Long = (60_000f / speed).roundToLong().coerceAtLeast(1L)

    /** How far a strip [screenPx] tall scrolls per ms at [speed]. */
    fun pxPerMs(speed: Float, screenPx: Int): Float = screenPx * speed / 60_000f

    /** [speed] as stored and shown: "0.25", "1.5", "3". */
    fun format(speed: Float): String = if (speed == speed.toLong().toFloat()) speed.toLong().toString() else speed.toString()

    /** A stored [value] as an offered speed; [DEFAULT] when it is no number. */
    fun parse(value: String?): Float = value?.toFloatOrNull()?.takeIf { it > 0f }?.let { nearest(it) } ?: DEFAULT
}

/**
 * The clock of one auto-scroll run, fed the time between frames: whole pixels for a
 * strip to scroll, carrying the fraction to the next frame, or a page's time running
 * out, carrying the overshoot into the next page so turns keep the exact pace.
 */
class AutoScrollClock {
    private var remainder = 0f

    /** Time spent on the current page, in ms. */
    var elapsedMs = 0L
        private set

    /** Starts over, as for a new run or a page turned by hand. */
    fun reset() {
        remainder = 0f
        elapsedMs = 0L
    }

    /** The whole pixels a strip [screenPx] tall scrolls in [dtMs] more at [speed]. */
    fun scroll(dtMs: Long, speed: Float, screenPx: Int): Int {
        remainder += AutoScroll.pxPerMs(speed, screenPx) * dtMs
        val px = remainder.toInt()
        remainder -= px
        return px
    }

    /** Adds [dtMs] on the page; true when its time at [speed] is up and the page turns. */
    fun turn(dtMs: Long, speed: Float): Boolean {
        elapsedMs += dtMs
        val page = AutoScroll.pageMs(speed)
        if (elapsedMs < page) return false
        // A speed raised mid-page turns once, not once per page it overshot.
        elapsedMs = (elapsedMs - page).takeIf { it < page } ?: 0L
        return true
    }

    /**
     * The ms until the next paged tick at [speed], for a bar [barPx] wide: once the bar
     * has grown a pixel, within [AutoScroll.MIN_TICK_MS] to [AutoScroll.MAX_TICK_MS],
     * and exactly when the page's time is up if that comes first.
     */
    fun nextTickMs(speed: Float, barPx: Int): Long {
        val page = AutoScroll.pageMs(speed)
        val pixel = if (barPx > 0) page / barPx else AutoScroll.MAX_TICK_MS
        val left = (page - elapsedMs).coerceAtLeast(1L)
        return minOf(pixel.coerceIn(AutoScroll.MIN_TICK_MS, AutoScroll.MAX_TICK_MS), left)
    }

    /** How much of the page's time at [speed] has passed, 0 to 1. */
    fun progress(speed: Float): Float = (elapsedMs.toFloat() / AutoScroll.pageMs(speed)).coerceIn(0f, 1f)
}
