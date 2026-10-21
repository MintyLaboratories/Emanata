package com.infinity.comicreader.reader

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PageSizesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val names = listOf("01.jpg", "02.jpg", null)

    @Test
    fun sizesComeBackForTheSameBookAndPages() {
        val store = PageSizes(tmp.newFolder("sizes"))
        assertNull(store.read("book|1|2", names))
        store.write("book|1|2", names, arrayOf(intArrayOf(1300, 2000), null, intArrayOf(2600, 2000)))
        val read = store.read("book|1|2", names)!!
        assertArrayEquals(intArrayOf(1300, 2000), read[0])
        assertNull(read[1])
        assertArrayEquals(intArrayOf(2600, 2000), read[2])
    }

    @Test
    fun anotherBookOrOtherPagesReadNothing() {
        val store = PageSizes(tmp.newFolder("sizes"))
        store.write("book|1|2", names, arrayOf(intArrayOf(1, 1), intArrayOf(1, 1), intArrayOf(1, 1)))
        assertNull(store.read("book|1|3", names))
        assertNull(store.read("book|1|2", listOf("01.jpg", "03.jpg", null)))
    }

    /** Pages whose size is their index, and a count of the sizes asked for. */
    private class Sized(override val pageCount: Int) : PageSource {
        var asked = 0
        override fun loadPage(index: Int, target: Target) = throw UnsupportedOperationException()
        override fun pageSize(index: Int): IntArray {
            asked++
            return intArrayOf(index + 1, 10)
        }
        override fun close() = Unit
    }

    @Test
    fun aScanFillsOnlyWhatIsMissingUntilCanceled() {
        val source = Sized(4)
        val sizes = arrayOf<IntArray?>(intArrayOf(9, 9), null, null, null)
        assertTrue(PageSizes.scan(source, sizes))
        assertEquals(3, source.asked)
        assertArrayEquals(intArrayOf(9, 9), sizes[0])
        assertArrayEquals(intArrayOf(4, 10), sizes[3])
        val more = arrayOfNulls<IntArray>(4)
        var left = 2
        assertFalse(PageSizes.scan(source, more) { left-- <= 0 })
        assertNull(more[3])
    }
}
