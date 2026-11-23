package com.infinity.comicreader.ai

import com.infinity.comicreader.reader.ComicInfo
import com.infinity.comicreader.reader.Plan
import com.infinity.comicreader.reader.Spreads
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A book's page class from its direction and median page shape, and the size its pages are read at ([ReadSize]). */
class ReadSizeTest {

    /** A book of [story] pages of [w] x [h] after a cover of [coverW] x [coverH], with [spreads] two-page spreads in one file among them. */
    private fun book(w: Int, h: Int, story: Int = 20, coverW: Int = 1000, coverH: Int = 1000, spreads: Int = 0): Spreads.Analysis {
        val names = ArrayList<String>()
        val sizes = ArrayList<IntArray?>()
        names += "Book 000.jpg"
        sizes += intArrayOf(coverW, coverH)
        for (i in 1..story) {
            names += "Book %03d.jpg".format(i)
            sizes += if (i % 5 == 0 && i / 5 <= spreads) intArrayOf(2 * w, h) else intArrayOf(w, h)
        }
        return Spreads.classify(names, sizes)
    }

    @Test
    fun theDirectionDecidesFirst() {
        assertEquals(PageClass.MANGA, PageClass.of(manga = true, topToBottom = false, aspect = 0.741))
        assertEquals(PageClass.MANGA, PageClass.of(manga = true, topToBottom = false, aspect = 0.3))
        assertEquals(PageClass.WEB, PageClass.of(manga = false, topToBottom = true, aspect = 0.65))
    }

    @Test
    fun aBookIsMangaReadRightToLeftOrMarkedManga() {
        assertTrue(PageClass.manga(rightToLeft = true, marked = null))
        assertTrue("left to right, Manga=Yes", PageClass.manga(rightToLeft = false, marked = ComicInfo.Manga.YES))
        assertTrue("Manga=YesAndRightToLeft read left to right", PageClass.manga(rightToLeft = false, marked = ComicInfo.Manga.YES_RTL))
        assertFalse(PageClass.manga(rightToLeft = false, marked = null))
        assertFalse(PageClass.manga(rightToLeft = false, marked = ComicInfo.Manga.NO))
        assertFalse(PageClass.manga(rightToLeft = false, marked = ComicInfo.Manga.UNKNOWN))
        val us = book(1988, 3056)
        assertEquals(PageClass.MANGA, BookShape.of(PageClass.manga(false, ComicInfo.Manga.YES), false, us).pageClass)
    }

    @Test
    fun aMedianAspectOutsideTheComicRangeReadsAsWeb() {
        fun of(a: Double) = PageClass.of(manga = false, topToBottom = false, aspect = a)
        assertEquals(PageClass.WEB, of(0.59))
        assertEquals(PageClass.US, of(0.60))
        assertEquals(PageClass.US, of(0.78))
        assertEquals(PageClass.WEB, of(0.781))
        assertEquals(PageClass.WEB, of(1.6))
    }

    @Test
    fun francoBelgianOnlyWithinItsNarrowRangeElseUs() {
        fun of(a: Double) = PageClass.of(manga = false, topToBottom = false, aspect = a)
        assertEquals(PageClass.US, of(0.729))
        assertEquals(PageClass.FRANCO_BELGIAN, of(0.73))
        assertEquals(PageClass.FRANCO_BELGIAN, of(0.741))
        assertEquals(PageClass.FRANCO_BELGIAN, of(0.755))
        assertEquals(PageClass.US, of(0.756))
        assertEquals(PageClass.US, of(0.65))
        assertEquals("unknown shape", PageClass.US, PageClass.of(manga = false, topToBottom = false, aspect = null))
    }

    @Test
    fun theMedianLeavesOutTheCoverAndSpreads() {
        // A square cover and four spreads, each far from the story pages' 0.65.
        val us = book(1988, 3056, spreads = 4)
        assertEquals(1988.0 / 3056, PageClass.medianAspect(us)!!, 1e-9)
        assertEquals(BookShape(PageClass.US, 0.65), BookShape.of(manga = false, topToBottom = false, analysis = us))
        assertEquals(PageClass.FRANCO_BELGIAN, BookShape.of(false, false, book(2400, 3240, spreads = 2)).pageClass)
        assertEquals(PageClass.WEB, BookShape.of(false, false, book(800, 12000)).pageClass)
    }

    @Test
    fun aBookOfWidePagesTakesTheirMedianAndReadsAsWeb() {
        val wide = book(1600, 1000, coverW = 700, coverH = 1000)
        assertEquals(1.6, PageClass.medianAspect(wide)!!, 1e-9)
        assertEquals(PageClass.WEB, BookShape.of(false, false, wide).pageClass)
    }

    @Test
    fun noKnownSizeIsAnUnknownShape() {
        val unknown = Spreads.classify(listOf("Book 001.jpg", "Book 002.jpg"), listOf(null, null))
        assertNull(PageClass.medianAspect(unknown))
        assertEquals(BookShape.UNKNOWN, BookShape.of(false, false, unknown))
        assertEquals(BookShape(PageClass.MANGA), BookShape.of(manga = true, topToBottom = false, analysis = null))
    }

    @Test
    fun eachClassPrintsAtItsWidth() {
        val ppi = 254.0
        // 254 ppi: 100 px per cm.
        assertEquals(1280.0, ReadSize(BookShape(PageClass.MANGA), ppi).printScale(1280.0, 1800.0) * 1280, 1e-6)
        assertEquals(1680.0, ReadSize(BookShape(PageClass.US), ppi).printScale(1000.0, 1500.0) * 1000, 1e-6)
        assertEquals(2150.0, ReadSize(BookShape(PageClass.FRANCO_BELGIAN), ppi).printScale(2400.0, 3240.0) * 2400, 1e-6)
        assertEquals("web: its pixels at 96 dpi", ppi / 96, ReadSize(BookShape(PageClass.WEB), ppi).printScale(900.0, 4000.0), 1e-9)
    }

    @Test
    fun aSpreadPrintsAtTheBooksPageHeight() {
        val read = ReadSize(BookShape(PageClass.US, 0.65), 254.0)
        val pageHeight = 1680.0 / 0.65
        assertEquals("a page of the book's shape", pageHeight, read.printScale(1300.0, 2000.0) * 2000, 1e-6)
        assertEquals("a spread", pageHeight, read.printScale(2600.0, 2000.0) * 2000, 1e-6)
        assertEquals("a tall strip keeps the width", 1680.0, read.printScale(1300.0, 6000.0) * 1300, 1e-6)
    }

    @Test
    fun upTo115xZoomThePageIsJudgedAsItFitsAbove115xAtPrintSize() {
        val read = ReadSize(BookShape(PageClass.US), 254.0)
        val print = read.printScale(1000.0, 1500.0)
        assertEquals("needs no zoom", 1.0, read.factor(print * 1.5, 1000.0, 1500.0), 0.0)
        assertEquals("at print size already", 1.0, read.factor(print, 1000.0, 1500.0), 0.0)
        assertEquals("1.149x zoom", 1.0, read.factor(print / 1.149, 1000.0, 1500.0), 0.0)
        assertEquals("1.151x zoom", 1.151, read.factor(print / 1.151, 1000.0, 1500.0), 1e-9)
        assertEquals("2.3x zoom", 2.3, read.factor(print / 2.3, 1000.0, 1500.0), 1e-9)
        // Exactly at the cutoff, and the next value above it.
        assertEquals("1.15x zoom", 1.0, ReadSize.factorAt(ReadSize.ZOOM_FREE), 0.0)
        val above = Math.nextUp(ReadSize.ZOOM_FREE)
        assertEquals(above, ReadSize.factorAt(above), 0.0)
    }

    @Test
    fun judgedAtTheReadSize() {
        val read = ReadSize(BookShape(PageClass.US), 254.0)
        // A 1000 x 1500 input from a 2000 x 3000 page, shown at 400 x 600: 40 % of the input, 20 % of the page.
        val shown = Plan(400, 600, 0.0, 0.0, 1000.0, 1500.0)
        val j = Judged.of(1000, 1500, shown, 2000.0, 3000.0, read)
        // Printed 1680 px wide: 4.2x the fit size, 168 % of the input.
        assertEquals(4.2, j.factor, 1e-9)
        assertEquals(168.0, j.percent, 1e-9)
        assertEquals(true, j.printed)
        val up = ChainModel(AiStep.UPSCALE, 2)
        assertEquals(listOf(ChainStep(0, 1_500_000L)), j.steps(listOf(up), AiThresholds()))
        val asShown = Judged.of(1000, 1500, shown, 2000.0, 3000.0, null)
        assertEquals(40.0, asShown.percent, 1e-9)
        assertEquals(emptyList<ChainStep>(), asShown.steps(listOf(up), AiThresholds()))
    }

    @Test
    fun theKeyNamesWhatDecidesTheFactor() {
        val us = ReadSize(BookShape(PageClass.US, 0.65), 239.6)
        assertEquals("u65@240", us.key)
        assertEquals("w@500", ReadSize(BookShape(PageClass.WEB, 0.4), 500.0).key)
        assertNotEquals(us.key, us.copy(ppi = 500.0).key)
        assertNotEquals(us.key, ReadSize(BookShape(PageClass.FRANCO_BELGIAN, 0.74), 239.6).key)
    }

    @Test
    fun ppiComesFromTheDisplaysOwnDpiUnlessItIsAPlaceholder() {
        assertEquals(239.5, ReadSize.ppiOf(239f, 240f, 320), 1e-9)
        assertEquals(500.0, ReadSize.ppiOf(500f, 500f, 560), 1e-9)
        assertEquals("missing", 320.0, ReadSize.ppiOf(0f, 0f, 320), 1e-9)
        assertEquals("far off the density", 320.0, ReadSize.ppiOf(72f, 72f, 320), 1e-9)
    }
}
