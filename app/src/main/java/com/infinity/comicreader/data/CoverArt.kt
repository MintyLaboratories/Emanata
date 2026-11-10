package com.infinity.comicreader.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.infinity.comicreader.ai.CoverTextDetector
import com.infinity.comicreader.reader.BitmapUtils
import com.infinity.comicreader.reader.Blur
import com.infinity.comicreader.reader.Fit
import com.infinity.comicreader.reader.Plan
import com.infinity.comicreader.reader.Scaling
import com.infinity.comicreader.reader.Target
import com.infinity.comicreader.reader.Wraparound
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Makes a cover of exactly the box size from a full-size image: cropped to fill
 * the box, or whole over a blurred, filled copy of itself, as [CoverStyle] says
 * (always whole for a wraparound whose front nothing tells).
 * All resizing of the cover itself is Lanczos3.
 */
object CoverArt {
    /** The blurred background is made this many times smaller than the box, then stretched back. */
    private const val BLUR_SHRINK = 6

    /** Blur radius at the shrunk size; three passes. */
    private const val BLUR_RADIUS = 3

    /** Darkens the background a little, so the cover's own edges show. */
    private const val BACKGROUND_DIM = 0x33000000

    /** A bookmark's page takes this share of the box on each side. */
    const val PAGE_SCALE = 0.8

    /** The page's shadow, as a share of the box width. */
    private const val PAGE_SHADOW = 0.03f
    private const val PAGE_SHADOW_COLOR = 0x99000000.toInt()

    /** `[width, height]` a bookmark's page is fitted into on a [width] x [height] cover. */
    fun pageBox(width: Int, height: Int): IntArray = intArrayOf(
        (width * PAGE_SCALE).roundToInt().coerceAtLeast(1),
        (height * PAGE_SCALE).roundToInt().coerceAtLeast(1)
    )

    /**
     * [cover] with [page] drawn over it in the bottom right corner, casting a soft
     * shadow; [cover] itself when it can be drawn on, else a copy (and [cover] is
     * recycled).
     */
    fun withPage(cover: Bitmap, page: Bitmap): Bitmap {
        val out = if (cover.isMutable) cover else cover.copy(Bitmap.Config.ARGB_8888, true).also { cover.recycle() }
        val canvas = Canvas(out)
        val left = (out.width - page.width).toFloat()
        val top = (out.height - page.height).toFloat()
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            setShadowLayer(out.width * PAGE_SHADOW, 0f, 0f, PAGE_SHADOW_COLOR)
        }
        canvas.drawRect(left, top, out.width.toFloat(), out.height.toFloat(), shadow)
        canvas.drawBitmap(page, left, top, null)
        return out
    }

    /**
     * [src] as a [width] x [height] cover. A wraparound, judged against its book's
     * typical inner page of shape [innerRatio] ([Wraparound.isWraparound]), shows its
     * front, the half [front] tells (the one without the barcode by default, [frontOf]),
     * one inner page wide from the outer edge, so the spine is left out; when [front]
     * tells none, the whole wraparound over a blurred copy of itself, whatever [style]
     * says. [focus] tells the Smart crop anchor where to look in the part shown, asked
     * only when it crops.
     */
    fun cover(
        src: Bitmap,
        width: Int,
        height: Int,
        style: CoverStyle,
        innerRatio: Double? = null,
        front: (Bitmap) -> Wraparound.Half? = ::frontOf,
        focus: (Bitmap, Rect) -> CoverFocus = { _, _ -> CoverFocus.NONE }
    ): Bitmap {
        if (!Wraparound.isWraparound(src.width, src.height, innerRatio)) return compose(src, width, height, style, focus = focus)
        val half = front(src) ?: return compose(src, width, height, style, fit = true)
        val (left, right) = Wraparound.frontColumns(src.width, src.height, half, innerRatio)
        return compose(src, width, height, style, Rect(left, 0, right, src.height), focus = focus)
    }

    /** `[left, right]` halves of wraparound [src] as shown, each one inner page of [innerRatio] wide from its outer edge. */
    fun halves(src: Bitmap, innerRatio: Double?): List<Rect> = Wraparound.Half.entries.map { half ->
        Wraparound.frontColumns(src.width, src.height, half, innerRatio).let { Rect(it[0], 0, it[1], src.height) }
    }

    /**
     * The front half of wraparound [src] by its tallest text ([SmartCrop.textFront]), from
     * [focus] of each of its [halves]. Null when the text does not tell, and when either focus
     * was found without the detector ([CoverFocus.keep]): then [unsure] too.
     */
    fun textFront(src: Bitmap, innerRatio: Double?, focus: (Bitmap, Rect) -> CoverFocus, unsure: () -> Unit = {}): Wraparound.Half? {
        val (left, right) = halves(src, innerRatio)
        val l = focus(src, left)
        val r = focus(src, right)
        if (!l.keep || !r.keep) {
            unsure()
            return null
        }
        return SmartCrop.textFront(l, left.width(), r, right.width(), src.height)
    }

    /**
     * Where the Smart crop anchor looks in [region] of [src]: its text, as [detector] finds it on
     * the region stretched to its input ([CoverTextDetector.inputSize]; none without [detector]),
     * and its detail. When [detector] gives null or fails, detail alone, not to be kept
     * ([CoverFocus.keep]).
     */
    fun focusOf(src: Bitmap, region: Rect, detector: CoverTextDetector?): CoverFocus {
        val (w, h) = (detector?.inputSize(region.width(), region.height()) ?: SmartCrop.inputSize(region.width(), region.height())).let { it[0] to it[1] }
        val plan = Target(w, h, Fit.STRETCH).plan(region.width().toDouble(), region.height().toDouble(), region.left.toDouble(), region.top.toDouble())
        val small = render(src, plan, false)
        try {
            val luma = ByteArray(w * h)
            val row = IntArray(w)
            for (y in 0 until h) {
                small.getPixels(row, 0, w, 0, y, w, 1)
                Wraparound.luminance(row, w, luma, y * w)
            }
            val (rows, columns) = SmartCrop.detail(luma, w, h).let { it[0] to it[1] }
            if (detector == null) return CoverFocus(null, emptyList(), rows, columns)
            val lines = runCatching { detector.lines(small) }.getOrNull() ?: return CoverFocus(null, emptyList(), rows, columns, keep = false)
            return SmartCrop.focus(lines, w, h, rows, columns)
        } finally {
            small.recycle()
        }
    }

    /** The front half of wraparound [src], from where its barcode is; null when that tells nothing. */
    fun frontOf(src: Bitmap): Wraparound.Half? {
        val scan = if (src.height > Wraparound.SCAN_HEIGHT) {
            render(src, Target(0, Wraparound.SCAN_HEIGHT).plan(src.width.toDouble(), src.height.toDouble()), false)
        } else {
            src
        }
        try {
            val w = scan.width
            val h = scan.height
            val luminance = ByteArray(w * h)
            val row = IntArray(w)
            for (y in 0 until h) {
                scan.getPixels(row, 0, w, 0, y, w, 1)
                Wraparound.luminance(row, w, luminance, y * w)
            }
            return Wraparound.front(luminance, w, h)
        } finally {
            if (scan !== src) scan.recycle()
        }
    }

    /**
     * [region] of [src] (all of it when null) as a [width] x [height] cover; [fit]
     * forces the whole region over a blurred copy whatever [style] says. [focus] is
     * asked where the Smart crop anchor looks when it crops. Leaves [src] as it is.
     */
    fun compose(
        src: Bitmap,
        width: Int,
        height: Int,
        style: CoverStyle,
        region: Rect? = null,
        fit: Boolean = false,
        focus: (Bitmap, Rect) -> CoverFocus = { _, _ -> CoverFocus.NONE }
    ): Bitmap {
        val r = region ?: Rect(0, 0, src.width, src.height)
        val fill = !fit && style.fills(r.width(), r.height(), width, height)
        val plan = { t: Target -> t.plan(r.width().toDouble(), r.height().toDouble(), r.left.toDouble(), r.top.toDouble()) }
        if (fill) {
            val anchored = plan(Target(width, height, Fit.COVER, anchorTop = style.anchor != CropAnchor.CENTER))
            val crops = anchored.spanW < r.width() || anchored.spanH < r.height()
            val shown = if (style.anchor == CropAnchor.SMART && crops) smart(anchored, r, focus(src, r)) else anchored
            return render(src, shown, style.linear, style.scaling)
        }
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        drawBackground(canvas, src, plan, width, height, style.linear)
        val front = render(src, plan(Target(width, height, Fit.INSIDE)), style.linear, style.scaling)
        canvas.drawBitmap(front, ((width - front.width) / 2).toFloat(), ((height - front.height) / 2).toFloat(), null)
        front.recycle()
        return out
    }

    /** [plan], a crop of region [r], moved along each axis it crops to where [focus] says ([SmartCrop.offset]). */
    private fun smart(plan: Plan, r: Rect, focus: CoverFocus): Plan {
        // At scale 1 a whole-pixel start keeps the result a plain crop.
        fun start(at: Double, span: Double, out: Int) = if (span == out.toDouble()) floor(at) else at
        val w = r.width().toDouble()
        val h = r.height().toDouble()
        val x = if (plan.spanW < w) r.left + start(SmartCrop.offset(focus, w, plan.spanW, vertical = false), plan.spanW, plan.width) else plan.x
        val y = if (plan.spanH < h) r.top + start(SmartCrop.offset(focus, h, plan.spanH, vertical = true), plan.spanH, plan.height) else plan.y
        return plan.copy(x = x, y = y)
    }

    /** The region filling the box, shrunk, blurred, stretched back and dimmed. */
    private fun drawBackground(canvas: Canvas, src: Bitmap, plan: (Target) -> Plan, width: Int, height: Int, linear: Boolean) {
        val w = (width / BLUR_SHRINK).coerceAtLeast(1)
        val h = (height / BLUR_SHRINK).coerceAtLeast(1)
        val small = render(src, plan(Target(w, h, Fit.COVER)), linear)
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        small.recycle()
        Blur.blur(pixels, w, h, BLUR_RADIUS)
        val blurred = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        canvas.drawBitmap(blurred, null, Rect(0, 0, width, height), Paint(Paint.FILTER_BITMAP_FLAG))
        blurred.recycle()
        canvas.drawColor(BACKGROUND_DIM)
    }

    /** A new bitmap of [src] as [plan] and [scaling] say; never [src] itself. */
    fun render(src: Bitmap, plan: Plan, linear: Boolean, scaling: Scaling = Scaling.PLAIN): Bitmap = when {
        plan.isIdentity(src.width, src.height) -> src.copy(Bitmap.Config.ARGB_8888, false)
        plan.isCrop() -> Bitmap.createBitmap(src, plan.x.toInt(), plan.y.toInt(), plan.width, plan.height)
        else -> BitmapUtils.resample(src, plan, linear && plan.shrinks(), scaling)
    }
}
