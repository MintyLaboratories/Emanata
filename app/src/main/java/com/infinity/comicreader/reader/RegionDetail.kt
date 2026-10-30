package com.infinity.comicreader.reader

import android.graphics.Bitmap
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Rectangles of one encoded image, decoded alone ([android.graphics.BitmapRegionDecoder]). */
interface RegionReader {
    val width: Int
    val height: Int

    /** Source pixels [left]..[right] x [top]..[bottom], sampled down by [sample] (a power of two). */
    fun read(left: Int, top: Int, right: Int, bottom: Int, sample: Int): Bitmap

    fun close()
}

/**
 * Which source rectangle a render of [plan] decodes, and at what power-of-two [sample]:
 * the plan's span with [margin] source pixels around it for the filter's taps, clamped to
 * the [w] x [h] image, so taps fall on real neighbors and only the image's own edges repeat.
 */
data class RegionRead(val left: Int, val top: Int, val right: Int, val bottom: Int, val sample: Int) {

    /** [plan] on the [decodedW] x [decodedH] bitmap this read returns. */
    fun local(plan: Plan, decodedW: Int, decodedH: Int): Plan {
        val sx = (right - left).toDouble() / decodedW
        val sy = (bottom - top).toDouble() / decodedH
        return plan.copy(x = (plan.x - left) / sx, y = (plan.y - top) / sy, spanW = plan.spanW / sx, spanH = plan.spanH / sy)
    }

    companion object {
        /** Most source pixels one read decodes; more are sampled down by a power of two. */
        const val MAX_READ_PIXELS = 16_000_000L

        /**
         * The read for [plan] of a [w] x [h] image through a filter of [radius]: unsampled,
         * unless the plan shrinks by 2 or more per sample on both axes (the result then still
         * has twice the output's pixels each way), or more than [MAX_READ_PIXELS] would be read.
         */
        fun of(plan: Plan, w: Int, h: Int, radius: Int): RegionRead {
            val fx = plan.spanW / plan.width
            val fy = plan.spanH / plan.height
            val mx = ceil(radius * max(1.0, fx)).toInt() + 1
            val my = ceil(radius * max(1.0, fy)).toInt() + 1
            val l = floor(plan.x - mx).toInt().coerceIn(0, w - 1)
            val t = floor(plan.y - my).toInt().coerceIn(0, h - 1)
            val r = ceil(plan.x + plan.spanW + mx).toInt().coerceIn(l + 1, w)
            val b = ceil(plan.y + plan.spanH + my).toInt().coerceIn(t + 1, h)
            var sample = 1
            while (sample * 4 <= min(fx, fy)) sample *= 2
            val pixels = (r - l).toLong() * (b - t)
            while (pixels / (sample.toLong() * sample) > MAX_READ_PIXELS) sample *= 2
            return RegionRead(l, t, r, b, sample)
        }
    }
}

/**
 * Detail from an encoded image read a rectangle at a time ([reader]), never decoded whole:
 * a page too large to hold at full size renders any [Region] from the source pixels under
 * it. [area] (`[left, top, right, bottom]` source pixels) is the part of the image the
 * page shows, as its [Target] lays it out (auto-crop, half). [encodedBytes] is what the
 * reader holds.
 */
class RegionDetail(private val reader: RegionReader, private val area: IntArray, private val encodedBytes: Long) : PageDetail {
    override val byteCount: Long get() = encodedBytes

    override fun render(region: Region, width: Int, height: Int, linear: Boolean, scaling: Scaling): Bitmap {
        val plan = region.plan(
            width, height, area[0].toDouble(), area[1].toDouble(), (area[2] - area[0]).toDouble(), (area[3] - area[1]).toDouble()
        )
        val read = RegionRead.of(plan, reader.width, reader.height, scaling.filter.radius)
        val pixels = synchronized(reader) { reader.read(read.left, read.top, read.right, read.bottom, read.sample) }
        val local = read.local(plan, pixels.width, pixels.height)
        if (local.isIdentity(pixels.width, pixels.height)) return pixels
        try {
            val inside = local.x >= 0 && local.y >= 0 && local.x + local.width <= pixels.width && local.y + local.height <= pixels.height
            return if (local.isCrop() && inside) {
                Bitmap.createBitmap(pixels, local.x.toInt(), local.y.toInt(), local.width, local.height)
            } else {
                BitmapUtils.resample(pixels, local, linear && local.shrinks(), scaling)
            }
        } finally {
            pixels.recycle()
        }
    }

    override fun release() = synchronized(reader) { reader.close() }
}
