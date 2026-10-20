package com.infinity.comicreader.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NaturalOrderTest {

    private fun sorted(vararg names: String) = names.sortedWith(NaturalOrder)

    @Test
    fun digitRunsCompareNumerically() {
        assertEquals(listOf("page2", "page10", "page100"), sorted("page100", "page10", "page2"))
    }

    @Test
    fun leadingZerosAreIgnored() {
        assertEquals(0, NaturalOrder.compare("p007", "p7"))
        assertTrue(NaturalOrder.compare("p007", "p8") < 0)
    }

    @Test
    fun lettersCompareCaseInsensitively() {
        assertEquals(0, NaturalOrder.compare("Cover.jpg", "cover.jpg"))
        assertEquals(listOf("a.jpg", "B.jpg", "c.jpg"), sorted("c.jpg", "B.jpg", "a.jpg"))
    }

    @Test
    fun prefixSortsFirst() {
        assertEquals(listOf("page", "page1"), sorted("page1", "page"))
    }

    @Test
    fun directoriesSortByPath() {
        assertEquals(
            listOf("ch1/p2.jpg", "ch1/p10.jpg", "ch2/p1.jpg"),
            sorted("ch2/p1.jpg", "ch1/p10.jpg", "ch1/p2.jpg")
        )
    }
}
