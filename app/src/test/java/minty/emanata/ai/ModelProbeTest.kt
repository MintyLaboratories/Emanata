package minty.emanata.ai

import android.app.Application
import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CancellationException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ModelProbeTest {

    @Test
    fun measuresAPaddedModel() {
        for (scale in 1..4) {
            val g = ModelProbe.measure(FakeModel(scale = scale, reach = 3, padded = true))
            assertEquals(Geometry(scale, 0, 3 + ModelProbe.SAFETY), g)
        }
    }

    @Test
    fun measuresAModelThatTrimsItsEdges() {
        for (scale in 1..3) {
            val g = ModelProbe.measure(FakeModel(scale = scale, reach = 4, padded = false))
            // Its output never sees past the tile, so the cut changes nothing: the trim alone keeps seams away.
            assertEquals(Geometry(scale, 4 * scale, ModelProbe.SAFETY), g)
            assertEquals(4, g.margin)
        }
    }

    @Test
    fun aModelWithoutReachNeedsOnlyTheSafety() {
        assertEquals(Geometry(2, 0, ModelProbe.SAFETY), ModelProbe.measure(FakeModel(scale = 2, reach = 0)))
    }

    @Test
    fun aReachTouchingTheProbesEdgeIsMeasuredOnALargerTile() {
        val model = FakeModel(scale = 1, reach = 40)
        assertEquals(Geometry(1, 0, 40 + ModelProbe.SAFETY), ModelProbe.measure(model))
        assertTrue("probed only at ${ModelProbe.FIRST_PROBE}", model.inputs.any { it.width > ModelProbe.FIRST_PROBE })
    }

    @Test
    fun aReachBeyondTheLargestProbeIsUnfit() {
        // Nearer taps too, as a real model's context is dense: a tile smaller than the reach still sees some of it.
        val model = FakeModel(scale = 1, reach = 100, taps = intArrayOf(8, 40, 100))
        assertThrows(ModelProbe.Unfit::class.java) { ModelProbe.measure(model) }
        assertTrue(model.inputs.any { it.width == ModelProbe.MAX_PROBE })
    }

    @Test
    fun measuringStopsOnceCanceled() {
        val model = FakeModel()
        assertThrows(CancellationException::class.java) { ModelProbe.measure(model, canceled = { model.runs.get() >= 2 }) }
        assertEquals(2, model.runs.get())
    }

    @Test
    fun aCleanModelPassesAtEverySize() {
        for (padded in listOf(true, false)) {
            val model = FakeModel(scale = 2, reach = 3, padded = padded)
            val g = ModelProbe.measure(model)
            for (size in listOf(64, 96, 192)) {
                val check = ModelProbe.verify(model, FakeModel(scale = 2, reach = 3, padded = padded, onGpu = false), g, size, 64)
                assertTrue("padded=$padded $size: $check", check.passes)
                assertEquals(0, check.seams.max)
                assertEquals(0, check.vsReference.max)
            }
        }
    }

    @Test
    fun roundingByPlaceInAnAlignedTileLeavesNoSeams() {
        val model = FakeModel(phaseNoise = true)
        val reference = FakeModel(onGpu = false)
        val check = ModelProbe.verify(model, reference, ModelProbe.measure(reference), 128, 128)
        assertTrue(check.toString(), check.passes)
        assertTrue(check.vsReference.noisy)
    }

    @Test
    fun roundingByTileSizeShowsAsNoisySeams() {
        val model = FakeModel(edgeNoise = true)
        val reference = FakeModel(onGpu = false)
        val check = ModelProbe.verify(model, reference, ModelProbe.measure(reference), 128, 128)
        assertFalse(check.passes)
        assertTrue(check.seams.toString(), check.seams.noisy)
        assertFalse(check.vsReference.wrong)
    }

    @Test
    fun aLargeTileGoneWrongDiffersFromTheReference() {
        val model = FakeModel(breaksAbove = 100)
        val reference = FakeModel(onGpu = false)
        val g = ModelProbe.measure(reference)
        val check = ModelProbe.verify(model, reference, g, 192, 64)
        assertTrue(check.vsReference.toString(), check.vsReference.wrong)
        assertFalse(check.passes)
        assertTrue(ModelProbe.verify(model, reference, g, 96, 64).passes)
    }

    @Test
    fun tilesGoneWrongByTheirContentShowAsSeams() {
        // Wrong only where a tile starts at an even column: the same pixels come out right in a tile starting elsewhere.
        val reference = FakeModel(onGpu = false)
        val g = ModelProbe.measure(reference)
        val flaky = object : TileModel by FakeModel() {
            private val inner = FakeModel()
            override fun run(src: android.graphics.Bitmap, input: Box, dst: android.graphics.Bitmap?, crop: Box?, dx: Int, dy: Int): Dims? {
                val d = inner.run(src, input, dst, crop, dx, dy)
                if (dst != null && crop != null && Math.floorMod(input.x, 16) == Math.floorMod(-g.margin, 16)) {
                    dst.setPixels(IntArray(crop.width * crop.height) { 0xFF000000.toInt() }, 0, crop.width, dx, dy, crop.width, crop.height)
                }
                return d
            }
        }
        val check = ModelProbe.verify(flaky, reference, g, 96, 96)
        assertTrue(check.seams.toString(), check.seams.wrong)
    }

    @Test
    fun outputUnlikeTheReferenceIsWrong() {
        val reference = FakeModel(onGpu = false)
        val check = ModelProbe.verify(FakeModel(wrong = true), reference, ModelProbe.measure(reference), 96, 96)
        assertTrue(check.vsReference.wrong)
        assertFalse(check.passes)
    }

    @Test
    fun differencesAreSortedBySizeAndMean() {
        assertTrue(ModelProbe.Diff(ModelProbe.TOLERANCE, 2.0).same)
        assertTrue(ModelProbe.Diff(ModelProbe.TOLERANCE + 1, 0.5).noisy)
        assertTrue(ModelProbe.Diff(ModelProbe.NOISE_MAX, ModelProbe.NOISE_MEAN).noisy)
        assertTrue(ModelProbe.Diff(ModelProbe.NOISE_MAX + 1, 0.1).wrong)
        assertTrue(ModelProbe.Diff(6, ModelProbe.NOISE_MEAN + 0.1).wrong)
        assertEquals("max 6, mean 0.25", ModelProbe.Diff(6, 0.25).toString())
    }

    @Test
    fun reachIsOnePastTheFurthestVisibleChange() {
        assertEquals(0, ModelProbe.reach(intArrayOf(0, 0, 0)))
        assertEquals(0, ModelProbe.reach(intArrayOf(ModelProbe.TOLERANCE, 1, 0)))
        assertEquals(1, ModelProbe.reach(intArrayOf(40, ModelProbe.TOLERANCE, 0)))
        assertEquals(4, ModelProbe.reach(intArrayOf(9, 0, 0, 3, 0)))
    }

    @Test
    fun theCutChangesOnlyPixelsWithinReach() {
        for (across in listOf(true, false)) {
            val worst = ModelProbe.cut(FakeModel(scale = 2, reach = 5), 128, 2, 0, ModelProbe.Pattern.NOISE, across)
            assertTrue(worst.take(5).any { it > ModelProbe.TOLERANCE })
            assertTrue(worst.drop(5).all { it == 0 })
        }
    }

    @Test
    fun theSwapChangesOnlyPixelsWithinReach() {
        for (across in listOf(true, false)) {
            val worst = ModelProbe.swap(FakeModel(scale = 2, reach = 5), 128, 2, 0, ModelProbe.Pattern.NOISE, across)
            assertTrue(worst.take(5).any { it > 0 })
            assertTrue(worst.drop(5).all { it == 0 })
        }
    }

    /** A 1x model mixing in a faint share (at most 2 codes) of the pixel 12 to the right. */
    private val faint = object : TileModel {
        override val onGpu = true

        override fun run(src: Bitmap, input: Box, dst: Bitmap?, crop: Box?, dx: Int, dy: Int): Dims? {
            val n = input.width
            if (dst != null) {
                val c = crop!!
                fun at(px: Int, py: Int) = src.getPixel((input.x + px).coerceIn(0, src.width - 1), (input.y + py).coerceIn(0, src.height - 1))
                for (y in 0 until c.height) for (x in 0 until c.width) {
                    val a = at(c.x + x, c.y + y)
                    val b = at(minOf(c.x + x + 12, n - 1), c.y + y)
                    fun ch(s: Int) = (((a shr s) and 0xFF) * 254 + ((b shr s) and 0xFF) * 2) / 256
                    dst.setPixel(dx + x, dy + y, (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0))
                }
            }
            return Dims(n, input.height)
        }

        override fun trim() {}

        override fun close() {}
    }

    @Test
    fun anExactProbeFindsContextTooFaintForTheTolerance() {
        assertEquals("hidden by the tolerance", Geometry(1, 0, ModelProbe.SAFETY), ModelProbe.measure(faint))
        assertEquals(Geometry(1, 0, 12 + ModelProbe.SAFETY), ModelProbe.probeExact(faint).geometry)
    }

    @Test
    fun anExactProbeMeasuresAsTheCutDoesForPlainModels() {
        for (scale in 1..3) assertEquals(Geometry(scale, 0, 3 + ModelProbe.SAFETY), ModelProbe.probeExact(FakeModel(scale = scale, reach = 3)).geometry)
        assertEquals(Geometry(1, 0, 40 + ModelProbe.SAFETY), ModelProbe.probeExact(FakeModel(scale = 1, reach = 40)).geometry)
        assertThrows(ModelProbe.Unfit::class.java) { ModelProbe.probeExact(FakeModel(scale = 1, reach = 100, taps = intArrayOf(8, 40, 100))) }
    }

    @Test
    fun probesRunOnStripsNoWiderThanATile() {
        val model = FakeModel(scale = 1, reach = 40)
        ModelProbe.measure(model)
        assertTrue(model.inputs.any { it.width == ModelProbe.MAX_PROBE } && model.inputs.any { it.height == ModelProbe.MAX_PROBE })
        assertTrue(model.inputs.all { it.width.toLong() * it.height <= ModelProbe.MAX_PROBE.toLong() * ModelProbe.STRIP })
    }

    @Test
    fun aReachAlongOneAxisOnlyIsMeasured() {
        // Blue mixed with the pixel 30 below only: a cut on the right alone would miss it.
        val vertical = object : TileModel by FakeModel(scale = 1, reach = 0) {
            override fun run(src: Bitmap, input: Box, dst: Bitmap?, crop: Box?, dx: Int, dy: Int): Dims? {
                if (dst == null) return Dims(input.width, input.height)
                val c = crop!!
                val tile = FakeModel.read(src, input)
                val row = IntArray(c.width)
                for (y in 0 until c.height) {
                    for (x in 0 until c.width) {
                        val px = c.x + x
                        val py = c.y + y
                        val far = if (py + 30 < input.height) tile[(py + 30) * input.width + px] else 0
                        row[x] = (0xFF shl 24) or (((tile[py * input.width + px] and 0xFF) + (far and 0xFF)) / 2)
                    }
                    dst.setPixels(row, 0, c.width, dx, dy + y, c.width, 1)
                }
                return Dims(input.width, input.height)
            }
        }
        assertEquals(30 + ModelProbe.SAFETY, ModelProbe.measure(vertical).context)
    }

    @Test
    fun aModelThatDoesNotRunIsUnfit() {
        assertThrows(ModelProbe.Unfit::class.java) { ModelProbe.measure(FakeModel(failAt = 0)) }
        assertThrows(ModelProbe.Unfit::class.java) { ModelProbe.measure(FakeModel(failAt = 3)) }
    }

    @Test
    fun anOutputNotAWholeMultipleIsUnfit() {
        val odd = object : TileModel {
            override val onGpu = false
            override fun run(src: Bitmap, input: Box, dst: Bitmap?, crop: Box?, dx: Int, dy: Int) =
                Dims(input.width * 3 / 2, input.height * 3 / 2)
            override fun trim() {}
            override fun close() {}
        }
        assertThrows(ModelProbe.Unfit::class.java) { ModelProbe.measure(odd) }
        val skewed = object : TileModel {
            override val onGpu = false
            override fun run(src: Bitmap, input: Box, dst: Bitmap?, crop: Box?, dx: Int, dy: Int) =
                Dims(input.width * 2, input.height * 2 - 2)
            override fun trim() {}
            override fun close() {}
        }
        assertThrows(ModelProbe.Unfit::class.java) { ModelProbe.measure(skewed) }
    }

    @Test
    fun unfitModelsSayWhy() {
        assertEquals(ModelRefusal.DOES_NOT_RUN, assertThrows(ModelProbe.Unfit::class.java) { ModelProbe.measure(FakeModel(failAt = 0)) }.reason)
        val far = FakeModel(scale = 1, reach = 100, taps = intArrayOf(8, 40, 100))
        assertEquals(ModelRefusal.REACH, assertThrows(ModelProbe.Unfit::class.java) { ModelProbe.measure(far) }.reason)
        val odd = object : TileModel {
            override val onGpu = false
            override fun run(src: Bitmap, input: Box, dst: Bitmap?, crop: Box?, dx: Int, dy: Int) = Dims(input.width * 3 / 2, input.height * 3 / 2)
            override fun trim() {}
            override fun close() {}
        }
        assertEquals(ModelRefusal.SHAPE, assertThrows(ModelProbe.Unfit::class.java) { ModelProbe.measure(odd) }.reason)
    }

    @Test
    fun probingCountsWorkingMemoryPerInputPixel() {
        assertEquals(1000L, ModelProbe.probe(FakeModel(scale = 2, reach = 3, bytesPerPixel = 1000)).bytesPerPixel)
        assertEquals("not counted", 0L, ModelProbe.probe(FakeModel(scale = 2, reach = 3)).bytesPerPixel)
    }

    @Test
    fun aProbeThatWouldPassItsBudgetIsNotRun() {
        // Reach 40 is measured on 256 x 64 strips, 164 MB at 10 KB per input pixel; 128 x 64 ones need 82 MB.
        val model = FakeModel(scale = 1, reach = 40, bytesPerPixel = 10_000)
        val e = assertThrows(ModelProbe.Unfit::class.java) { ModelProbe.probe(model, budgetBytes = 100L shl 20) }
        assertEquals(ModelRefusal.MEMORY, e.reason)
        assertEquals(256L * ModelProbe.STRIP * 10_000, e.bytes)
        assertTrue("ran past the 128 probe", model.inputs.none { maxOf(it.width, it.height) > 128 })
        val roomy = ModelProbe.probe(FakeModel(scale = 1, reach = 40, bytesPerPixel = 10_000), budgetBytes = 1L shl 30)
        assertEquals(Geometry(1, 0, 40 + ModelProbe.SAFETY), roomy.geometry)
    }

    @Test
    fun aCheckCountsTheMostATileHeld() {
        val reference = FakeModel(onGpu = false)
        val g = ModelProbe.measure(reference)
        val check = ModelProbe.verify(FakeModel(bytesPerPixel = 100), reference, g, 96, 96)
        assertEquals(96L * 96 * 100, check.peakBytes)
    }
}
