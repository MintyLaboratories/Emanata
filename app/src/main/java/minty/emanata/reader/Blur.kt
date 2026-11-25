package minty.emanata.reader

/** Box blur of 8-bit ARGB pixels, free of Android types; three passes approximate a Gaussian. */
object Blur {

    /**
     * Blurs [pixels] ([width] x [height]) in place with [passes] box passes of
     * [radius] on each axis. Pixels past the edge repeat the edge pixel, so a flat
     * image stays flat.
     */
    fun blur(pixels: IntArray, width: Int, height: Int, radius: Int, passes: Int = 3) {
        require(pixels.size >= width * height) { "Too few pixels" }
        if (radius <= 0 || width <= 0 || height <= 0) return
        val tmp = IntArray(width * height)
        repeat(passes) {
            // Each pass reads rows and writes columns, so two passes cover both axes.
            pass(pixels, tmp, width, height, radius)
            pass(tmp, pixels, height, width, radius)
        }
    }

    /** Averages each row of [src] ([w] x [h]) over 2 [r] + 1 pixels into [dst], transposed ([h] x [w]). */
    private fun pass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val window = 2 * r + 1
        val last = w - 1
        for (y in 0 until h) {
            val row = y * w
            var a = 0
            var red = 0
            var g = 0
            var b = 0
            for (i in -r..r) {
                val p = src[row + i.coerceIn(0, last)]
                a += p ushr 24
                red += (p shr 16) and 0xFF
                g += (p shr 8) and 0xFF
                b += p and 0xFF
            }
            for (x in 0 until w) {
                dst[x * h + y] = (round(a, window) shl 24) or (round(red, window) shl 16) or
                    (round(g, window) shl 8) or round(b, window)
                val out = src[row + (x - r).coerceIn(0, last)]
                val inn = src[row + (x + r + 1).coerceIn(0, last)]
                a += (inn ushr 24) - (out ushr 24)
                red += ((inn shr 16) and 0xFF) - ((out shr 16) and 0xFF)
                g += ((inn shr 8) and 0xFF) - ((out shr 8) and 0xFF)
                b += (inn and 0xFF) - (out and 0xFF)
            }
        }
    }

    private fun round(sum: Int, n: Int): Int = (sum + n / 2) / n
}
