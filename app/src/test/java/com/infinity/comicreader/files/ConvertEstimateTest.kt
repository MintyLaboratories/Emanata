package com.infinity.comicreader.files

import com.infinity.comicreader.reader.ImageKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** How long Convert work takes: first-run defaults, learned rates, books on shares, many books, and the time left as a job runs. */
class ConvertEstimateTest {
    private val mb = 1L shl 20

    /** A book of [count] pages of [format], [each] bytes. */
    private fun book(count: Int, each: Long = 2 * mb, format: ImageKind = ImageKind.JPEG, location: String = WorkRates.DEVICE, container: Container = Container.ZIP) =
        BookPlan(count * each + 4096, location, container, List(count) { PagePlan(format, each) })

    @Test
    fun aFirstRunUsesTheDefaults() {
        val rates = WorkRates()
        val plan = book(100)
        val seconds = ConvertEstimate(rates).forecast(ConvertOp.COMPACT, listOf(plan)).single()
        val convert = 100 * WorkRates.defaultSeconds(Step.COMPACT, ImageKind.JPEG)
        val expected = convert + plan.bytes / WorkRates.DEVICE_WRITE + WorkRates.BOOK_SECONDS
        assertEquals(expected, seconds, 1e-6)
    }

    @Test
    fun copyingPagesAsTheyAreOnlyReadsAndWrites() {
        val plan = book(100, container = Container.RAR)
        val seconds = ConvertEstimate(WorkRates()).forecast(ConvertOp.REPACK, listOf(plan)).single()
        assertEquals(plan.bytes / WorkRates.RAR_READ + plan.bytes / WorkRates.DEVICE_WRITE + WorkRates.BOOK_SECONDS, seconds, 1e-6)
    }

    @Test
    fun learnedRatesReplaceTheDefaults() {
        val rates = WorkRates()
        val before = ConvertEstimate(rates).forecast(ConvertOp.COMPACT, listOf(book(100))).single()
        repeat(300) { rates.page(Step.COMPACT, ImageKind.JPEG, 2 * mb, 2.0) }
        val after = ConvertEstimate(rates).forecast(ConvertOp.COMPACT, listOf(book(100))).single()
        assertTrue(after > 3 * before)
        assertEquals(200.0, after, 5.0)
        // Pack compacted makes pages as Compact does.
        assertEquals(after, ConvertEstimate(rates).forecast(ConvertOp.PACK_COMPACTED, listOf(book(100))).single(), 1.0)
    }

    @Test
    fun aBookOnASlowShareIsBoundByReadingIt() {
        val rates = WorkRates(WorkRates.Seeds(shareRead = { 1e6 }))
        val device = ConvertEstimate(rates).forecast(ConvertOp.COMPACT, listOf(book(100))).single()
        val share = ConvertEstimate(rates).forecast(ConvertOp.COMPACT, listOf(book(100, location = WorkRates.share("a")))).single()
        // 200 MB at 1 MB/s read, and as much again written there at 0.6 MB/s.
        assertTrue(share > 10 * device)
        assertEquals(200.0 * mb / 1e6 + 200.0 * mb / 0.6e6, share, 30.0)
    }

    @Test
    fun aFolderOfManyBooksEstimatesThoseNotListedLikeThoseListed() {
        val rates = WorkRates()
        val listed = List(5) { book(40, format = ImageKind.PNG) }
        val unlisted = List(195) { BookPlan(listed[0].bytes, WorkRates.DEVICE, Container.ZIP) }
        val times = ConvertEstimate(rates).forecast(ConvertOp.COMPACT, listed + unlisted)
        assertEquals(200, times.size)
        assertEquals(times[0], times[199], times[0] * 0.01)
        // Without any listed: medium JPEGs.
        val blind = ConvertEstimate(rates).forecast(ConvertOp.COMPACT, unlisted).first()
        assertTrue(blind < times[0] / 4)
    }

    @Test
    fun booksAlreadyDoneAreSkipped() {
        val times = ConvertEstimate(WorkRates()).forecast(ConvertOp.COMPACT, listOf(book(100).copy(done = true), book(100)))
        assertEquals(ConvertEstimate.SKIP_SECONDS, times[0], 0.0)
        assertTrue(times[1] > 10)
    }

    @Test
    fun aFolderOfBooksDoneBeforeStaysQuickPastTheListingBudget() {
        val listed = List(5) { book(40).copy(done = true) }
        // Marks read, pages not listed.
        val unlisted = List(195) { BookPlan(100 * mb, done = true) }
        val times = ConvertEstimate(WorkRates()).forecast(ConvertOp.COMPACT, listed + unlisted)
        assertEquals(200 * ConvertEstimate.SKIP_SECONDS, times.sum(), 1e-6)
        // Marks not read either: done as often as those read.
        val unread = List(195) { BookPlan(100 * mb, done = null) }
        assertEquals(200 * ConvertEstimate.SKIP_SECONDS, ConvertEstimate(WorkRates()).forecast(ConvertOp.COMPACT, listed + unread).sum(), 1e-6)
        // Half of those read done: an unread book counts half its work.
        val half = ConvertEstimate(WorkRates()).forecast(ConvertOp.COMPACT, listOf(book(40), book(40).copy(done = true), book(40).copy(done = null)))
        assertEquals((half[0] + half[1]) / 2, half[2], 1e-6)
        // None read: each counts in full.
        val blind = ConvertEstimate(WorkRates()).forecast(ConvertOp.COMPACT, listOf(book(40), BookPlan(book(40).bytes, done = null)))
        assertTrue(blind[1] > 1)
    }

    @Test
    fun anUpscaleStepWithNoRecordUsesItsOwnSeed() {
        val seconds = ConvertEstimate(WorkRates()).forecast(ConvertOp.upscale("model-a"), listOf(book(10))).single()
        assertTrue(seconds > 10 * 2.0)
    }

    @Test
    fun theTimeLeftFollowsThePlanUntilTheJobShowsItsOwnSpeed() {
        val plan = listOf(60.0, 60.0, 60.0)
        assertEquals(180.0, ConvertEstimate.remaining(plan, 0, 0.0, 0.0), 1e-9)
        // On plan: the plan's time left.
        assertEquals(90.0, ConvertEstimate.remaining(plan, 1, 0.5, 90.0), 1e-9)
        // Twice as slow so far: 90 s planned done, 180 s taken; the measure weighs 90 / 120.
        val slower = ConvertEstimate.remaining(plan, 1, 0.5, 180.0)
        assertEquals(90.0 * (0.75 * 2 + 0.25), slower, 1e-9)
        // Early on, the plan still counts most.
        val early = ConvertEstimate.remaining(plan, 0, 0.05, 30.0)
        assertTrue(early < 171 * 3 && early > 171)
        assertEquals(0.0, ConvertEstimate.remaining(plan, 3, 0.0, 500.0), 1e-9)
        assertEquals(0.0, ConvertEstimate.remaining(emptyList(), 0, 0.0, 5.0), 0.0)
    }

    @Test
    fun aStalledStartDoesNotSwingTheTimeLeftWildly() {
        val plan = listOf(600.0)
        val left = ConvertEstimate.remaining(plan, 0, 0.001, 120.0)
        assertTrue(left < 600 * 1.25)
    }

    @Test
    fun pageFormatsByName() {
        assertEquals(ImageKind.JPEG, ConvertEstimate.formatOf("a/01.JPG"))
        assertEquals(ImageKind.HEIF, ConvertEstimate.formatOf("01.heic"))
        assertEquals(ImageKind.TIFF, ConvertEstimate.formatOf("01.tif"))
        assertNull(ConvertEstimate.formatOf("ComicInfo.xml"))
    }

    @Test
    fun spansRoundToMinutesThenToTensOfMinutes() {
        assertTrue(Span.of(59.0).underMinute)
        assertTrue(Span.of(Double.NaN).underMinute)
        assertEquals(Span(0, 1), Span.of(60.0))
        assertEquals(Span(0, 13), Span.of(12.6 * 60))
        assertEquals(Span(0, 89), Span.of(89 * 60.0))
        assertEquals(Span(1, 30), Span.of(91 * 60.0))
        assertEquals(Span(2, 0), Span.of(118 * 60.0))
    }
}
