package com.infinity.comicreader.ui

import kotlin.math.abs

/** Orders pending grid work by distance from where the user looks. */
object NearestFirst {
    /** The member of [pending] closest to [center]; of two as close, the earlier. Null when empty. */
    fun pick(pending: Collection<Int>, center: Int): Int? {
        var best: Int? = null
        for (p in pending) {
            val b = best
            if (b == null || abs(p - center) < abs(b - center) || (abs(p - center) == abs(b - center) && p < b)) best = p
        }
        return best
    }

    /** The middle of the cells [first] to [last] in view; 0 when nothing is in view. */
    fun middle(first: Int, last: Int): Int = if (first < 0 || last < first) 0 else (first + last) / 2
}
