package minty.emanata.util

import android.os.Build
import android.os.Trace
import minty.emanata.BuildConfig
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Trace sections for Perfetto and systrace: where the time of opening a book or turning a
 * page goes. Free while no trace records the app (a platform flag check). Debug and bench
 * builds only: [BuildConfig.TRACES] is false in release builds, so R8 drops every section
 * call and name there. Sections nest on their thread; their blocks must not suspend.
 */
object Traces {
    /** Longest section name the platform takes. */
    const val MAX_NAME = 127

    /** False where [Trace] is a stub that throws (plain JVM unit tests): nothing is traced there. */
    @PublishedApi
    internal val usable: Boolean = BuildConfig.TRACES && try {
        Trace.beginSection("Traces")
        Trace.endSection()
        true
    } catch (e: RuntimeException) {
        false
    }

    /** True while a trace records the app; always true below Android 10, which cannot tell. */
    fun recording(): Boolean =
        BuildConfig.TRACES && usable && (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || Trace.isEnabled())

    /** Runs [block] inside the section [name]. */
    inline fun <T> section(name: String, block: () -> T): T {
        if (!BuildConfig.TRACES || !usable) return block()
        Trace.beginSection(name.take(MAX_NAME))
        try {
            return block()
        } finally {
            Trace.endSection()
        }
    }

    /** Runs [block] inside the section [name] makes, made only while a trace records the app. */
    inline fun <T> section(name: () -> String, block: () -> T): T =
        if (BuildConfig.TRACES && recording()) section(name(), block) else block()

    /**
     * An async section begun now, ended by the first [Async.end] on any thread (Android 10+;
     * nothing below); [name] is made only while a trace records the app.
     */
    inline fun queued(name: () -> String): Async =
        if (BuildConfig.TRACES && recording()) Async(name().take(MAX_NAME), cookies.incrementAndGet()) else none

    /** One async section ([queued]). */
    class Async @PublishedApi internal constructor(private val name: String, private val cookie: Int, begin: Boolean = true) {
        private val open = AtomicBoolean(BuildConfig.TRACES && begin && usable)

        init {
            if (open.get() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Trace.beginAsyncSection(name, cookie)
        }

        /** Ends the section; later calls do nothing. */
        fun end() {
            if (BuildConfig.TRACES && open.compareAndSet(true, false) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Trace.endAsyncSection(name, cookie)
            }
        }
    }

    /** A section never begun. */
    @PublishedApi
    internal val none = Async("", 0, begin = false)

    @PublishedApi
    internal val cookies = AtomicInteger()
}
