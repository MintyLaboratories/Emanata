package com.infinity.comicreader.ui

import com.infinity.comicreader.ui.PageLayout.Companion.NONE
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageLayoutTest {

    private val single = PageLayout(spread = false)
    private val ltr = PageLayout(spread = true)
    private val rtl = PageLayout(spread = true, rtl = true)

    /** Every page shown by some item, in reading order. */
    private fun shown(layout: PageLayout, pageCount: Int, rtl: Boolean = false): List<Int> =
        (0 until layout.itemCount(pageCount)).flatMap { item ->
            val slots = layout.slots(item, pageCount).toList()
            (if (rtl) slots.reversed() else slots).filter { it != NONE }
        }

    @Test
    fun singlePageShowsEveryPage() {
        assertEquals(5, single.itemCount(5))
        assertEquals((0 until 5).toList(), shown(single, 5))
        assertArrayEquals(intArrayOf(NONE), single.slots(5, 5))
    }

    @Test
    fun spreadItemCountRoundsUp() {
        assertEquals(0, ltr.itemCount(0))
        assertEquals(1, ltr.itemCount(1))
        assertEquals(2, ltr.itemCount(4))
        assertEquals(3, ltr.itemCount(5))
    }

    @Test
    fun spreadShowsEveryPageOfEvenBook() {
        assertEquals((0 until 24).toList(), shown(ltr, 24))
    }

    @Test
    fun spreadShowsEveryPageOfOddBook() {
        assertEquals((0 until 25).toList(), shown(ltr, 25))
    }

    @Test
    fun spreadShowsPagesFromSecondHalf() {
        // Pages from ceil(N/2) on used to be checked against the item count and drop out.
        assertArrayEquals(intArrayOf(6, 7), ltr.slots(3, 10))
        assertArrayEquals(intArrayOf(8, 9), ltr.slots(4, 10))
    }

    @Test
    fun oddLastPageStandsAlone() {
        assertArrayEquals(intArrayOf(4, NONE), ltr.slots(2, 5))
        assertArrayEquals(intArrayOf(NONE, 4), rtl.slots(2, 5))
        assertArrayEquals(intArrayOf(0, NONE), ltr.slots(0, 1))
    }

    @Test
    fun rtlPutsFirstPageOnTheRight() {
        assertArrayEquals(intArrayOf(1, 0), rtl.slots(0, 4))
        assertArrayEquals(intArrayOf(3, 2), rtl.slots(1, 4))
        assertEquals((0 until 7).toList(), shown(rtl, 7, rtl = true))
    }

    @Test
    fun itemsPastTheEndAreEmpty() {
        assertArrayEquals(intArrayOf(NONE, NONE), ltr.slots(3, 5))
        assertArrayEquals(intArrayOf(NONE, NONE), ltr.slots(0, 0))
    }

    @Test
    fun pageAndItemRoundTrip() {
        for (page in 0 until 9) {
            val item = ltr.itemOf(page)
            assertTrue(page in ltr.firstPage(item)..ltr.lastPage(item, 9))
        }
        assertEquals(8, ltr.lastPage(4, 9))
        assertEquals(7, ltr.lastPage(3, 9))
        assertEquals(4, single.firstPage(4))
        assertEquals(4, single.lastPage(4, 9))
    }

    @Test
    fun stepMovesOneSpreadAtATime() {
        assertEquals(2, ltr.step(0, 1))
        assertEquals(2, ltr.step(1, 1))
        assertEquals(0, ltr.step(3, -1))
        assertEquals(-2, ltr.step(1, -1))
        // Past the last spread of an even and an odd book.
        assertEquals(10, ltr.step(8, 1))
        assertEquals(10, ltr.step(9, 1))
        assertEquals(1, single.step(0, 1))
        assertEquals(-1, single.step(0, -1))
    }

    @Test
    fun stepVisitsEverySpreadOnce() {
        for (pageCount in listOf(9, 10)) {
            val visited = generateSequence(0) { ltr.step(it, 1) }.takeWhile { it < pageCount }.toList()
            assertEquals((0 until ltr.itemCount(pageCount)).map { it * 2 }, visited)
        }
    }

    @Test
    fun lastPageNeverPassesTheEnd() {
        assertEquals(8, single.lastPage(12, 9))
        assertEquals(8, ltr.lastPage(6, 9))
    }

    @Test
    fun lastItemOfEvenSpreadStartsBeforeLastPage() {
        // A spread of 4 pages ends on item (2, 3); the reader sits on its first page.
        assertTrue(ltr.onLastItem(2, 4))
        assertFalse(ltr.onLastItem(1, 4))
        assertTrue(ltr.onLastItem(4, 5))
        assertFalse(ltr.onLastItem(3, 5))
        assertTrue(single.onLastItem(3, 4))
        assertFalse(single.onLastItem(2, 4))
        assertTrue(ltr.onLastItem(0, 0))
    }
}
