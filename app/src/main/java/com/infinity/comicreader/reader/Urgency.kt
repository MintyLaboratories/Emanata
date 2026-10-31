package com.infinity.comicreader.reader

/**
 * Whether a thread's pixel work is waited on: a page on screen is, a page read ahead or a
 * preview is not. [NativeResampler] gives waited-on work its full worker count and the
 * rest its later budget, asking again before each band, so a page read ahead that comes
 * on screen mid-resample speeds up. Work outside [during] counts as waited on.
 */
object Urgency {
    private val mark = ThreadLocal<() -> Boolean>()

    private val always: () -> Boolean = { true }

    private val never: () -> Boolean = { false }

    /** Runs [block] on this thread, whose work is waited on whenever [urgent] returns true. */
    fun <T> during(urgent: () -> Boolean, block: () -> T): T {
        val outer = mark.get()
        mark.set(urgent)
        try {
            return block()
        } finally {
            if (outer == null) mark.remove() else mark.set(outer)
        }
    }

    /** Runs [block] as work nobody waits on. */
    fun <T> later(block: () -> T): T = during(never, block)

    /** This thread's mark, for workers on its behalf to poll; waited on outside [during]. */
    fun current(): () -> Boolean = mark.get() ?: always

    /** True only inside [during] while its work is waited on: a page on screen, not covers, previews or scans. */
    fun waited(): Boolean = mark.get()?.invoke() == true

    /** True inside [during] for a page, shown or read ahead; not for [later] work (previews) or work outside [during] (covers, scans). */
    fun page(): Boolean = mark.get().let { it != null && it !== never }
}
