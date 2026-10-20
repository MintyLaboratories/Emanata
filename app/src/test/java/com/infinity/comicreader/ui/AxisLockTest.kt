package com.infinity.comicreader.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AxisLockTest {

    @Test
    fun withinTheSlopNothingIsDecided() {
        assertEquals(AxisLock.NONE, AxisLock.decide(0f, 0f, 8f))
        assertEquals(AxisLock.NONE, AxisLock.decide(5f, -6f, 8f))
        assertEquals(AxisLock.NONE, AxisLock.decide(0f, 8f, 8f))
    }

    @Test
    fun theLargerComponentPicksTheAxis() {
        assertEquals(AxisLock.VERTICAL, AxisLock.decide(3f, 9f, 8f))
        assertEquals(AxisLock.VERTICAL, AxisLock.decide(-6f, -7f, 8f))
        assertEquals(AxisLock.HORIZONTAL, AxisLock.decide(9f, 3f, 8f))
        assertEquals(AxisLock.HORIZONTAL, AxisLock.decide(-7f, 6f, 8f))
    }

    @Test
    fun aDiagonalTurnsThePage() {
        assertEquals(AxisLock.HORIZONTAL, AxisLock.decide(7f, 7f, 8f))
        assertEquals(AxisLock.HORIZONTAL, AxisLock.decide(-7f, -7f, 8f))
    }
}
