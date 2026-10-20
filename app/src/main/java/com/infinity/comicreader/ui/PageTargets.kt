package com.infinity.comicreader.ui

import com.infinity.comicreader.reader.Fit
import com.infinity.comicreader.reader.Target

/**
 * The exact size each page bitmap is decoded to, so that at rest it is drawn pixel
 * for pixel with no GPU scaling. Mirrors how [PageAdapter] lays its items out; kept
 * free of Android types so it can be unit-tested.
 */
object PageTargets {

    /** Where a page sits in its item: alone, or one half of a two-page spread row. */
    enum class Slot { ALONE, LEFT, RIGHT }

    /**
     * `[width, height]` of the image view showing a page in [slot] of an [itemW] x [itemH]
     * item. A single page's container is padded by [spacingPx] on every side, except in
     * long strips; a spread row is not padded, and splits its width the way equal
     * [android.widget.LinearLayout] weights do, the left half taking the floor. A lone
     * page of a spread takes the whole row.
     */
    fun viewSize(mode: PageAdapter.Mode, spread: Boolean, itemW: Int, itemH: Int, spacingPx: Int, slot: Slot): IntArray {
        val size = when {
            slot == Slot.LEFT -> intArrayOf(itemW / 2, itemH)
            slot == Slot.RIGHT -> intArrayOf(itemW - itemW / 2, itemH)
            spread || mode == PageAdapter.Mode.LONG_STRIP -> intArrayOf(itemW, itemH)
            else -> intArrayOf(itemW - 2 * spacingPx, itemH - 2 * spacingPx)
        }
        return intArrayOf(size[0].coerceAtLeast(1), size[1].coerceAtLeast(1))
    }

    /**
     * What a page shown in a [viewW] x [viewH] view needs for [fit]: the box [ZoomImageView]
     * fits it into, so its fitted scale comes out at exactly 1. Long strips always fit
     * the width and are never auto-cropped, since their gaps belong to the art.
     */
    fun target(
        mode: PageAdapter.Mode,
        fit: ZoomImageView.FitMode,
        viewW: Int,
        viewH: Int,
        autoCrop: Boolean = false,
        linear: Boolean = false
    ): Target = when {
        mode == PageAdapter.Mode.LONG_STRIP -> Target(viewW, 0, linear = linear)
        fit == ZoomImageView.FitMode.WIDTH -> Target(viewW, 0, autoCrop = autoCrop, linear = linear)
        fit == ZoomImageView.FitMode.HEIGHT -> Target(0, viewH, autoCrop = autoCrop, linear = linear)
        fit == ZoomImageView.FitMode.STRETCH -> Target(viewW, viewH, Fit.STRETCH, autoCrop, linear)
        else -> Target(viewW, viewH, autoCrop = autoCrop, linear = linear)
    }
}
