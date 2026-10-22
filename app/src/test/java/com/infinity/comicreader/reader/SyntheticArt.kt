package com.infinity.comicreader.reader

import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Generated color pictures for spread seam tests: ARGB pixels, row by row. */
object SyntheticArt {

    /**
     * A [width] x [height] picture from [seed]: soft color shading, a few dark strokes
     * crossing it at random angles and some filled blobs, like busy comic art.
     */
    fun art(width: Int, height: Int, seed: Long): IntArray {
        val r = Random(seed)
        val waves = List(3) { List(3) { doubleArrayOf(r.nextDouble() * 0.05 + 0.005, r.nextDouble() * 0.05 + 0.005, r.nextDouble() * 2 * PI, r.nextDouble() * 50 + 20) } }
        val base = DoubleArray(3) { r.nextDouble() * 120 + 70 }
        val blobs = List(6) { doubleArrayOf(r.nextDouble() * width, r.nextDouble() * height, r.nextDouble() * 60 + 20, r.nextDouble() * 60 + 20, r.nextDouble() * 255, r.nextDouble() * 255, r.nextDouble() * 255) }
        val strokes = List(10) { doubleArrayOf(r.nextDouble() * width, r.nextDouble() * height, r.nextDouble() * PI, r.nextDouble() * 3 + 1.5) }
        val out = IntArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            val c = DoubleArray(3) { ch -> base[ch] + waves[ch].sumOf { w -> w[3] * sin(x * w[0] + y * w[1] + w[2]) } }
            for (b in blobs) {
                val dx = (x - b[0]) / b[2]
                val dy = (y - b[1]) / b[3]
                if (dx * dx + dy * dy <= 1) for (ch in 0 until 3) c[ch] = b[4 + ch]
            }
            for (s in strokes) {
                // Distance from the line through (s0, s1) at angle s2.
                val d = abs((x - s[0]) * sin(s[2]) - (y - s[1]) * cos(s[2]))
                if (d <= s[3]) for (ch in 0 until 3) c[ch] = 20.0
            }
            out[y * width + x] = argb(c[0], c[1], c[2])
        }
        return out
    }

    /** A picture whose color only darkens slowly from top to bottom, like a clear sky. */
    fun sky(width: Int, height: Int): IntArray = IntArray(width * height) { i ->
        val t = (i / width).toDouble() / height
        argb(90 + 80 * t, 150 + 60 * t, 230 - 20 * t)
    }

    /** Columns [from] until [until] of a [width]-wide picture [pixels]. */
    fun columns(pixels: IntArray, width: Int, from: Int, until: Int): IntArray {
        val w = until - from
        val h = pixels.size / width
        return IntArray(w * h) { pixels[(it / w) * width + from + it % w] }
    }

    /**
     * [pixels] [width] wide with columns [from] until [until] painted [color], or their own
     * color when [keep], plus noise of [noise] steps from [seed].
     */
    fun paint(pixels: IntArray, width: Int, from: Int, until: Int, color: Int, noise: Int = 0, seed: Long = 0, keep: Boolean = false): IntArray {
        val r = Random(seed)
        val out = pixels.copyOf()
        for (i in out.indices) {
            if (i % width !in from until until) continue
            val n = if (noise > 0) r.nextGaussian() * noise else 0.0
            val c = if (keep) pixels[i] else color
            out[i] = argb(((c shr 16) and 0xFF) + n, ((c shr 8) and 0xFF) + n, (c and 0xFF) + n)
        }
        return out
    }

    /**
     * [pixels], [width] wide, with what a JPEG encode at [quality] (1..100) does to them:
     * colors to YCbCr, color halved both ways, each 8 x 8 block's DCT rounded to the
     * standard tables scaled for [quality], and back.
     */
    fun jpegLike(pixels: IntArray, width: Int, quality: Int): IntArray {
        val h = pixels.size / width
        val planes = Array(3) { DoubleArray(pixels.size) }
        for ((i, p) in pixels.withIndex()) {
            val r = ((p shr 16) and 0xFF).toDouble()
            val g = ((p shr 8) and 0xFF).toDouble()
            val b = (p and 0xFF).toDouble()
            planes[0][i] = 0.299 * r + 0.587 * g + 0.114 * b
            planes[1][i] = 128 - 0.168736 * r - 0.331264 * g + 0.5 * b
            planes[2][i] = 128 + 0.5 * r - 0.418688 * g - 0.081312 * b
        }
        for (c in 1..2) halveColor(planes[c], width, h)
        val scale = if (quality < 50) 5000.0 / quality else 200.0 - 2 * quality
        for (c in 0..2) quantize(planes[c], width, h, if (c == 0) LUMA_TABLE else CHROMA_TABLE, scale)
        return IntArray(pixels.size) { i ->
            val y = planes[0][i]
            val cb = planes[1][i] - 128
            val cr = planes[2][i] - 128
            argb(y + 1.402 * cr, y - 0.344136 * cb - 0.714136 * cr, y + 1.772 * cb)
        }
    }

    /** Each 2 x 2 block of [plane] set to its mean. */
    private fun halveColor(plane: DoubleArray, w: Int, h: Int) {
        for (y in 0 until h step 2) for (x in 0 until w step 2) {
            val cells = listOf(x to y, x + 1 to y, x to y + 1, x + 1 to y + 1).filter { it.first < w && it.second < h }
            val mean = cells.sumOf { plane[it.second * w + it.first] } / cells.size
            for ((cx, cy) in cells) plane[cy * w + cx] = mean
        }
    }

    /** Every 8 x 8 block of [plane] through a DCT rounded to [table] at [scale] percent. */
    private fun quantize(plane: DoubleArray, w: Int, h: Int, table: IntArray, scale: Double) {
        val q = DoubleArray(64) { ((table[it] * scale + 50) / 100).toInt().coerceIn(1, 255).toDouble() }
        val block = DoubleArray(64)
        val coef = DoubleArray(64)
        for (by in 0 until h step 8) for (bx in 0 until w step 8) {
            for (i in 0 until 64) {
                val x = minOf(bx + i % 8, w - 1)
                val y = minOf(by + i / 8, h - 1)
                block[i] = plane[y * w + x] - 128
            }
            for (v in 0 until 8) for (u in 0 until 8) {
                var sum = 0.0
                for (i in 0 until 64) sum += block[i] * COS[u * 8 + i % 8] * COS[v * 8 + i / 8]
                coef[v * 8 + u] = Math.round(sum * alpha(u) * alpha(v) / 4 / q[v * 8 + u]) * q[v * 8 + u]
            }
            for (i in 0 until 64) {
                val x = bx + i % 8
                val y = by + i / 8
                if (x >= w || y >= h) continue
                var sum = 0.0
                for (v in 0 until 8) for (u in 0 until 8) sum += alpha(u) * alpha(v) * coef[v * 8 + u] * COS[u * 8 + i % 8] * COS[v * 8 + i / 8]
                plane[y * w + x] = sum / 4 + 128
            }
        }
    }

    private fun alpha(u: Int) = if (u == 0) 1 / Math.sqrt(2.0) else 1.0

    /** cos((2x + 1) u pi / 16) at [u * 8 + x]. */
    private val COS = DoubleArray(64) { cos((2 * (it % 8) + 1) * (it / 8) * PI / 16) }

    private val LUMA_TABLE = intArrayOf(
        16, 11, 10, 16, 24, 40, 51, 61, 12, 12, 14, 19, 26, 58, 60, 55, 14, 13, 16, 24, 40, 57, 69, 56,
        14, 17, 22, 29, 51, 87, 80, 62, 18, 22, 37, 56, 68, 109, 103, 77, 24, 35, 55, 64, 81, 104, 113, 92,
        49, 64, 78, 87, 103, 121, 120, 101, 72, 92, 95, 98, 112, 100, 103, 99
    )

    private val CHROMA_TABLE = IntArray(64) { i ->
        if (i % 8 < 4 && i / 8 < 4) intArrayOf(17, 18, 24, 47, 18, 21, 26, 66, 24, 26, 56, 99, 47, 66, 99, 99)[(i / 8) * 4 + i % 8] else 99
    }

    private fun argb(r: Double, g: Double, b: Double): Int =
        (0xFF shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)

    private fun ch(v: Double) = v.roundToInt().coerceIn(0, 255)
}
