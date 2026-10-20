package com.infinity.comicreader.ui

import com.infinity.comicreader.ui.PageLayout.Companion.NONE
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PageStripTest {

    private fun single(pages: Int) = PageStrip("a", pages, PageLayout(spread = false))
    private fun spread(pages: Int, rtl: Boolean = false) =
        PageStrip("a", pages, PageLayout(spread = true, rtl = rtl))

    /** Every page some item shows, in item order, empty slots left out. */
    private fun shown(strip: PageStrip<String>): List<Int> =
        (0 until strip.itemCount).flatMap { strip.slots(it).filter { p -> p != NONE } }

    @Test
    fun spreadShowsEveryPageOfOddAndEvenBooks() {
        for (pages in listOf(1, 5, 9, 10)) {
            val strip = spread(pages)
            assertEquals((pages + 1) / 2, strip.itemCount)
            assertEquals((0 until pages).toList(), shown(strip))
        }
    }

    @Test
    fun spreadLastItemOfOddBookShowsOnePage() {
        assertArrayEquals(intArrayOf(4, NONE), spread(5).slots(2))
        assertArrayEquals(intArrayOf(NONE, 4), spread(5, rtl = true).slots(2))
        assertEquals(4, spread(5).lastPage(2))
        assertEquals(9, spread(10).lastPage(4))
    }

    @Test
    fun spreadRefusesAppend() {
        val strip = spread(10)
        assertNull(strip.append("b", 10))
        assertEquals(5, strip.itemCount)
        assertEquals(10, strip.end)
    }

    @Test
    fun spreadRefusesTrims() {
        val strip = spread(10)
        assertNull(strip.dropTo(3))
        assertNull(strip.dropAfter(3))
        assertEquals(0, strip.base)
        assertEquals(5, strip.itemCount)
        assertArrayEquals(intArrayOf(6, 7), strip.slots(3))
    }

    @Test
    fun appendInsertsBehindLastChapter() {
        val strip = single(10)
        assertEquals(10 until 16, strip.append("b", 6))
        assertEquals(16, strip.itemCount)
        assertEquals("b", strip.chapterOf(10).value)
        assertEquals(10, strip.chapterOf(15).first)
        assertEquals("a", strip.chapterOf(9).value)
        assertNull(strip.append("c", 0))
    }

    @Test
    fun dropToKeepsItemCountConsistent() {
        val strip = single(10)
        strip.append("b", 10)
        val removal = strip.dropTo(10)!!
        assertEquals(0, removal.position)
        assertEquals(10, removal.count)
        assertEquals(listOf("a"), removal.chapters)
        // 20 items minus the 10 removed.
        assertEquals(10, strip.itemCount)
        assertEquals(10, strip.base)
        assertArrayEquals(intArrayOf(10), strip.slots(0))
        assertEquals(10 until 15, strip.append("c", 5))
        assertEquals(15, strip.itemCount)
        assertArrayEquals(intArrayOf(19), strip.slots(9))
        assertArrayEquals(intArrayOf(24), strip.slots(14))
    }

    @Test
    fun twoRollOversTrimEachFinishedChapter() {
        val strip = single(10)
        strip.append("b", 8)
        assertEquals(listOf("a"), strip.dropTo(10)!!.chapters)
        strip.append("c", 6)
        // The second trim is by item position: chapter "b" is items 0 until 8.
        val removal = strip.dropTo(8)!!
        assertEquals(8, removal.count)
        assertEquals(listOf("b"), removal.chapters)
        assertEquals(18, strip.base)
        assertEquals(6, strip.itemCount)
        assertEquals(listOf("c"), strip.chapters.map { it.value })
        assertArrayEquals(intArrayOf(18), strip.slots(0))
    }

    @Test
    fun dropToWithinChapterKeepsIt() {
        val strip = single(10)
        val removal = strip.dropTo(4)!!
        assertEquals(4, removal.count)
        assertEquals(emptyList<String>(), removal.chapters)
        assertEquals(6, strip.itemCount)
        assertNull(strip.dropTo(0))
    }

    @Test
    fun dropAfterUndoesAppendAfterRollOver() {
        val strip = single(10)
        strip.append("b", 8)
        strip.dropTo(10)
        strip.append("c", 6)
        val removal = strip.dropAfter(8)!!
        assertEquals(8, removal.position)
        assertEquals(6, removal.count)
        assertEquals(listOf("c"), removal.chapters)
        assertEquals(8, strip.itemCount)
        assertEquals(18, strip.end)
        assertNull(strip.dropAfter(8))
    }

    @Test
    fun clearReturnsEveryChapter() {
        val strip = single(10)
        strip.append("b", 8)
        strip.dropTo(10)
        strip.append("c", 6)
        assertEquals(listOf("b", "c"), strip.clear())
        assertEquals(0, strip.itemCount)
        assertEquals(0, strip.base)
    }
}
