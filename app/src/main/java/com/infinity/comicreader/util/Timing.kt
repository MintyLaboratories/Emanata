package com.infinity.comicreader.util

import android.os.SystemClock
import android.util.Log

/**
 * Timestamps of slow work (connecting to shares, listing, facts, covers) logged under
 * [TAG] in debuggable builds only, for profiling on a device: each line starts with the
 * uptime in milliseconds, so lines from different threads line up.
 */
object Timing {
    const val TAG = "Timing"

    /** Set at startup for debuggable builds. */
    @Volatile
    var enabled = false

    /** Logs [what] with the time now. */
    fun mark(what: String) {
        if (enabled) runCatching { Log.d(TAG, "${SystemClock.uptimeMillis()} $what") }
    }

    /** Runs [block], logging [what] with how long it took, and whether it failed. */
    inline fun <T> span(what: String, block: () -> T): T {
        if (!enabled) return block()
        val start = SystemClock.uptimeMillis()
        var failure: Throwable? = null
        try {
            return block()
        } catch (t: Throwable) {
            failure = t
            throw t
        } finally {
            mark("$what ${SystemClock.uptimeMillis() - start} ms${failure?.let { " (failed: ${it.javaClass.simpleName})" }.orEmpty()}")
        }
    }
}
