package com.infinity.comicreader.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import com.infinity.comicreader.util.Timing
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.math.abs

/** Buffering of encoded entries and bitmap post-processing; decoding itself lives in [Decoders]. */
object BitmapUtils {
    /** Largest single archive entry that will be buffered in memory (guards against zip bombs). */
    private const val MAX_ENTRY_BYTES = 96L * 1024 * 1024

    /**
     * Reads [input] fully, but refuses to buffer more than [MAX_ENTRY_BYTES]. With its [size]
     * known (-1 when not), it is read straight into one array of that size.
     */
    fun readCapped(input: InputStream, size: Long = -1): ByteArray {
        if (size in 0..MAX_ENTRY_BYTES) {
            val known = ByteArray(size.toInt())
            var at = 0
            while (at < known.size) {
                val n = input.read(known, at, known.size - at)
                if (n < 0) return known.copyOf(at)
                at += n
            }
            val next = input.read()
            if (next < 0) return known
            // Longer than it said: the rest as any stream.
            val rest = readCapped(input)
            if (known.size + 1L + rest.size > MAX_ENTRY_BYTES) throw DecodeException("Image entry is too large")
            return known + byteArrayOf(next.toByte()) + rest
        }
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
     * Content of [src] inside uniform borders (the color found in the corners). Returns
     * null when there is nothing worth trimming, when the page has no
     * consistent border color, or when the result would be implausibly small.
     */
    fun contentBounds(src: Bitmap): Rect? {
        val w = src.width
        val h = src.height
        if (w < 64 || h < 64) return null
        val tol = 60
        val bg = src.getPixel(1, 1)
        // The border color must be shared by at least one other corner.
        val similar = listOf(
            src.getPixel(w - 2, 1), src.getPixel(1, h - 2), src.getPixel(w - 2, h - 2)
        ).count { !differs(it, bg, tol) }
        if (similar < 1) return null

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
        if (top >= h) return null // blank page
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
        if (ratio > 0.97 || ratio < 0.25) return null
        return Rect(l, t, l + newW, t + newH)
    }

    // ------------------------------------------------------------------ resampling

    /** [src] scaled as [plan] says by the platform's bilinear filter, into a new ARGB_8888 bitmap: quick, for a stand-in. */
    fun scaleQuick(src: Bitmap, plan: Plan): Bitmap {
        val out = Bitmap.createBitmap(plan.width, plan.height, Bitmap.Config.ARGB_8888, src.hasAlpha())
        val m = Matrix()
        m.setTranslate(-plan.x.toFloat(), -plan.y.toFloat())
        m.postScale((plan.width / plan.spanW).toFloat(), (plan.height / plan.spanH).toFloat())
        Canvas(out).drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /**
     * [src] resampled through [Resampler] as [plan] and [scaling] say, into a new ARGB_8888 bitmap. Reads
     * and writes rows in place, so no second full-size copy of [src] is ever made.
     * Stops early, throwing, once this thread's [Cancellation] mark is set. Timed ([Timing]).
     */
    fun resample(src: Bitmap, plan: Plan, linear: Boolean, scaling: Scaling = Scaling.PLAIN): Bitmap {
        val w = src.width
        val out = Bitmap.createBitmap(plan.width, plan.height, Bitmap.Config.ARGB_8888, src.hasAlpha())
        try {
            Timing.span("resample ${w}x${src.height} to ${plan.width}x${plan.height} ${scaling.filter.key} ${scaling.upRing}/${scaling.downRing}") {
                Resampler.resample(
                    w, src.height,
                    { y, row -> src.getPixels(row, 0, w, 0, y, w, 1) },
                    Resampler.Window(plan.x, plan.y, plan.spanW, plan.spanH),
                    plan.width, plan.height, linear,
                    { y, rows, pixels -> synchronized(out) { out.setPixels(pixels, 0, plan.width, 0, y, plan.width, rows) } },
                    scaling,
                    canceled = Cancellation.current()
                )
            }
        } catch (t: Throwable) {
            out.recycle()
            throw t
        }
        return out
    }
}
