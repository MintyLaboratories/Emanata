package com.infinity.comicreader.ui

import android.view.OrientationEventListener.ORIENTATION_UNKNOWN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UprightFilterTest {

    @Test
    fun readingsNearAQuarterPointToIt() {
        assertEquals(0, UprightFilter.quarterOf(0))
        assertEquals(0, UprightFilter.quarterOf(25))
        assertEquals(0, UprightFilter.quarterOf(335))
        assertEquals(1, UprightFilter.quarterOf(90))
        assertEquals(2, UprightFilter.quarterOf(200))
        assertEquals(3, UprightFilter.quarterOf(240))
        assertEquals(0, UprightFilter.quarterOf(360))
    }

    @Test
    fun flatOrBetweenQuartersPointsNowhere() {
        assertEquals(-1, UprightFilter.quarterOf(ORIENTATION_UNKNOWN))
        assertEquals(-1, UprightFilter.quarterOf(45))
        assertEquals(-1, UprightFilter.quarterOf(130))
    }

    @Test
    fun aQuarterSettlesOnceItHolds() {
        val f = UprightFilter(settleMs = 400)
        assertFalse(f.feed(ORIENTATION_UNKNOWN, 0))
        assertFalse(f.settled(1000))
        assertTrue(f.feed(2, 1000))
        assertFalse(f.feed(10, 1200))
        assertFalse(f.settled(1399))
        assertTrue(f.settled(1400))
    }

    @Test
    fun lyingDownAgainOrTurningStartsOver() {
        val f = UprightFilter(settleMs = 400)
        f.feed(0, 0)
        f.feed(ORIENTATION_UNKNOWN, 300)
        assertFalse(f.settled(1000))
        assertTrue(f.feed(90, 1000))
        assertTrue(f.feed(180, 1300))
        assertFalse(f.settled(1600))
        assertTrue(f.settled(1700))
        f.reset()
        assertFalse(f.settled(5000))
    }
}
