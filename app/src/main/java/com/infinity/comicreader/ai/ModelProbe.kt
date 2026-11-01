package com.infinity.comicreader.ai

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Measures a model's [Geometry] by running it on small synthetic tiles:
 * - scale and trim from the output sizes of two input sizes;
 * - context by cutting: the model runs on a [probe]-sized tile of busy content, and on its
 *   top-left three quarters alone, as a tile of a page would see it; the output pixels that
 *   change by more than [TOLERANCE] codes lie within the context of the cut. A context
 *   reaching the quarter beyond the cut is measured again on a tile twice the size, up to
 *   [MAX_PROBE].
 * Works for models that pad (output exactly scale times the input) and for ones that do not
 * (output smaller by a fixed trim; their cut changes nothing).
 */
object ModelProbe {
    /** Output change, in 8-bit codes, that counts; smaller ones do not show as seams. */
    const val TOLERANCE = 2

    /** Input pixels added to the measured context, for content busier than the probes. */
    const val SAFETY = 2

    /** The two input sizes whose outputs give scale and trim. */
    private const val SMALL = 32
    private const val LARGE = 48

    /** The first probe tile, and the largest. */
    const val FIRST_PROBE = 64
    const val MAX_PROBE = 256

    private const val SCALE_LIMIT = 8

    /** Why a model could not be measured. */
    class Unfit(message: String) : Exception(message)

    /** [model]'s geometry; throws [Unfit] when it fails to run or maps tiles in a way tiles cannot use. */
    internal fun measure(model: TileModel): Geometry {
        val (scale, trim) = scaleAndTrim(model)
        var size = FIRST_PROBE
        while (true) {
            val reach = Pattern.entries.maxOf { reach(cut(model, size, scale, trim, it)) }
            // A reach filling the quarter past the cut may be longer still; past the largest probe, take all of it.
            if (reach < size / 4) return Geometry(scale, trim, reach + SAFETY)
            if (size >= MAX_PROBE) return Geometry(scale, trim, size / 4 + SAFETY)
            size *= 2
        }
    }

    /** Busy content for probes: noise, and flat blocks with hard edges, as line art has. */
    enum class Pattern { NOISE, BLOCKS }

    /**
     * The largest output change, in codes, at each distance (in input pixels) from the cut,
     * when [model] sees only the top-left three quarters of a [size] tile of [pattern]: index 0
     * is the row and column next to the cut.
     */
    internal fun cut(model: TileModel, size: Int, scale: Int, trim: Int, pattern: Pattern): IntArray {
        val part = size - size / 4
        val src = content(size, pattern)
        try {
            val whole = output(model, src, size, scale, trim)
            val cut = output(model, src, part, scale, trim)
            val wholeW = size * scale - 2 * trim
            val cutW = part * scale - 2 * trim
            val worst = IntArray(part)
            for (y in 0 until cutW) for (x in 0 until cutW) {
                val d = differ(whole[y * wholeW + x], cut[y * cutW + x])
                if (d == 0) continue
                val ix = (x + trim) / scale
                val iy = (y + trim) / scale
                val from = min(part - 1 - ix, part - 1 - iy)
                worst[from] = max(worst[from], d)
            }
            return worst
        } finally {
            src.recycle()
        }
    }

    /** Input pixels next to the cut whose output changed past [TOLERANCE]: one past the furthest such distance. */
    internal fun reach(worst: IntArray): Int {
        for (d in worst.indices.reversed()) if (worst[d] > TOLERANCE) return d + 1
        return 0
    }

    /** Scale and trim from the outputs of two square inputs. */
    private fun scaleAndTrim(model: TileModel): Pair<Int, Int> {
        val src = content(LARGE, Pattern.NOISE)
        try {
            val a = model.run(src, Box(0, 0, SMALL, SMALL)) ?: throw Unfit("Model does not run")
            val b = model.run(src, Box(0, 0, LARGE, LARGE)) ?: throw Unfit("Model does not run")
            val sx = (b.width - a.width) / (LARGE - SMALL)
            val sy = (b.height - a.height) / (LARGE - SMALL)
            if (sx != sy || sx < 1 || sx > SCALE_LIMIT || (b.width - a.width) % (LARGE - SMALL) != 0) {
                throw Unfit("Output ${a.width}x${a.height} for $SMALL, ${b.width}x${b.height} for $LARGE")
            }
            val lostX = sx * SMALL - a.width
            val lostY = sx * SMALL - a.height
            if (lostX != lostY || lostX < 0 || lostX % 2 != 0 || a.width <= 0) {
                throw Unfit("Output ${a.width}x${a.height} for $SMALL at scale $sx")
            }
            return sx to lostX / 2
        } finally {
            src.recycle()
        }
    }

    /** All of [model]'s output for the top-left [side] x [side] of [src], as pixels. */
    private fun output(model: TileModel, src: Bitmap, side: Int, scale: Int, trim: Int): IntArray {
        val out = side * scale - 2 * trim
        if (out <= 0) throw Unfit("No output for $side at scale $scale, trim $trim")
        val dst = Bitmap.createBitmap(out, out, Bitmap.Config.ARGB_8888)
        try {
            model.run(src, Box(0, 0, side, side), dst, Box(0, 0, out, out), 0, 0) ?: throw Unfit("Model does not run")
            val px = IntArray(out * out)
            dst.getPixels(px, 0, out, 0, 0, out, out)
            return px
        } finally {
            dst.recycle()
        }
    }

    /** The largest channel difference of two opaque pixels. */
    private fun differ(a: Int, b: Int): Int =
        max(abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)), max(abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)), abs((a and 0xFF) - (b and 0xFF))))

    private fun content(size: Int, pattern: Pattern): Bitmap {
        val r = Random(size * 7 + pattern.ordinal)
        val px = when (pattern) {
            Pattern.NOISE -> IntArray(size * size) { r.nextInt(1 shl 24) }
            Pattern.BLOCKS -> {
                val block = 6
                val per = (size + block - 1) / block
                val colors = IntArray(per * per) { if (r.nextInt(3) == 0) 0x101010 else r.nextInt(1 shl 24) }
                IntArray(size * size) { k -> colors[(k / size / block) * per + (k % size) / block] }
            }
        }
        for (i in px.indices) px[i] = px[i] or (0xFF shl 24)
        return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
            setPixels(px, 0, size, 0, 0, size, size)
            setHasAlpha(false)
        }
    }
}
