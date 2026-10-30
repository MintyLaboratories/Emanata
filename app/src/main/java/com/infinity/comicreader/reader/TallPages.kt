package com.infinity.comicreader.reader

import kotlin.math.abs

/**
 * Pages much taller than the screen: a page is tall when its height / width is at least
 * a threshold times the portrait screen's height / width (the portrait shape even in
 * landscape). A book, or a folder of loose images, is tall when at least half of its
 * pages of known size are.
 */
object TallPages {
    /** The threshold, in hundredths: 1.01 to 2.00, 1.20 by default. */
    const val MIN_THRESHOLD = 101
    const val MAX_THRESHOLD = 200
    const val DEFAULT_THRESHOLD = 120

    /** Images of a folder judged for it, nearest the one opened ([nearest]). */
    const val FOLDER_SAMPLE = 16

    /** Height / width of a portrait screen [w] x [h], whichever way it is turned. */
    fun portraitAspect(w: Int, h: Int): Double = if (w <= 0 || h <= 0) 1.0 else maxOf(w, h).toDouble() / minOf(w, h)

    /** The shape a page must reach to be tall: [threshold] (hundredths) times [screenAspect]. */
    fun minAspect(threshold: Int, screenAspect: Double): Double =
        threshold.coerceIn(MIN_THRESHOLD, MAX_THRESHOLD) / 100.0 * screenAspect

    /** True when a `[width, height]` page [size] is tall. */
    fun isTall(size: IntArray?, threshold: Int, screenAspect: Double): Boolean {
        if (size == null || size.size < 2 || size[0] <= 0 || size[1] <= 0) return false
        val ratio = size[1].toDouble() / size[0]
        val min = minAspect(threshold, screenAspect)
        return ratio >= min || abs(ratio - min) < 1e-9
    }

    /** The tall pages among [sizes], by index; unknown sizes are not tall. */
    fun of(sizes: List<IntArray?>, threshold: Int, screenAspect: Double): Set<Int> =
        sizes.indices.filterTo(HashSet()) { isTall(sizes[it], threshold, screenAspect) }

    /** True when at least half the pages of known size among [sizes] are tall; null when none is known. */
    fun mostlyTall(sizes: List<IntArray?>, threshold: Int, screenAspect: Double): Boolean? {
        val known = sizes.filterNotNull().filter { it.size >= 2 && it[0] > 0 && it[1] > 0 }
        if (known.isEmpty()) return null
        return known.count { isTall(it, threshold, screenAspect) } * 2 >= known.size
    }

    /** Up to [count] indices of [size] items nearest [at], it first, then those after before those before. */
    fun nearest(size: Int, at: Int, count: Int = FOLDER_SAMPLE): List<Int> {
        if (size <= 0 || count <= 0) return emptyList()
        val center = at.coerceIn(0, size - 1)
        val out = ArrayList<Int>(minOf(size, count))
        out += center
        var step = 1
        while (out.size < minOf(size, count)) {
            if (center + step < size) out += center + step
            if (out.size < count && center - step >= 0) out += center - step
            step++
        }
        return out
    }
}
