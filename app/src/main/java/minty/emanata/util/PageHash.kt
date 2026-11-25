package minty.emanata.util

/**
 * A page's perceptual hash (dHash): which neighboring cells of a coarse luma grid grow
 * brighter, [across] (9 x 8 cells, left to right) and [down] (8 x 9 cells, top to bottom),
 * 64 bits each. Re-encoding a page barely changes it, whatever the format. [flat] marks a
 * page of nearly one tone (blank, all black), whose hash tells nothing about it.
 * Free of Android types.
 */
data class PageHash(val across: Long, val down: Long, val flat: Boolean) {

    /** Bits that differ from [other]'s, of 128. */
    fun distance(other: PageHash): Int =
        java.lang.Long.bitCount(across xor other.across) + java.lang.Long.bitCount(down xor other.down)

    /** `across.down` in hex, `!` after a flat one. */
    fun encode(): String = hex(across) + "." + hex(down) + if (flat) "!" else ""

    companion object {
        /** Luma steps (of 255) a cell must gain on its neighbor to set a bit, so re-encoding noise on even tones flips none. */
        private const val RISE = 2.0

        /** Spread of cell lumas (of 255) below which a page is [flat]. */
        private const val FLAT_SPREAD = 8.0

        /** The hash of a [width] x [height] picture of ARGB [pixels], row by row. */
        fun of(pixels: IntArray, width: Int, height: Int): PageHash {
            require(width > 0 && height > 0 && pixels.size >= width * height) { "Bad picture: $width x $height" }
            val luma = FloatArray(width * height) { i ->
                val p = pixels[i]
                (0.299f * (p shr 16 and 0xFF) + 0.587f * (p shr 8 and 0xFF) + 0.114f * (p and 0xFF))
            }
            val wide = cells(luma, width, height, 9, 8)
            val tall = cells(luma, width, height, 8, 9)
            var across = 0L
            var down = 0L
            for (y in 0 until 8) for (x in 0 until 8) {
                val bit = y * 8 + x
                if (wide[y * 9 + x + 1] - wide[y * 9 + x] > RISE) across = across or (1L shl bit)
                if (tall[(y + 1) * 8 + x] - tall[y * 8 + x] > RISE) down = down or (1L shl bit)
            }
            val spread = (wide.maxOrNull() ?: 0.0) - (wide.minOrNull() ?: 0.0)
            return PageHash(across, down, spread < FLAT_SPREAD)
        }

        /**
         * Mean luma of each of [cols] x [rows] equal cells of a [width] x [height] picture,
         * row by row; each source pixel counts toward the cells it overlaps by the share of
         * the cell it covers, so a picture smaller than the grid still fills every cell.
         */
        private fun cells(luma: FloatArray, width: Int, height: Int, cols: Int, rows: Int): DoubleArray {
            val sums = DoubleArray(cols * rows)
            val xs = spans(width, cols)
            val ys = spans(height, rows)
            for (y in 0 until height) {
                val yRow = ys[y]
                for (x in 0 until width) {
                    val v = luma[y * width + x].toDouble()
                    val xRow = xs[x]
                    for ((cy, wy) in yRow) for ((cx, wx) in xRow) sums[cy * cols + cx] += v * wy * wx
                }
            }
            return sums
        }

        /** For each of [length] source pixels, the cells of [count] it overlaps and the share of each cell's span it covers. */
        private fun spans(length: Int, count: Int): Array<List<Pair<Int, Double>>> = Array(length) { p ->
            val start = p.toDouble() * count / length
            val end = (p + 1).toDouble() * count / length
            val out = ArrayList<Pair<Int, Double>>(2)
            var c = start.toInt()
            while (c < count && c < end) {
                val overlap = minOf(end, c + 1.0) - maxOf(start, c.toDouble())
                if (overlap > 0) out += c to overlap
                c++
            }
            out
        }

        /** The hash [encode] wrote; null for anything else. */
        fun decode(text: String): PageHash? {
            val flat = text.endsWith("!")
            val body = if (flat) text.dropLast(1) else text
            val dot = body.indexOf('.')
            if (dot != 16 || body.length != 33) return null
            return runCatching {
                PageHash(java.lang.Long.parseUnsignedLong(body.substring(0, dot), 16), java.lang.Long.parseUnsignedLong(body.substring(dot + 1), 16), flat)
            }.getOrNull()
        }

        private fun hex(v: Long): String = java.lang.Long.toHexString(v).padStart(16, '0')
    }
}
