package minty.emanata.reader

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Compares the edges of two pages to tell whether the picture runs on from one into the
 * other: the bottom of one slice and the top of the next, or (for spread pairing) the
 * facing sides of two pages. An edge is a [Profile]: the mean luma of its outermost
 * rows, at [LENGTH] points along it. Edges with too little in them (blank gutters, flat
 * color) say nothing and are left out ([informative]). Free of Android types.
 */
object Seams {
    /** Points along an edge. */
    const val LENGTH = 192

    /** Rows (or columns) averaged into an edge, from the outermost in. */
    const val ROWS = 2

    /** Below this spread of luma, in 0..255, an edge is too flat to compare. */
    const val MIN_STD = 10.0

    /** The most the edges of a seam that runs on differ, in mean luma steps. */
    const val MAX_DISTANCE = 12.0

    /** The least the edges of a seam that runs on correlate. */
    const val MIN_CORRELATION = 0.8

    /** How far, in points, one edge may be shifted along the other. */
    const val MAX_SHIFT = 3

    /** An edge: luma 0..255 at [LENGTH] points along it. */
    class Profile(val values: FloatArray) {
        val mean: Double = values.average()
        val std: Double = sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
    }

    /** How two edges compare: [distance] in mean luma steps and [correlation], at their best [shift]. */
    data class Match(val distance: Double, val correlation: Double, val shift: Int) {
        /** True when the picture runs on across the seam. */
        val runsOn: Boolean get() = distance <= MAX_DISTANCE && correlation >= MIN_CORRELATION
    }

    /**
     * The edge of a [width] x [height] image of ARGB [pixels] along its bottom ([bottom]) or
     * top: the mean luma of its [rows] outermost rows, area-averaged down (or spread) to
     * [LENGTH] points. Null for an empty image.
     */
    fun profile(pixels: IntArray, width: Int, height: Int, bottom: Boolean, rows: Int = ROWS): Profile? {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return null
        val n = rows.coerceIn(1, height)
        val sums = DoubleArray(width)
        for (r in 0 until n) {
            val y = if (bottom) height - 1 - r else r
            for (x in 0 until width) sums[x] += luma(pixels[y * width + x])
        }
        for (x in 0 until width) sums[x] /= n
        return Profile(resample(sums))
    }

    /** True when [p] holds enough of a picture to compare. */
    fun informative(p: Profile): Boolean = p.std >= MIN_STD

    /** How [a] and [b] compare at the shift of up to [MAX_SHIFT] points that brings them closest. */
    fun compare(a: Profile, b: Profile): Match {
        var best = Match(Double.MAX_VALUE, 0.0, 0)
        for (shift in -MAX_SHIFT..MAX_SHIFT) {
            val m = at(a.values, b.values, shift)
            if (m.distance < best.distance) best = m
        }
        return best
    }

    /** True when the picture runs on from edge [a] into edge [b]; null when either is too flat to say. */
    fun runsOn(a: Profile, b: Profile): Boolean? =
        if (!informative(a) || !informative(b)) null else compare(a, b).runsOn

    private fun at(a: FloatArray, b: FloatArray, shift: Int): Match {
        val from = maxOf(0, -shift)
        val to = minOf(a.size, b.size - shift)
        val n = to - from
        if (n <= 0) return Match(Double.MAX_VALUE, 0.0, shift)
        var sumA = 0.0
        var sumB = 0.0
        var diff = 0.0
        for (i in from until to) {
            sumA += a[i]
            sumB += b[i + shift]
            diff += abs(a[i] - b[i + shift])
        }
        val meanA = sumA / n
        val meanB = sumB / n
        var cov = 0.0
        var varA = 0.0
        var varB = 0.0
        for (i in from until to) {
            val da = a[i] - meanA
            val db = b[i + shift] - meanB
            cov += da * db
            varA += da * da
            varB += db * db
        }
        val r = if (varA <= 0.0 || varB <= 0.0) 0.0 else cov / sqrt(varA * varB)
        return Match(diff / n, r, shift)
    }

    /** [values] area-averaged (or spread) to [LENGTH] points. */
    internal fun resample(values: DoubleArray): FloatArray {
        val out = FloatArray(LENGTH)
        val scale = values.size.toDouble() / LENGTH
        for (i in 0 until LENGTH) {
            val start = i * scale
            val end = start + scale
            var sum = 0.0
            var weight = 0.0
            var x = start.toInt()
            while (x < end && x < values.size) {
                val w = minOf(end, x + 1.0) - maxOf(start, x.toDouble())
                if (w > 0) {
                    sum += values[x] * w
                    weight += w
                }
                x++
            }
            out[i] = if (weight > 0) (sum / weight).toFloat() else values[(start.roundToInt()).coerceIn(0, values.size - 1)].toFloat()
        }
        return out
    }

    internal fun luma(argb: Int): Double =
        0.299 * ((argb shr 16) and 0xFF) + 0.587 * ((argb shr 8) and 0xFF) + 0.114 * (argb and 0xFF)
}
