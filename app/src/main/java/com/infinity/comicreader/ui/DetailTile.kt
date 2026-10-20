package com.infinity.comicreader.ui

import com.infinity.comicreader.reader.Region
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * The part of a zoomed view a settled zoom re-renders: the view pixels at ([left], [top])
 * sized [width] x [height] that show the page, and the [region] of the page behind them.
 * Rendered at that size and drawn there unscaled, it lands pixel for pixel on the
 * bitmap it replaces.
 */
data class DetailTile(val left: Int, val top: Int, val width: Int, val height: Int, val region: Region) {

    companion object {
        /**
         * The tile of a [viewW] x [viewH] view showing a [bitmapW] x [bitmapH] bitmap scaled
         * by ([scaleX], [scaleY]) and moved by ([transX], [transY]); null when none of it
         * shows. Takes the view pixels whose centers lie on the bitmap, as a drawn
         * rectangle covers them.
         */
        fun of(
            scaleX: Float, scaleY: Float, transX: Float, transY: Float,
            bitmapW: Int, bitmapH: Int, viewW: Int, viewH: Int
        ): DetailTile? {
            if (scaleX <= 0f || scaleY <= 0f || bitmapW <= 0 || bitmapH <= 0) return null
            val spanW = scaleX.toDouble() * bitmapW
            val spanH = scaleY.toDouble() * bitmapH
            val tx = transX.toDouble()
            val ty = transY.toDouble()
            val x0 = max(0, ceil(tx - 0.5).toInt())
            val y0 = max(0, ceil(ty - 0.5).toInt())
            val x1 = min(viewW, ceil(tx + spanW - 0.5).toInt())
            val y1 = min(viewH, ceil(ty + spanH - 0.5).toInt())
            if (x1 <= x0 || y1 <= y0) return null
            val region = Region((x0 - tx) / spanW, (y0 - ty) / spanH, (x1 - x0) / spanW, (y1 - y0) / spanH)
            return DetailTile(x0, y0, x1 - x0, y1 - y0, region)
        }
    }
}
