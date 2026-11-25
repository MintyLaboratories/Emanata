package minty.emanata.ui

import android.os.SystemClock

/**
 * The reader's pace: the median time between its last [SAMPLES] turns to the next item, and
 * how long it has been on the item read now. A jump or a turn back starts over, as such a
 * pace says nothing of what comes next. Main thread only.
 */
internal class TurnPace(private val clock: () -> Long = SystemClock::uptimeMillis) {
    private val gaps = ArrayDeque<Long>()
    private var at = NO_ITEM
    private var since = 0L

    /** Notes that item [item] is read from now. */
    fun turned(item: Int) {
        if (item == at) return
        val now = clock()
        if (at != NO_ITEM && item == at + 1) {
            gaps.addLast(now - since)
            if (gaps.size > SAMPLES) gaps.removeFirst()
        } else {
            gaps.clear()
        }
        at = item
        since = now
    }

    /** Milliseconds a page is read lately; null until [MIN_SAMPLES] turns in a row say. */
    val paceMs: Long? get() = if (gaps.size < MIN_SAMPLES) null else gaps.sorted()[gaps.size / 2]

    /** Milliseconds on the item read now. */
    val dwellMs: Long get() = clock() - since

    /** Milliseconds between the last turns in a row, oldest first; at most [SAMPLES]. */
    val gapsMs: List<Long> get() = gaps.toList()

    companion object {
        const val SAMPLES = 3
        const val MIN_SAMPLES = 2
        private const val NO_ITEM = Int.MIN_VALUE
    }
}

/** Where AI work starts when the reader turns pages faster than they are processed. */
internal object AiLead {
    /** The most items AI work starts past the one read. */
    const val MAX = 4

    /**
     * Items past the one read to process first, so a page taking [pageMs] shows processed for
     * most of the time it is read: 0 while it is done before the reader, at [paceMs] a page and
     * [dwellMs] into this one, moves on (and with no pace known, or once the reader stays past
     * its pace); else the item read when it is done, if it then still shows for half a pace
     * or more, or the one after; at most [MAX] on.
     */
    fun of(pageMs: Long, paceMs: Long?, dwellMs: Long): Int {
        if (pageMs <= 0 || paceMs == null || paceMs <= 0 || dwellMs >= paceMs) return 0
        val left = paceMs - dwellMs
        if (pageMs <= left) return 0
        // Read when the page is done, and for how long after.
        val then = 1 + (pageMs - left) / paceMs
        val shown = left + then * paceMs - pageMs
        val lead = if (2 * shown >= paceMs) then else then + 1
        return lead.toInt().coerceAtMost(MAX)
    }
}

/** How long AI work holds off while the reader flips faster than pages are processed. */
internal object AiHold {
    /** The longest hold on one item. */
    const val MAX_MS = 1_500L

    /**
     * Milliseconds AI work still holds off on the item read, [dwellMs] into it; 0 when not held.
     * Held after the last two turns in a row ([gapsMs], oldest first) each came sooner than a
     * page takes ([pageMs]), until the item has stayed up for the last turn's interval plus a
     * fifth, at most [MAX_MS].
     */
    fun of(pageMs: Long, gapsMs: List<Long>, dwellMs: Long): Long {
        if (pageMs <= 0 || gapsMs.size < 2) return 0
        val last = gapsMs[gapsMs.size - 1]
        if (last >= pageMs || gapsMs[gapsMs.size - 2] >= pageMs) return 0
        return (minOf(last + last / 5, MAX_MS) - dwellMs).coerceAtLeast(0)
    }
}
