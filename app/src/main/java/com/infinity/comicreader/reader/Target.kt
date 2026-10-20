package com.infinity.comicreader.reader

import kotlin.math.roundToInt
import kotlin.math.sqrt

/** How a decode maps its source onto the [Target] box. */
enum class Fit {
    /** Keeps the aspect ratio and fits inside the box, enlarging small images too. */
    INSIDE,

    /** Like [INSIDE], but never enlarges. */
    SHRINK,

    /** Keeps the aspect ratio and fills the box exactly, cropping the overflow evenly from both sides. */
    COVER,

    /** Fills the box exactly, ignoring the aspect ratio. */
    STRETCH
}

/**
 * The bitmap a decode produces: the source (or, with [autoCrop], its content without
 * blank borders) mapped onto [width] x [height] as [fit] says, with Lanczos3 for any
 * change of size. A dimension of 0 or less leaves that axis free: `Target(1848, 0)`
 * fits the width, and both free keep the source size. [COVER][Fit.COVER] and
 * [STRETCH][Fit.STRETCH] need both dimensions and act as [INSIDE][Fit.INSIDE] without.
 *
 * [linear] shrinks in linear light; enlarging stays in sRGB, where Lanczos halos are
 * fainter. [capPixels] bounds the decoded source: a larger
 * image is first reduced by the decoder's own power-of-two sampling (see [Decoders]).
 * The result never exceeds [MAX_OUTPUT_PIXELS], nor [MAX_OUTPUT_SIDE] on either side.
 *
 * A data class, so a page's target can key a cache of decoded bitmaps.
 */
data class Target(
    val width: Int = 0,
    val height: Int = 0,
    val fit: Fit = Fit.INSIDE,
    val autoCrop: Boolean = false,
    val linear: Boolean = false,
    val capPixels: Long = DEFAULT_CAP_PIXELS
) {
    /** What to produce from a [srcW] x [srcH] source region at ([left], [top]), in source pixels. */
    fun plan(srcW: Double, srcH: Double, left: Double = 0.0, top: Double = 0.0): Plan {
        require(srcW > 0 && srcH > 0) { "Empty source" }
        val bw = width
        val bh = height
        val both = bw > 0 && bh > 0
        val plan = when {
            both && fit == Fit.STRETCH -> Plan(bw, bh, left, top, srcW, srcH)
            both && fit == Fit.COVER -> {
                // The axis needing the larger scale is filled; the other is cropped evenly.
                if (bw * srcH >= bh * srcW) {
                    val span = bh * srcW / bw
                    Plan(bw, bh, left, top + (srcH - span) / 2, srcW, span)
                } else {
                    val span = bw * srcH / bh
                    Plan(bw, bh, left + (srcW - span) / 2, top, span, srcH)
                }
            }
            else -> inside(srcW, srcH, left, top)
        }
        return plan.capped(MAX_OUTPUT_PIXELS, MAX_OUTPUT_SIDE)
    }

    /** [Fit.INSIDE] and [Fit.SHRINK]: the limiting axis lands exactly on the box. */
    private fun inside(srcW: Double, srcH: Double, left: Double, top: Double): Plan {
        val bw = width
        val bh = height
        val byWidth = when {
            bw <= 0 && bh <= 0 -> return native(srcW, srcH, left, top)
            bh <= 0 -> true
            bw <= 0 -> false
            else -> bw * srcH <= bh * srcW
        }
        val scale = if (byWidth) bw / srcW else bh / srcH
        if (fit == Fit.SHRINK && scale >= 1) return native(srcW, srcH, left, top)
        return if (byWidth) {
            val h = (srcH * scale).roundToInt().coerceAtLeast(1).let { if (bh > 0) it.coerceAtMost(bh) else it }
            Plan(bw, h, left, top, srcW, srcH)
        } else {
            val w = (srcW * scale).roundToInt().coerceAtLeast(1).let { if (bw > 0) it.coerceAtMost(bw) else it }
            Plan(w, bh, left, top, srcW, srcH)
        }
    }

    private fun native(srcW: Double, srcH: Double, left: Double, top: Double) =
        Plan(srcW.roundToInt().coerceAtLeast(1), srcH.roundToInt().coerceAtLeast(1), left, top, srcW, srcH)

    companion object {
        /**
         * Largest source decoded without reduction: 200 MB of ARGB, above any page a
         * scanner produces, so normally every pixel reaches the resampler.
         */
        const val DEFAULT_CAP_PIXELS = 50_000_000L

        /** Largest bitmap, in bytes, the platform draws: larger ones throw when drawn. */
        const val MAX_DRAW_BYTES = 100L * 1024 * 1024

        /** Largest result: [MAX_DRAW_BYTES] of ARGB. */
        const val MAX_OUTPUT_PIXELS = MAX_DRAW_BYTES / 4

        /** Longest side of a result: the largest texture the GPU uploads in one piece. */
        const val MAX_OUTPUT_SIDE = 16_384

        /** The source as it is. */
        val NATIVE = Target()
    }
}

/** A [width] x [height] result sampling the source rectangle at ([x], [y]) sized [spanW] x [spanH]. */
data class Plan(val width: Int, val height: Int, val x: Double, val y: Double, val spanW: Double, val spanH: Double) {

    /** True when the result is the [srcW] x [srcH] source, untouched. */
    fun isIdentity(srcW: Int, srcH: Int): Boolean = isCrop() && x == 0.0 && y == 0.0 && width == srcW && height == srcH

    /** True when neither axis is enlarged. */
    fun shrinks(): Boolean = width <= spanW && height <= spanH

    /** True when the result is a whole-pixel crop of the source, with no resampling. */
    fun isCrop(): Boolean =
        width.toDouble() == spanW && height.toDouble() == spanH && x == Math.floor(x) && y == Math.floor(y)

    /** Shrunk evenly, aspect kept, to at most [maxPixels] pixels and [maxSide] on either side. */
    fun capped(maxPixels: Long, maxSide: Int): Plan {
        val pixels = width.toLong() * height
        val f = minOf(
            if (pixels > maxPixels) sqrt(maxPixels.toDouble() / pixels) else 1.0,
            maxSide.toDouble() / maxOf(width, height)
        )
        if (f >= 1.0) return this
        return copy(width = (width * f).toInt().coerceAtLeast(1), height = (height * f).toInt().coerceAtLeast(1))
    }
}
