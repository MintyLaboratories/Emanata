package minty.emanata.net

import java.util.concurrent.Executor

/**
 * Keeps one extra connection, a lane, beside a share's main connection, for long reads
 * ([SmbFs]). Made on [run] when a long read first wants it ([want]), unless one is there,
 * being made, or failed within [retryMs]; never while [thrifty]. Let go once unused for
 * [idleMs] (checked through [schedule] as that time comes), when a read on it fails
 * ([drop]), when wanted for another answer time than it was made for ([now]), and with the
 * main connection ([take]); one made meanwhile for a connection since let go is shut as
 * it arrives. [shut] runs outside this object's monitor.
 */
internal class Lanes<L : Any>(
    private val make: () -> L,
    private val shut: (L) -> Unit,
    private val answerMsOf: (L) -> Long,
    private val run: Executor,
    private val schedule: (delayMs: Long, check: () -> Unit) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idleMs: Long = IDLE_MS,
    private val retryMs: Long = RETRY_MS,
    private val thrifty: () -> Boolean = { false }
) {
    private var lane: L? = null
    private var making = false
    private var failedAt: Long? = null
    private var used = 0L

    /** Counts the main connections let go ([take]), so a lane made for an earlier one is never kept. */
    private var generation = 0

    /** The lane for a long read with [answerMs], marked as used; null without one, while [thrifty], or with one made for another answer time (then shut). */
    fun now(answerMs: Long): L? {
        val stale = synchronized(this) {
            val l = lane ?: return null
            if (answerMsOf(l) == answerMs) {
                if (thrifty()) return null
                used = clock()
                return l
            }
            lane = null
            l
        }
        shut(stale)
        return null
    }

    /** Makes the lane on [run], unless one is there, being made or failed lately, or while [thrifty]. */
    fun want() {
        val gen = synchronized(this) {
            if (lane != null || making || thrifty()) return
            failedAt?.let { if (clock() - it < retryMs) return }
            making = true
            generation
        }
        try {
            run.execute { add(gen) }
        } catch (e: RuntimeException) {
            synchronized(this) { making = false }
        }
    }

    private fun add(gen: Int) {
        val made = runCatching { make() }.getOrNull()
        val kept = synchronized(this) {
            making = false
            if (made == null) failedAt = clock()
            if (made == null || gen != generation || lane != null) {
                false
            } else {
                lane = made
                used = clock()
                true
            }
        }
        if (made == null) return
        if (kept) schedule(idleMs) { idleCheck(made) } else shut(made)
    }

    /** Shuts [l] once unused for [idleMs]; else checks again when that time comes. */
    private fun idleCheck(l: L) {
        val left = synchronized(this) {
            if (lane !== l) return
            val left = idleMs - (clock() - used)
            if (left <= 0) lane = null
            left
        }
        if (left <= 0) shut(l) else schedule(left) { idleCheck(l) }
    }

    /** Lets go of [l]; one that [failed] is not made again for [retryMs]. */
    fun drop(l: L, failed: Boolean) {
        synchronized(this) {
            if (lane === l) lane = null
            if (failed) failedAt = clock()
        }
        shut(l)
    }

    /** Takes the lane away with the main connection, for the caller to shut; one being made is shut as it arrives. */
    @Synchronized
    fun take(): L? {
        generation++
        return lane.also { lane = null }
    }

    /** True while a lane is there; for tests. */
    internal val isOpen: Boolean @Synchronized get() = lane != null

    companion object {
        /** How long a lane stays unused. */
        const val IDLE_MS = 60_000L

        /** How long a lane that failed is not made again. */
        const val RETRY_MS = 10 * 60_000L
    }
}
