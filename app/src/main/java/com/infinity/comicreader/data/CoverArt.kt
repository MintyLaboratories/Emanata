package com.infinity.comicreader.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.infinity.comicreader.reader.BitmapUtils
import com.infinity.comicreader.reader.Blur
import com.infinity.comicreader.reader.Fit
import com.infinity.comicreader.reader.Plan
import com.infinity.comicreader.reader.Target
import com.infinity.comicreader.reader.Wraparound
import kotlin.math.roundToInt

/**
 * Makes a cover of exactly the box size from a full-size image: cropped to fill
 * the box, or whole over a blurred, filled copy of itself, as [CoverStyle] says.
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
     * front: the half without the barcode ([front], worked out by [frontOf] when asked),
     * else the half a book read [rightToLeft] or not starts on; one inner page wide
     * from the outer edge, so the spine is left out.
     */
    fun cover(
        src: Bitmap,
        width: Int,
        height: Int,
        style: CoverStyle,
        innerRatio: Double? = null,
        rightToLeft: Boolean = false,
        front: (Bitmap) -> Wraparound.Half? = ::frontOf
    ): Bitmap {
        if (!Wraparound.isWraparound(src.width, src.height, innerRatio)) return compose(src, width, height, style)
        val half = Wraparound.shownFront(front(src), rightToLeft)
        val (left, right) = Wraparound.frontColumns(src.width, src.height, half, innerRatio)
        return compose(src, width, height, style, Rect(left, 0, right, src.height))
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
     * forces the whole region over a blurred copy whatever [style] says. Leaves
     * [src] as it is.
     */
    fun compose(src: Bitmap, width: Int, height: Int, style: CoverStyle, region: Rect? = null, fit: Boolean = false): Bitmap {
        val r = region ?: Rect(0, 0, src.width, src.height)
        val fill = !fit && style.fills(r.width(), r.height(), width, height)
        val plan = { t: Target -> t.plan(r.width().toDouble(), r.height().toDouble(), r.left.toDouble(), r.top.toDouble()) }
        if (fill) {
            return render(src, plan(Target(width, height, Fit.COVER, anchorTop = style.anchor == CropAnchor.TOP)), style.linear)
        }
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        drawBackground(canvas, src, plan, width, height, style.linear)
        val front = render(src, plan(Target(width, height, Fit.INSIDE)), style.linear)
        canvas.drawBitmap(front, ((width - front.width) / 2).toFloat(), ((height - front.height) / 2).toFloat(), null)
        front.recycle()
        return out
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

    /** A new bitmap of [src] as [plan] says; never [src] itself. */
    fun render(src: Bitmap, plan: Plan, linear: Boolean): Bitmap = when {
        plan.isIdentity(src.width, src.height) -> src.copy(Bitmap.Config.ARGB_8888, false)
        plan.isCrop() -> Bitmap.createBitmap(src, plan.x.toInt(), plan.y.toInt(), plan.width, plan.height)
        else -> BitmapUtils.resample(src, plan, linear && plan.shrinks())
    }
}
