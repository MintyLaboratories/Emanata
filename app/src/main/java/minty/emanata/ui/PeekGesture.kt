package minty.emanata.ui

import minty.emanata.data.PeekMode

/**
 * The two-finger spread that peeks into a comic, free of Android types: the peek
 * starts once the fingers spread past a slop, grows with the spread, and on
 * release collapses, stays open or opens the book.
 */
class PeekGesture(
    /** Spread growth, in pixels, before the peek starts. */
    private val slop: Float,
    /** Spread growth, in pixels, that opens the peek fully. */
    private val fullSpread: Float
) {
    enum class Release { COLLAPSE, KEEP_OPEN, OPEN_BOOK }

    private var startSpan = 0f

    /** True once the spread has passed the slop and drives a peek. */
    var peeking = false
        private set

    /** How far open the peek is: 0 is the tile, 1 fully open. */
    var progress = 0f
        private set

    /** Two fingers went down [span] pixels apart. */
    fun begin(span: Float) {
        startSpan = span
        peeking = false
        progress = 0f
    }

    /** The fingers are now [span] pixels apart; returns true when this starts the peek. */
    fun move(span: Float): Boolean {
        val grown = span - startSpan
        if (!peeking) {
            if (grown <= slop) return false
            peeking = true
            // Growth starts from here, so the grid does not jump by the slop.
            startSpan += slop
            progress = progressOf(span - startSpan)
            return true
        }
        progress = progressOf(grown)
        return false
    }

    /** What lifting a finger does in [mode]. */
    fun release(mode: PeekMode): Release = outcome(progress, mode).also { peeking = false }

    private fun progressOf(grown: Float) = (grown / fullSpread).coerceIn(0f, 1f)

    companion object {
        /** Released below this, the peek collapses back into its tile. */
        const val COLLAPSE_BELOW = 0.2f

        /** Spread growth that opens the peek fully, as a share of the screen's shorter side. */
        const val FULL_SPREAD_SHARE = 0.3f

        fun outcome(progress: Float, mode: PeekMode): Release = when {
            progress < COLLAPSE_BELOW -> Release.COLLAPSE
            progress >= 1f && mode == PeekMode.PEEK_OPEN -> Release.OPEN_BOOK
            else -> Release.KEEP_OPEN
        }
    }
}

/** A rectangle as left, top, width and height. */
data class Box(val left: Float, val top: Float, val width: Float, val height: Float) {
    companion object {
        /** The box [t] of the way from [from] to [to]. */
        fun lerp(from: Box, to: Box, t: Float) = Box(
            from.left + (to.left - from.left) * t,
            from.top + (to.top - from.top) * t,
            from.width + (to.width - from.width) * t,
            from.height + (to.height - from.height) * t
        )
    }
}

/**
 * How a card laid out at [full] is drawn into [box]: scaled uniformly by [scale]
 * from its top-left corner, moved by [dx], [dy], and cut to its top [clipHeight]
 * (in its own pixels) so pages are never stretched.
 */
data class PeekFrame(val scale: Float, val dx: Float, val dy: Float, val clipHeight: Float) {
    companion object {
        fun of(box: Box, full: Box): PeekFrame {
            val scale = if (full.width > 0f) box.width / full.width else 1f
            val clip = if (scale > 0f) (box.height / scale).coerceAtMost(full.height) else 0f
            return PeekFrame(scale, box.left - full.left, box.top - full.top, clip)
        }
    }
}
