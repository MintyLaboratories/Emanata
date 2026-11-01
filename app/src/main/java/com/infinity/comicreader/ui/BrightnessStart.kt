package com.infinity.comicreader.ui

/**
 * Where a brightness drag may start: on the page image itself (not its margins or
 * letterbox), and never in the system's gesture areas (the home strip at the bottom, the
 * back edges at the sides), whose swipes belong to the system.
 */
object BrightnessStart {
    /** How far in from each edge of the window the system's gesture areas reach, in px. */
    data class Edges(val left: Int = 0, val top: Int = 0, val right: Int = 0, val bottom: Int = 0) {
        companion object {
            val NONE = Edges()
        }
    }

    /**
     * True for a finger down at ([x], [y]) in a view showing its image in ([imageLeft],
     * [imageTop])-([imageRight], [imageBottom]) (view coordinates), the view at ([viewLeft],
     * [viewTop]) in a window [windowWidth] x [windowHeight] with gesture areas [gestures]
     * (none while the window has no size yet).
     */
    fun allowed(
        x: Float, y: Float,
        imageLeft: Float, imageTop: Float, imageRight: Float, imageBottom: Float,
        viewLeft: Int, viewTop: Int, windowWidth: Int, windowHeight: Int, gestures: Edges
    ): Boolean {
        if (x < imageLeft || x > imageRight || y < imageTop || y > imageBottom) return false
        if (windowWidth <= 0 || windowHeight <= 0) return true
        val wx = viewLeft + x
        val wy = viewTop + y
        return wx >= gestures.left && wx <= windowWidth - gestures.right && wy >= gestures.top && wy <= windowHeight - gestures.bottom
    }
}
