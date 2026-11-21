package com.infinity.comicreader.ui

import android.os.Handler
import android.os.Looper
import android.view.View
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * The reader's AI indicator: a small dot in the bottom right corner of the screen, shown while the
 * setting is on ([enabled]) and models work on a page of the open book ([busy], as
 * [com.infinity.comicreader.ai.AiQueue.working] tells it). It shows once the work has gone on for
 * [showDelayMs], so a page put off or given up at once never blinks it, and hides at once. Only its
 * visibility changes, never per tile; hidden while enabled it stays laid out (invisible), so showing
 * it costs no layout. [place] keeps it clear of the window's edges and a rounded screen corner,
 * and above the bars while they show, by translation alone: placing it lays nothing out.
 */
internal class AiDot(
    private val view: View,
    /** Its gap to the screen's edges, a rounded corner and the bars, in px. */
    private val margin: Int,
    private val showDelayMs: Long = SHOW_DELAY_MS
) {
    private val handler = Handler(Looper.getMainLooper())
    private val show = Runnable { view.visibility = View.VISIBLE }

    /** The setting: off, the dot never shows. */
    var enabled = false
        set(v) {
            if (field == v) return
            field = v
            update()
        }

    /** True while models work on a page of the open book. */
    var busy = false
        set(v) {
            if (field == v) return
            field = v
            update()
        }

    private fun update() {
        handler.removeCallbacks(show)
        when {
            !enabled -> view.visibility = View.GONE
            !busy -> view.visibility = View.INVISIBLE
            view.visibility != View.VISIBLE -> {
                view.visibility = View.INVISIBLE
                handler.postDelayed(show, showDelayMs)
            }
        }
    }

    /**
     * Puts the dot, laid out in its parent's bottom right corner, in the window's: its parent
     * [awayRight] and [awayBottom] px from the window's edges (the system bars and a display
     * cutout, in a window that fits them), the window's bottom right corner rounded at [radius]
     * px (0: square), and above [barTop] (in its parent) while a bar shows there, else null.
     */
    fun place(awayRight: Int, awayBottom: Int, radius: Int, barTop: Int?) {
        val (right, bottom) = offsets(awayRight, awayBottom, radius, margin)
        val dx = (right - awayRight).coerceAtLeast(0)
        val dy = (bottom - awayBottom).coerceAtLeast(0)
        view.translationX = -dx.toFloat()
        view.translationY = (-dy + lift(view.bottom - dy, barTop, margin)).toFloat()
    }

    /** Stops a pending show; the reader is going away. */
    fun release() = handler.removeCallbacks(show)

    companion object {
        /** How long models work on a page before the dot shows. */
        const val SHOW_DELAY_MS = 300L

        /** How far the corner of a box must stay from each edge of a corner rounded at [radius] px to sit wholly inside the curve. */
        fun cornerGap(radius: Int): Int = if (radius <= 0) 0 else ceil(radius * (1 - 1 / sqrt(2.0))).toInt()

        /**
         * The dot's distances from the window's right and bottom edges: past the [right] and [bottom]
         * insets, and past a corner rounded at [radius] when both insets leave the curve in the way,
         * plus [margin].
         */
        fun offsets(right: Int, bottom: Int, radius: Int, margin: Int): Pair<Int, Int> {
            val gap = if (right < radius && bottom < radius) cornerGap(radius) else 0
            return (maxOf(right, gap) + margin) to (maxOf(bottom, gap) + margin)
        }

        /** How far up a dot whose bottom is at [dotBottom] moves to sit [margin] above [barTop]; 0 with no bar. */
        fun lift(dotBottom: Int, barTop: Int?, margin: Int): Int =
            if (barTop == null) 0 else minOf(0, barTop - margin - dotBottom)
    }
}
