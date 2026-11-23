package com.infinity.comicreader.ui

import android.util.Log
import com.infinity.comicreader.reader.Cancellation
import com.infinity.comicreader.reader.PageDetail
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.CancellationException

/**
 * Full-detail pages ([PageDetail]) for the item on screen, keyed by [K], and the
 * renders made from them for settled zooms.
 *
 * - Only the pages of one item (a page, or the two of a spread) are kept: asking for
 *   one drops every page not named with it, so memory stays at one item's worth.
 * - A page's detail loads once and serves every render until it is dropped; a page
 *   dropped mid-render is freed once that render is done. A failed load is forgotten,
 *   so the next render tries again.
 * - Loads and renders run one at a time on [io], since each holds a full page or
 *   uses every core already.
 *
 * Main thread only, apart from what runs on [io]; [scope] must run on the main thread.
 */
internal class DetailStore<K>(private val scope: CoroutineScope, private val io: CoroutineDispatcher) {

    private class Entry {
        lateinit var deferred: Deferred<PageDetail?>

        /** Renders using the detail; it is freed only once none is. */
        var users = 0

        /** The loaded detail; guarded by the entry. */
        var detail: PageDetail? = null
        var dropped = false
    }

    private val entries = HashMap<K, Entry>()
    private val gate = Semaphore(1)

    /** The pages currently held or loading; for tests. */
    val keys: Set<K> get() = entries.keys

    /** True when [key]'s detail is loaded, so a render needs no load first. */
    fun loaded(key: K): Boolean = entries[key]?.let { synchronized(it) { it.detail != null } } == true

    /** Starts loading [key] with [load] unless it is held, dropping every page not in [keep]. */
    fun prepare(key: K, keep: Collection<K>, load: () -> PageDetail) {
        entry(key, keep, load)
    }

    /**
     * Renders [request] from [key]'s detail, loading it with [load] first if needed,
     * and hands the result to [done] on the main thread unless the returned job is
     * canceled first. Drops every page not in [keep]. A failed load or render (logged)
     * calls [failed] instead; the page dropped before its render starts calls [dropped],
     * as [failed] unless told otherwise: asked again, it loads anew.
     */
    fun <R> render(
        key: K,
        keep: Collection<K>,
        load: () -> PageDetail,
        request: (PageDetail) -> R,
        failed: () -> Unit = {},
        dropped: () -> Unit = failed,
        done: (R) -> Unit
    ): Job {
        val entry = entry(key, keep, load)
        return scope.launch {
            val job = coroutineContext.job
            // A drop cancels the load: that fails this render, unless it was canceled itself.
            val detail = try {
                entry.deferred.await()
            } catch (e: CancellationException) {
                job.ensureActive()
                null
            }
            if (entry.dropped) {
                dropped()
                return@launch
            }
            if (detail == null) {
                failed()
                return@launch
            }
            entry.users++
            val result = try {
                Result.success(gate.withPermit {
                    withContext(io) { Cancellation.during({ job.isCancelled }) { request(detail) } }
                })
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w(TAG, "Detail render failed", t)
                Result.failure(t)
            } finally {
                entry.users--
                if (entry.dropped && entry.users == 0) free(entry)
            }
            result.fold(done) { failed() }
        }
    }

    /** Drops every page but [keep]. */
    fun retain(keep: Collection<K>) {
        entries.keys.filter { it !in keep }.forEach { drop(it) }
    }

    /** Drops [keys]. */
    fun drop(keys: Collection<K>) = keys.forEach { drop(it) }

    /** Drops every page, as when memory runs low. */
    fun clear() = entries.keys.toList().forEach { drop(it) }

    private fun entry(key: K, keep: Collection<K>, load: () -> PageDetail): Entry {
        entries.keys.filter { it != key && it !in keep }.forEach { drop(it) }
        entries[key]?.let { return it }
        val entry = Entry()
        // Held before the load starts: a load that fails at once is forgotten by the handler below, which may run within this call.
        entries[key] = entry
        entry.deferred = scope.async(io) {
            gate.withPermit {
                val job = coroutineContext.job
                val detail = try {
                    Cancellation.during({ job.isCancelled }) { load() }
                } catch (e: CancellationException) {
                    null
                } catch (t: Throwable) {
                    Log.w(TAG, "Detail load failed", t)
                    null
                }
                synchronized(entry) {
                    if (entry.dropped) detail?.release() else entry.detail = detail
                }
                if (entry.dropped) null else detail
            }
        }
        entry.deferred.invokeOnCompletion {
            // Runs where the load ended; hop back before touching [entries].
            scope.launch {
                val failed = synchronized(entry) { entry.detail == null }
                if (failed && entries[key] === entry) entries.remove(key)
            }
        }
        return entry
    }

    private fun drop(key: K) {
        val entry = entries.remove(key) ?: return
        synchronized(entry) { entry.dropped = true }
        entry.deferred.cancel()
        if (entry.users == 0) free(entry)
    }

    private fun free(entry: Entry) {
        synchronized(entry) {
            entry.detail?.release()
            entry.detail = null
        }
    }

    companion object {
        private const val TAG = "DetailStore"

        /**
         * Most memory one item's full-detail pages may hold once loaded: two of the largest
         * pages drawn. Loading one briefly takes more (see [com.infinity.comicreader.reader.Target.detail]).
         */
        const val MAX_BUDGET = 200L * 1024 * 1024

        /** The detail budget on a device with [totalMem] bytes of RAM: a sixteenth of it, at most [MAX_BUDGET]. */
        fun budget(totalMem: Long): Long = (totalMem / 16).coerceIn(MIN_BUDGET, MAX_BUDGET)

        private const val MIN_BUDGET = 32L * 1024 * 1024
    }
}
