package minty.emanata.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FactsWorkTest {

    @Test
    fun coversTheSameOrLessWorkOnTheSameListing() {
        val both = FactsWork(3, books = true, sizes = true)
        assertTrue(both.covers(both))
        assertTrue(both.covers(FactsWork(3, books = true, sizes = false)))
        assertTrue(FactsWork(3, books = true, sizes = false).covers(FactsWork(3, books = true, sizes = false)))
        assertFalse(FactsWork(3, books = true, sizes = false).covers(both))
        assertFalse(FactsWork(3, books = false, sizes = true).covers(FactsWork(3, books = true, sizes = false)))
        // A new listing always needs its own.
        assertFalse(both.covers(FactsWork(4, books = true, sizes = false)))
    }
}
