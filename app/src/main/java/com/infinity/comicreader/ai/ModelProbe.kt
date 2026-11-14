package com.infinity.comicreader.ai

import android.graphics.Bitmap
import java.util.Locale
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Measures a model's [Geometry] by running it on small synthetic tiles ([measure]), and checks
 * a model in tiles of one size ([verify]):
 * - scale and trim from the output sizes of two input sizes;
 * - context by cutting: the model runs on a strip of busy content as long as the probe, a row
 *   and a column, and on its first three quarters alone, as a tile of a page would see it;
 *   the output pixels that change by more than [TOLERANCE] codes lie within the context of
 *   the cut. A context reaching the quarter beyond the cut is measured again on a strip twice
 *   as long, up to [MAX_PROBE]; one reaching past that makes the model [Unfit]: its output
 *   depends on content too far away for tiles to share (as with layers that pool the whole
 *   tile).
 * Works for models that pad (output exactly scale times the input) and for ones that do not
 * (output smaller by a fixed trim; their cut changes nothing). Measure on the CPU, in fp32:
 * fp16 rounding differs with a tile's shape and reads as reach.
 *
 * Every run counts the working memory the model holds ([TileModel.takePeak]), its buffers
 * freed before it so each run counts alone. A run whose input would need more than its budget
 * at the bytes per input pixel of the largest run so far is not started: the model is [Unfit]
 * for [ModelRefusal.MEMORY]. Heavy upscalers work at output resolution, so their memory grows
 * with the input's area (a 4x ESRGAN-class model needs gigabytes for a 256 x 256 input, a
 * quarter of that for the longest strip).
 */
object ModelProbe {
    /** Output change, in 8-bit codes, that counts; smaller ones do not show as seams. */
    const val TOLERANCE = 2

    /** Input pixels added to the measured context, for content busier than the probes. */
    const val SAFETY = 2

    /** Mean difference, in codes, up to which outputs past [TOLERANCE] count as rounding noise rather than wrong. */
    const val NOISE_MEAN = 1.5

    /** The largest difference, in codes, rounding noise may reach. */
    const val NOISE_MAX = 32

    /** The two input sizes whose outputs give scale and trim. */
    private const val SMALL = 32
    private const val LARGE = 48

    /** The first probe tile, and the largest. */
    const val FIRST_PROBE = 64
    const val MAX_PROBE = 256

    /** The short side of a probe's strips ([cut]). */
    const val STRIP = 64

    private const val SCALE_LIMIT = 8

    /** Why a model could not be measured or checked: [reason]; for memory, the [bytes] a run would have needed. */
    class Unfit(val reason: ModelRefusal, message: String, val bytes: Long = 0) : Exception(message)

    /** A model's [geometry], and the most working bytes per input pixel its largest run took ([bytesPerPixel]; 0 where not counted). */
    class Probed(val geometry: Geometry, val bytesPerPixel: Long)

    /** How two outputs differ: the largest and the mean channel difference, in codes. */
    data class Diff(val max: Int, val mean: Double) {
        /** Within [TOLERANCE]: nothing shows. */
        val same: Boolean get() = max <= TOLERANCE

        /** Rounding noise: past [TOLERANCE], but small on average and at worst. */
        val noisy: Boolean get() = !same && mean <= NOISE_MEAN && max <= NOISE_MAX

        /** Neither: one of the outputs is wrong. */
        val wrong: Boolean get() = !same && !noisy

        override fun toString() = String.format(Locale.ROOT, "max %d, mean %.2f", max, mean)
    }

    /**
     * [verify]'s result at one tile size: the model against the reference ([vsReference]) and
     * its tiles against tiles on a shifted grid ([seams]), and the most working memory one of
     * its tiles took ([peakBytes]; 0 where not counted). It passes when its output is not
     * wrong and no seams show.
     */
    class Check(val vsReference: Diff, val seams: Diff, val peakBytes: Long = 0) {
        val passes: Boolean get() = !vsReference.wrong && seams.same

        override fun toString() = "against the reference $vsReference; seams $seams; ${peakBytes shr 20} MB"
    }

    /** [model]'s geometry ([probe]). */
    internal fun measure(model: TileModel, canceled: () -> Boolean = { false }): Geometry = probe(model, canceled).geometry

    /**
     * [probe] for a model that computes every output pixel alike wherever it sits in a tile of
     * a given size (a shader chain: no rounding by place), so its whole reach can be found
     * exactly: strips whose last quarter is replaced by its negative ([swap]), the same size as
     * the strips they are compared with, change no output pixel past the reach by even one
     * code, however faint the far context's share. The context is that reach and [SAFETY].
     */
    internal fun probeExact(model: TileModel, canceled: () -> Boolean = { false }, budgetBytes: Long = Long.MAX_VALUE): Probed {
        val m = Checked(model, canceled, budgetBytes)
        val (scale, trim) = scaleAndTrim(m)
        var size = FIRST_PROBE
        while (true) {
            val reach = Pattern.entries.maxOf { p -> listOf(true, false).maxOf { across -> reach(swap(m, size, scale, trim, p, across), 0) } }
            if (reach < size / 4) return Probed(Geometry(scale, trim, reach + SAFETY), m.perPixel)
            if (size >= MAX_PROBE) throw Unfit(ModelRefusal.REACH, "Output changes ${size / 4} or more pixels away from a change: tiles would show")
            size *= 2
        }
    }

    /**
     * [model]'s geometry and working memory, no run taking more than [budgetBytes]; throws
     * [Unfit] when it fails to run, maps tiles in a way tiles cannot use or would need more
     * memory, and [CancellationException] once [canceled], checked before every run.
     */
    internal fun probe(model: TileModel, canceled: () -> Boolean = { false }, budgetBytes: Long = Long.MAX_VALUE): Probed {
        val m = Checked(model, canceled, budgetBytes)
        val (scale, trim) = scaleAndTrim(m)
        var size = FIRST_PROBE
        while (true) {
            val reach = Pattern.entries.maxOf { p -> listOf(true, false).maxOf { across -> reach(cut(m, size, scale, trim, p, across)) } }
            if (reach < size / 4) return Probed(Geometry(scale, trim, reach + SAFETY), m.perPixel)
            if (size >= MAX_PROBE) throw Unfit(ModelRefusal.REACH, "Output changes ${size / 4} or more pixels away from a cut: tiles would show")
            size *= 2
        }
    }

    /**
     * Checks [model] in [size] tiles for [geometry] on a page of noise (the content fp16 rounds
     * worst), three tiles wide:
     * - seams: the page in [size] tiles against the page less its first [TilePlan.ALIGN] rows
     *   and columns in [size] tiles, so tiles meet elsewhere while every pixel keeps its place
     *   in the aligned period. Rounding that differs by where a pixel sits in its tile shows
     *   here, as do tiles that go wrong by their content;
     * - against [reference] (the model on the CPU, in fp32), on the page's first
     *   [referenceSize] tile: wrong tiles (a feature map past what the GPU addresses) and wrong
     *   arithmetic show here.
     * Throws [Unfit] when a run fails, and [CancellationException] once [canceled], checked
     * before every run.
     */
    internal fun verify(
        model: TileModel,
        reference: TileModel,
        geometry: Geometry,
        size: Int,
        referenceSize: Int,
        canceled: () -> Boolean = { false }
    ): Check {
        require(TilePlan.fits(size, geometry) && TilePlan.fits(referenceSize, geometry)) { "Tile too small for margin ${geometry.margin}" }
        val m = Checked(model, canceled)
        val r = Checked(reference, canceled)
        val margin = geometry.margin
        val s = geometry.scale
        val shift = TilePlan.ALIGN
        // Three tiles across, so the two pages' tiles meet inside what is compared; one down
        // (unless the margin leaves too few rows to compare).
        val core = TilePlan.core(size, geometry)
        val w = size + core
        val h = max(core, shift + margin + TilePlan.MIN_CORE)
        val src = content(w, h, Pattern.NOISE)
        val shifted = Bitmap.createBitmap(src, shift, shift, w - shift, h - shift)
        try {
            val a = tiled(m, src, size, geometry)
            val b = tiled(m, shifted, size, geometry)
            // The CPU runs the first tile alone: the reference is slow, and wrong output is wrong everywhere.
            val first = TilePlan.tiles(w, h, referenceSize, geometry).first()
            val truth = run(r, src, first.input, first.crop)
            // The shifted page's pixels whose context lies inside it, from its margin on, and the same pixels of the page.
            val aw = w * s
            val bw = (w - shift) * s
            val from = margin * s
            val at = (shift + margin) * s
            val seams = diff(bw - from, (h - shift) * s - from, { x, y -> a[(y + at) * aw + x + at] }, { x, y -> b[(y + from) * bw + x + from] })
            val tw = first.crop.width
            return Check(diff(tw, first.crop.height, { x, y -> a[y * aw + x] }, { x, y -> truth[y * tw + x] }), seams, m.peak)
        } finally {
            shifted.recycle()
            src.recycle()
        }
    }

    /** [model]'s output for all of [src] in [size] tiles ([TilePlan]), as pixels. */
    private fun tiled(model: TileModel, src: Bitmap, size: Int, geometry: Geometry): IntArray {
        val dst = Bitmap.createBitmap(src.width * geometry.scale, src.height * geometry.scale, Bitmap.Config.ARGB_8888)
        try {
            for (t in TilePlan.tiles(src.width, src.height, size, geometry)) {
                model.run(src, t.input, dst, t.crop, t.dx, t.dy) ?: throw Unfit(ModelRefusal.DOES_NOT_RUN, "Model does not run")
            }
            return pixels(dst)
        } finally {
            dst.recycle()
        }
    }

    /** Busy content for probes: noise, and flat blocks with hard edges, as line art has. */
    enum class Pattern { NOISE, BLOCKS }

    /**
     * The largest output change, in codes, at each distance (in input pixels) from the cut,
     * when [model] sees only the first three quarters of a [size] x [STRIP] strip of [pattern]
     * ([across]: a row, cut on its right; else a column, cut at its bottom): index 0 is the
     * column or row next to the cut. The strip's other edges are the same in both runs, so
     * only the cut shows; a strip costs what a tile of a quarter the area does.
     */
    internal fun cut(model: TileModel, size: Int, scale: Int, trim: Int, pattern: Pattern, across: Boolean = true): IntArray {
        val part = size - size / 4
        val thin = min(size, STRIP)
        val w = if (across) size else thin
        val h = if (across) thin else size
        val src = content(w, h, pattern)
        try {
            val cw = if (across) part else w
            val ch = if (across) h else part
            val whole = output(model, src, w, h, scale, trim)
            val cut = output(model, src, cw, ch, scale, trim)
            val wholeW = w * scale - 2 * trim
            val cutW = cw * scale - 2 * trim
            val cutH = ch * scale - 2 * trim
            val worst = IntArray(part)
            for (y in 0 until cutH) for (x in 0 until cutW) {
                val d = differ(whole[y * wholeW + x], cut[y * cutW + x])
                if (d == 0) continue
                val from = part - 1 - (if (across) x + trim else y + trim) / scale
                worst[from] = max(worst[from], d)
            }
            return worst
        } finally {
            src.recycle()
        }
    }

    /**
     * The largest output change, in codes, at each distance (in input pixels) from where a
     * [size] x [STRIP] strip of [pattern] ([across]: a row, else a column) changes: [model]'s
     * output for the strip against its output for the strip with its last quarter negated.
     * Index 0 is the column or row next to the change; only pixels before it are compared.
     */
    internal fun swap(model: TileModel, size: Int, scale: Int, trim: Int, pattern: Pattern, across: Boolean = true): IntArray {
        val part = size - size / 4
        val thin = min(size, STRIP)
        val w = if (across) size else thin
        val h = if (across) thin else size
        val src = content(w, h, pattern)
        val other = src.copy(Bitmap.Config.ARGB_8888, true)
        try {
            val px = pixels(other)
            for (y in 0 until h) for (x in 0 until w) {
                if ((if (across) x else y) >= part) px[y * w + x] = px[y * w + x] xor 0xFFFFFF
            }
            other.setPixels(px, 0, w, 0, 0, w, h)
            val a = output(model, src, w, h, scale, trim)
            val b = output(model, other, w, h, scale, trim)
            val ow = w * scale - 2 * trim
            val oh = h * scale - 2 * trim
            val worst = IntArray(part)
            for (y in 0 until oh) for (x in 0 until ow) {
                val along = (if (across) x else y) + trim
                if (along >= part * scale) continue
                val d = differ(a[y * ow + x], b[y * ow + x])
                if (d == 0) continue
                val from = part - 1 - along / scale
                worst[from] = max(worst[from], d)
            }
            return worst
        } finally {
            other.recycle()
            src.recycle()
        }
    }

    /** Input pixels next to the cut whose output changed past [tolerance] codes: one past the furthest such distance. */
    internal fun reach(worst: IntArray, tolerance: Int = TOLERANCE): Int {
        for (d in worst.indices.reversed()) if (worst[d] > tolerance) return d + 1
        return 0
    }

    /** Scale and trim from the outputs of two square inputs. */
    private fun scaleAndTrim(model: TileModel): Pair<Int, Int> {
        val src = content(LARGE, Pattern.NOISE)
        try {
            val a = model.run(src, Box(0, 0, SMALL, SMALL)) ?: throw Unfit(ModelRefusal.DOES_NOT_RUN, "Model does not run")
            val b = model.run(src, Box(0, 0, LARGE, LARGE)) ?: throw Unfit(ModelRefusal.DOES_NOT_RUN, "Model does not run")
            val sx = (b.width - a.width) / (LARGE - SMALL)
            val sy = (b.height - a.height) / (LARGE - SMALL)
            if (sx != sy || sx < 1 || sx > SCALE_LIMIT || (b.width - a.width) % (LARGE - SMALL) != 0) {
                throw Unfit(ModelRefusal.SHAPE, "Output ${a.width}x${a.height} for $SMALL, ${b.width}x${b.height} for $LARGE")
            }
            val lostX = sx * SMALL - a.width
            val lostY = sx * SMALL - a.height
            if (lostX != lostY || lostX < 0 || lostX % 2 != 0 || a.width <= 0) {
                throw Unfit(ModelRefusal.SHAPE, "Output ${a.width}x${a.height} for $SMALL at scale $sx")
            }
            return sx to lostX / 2
        } finally {
            src.recycle()
        }
    }

    /** All of [model]'s output for the top-left [w] x [h] of [src], as pixels. */
    private fun output(model: TileModel, src: Bitmap, w: Int, h: Int, scale: Int, trim: Int): IntArray {
        val ow = w * scale - 2 * trim
        val oh = h * scale - 2 * trim
        if (ow <= 0 || oh <= 0) throw Unfit(ModelRefusal.SHAPE, "No output for ${w}x$h at scale $scale, trim $trim")
        return run(model, src, Box(0, 0, w, h), Box(0, 0, ow, oh))
    }

    /** Output window [crop] of [model]'s run on [input] of [src], as pixels. */
    private fun run(model: TileModel, src: Bitmap, input: Box, crop: Box): IntArray {
        val dst = Bitmap.createBitmap(crop.width, crop.height, Bitmap.Config.ARGB_8888)
        try {
            model.run(src, input, dst, crop, 0, 0) ?: throw Unfit(ModelRefusal.DOES_NOT_RUN, "Model does not run")
            return pixels(dst)
        } finally {
            dst.recycle()
        }
    }

    private fun pixels(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    /** How the pixels [a] and [b] give at each place of a [w] x [h] rectangle differ. */
    private inline fun diff(w: Int, h: Int, a: (Int, Int) -> Int, b: (Int, Int) -> Int): Diff {
        if (w <= 0 || h <= 0) return Diff(0, 0.0)
        var worst = 0
        var sum = 0L
        for (y in 0 until h) for (x in 0 until w) {
            val d = differ(a(x, y), b(x, y))
            worst = max(worst, d)
            sum += d
        }
        return Diff(worst, sum.toDouble() / (w.toLong() * h))
    }

    /** The largest channel difference of two opaque pixels. */
    private fun differ(a: Int, b: Int): Int =
        max(abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)), max(abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)), abs((a and 0xFF) - (b and 0xFF))))

    private fun content(size: Int, pattern: Pattern): Bitmap = content(size, size, pattern)

    /** An opaque [w] x [h] bitmap of [pattern]. */
    private fun content(w: Int, h: Int, pattern: Pattern): Bitmap {
        val r = Random(w * 7 + (h - w) * 13 + pattern.ordinal)
        val px = when (pattern) {
            Pattern.NOISE -> IntArray(w * h) { r.nextInt(1 shl 24) }
            Pattern.BLOCKS -> {
                val block = 6
                val across = (w + block - 1) / block
                val colors = IntArray(across * ((h + block - 1) / block)) { if (r.nextInt(3) == 0) 0x101010 else r.nextInt(1 shl 24) }
                IntArray(w * h) { k -> colors[(k / w / block) * across + (k % w) / block] }
            }
        }
        for (i in px.indices) px[i] = px[i] or (0xFF shl 24)
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPixels(px, 0, w, 0, 0, w, h)
            setHasAlpha(false)
        }
    }

    /**
     * [model], refusing to run once [canceled], or when its input would need more than
     * [budgetBytes] at [perPixel]; counts the most each run held ([peak]), its buffers freed
     * first once the model counts memory at all.
     */
    private class Checked(
        private val model: TileModel,
        private val canceled: () -> Boolean,
        private val budgetBytes: Long = Long.MAX_VALUE
    ) : TileModel by model {
        /** Working bytes per input pixel of the largest run counted so far; 0 before any. */
        var perPixel = 0L
            private set

        /** The most working bytes any run held. */
        var peak = 0L
            private set

        private var largest = 0L

        override fun run(src: Bitmap, input: Box, dst: Bitmap?, crop: Box?, dx: Int, dy: Int): Dims? {
            if (canceled()) throw CancellationException("AI model check canceled")
            val area = input.width.toLong() * input.height
            val need = area * perPixel
            if (need > budgetBytes) {
                throw Unfit(ModelRefusal.MEMORY, "A ${input.width}x${input.height} run needs about ${need shr 20} MB, over ${budgetBytes shr 20} MB", need)
            }
            // Counted alone: the buffers earlier runs left are freed first.
            if (perPixel > 0) model.trim()
            model.takePeak()
            val out = model.run(src, input, dst, crop, dx, dy)
            val held = model.takePeak()
            peak = max(peak, held)
            if (held > 0 && area >= largest) {
                largest = area
                perPixel = (held + area - 1) / area
            }
            return out
        }
    }
}
