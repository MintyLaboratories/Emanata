package com.infinity.comicreader.debug

/**
 * When the pages on screen first became visible and then sharp after [start] (an open or
 * a turn), checked frame by frame ([frame]); times in nanoseconds on one clock. A frame
 * finding them sharp counts as visible too.
 */
internal class Milestones(val start: Long) {
    /** Nanoseconds from [start] to the first frame they were visible; -1 until then. */
    var visible = -1L
        private set

    /** Nanoseconds from [start] to the first frame they were sharp; -1 until then. */
    var sharp = -1L
        private set

    /** Nanoseconds from [start] to the reader's move ([moved]); -1 until then. */
    var moved = -1L
        private set

    val done: Boolean get() = sharp >= 0

    /** The reader moved at [now]; only the first counts. */
    fun moved(now: Long) {
        if (moved < 0) moved = (now - start).coerceAtLeast(0)
    }

    /** The frame at [now] found the pages on screen [ready]; true once they are sharp. */
    fun frame(now: Long, ready: PageWatch.Ready): Boolean {
        val at = (now - start).coerceAtLeast(0)
        if (ready != PageWatch.Ready.NOTHING && visible < 0) visible = at
        if (ready == PageWatch.Ready.SHARP && sharp < 0) sharp = at
        return done
    }
}
