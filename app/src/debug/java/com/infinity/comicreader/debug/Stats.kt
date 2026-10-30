package com.infinity.comicreader.debug

import org.json.JSONObject
import kotlin.math.ceil
import kotlin.math.roundToLong

/** Small statistics for benchmark results. */
internal object Stats {
    /** The [p]th percentile (nearest rank) of [sorted], ascending; 0 when empty. */
    fun percentile(sorted: List<Long>, p: Int): Long {
        if (sorted.isEmpty()) return 0
        val rank = ceil(p / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    /** Count, median, 90th percentile and largest of [values] (ms), leaving out the unknown (negative) ones. */
    fun summary(values: List<Long>): JSONObject {
        val known = values.filter { it >= 0 }.sorted()
        return JSONObject()
            .put("n", known.size)
            .put("median", percentile(known, 50))
            .put("p90", percentile(known, 90))
            .put("max", known.lastOrNull() ?: 0)
    }

    /** [ns] in whole milliseconds; -1 stays -1. */
    fun ms(ns: Long): Long = if (ns < 0) -1 else (ns / 1e6).roundToLong()

    /** [x] to one decimal place. */
    fun round1(x: Double): Double = (x * 10).roundToLong() / 10.0
}
