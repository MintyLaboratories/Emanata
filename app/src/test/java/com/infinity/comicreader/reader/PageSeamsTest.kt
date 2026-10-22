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
    fun pagesNamedAsStripFormatsAreCheap() {
        assertTrue(Decoders.regionNamed("a/p01.JPG"))
        assertTrue(Decoders.regionNamed("p01.webp"))
        assertTrue(Decoders.regionNamed("p01.png"))
        assertFalse(Decoders.regionNamed("p01.avif"))
        assertFalse(Decoders.regionNamed("p01.jxl"))
        assertFalse(Decoders.regionNamed(null))
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
    fun checkingStopsWhenCanceled() {
        val found = PageSeams.Found(3)
        assertFalse(PageSeams.check(Sided(listOf(null, null, null)), listOf(0, 1), found) { true })
        assertFalse(found.checked(0))
    }
}
