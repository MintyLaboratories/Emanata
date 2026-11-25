package minty.emanata.ui

import android.os.SystemClock
import android.view.MotionEvent
import androidx.recyclerview.widget.RecyclerView

/**
 * Whether the reader is moving: touched, its list scrolling (a page turn, a swipe, a fling),
 * or within [settleMs] of either ending, so background GPU work can wait for frames to settle.
 * A touch counts for at most [touchMs] after its last event, so a finger resting still does
 * not hold work back for long. Told on the main thread; [moving] is read on any.
 */
internal class ReaderMotion(
    private val clock: () -> Long = SystemClock::uptimeMillis,
    private val settleMs: Long = SETTLE_MS,
    private val touchMs: Long = TOUCH_MS
) {
    @Volatile
    private var touching = false

    @Volatile
    private var scrolling = false

    @Volatile
    private var lastTouch = 0L

    @Volatile
    private var quietAt = 0L

    /** True while the reader moves or just did. */
    val moving: Boolean
        get() {
            val now = clock()
            return scrolling || (touching && now - lastTouch < touchMs) || now < quietAt
        }

    /** Follows a touch event of [action] ([MotionEvent.getActionMasked]). */
    fun touched(action: Int) {
        val now = clock()
        lastTouch = now
        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> touching = true
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touching = false
                quietAt = now + settleMs
            }
        }
    }

    /** Follows the list's scroll [state] ([RecyclerView.SCROLL_STATE_IDLE] and the others). */
    fun scrolled(state: Int) {
        scrolling = state != RecyclerView.SCROLL_STATE_IDLE
        if (!scrolling) quietAt = clock() + settleMs
    }

    companion object {
        /** Time after a touch or scroll ends still counted as moving: a page settling, a zoom easing in. */
        const val SETTLE_MS = 250L

        /** Longest a touch without events counts as moving. */
        const val TOUCH_MS = 3_000L
    }
}
