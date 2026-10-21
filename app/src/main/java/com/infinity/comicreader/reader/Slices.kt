package com.infinity.comicreader.reader

import com.infinity.comicreader.data.ReadingDirection
import kotlin.math.abs

/**
 * Tells a book whose pages are slices of one tall strip, read top to bottom (webtoons,
 * manhua), from what its first [SAMPLE] pages are like:
 *  - their shape, from the image headers: very tall pages (height [MIN_RATIO] to
 *    [MAX_RATIO] times the width) sharing one width; varying heights, as cutting at the
 *    gaps between panels leaves, settle it alone;
 *  - their seams ([Seams]): the bottom of one slice runs on into the top of the next. Flat
 *    seams (blank gaps) say nothing; at least [MIN_SEAMS] that tell, mostly running on,
 *    settle a shape that alone does not.
 * Free of Android types.
 */
object Slices {
    /** Pages from the start that are looked at. */
    const val SAMPLE = 16

    /** Height over width of a slice. */
    const val MIN_RATIO = 2.0
    const val MAX_RATIO = 10.0

    /** How far widths may differ and still count as one, as a share of the width. */
    const val WIDTH_TOLERANCE = 0.01

    /** Slices that settle the shape alone, and the share of the known pages they must be. */
    const val SURE_SLICES = 4
    const val SURE_SHARE = 0.8

    /** Slices that make the shape worth checking the seams of, and their share of the known pages. */
    const val LIKELY_SLICES = 3
    const val LIKELY_SHARE = 0.5

    /** Seams that must tell, and the share of them that must run on. */
    const val MIN_SEAMS = 5
    const val SEAM_SHARE = 0.7

    /** Leading pages under [MIN_RATIO] that settle a book as not slices, so no more sizes are read. */
    const val ORDINARY_LEAD = 4

    /** What the pages' shape says. */
    enum class Shape {
        /** Not slices. */
        NONE,

        /** Tall pages of one width, but uniform heights or a mix: the seams decide. */
        LIKELY,

        /** Slices: tall pages of one width and varying heights, nearly all of them. */
        SURE
    }

    /**
     * The shape of pages of [sizes] (`[width, height]`, null where unknown), the first
     * [SAMPLE] of them. The slices are the tall pages of the most common width; their
     * heights vary when at least half of them, and three, are heights of their own.
     */
    fun shape(sizes: List<IntArray?>): Shape {
        val known = sizes.take(SAMPLE).filterNotNull().filter { it.size >= 2 && it[0] > 0 && it[1] > 0 }
        val slices = slicesOf(known)
        if (slices.size < LIKELY_SLICES) return Shape.NONE
        val share = slices.size.toDouble() / known.size
        val heights = slices.map { it[1] }.distinct().size
        val varying = heights >= 3 && heights * 2 >= slices.size
        return when {
            slices.size >= SURE_SLICES && share >= SURE_SHARE && varying -> Shape.SURE
            share >= LIKELY_SHARE -> Shape.LIKELY
            else -> Shape.NONE
        }
    }

    /** True when the first [ORDINARY_LEAD] of [sizes] are all known and none is tall: ordinary pages, not slices. */
    fun ordinary(sizes: List<IntArray?>): Boolean =
        sizes.size >= ORDINARY_LEAD && sizes.take(ORDINARY_LEAD).all { it != null && it.size >= 2 && it[0] > 0 && it[1] > 0 && it[1].toDouble() / it[0] < MIN_RATIO }

    /** The tall pages among [known] that share the most common width. */
    private fun slicesOf(known: List<IntArray>): List<IntArray> {
        val tall = known.filter { it[1].toDouble() / it[0] in MIN_RATIO..MAX_RATIO }
        if (tall.isEmpty()) return emptyList()
        return tall.map { width -> tall.filter { sameWidth(it[0], width[0]) } }.maxBy { it.size }
    }

    private fun sameWidth(a: Int, b: Int) = abs(a - b) <= maxOf(1.0, WIDTH_TOLERANCE * maxOf(a, b))

    /**
     * The pairs of pages, by index among [sizes], whose seam [verdict] needs: each slice
     * and the next page when that is a slice of the same width.
     */
    fun seamPairs(sizes: List<IntArray?>): List<Int> {
        val sample = sizes.take(SAMPLE)
        val known = sample.filterNotNull().filter { it.size >= 2 && it[0] > 0 && it[1] > 0 }
        val slices = slicesOf(known)
        if (slices.isEmpty()) return emptyList()
        val width = slices[0][0]
        fun slice(i: Int) = sample.getOrNull(i)?.let { it.size >= 2 && it[0] > 0 && sameWidth(it[0], width) && it[1].toDouble() / it[0] in MIN_RATIO..MAX_RATIO } == true
        return (0 until sample.size - 1).filter { slice(it) && slice(it + 1) }
    }

    /**
     * What [shape] and the [seams] found for its [seamPairs] (true where the picture runs on,
     * false where it does not, null where a seam is too flat to tell) decide: top to bottom
     * from the shape alone, or from the shape and its seams; null for anything else.
     */
    fun verdict(shape: Shape, seams: List<Boolean?> = emptyList()): DirectionDetection.Detected? = when (shape) {
        Shape.SURE -> DirectionDetection.Detected(ReadingDirection.TTB, DirectionDetection.Source.SHAPE)
        Shape.NONE -> null
        Shape.LIKELY -> {
            val told = seams.filterNotNull()
            val on = told.count { it }
            if (told.size >= MIN_SEAMS && on >= SEAM_SHARE * told.size) {
                DirectionDetection.Detected(ReadingDirection.TTB, DirectionDetection.Source.SEAMS)
            } else {
                null
            }
        }
    }
}
