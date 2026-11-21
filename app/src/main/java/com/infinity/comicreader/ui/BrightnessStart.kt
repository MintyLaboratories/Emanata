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
    ): Boolean = allowedAt(x, y, imageLeft, imageTop, imageRight, imageBottom, viewLeft + x, viewTop + y, windowWidth, windowHeight, gestures)

    /**
     * As [allowed], the finger at ([windowX], [windowY]) in the window: for a view shown
     * turned ([TurnFrame]), whose coordinates run along other axes than the window's.
     */
    fun allowedAt(
        x: Float, y: Float,
        imageLeft: Float, imageTop: Float, imageRight: Float, imageBottom: Float,
        windowX: Float, windowY: Float, windowWidth: Int, windowHeight: Int, gestures: Edges
    ): Boolean {
        if (x < imageLeft || x > imageRight || y < imageTop || y > imageBottom) return false
        if (windowWidth <= 0 || windowHeight <= 0) return true
        return windowX >= gestures.left && windowX <= windowWidth - gestures.right &&
            windowY >= gestures.top && windowY <= windowHeight - gestures.bottom
    }
}
