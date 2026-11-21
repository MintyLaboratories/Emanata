package com.infinity.comicreader.ui

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * The reader's AI indicator: a small dot in the bottom right corner of the screen, shown while the
 * setting is on ([enabled]) and models work on a page of the open book ([busy], as
 * [com.infinity.comicreader.ai.AiQueue.working] tells it). It shows once the work has gone on for
 * [showDelayMs], so a page put off or given up at once never blinks it, and hides at once. Only its
 * visibility changes, never per tile; hidden while enabled it stays laid out (invisible), so showing
 * it costs no layout. [inset] and [place] keep it inside the window's insets, clear of a rounded
 * screen corner, and above the bars while they show.
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

    /** The window's insets at the right and bottom edges, and the radius of its rounded bottom right corner, in px. */
    private var insetRight = 0
    private var insetBottom = 0
    private var radius = 0

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

    /** Takes the window's insets at the right and bottom edges (system bars, display cutout) and its bottom right corner's [radius]. */
    fun inset(right: Int, bottom: Int, radius: Int) {
        insetRight = right
        insetBottom = bottom
        this.radius = radius
    }

    /**
     * Puts the dot in the corner: [awayRight] and [awayBottom] px from the window's edges, as its
     * parent is, and above [barTop] (in its parent) while a bar shows there, else null.
     */
    fun place(awayRight: Int, awayBottom: Int, barTop: Int?) {
        val (right, bottom) = offsets(insetRight + awayRight, insetBottom + awayBottom, radius, margin)
        val lp = view.layoutParams as? ViewGroup.MarginLayoutParams
        if (lp != null) {
            val r = (right - awayRight).coerceAtLeast(0)
            val b = (bottom - awayBottom).coerceAtLeast(0)
            if (lp.rightMargin != r || lp.bottomMargin != b) {
                lp.rightMargin = r
                lp.bottomMargin = b
                view.layoutParams = lp
            }
        }
        view.translationY = lift(view.bottom, barTop, margin).toFloat()
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
