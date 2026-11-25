package minty.emanata.reader

import java.util.Random
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/** Generated pictures for slice and seam tests: gray ARGB pixels, row by row. */
object SyntheticStrips {

    /**
     * A picture [width] x [height] whose shading runs on smoothly down its whole length,
     * like one tall strip, from a few waves chosen by [seed]. Rows in [blank] are white,
     * as the gaps between panels.
     */
    fun strip(width: Int, height: Int, seed: Long, blank: List<IntRange> = emptyList()): IntArray {
        val r = Random(seed)
        val waves = List(5) {
            doubleArrayOf(r.nextDouble() * 0.15 + 0.02, r.nextDouble() * 0.02 + 0.002, r.nextDouble() * 2 * PI, r.nextDouble() * 30 + 15)
        }
        val out = IntArray(width * height)
        for (y in 0 until height) {
            val white = blank.any { y in it }
            for (x in 0 until width) {
                var v = 128.0
                if (white) v = 255.0 else for (w in waves) v += w[3] * sin(x * w[0] + y * w[1] + w[2])
                out[y * width + x] = gray(v)
            }
        }
        return out
    }

    /** Rows [from] until [until] of a [width]-wide picture [pixels]. */
    fun rows(pixels: IntArray, width: Int, from: Int, until: Int): IntArray = pixels.copyOfRange(from * width, until * width)

    /** [pixels] cut into slices of [heights], in order. */
    fun cut(pixels: IntArray, width: Int, heights: List<Int>): List<IntArray> {
        var at = 0
        return heights.map { h -> rows(pixels, width, at, at + h).also { at += h } }
    }

    private fun gray(v: Double): Int {
        val g = v.roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (g shl 16) or (g shl 8) or g
    }
}
