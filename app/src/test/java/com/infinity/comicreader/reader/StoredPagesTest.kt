package com.infinity.comicreader.reader

import android.graphics.Bitmap
import com.infinity.comicreader.reader.SpreadSeams.Seam
import com.infinity.comicreader.reader.SpreadSeams.Verdict
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.BitSet

class StoredPagesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeSource(override val pageCount: Int) : PageSource {
        override fun loadPage(index: Int, target: Target): Bitmap = throw UnsupportedOperationException()
        override fun pageSize(index: Int): IntArray = intArrayOf(index + 1, 10)
        override fun pageName(index: Int): String = "p%02d.png".format(index)
        override fun close() = Unit
    }

    private val book = FakeSource(5)
    private val sizes by lazy { PageSizes(tmp.newFolder("sizes")) }
    private val seams by lazy { PageSeams(tmp.newFolder("seams")) }

    private fun stored(hidden: Set<Int>) = StoredPages(sizes, seams, "book|1", HiddenPages.wrap(book, hidden))

    private fun size(i: Int) = intArrayOf(i + 1, 10)

    @Test
    fun hidingPagesKeepsTheSizesOfTheBook() {
        stored(emptySet()).writeSizes(Array(5) { size(it) })
        // Hiding pages 1 and 3 reads the sizes kept for the whole book: none anew.
        val shown = stored(setOf(1, 3)).readSizes()
        assertArrayEquals(arrayOf(size(0), size(2), size(4)), shown)
        assertEquals(1, sizes.dir.list()!!.size)
    }

    @Test
    fun sizesFoundWhileHidingAreKeptForTheBook() {
        stored(setOf(1)).writeSizes(arrayOf(size(0), null, size(3), size(4)))
        val all = stored(emptySet()).readSizes()
        assertArrayEquals(size(0), all[0])
        assertNull(all[1])
        assertNull(all[2])
        assertArrayEquals(size(3), all[3])
        assertEquals(1, sizes.dir.list()!!.size)
        assertEquals(listOf("p00.png", "p02.png", "p03.png", "p04.png"), stored(setOf(1)).names)
    }

    @Test
    fun seamsAcrossAHiddenPageAreKeptApart() {
        val match = Verdict(Seam.MATCH, 1.0)
        val none = Verdict(Seam.NO_MATCH, 1.0)
        val all = PageSeams.Found(5)
        for (i in 0 until 4) all.set(i, if (i == 1) match else none, none)
        stored(emptySet()).writeSeams(all)

        // Pages 0 1 3 4 shown: the seams 0|1 and 3|4 are the book's, 1|3 is not checked yet.
        val hiding = stored(setOf(2))
        val shown = hiding.readSeams()
        assertEquals(listOf(none, null, none), shown.of(rightToLeft = false))
        // Writing only seams of neighbors stores nothing for the pages shown alone.
        hiding.writeSeams(shown)
        assertEquals(1, seams.dir.list()!!.size)

        shown.set(1, match, none)
        hiding.writeSeams(shown)
        assertEquals(2, seams.dir.list()!!.size)
        assertEquals(listOf(none, match, none), stored(setOf(2)).readSeams().of(rightToLeft = false))
        // The book's own seams are left as they were.
        val whole = stored(emptySet()).readSeams()
        assertEquals(listOf(none, match, none, none), whole.of(rightToLeft = false))
        assertTrue(whole.checked(2))
        assertFalse(stored(setOf(1, 2)).readSeams().checked(0))
    }

    @Test
    fun pagesSeenAreKeptForTheBookByItsOwnPages() {
        // Pages 1 and 3 hidden: shown 0 1 2 are the book's 0 2 4.
        stored(setOf(1, 3)).writeSeen(BitSet().apply { set(1); set(2) })
        assertEquals(BitSet().apply { set(2); set(4) }, stored(emptySet()).readSeen())
        // Page 2 hidden instead: the book's 4 is shown 3.
        assertEquals(BitSet().apply { set(3) }, stored(setOf(2)).readSeen())
        // Added to, never replaced; nothing new writes nothing.
        stored(emptySet()).writeSeen(BitSet().apply { set(0) })
        assertEquals(BitSet().apply { set(0); set(2); set(4) }, stored(emptySet()).readSeen())
        val file = seams.dir.listFiles()!!.single { it.name.endsWith(".seen") }
        file.setLastModified(1_000)
        stored(setOf(1, 3)).writeSeen(BitSet().apply { set(0) })
        assertEquals(1_000, file.lastModified())
    }
}
