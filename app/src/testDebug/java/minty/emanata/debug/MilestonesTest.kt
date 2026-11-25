package minty.emanata.debug

import minty.emanata.debug.PageWatch.Ready
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MilestonesTest {
    /** Frames that begin drawing every 10 ns from 1 005 on and complete 7 ns later. */
    private val frames = FrameStats().apply { for (t in 1_005L..1_105L step 10) add(7, 10, t, t + 7) }

    @Test
    fun firstVisibleThenFirstSharpChanges() {
        val m = Milestones(start = 1_000)
        assertFalse(m.frame(1_010, Ready.NOTHING, changedAt = 1_002))
        assertFalse(m.frame(1_020, Ready.VISIBLE, changedAt = 1_012))
        assertFalse(m.frame(1_030, Ready.VISIBLE, changedAt = 1_012))
        assertTrue(m.frame(1_040, Ready.SHARP, changedAt = 1_033))
        assertEquals(1_012L, m.visibleAt)
        assertEquals(1_033L, m.sharpAt)
        assertTrue(m.frame(1_050, Ready.SHARP, changedAt = 1_045))
        assertEquals(1_033L, m.sharpAt)
    }

    @Test
    fun onScreenOnceTheFrameDrawingTheChangeCompletes() {
        val m = Milestones(start = 1_000)
        m.frame(1_020, Ready.VISIBLE, changedAt = 1_012)
        m.frame(1_040, Ready.SHARP, changedAt = 1_033)
        // Drawn by the frames beginning to draw at 1 015 and 1 035.
        assertEquals(22L, m.visible(frames::drawnBy))
        assertEquals(42L, m.sharp(frames::drawnBy))
    }

    @Test
    fun aViewScrolledInChangedNoLaterThanTheCheckBefore() {
        val m = Milestones(start = 1_000)
        m.frame(1_010, Ready.NOTHING, changedAt = 0)
        // Bound long before, it came on screen in the layout after the check at 1 010.
        m.frame(1_020, Ready.SHARP, changedAt = 500)
        assertEquals(1_010L, m.sharpAt)
        assertEquals(22L, m.sharp(frames::drawnBy))
    }

    @Test
    fun aSharpCheckIsVisibleToo() {
        val m = Milestones(start = 0)
        assertTrue(m.frame(16, Ready.SHARP, changedAt = 10))
        assertEquals(10L, m.visibleAt)
        assertEquals(10L, m.sharpAt)
    }

    @Test
    fun unknownUntilReachedOrDrawn() {
        val m = Milestones(start = 0)
        m.frame(5, Ready.NOTHING, changedAt = 1)
        assertEquals(-1L, m.visibleAt)
        assertEquals(-1L, m.sharp(frames::drawnBy))
        assertEquals(-1L, m.moved)
        assertFalse(m.done)
        // Sharp after the last frame heard: not drawn yet.
        m.frame(2_000, Ready.SHARP, changedAt = 1_990)
        assertEquals(-1L, m.sharp(frames::drawnBy))
    }

    @Test
    fun onlyTheFirstMoveCounts() {
        val m = Milestones(start = 100)
        m.moved(400)
        m.moved(500)
        assertEquals(300L, m.moved)
    }

    @Test
    fun changesBeforeTheStartCountAsAtIt() {
        val m = Milestones(start = 1_000)
        m.frame(1_001, Ready.SHARP, changedAt = 90)
        assertEquals(1_000L, m.sharpAt)
        assertEquals(12L, m.sharp(frames::drawnBy))
    }
}
