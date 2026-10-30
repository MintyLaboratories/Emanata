package com.infinity.comicreader.debug

import com.infinity.comicreader.debug.PageWatch.Ready
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MilestonesTest {
    @Test
    fun firstVisibleThenFirstSharpFrames() {
        val m = Milestones(start = 1_000)
        assertFalse(m.frame(1_010, Ready.NOTHING))
        assertFalse(m.frame(1_020, Ready.VISIBLE))
        assertFalse(m.frame(1_030, Ready.VISIBLE))
        assertTrue(m.frame(1_040, Ready.SHARP))
        assertEquals(20L, m.visible)
        assertEquals(40L, m.sharp)
        assertTrue(m.frame(1_050, Ready.SHARP))
        assertEquals(40L, m.sharp)
    }

    @Test
    fun aSharpFrameIsVisibleToo() {
        val m = Milestones(start = 0)
        assertTrue(m.frame(16, Ready.SHARP))
        assertEquals(16L, m.visible)
        assertEquals(16L, m.sharp)
    }

    @Test
    fun unknownUntilReached() {
        val m = Milestones(start = 0)
        m.frame(5, Ready.NOTHING)
        assertEquals(-1L, m.visible)
        assertEquals(-1L, m.sharp)
        assertEquals(-1L, m.moved)
        assertFalse(m.done)
    }

    @Test
    fun onlyTheFirstMoveCounts() {
        val m = Milestones(start = 100)
        m.moved(400)
        m.moved(500)
        assertEquals(300L, m.moved)
    }

    @Test
    fun framesBeforeTheStartCountAsAtIt() {
        val m = Milestones(start = 100)
        m.frame(90, Ready.SHARP)
        assertEquals(0L, m.sharp)
    }
}
