package com.infinity.comicreader.ai

/**
 * How a model maps a tile, as [ModelProbe] measures it: its output is [scale] times its input
 * less [trim] output pixels on each side, and each output pixel depends on input pixels up to
 * [context] away on each side.
 */
data class Geometry(val scale: Int, val trim: Int, val context: Int) {
    init {
        require(scale >= 1 && trim >= 0 && context >= 0) { "Bad geometry $scale/$trim/$context" }
    }

    /**
     * Input pixels on each side of a tile whose output is dropped: enough that every pixel kept
     * exists ([trim]) and saw all its [context] within the tile, so tiles join without seams.
     */
    val margin: Int get() = maxOf(context, (trim + scale - 1) / scale)
}

/**
 * One tile of a page: the model reads [input] (which may reach past the page, whose edge
 * pixels repeat), and output window [crop] goes to the output page at ([dx], [dy]).
 */
data class Tile(val input: Box, val crop: Box, val dx: Int, val dy: Int)

/**
 * Covers a page with fixed-size tiles: every tile's input is [size] x [size], so the GPU sees
 * one shape. Each tile keeps its core, its input less at least [Geometry.margin] on each side;
 * cores cover the page edge to edge, the last in a row or column moved back to end at the
 * page's edge (overlapping the one before), or up to [ALIGN] pixels past it.
 *
 * Cores are a multiple of [ALIGN] long and start at multiples of it, so every tile's input
 * starts at the same place in that period: GPU kernels that work on blocks of pixels round
 * each pixel alike whichever tile it falls in, and tiles join without fp16 seams.
 */
object TilePlan {
    /** The smallest core worth a tile, in input pixels. */
    const val MIN_CORE = 16

    /** The period cores start at and are a multiple of, in input pixels. */
    const val ALIGN = 8

    /** Core input pixels of a tile of [size] for [geometry], a multiple of [ALIGN]; at most 0 when the margins leave none. */
    fun core(size: Int, geometry: Geometry): Int {
        val room = size - 2 * geometry.margin
        return if (room <= 0) room else room / ALIGN * ALIGN
    }

    /** True when tiles of [size] keep at least [MIN_CORE] pixels of core for [geometry]. */
    fun fits(size: Int, geometry: Geometry): Boolean = core(size, geometry) >= MIN_CORE

    /**
     * Where cores of [core] pixels start along an axis of [length], at multiples of [align]
     * (which [core] is a multiple of): every core starts inside it, the last ends at its end
     * or up to [align] - 1 pixels past it.
     */
    fun starts(length: Int, core: Int, align: Int = ALIGN): IntArray {
        require(length > 0 && core > 0 && align > 0 && core % align == 0) { "Empty axis or core, or unaligned core" }
        if (length <= core) return intArrayOf(0)
        val n = (length + core - 1) / core
        val last = (length - core + align - 1) / align * align
        return IntArray(n) { i -> if (i == n - 1) last else i * core }
    }

    /** The tiles covering a [width] x [height] page with [size] x [size] inputs, rows top to bottom, each left to right. */
    fun tiles(width: Int, height: Int, size: Int, geometry: Geometry): List<Tile> {
        require(width > 0 && height > 0) { "Empty page" }
        require(fits(size, geometry)) { "Tile $size too small for margin ${geometry.margin}" }
        val core = core(size, geometry)
        val m = geometry.margin
        val s = geometry.scale
        val off = m * s - geometry.trim
        val xs = starts(width, core)
        val ys = starts(height, core)
        val out = ArrayList<Tile>(xs.size * ys.size)
        for (y in ys) {
            val h = minOf(core, height - y)
            for (x in xs) {
                val w = minOf(core, width - x)
                out += Tile(Box(x - m, y - m, size, size), Box(off, off, w * s, h * s), x * s, y * s)
            }
        }
        return out
    }

    /** Output pixels of a [size] input tile: [Geometry.scale] times it, less the trim on both sides. */
    fun outputSize(size: Int, geometry: Geometry): Int = size * geometry.scale - 2 * geometry.trim
}
