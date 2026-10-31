package com.infinity.comicreader.reader

import android.graphics.Bitmap
import com.infinity.comicreader.reader.SpreadSeams.Seam
import com.infinity.comicreader.reader.SpreadSeams.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.BitSet

class PageSeamsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val names = listOf("01.jpg", "02.jpg", "03.jpg", null)

    @Test
    fun seamsComeBackForTheSameBookAndPages() {
        val store = PageSeams(tmp.newFolder("seams"))
        assertNull(store.read("book|1|2", names))
        val found = PageSeams.Found(names.size)
        found.set(0, Verdict(Seam.MATCH, 0.8), Verdict(Seam.NO_MATCH, 1.0))
        found.set(2, Verdict.UNKNOWN, Verdict.UNKNOWN)
        store.write("book|1|2", names, found)
        val read = store.read("book|1|2", names)!!
        assertEquals(Seam.MATCH, read.of(rightToLeft = false)[0]!!.seam)
        assertEquals(0.8, read.of(rightToLeft = false)[0]!!.confidence, 1.0 / 255)
        assertEquals(Seam.NO_MATCH, read.of(rightToLeft = true)[0]!!.seam)
        assertNull(read.of(rightToLeft = false)[1])
        assertFalse(read.checked(1))
        assertEquals(Seam.UNKNOWN, read.of(rightToLeft = true)[2]!!.seam)
        assertTrue(read.anyMatch)
        assertNull(store.read("book|1|3", names))
        assertNull(store.read("book|1|2", names.dropLast(1)))
    }

    @Test
    fun pagesSeenComeBackBesideTheSeams() {
        val dir = tmp.newFolder("seams")
        val store = PageSeams(dir)
        assertTrue(store.readSeen("book|1|2", names).isEmpty)
        val found = PageSeams.Found(names.size)
        found.set(1, Verdict(Seam.MATCH, 1.0), Verdict(Seam.NO_MATCH, 1.0))
        store.write("book|1|2", names, found)
        store.writeSeen("book|1|2", names, BitSet().apply { set(0); set(3); set(9) })
        // Pages past the book are left out; the seams stay as they were, in a file of their own.
        assertEquals(BitSet().apply { set(0); set(3) }, store.readSeen("book|1|2", names))
        assertEquals(Seam.MATCH, store.read("book|1|2", names)!!.of(rightToLeft = false)[1]!!.seam)
        assertEquals(setOf("bin", "seen"), dir.list()!!.map { it.substringAfterLast('.') }.toSet())
        assertEquals(1, dir.list()!!.map { it.substringBeforeLast('.') }.distinct().size)
        // Another book, or the same one with other pages, has none.
        assertTrue(store.readSeen("book|1|3", names).isEmpty)
        assertTrue(store.readSeen("book|1|2", names.dropLast(1)).isEmpty)
    }

    @Test
    fun aBooksSeamsAndPagesSeenAreForgottenByItsIdAndTrimmedOldestFirst() {
        val dir = tmp.newFolder("seams")
        val store = PageSeams(dir)
        val found = PageSeams.Found(names.size).apply { set(0, Verdict(Seam.MATCH, 1.0), Verdict(Seam.NO_MATCH, 1.0)) }
        store.write("id:00000000000000aa", names, found)
        store.writeSeen("id:00000000000000aa", names, BitSet().apply { set(1) })
        store.write("id:00000000000000aa\u0000shown:0,2", names.take(2), PageSeams.Found(2))
        store.write("id:00000000000000bb", names, found)
        // A book's own files, those of its pages shown too, start with its ID.
        assertEquals(3, dir.list()!!.count { it.startsWith("00000000000000aa-") })
        dir.listFiles()!!.forEach { it.setLastModified(if (it.name.startsWith("00000000000000bb-")) 1_000_000 else 2_000_000) }
        PageSeams.trim(dir, maxBytes = 3 * 4096)
        assertNull(store.read("id:00000000000000bb", names))
        PageSeams.forget(dir, com.infinity.comicreader.data.BookId(0xaa))
        assertTrue(dir.list()!!.isEmpty())
    }

    private fun analysis(count: Int, wide: Set<Int> = emptySet()): Spreads.Analysis =
        Spreads.classify(List(count) { "page%02d.jpg".format(it + 1) }, List(count) { if (it in wide) intArrayOf(2600, 2000) else intArrayOf(1300, 2000) })

    @Test
    fun uncheckedSeamsGoOutwardFromThePageShownAndSkipWidePages() {
        val found = PageSeams.Found(8)
        assertEquals(listOf(3, 4, 2, 5, 1, 6, 0), PageSeams.unchecked(analysis(8), found, from = 3))
        // Page 5 is wide: neither of its seams counts.
        assertEquals(listOf(3, 2, 1, 6, 0), PageSeams.unchecked(analysis(8, wide = setOf(5)), found, from = 3))
        found.set(3, Verdict.UNKNOWN, Verdict.UNKNOWN)
        assertEquals(listOf(4, 2, 5), PageSeams.unchecked(analysis(8), found, from = 3, window = 2).filter { it in 2..5 })
        assertEquals(listOf(4, 2, 5, 1), PageSeams.unchecked(analysis(8), found, from = 3, window = 2))
        assertEquals(emptyList<Int>(), PageSeams.unchecked(analysis(1), PageSeams.Found(1), from = 0))
    }

    @Test
    fun beyondTheWindowOnlyCheapPagesAreChecked() {
        val found = PageSeams.Found(12)
        // Pages 8 and 9 are costly: the seams on either side of them wait for the reader.
        val cheap = { i: Int -> i != 8 && i != 9 }
        assertEquals(setOf(0, 1, 2, 3, 4, 5, 6, 10), PageSeams.unchecked(analysis(12), found, from = 1, window = 2, cheap = cheap).toSet())
        assertEquals(setOf(6, 7, 8, 9, 10), PageSeams.unchecked(analysis(12), found, from = 8, window = 2, cheap = { false }).toSet())
    }

    @Test
    fun onlyPagesNamedAsJpegHaveCheapSides() {
        assertTrue(Decoders.cheapSidesNamed("a/p01.JPG"))
        assertTrue(Decoders.cheapSidesNamed("p01.jpeg"))
        assertFalse(Decoders.cheapSidesNamed("p01.webp"))
        assertFalse(Decoders.cheapSidesNamed("p01.png"))
        assertFalse(Decoders.cheapSidesNamed("p01.avif"))
        assertFalse(Decoders.cheapSidesNamed("p01.jxl"))
        assertFalse(Decoders.cheapSidesNamed(null))
    }

    /** Pages whose sides are given, and a count of the sides read of each page. */
    private class Sided(private val sides: List<SpreadSeams.Sides?>) : PageSource {
        val reads = IntArray(sides.size)
        override val pageCount: Int get() = sides.size
        override fun loadPage(index: Int, target: Target): Bitmap = throw UnsupportedOperationException()
        override fun sides(index: Int): SpreadSeams.Sides? = sides[index].also { reads[index]++ }
        override fun close() {}
    }

    @Test
    fun checkingReadsEachPageOnceAndFindsTheSpread() {
        val w = 400
        val h = 600
        fun halves(seed: Long) = SyntheticArt.art(w, h, seed).let { art ->
            listOf(SyntheticArt.columns(art, w, 0, w / 2), SyntheticArt.columns(art, w, w / 2, w)).map { SpreadSeams.sides(it, w / 2, h) }
        }
        // Pages 1 and 2 are the halves of one picture; the others unrelated, the last unreadable.
        val pages = listOf(halves(1)[0], halves(2)[0], halves(2)[1], halves(3)[1], null)
        val source = Sided(pages)
        val found = PageSeams.Found(pages.size)
        assertTrue(PageSeams.check(source, listOf(1, 2, 0, 3), found))
        assertEquals(listOf(1, 1, 1, 1, 1), source.reads.toList())
        val ltr = found.of(rightToLeft = false)
        assertEquals(listOf(Seam.NO_MATCH, Seam.MATCH, Seam.NO_MATCH, Seam.UNKNOWN), ltr.map { it!!.seam })
        assertFalse(found.of(rightToLeft = true)[1]!!.match)
    }

    @Test
    fun enoughEndsTheCheckBeforeTheNextRead() {
        val source = Sided(listOf(null, null, null, null, null))
        val found = PageSeams.Found(5)
        // Enough once page 1 was read: its seam with page 2 waits, as do the rest.
        assertTrue(PageSeams.check(source, listOf(0, 1, 2, 3), found, enough = { source.reads[1] > 0 }))
        assertEquals(listOf(1, 1, 0, 0, 0), source.reads.toList())
        assertTrue(found.checked(0))
        assertFalse(found.checked(1))
        assertFalse(found.checked(2))
    }

    @Test
    fun checkingStopsWhenCanceled() {
        val found = PageSeams.Found(3)
        assertFalse(PageSeams.check(Sided(listOf(null, null, null)), listOf(0, 1), found) { true })
        assertFalse(found.checked(0))
    }
}
