package com.infinity.comicreader.ui

import android.view.Choreographer

/**
 * One clock for every moving title of a list, so they move in sync. It ticks only
 * while some title moves and the list neither scrolls nor hides; [elapsed] holds
 * still in between, so titles pick up where they stopped.
 */
class TitleClock(
    /** Glide speed in px per ms. */
    val speed: Float
) : Choreographer.FrameCallback {

    private val views = LinkedHashSet<TitleView>()
    private var elapsedNanos = 0L
    private var lastFrame = 0L
    private var posted = false
    private var cycleStart = 0L

    /** Running time in ms. */
    val elapsed: Long get() = elapsedNanos / 1_000_000

    /** The longest glide among the titles showing when this bounce cycle began. */
    var cycleTravel = 0L
        private set

    /** Time into the current bounce cycle. */
    val local: Long get() = elapsed - cycleStart

    /** The list is scrolling: titles wait. */
    var scrolling = false
        set(value) {
            field = value
            post()
        }

    /** The screen is not showing: titles wait. */
    var stopped = false
        set(value) {
            field = value
            post()
        }

    private val paused get() = scrolling || stopped

    fun add(view: TitleView) {
        if (!views.add(view)) return
        // A longer title arriving while the others still rest joins this cycle.
        if (view.travelMs > cycleTravel && local < TitleMotion.PAUSE_MS) cycleTravel = view.travelMs
        post()
    }

    fun remove(view: TitleView) {
        views.remove(view)
    }

    private fun post() {
        if (posted || paused || views.isEmpty()) return
        posted = true
        lastFrame = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        posted = false
        if (paused || views.isEmpty()) return
        if (lastFrame != 0L) elapsedNanos += frameTimeNanos - lastFrame
        lastFrame = frameTimeNanos
        if (local >= TitleMotion.bounceCycleMs(cycleTravel)) {
            cycleStart = elapsed
            cycleTravel = views.maxOf { it.travelMs }
        }
        views.forEach { it.invalidate() }
        posted = true
        Choreographer.getInstance().postFrameCallback(this)
    }
}
