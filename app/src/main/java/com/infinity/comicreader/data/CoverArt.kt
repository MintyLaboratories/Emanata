package com.infinity.comicreader.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.infinity.comicreader.reader.BitmapUtils
import com.infinity.comicreader.reader.Blur
import com.infinity.comicreader.reader.Fit
import com.infinity.comicreader.reader.Plan
import com.infinity.comicreader.reader.Target
import com.infinity.comicreader.reader.Wraparound

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

    /**
     * [src] as a [width] x [height] cover. A wraparound shows the half [front] names
     * (worked out by [frontOf] when asked), or all of it over a blurred copy when
     * neither half is known to be the front.
     */
    fun cover(src: Bitmap, width: Int, height: Int, style: CoverStyle, front: (Bitmap) -> Wraparound.Half? = ::frontOf): Bitmap {
        if (!Wraparound.isWraparound(src.width, src.height)) return compose(src, width, height, style)
        val half = src.width / 2
        return when (front(src)) {
            Wraparound.Half.LEFT -> compose(src, width, height, style, Rect(0, 0, half, src.height))
            Wraparound.Half.RIGHT -> compose(src, width, height, style, Rect(src.width - half, 0, src.width, src.height))
            null -> compose(src, width, height, style, fit = true)
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
