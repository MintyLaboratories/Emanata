package minty.emanata.debug

/**
 * When the pages on screen became visible and then sharp after [start] (an open or a
 * turn), from checks at the start of each frame ([frame]); times in nanoseconds on one
 * clock. A check finding them sharp counts as visible too.
 *
 * A check runs before its frame draws, so what it finds is only on screen once a frame
 * drawn after the change completes: [visibleAt] and [sharpAt] are when that change
 * happened, the later of the views' last change and the check before (a view scrolled in
 * during that frame's layout), and [visible] and [sharp] take the frame drawing it.
 */
internal class Milestones(val start: Long) {
    /** When the pages on screen became visible; -1 until then. */
    var visibleAt = -1L
        private set

    /** When the pages on screen became sharp; -1 until then. */
    var sharpAt = -1L
        private set

    /** Nanoseconds from [start] to the reader's move ([moved]); -1 until then. */
    var moved = -1L
        private set

    private var lastCheck = start

    val done: Boolean get() = sharpAt >= 0

    /** The reader moved at [now]; only the first counts. */
    fun moved(now: Long) {
        if (moved < 0) moved = (now - start).coerceAtLeast(0)
    }

    /** The check at [now] found the pages on screen [ready], last changed at [changedAt]; true once they are sharp. */
    fun frame(now: Long, ready: PageWatch.Ready, changedAt: Long): Boolean {
        val at = maxOf(changedAt, lastCheck)
        if (ready != PageWatch.Ready.NOTHING && visibleAt < 0) visibleAt = at
        if (ready == PageWatch.Ready.SHARP && sharpAt < 0) sharpAt = at
        lastCheck = maxOf(lastCheck, now)
        return done
    }

    /** Nanoseconds from [start] until they were visible on screen, as [drawnBy] tells when a frame drawn from a time on completed; -1 when unknown. */
    fun visible(drawnBy: (Long) -> Long): Long = since(visibleAt, drawnBy)

    /** Nanoseconds from [start] until they were sharp on screen ([visible]). */
    fun sharp(drawnBy: (Long) -> Long): Long = since(sharpAt, drawnBy)

    private fun since(at: Long, drawnBy: (Long) -> Long): Long {
        if (at < 0) return -1
        val drawn = drawnBy(at)
        return if (drawn < 0) -1 else (drawn - start).coerceAtLeast(0)
    }
}
