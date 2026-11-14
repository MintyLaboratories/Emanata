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

    /** The most allocated for a size an archive's header declares before its bytes arrive ([readCapped]). */
    private const val UPFRONT_BYTES = 16 * 1024 * 1024

    /**
     * Reads [input] fully, but refuses to buffer more than [MAX_ENTRY_BYTES]. With its [size]
     * known (-1 when not), it is read straight into one array of that size; past [upfront]
     * bytes the array grows as bytes arrive, so a damaged header declaring more than the
     * entry holds costs no more than [upfront].
     */
    fun readCapped(input: InputStream, size: Long = -1, upfront: Int = UPFRONT_BYTES): ByteArray {
        if (size in 0..MAX_ENTRY_BYTES) {
            var known = ByteArray(minOf(size, upfront.toLong()).toInt())
            var at = 0
            while (true) {
                if (at == known.size) {
                    if (at >= size) break
                    known = known.copyOf(minOf(size, at * 2L).toInt())
                }
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
     * [src] resampled as [plan] and [scaling] say, into a new ARGB_8888 bitmap: by native code
     * ([NativeResampler]) where it can, else through [Resampler], reading and writing rows in
     * place. Either way no second full-size copy of [src] is ever made. With [Scaling.sharpen],
     * the result is then sharpened in place ([sharpen]). Stops early, throwing, once this
     * thread's [Cancellation] mark is set. Timed ([Timing]).
     */
    fun resample(src: Bitmap, plan: Plan, linear: Boolean, scaling: Scaling = Scaling.PLAIN): Bitmap {
        val out = Bitmap.createBitmap(plan.width, plan.height, Bitmap.Config.ARGB_8888, src.hasAlpha())
        val window = Resampler.Window(plan.x, plan.y, plan.spanW, plan.spanH)
        try {
            val sharpened = if (scaling.sharpen > 0) " sharpen ${scaling.sharpen}" else ""
            Timing.span("resample ${src.width}x${src.height} to ${plan.width}x${plan.height} ${scaling.filter.key} ${scaling.upRing}/${scaling.downRing}$sharpened") {
                val canceled = Cancellation.current()
                resampleInto(src, out, window, linear, scaling, canceled)
                if (scaling.sharpen > 0) {
                    sharpen(src, out, window, Rcas.amount(scaling.sharpen), canceled) { win, strip ->
                        // Thin strips: the CPU's, which needs no GPU job for a row or a column.
                        val engine = if (NativeResampler.engine == PixelEngine.GPU) PixelEngine.NATIVE else NativeResampler.engine
                        resampleInto(src, strip, win, linear, scaling, canceled, engine)
                    }
                }
            }
        } catch (t: Throwable) {
            out.recycle()
            throw t
        }
        return out
    }

    /** [window] of [src] resampled into all of [out] by [engine] where native code can, else through [Resampler]. */
    private fun resampleInto(
        src: Bitmap, out: Bitmap, window: Resampler.Window, linear: Boolean, scaling: Scaling, canceled: () -> Boolean,
        engine: PixelEngine = NativeResampler.engine
    ) {
        if (NativeResampler.resample(src, out, window, linear, scaling, canceled, engine)) return
        val w = src.width
        Resampler.resample(
            w, src.height,
            { y, row -> src.getPixels(row, 0, w, 0, y, w, 1) },
            window,
            out.width, out.height, linear,
            { y, rows, pixels -> synchronized(out) { out.setPixels(pixels, 0, out.width, 0, y, out.width, rows) } },
            scaling,
            canceled = canceled
        )
    }

    /**
     * Sharpens [out], [window] of [src] shown at its size, by [amount] ([Rcas]), in place.
     * Where [window] lies inside [src], [fill] draws the one-pixel strip just past that edge
     * ([rimWindows]) at the same scale, which serves as the edge's neighbors, so a region
     * rendered alone (a zoomed detail, a band) matches the whole page rendered at once; at
     * the source's own edges, the edge pixels repeat. Every strip is freed, also on failure.
     */
    private fun sharpen(
        src: Bitmap, out: Bitmap, window: Resampler.Window, amount: Float, canceled: () -> Boolean,
        fill: (Resampler.Window, Bitmap) -> Unit
    ) {
        val made = ArrayList<Bitmap>(4)
        try {
            val strips = rimWindows(src.width, src.height, window, out.width, out.height).map { (win, size) ->
                Bitmap.createBitmap(size[0], size[1], Bitmap.Config.ARGB_8888, src.hasAlpha()).also { strip ->
                    made += strip
                    fill(win, strip)
                }
            }
            if (NativeResampler.sharpen(out, amount, strips, canceled)) return
            val w = out.width
            val rim = strips.map { b -> IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) } }
            Rcas.sharpen(
                w, out.height, amount,
                { y, row -> synchronized(out) { out.getPixels(row, 0, w, 0, y, w, 1) } },
                { y, row -> synchronized(out) { out.setPixels(row, 0, w, 0, y, w, 1) } },
                rim, minOf(out.height, Runtime.getRuntime().availableProcessors() * 3), Runtime.getRuntime().availableProcessors(), canceled
            )
        } finally {
            made.forEach { it.recycle() }
        }
    }

    /**
     * The windows of the one-pixel strips just past each edge of [window] of a [srcW] x [srcH]
     * source shown at [dstW] x [dstH], each with its `[width, height]`: the row above and
     * below, the column left and right, at the same scale; none where [window] reaches the
     * source's edge.
     */
    internal fun rimWindows(srcW: Int, srcH: Int, window: Resampler.Window, dstW: Int, dstH: Int): Rcas.Rim<Pair<Resampler.Window, IntArray>> {
        val pw = window.width / dstW
        val ph = window.height / dstH
        val eps = 1e-9
        return Rcas.Rim(
            top = if (window.y > eps) Resampler.Window(window.x, window.y - ph, window.width, ph) to intArrayOf(dstW, 1) else null,
            bottom = if (window.y + window.height < srcH - eps) {
                Resampler.Window(window.x, window.y + window.height, window.width, ph) to intArrayOf(dstW, 1)
            } else null,
            left = if (window.x > eps) Resampler.Window(window.x - pw, window.y, pw, window.height) to intArrayOf(1, dstH) else null,
            right = if (window.x + window.width < srcW - eps) {
                Resampler.Window(window.x + window.width, window.y, pw, window.height) to intArrayOf(1, dstH)
            } else null
        )
    }
}
