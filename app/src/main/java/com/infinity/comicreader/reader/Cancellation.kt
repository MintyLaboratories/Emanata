package com.infinity.comicreader.reader

/**
 * Lets blocking decode work stop once its caller no longer wants the result. [during]
 * marks the calling thread for the length of a blocking call; long loops, such as the
 * resampler's, poll the mark taken with [current] and throw
 * [java.util.concurrent.CancellationException] once it is set.
 */
object Cancellation {
    private val mark = ThreadLocal<() -> Boolean>()

    private val never: () -> Boolean = { false }

    /** Runs [block] on this thread, which counts as cancelled whenever [cancelled] returns true. */
    fun <T> during(cancelled: () -> Boolean, block: () -> T): T {
        val outer = mark.get()
        mark.set(cancelled)
        try {
            return block()
        } finally {
            if (outer == null) mark.remove() else mark.set(outer)
        }
    }

    /** This thread's mark, for other threads working on its behalf to poll; never set outside [during]. */
    fun current(): () -> Boolean = mark.get() ?: never
}
