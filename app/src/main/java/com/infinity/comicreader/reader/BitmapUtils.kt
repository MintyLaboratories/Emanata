package com.infinity.comicreader.reader

import android.graphics.Bitmap
import android.graphics.Color
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.math.abs

/** Buffering of encoded entries and bitmap post-processing; decoding itself lives in [Decoders]. */
object BitmapUtils {
    /** Largest single archive entry that will be buffered in memory (guards against zip bombs). */
    private const val MAX_ENTRY_BYTES = 96L * 1024 * 1024

    /** Reads [input] fully, but refuses to buffer more than [MAX_ENTRY_BYTES]. */
    fun readCapped(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_ENTRY_BYTES) throw DecodeException("Image entry is too large")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    // ------------------------------------------------------------------ auto-crop

    private fun differs(a: Int, b: Int, tol: Int): Boolean =
        abs(Color.red(a) - Color.red(b)) > tol ||
            abs(Color.green(a) - Color.green(b)) > tol ||
            abs(Color.blue(a) - Color.blue(b)) > tol

    /**
     * Trims uniform borders (the colour found in the corners) from [src]. Returns
     * [src] itself when there is nothing worth trimming, when the page has no
     * consistent border colour, or when the result would be implausibly small.
     */
    fun autoCrop(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        if (w < 64 || h < 64) return src
        val tol = 60
        val bg = src.getPixel(1, 1)
        // The border colour must be shared by at least one other corner.
        val similar = listOf(
            src.getPixel(w - 2, 1), src.getPixel(1, h - 2), src.getPixel(w - 2, h - 2)
        ).count { !differs(it, bg, tol) }
        if (similar < 1) return src

        val minHits = maxOf(2, w / 200)
        val row = IntArray(w)
        fun rowHasContent(y: Int): Boolean {
            src.getPixels(row, 0, w, 0, y, w, 1)
            var hits = 0
            var x = 0
            while (x < w) {
                if (differs(row[x], bg, tol) && ++hits >= minHits) return true
                x += 2
            }
            return false
        }

        var top = 0
        while (top < h && !rowHasContent(top)) top++
        if (top >= h) return src // blank page
        var bottom = h - 1
        while (bottom > top && !rowHasContent(bottom)) bottom--

        val ch = bottom - top + 1
        val col = IntArray(ch)
        val minColHits = maxOf(2, ch / 200)
        fun colHasContent(x: Int): Boolean {
            src.getPixels(col, 0, 1, x, top, 1, ch)
            var hits = 0
            var y = 0
            while (y < ch) {
                if (differs(col[y], bg, tol) && ++hits >= minColHits) return true
                y += 2
            }
            return false
        }
        var left = 0
        while (left < w && !colHasContent(left)) left++
        var right = w - 1
        while (right > left && !colHasContent(right)) right--

        val pad = maxOf(4, minOf(w, h) / 200)
        val l = (left - pad).coerceAtLeast(0)
        val t = (top - pad).coerceAtLeast(0)
        val r = (right + pad).coerceAtMost(w - 1)
        val b = (bottom + pad).coerceAtMost(h - 1)
        val newW = r - l + 1
        val newH = b - t + 1
        val ratio = (newW.toLong() * newH).toDouble() / (w.toLong() * h)
        if (ratio > 0.97 || ratio < 0.25) return src
        return Bitmap.createBitmap(src, l, t, newW, newH)
    }
}
