package minty.emanata.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BlurTest {

    @Test
    fun aFlatImageStaysFlat() {
        val px = IntArray(7 * 5) { 0xFF336699.toInt() }
        Blur.blur(px, 7, 5, 2)
        px.forEach { assertEquals(0xFF336699.toInt(), it) }
    }

    @Test
    fun aDotSpreadsEvenlyOnBothAxes() {
        val w = 21
        val px = IntArray(w * w) { 0xFF000000.toInt() }
        px[10 * w + 10] = 0xFFFFFFFF.toInt()
        Blur.blur(px, w, w, 2, passes = 1)
        fun red(x: Int, y: Int) = (px[y * w + x] shr 16) and 0xFF
        // One pass of radius 2 is a 5 x 5 box: 255 / 25, rounded.
        assertEquals(10, red(10, 10))
        assertEquals(10, red(8, 12))
        assertEquals(0, red(7, 10))
        assertEquals(red(10, 8), red(8, 10))
    }

    @Test
    fun edgesRepeatTheEdgePixel() {
        // Left half white, right half black: the far left column stays white.
        val w = 10
        val px = IntArray(w * 3) { if (it % w < 5) 0xFFFFFFFF.toInt() else 0xFF000000.toInt() }
        Blur.blur(px, w, 3, 2, passes = 1)
        assertEquals(0xFFFFFFFF.toInt(), px[0])
        assertEquals(0xFF000000.toInt(), px[w - 1])
        assertTrue(((px[5] shr 8) and 0xFF) in 1..254)
    }
}
