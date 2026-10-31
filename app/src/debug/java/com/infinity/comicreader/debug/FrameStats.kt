package com.infinity.comicreader.debug

import org.json.JSONObject

/**
 * Frames heard from a window ([android.view.FrameMetrics]): each one's duration against
 * the deadline it had, giving how many frames, how many were late (past the deadline),
 * how many janky (past twice the deadline, as Jetpack JankStats counts by default: a
 * stutter, not one late frame swapping a page in) and percentiles; and when each began
 * drawing and completed, for [drawnBy].
 */
internal class FrameStats {
    private val durations = ArrayList<Long>()
    private var late = 0
    private var janky = 0
    private val drawStarts = ArrayList<Long>()
    private val completions = ArrayList<Long>()

    /**
     * A frame took [totalNs] where it had [deadlineNs]; it began drawing at [drawStartNs] and
     * completed (handed to the display) at [completedNs]. A window's [first] frame counts for
     * [drawnBy] only, its duration being the window's start.
     */
    @Synchronized
    fun add(totalNs: Long, deadlineNs: Long, drawStartNs: Long = -1, completedNs: Long = -1, first: Boolean = false) {
        if (drawStartNs >= 0 && completedNs >= 0) {
            drawStarts += drawStartNs
            completions += completedNs
        }
        if (first) return
        durations += totalNs
        if (totalNs > deadlineNs) late++
        if (totalNs > 2 * deadlineNs) janky++
    }

    /** When the first frame that began drawing at or after [at] completed; -1 while none has been heard. */
    @Synchronized
    fun drawnBy(at: Long): Long {
        for (i in drawStarts.indices) if (drawStarts[i] >= at) return completions[i]
        return -1
    }

    @Synchronized
    fun reset() {
        durations.clear()
        late = 0
        janky = 0
        drawStarts.clear()
        completions.clear()
    }

    /** Frames, late and janky frames, and the 50th, 90th and 99th percentile and longest frame in ms. */
    @Synchronized
    fun toJson(): JSONObject {
        val sorted = durations.sorted()
        return JSONObject()
            .put("frames", sorted.size)
            .put("late", late)
            .put("janky", janky)
            .put("p50Ms", ms(Stats.percentile(sorted, 50)))
            .put("p90Ms", ms(Stats.percentile(sorted, 90)))
            .put("p99Ms", ms(Stats.percentile(sorted, 99)))
            .put("maxMs", ms(sorted.lastOrNull() ?: 0))
    }

    private fun ms(ns: Long) = Stats.round1(ns / 1e6)
}
