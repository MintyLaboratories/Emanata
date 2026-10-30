package com.infinity.comicreader.debug

import org.json.JSONObject

/**
 * Frame durations heard from the window ([android.view.FrameMetrics]), each against the
 * deadline it had: how many frames, how many missed it (jank), and percentiles.
 */
internal class FrameStats {
    private val durations = ArrayList<Long>()
    private var janky = 0

    /** A frame took [totalNs] where it had [deadlineNs]. */
    @Synchronized
    fun add(totalNs: Long, deadlineNs: Long) {
        durations += totalNs
        if (totalNs > deadlineNs) janky++
    }

    @Synchronized
    fun reset() {
        durations.clear()
        janky = 0
    }

    /** Frames, janky frames, and the 50th, 90th and 99th percentile and longest frame in ms. */
    @Synchronized
    fun toJson(): JSONObject {
        val sorted = durations.sorted()
        return JSONObject()
            .put("frames", sorted.size)
            .put("janky", janky)
            .put("p50Ms", ms(Stats.percentile(sorted, 50)))
            .put("p90Ms", ms(Stats.percentile(sorted, 90)))
            .put("p99Ms", ms(Stats.percentile(sorted, 99)))
            .put("maxMs", ms(sorted.lastOrNull() ?: 0))
    }

    private fun ms(ns: Long) = Stats.round1(ns / 1e6)
}
