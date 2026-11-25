package minty.emanata.ai

import android.graphics.Bitmap

/**
 * Keeps black-and-white pages black and white through AI models. Models get RGB (three equal
 * planes on a gray page) and may tint their output; on a page whose source is gray, each model's
 * output is collapsed back to gray as its tiles are written ([TileEngine.run]), before the
 * next model or the resampling reads it. Color pages are left as the models make them, and so
 * is a scan of a black-and-white page with a paper tint or chroma noise over [TOLERANCE]: only
 * sources neutral within a code are found gray.
 */
object GrayPages {
    /** Rows read at a time. */
    private const val ROWS = 16

    /**
     * Most a gray page's pixel may have between its red, green and blue: a one-channel file with
     * a gray color profile decodes into RGB with channels rounded a code apart.
     */
    const val TOLERANCE = 1

    /**
     * True when [page] is gray: every pixel's red, green and blue within [TOLERANCE]. A
     * one-channel source (gray JPEG, PNG, JPEG XL) is, as its decoder writes the channel to all
     * three; a color file of a gray page is when its chroma is neutral. Read up to its first
     * colored pixel; a chain finds it in the read that looks for transparency ([TileEngine.scan]).
     * Translucent pixels count by their color channels alone.
     */
    fun isGray(page: Bitmap): Boolean = TileEngine.scan(page, findGray = true, alpha = false).gray

    /** True when [argb]'s red, green and blue are within [TOLERANCE]; at once when equal. */
    fun isGray(argb: Int): Boolean = ((argb xor (argb ushr 8)) and 0xFFFF) == 0 || spreadOf(argb) <= TOLERANCE

    /**
     * The largest difference between a pixel's red, green and blue over [page] (ARGB_8888):
     * 0 on a gray page. Stops reading at the first pixel over [stopAbove]. To see what a model
     * makes of a gray page before the collapse, read it on [TileEngine.run] with gray false
     * (its default) or [AiPages.render] with gray false; both collapse a gray page otherwise.
     */
    fun spread(page: Bitmap, stopAbove: Int = 255): Int {
        val w = page.width
        val h = page.height
        if (w <= 0 || h <= 0) return 0
        val rows = IntArray(w * minOf(ROWS, h))
        var most = 0
        var y = 0
        while (y < h) {
            val n = minOf(ROWS, h - y)
            page.getPixels(rows, 0, w, 0, y, w, n)
            for (i in 0 until w * n) {
                val s = spreadOf(rows[i])
                if (s > most) {
                    most = s
                    if (most > stopAbove) return most
                }
            }
            y += n
        }
        return most
    }

    /** The difference between the largest and smallest of [argb]'s red, green and blue. */
    fun spreadOf(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return maxOf(r, g, b) - minOf(r, g, b)
    }

    /**
     * [argb] with its luma in all three channels and its alpha kept: Rec. 601 weights as 77,
     * 150 and 29 of 256. A gray pixel stays as it is.
     */
    fun luma(argb: Int): Int {
        val y = (((argb shr 16) and 0xFF) * 77 + ((argb shr 8) and 0xFF) * 150 + (argb and 0xFF) * 29 + 128) shr 8
        return (argb and 0xFF000000.toInt()) or (y shl 16) or (y shl 8) or y
    }

    /** Sets each pixel of [area] (all by default) of [output] (mutable ARGB_8888) to its [luma]. */
    fun toGray(output: Bitmap, area: Box = Box(0, 0, output.width, output.height)) {
        if (area.width <= 0 || area.height <= 0) return
        val rows = IntArray(area.width * minOf(ROWS, area.height))
        var y = 0
        while (y < area.height) {
            val n = minOf(ROWS, area.height - y)
            output.getPixels(rows, 0, area.width, area.x, area.y + y, area.width, n)
            for (i in 0 until area.width * n) rows[i] = luma(rows[i])
            output.setPixels(rows, 0, area.width, area.x, area.y + y, area.width, n)
            y += n
        }
    }
}
