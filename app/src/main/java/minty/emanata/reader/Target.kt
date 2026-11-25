package minty.emanata.reader

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** The part of a source a decode shows: all of it, or its left or right half (one page of a spread in one file). */
enum class Half { WHOLE, LEFT, RIGHT }

/** A rectangle of a source as fractions of its width and height. */
data class Box(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    val width: Double get() = right - left
    val height: Double get() = bottom - top

    /** `[left, top, right, bottom]` in the pixels of a [w] x [h] source: the whole pixels it touches, at least one each way. */
    fun pixels(w: Int, h: Int): IntArray {
        val l = floor(left * w + EPSILON).toInt().coerceIn(0, w - 1)
        val t = floor(top * h + EPSILON).toInt().coerceIn(0, h - 1)
        val r = ceil(right * w - EPSILON).toInt().coerceIn(l + 1, w)
        val b = ceil(bottom * h - EPSILON).toInt().coerceIn(t + 1, h)
        return intArrayOf(l, t, r, b)
    }

    companion object {
        val FULL = Box(0.0, 0.0, 1.0, 1.0)

        /** Rounding noise a fraction may carry without taking in another pixel. */
        private const val EPSILON = 1e-9

        /** [bounds] (`[left, top, right, bottom]` pixels) of a [w] x [h] source as fractions; all of it when null. */
        fun of(bounds: IntArray?, w: Int, h: Int): Box =
            if (bounds == null || w <= 0 || h <= 0) FULL
            else Box(bounds[0].toDouble() / w, bounds[1].toDouble() / h, bounds[2].toDouble() / w, bounds[3].toDouble() / h)
    }
}

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
 * blank borders) mapped onto [width] x [height] as [fit] says, resampled as [scaling]
 * says for any change of size. A dimension of 0 or less leaves that axis free: `Target(1848, 0)`
 * fits the width, and both free keep the source size. [COVER][Fit.COVER] and
 * [STRETCH][Fit.STRETCH] need both dimensions and act as [INSIDE][Fit.INSIDE] without.
 *
 * [anchorTop] makes [COVER][Fit.COVER] keep the top of a source too tall for the
 * box, cropping only its bottom; sides are always cropped evenly.
 *
 * [half] shows one half of the source, split after any auto-crop ([area]). [box], when
 * set, is the crop to show in place of the one auto-crop would find: two pages side by
 * side share their top and bottom crop this way.
 *
 * [linear] shrinks in linear light; enlarging stays in sRGB, where Lanczos halos are
 * fainter. [capPixels] bounds the decoded source: a larger
 * image is first reduced by the decoder's own power-of-two sampling (see [Decoders]).
 * The result never exceeds [MAX_OUTPUT_PIXELS], nor [MAX_OUTPUT_SIDE] on either side.
 *
 * [banded] marks a page drawn in bands at its full size where it is large ([bandsAt]):
 * its bitmap is then a stand-in of at most [BASE_PIXELS], decoded from the source
 * sampled down to at most [BASE_DECODE_PIXELS] ([Decoders]).
 *
 * A data class, so a page's target can key a cache of decoded bitmaps.
 */
data class Target(
    val width: Int = 0,
    val height: Int = 0,
    val fit: Fit = Fit.INSIDE,
    val autoCrop: Boolean = false,
    val linear: Boolean = false,
    val capPixels: Long = DEFAULT_CAP_PIXELS,
    val anchorTop: Boolean = false,
    val half: Half = Half.WHOLE,
    val box: Box? = null,
    val scaling: Scaling = Scaling.PLAIN,
    val banded: Boolean = false
) {
    /**
     * `[left, top, right, bottom]` of a [srcW] x [srcH] source that this target shows: its
     * [box], else the content [bounds] auto-crop found in the same form (all of it when
     * null), then its [half], the left one taking the floor of an odd width.
     */
    fun area(srcW: Int, srcH: Int, bounds: IntArray? = null): IntArray {
        val crop = box?.pixels(srcW, srcH) ?: bounds
        var l = crop?.get(0) ?: 0
        val t = crop?.get(1) ?: 0
        var r = crop?.get(2) ?: srcW
        val b = crop?.get(3) ?: srcH
        when (half) {
            Half.LEFT -> r = l + (r - l) / 2
            Half.RIGHT -> l += (r - l) / 2
            Half.WHOLE -> Unit
        }
        return intArrayOf(l, t, r, b)
    }

    /** What to produce from a [srcW] x [srcH] source region at ([left], [top]), in source pixels. */
    fun plan(srcW: Double, srcH: Double, left: Double = 0.0, top: Double = 0.0): Plan {
        val plan = fullPlan(srcW, srcH, left, top)
        if (banded && bands(plan)) return plan.capped(BASE_PIXELS, MAX_OUTPUT_SIDE)
        return plan.capped(MAX_OUTPUT_PIXELS, MAX_OUTPUT_SIDE)
    }

    /** True when this target shows a [srcW] x [srcH] source region in bands, its bitmap a stand-in ([banded]). */
    fun bandsAt(srcW: Double, srcH: Double): Boolean = banded && srcW > 0 && srcH > 0 && bands(fullPlan(srcW, srcH))

    /** [plan] before any cap on its size. */
    private fun fullPlan(srcW: Double, srcH: Double, left: Double = 0.0, top: Double = 0.0): Plan {
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
                    val offset = if (anchorTop) 0.0 else coverOffset(srcH, span, bh)
                    Plan(bw, bh, left, top + offset, srcW, span)
                } else {
                    val span = bw * srcH / bh
                    Plan(bw, bh, left + coverOffset(srcW, span, bw), top, span, srcH)
                }
            }
            else -> inside(srcW, srcH, left, top)
        }
        return plan
    }

    /**
     * Where a [span] of a [length]-long axis starts when cropped evenly from both sides;
     * at scale 1 (the span is the [box]) on a whole pixel, so the result is a plain crop.
     */
    private fun coverOffset(length: Double, span: Double, box: Int): Double {
        val offset = (length - span) / 2
        return if (span == box.toDouble()) floor(offset) else offset
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

    /**
     * The decode of a page that shows as this target does, at full detail: its content
     * (auto-cropped likewise) at full size, or, when a `[width, height]` [size] page
     * would take more than [maxBytes] of ARGB, reduced evenly to fit; never sharpened, as
     * renders from it are ([Scaling.sharpen]). Unknown sizes
     * decode at full size. [maxBytes] bounds the bitmap kept: decoding it still reads
     * the source whole first (up to [capPixels]), beside its crop with auto-crop.
     */
    fun detail(size: IntArray?, maxBytes: Long): Target {
        val full = Target(autoCrop = autoCrop, linear = linear, capPixels = capPixels, half = half, box = box, scaling = scaling.unsharpened)
        if (size == null || size[0] <= 0 || size[1] <= 0) return full
        val bytes = size[0].toLong() * size[1] * 4
        if (bytes <= maxBytes) return full
        val f = sqrt(maxBytes.toDouble() / bytes)
        return full.copy(
            width = floor(size[0] * f).toInt().coerceAtLeast(1),
            height = floor(size[1] * f).toInt().coerceAtLeast(1),
            fit = Fit.SHRINK
        )
    }

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

        /** Largest page a [banded] target makes in one bitmap: as large as one may be ([MAX_OUTPUT_PIXELS]), so only pages it would reduce take bands. */
        const val BAND_ABOVE_PIXELS = MAX_OUTPUT_PIXELS

        /** Largest stand-in of a page drawn in bands: 16 MB of ARGB. */
        const val BASE_PIXELS = 4_000_000L

        /** Largest source a stand-in of a page drawn in bands is decoded from, sampled down by a power of two. */
        const val BASE_DECODE_PIXELS = 4 * BASE_PIXELS

        /**
         * True when a page planned at [plan] is drawn in bands: larger than [BAND_ABOVE_PIXELS]
         * or taller than [MAX_OUTPUT_SIDE], and no wider than a band can be.
         */
        fun bands(plan: Plan): Boolean =
            plan.width <= MAX_OUTPUT_SIDE && (plan.width.toLong() * plan.height > BAND_ABOVE_PIXELS || plan.height > MAX_OUTPUT_SIDE)
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

    /** The source span at its own size when this plan enlarges it (a plain crop when the span is whole pixels); else this plan. */
    fun notEnlarged(): Plan =
        if (shrinks()) this else copy(width = spanW.roundToInt().coerceAtLeast(1), height = spanH.roundToInt().coerceAtLeast(1))

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
