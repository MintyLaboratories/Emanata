package com.infinity.comicreader.ai

import android.app.Application
import android.graphics.Bitmap
import com.infinity.comicreader.reader.BitmapUtils
import com.infinity.comicreader.reader.Plan
import com.infinity.comicreader.reader.Scaling
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CancellationException
import kotlin.random.Random

/** A page through a chain of models ([AiPages.chain]): each in turn, then resampled; nothing made when it falls back. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AiChainRunTest {

    private fun page(w: Int, h: Int): Bitmap {
        val r = Random(w * 7 + h)
        val px = IntArray(w * h) { 0xFF000000.toInt() or r.nextInt(1 shl 24) }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPixels(px, 0, w, 0, 0, w, h)
            setHasAlpha(false)
        }
    }

    private fun pixels(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    private fun bitmapOf(px: IntArray, w: Int, h: Int) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
        setPixels(px, 0, w, 0, 0, w, h)
        setHasAlpha(false)
    }

    private fun session(model: FakeModel) = Session(model, ModelProbe.measure(model), 48)

    /** Shows a 60 x 90 page at 70 x 105: enlarged. */
    private val plan = Plan(70, 105, 0.0, 0.0, 60.0, 90.0)

    @Test
    fun eachModelRunsOnTheOneBeforesOutputThenThePageIsResampled() {
        val clean = FakeModel(scale = 1, reach = 1)
        val up = FakeModel(scale = 2, reach = 2)
        val src = page(60, 90)
        val run = AiPages.chain(listOf(session(clean), session(up)), src, plan)
        assertNull(run.fallback)
        assertEquals(2, run.ran)
        val once = bitmapOf(FakeModel.reference(src, 1, 1), 60, 90)
        val twice = bitmapOf(FakeModel.reference(once, 2, 2), 120, 180)
        val expected = BitmapUtils.resample(twice, AiPages.scaled(plan, 2), false, Scaling.DEFAULT)
        assertEquals(70, run.bitmap!!.width)
        assertArrayEquals(pixels(expected), pixels(run.bitmap!!))
        assertFalse("the page itself is only read", src.isRecycled)
    }

    @Test
    fun anUpscalerIsSkippedWhereThePageShowsNoLarger() {
        val clean = FakeModel(scale = 1, reach = 1)
        val up = FakeModel(scale = 2, reach = 1)
        val upSession = session(up)
        val before = up.runs.get()
        val shrink = Plan(50, 75, 0.0, 0.0, 60.0, 90.0)
        val run = AiPages.chain(listOf(session(clean), upSession), page(60, 90), shrink)
        assertNull(run.fallback)
        assertEquals(1, run.ran)
        assertEquals(before, up.runs.get())
    }

    @Test
    fun aSecondUpscalerIsSkippedOnceTheFirstMadeThePageLargeEnough() {
        val first = FakeModel(scale = 2, reach = 1)
        val second = FakeModel(scale = 2, reach = 1)
        val secondSession = session(second)
        val before = second.runs.get()
        val run = AiPages.chain(listOf(session(first), secondSession), page(60, 90), plan)
        assertEquals(1, run.ran)
        assertEquals(before, second.runs.get())
    }

    @Test
    fun onlyUpscalersOnAShrunkPageMakeNothing() {
        val up = FakeModel(scale = 2, reach = 1)
        val run = AiPages.chain(listOf(session(up)), page(60, 90), Plan(30, 45, 0.0, 0.0, 60.0, 90.0))
        assertEquals(Fallback.UNNEEDED, run.fallback)
        assertNull(run.bitmap)
        assertEquals(0, run.ran)
    }

    @Test
    fun aModelThatFallsBackMakesNothing() {
        val clean = FakeModel(scale = 1, reach = 1)
        val up = FakeModel(scale = 2, reach = 1)
        val sessions = listOf(session(clean), session(up))
        var gateCalls = 0
        // The gate is asked before a page's first tile and between tiles: the first model passes, the second stops.
        val tilesOfFirst = TilePlan.tiles(60, 90, 48, sessions[0].geometry).size
        val run = AiPages.chain(sessions, page(60, 90), plan, gate = { if (++gateCalls > tilesOfFirst) Fallback.PAUSED else null })
        assertEquals(Fallback.PAUSED, run.fallback)
        assertNull(run.bitmap)
        assertEquals(1, run.ran)
    }

    @Test
    fun theModelsShareOnePagesTime() {
        val clean = FakeModel(scale = 1, reach = 1)
        val up = FakeModel(scale = 2, reach = 1)
        var now = 0L
        // Every clock reading moves time on by 400 ms: the first model alone takes more than the 1 s allowed.
        val run = AiPages.chain(
            listOf(session(clean), session(up)), page(60, 90), plan, limits = PageLimits(timeoutMs = 1_000), clock = { now.also { now += 400 } }
        )
        assertEquals(Fallback.TIMEOUT, run.fallback)
        assertNull(run.bitmap)
    }

    @Test
    fun cancelingThrows() {
        val up = FakeModel(scale = 2, reach = 1)
        assertThrows(CancellationException::class.java) { AiPages.chain(listOf(session(up)), page(60, 90), plan, canceled = { true }) }
    }

    @Test
    fun aChainExpectedToRunPastItsTimeFallsBackBeforeAnyModelRuns() {
        val clean = FakeModel(scale = 1, reach = 1)
        val up = FakeModel(scale = 2, reach = 1)
        val steps = ArrayList<Int>()
        val run = AiPages.chain(
            listOf(session(clean), session(up)), page(60, 90), plan, limits = PageLimits(timeoutMs = 1_000),
            expect = { _, _ -> 600 }, onStep = { i, _, _ -> steps += i }
        )
        assertEquals(Fallback.TIMEOUT, run.fallback)
        assertTrue(run.early)
        assertEquals(0, run.ran)
        assertTrue("no model ran", steps.isEmpty())
    }

    @Test
    fun aModelLeavesTheModelsAfterItTheirExpectedTime() {
        val up = FakeModel(scale = 2, reach = 1)
        var t = 0L
        val first = session(FakeModel(scale = 1, reach = 1, onRun = { t += 100 }))
        val tiles = TilePlan.tiles(60, 90, 48, first.geometry).size
        val seen = ArrayList<Pair<Int, Long>>()
        // The first model alone fits the limit at 100 ms a tile, but not with the 600 ms kept for the second.
        val run = AiPages.chain(
            listOf(first, session(up)), page(60, 90), plan, limits = PageLimits(timeoutMs = tiles * 100L + 500), clock = { t },
            expect = { i, _ -> if (i == 0) 0 else 600 },
            onStep = { i, px, stats ->
                seen += i to px
                assertTrue(stats.tiles < stats.planned)
            }
        )
        assertEquals(Fallback.TIMEOUT, run.fallback)
        assertEquals(listOf(0 to 60L * 90), seen)
    }

    @Test
    fun stepsLeaveOutAnUpscalerWhereThePageShowsNoLarger() {
        val shrink = Plan(40, 60, 0.0, 0.0, 60.0, 90.0)
        val clean = ChainModel(AiStep.ARTIFACTS, 1)
        val up = ChainModel(AiStep.UPSCALE, 2)
        assertEquals(listOf(ChainStep(0, 5400L)), AiPages.steps(listOf(clean, up), 60, 90, shrink))
        assertEquals(listOf(ChainStep(0, 5400L), ChainStep(1, 5400L)), AiPages.steps(listOf(clean, up), 60, 90, plan))
        // A second upscaler reads the first's output, four times the pixels.
        val big = Plan(240, 360, 0.0, 0.0, 60.0, 90.0)
        assertEquals(listOf(ChainStep(0, 5400L), ChainStep(1, 21600L)), AiPages.steps(listOf(up, up), 60, 90, big))
    }

    @Test
    fun theChainRunsOnlyTheStepsItIsGiven() {
        val clean = FakeModel(scale = 1, reach = 1)
        val up = FakeModel(scale = 2, reach = 1)
        val sessions = listOf(session(clean), session(up))
        val before = clean.runs.get()
        val run = AiPages.chain(sessions, page(60, 90), plan, steps = listOf(ChainStep(1, 5400L)))
        assertNull(run.fallback)
        assertEquals(1, run.ran)
        assertEquals(before, clean.runs.get())
        assertEquals(Fallback.UNNEEDED, AiPages.chain(sessions, page(60, 90), plan, steps = emptyList()).fallback)
    }

    @Test
    fun theLastModelsOutputIsKeptAtFullSizeWhenAskedFor() {
        val clean = FakeModel(scale = 1, reach = 1)
        val up = FakeModel(scale = 2, reach = 2)
        val src = page(60, 90)
        val plain = AiPages.chain(listOf(session(clean), session(up)), src, plan)
        assertNull("none unless asked for", plain.detail)
        val kept = AiPages.chain(listOf(session(clean), session(up)), src, plan, keepBytes = Long.MAX_VALUE)
        val once = bitmapOf(FakeModel.reference(src, 1, 1), 60, 90)
        val full = FakeModel.reference(once, 2, 2)
        val detail = kept.detail!!
        assertEquals(120, detail.width)
        assertEquals(180, detail.height)
        assertArrayEquals(full, pixels(detail))
        // The page shown is the same either way.
        assertArrayEquals(pixels(plain.bitmap!!), pixels(kept.bitmap!!))
        assertFalse(src.isRecycled)
    }

    @Test
    fun aKeptOutputOverItsShareIsReducedEvenlyByTheSameResampler() {
        val up = FakeModel(scale = 2, reach = 1)
        val src = page(60, 90)
        val run = AiPages.chain(listOf(session(up)), src, plan, keepBytes = 120L * 180 * 4 / 4)
        val detail = run.detail!!
        assertEquals(60, detail.width)
        assertEquals(90, detail.height)
        val full = bitmapOf(FakeModel.reference(src, 2, 1), 120, 180)
        val expected = BitmapUtils.resample(full, Plan(60, 90, 0.0, 0.0, 120.0, 180.0), false, Scaling.DEFAULT)
        assertArrayEquals(pixels(expected), pixels(detail))
    }

    @Test
    fun aFallenBackChainKeepsNoOutput() {
        val up = FakeModel(scale = 2, reach = 1)
        val run = AiPages.chain(listOf(session(up)), page(60, 90), plan, gate = { Fallback.PAUSED }, keepBytes = Long.MAX_VALUE)
        assertEquals(Fallback.PAUSED, run.fallback)
        assertNull(run.detail)
    }
}
