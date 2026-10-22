package com.infinity.comicreader.reader

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Tells whether two pages side by side are the halves of one picture: the right edge of
 * the left page runs on into the left edge of the right page. Each side of a page is an
 * [Edge]: the color of its outermost [BAND] columns and of the [BAND] columns next to
 * them, at [Seams.LENGTH] points down the page. A side with too little in it (a white or
 * dark gutter, flat color, a lone line) says nothing ([informative]). Otherwise the two
 * facing edges must agree in color, within a fixed limit or about as well as each edge
 * agrees with the columns just inside it, and in shape: the same rises and falls of
 * brightness down the page ([compare]). Free of Android types.
 */
object SpreadSeams {
    /** Columns of a side strip, at the scale it is decoded at. */
    const val COLUMNS = 8

    /** Columns averaged into one line of an edge. */
    const val BAND = 2

    /** Below this spread of luma, in 0..255, a side is too flat to compare. */
    const val MIN_STD = 10.0

    /** The least share of a side that must stand out from its most common color. */
    const val MIN_BUSY = 0.15

    /** How far, in 0..255 on any channel, a point stands out from the side's median color. */
    const val BUSY_STEP = 16.0

    /** The most two facing edges differ, in mean steps per channel, and still run on. */
    const val MAX_DISTANCE = 12.0

    /** The most two facing edges differ relative to how each differs from the columns inside it. */
    const val MAX_RATIO = 1.6

    /**
     * The least the brightness of two facing edges correlates down the page: [MIN_CORRELATION],
     * or less where each edge correlates less with the columns inside it ([CORRELATION_SHARE]
     * of that), never below [CORRELATION_FLOOR].
     */
    const val MIN_CORRELATION = 0.75
    const val CORRELATION_SHARE = 0.85
    const val CORRELATION_FLOOR = 0.4

    /** The least the rises and falls of brightness down two facing edges correlate; lowered as [MIN_CORRELATION] is. */
    const val MIN_GRADIENT = 0.3
    const val GRADIENT_SHARE = 0.6
    const val GRADIENT_FLOOR = 0.15

    /** How far, in points, one edge may be shifted along the other. */
    const val MAX_SHIFT = 3

    /** Confidence above which a single match is trusted alone. */
    const val STRONG = 0.6

    enum class Seam { MATCH, NO_MATCH, UNKNOWN }

    /** What a seam is, and how sure: 0..1, 0 for [Seam.UNKNOWN]. */
    data class Verdict(val seam: Seam, val confidence: Double = 0.0) {
        val match: Boolean get() = seam == Seam.MATCH

        companion object {
            val UNKNOWN = Verdict(Seam.UNKNOWN)
        }
    }

    /**
     * One side of a page: red, green and blue at [Seams.LENGTH] points down its [outer]
     * [BAND] columns and the [inner] [BAND] next to them, interleaved.
     */
    class Edge(val outer: FloatArray, val inner: FloatArray) {
        /** Brightness down the outer columns. */
        val luma = FloatArray(Seams.LENGTH) { lumaAt(outer, it) }

        /** Brightness down the inner columns. */
        val innerLuma = FloatArray(Seams.LENGTH) { lumaAt(inner, it) }
        val std: Double = luma.average().let { m -> sqrt(luma.sumOf { (it - m) * (it - m) } / luma.size) }

        /** Share of points whose color stands out from the side's median color by [BUSY_STEP]. */
        val busy: Double = run {
            val median = FloatArray(3) { c -> FloatArray(Seams.LENGTH) { outer[it * 3 + c] }.sorted()[Seams.LENGTH / 2] }
            (0 until Seams.LENGTH).count { i -> (0 until 3).any { c -> abs(outer[i * 3 + c] - median[c]) > BUSY_STEP } }.toDouble() / Seams.LENGTH
        }
    }

    /** The two sides of one page. */
    class Sides(val left: Edge, val right: Edge)

    /**
     * Both sides of a [width] x [height] image of ARGB [pixels], each from its outermost
     * columns ([edge]); null for an empty image.
     */
    fun sides(pixels: IntArray, width: Int, height: Int): Sides? {
        val left = edge(pixels, width, height, right = false) ?: return null
        val right = edge(pixels, width, height, right = true) ?: return null
        return Sides(left, right)
    }

    /**
     * The [right] or left side of a [width] x [height] image of ARGB [pixels]: its outermost
     * [BAND] columns and the [BAND] next in, area-averaged down to [Seams.LENGTH] points.
     * A strip narrower than two bands uses what it has. Null for an empty image.
     */
    fun edge(pixels: IntArray, width: Int, height: Int, right: Boolean): Edge? {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return null
        val outer = minOf(BAND, width)
        val innerFrom = minOf(BAND, width - 1)
        val innerTo = minOf(2 * BAND, width).coerceAtLeast(innerFrom + 1)
        return Edge(line(pixels, width, height, right, 0, outer), line(pixels, width, height, right, innerFrom, innerTo))
    }

    /** Mean color of columns [from] until [to], counted in from the [right] or left side, at [Seams.LENGTH] points. */
    private fun line(pixels: IntArray, width: Int, height: Int, right: Boolean, from: Int, to: Int): FloatArray {
        val channels = Array(3) { DoubleArray(height) }
        val n = to - from
        for (y in 0 until height) {
            var r = 0.0
            var g = 0.0
            var b = 0.0
            for (k in from until to) {
                val p = pixels[y * width + if (right) width - 1 - k else k]
                r += (p shr 16) and 0xFF
                g += (p shr 8) and 0xFF
                b += p and 0xFF
            }
            channels[0][y] = r / n
            channels[1][y] = g / n
            channels[2][y] = b / n
        }
        val sampled = Array(3) { Seams.resample(channels[it]) }
        return FloatArray(Seams.LENGTH * 3) { sampled[it % 3][it / 3] }
    }

    /** True when [e] holds enough of a picture to compare: varied brightness over enough of it. */
    fun informative(e: Edge): Boolean = e.std >= MIN_STD && e.busy >= MIN_BUSY

    /**
     * The seam between pages [first] and [second], in reading order: shown left to right,
     * or right to left when [rtl].
     */
    fun verdict(first: Sides, second: Sides, rtl: Boolean): Verdict =
        if (rtl) compare(second.right, first.left) else compare(first.right, second.left)

    /**
     * Whether the picture runs on from [left], the right side of the page on the left, into
     * [right], the left side of the page on the right, at the shift of up to [MAX_SHIFT]
     * points that brings their colors closest.
     */
    fun compare(left: Edge, right: Edge): Verdict {
        if (!informative(left) || !informative(right)) return Verdict.UNKNOWN
        var best: Fit? = null
        for (shift in -MAX_SHIFT..MAX_SHIFT) {
            val f = fit(left.outer, left.luma, right.outer, right.luma, shift)
            if (best == null || f.distance < best.distance) best = f
        }
        val fit = best!!
        // A seam is two columns apart, as is each edge from the columns just inside it: busy
        // art differs and decorrelates that much within a page too, and may across the seam.
        val inLeft = fit(left.outer, left.luma, left.inner, left.innerLuma, 0)
        val inRight = fit(right.outer, right.luma, right.inner, right.innerLuma, 0)
        val ratio = fit.distance / maxOf((inLeft.distance + inRight.distance) / 2, 1.0)
        val minCorrelation = minOf(MIN_CORRELATION, CORRELATION_SHARE * (inLeft.correlation + inRight.correlation) / 2).coerceAtLeast(CORRELATION_FLOOR)
        val minGradient = minOf(MIN_GRADIENT, GRADIENT_SHARE * (inLeft.gradient + inRight.gradient) / 2).coerceAtLeast(GRADIENT_FLOOR)
        val error = minOf(fit.distance / MAX_DISTANCE, ratio / MAX_RATIO)
        val agrees = error <= 1.0 && fit.correlation >= minCorrelation
        return when {
            agrees && fit.gradient >= minGradient -> {
                val color = maxOf((MAX_RATIO - ratio) / (MAX_RATIO - 1), 1 - fit.distance / MAX_DISTANCE).coerceIn(0.0, 1.0)
                val shape = (fit.correlation - minCorrelation) / (1 - minCorrelation)
                val detail = (fit.gradient - minGradient) / (1 - minGradient)
                Verdict(Seam.MATCH, ((color + shape + detail) / 3).coerceIn(0.0, 1.0))
            }
            // Alike in color and overall shading, without detail to tell: a shared sky.
            agrees -> Verdict.UNKNOWN
            else -> Verdict(
                Seam.NO_MATCH,
                maxOf((error - 1) / 2, (minCorrelation - fit.correlation) / minCorrelation).coerceIn(0.0, 1.0)
            )
        }
    }

    /** How two lines compare at one [shift]: mean color [distance], brightness [correlation] and that of its rises and falls ([gradient]). */
    private class Fit(val distance: Double, val correlation: Double, val gradient: Double)

    /** Colors [a] and [b] (with their brightness [la] and [lb]) compared with [b] moved [shift] points along. */
    private fun fit(a: FloatArray, la: FloatArray, b: FloatArray, lb: FloatArray, shift: Int): Fit {
        val from = maxOf(0, -shift)
        val to = minOf(Seams.LENGTH, Seams.LENGTH - shift)
        var diff = 0.0
        for (i in from until to) for (c in 0 until 3) diff += abs(a[i * 3 + c] - b[(i + shift) * 3 + c])
        val n = to - from
        val ya = FloatArray(n) { la[from + it] }
        val yb = FloatArray(n) { lb[from + shift + it] }
        val ga = FloatArray(n - 1) { ya[it + 1] - ya[it] }
        val gb = FloatArray(n - 1) { yb[it + 1] - yb[it] }
        return Fit(diff / (n * 3), correlation(ya, yb), correlation(ga, gb))
    }

    private fun correlation(a: FloatArray, b: FloatArray): Double {
        val ma = a.average()
        val mb = b.average()
        var cov = 0.0
        var va = 0.0
        var vb = 0.0
        for (i in a.indices) {
            val da = a[i] - ma
            val db = b[i] - mb
            cov += da * db
            va += da * da
            vb += db * db
        }
        return if (va <= 0.0 || vb <= 0.0) 0.0 else cov / sqrt(va * vb)
    }

    private fun lumaAt(rgb: FloatArray, i: Int): Float =
        (0.299 * rgb[i * 3] + 0.587 * rgb[i * 3 + 1] + 0.114 * rgb[i * 3 + 2]).toFloat()
}
