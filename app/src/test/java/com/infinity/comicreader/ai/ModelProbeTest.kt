package com.infinity.comicreader.ai

import android.app.Application
import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

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
    fun aReachBeyondTheLargestProbeTakesAllOfIt() {
        // Nearer taps too, as a real model's context is dense: a tile smaller than the reach still sees some of it.
        val g = ModelProbe.measure(FakeModel(scale = 1, reach = 100, taps = intArrayOf(8, 40, 100)))
        assertEquals(ModelProbe.MAX_PROBE / 4 + ModelProbe.SAFETY, g.context)
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
        val worst = ModelProbe.cut(FakeModel(scale = 2, reach = 5), 64, 2, 0, ModelProbe.Pattern.NOISE)
        assertTrue(worst.take(5).any { it > ModelProbe.TOLERANCE })
        assertTrue(worst.drop(5).all { it == 0 })
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
}
