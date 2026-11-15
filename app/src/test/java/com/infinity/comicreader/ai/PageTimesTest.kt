package com.infinity.comicreader.ai

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.math.roundToLong

/** Page times learned on this device: kept by model and its files, blended page by page, written only on a real change, read back by a new instance; unlearned ones scaled alike. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PageTimesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val upscale = ModelCatalog.byId(ModelCatalog.DEFAULT_UPSCALE)!!
    private val descreen = ModelCatalog.forStep(AiStep.DESCREEN).first()

    @Test
    fun aTimeIsKeptPerModelAndReadBack() {
        val file = File(tmp.root, "times")
        val times = PageTimes(file)
        assertNull(times.pageMs(upscale))
        times.keep(upscale, 4_200)
        times.keep(descreen, 9_000)
        assertEquals(4_200L, times.pageMs(upscale))
        val again = PageTimes(file)
        assertEquals(4_200L, again.pageMs(upscale))
        assertEquals(9_000L, again.pageMs(descreen))
    }

    @Test
    fun laterPagesMoveTheTimeAQuarterOfTheWayWrittenOnlyOnALargerMove() {
        val file = File(tmp.root, "times")
        val times = PageTimes(file)
        times.keep(upscale, 4_000)
        val written = file.lastModified()
        file.setLastModified(written - 10_000)
        // 4000 + (4400 - 4000) / 4: 2.5 % from what was written.
        times.keep(upscale, 4_400)
        assertEquals(4_100L, times.pageMs(upscale))
        assertEquals("not written", written - 10_000, file.lastModified())
        assertEquals(4_000L, PageTimes(file).pageMs(upscale))
        // 4100 + (5300 - 4100) / 4 = 4400: 10 % from 4000.
        times.keep(upscale, 5_300)
        assertEquals(4_400L, times.pageMs(upscale))
        assertEquals(4_400L, PageTimes(file).pageMs(upscale))
        times.keep(upscale, 0)
        assertEquals("nothing to learn from", 4_400L, times.pageMs(upscale))
    }

    @Test
    fun oneSlowPageDoesNotReplaceATimeKeptBefore() {
        val file = File(tmp.root, "times")
        PageTimes(file).keep(upscale, 4_000)
        // A later reader's first page, taken warm.
        val later = PageTimes(file)
        later.keep(upscale, 8_000)
        assertEquals(5_000L, later.pageMs(upscale))
    }

    @Test
    fun modelsNotLearnedYetAreScaledAsTheLearnedOnesRan() {
        val times = PageTimes(File(tmp.root, "times"))
        assertEquals("the catalog's before any", descreen.pageMs, times.estimate(descreen))
        times.keep(upscale, upscale.pageMs * 3 / 2)
        assertEquals(1.5, times.ratio(), 0.001)
        assertEquals(upscale.pageMs * 3 / 2, times.estimate(upscale))
        assertEquals(Math.round(descreen.pageMs * times.ratio()), times.estimate(descreen))
        // The median of the ratios learned.
        val artifacts = ModelCatalog.forStep(AiStep.ARTIFACTS).first()
        times.keep(descreen, descreen.pageMs * 2)
        times.keep(artifacts, artifacts.pageMs)
        assertEquals(1.5, times.ratio(), 0.001)
    }

    @Test
    fun anImportedModelsOwnTimeStandsUnscaledAndScalesNoOther() {
        val times = PageTimes(File(tmp.root, "times"))
        val imported = UserModel("u1", "Mine", AiStep.UPSCALE, 2, 6_000, 100, 1_000, "a".repeat(64), "b".repeat(64))
        times.keep(upscale, upscale.pageMs * 2)
        assertEquals("measured here: not scaled again", 6_000L, times.estimate(imported))
        // Learned three times faster than its own measure: the catalog's ratio stays 2.
        times.keep(imported, 2_000)
        assertEquals(2_000L, times.estimate(imported))
        assertEquals(2.0, times.ratio(), 0.001)
        assertEquals(descreen.pageMs * 2, times.estimate(descreen))
    }

    @Test
    fun shaderChainsAndNcnnModelsScaleByTheirOwnEngine() {
        val times = PageTimes(File(tmp.root, "times"))
        val chain = ShaderCatalog.byId("anime4k-a-s")!!
        val other = ShaderCatalog.byId("anime4k-c-l")!!
        // One engine learned: the other scales by it, a device's speed counting for both, less the shader times' warmth.
        times.keep(upscale, upscale.pageMs * 2)
        assertEquals(2.0 / ShaderCatalog.WARM, times.ratio(PageTimes.Engine.SHADER), 0.001)
        assertEquals((other.pageMs * (2.0 / ShaderCatalog.WARM)).roundToLong(), times.estimate(other))
        // Once learned, each engine scales by its own.
        times.keep(chain, chain.pageMs / 2)
        assertEquals(0.5, times.ratio(PageTimes.Engine.SHADER), 0.001)
        assertEquals(2.0, times.ratio(PageTimes.Engine.NCNN), 0.001)
        assertEquals(other.pageMs / 2, times.estimate(other))
        assertEquals(descreen.pageMs * 2, times.estimate(descreen))
    }

    @Test
    fun onlyShaderChainsLearnedScaleNcnnModelsByTheirWarmth() {
        val times = PageTimes(File(tmp.root, "times"))
        val chain = ShaderCatalog.byId("anime4k-a-ul")!!
        // Ran cool at its catalog time over the warmth: this device is the reference tablet.
        times.keep(chain, (chain.pageMs / ShaderCatalog.WARM).roundToLong())
        assertEquals(1.0, times.ratio(PageTimes.Engine.NCNN), 0.01)
        assertEquals(descreen.pageMs.toDouble(), times.estimate(descreen).toDouble(), descreen.pageMs * 0.01)
    }

    @Test
    fun newFilesForgetTheOldTime() {
        val times = PageTimes(File(tmp.root, "times"))
        times.keep(upscale, 4_000)
        val replaced = upscale.copy(bin = upscale.bin.copy(sha256 = "0".repeat(64)))
        assertNull(times.pageMs(replaced))
        times.keep(replaced, 4_100)
        assertEquals("the replaced model's own, though close", 4_100L, times.pageMs(replaced))
        assertNull(times.pageMs(upscale))
    }

    @Test
    fun anUnreadableFileReadsAsNoneLearned() {
        val file = File(tmp.root, "times")
        file.writeText("garbage\n${upscale.id}\t${upscale.fingerprint}\tnot-a-number\t3000\n${descreen.id}\t${descreen.fingerprint}\t5000\t4000\n")
        val times = PageTimes(file)
        assertNull(times.pageMs(upscale))
        assertEquals(5_000L, times.pageMs(descreen))
    }
}
