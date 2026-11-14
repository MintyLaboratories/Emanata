package com.infinity.comicreader.ai

import android.app.Application
import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.CancellationException

/** Tile sizes chosen per model and device: the selection rule, the timing, what is kept, and how opening uses them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TileTuningTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun timing(size: Int, vararg ms: Long, peak: Long = 0) = TileTiming.Timing(size, ms.toList(), peakBytes = peak)

    // ------------------------------------------------------------------ the rule, on the reference tablet's times

    @Test
    fun theTabletsTimesPickEachModelsOwnSize() {
        // Per-tile times on the reference tablet (GPU fp16, 1000 x 1500 page time over its tiles).
        val cunny = Geometry(2, 0, 17)
        assertEquals("CuNNy: 384 px, 512 px past the stop bound", 384, TileTiming.rank(listOf(timing(192, 42, 42, 43), timing(384, 97, 96, 98), timing(512, 185, 184, 186)), cunny).first())
        val artcnn = Geometry(2, 0, 8)
        assertEquals("ArtCNN: 512 px", listOf(512, 384, 192), TileTiming.rank(listOf(timing(192, 37, 36, 37), timing(384, 116, 117, 116), timing(512, 128, 127, 129)), artcnn))
        val compact = Geometry(2, 0, 20)
        assertEquals("OpenComic 2x compact: 192 px, the larger past the stop bound", listOf(192), TileTiming.rank(listOf(timing(192, 77, 77, 78), timing(256, 164, 163, 165), timing(384, 396, 395, 397)), compact))
        val waifu2x = Geometry(2, 0, 15)
        assertEquals("waifu2x: 192 px", listOf(192), TileTiming.rank(listOf(timing(192, 56, 56, 57), timing(384, 233, 232, 234)), waifu2x))
    }

    @Test
    fun slowTilesRuleASizeOut() {
        val g = Geometry(2, 0, 8)
        val bound = TileTiming.TILE_MS
        val spike = bound * TileTiming.SPIKE
        assertEquals("median past the bound", listOf(192), TileTiming.rank(listOf(timing(192, 37, 37, 37), timing(512, 100, bound + 1, bound + 1)), g))
        assertEquals("one tile past the spike", listOf(192), TileTiming.rank(listOf(timing(192, 37, 37, 37), timing(512, 100, 100, spike + 1)), g))
        assertEquals(listOf(512, 192), TileTiming.rank(listOf(timing(192, 37, 37, 37), timing(512, 100, bound, spike)), g))
    }

    @Test
    fun pagesSayWhenTheNextSizeWouldEndInTime() {
        // 192 px tiles at 60 ms: 256 px about 107 ms, 384 px about 240 ms.
        val fast = RunStats(6, 70, longArrayOf(300, 60, 60, 61, 59, 60), 640)
        assertTrue(TileTiming.roomFor(fast, 192, 256))
        assertFalse(TileTiming.roomFor(fast, 192, 384))
        assertFalse("too few tiles past the first", TileTiming.roomFor(RunStats(3, 70, longArrayOf(300, 10, 10), 320), 192, 256))
        assertFalse(TileTiming.roomFor(RunStats(6, 70, longArrayOf(300, 90, 90, 90, 90, 90), 750), 192, 256))
    }

    @Test
    fun nearlyEqualCostsGoToTheSmallerSize() {
        val g = Geometry(1, 0, 0)
        // 256: 32 ms for 65536 pixels; 512: 4.7 % more per pixel, then 10 % less.
        assertEquals(listOf(256, 512), TileTiming.rank(listOf(timing(256, 32), timing(512, 134)), g))
        assertEquals(listOf(512, 256), TileTiming.rank(listOf(timing(256, 32), timing(512, 115)), g))
    }

    @Test
    fun sizesThatFailedOrHeldTooMuchAreLeftOut() {
        val g = Geometry(2, 0, 8)
        val failed = TileTiming.Timing(512, emptyList(), failed = true)
        assertEquals(listOf(192), TileTiming.rank(listOf(timing(192, 37), failed), g))
        assertEquals(listOf(192), TileTiming.rank(listOf(timing(192, 37, peak = 10), timing(384, 80, peak = 101)), g, budgetBytes = 100))
        assertTrue(TileTiming.rank(emptyList(), g).isEmpty())
        assertTrue("none within the stop bound", TileTiming.rank(listOf(timing(192, 400)), g).isEmpty())
    }

    @Test
    fun catalogModelsOfferEveryCandidateSize() {
        assertEquals(listOf(192, 256, 384, 512), TileSizing.CANDIDATES)
        assertTrue(ModelCatalog.all.all { it.tiles == TileSizing.CANDIDATES })
        assertTrue("past the default, so devices choose", TileSizing.CANDIDATES.any { it > TileSizing.DEFAULT_MAX })
    }

    // ------------------------------------------------------------------ timing

    /** [inner], whose runs move [now] on by [ms] of their input's width. */
    private class Clocked(val inner: FakeModel, val now: LongArray, val ms: (Int) -> Long) : TileModel by inner {
        val widths = ArrayList<Int>()

        override fun run(src: Bitmap, input: Box, dst: Bitmap?, crop: Box?, dx: Int, dy: Int): Dims? {
            widths += input.width
            now[0] += ms(input.width)
            return inner.run(src, input, dst, crop, dx, dy)
        }
    }

    /** About a light 2x model's tile times on the reference tablet: 384 px the cheapest per pixel, 512 px past the stop bound. */
    private fun lightMs(width: Int): Long = when {
        width <= 192 -> 42
        width <= 256 -> 60
        width <= 384 -> 97
        else -> 185
    }

    @Test
    fun timingRunsEachSizeOnceUntimedThenInRounds() {
        val now = longArrayOf(0)
        val m = Clocked(FakeModel(), now, ::lightMs)
        val tried = ArrayList<Int>()
        val t = TileTiming.time(m, listOf(512, 192, 384, 256), Geometry(2, 0, 5), { now[0] }, { false }) { tried += it }
        assertEquals(listOf(192, 256, 384, 512), tried)
        // Smallest first, each twice, timing the second; 512 past the bound runs again until its median can no longer pass.
        val round = listOf(192, 192, 256, 256, 384, 384)
        assertEquals(round + listOf(512, 512) + round + listOf(512, 512) + round, m.widths)
        assertEquals(listOf(42L, 42L, 42L), t.first { it.size == 192 }.ms)
        assertEquals(listOf(185L, 185L), t.first { it.size == 512 }.ms)
        assertEquals("freed before each other size, and at the end", m.widths.zipWithNext().count { (a, b) -> a != b } + 1, m.inner.trims.get())
        assertEquals(384, TileTiming.rank(t, Geometry(2, 0, 5)).first())
    }

    @Test
    fun oneSlowRunDoesNotRuleASizeOut() {
        val now = longArrayOf(0)
        // The first timed 384 px tile takes 160 ms, the later ones their usual 97.
        var runs384 = 0
        val m = Clocked(FakeModel(), now) { w -> if (w == 384 && ++runs384 == 2) 160 else lightMs(w) }
        val t = TileTiming.time(m, TileSizing.CANDIDATES, Geometry(2, 0, 5), { now[0] }, { false })
        assertEquals(listOf(160L, 97L, 97L), t.single { it.size == 384 }.ms)
        assertTrue("climbed past it", 512 in m.widths)
        assertEquals(384, TileTiming.rank(t, Geometry(2, 0, 5)).first())
    }

    @Test
    fun aSizeFarPastTheBoundIsNotTried() {
        val now = longArrayOf(0)
        // 192 at 100 ms: 384 would take about 400, past twice the bound.
        val m = Clocked(FakeModel(), now) { w -> if (w <= 192) 100 else 1000 }
        val t = TileTiming.time(m, listOf(192, 384), Geometry(2, 0, 5), { now[0] }, { false })
        assertFalse(384 in m.widths)
        assertEquals(listOf(192), t.map { it.size })
    }

    @Test
    fun aSizeThatFailsEndsTheTimingOfItAndLarger() {
        val now = longArrayOf(0)
        val m = Clocked(FakeModel(failsAbove = 256), now, ::lightMs)
        val t = TileTiming.time(m, TileSizing.CANDIDATES, Geometry(2, 0, 5), { now[0] }, { false })
        assertEquals(listOf(192, 256, 384), t.map { it.size })
        assertTrue(t.single { it.size == 384 }.failed)
        assertFalse(512 in m.widths)
        assertEquals(256, TileTiming.rank(t, Geometry(2, 0, 5)).first())
    }

    @Test
    fun aLaterFailureMarksOnlyItsOwnSize() {
        val now = longArrayOf(0)
        // The first run of the second round (192 px, untimed) fails.
        val m = Clocked(FakeModel(failAt = 8), now, ::lightMs)
        val t = TileTiming.time(m, TileSizing.CANDIDATES, Geometry(2, 0, 5), { now[0] }, { false })
        assertEquals(listOf(192), t.filter { it.failed }.map { it.size })
        assertTrue(t.single { it.size == 384 }.ms.size > 1)
        assertNull("the GPU failed, not a size", TileTiming.blamed(t, TileSizing.DEFAULT_MAX))
    }

    @Test
    fun failuresBlameTheSmallestFailedSizePastTheCap() {
        val failed = { size: Int -> TileTiming.Timing(size, emptyList(), failed = true) }
        assertEquals(384, TileTiming.blamed(listOf(timing(192, 40), timing(256, 60), failed(384)), 192))
        assertEquals(256, TileTiming.blamed(listOf(timing(192, 40), failed(256), failed(384)), 192))
        assertNull(TileTiming.blamed(listOf(failed(192), failed(384)), 192))
        assertNull(TileTiming.blamed(listOf(timing(192, 40), timing(384, 90)), 192))
    }

    @Test
    fun aFailedReferenceIsNotBlamedOnTheModelChecked() {
        val e = assertThrows(ModelProbe.Unfit::class.java) {
            ModelProbe.verify(FakeModel(), FakeModel(onGpu = false, failsAbove = 0), Geometry(2, 0, 5), 128, 64)
        }
        assertTrue(e.reference)
        val own = assertThrows(ModelProbe.Unfit::class.java) {
            ModelProbe.verify(FakeModel(failsAbove = 0), FakeModel(onGpu = false), Geometry(2, 0, 5), 128, 64)
        }
        assertFalse(own.reference)
    }

    @Test
    fun timingCanBeCanceled() {
        val now = longArrayOf(0)
        val m = Clocked(FakeModel(), now, ::lightMs)
        assertThrows(CancellationException::class.java) { TileTiming.time(m, TileSizing.CANDIDATES, Geometry(2, 0, 5), { now[0] }, { m.widths.size >= 3 }) }
        assertEquals(3, m.widths.size)
    }

    // ------------------------------------------------------------------ what is kept

    @Test
    fun choicesAreKeptPerKeyAndSurviveUnreadableFiles() {
        val dir = tmp.newFolder()
        val choices = TileChoices(dir)
        assertEquals(TileChoice(), choices.get("a"))
        choices.put("a", TileChoice(384, 511, 0, 2))
        choices.put("b", TileChoice(256))
        assertEquals(TileChoice(384, 511, 0, 2), TileChoices(dir).get("a"))
        assertEquals(TileChoice(256), TileChoices(dir).get("b"))
        dir.listFiles()!!.forEach { it.writeText("nonsense") }
        assertEquals(TileChoice(), choices.get("a"))
    }

    @Test
    fun aSizeLeftTryingLowersTheCeiling() {
        assertEquals(TileChoice(0, 383, 0), TileChoice(384, Int.MAX_VALUE, 384).settled())
        assertEquals(TileChoice(256, 511, 0), TileChoice(256, Int.MAX_VALUE, 512).settled())
        assertEquals(TileChoice(256), TileChoice(256).settled())
        assertEquals(TileChoice(0, 255, 0), TileChoice(384, 511).below(256))
    }

    // ------------------------------------------------------------------ opening

    private val now = longArrayOf(0)

    private fun runtime(gpuMs: (Int) -> Long = ::lightMs, gpuModel: () -> FakeModel = { FakeModel() }) =
        FakeRuntime(make = { onGpu, _ -> if (onGpu) Clocked(gpuModel(), now, gpuMs) else FakeModel(onGpu = false) })

    private fun tuning(dir: File, candidates: List<Int> = TileSizing.CANDIDATES) = TileTuning(candidates, TileChoices(dir), "model-a")

    private fun open(runtime: FakeRuntime, tuning: TileTuning?) =
        Sessions.open(runtime, fakeFiles(tmp.root), threads = 4, tuning = tuning, clock = { now[0] })!!

    private fun gpuWidths(runtime: FakeRuntime) = (runtime.opened.last() as Clocked).widths

    private fun kept(dir: File) = TileChoices(dir).get("Test GPU|fp16|model-a")

    @Test
    fun aTunedModelTakesItsFastestSizeAndKeepsIt() {
        val dir = tmp.newFolder()
        val runtime = runtime()
        val s = open(runtime, tuning(dir))
        assertTrue(s.model.onGpu)
        assertEquals(384, s.size)
        assertEquals(TileChoice(384, timings = 1), kept(dir))
        // Opened again: the kept size, checked, not timed again.
        val again = runtime()
        assertEquals(384, open(again, tuning(dir)).size)
        assertTrue(gpuWidths(again).all { it == 384 })
    }

    @Test
    fun aModelWithoutCandidatesBehavesAsToday() {
        val dir = tmp.newFolder()
        val plain = runtime()
        assertEquals(TileSizing.DEFAULT_MAX, open(plain, null).size)
        assertTrue("nothing timed", gpuWidths(plain).all { it == TileSizing.DEFAULT_MAX })
        val none = runtime()
        assertEquals(TileSizing.DEFAULT_MAX, open(none, tuning(dir, emptyList())).size)
        val capped = runtime()
        assertEquals(TileSizing.DEFAULT_MAX, open(capped, tuning(dir, listOf(128, 192))).size)
        assertTrue(gpuWidths(capped).all { it == TileSizing.DEFAULT_MAX })
        assertTrue("nothing kept", dir.listFiles()!!.isEmpty())
        assertFalse(open(runtime(), null).shrink())
    }

    @Test
    fun slowTilesKeepTheDefaultForTheStopBound() {
        val dir = tmp.newFolder()
        val s = open(runtime(gpuMs = { w -> if (w <= 192) 120 else 200 }), tuning(dir))
        assertEquals(192, s.size)
        assertEquals(TileChoice(192, timings = 1), kept(dir))
        assertFalse(s.shrink())
    }

    @Test
    fun aSizeThatFailsToRunFallsBackAndIsRemembered() {
        val dir = tmp.newFolder()
        val runtime = runtime(gpuModel = { FakeModel(failsAbove = 256) })
        val s = open(runtime, tuning(dir))
        assertTrue(s.model.onGpu)
        assertTrue("fp16 kept", s.fp16)
        assertEquals(256, s.size)
        assertEquals(TileChoice(256, 383, 0, 1), kept(dir))
        // Opened again: nothing past the ceiling is tried.
        val again = runtime(gpuModel = { FakeModel(failsAbove = 256) })
        assertEquals(256, open(again, tuning(dir)).size)
        assertTrue(gpuWidths(again).all { it <= 256 })
    }

    @Test
    fun aKeptSizeThatNoLongerRunsFallsBack() {
        val dir = tmp.newFolder()
        TileChoices(dir).put("Test GPU|fp16|model-a", TileChoice(512))
        val s = open(runtime(gpuModel = { FakeModel(failsAbove = 384) }), tuning(dir))
        assertEquals(384, s.size)
        assertEquals(TileChoice(384, 511, 0), kept(dir))
    }

    @Test
    fun aSizeTheAppEndedOnIsNotTriedAgain() {
        val dir = tmp.newFolder()
        TileChoices(dir).put("Test GPU|fp16|model-a", TileChoice(trying = 384))
        val runtime = runtime()
        val s = open(runtime, tuning(dir))
        assertEquals(256, s.size)
        assertTrue(gpuWidths(runtime).none { it >= 384 })
        assertEquals(TileChoice(256, 383, 0, 1), kept(dir))
    }

    @Test
    fun aCancelWhileTimingLowersNoCeiling() {
        val dir = tmp.newFolder()
        // Canceled in a later round, after every size was first tried.
        var gpuRuns = 0
        val canceling = runtime(gpuMs = { w -> gpuRuns++; lightMs(w) })
        assertThrows(CancellationException::class.java) {
            Sessions.open(canceling, fakeFiles(tmp.root), threads = 4, tuning = tuning(dir), clock = { now[0] }, canceled = { gpuRuns >= 12 })
        }
        assertEquals(TileChoice(), kept(dir))
        val s = open(runtime(), tuning(dir))
        assertEquals("the largest sizes still tried", 384, s.size)
        assertEquals(Int.MAX_VALUE, kept(dir).ceiling)
    }

    @Test
    fun aSizeWhoseCheckFailsGivesWayToTheNext() {
        val dir = tmp.newFolder()
        // Wrong output on tiles wider than 256, as a feature map past a GPU buffer gives: seams and the CPU's check catch it.
        val s = open(runtime(gpuModel = { FakeModel(breaksAbove = 256) }), tuning(dir))
        assertEquals(256, s.size)
        assertEquals(TileChoice(256, timings = 1), kept(dir))
    }

    @Test
    fun aSessionInATunedSizeShrinksAfterAFailure() {
        val dir = tmp.newFolder()
        val s = open(runtime(), tuning(dir))
        assertEquals(384, s.size)
        assertTrue(s.shrink())
        assertEquals(TileChoice(0, 383, 0, 1), kept(dir))
        assertEquals("timed again below the ceiling", 256, open(runtime(), tuning(dir)).size)
        assertNull(dir.listFiles()!!.firstOrNull { it.name.endsWith(".tmp") })
    }

    @Test
    fun aChoiceTimedWhileSlowIsTimedAgainOncePagesAreFaster() {
        val dir = tmp.newFolder()
        // Slow when first opened: 256 px past the bound, so 192 px.
        val slow = open(runtime(gpuMs = { w -> if (w <= 192) 120 else 200 }), tuning(dir))
        assertEquals(192, slow.size)
        // A page at 120 ms a tile leaves no room; one at 42 ms does.
        slow.heard(RunStats(8, 70, LongArray(8) { 120 }, 1000))
        assertEquals(TileChoice(192, timings = 1), kept(dir))
        slow.heard(RunStats(8, 70, LongArray(8) { 42 }, 400))
        assertEquals(TileChoice(0, timings = 1), kept(dir))
        assertEquals("timed again, now fast", 384, open(runtime(), tuning(dir)).size)
        assertEquals(TileChoice(384, timings = 2), kept(dir))
        // Timed at most three times in all.
        TileChoices(dir).put("Test GPU|fp16|model-a", TileChoice(192, timings = TileTiming.MAX_TIMINGS))
        val done = open(runtime(), tuning(dir))
        assertEquals(192, done.size)
        done.heard(RunStats(8, 70, LongArray(8) { 42 }, 400))
        assertEquals(TileChoice(192, timings = TileTiming.MAX_TIMINGS), kept(dir))
    }

    @Test
    fun eachPrecisionKeepsItsOwnChoice() {
        val dir = tmp.newFolder()
        val runtime = FakeRuntime(
            gpu = GpuInfo("Test GPU", 2048, false, Long.MAX_VALUE),
            make = { onGpu, _ -> if (onGpu) Clocked(FakeModel(), now, ::lightMs) else FakeModel(onGpu = false) }
        )
        assertEquals(384, open(runtime, tuning(dir)).size)
        assertEquals(TileChoice(384, timings = 1), TileChoices(dir).get("Test GPU|fp32|model-a"))
        assertEquals(TileChoice(), kept(dir))
    }
}
