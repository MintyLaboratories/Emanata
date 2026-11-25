package minty.emanata.reader

import android.graphics.Bitmap
import android.graphics.Matrix
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
 * The pixels of [raw], an image stored turned or mirrored, as its EXIF [orientation]
 * (1-8) shows them: each read takes the stored rectangle under the one asked for and
 * turns it likewise.
 */
class OrientedRegions(private val raw: RegionReader, private val orientation: Int) : RegionReader {
    private val swapped = orientation in 5..8
    override val width: Int get() = if (swapped) raw.height else raw.width
    override val height: Int get() = if (swapped) raw.width else raw.height

    override fun read(left: Int, top: Int, right: Int, bottom: Int, sample: Int): Bitmap {
        val r = stored(orientation, raw.width, raw.height, left, top, right, bottom)
        val pixels = raw.read(r[0], r[1], r[2], r[3], sample)
        if (orientation == 1) return pixels
        val m = Matrix()
        m.setValues(floatArrayOf(TURNS[orientation][0], TURNS[orientation][1], 0f, TURNS[orientation][2], TURNS[orientation][3], 0f, 0f, 0f, 1f))
        val shown = Bitmap.createBitmap(pixels, 0, 0, pixels.width, pixels.height, m, false)
        if (shown !== pixels) pixels.recycle()
        return shown
    }

    override fun close() = raw.close()

    companion object {
        /** By orientation, how a stored pixel's x and y make the shown one's: `[xx, xy, yx, yy]`. */
        private val TURNS = arrayOf(
            floatArrayOf(1f, 0f, 0f, 1f),
            floatArrayOf(1f, 0f, 0f, 1f),
            floatArrayOf(-1f, 0f, 0f, 1f),
            floatArrayOf(-1f, 0f, 0f, -1f),
            floatArrayOf(1f, 0f, 0f, -1f),
            floatArrayOf(0f, 1f, 1f, 0f),
            floatArrayOf(0f, -1f, 1f, 0f),
            floatArrayOf(0f, -1f, -1f, 0f),
            floatArrayOf(0f, 1f, -1f, 0f)
        )

        /**
         * `[left, top, right, bottom]` of a [w] x [h] stored image under the rectangle
         * [left]..[right] x [top]..[bottom] of it shown in [orientation].
         */
        fun stored(orientation: Int, w: Int, h: Int, left: Int, top: Int, right: Int, bottom: Int): IntArray = when (orientation) {
            2 -> intArrayOf(w - right, top, w - left, bottom)
            3 -> intArrayOf(w - right, h - bottom, w - left, h - top)
            4 -> intArrayOf(left, h - bottom, right, h - top)
            5 -> intArrayOf(top, left, bottom, right)
            6 -> intArrayOf(top, h - right, bottom, h - left)
            7 -> intArrayOf(w - bottom, h - right, w - top, h - left)
            8 -> intArrayOf(w - bottom, left, w - top, right)
            else -> intArrayOf(left, top, right, bottom)
        }
    }
}

/**
 * Reads of [inner], a source any part of which decodes it whole (a progressive JPEG),
 * served from one taller read kept: unsampled reads take the rows asked and more, up to
 * [maxPixels] in all, on in the direction they move, so a page's bands share a few decodes.
 * Sampled reads pass straight through.
 */
class KeptRows(private val inner: RegionReader, private val maxPixels: Long) : RegionReader {
    override val width: Int get() = inner.width
    override val height: Int get() = inner.height

    private var kept: Bitmap? = null
    private var keptLeft = 0
    private var keptTop = 0
    private var keptRight = 0
    private var keptBottom = 0

    override fun read(left: Int, top: Int, right: Int, bottom: Int, sample: Int): Bitmap {
        if (sample != 1) return inner.read(left, top, right, bottom, sample)
        val held = kept
        val inside = held != null && left >= keptLeft && right <= keptRight && top >= keptTop && bottom <= keptBottom
        val rows = if (inside) kept!! else {
            val up = held != null && top < keptTop
            val span = maxOf(bottom - top, (maxPixels / (right - left)).toInt()).coerceAtMost(height)
            val t = (if (up) bottom - span else top).coerceIn(0, height - span)
            held?.recycle()
            kept = null
            inner.read(left, t, right, t + span, 1).also {
                kept = it
                keptLeft = left
                keptTop = t
                keptRight = right
                keptBottom = t + span
            }
        }
        val part = Bitmap.createBitmap(rows, left - keptLeft, top - keptTop, right - left, bottom - top)
        return if (part === rows) rows.copy(Bitmap.Config.ARGB_8888, false) else part
    }

    override fun close() {
        kept?.recycle()
        kept = null
        inner.close()
    }
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
         * With [sharpened], one output pixel more each way, for the strips past the plan's
         * edges that sharpening resamples ([BitmapUtils.rimWindows]).
         */
        fun of(plan: Plan, w: Int, h: Int, radius: Int, sharpened: Boolean = false): RegionRead {
            val fx = plan.spanW / plan.width
            val fy = plan.spanH / plan.height
            val mx = ceil(radius * max(1.0, fx)).toInt() + 1 + if (sharpened) ceil(fx).toInt() else 0
            val my = ceil(radius * max(1.0, fy)).toInt() + 1 + if (sharpened) ceil(fy).toInt() else 0
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
        val read = RegionRead.of(plan, reader.width, reader.height, scaling.filter.radius, scaling.sharpen > 0)
        val pixels = synchronized(reader) { reader.read(read.left, read.top, read.right, read.bottom, read.sample) }
        val local = read.local(plan, pixels.width, pixels.height)
        if (local.isIdentity(pixels.width, pixels.height) && scaling.sharpen == 0) return pixels
        try {
            val inside = local.x >= 0 && local.y >= 0 && local.x + local.width <= pixels.width && local.y + local.height <= pixels.height
            return if (local.isCrop() && inside) {
                BitmapUtils.crop(pixels, local, scaling)
            } else {
                BitmapUtils.resample(pixels, local, linear && local.shrinks(), scaling)
            }
        } finally {
            pixels.recycle()
        }
    }

    override fun release() = synchronized(reader) { reader.close() }
}
