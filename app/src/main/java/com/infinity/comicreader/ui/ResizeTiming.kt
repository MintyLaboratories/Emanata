package com.infinity.comicreader.ui

import android.os.SystemClock
import android.util.Log
import com.infinity.comicreader.util.Traces

/**
 * Times one resize of the pages (a new fit, page gap or item size) for profiling: until
 * its first frame is drawn, and until every page it left standing in has its exact
 * bitmap. Logged under "ResizeTiming" and traced as the async sections "Resize shown" and
 * "Resize exact" ([Traces], Android 10+), for logcat or Perfetto. A new resize ends the last one.
 *
 * Main thread only.
 */
internal class ResizeTiming(private val now: () -> Long = SystemClock::uptimeMillis) {

    private var start = -1L
    private var shownSection = Traces.none
    private var exactSection = Traces.none
    private var shown = false
    private val waiting = HashSet<Any>()

    /** True while [waiting] or the first frame is still due; for tests. */
    val running: Boolean get() = start >= 0

    /** A resize starts now. */
    fun begin() {
        shownSection.end()
        exactSection.end()
        start = now()
        shown = false
        waiting.clear()
        shownSection = Traces.queued { SHOWN }
        exactSection = Traces.queued { EXACT }
    }

    /** [slot] shows a stand-in until its exact bitmap arrives ([done]). */
    fun standIn(slot: Any) {
        if (running) waiting += slot
    }

    /** The resize's first frame is drawn; its ms since [begin], or null if already counted. */
    fun drawn(): Long? {
        if (!running || shown) return null
        shown = true
        shownSection.end()
        val ms = now() - start
        Log.d(TAG, "Resize shown in $ms ms, ${waiting.size} page(s) standing in")
        finishIfDone()
        return ms
    }

    /**
     * [slot] shows its exact bitmap, or no longer waits for it; the ms since [begin] once
     * that was the last page standing in, otherwise null.
     */
    fun done(slot: Any): Long? {
        if (!running || !waiting.remove(slot)) return null
        return finishIfDone()
    }

    private fun finishIfDone(): Long? {
        if (!shown || waiting.isNotEmpty()) return null
        exactSection.end()
        val ms = now() - start
        Log.d(TAG, "Resize exact in $ms ms")
        start = -1L
        return ms
    }

    private companion object {
        const val TAG = "ResizeTiming"
        const val SHOWN = "Resize shown"
        const val EXACT = "Resize exact"
    }
}
