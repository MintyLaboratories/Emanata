package com.infinity.comicreader.ui

import com.infinity.comicreader.reader.Half
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
    fun onlyLoneLastPageOfSpreadIsAlone() {
        assertTrue(single.shownAlone(3, 5))
        assertTrue(single.shownAlone(4, 5))
        assertFalse(ltr.shownAlone(0, 5))
        assertFalse(ltr.shownAlone(3, 5))
        assertTrue(ltr.shownAlone(4, 5))
        assertFalse(ltr.shownAlone(9, 10))
        assertTrue(rtl.shownAlone(0, 1))
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

    @Test
    fun slotOfFollowsScreenOrder() {
        assertEquals(0, ltr.slotOf(2, 5))
        assertEquals(1, ltr.slotOf(3, 5))
        // Right to left, the first page of a pair sits on the right.
        assertEquals(1, rtl.slotOf(2, 5))
        assertEquals(0, rtl.slotOf(3, 5))
        assertEquals(0, single.slotOf(3, 5))
        assertEquals(-1, ltr.slotOf(7, 5))
    }

    // ------------------------------------------------------------------ views

    private val pairs = PageLayout.of(listOf(intArrayOf(0), intArrayOf(1, 2), intArrayOf(3), intArrayOf(4, 5)), spread = true, rtl = false)

    @Test
    fun viewsMapItemsToTheirPages() {
        assertEquals(4, pairs.itemCount(6))
        assertEquals(listOf(0, 1, 1, 2, 3, 3), (0 until 6).map { pairs.itemOf(it) })
        assertEquals(1, pairs.firstPage(1))
        assertEquals(2, pairs.lastPage(1, 6))
        assertArrayEquals(intArrayOf(0, NONE), pairs.slots(0, 6))
        assertArrayEquals(intArrayOf(4, 5), pairs.slots(3, 6))
        assertTrue(pairs.alone(2, 6))
        assertFalse(pairs.alone(3, 6))
        assertTrue(pairs.isLastItem(3, 6))
        assertFalse(pairs.isLastItem(2, 6))
        // Out of the book: before the first page, past the last.
        assertEquals(-1, pairs.firstPage(-1))
        assertEquals(6, pairs.firstPage(4))
    }

    @Test
    fun viewsInRightToLeftPutTheFirstPageOnTheRight() {
        val rtlPairs = PageLayout.of(listOf(intArrayOf(0), intArrayOf(1, 2)), spread = true, rtl = true)
        assertArrayEquals(intArrayOf(2, 1), rtlPairs.slots(1, 3))
        assertArrayEquals(intArrayOf(0, NONE), rtlPairs.slots(0, 3))
    }

    @Test
    fun halvesOfAPageTakeTwoItems() {
        val views = listOf(
            listOf(PageLayout.Part(0)),
            listOf(PageLayout.Part(1, Half.LEFT)), listOf(PageLayout.Part(1, Half.RIGHT)),
            listOf(PageLayout.Part(2))
        )
        val split = PageLayout(spread = false, views = views)
        assertTrue(split.grouped)
        assertEquals(4, split.itemCount(3))
        assertEquals(1, split.itemOf(1))
        assertEquals(2, split.itemOf(PageLayout.Part(1, Half.RIGHT)))
        assertEquals(1, split.firstPage(2))
        assertEquals(PageLayout.Part(1, Half.RIGHT), split.parts(2, 3).single())
        assertEquals(3, split.itemOf(2))
        assertTrue(split.sameAs(PageLayout(spread = false, views = views)))
        assertFalse(split.sameAs(PageLayout(spread = false)))
    }

    @Test
    fun positionalLayoutsAreNotGrouped() {
        assertFalse(single.grouped)
        assertTrue(ltr.grouped)
        assertTrue(ltr.sameAs(PageLayout(spread = true)))
    }

    @Test
    fun splitShowsASpreadsHalvesInReadingOrder() {
        val ltr = PageLayout.split(3, rtl = false) { it == 1 }
        assertEquals(4, ltr.itemCount(3))
        assertEquals(listOf(PageLayout.Part(0)), ltr.parts(0, 3).toList())
        assertEquals(listOf(PageLayout.Part(1, Half.LEFT)), ltr.parts(1, 3).toList())
        assertEquals(listOf(PageLayout.Part(1, Half.RIGHT)), ltr.parts(2, 3).toList())
        val rtl = PageLayout.split(3, rtl = true) { it == 1 }
        assertEquals(listOf(PageLayout.Part(1, Half.RIGHT)), rtl.parts(1, 3).toList())
        assertEquals(listOf(PageLayout.Part(1, Half.LEFT)), rtl.parts(2, 3).toList())
        assertEquals(listOf(PageLayout.Part(2)), rtl.parts(3, 3).toList())
    }

    @Test
    fun joinedItemsShowOnePictureAndCountForSameness() {
        val views = listOf(intArrayOf(0), intArrayOf(1, 2), intArrayOf(3))
        val joined = PageLayout.of(views, spread = true, rtl = false, joined = setOf(1))
        assertTrue(joined.joined(1))
        assertFalse(joined.joined(0))
        assertFalse(joined.sameAs(PageLayout.of(views, spread = true, rtl = false)))
        assertTrue(joined.sameAs(PageLayout.of(views, spread = true, rtl = false, joined = setOf(1))))
        // A lone page of such a layout has its row to itself.
        assertTrue(joined.alone(0, 4))
        assertFalse(joined.alone(1, 4))
    }

    private fun items(layout: PageLayout, pageCount: Int): String =
        (0 until layout.itemCount(pageCount)).joinToString("") { item ->
            val slots = layout.slots(item, pageCount).filter { it != NONE }
            slots.joinToString("|", "[", "]") + if (layout.joined(item)) "*" else ""
        }

    @Test
    fun keepingHoldsTheScreensThroughOneAndRePairsAfter() {
        val old = PageLayout.of(listOf(intArrayOf(0), intArrayOf(1, 2), intArrayOf(3, 4), intArrayOf(5, 6), intArrayOf(7, 8)), spread = true, rtl = false)
        val next = PageLayout.of(
            listOf(intArrayOf(0), intArrayOf(1), intArrayOf(2, 3), intArrayOf(4, 5), intArrayOf(6, 7), intArrayOf(8)),
            spread = true, rtl = false, joined = setOf(1, 4)
        )
        // Through [3|4]: page 5 alone, then the new pairs; joins follow the new layout after.
        val kept = old.keeping(2, next, 9)
        assertEquals("[0][1|2][3|4][5][6|7]*[8]", items(kept, 9))
        assertTrue(kept.sameItems(old.keeping(2, next, 9), 9))
        assertFalse(kept.sameItems(old, 9))
        // Kept through the last screen, nothing changes.
        assertTrue(old.keeping(4, next, 9).sameItems(old, 9))
        // Kept through the first, the rest is the new layout.
        assertTrue(old.keeping(0, next, 9).sameItems(next, 9))
    }

    @Test
    fun firstPairingFindsAScreenThatPairsAPageNeedingItsOwn() {
        val old = PageLayout.of(listOf(intArrayOf(0), intArrayOf(1, 2), intArrayOf(3, 4), intArrayOf(5, 6)), spread = true, rtl = false)
        assertEquals(-1, old.firstPairing(3, 7) { false })
        assertEquals(2, old.firstPairing(3, 7) { it == 4 || it == 6 })
        // Only screens through the one asked for count; a lone page pairs nothing.
        assertEquals(-1, old.firstPairing(1, 7) { it == 4 })
        assertEquals(-1, old.firstPairing(3, 7) { it == 0 })
        // Kept through the screen before it, the new layout takes over from there.
        val next = PageLayout.of(listOf(intArrayOf(0), intArrayOf(1, 2), intArrayOf(3), intArrayOf(4), intArrayOf(5, 6)), spread = true, rtl = false)
        assertEquals("[0][1|2][3][4][5|6]", items(old.keeping(1, next, 7), 7))
    }

    @Test
    fun keepingWorksFromPlainLayouts() {
        val merged = PageLayout.of(listOf(intArrayOf(0), intArrayOf(1), intArrayOf(2, 3), intArrayOf(4)), spread = true, rtl = false, joined = setOf(2))
        // One page a screen: the screen on page 2 stays alone; 3 cannot join it.
        val kept = single.keeping(2, merged, 5)
        assertEquals("[0][1][2][3][4]", items(kept, 5))
        assertEquals("[0][1][2|3]*[4]", items(single.keeping(1, merged, 5), 5))
    }
}
