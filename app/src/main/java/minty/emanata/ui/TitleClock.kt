package minty.emanata.ui

import android.view.Choreographer

/**
 * One clock for every moving title of a list, so they move in sync. It ticks only
 * while some title moves and the list neither scrolls nor hides; [elapsed] holds
 * still in between, so titles pick up where they stopped. Only titles that moved
 * are redrawn, and while all rest it sleeps until the next one moves. Titles redraw on
 * every other frame of a 120 Hz screen (every frame at 90 Hz and below): their slow
 * glide looks the same for half the drawing.
 */
class TitleClock(
    /** Glide speed in px per ms. */
    val speed: Float
) : Choreographer.FrameCallback {

    private val views = LinkedHashSet<TitleView>()
    private var elapsedNanos = 0L
    private var lastFrame = 0L
    private var posted = false

    /** The frame titles last redrew on. */
    private var lastTick = 0L

    /** The callback posted waits for the end of a rest. */
    private var delayed = false
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
            if (paused) cancel() else post()
        }

    /** The screen is not showing: titles wait. */
    var stopped = false
        set(value) {
            field = value
            if (paused) cancel() else post()
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

    /** Ticks on the next frame; a rest being waited out ends early, still counted. */
    private fun post() {
        if (paused || views.isEmpty()) return
        if (posted && !delayed) return
        val choreographer = Choreographer.getInstance()
        if (posted) choreographer.removeFrameCallback(this) else lastFrame = 0L
        posted = true
        delayed = false
        choreographer.postFrameCallback(this)
    }

    /** Drops the tick waiting; a pause does not count toward [elapsed]. */
    private fun cancel() {
        if (!posted) return
        Choreographer.getInstance().removeFrameCallback(this)
        posted = false
        delayed = false
    }

    override fun doFrame(frameTimeNanos: Long) {
        posted = false
        delayed = false
        if (paused || views.isEmpty()) return
        if (lastFrame != 0L) elapsedNanos += frameTimeNanos - lastFrame
        lastFrame = frameTimeNanos
        if (local >= TitleMotion.bounceCycleMs(cycleTravel)) {
            cycleStart = elapsed
            cycleTravel = views.maxOf { it.travelMs }
        }
        // Every frame counts toward [elapsed]; titles redraw on those at least [MIN_TICK_NS] apart.
        if (frameTimeNanos - lastTick >= MIN_TICK_NS) {
            lastTick = frameTimeNanos
            views.forEach { it.tick() }
        }
        val still = views.minOf { it.stillMs() }
        posted = true
        if (still > REST_MIN_MS) {
            delayed = true
            Choreographer.getInstance().postFrameCallbackDelayed(this, still)
        } else {
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private companion object {
        /** Rests shorter than about two frames are ticked through. */
        const val REST_MIN_MS = 32L

        /** Between a 120 Hz frame and a 90 Hz one: every other frame at 120 Hz, every one at 90 or 60 Hz. */
        const val MIN_TICK_NS = 10_000_000L
    }
}
