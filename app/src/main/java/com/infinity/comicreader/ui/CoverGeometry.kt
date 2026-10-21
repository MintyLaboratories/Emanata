package com.infinity.comicreader.ui

import com.infinity.comicreader.data.CoverShape
import com.infinity.comicreader.data.ListDensity
import kotlin.math.roundToInt

/** Sizes of cover boxes in grid cells and list rows, free of Android types. */
object CoverGeometry {

    /** Shape of a list row's thumbnail slot, and of its box with [CoverShape.CELL]: today's 54 x 72 dp. */
    const val LIST_SLOT_RATIO = 3f / 4f

    /**
     * The largest box of [ratio] (width / height) inside [width] x [height], as
     * `[width, height]`; the whole space when [ratio] is 0.
     */
    fun box(width: Int, height: Int, ratio: Float): IntArray {
        if (ratio <= 0f || width <= 0 || height <= 0) return intArrayOf(width.coerceAtLeast(0), height.coerceAtLeast(0))
        return if (width <= height * ratio) {
            intArrayOf(width, (width / ratio).roundToInt().coerceIn(1, height))
        } else {
            intArrayOf((height * ratio).roundToInt().coerceIn(1, width), height)
        }
    }

    /** Height of a grid cover area [width] wide whose height is free: the shape's, or [cellHeight] for the cell's shape. */
    fun freeHeight(width: Int, ratio: Float, cellHeight: Int): Int =
        if (ratio <= 0f) cellHeight else (width / ratio).roundToInt()

    /** Height of a list row's thumbnail, in dp. */
    fun listHeightDp(density: ListDensity): Int = when (density) {
        ListDensity.COMPACT -> 48
        ListDensity.NORMAL -> 72
        ListDensity.LARGE -> 120
    }

    /** `[width, height]` of a list row's thumbnail, in dp. */
    fun listBoxDp(density: ListDensity, shape: CoverShape): IntArray {
        val h = listHeightDp(density)
        val ratio = if (shape == CoverShape.CELL) LIST_SLOT_RATIO else shape.ratio
        return intArrayOf((h * ratio).roundToInt(), h)
    }
}
