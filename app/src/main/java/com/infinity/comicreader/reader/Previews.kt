package com.infinity.comicreader.reader

import android.graphics.Bitmap
import com.infinity.comicreader.util.Traces

/**
 * Page previews: whole pages shrunk to fit a [BOX] x [BOX] square, never enlarged.
 * They stand in for a page until its exact rendering is ready (peek, page grids).
 */
object Previews {
    /** Longest side of a preview. */
    const val BOX = 256

    /** What a preview is made to. */
    val TARGET = Target(BOX, BOX, Fit.SHRINK)

    /** Largest decoder sample size used for a quick preview: JPEG's DCT scaling stops at 1/8. */
    const val MAX_SAMPLE = 8

    /**
     * Times a [w] x [h] image can be halved before Lanczos3 makes it [pw] x [ph] while
     * staying at least twice that size, so the filter still sees every detail it keeps.
     */
    fun halvings(w: Int, h: Int, pw: Int, ph: Int): Int {
        var n = 0
        var cw = w
        var ch = h
        while (cw / 2 >= 2 * pw && ch / 2 >= 2 * ph) {
            cw /= 2
            ch /= 2
            n++
        }
        return n
    }

    /**
     * Largest power-of-two decoder sample size, up to [MAX_SAMPLE], at which a [w] x [h]
     * image still covers a [pw] x [ph] preview (the decoder rounds sampled sizes up).
     */
    fun sampleFor(w: Int, h: Int, pw: Int, ph: Int): Int {
        var sample = 1
        while (sample < MAX_SAMPLE) {
            val next = sample * 2
            if (ceilDiv(w, next) < pw || ceilDiv(h, next) < ph) break
            sample = next
        }
        return sample
    }

    /** True when a [w] x [h] rendering of a whole page reaches its preview's size, so the preview loses nothing. */
    fun isFullSize(w: Int, h: Int): Boolean = w >= BOX || h >= BOX

    /** `[width, height]` of the preview of a [w] x [h] page. */
    fun sizeOf(w: Int, h: Int): IntArray = TARGET.plan(w.toDouble(), h.toDouble()).let { intArrayOf(it.width, it.height) }

    /**
     * The first step of [make] for a [page] about to be freed: [page] halved once when
     * [make] would halve it, else a copy. Cheap next to the rest, which can run later
     * on it with the page's own preview size ([sizeOf]).
     */
    fun firstStep(page: Bitmap): Bitmap {
        val size = sizeOf(page.width, page.height)
        return if (halvings(page.width, page.height, size[0], size[1]) > 0) halve(page) else page.copy(Bitmap.Config.ARGB_8888, false)
    }

    /**
     * The [size] preview of [page] (by default its own, [sizeOf]): halved by the
     * platform's bilinear filter (an exact 2x2 average at that scale) while it stays
     * twice the preview size, then Lanczos3. Leaves [page] as it is; never returns it.
     */
    fun make(page: Bitmap, size: IntArray = sizeOf(page.width, page.height)): Bitmap = Traces.section("preview make") { made(page, size) }

    private fun made(page: Bitmap, size: IntArray): Bitmap {
        var src = page
        try {
            repeat(halvings(page.width, page.height, size[0], size[1])) {
                val half = halve(src)
                if (src !== page) src.recycle()
                src = half
            }
            val plan = Target(size[0], size[1], Fit.STRETCH).plan(src.width.toDouble(), src.height.toDouble())
            return when {
                plan.isIdentity(src.width, src.height) && src !== page -> src.also { src = page }
                plan.isIdentity(src.width, src.height) -> src.copy(Bitmap.Config.ARGB_8888, false)
                else -> BitmapUtils.resample(src, plan, false)
            }
        } finally {
            if (src !== page) src.recycle()
        }
    }

    private fun halve(src: Bitmap): Bitmap =
        Bitmap.createScaledBitmap(src, (src.width / 2).coerceAtLeast(1), (src.height / 2).coerceAtLeast(1), true)

    private fun ceilDiv(a: Int, b: Int) = (a + b - 1) / b
}
