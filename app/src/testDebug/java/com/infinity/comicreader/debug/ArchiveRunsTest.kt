package com.infinity.comicreader.debug

import com.infinity.comicreader.debug.ArchiveRuns.ListingDiff
import com.infinity.comicreader.debug.ArchiveRuns.Via
import com.infinity.comicreader.reader.ArchiveEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveRunsTest {
    private fun entry(path: String, size: Long = 10, index: Int = 0) = ArchiveEntry(path, size, index)

    @Test
    fun theRandomOrderIsFixedBySeedAndDistinct() {
        val order = ArchiveRuns.randomOrder(100, 20, 1)
        assertEquals(20, order.size)
        assertEquals(20, order.toSet().size)
        assertTrue(order.all { it in 0 until 100 })
        // The same on every JVM and device: SplitMix64, not java.util.Random's shuffle.
        assertEquals(listOf(32, 42, 35, 28, 3, 86, 81, 21, 67, 16, 63, 61, 10, 99, 29, 49, 38, 19, 90, 31), order)
        assertEquals(order, ArchiveRuns.randomOrder(100, 20, 1))
        assertNotEquals(order, ArchiveRuns.randomOrder(100, 20, 2))
        assertEquals(5, ArchiveRuns.randomOrder(5, 20, 1).toSet().size)
        assertEquals(emptyList<Int>(), ArchiveRuns.randomOrder(0, 20, 1))
    }

    @Test
    fun pagesAreImagesInNaturalOrder() {
        val entries = listOf(entry("10.jpg"), entry("notes.txt"), entry("9.png"), entry("b/1.webp"), entry("a/2.JPG"))
        assertEquals(listOf("9.png", "10.jpg", "a/2.JPG", "b/1.webp"), ArchiveRuns.pages(entries).map { it.path })
        assertEquals(listOf("9.png", "10.jpg", "a/2.JPG", "b/1.webp", "notes.txt"), ArchiveRuns.pages(entries, anyEntry = true).map { it.path })
    }

    @Test
    fun listingsCompareByPathSizeAndOrder() {
        val a = listOf(entry("1.jpg", 5), entry("2.jpg", 6), entry("3.jpg", 7))
        assertEquals(ListingDiff(0, 0, 0, true), ArchiveRuns.compare(a, a))
        assertTrue(ArchiveRuns.compare(a, a).same)
        assertEquals(ListingDiff(1, 1, 0, true), ArchiveRuns.compare(a, listOf(entry("1.jpg", 5), entry("2.jpg", 6), entry("4.jpg", 7))))
        assertEquals(ListingDiff(0, 0, 1, true), ArchiveRuns.compare(a, listOf(entry("1.jpg", 5), entry("2.jpg", -1), entry("3.jpg", 7))))
        val swapped = ArchiveRuns.compare(a, listOf(entry("2.jpg", 6), entry("1.jpg", 5), entry("3.jpg", 7)))
        assertEquals(ListingDiff(0, 0, 0, false), swapped)
        assertFalse(swapped.same)
    }

    @Test
    fun viasReadByKey() {
        assertEquals(listOf(Via.FILE), Via.list(null))
        assertEquals(listOf(Via.CHANNEL), Via.list("channel"))
        assertEquals(listOf(Via.FILE, Via.CHANNEL), Via.list("both"))
        assertNull(Via.list("share"))
    }
}
