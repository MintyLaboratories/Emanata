package com.infinity.comicreader.ai

import com.infinity.comicreader.reader.Plan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which models of a chain run at a page's display size ([AiPages.steps]), as the AI thresholds' two handles say. */
class AiStepsTest {

    private val up = ChainModel(AiStep.UPSCALE, 2)
    private val clean = ChainModel(AiStep.ARTIFACTS, 1)
    private val descreen = ChainModel(AiStep.DESCREEN, 1)

    /** A 1000 x 1500 input shown at [pct] % of its side. */
    private fun shown(pct: Int) = Plan(10 * pct, 15 * pct, 0.0, 0.0, 1000.0, 1500.0)

    /** True when [m] alone runs on a page shown at [pct] % under [t]. */
    private fun runs(m: ChainModel, pct: Int, t: AiThresholds) = AiPages.steps(listOf(m), 1000, 1500, shown(pct), t).isNotEmpty()

    private fun upscaleAt(handle: Int) = AiThresholds.of(handle, AiThresholds.CLEANUP_DEFAULT)

    private fun cleanupAt(handle: Int) = AiThresholds.of(AiThresholds.UPSCALE_DEFAULT, handle)

    @Test
    fun upscaleRunsAboveItsHandle() {
        for (handle in listOf(100, 99, 50)) {
            assertEquals("handle $handle, page above", true, runs(up, handle + 1, upscaleAt(handle)))
            assertEquals("handle $handle, page at it", false, runs(up, handle, upscaleAt(handle)))
            assertEquals("handle $handle, page below", false, runs(up, handle - 1, upscaleAt(handle)))
        }
    }

    @Test
    fun upscaleHandleStopsAtTheFloor() {
        val t = upscaleAt(49)
        assertEquals(AiThresholds.UPSCALE_FLOOR, t.upscalePct)
        assertEquals(false, runs(up, 50, t))
        assertEquals(false, runs(up, 49, t))
        assertEquals(true, runs(up, 51, t))
        for (pct in 1..50) assertEquals("$pct %", false, runs(up, pct, upscaleAt(0)))
    }

    @Test
    fun cleanupRunsAboveItsHandle() {
        assertEquals(true, runs(clean, 51, cleanupAt(50)))
        assertEquals(false, runs(clean, 50, cleanupAt(50)))
        assertEquals(true, runs(clean, 50, cleanupAt(49)))
        assertEquals(false, runs(clean, 49, cleanupAt(49)))
    }

    @Test
    fun cleanupAtTheLeftEndRunsAlways() {
        val t = cleanupAt(AiThresholds.ALWAYS)
        for (pct in listOf(1, 10, 25, 50, 100, 200)) assertEquals("$pct %", true, runs(clean, pct, t))
    }

    @Test
    fun descreenRunsAtEverySize() {
        for (t in listOf(AiThresholds(), cleanupAt(200), AiThresholds(200, 200))) {
            for (pct in listOf(1, 10, 25, 50, 100, 300)) assertEquals("$t at $pct %", true, runs(descreen, pct, t))
        }
    }

    @Test
    fun theHandlesMayCross() {
        val t = AiThresholds(upscalePct = 60, cleanupPct = 150)
        val steps = AiPages.steps(listOf(clean, descreen, up), 1000, 1500, shown(100), t)
        assertEquals(listOf(1, 2), steps.map { it.index })
    }

    @Test
    fun aModelAfterAnUpscalerIsMeasuredAgainstItsEnlargedInput() {
        // Shown at 300 %: 150 % of the first 2x model's output, 75 % of the second's.
        val steps = AiPages.steps(listOf(up, up, up), 1000, 1500, shown(300), AiThresholds())
        assertEquals(listOf(ChainStep(0, 1_500_000L), ChainStep(1, 6_000_000L)), steps)
        val after = AiPages.steps(listOf(up, clean), 1000, 1500, shown(90), AiThresholds(upscalePct = 50, cleanupPct = 50))
        assertEquals("the cleanup sees 45 % of the upscaled page", listOf(0), after.map { it.index })
    }

    @Test
    fun theRenderSizeChangesWhichModelsRun() {
        val chain = listOf(clean, descreen, up)
        val t = AiThresholds()
        assertEquals(listOf(1), AiPages.steps(chain, 1000, 1500, shown(40), t).map { it.index })
        assertEquals(listOf(0, 1), AiPages.steps(chain, 1000, 1500, shown(80), t).map { it.index })
        assertEquals(listOf(0, 1, 2), AiPages.steps(chain, 1000, 1500, shown(160), t).map { it.index })
    }

    @Test
    fun aModelIsDecidedByWhatItDoes() {
        assertEquals(AiStep.UPSCALE, up.rule)
        assertEquals(AiStep.ARTIFACTS, clean.rule)
        assertEquals(AiStep.DESCREEN, descreen.rule)
        assertEquals("a 1x model picked as Upscale cleans up", AiStep.ARTIFACTS, ChainModel(AiStep.UPSCALE, 1).rule)
        assertEquals("an enlarging model upscales", AiStep.UPSCALE, ChainModel(AiStep.DESCREEN, 2).rule)
    }

    @Test
    fun theFallbackKeyHoldsOnlyTheHandlesThatDecideTheChain() {
        val t = AiThresholds(upscalePct = 80, cleanupPct = AiThresholds.ALWAYS)
        assertEquals("u80c0", AiPages.handles(listOf(clean, descreen, up), t))
        assertEquals("u80", AiPages.handles(listOf(up), t))
        assertEquals("c0", AiPages.handles(listOf(clean), t))
        assertEquals("", AiPages.handles(listOf(descreen), t))
        assertTrue(AiPages.handles(listOf(up), t) != AiPages.handles(listOf(up), AiThresholds(upscalePct = 81)))
    }

    @Test
    fun aRunKeyNamesTheModelsThatRanAndTheirStepsFollowFromIt() {
        assertEquals("r0,2", AiPages.runKey(listOf(0, 2)))
        assertEquals("r", AiPages.runKey(emptyList()))
        val models = listOf(up, clean, up)
        val steps = AiPages.steps(models, 1000, 1500, shown(150))
        assertEquals("the second upscale would show at 75 %", listOf(0, 1), steps.map { it.index })
        assertEquals(listOf(ChainStep(0, 1_500_000), ChainStep(1, 6_000_000)), AiPages.stepsOf(listOf(0, 1), models, 1000, 1500))
        assertEquals(steps, AiPages.stepsOf(steps.map { it.index }, models, 1000, 1500))
    }
}
