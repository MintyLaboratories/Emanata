package com.infinity.comicreader.ai

import android.graphics.Bitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How a page's AI work ended. */
sealed class AiOutcome {
    /** Processed: [bitmap] shows in place of the plain page; [stored] when read back from disk rather than made, in [ms]. */
    class Done(val bitmap: Bitmap, val stored: Boolean = false, val ms: Long = 0) : AiOutcome()

    /** Never processed this time ([why]): the plain page stays, and the page is not tried again until [AiQueue.reset]. */
    class Skipped(val why: Fallback) : AiOutcome()

    /** Not now (heat, battery saver, models still downloading): tried again after a back-off. */
    object Later : AiOutcome()
}

/**
 * AI work on pages, one at a time in reading order: the pages [want]ed, first first (those
 * shown, then the next screen's). A page no longer wanted stops at once ([work]'s `canceled`
 * turns true); a page done or skipped is not tried again until [forget] or [reset]. A page put
 * off ([AiOutcome.Later]) holds the whole queue back for a back-off that doubles each time in
 * a row, from [backoffMs] up to [maxBackoffMs], so a hot or saving device is asked seldom; a
 * page wanted first in its place is tried at once (one kept on disk shows meanwhile).
 * A page skipped for a model that would not open ([Fallback.UNOPENED]) is tried again at
 * [resume], as the reader's models close while it is out of sight.
 * Work runs on [worker]; [deliver] runs on [scope]'s thread (the main one), as do all calls.
 */
internal class AiQueue<K : Any>(
    private val scope: CoroutineScope,
    private val worker: CoroutineDispatcher,
    private val work: (key: K, canceled: () -> Boolean) -> AiOutcome,
    private val deliver: (K, AiOutcome.Done) -> Unit,
    private val backoffMs: Long = BACKOFF_MS,
    private val maxBackoffMs: Long = MAX_BACKOFF_MS
) {
    private var wanted: List<K> = emptyList()
    private val finished = HashSet<K>()

    /** The pages of [finished] skipped for a model that would not open. */
    private val unopened = HashSet<K>()
    private var running: K? = null
    private var job: Job? = null
    private var wait: Job? = null
    private var backoffs = 0
    private var released = false
    private var paused = false

    /** The page put off last ([AiOutcome.Later]). */
    private var putOff: K? = null

    /** The page being worked on; null when none is. */
    val current: K? get() = running

    /** True while put off ([AiOutcome.Later]). */
    val waiting: Boolean get() = wait?.isActive == true

    /** Wants [keys], in this order, instead of those before; stops the page running when it is no longer among them. */
    fun want(keys: List<K>) {
        if (released) return
        wanted = keys
        running?.let { if (it !in keys) stop() }
        // Another page first: tried now, the back-off still doubling should it be put off too.
        if (waiting && keys.firstOrNull { it !in finished }.let { it != null && it != putOff }) {
            wait?.cancel()
            wait = null
        }
        next()
    }

    /** Forgets what was done and skipped, as when the chain changes: every page wanted is worked on again. */
    fun reset() {
        finished.clear()
        unopened.clear()
        stop()
        wait?.cancel()
        wait = null
        backoffs = 0
        next()
    }

    /** Ends a back-off now, as when a model finished downloading or battery saver turned off. */
    fun wake() {
        if (!waiting) return
        wait?.cancel()
        wait = null
        next()
    }

    /** Works on [key] again when wanted, as when its page was decoded afresh after leaving the cache. */
    fun forget(key: K) {
        finished -= key
        unopened -= key
    }

    /**
     * Stops the page running, its work lost, and starts none until [resume]: while the reader
     * is out of sight. What is wanted, done and skipped is kept.
     */
    fun pause() {
        if (paused) return
        paused = true
        stop()
    }

    /** Goes on from [pause] with the pages wanted, those skipped for a model that would not open tried again. */
    fun resume() {
        if (!paused) return
        paused = false
        finished -= unopened
        unopened.clear()
        next()
    }

    /** Stops for good. */
    fun release() {
        released = true
        stop()
        wait?.cancel()
    }

    private fun stop() {
        job?.cancel()
        job = null
        running = null
    }

    private fun next() {
        if (released || paused || job != null || waiting) return
        val key = wanted.firstOrNull { it !in finished } ?: return
        running = key
        // Started once it is the job: work that ends before the caller suspends goes on inline.
        val started = scope.launch(start = CoroutineStart.LAZY) {
            val outcome = try {
                withContext(worker) {
                    val self = coroutineContext.job
                    work(key) { self.isCancelled }
                }
            } catch (e: CancellationException) {
                return@launch
            } catch (t: Throwable) {
                AiOutcome.Skipped(Fallback.FAILED)
            }
            // A page no longer wanted: its bitmap may still be on its way to disk, so the GC frees it.
            if (job !== coroutineContext.job) return@launch
            job = null
            running = null
            when (outcome) {
                is AiOutcome.Done -> {
                    finished += key
                    backoffs = 0
                    deliver(key, outcome)
                }
                is AiOutcome.Skipped -> {
                    finished += key
                    if (outcome.why == Fallback.UNOPENED) unopened += key
                }
                AiOutcome.Later -> {
                    putOff = key
                    val ms = (backoffMs shl backoffs.coerceAtMost(MAX_DOUBLINGS)).coerceAtMost(maxBackoffMs)
                    backoffs++
                    wait = scope.launch {
                        delay(ms)
                        wait = null
                        next()
                    }
                    return@launch
                }
            }
            next()
        }
        job = started
        started.start()
    }

    companion object {
        /** The first back-off after a page is put off. */
        const val BACKOFF_MS = 15_000L

        /** The longest back-off. */
        const val MAX_BACKOFF_MS = 120_000L

        private const val MAX_DOUBLINGS = 10
    }
}
