package com.infinity.comicreader.reader

import android.graphics.Bitmap
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HiddenPagesTest {

    /** Page names and sizes stand in for pages: they need no Android bitmap. */
    private class FakeSource(override val pageCount: Int) : PageSource {
        var closed = false
        override fun loadPage(index: Int, target: Target): Bitmap = throw UnsupportedOperationException()
        override fun pageSize(index: Int): IntArray = intArrayOf(index, 1)
        override fun pageName(index: Int): String = "p%02d.png".format(index)
        override fun close() {
            closed = true
        }
    }

    @Test
    fun shownAndBookIndicesTurnIntoEachOther() {
        val hidden = setOf(1, 4, 5)
        // Book 0 2 3 6 7 shown as 0 1 2 3 4.
        assertEquals(listOf(0, 2, 3, 6, 7), (0..4).map { HiddenPages.toBook(it, hidden) })
        assertEquals(listOf(0, 1, 2, 3, 4), listOf(0, 2, 3, 6, 7).map { HiddenPages.toShown(it, hidden) })
        // A hidden page reads as the next page shown.
        assertEquals(1, HiddenPages.toShown(1, hidden))
        assertEquals(3, HiddenPages.toShown(4, hidden))
        assertEquals(5, HiddenPages.toBook(5, emptySet()))
    }

    @Test
    fun remapKeepsTheSameBookPage() {
        // Shown page 3 is book page 4 once page 1 hides; hiding page 2 as well makes it shown page 2.
        assertEquals(2, HiddenPages.remap(3, setOf(1), setOf(1, 2)))
        // Showing everything again: back to its book index.
        assertEquals(4, HiddenPages.remap(2, setOf(1, 2), emptySet()))
    }

    @Test
    fun aSourceShowsOnlyThePagesNotHidden() {
        val inner = FakeSource(5)
        val src = HiddenPages.wrap(inner, setOf(0, 3))
        assertEquals(3, src.pageCount)
        assertEquals(listOf("p01.png", "p02.png", "p04.png"), (0 until 3).map { src.pageName(it) })
        assertArrayEquals(intArrayOf(4, 1), src.pageSize(2))
        assertNull(src.pageName(3))
        assertEquals(4, HiddenPages.bookIndex(src, 2))
        // Book pages 1 2 4 shown as 0 1 2; a hidden one reads as the next page shown.
        assertEquals(listOf(0, 0, 1, 2, 2), (0 until 5).map { HiddenPages.shownIndex(src, it) })
        assertEquals(5, HiddenPages.bookCount(src))
        src.close()
        assertTrue(inner.closed)
    }

    @Test
    fun nothingToHideLeavesTheSourceAsItIs() {
        val inner = FakeSource(3)
        assertSame(inner, HiddenPages.wrap(inner, emptySet()))
        // Pages past the end of a changed book are ignored.
        assertSame(inner, HiddenPages.wrap(inner, setOf(7)))
        // Hiding every page would leave none: all of them show.
        assertSame(inner, HiddenPages.wrap(inner, setOf(0, 1, 2)))
        assertEquals(2, HiddenPages.bookIndex(inner, 2))
        assertEquals(2, HiddenPages.shownIndex(inner, 2))
    }

    @Test
    fun storedValuesAreKeptApartByWhatIsShown() {
        val inner = FakeSource(4)
        assertEquals("book", HiddenPages.pagesId("book", inner))
        val a = HiddenPages.pagesId("book", HiddenPages.wrap(inner, setOf(1)))
        val b = HiddenPages.pagesId("book", HiddenPages.wrap(inner, setOf(2)))
        assertTrue(a != "book" && a != b)
    }
}
