package com.infinity.comicreader.util

import android.os.Build
import android.os.Trace
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Trace sections for Perfetto and systrace: where the time of opening a book or turning a
 * page goes. Free while no trace records the app (a platform flag check), so they stay in
 * every build. Sections nest on their thread; their blocks must not suspend.
 */
object Traces {
    /** Longest section name the platform takes. */
    const val MAX_NAME = 127

    /** True while a trace records the app; always true below Android 10, which cannot tell. */
    fun recording(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || Trace.isEnabled()

    /** Runs [block] inside the section [name]. */
    inline fun <T> section(name: String, block: () -> T): T {
        Trace.beginSection(name.take(MAX_NAME))
        try {
            return block()
        } finally {
            Trace.endSection()
        }
    }

    /** Runs [block] inside the section [name] makes, made only while a trace records the app. */
    inline fun <T> section(name: () -> String, block: () -> T): T =
        if (recording()) section(name(), block) else block()

    /** An async section begun now, ended by the first [Async.end] on any thread (Android 10+; nothing below). */
    fun queued(name: String): Async = Async(name.take(MAX_NAME), cookies.incrementAndGet())

    /** One async section ([queued]). */
    class Async internal constructor(private val name: String, private val cookie: Int) {
        private val open = AtomicBoolean(true)

        init {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Trace.beginAsyncSection(name, cookie)
        }

        /** Ends the section; later calls do nothing. */
        fun end() {
            if (open.compareAndSet(true, false) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Trace.endAsyncSection(name, cookie)
        }
    }

    private val cookies = AtomicInteger()
}
