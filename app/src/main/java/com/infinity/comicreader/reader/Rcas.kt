package com.infinity.comicreader.reader

import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * RCAS ("robust contrast-adaptive sharpening"), the sharpening pass of AMD FidelityFX Super
 * Resolution 1, written from AMD's description and MIT-licensed reference (`ffx_fsr1.h`,
 * Copyright (c) 2021 Advanced Micro Devices, Inc.; the notice is under About). The model
 * of the native pass (`rcas.c`), which must match it bit for bit; the pass itself where
 * native code cannot run.
 *
 * Each pixel `e` is weighed against its four neighbors `b` (above), `d` (left), `f` (right)
 * and `h` (below) as `(lobe * (b + d + f + h) + e) / (4 * lobe + 1)`, with one negative
 * `lobe` for all three channels:
 * - per channel, the most sharpening that keeps the result inside 0..255 when the
 *   neighbors sit at their minimum or maximum (RCAS's limiter); the least of the channels';
 * - at most [LIMIT], times the [amount];
 * - halved where the center stands out from all four neighbors as noise does (RCAS's
 *   denoise: luma `R / 2 + G + B / 2`, its high-pass against its range).
 *
 * The limiter keeps results inside 0..255, not inside the neighbors' range: a dark dot on a
 * lighter ground gets darker than any of its neighbors, as sharpening means.
 *
 * Values are 8-bit codes held as floats (RCAS's peak 1 is 255 here), in fp32 like the
 * native pass, with exact divisions (AMD's reference approximates some). A divisor that
 * is 0 means its limit cannot bind: all four neighbors black cannot go below 0, all white
 * not above 255. Only pixels whose five are all opaque change. Pixels are ARGB as
 * [android.graphics.Bitmap.getPixels] gives them; RCAS treats red and blue alike, so the
 * byte order of the native pass gives the same results.
 *
 * Bitmaps are sharpened in place in bands, each knowing its two rows beside the next band
 * ([sharpen]). Past an edge, the [Rim]'s pixels serve as neighbors where given, so a region
 * rendered alone joins its neighbors seamlessly; else the edge pixels repeat.
 */
object Rcas {
    /** RCAS's largest lobe magnitude: `0.25 - 1/16`. */
    const val LIMIT = 0.1875f

    /** RCAS's sharpness for [strength] percent: 0 changes nothing, 1 is its most (0 stops). */
    fun amount(strength: Int): Float = strength.coerceIn(0, 100) / 100f

    /** The pixels just outside each edge of an image, or null where its edge pixels repeat: rows as wide, columns as tall as it. */
    class Rim<T>(val top: T? = null, val bottom: T? = null, val left: T? = null, val right: T? = null) {
        fun <U> map(f: (T) -> U): Rim<U> = Rim(top?.let(f), bottom?.let(f), left?.let(f), right?.let(f))

        val isEmpty: Boolean get() = top == null && bottom == null && left == null && right == null
    }

    /** [e] sharpened against [b] (above), [d] (left), [f] (right) and [h] (below) by [amount]; [e] itself unless all five are opaque. */
    fun pixel(b: Int, d: Int, e: Int, f: Int, h: Int, amount: Float): Int {
        if ((b and d and e and f and h) ushr 24 != 0xFF) return e
        val bR = ch(b, 16)
        val bG = ch(b, 8)
        val bB = ch(b, 0)
        val dR = ch(d, 16)
        val dG = ch(d, 8)
        val dB = ch(d, 0)
        val eR = ch(e, 16)
        val eG = ch(e, 8)
        val eB = ch(e, 0)
        val fR = ch(f, 16)
        val fG = ch(f, 8)
        val fB = ch(f, 0)
        val hR = ch(h, 16)
        val hG = ch(h, 8)
        val hB = ch(h, 0)
        // Luma times 2, then how far the center stands out of its ring, against the five's range.
        val bL = luma(bR, bG, bB)
        val dL = luma(dR, dG, dB)
        val eL = luma(eR, eG, eB)
        val fL = luma(fR, fG, fB)
        val hL = luma(hR, hG, hB)
        val pass = 0.25f * bL + 0.25f * dL + 0.25f * fL + 0.25f * hL - eL
        val range = max(max(max(bL, dL), max(eL, fL)), hL) - min(min(min(bL, dL), min(eL, fL)), hL)
        val noise = if (range > 0f) min(abs(pass) / range, 1f) else 0f
        val nz = 1f - 0.5f * noise
        val lobeR = lobe(bR, dR, eR, fR, hR)
        val lobeG = lobe(bG, dG, eG, fG, hG)
        val lobeB = lobe(bB, dB, eB, fB, hB)
        val lobe = max(-LIMIT, min(max(lobeR, max(lobeG, lobeB)), 0f)) * amount * nz
        val div = 4f * lobe + 1f
        val r = (lobe * (bR + dR + fR + hR) + eR) / div
        val g = (lobe * (bG + dG + fG + hG) + eG) / div
        val bl = (lobe * (bB + dB + fB + hB) + eB) / div
        return (0xFF shl 24) or (code(r) shl 16) or (code(g) shl 8) or code(bl)
    }

    private fun ch(p: Int, shift: Int): Float = ((p shr shift) and 0xFF).toFloat()

    private fun luma(r: Float, g: Float, b: Float): Float = b * 0.5f + (r * 0.5f + g)

    /** The most negative lobe one channel allows: the larger of its two limits. */
    private fun lobe(b: Float, d: Float, e: Float, f: Float, h: Float): Float {
        val mn = min(min(b, d), min(f, h))
        val mx = max(max(b, d), max(f, h))
        val hitMin = if (mx > 0f) min(mn, e) / (4f * mx) else 1f
        val hitMax = if (mn < 255f) (255f - max(mx, e)) / (4f * mn - 1020f) else -1f
        return max(-hitMin, hitMax)
    }

    private fun code(v: Float): Int = (min(max(v, 0f), 255f) + 0.5f).toInt()

    /**
     * Output row [out] from rows [up], [mid] and [down] of [w] pixels, [left] and [right] the
     * pixels just past [mid]'s ends.
     */
    fun row(up: IntArray, mid: IntArray, down: IntArray, w: Int, left: Int, right: Int, amount: Float, out: IntArray) {
        for (x in 0 until w) {
            val d = if (x > 0) mid[x - 1] else left
            val f = if (x < w - 1) mid[x + 1] else right
            out[x] = pixel(up[x], d, mid[x], f, down[x], amount)
        }
    }

    /** Reads row [y] of the image into [out]. Called from several threads. */
    fun interface RowReader {
        fun read(y: Int, out: IntArray)
    }

    /** Takes row [y] of the result. Called from several threads. */
    fun interface RowWriter {
        fun write(y: Int, row: IntArray)
    }

    /** The first row of band [b] of [bands] over [h] rows. */
    fun bandStart(h: Int, bands: Int, b: Int): Int = (h.toLong() * b / bands).toInt()

    /**
     * Sharpens a [w] x [h] image by [amount] in place: rows read through [reader] are written
     * back through [writer], in [bands] bands on up to [threads] threads ([Resampler.inBands]),
     * [rim] past its edges. Each band's two rows beside the band above are read first, so
     * bands run in any order. Polls [canceled] before each band and throws
     * [CancellationException] once it says so.
     */
    fun sharpen(
        w: Int,
        h: Int,
        amount: Float,
        reader: RowReader,
        writer: RowWriter,
        rim: Rim<IntArray> = Rim(),
        bands: Int = 1,
        threads: Int = 1,
        canceled: () -> Boolean = { false }
    ) {
        require(w > 0 && h > 0 && bands in 1..h) { "Empty image or bands" }
        // Rows y0 - 1 and y0 of each band but the first, as they were.
        val saved = Array(2 * bands) { IntArray(0) }
        for (b in 1 until bands) {
            val y0 = bandStart(h, bands, b)
            saved[2 * b] = IntArray(w).also { reader.read(y0 - 1, it) }
            saved[2 * b + 1] = IntArray(w).also { reader.read(y0, it) }
        }
        Resampler.inBands(bands, threads, canceled) { _, b0, b1 ->
            for (b in b0 until b1) {
                band(w, h, amount, reader, writer, rim, bandStart(h, bands, b), bandStart(h, bands, b + 1), saved.getOrNull(2 * b), saved.getOrNull(2 * b + 3))
            }
            true
        }
    }

    /** Rows [y0] until [y1] of [sharpen], [above] row y0 - 1 and [below] row y1 as they were (unused at the image's edges). */
    private fun band(
        w: Int, h: Int, amount: Float, reader: RowReader, writer: RowWriter, rim: Rim<IntArray>, y0: Int, y1: Int,
        above: IntArray?, below: IntArray?
    ) {
        var prev = IntArray(w)
        var cur = IntArray(w)
        var next = IntArray(w)
        val out = IntArray(w)
        reader.read(y0, cur)
        if (y0 > 0) above!!.copyInto(prev)
        for (y in y0 until y1) {
            val up = if (y > 0) prev else rim.top ?: cur
            val down = when {
                y + 1 < y1 -> next.also { reader.read(y + 1, it) }
                y + 1 < h -> below!!
                else -> rim.bottom ?: cur
            }
            val left = rim.left?.get(y) ?: cur[0]
            val right = rim.right?.get(y) ?: cur[w - 1]
            row(up, cur, down, w, left, right, amount, out)
            writer.write(y, out)
            // The row just read becomes the current one, unchanged; the current one the previous.
            val t = prev
            prev = cur
            cur = if (down === next) next.also { next = t } else t
        }
    }
}
