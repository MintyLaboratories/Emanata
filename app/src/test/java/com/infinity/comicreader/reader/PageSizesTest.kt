package com.infinity.comicreader.reader

import com.infinity.comicreader.data.BookId
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

    @Test
    fun readingMarksABookUsedSoTrimmingDropsOthersFirst() {
        val dir = tmp.newFolder("sizes")
        val store = PageSizes(dir)
        val sizes = arrayOf<IntArray?>(intArrayOf(1, 1), intArrayOf(1, 1), intArrayOf(1, 1))
        store.write("id:00000000000000aa", names, sizes)
        store.write("id:00000000000000bb", names, sizes)
        val (a, b) = listOf("00000000000000aa", "00000000000000bb").map { p -> dir.listFiles()!!.single { it.name.startsWith("$p-") } }
        a.setLastModified(1_000_000)
        b.setLastModified(2_000_000)
        // Read, the older one becomes the newer.
        store.read("id:00000000000000aa", names)
        assertTrue(a.lastModified() > b.lastModified())
        PageSizes.trim(dir, maxBytes = 4096)
        assertTrue(a.isFile)
        assertFalse(b.isFile)
    }

    @Test
    fun aBooksSizesAreForgottenByItsId() {
        val dir = tmp.newFolder("sizes")
        val store = PageSizes(dir)
        val sizes = arrayOf<IntArray?>(intArrayOf(1, 1), intArrayOf(1, 1), intArrayOf(1, 1))
        store.write("id:00000000000000aa", names, sizes)
        store.write("id:00000000000000aa", listOf("x.jpg", "y.jpg", null), sizes)
        store.write("id:00000000000000bb", names, sizes)
        PageSizes.forget(dir, BookId(0xaa))
        assertNull(store.read("id:00000000000000aa", names))
        assertEquals(1, dir.listFiles()!!.size)
        assertTrue(store.read("id:00000000000000bb", names) != null)
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

    @Test
    fun aScanStartsAtTheOpenPageAndWorksOutward() {
        assertEquals(listOf(3, 4, 2, 5, 1, 0), PageSizes.outward(6, 3))
        assertEquals(listOf(0, 1, 2), PageSizes.outward(3, 0))
        assertEquals(listOf(2, 1, 0), PageSizes.outward(3, 7))
        assertEquals(emptyList<Int>(), PageSizes.outward(0, 0))
        val source = Sized(5)
        val sizes = arrayOfNulls<IntArray>(5)
        var left = 2
        assertFalse(PageSizes.scan(source, sizes, from = 3) { left-- <= 0 })
        assertArrayEquals(intArrayOf(4, 10), sizes[3])
        assertArrayEquals(intArrayOf(5, 10), sizes[4])
        assertNull(sizes[0])
    }
}
