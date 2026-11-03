package com.infinity.comicreader.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The reader's pace of turns, and how far ahead AI work starts to keep up with it. */
class AiPaceTest {
    private var now = 0L
    private val pace = TurnPace { now }

    private fun turnEvery(ms: Long, vararg items: Int) {
        for (item in items) {
            pace.turned(item)
            now += ms
        }
    }

    @Test
    fun aPaceIsKnownAfterTwoTurnsInARowAndIsTheirMedian() {
        pace.turned(0)
        now += 3_000
        pace.turned(1)
        assertNull(pace.paceMs)
        now += 5_000
        pace.turned(2)
        now += 4_000
        pace.turned(3)
        assertEquals(4_000L, pace.paceMs)
        now += 1_000
        pace.turned(4)
        assertEquals("the last three", 4_000L, pace.paceMs)
        assertEquals(0L, pace.dwellMs)
    }

    @Test
    fun aJumpOrATurnBackStartsOver() {
        turnEvery(3_000, 0, 1, 2)
        assertEquals(3_000L, pace.paceMs)
        pace.turned(10)
        assertNull(pace.paceMs)
        turnEvery(3_000, 10, 11, 12)
        assertEquals(3_000L, pace.paceMs)
        pace.turned(11)
        assertNull(pace.paceMs)
    }

    @Test
    fun noLeadWhilePagesAreDoneBeforeTheReaderMovesOn() {
        assertEquals(0, AiLead.of(5_000, null, 0))
        assertEquals(0, AiLead.of(5_000, 8_000, 0))
        assertEquals(0, AiLead.of(5_000, 8_000, 3_000))
        assertEquals("a reader staying past its pace reads slower now", 0, AiLead.of(5_000, 3_000, 3_000))
        assertEquals(0, AiLead.of(0, 3_000, 0))
    }

    @Test
    fun turningFasterThanPagesAreDoneStartsAtThePageReachedOnceOneIsDone() {
        // 5 s a page, a turn every 3 s: the next page would show processed for its last second only; the one after, all of it.
        assertEquals(2, AiLead.of(5_000, 3_000, 0))
        // 2 s into this page: the next comes at 1 s, the one after at 4 s, shown processed for 2 of its 3 s.
        assertEquals(2, AiLead.of(5_000, 3_000, 2_000))
        // 3.4 s a page, a turn every 3 s: the next shows processed for 2.6 of its 3 s.
        assertEquals(1, AiLead.of(3_400, 3_000, 0))
        assertEquals(1, AiLead.of(5_000, 8_000, 4_000))
        assertEquals("at most so far ahead", AiLead.MAX, AiLead.of(60_000, 1_000, 0))
    }
}
