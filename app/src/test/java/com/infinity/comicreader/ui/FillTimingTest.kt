package com.infinity.comicreader.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FillTimingTest {

    @Test
    fun stagesInOrder() {
        val t = FillTiming(1_000)
        val view = 0..2
        assertFalse(t.shown(1, false, view, 1_040))
        assertEquals(40, t.first)
        assertFalse(t.shown(0, false, view, 1_050))
        assertFalse(t.shown(2, true, view, 1_070))
        assertEquals(70, t.inView)
        assertFalse(t.shown(0, true, view, 1_200))
        assertTrue(t.shown(1, true, view, 1_300))
        assertEquals(300, t.exact)
        assertTrue(t.done)
        assertFalse("reported once", t.shown(2, true, view, 1_400))
    }

    @Test
    fun cellsOutOfViewDoNotCount() {
        val t = FillTiming(0)
        assertFalse(t.shown(5, true, 0..1, 10))
        assertFalse(t.shown(0, true, 0..1, 20))
        assertTrue(t.shown(1, true, 0..1, 30))
        assertEquals(10, t.first)
        assertEquals(30, t.inView)
    }

    @Test
    fun nothingInViewCompletesNothing() {
        val t = FillTiming(0)
        assertFalse(t.shown(0, true, IntRange.EMPTY, 5))
        assertEquals(-1, t.exact)
    }
}
