package com.infinity.comicreader.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.math.MathContext
import kotlin.math.abs

class ScalingTest {

    /** sin(x) by its Taylor series in 40-digit decimals: a reference independent of [Math.sin]. */
    private fun sin(x: BigDecimal): BigDecimal {
        val mc = MathContext(40)
        var term = x
        var sum = x
        var n = 1
        while (term.abs() > BigDecimal("1e-35")) {
            term = term.negate().multiply(x, mc).multiply(x, mc).divide(BigDecimal((2 * n) * (2 * n + 1)), mc)
            sum = sum.add(term, mc)
            n++
        }
        return sum
    }

    private val pi = BigDecimal("3.141592653589793238462643383279502884197")

    /** `sinc(x) * sinc(x / r)` from the decimal series. */
    private fun lanczos(r: Int, x: Double): Double {
        if (x == 0.0) return 1.0
        if (abs(x) >= r) return 0.0
        val mc = MathContext(40)
        val px = pi.multiply(BigDecimal(x), mc)
        val pxr = px.divide(BigDecimal(r), mc)
        return sin(px).divide(px, mc).multiply(sin(pxr).divide(pxr, mc), mc).toDouble()
    }

    /** Offsets across each kernel's whole support, including its ends and both sides of zero. */
    private fun offsets(radius: Int) = (-radius * 64..radius * 64).map { it / 64.0 } + listOf(1e-9, -1e-9, 2.9999, 5.9999)

    @Test
    fun lanczosKernelsMatchADecimalReference() {
        for ((filter, r) in listOf(ScaleFilter.LANCZOS3 to 3, ScaleFilter.LANCZOS6 to 6)) {
            for (x in offsets(r)) {
                assertEquals("$filter at $x", lanczos(r, x), filter.weight(x), 1e-12)
            }
        }
        // Lanczos6's lobes past the fourth exist and alternate in sign.
        assertTrue(ScaleFilter.LANCZOS6.weight(4.5) > 0 && ScaleFilter.LANCZOS6.weight(5.5) < 0)
        assertEquals(0.0, ScaleFilter.LANCZOS6.weight(6.0), 0.0)
    }

    @Test
    fun cubicKernelsMatchTheirClosedForms() {
        val hermite = ScaleFilter.HERMITE
        assertEquals(1.0, hermite.weight(0.0), 0.0)
        assertEquals(0.5, hermite.weight(0.5), 1e-15)
        assertEquals(0.5, hermite.weight(-0.5), 1e-15)
        assertEquals(0.0, hermite.weight(1.0), 0.0)
        val catmullRom = ScaleFilter.CATMULL_ROM
        assertEquals(1.0, catmullRom.weight(0.0), 0.0)
        assertEquals(0.5625, catmullRom.weight(0.5), 1e-15)
        assertEquals(-0.0625, catmullRom.weight(1.5), 1e-15)
        assertEquals(0.0, catmullRom.weight(1.0), 1e-15)
        assertEquals(0.0, catmullRom.weight(2.0), 0.0)
    }

    @Test
    fun cubicsInterpolateAndSumToOne() {
        for (filter in listOf(ScaleFilter.HERMITE, ScaleFilter.CATMULL_ROM)) {
            for (step in 0 until 64) {
                val f = step / 64.0
                val sum = (-filter.radius..filter.radius + 1).sumOf { filter.weight(it - f) }
                assertEquals("$filter at $f", 1.0, sum, 1e-12)
            }
        }
    }

    @Test
    fun settingsKeys() {
        assertEquals(ScaleFilter.LANCZOS3, ScaleFilter.of(null))
        assertEquals(ScaleFilter.LANCZOS6, ScaleFilter.of("lanczos6"))
        assertEquals(ScaleFilter.CATMULL_ROM, ScaleFilter.of("bicubic"))
        assertEquals("", Scaling.DEFAULT.shrinkKey)
        assertTrue(Scaling(ScaleFilter.HERMITE).shrinkKey.isNotEmpty())
        assertTrue(Scaling.PLAIN.shrinkKey != Scaling.DEFAULT.shrinkKey)
        // Only what shrinking uses counts.
        assertEquals("", Scaling(upRing = 10).shrinkKey)
        assertEquals(Scaling(ScaleFilter.LANCZOS6, 0, 30).shrinkKey, Scaling(ScaleFilter.LANCZOS6, 80, 30).shrinkKey)
        assertEquals(0.5f, Scaling.DEFAULT.ring(enlarged = false))
        assertEquals(0f, Scaling.DEFAULT.ring(enlarged = true))
        assertThrows(IllegalArgumentException::class.java) { Scaling(upRing = 101) }
    }
}
