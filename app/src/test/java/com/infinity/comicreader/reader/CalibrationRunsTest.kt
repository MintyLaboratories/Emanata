package com.infinity.comicreader.reader

import com.infinity.comicreader.reader.CalibrationRuns.End
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationRunsTest {
    private val runs = CalibrationRuns()

    @Test
    fun oneRunAtATime() {
        val id = runs.begin()
        assertTrue(id > 0)
        assertTrue(runs.running)
        assertEquals(0, runs.begin())
        assertEquals(0, runs.begin(restart = true))
        assertFalse(runs.dropped(id))
        assertEquals(End.REPORTED to false, runs.ended(id))
        assertFalse(runs.running)
    }

    @Test
    fun aCanceledRunEndsCanceled() {
        val id = runs.begin()
        assertTrue(runs.cancel())
        assertTrue(runs.dropped(id))
        assertTrue(runs.running)
        assertEquals(End.CANCELED to false, runs.ended(id))
        assertFalse(runs.cancel())
    }

    @Test
    fun aRunAskedForWhileCancelingStartsOnceTheCanceledOneEnds() {
        val id = runs.begin()
        runs.cancel()
        assertEquals(0, runs.begin(restart = true))
        assertFalse(runs.canceling)
        // Not reported as canceled: the screen that asked for the new run stays.
        assertEquals(End.DROPPED to true, runs.ended(id))
        val next = runs.begin()
        assertTrue(next > id)
        assertFalse(runs.dropped(next))
    }

    @Test
    fun aPlainStartWhileCancelingDoesNotRestart() {
        val id = runs.begin()
        runs.cancel()
        assertEquals(0, runs.begin())
        assertEquals(End.CANCELED to false, runs.ended(id))
    }

    @Test
    fun droppingReplacesTheRunningOne() {
        val id = runs.begin()
        assertEquals(0, runs.begin(drop = true))
        assertTrue(runs.dropped(id))
        assertEquals(End.DROPPED to true, runs.ended(id))
    }

    @Test
    fun cancelAlsoStopsTheRunWaitingToFollow() {
        val id = runs.begin()
        runs.begin(drop = true)
        runs.cancel()
        assertEquals(End.CANCELED to false, runs.ended(id))
    }
}
