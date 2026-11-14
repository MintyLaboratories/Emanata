package com.infinity.comicreader.reader

import android.graphics.Bitmap

/**
 * Part of a page as fractions of its width and height; may reach a little past its
 * edges, where the edge pixels repeat.
 */
data class Region(val x: Double, val y: Double, val width: Double, val height: Double) {

    /** This region of a page spanning [spanW] x [spanH] source units from ([left], [top]), rendered at [width] x [height]. */
    fun plan(width: Int, height: Int, left: Double, top: Double, spanW: Double, spanH: Double): Plan =
        Plan(width, height, left + x * spanW, top + y * spanH, this.width * spanW, this.height * spanH)
}

/**
 * A page held at full detail, from which any [Region] renders at any size: a zoomed
 * view re-renders what it shows from it. Regions are fractions of the page as the
 * [Target] it was loaded for lays it out (auto-crop included), so they line up with
 * the bitmap [PageSource.loadPage] makes for that target.
 */
interface PageDetail {
    /** Memory it holds. */
    val byteCount: Long

    /**
     * [region] at [width] x [height], resampled as [scaling] says for any change of size
     * ([linear] for shrinking in linear light). Blocks; stops early, throwing, once this
     * thread's [Cancellation] mark is set. Safe to call from several threads.
     */
    fun render(region: Region, width: Int, height: Int, linear: Boolean, scaling: Scaling = Scaling.PLAIN): Bitmap

    /** Frees its memory; no [render] may be running or follow. */
    fun release()
}

/** Detail from [bitmap], the whole page decoded. */
class BitmapDetail(val bitmap: Bitmap) : PageDetail {
    override val byteCount: Long get() = bitmap.byteCount.toLong()

    override fun render(region: Region, width: Int, height: Int, linear: Boolean, scaling: Scaling): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val plan = region.plan(width, height, 0.0, 0.0, w.toDouble(), h.toDouble())
        val inside = plan.x >= 0 && plan.y >= 0 && plan.x + plan.width <= w && plan.y + plan.height <= h
        return if (plan.isCrop() && inside) {
            BitmapUtils.crop(bitmap, plan, scaling)
        } else {
            BitmapUtils.resample(bitmap, plan, linear && plan.shrinks(), scaling)
        }
    }

    override fun release() = bitmap.recycle()
}
