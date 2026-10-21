package com.infinity.comicreader.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeamsTest {

    private val width = 300

    private fun bottom(slice: IntArray, w: Int = width) = Seams.profile(slice, w, slice.size / w, bottom = true)!!

    private fun top(slice: IntArray, w: Int = width) = Seams.profile(slice, w, slice.size / w, bottom = false)!!

    @Test
    fun aStripCutInSlicesRunsOnAcrossEverySeam() {
        val slices = SyntheticStrips.cut(SyntheticStrips.strip(width, 2400, seed = 1), width, listOf(500, 700, 400, 800))
        for (k in 0 until slices.size - 1) {
            val m = Seams.compare(bottom(slices[k]), top(slices[k + 1]))
            assertTrue("seam $k: $m", m.runsOn)
            assertEquals(true, Seams.runsOn(bottom(slices[k]), top(slices[k + 1])))
        }
    }

    @Test
    fun unrelatedPagesDoNotRunOn() {
        for (seed in 2L..9L) {
            val a = SyntheticStrips.strip(width, 600, seed)
            val b = SyntheticStrips.strip(width, 600, seed + 100)
            assertEquals("seed $seed", false, Seams.runsOn(bottom(a), top(b)))
        }
    }

    @Test
    fun aBlankGapSaysNothing() {
        val strip = SyntheticStrips.strip(width, 1200, seed = 3, blank = listOf(560..640))
        val (upper, lower) = SyntheticStrips.cut(strip, width, listOf(600, 600))
        assertFalse(Seams.informative(bottom(upper)))
        assertNull(Seams.runsOn(bottom(upper), top(lower)))
    }

    @Test
    fun aSmallShiftAlongTheEdgeStillMatches() {
        val wide = width + 4
        val strip = SyntheticStrips.strip(wide, 1000, seed = 4)
        // The lower slice is taken a few pixels further right, as a slightly different crop would leave it.
        val upper = IntArray(width * 500) { strip[(it / width) * wide + it % width] }
        val lower = IntArray(width * 500) { strip[(500 + it / width) * wide + it % width + 4] }
        val m = Seams.compare(bottom(upper), top(lower))
        assertTrue(m.toString(), m.runsOn)
        assertNotEquals(0, m.shift)
    }

    @Test
    fun edgesAreSampledToOneLength() {
        for (w in listOf(1, 50, Seams.LENGTH, 1000)) {
            val p = Seams.profile(IntArray(w * 3) { 0xFF808080.toInt() }, w, 3, bottom = true)
            assertNotNull(p)
            assertEquals(Seams.LENGTH, p!!.values.size)
            assertEquals(128.0, p.mean, 0.01)
            assertEquals(0.0, p.std, 0.01)
        }
        assertNull(Seams.profile(IntArray(0), 0, 0, bottom = true))
    }

    @Test
    fun theOutermostRowsMakeTheEdge() {
        // Black on top, white at the bottom.
        val w = 10
        val pixels = IntArray(w * 4) { if (it < w * 2) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        assertEquals(255.0, bottom(pixels, w).mean, 0.01)
        assertEquals(0.0, top(pixels, w).mean, 0.01)
    }
}
