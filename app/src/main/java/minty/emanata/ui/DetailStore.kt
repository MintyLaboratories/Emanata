package minty.emanata.ui

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
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
import minty.emanata.reader.Cancellation
import minty.emanata.reader.PageDetail
import java.util.concurrent.CancellationException

/**
 * Full-detail pages ([PageDetail]) keyed by [K], and the renders made from them for settled
 * zooms: details loaded from the page itself for the item on screen, and details made
 * elsewhere ([offer]: a model's output) for pages near the one read.
 *
 * - A loaded detail is kept only for the pages of one item (a page, or the two of a spread):
 *   asking for one drops every loaded page not named with it.
 * - A made detail is kept while its page is near ([rank]) and the details held fit [budget],
 *   the farthest let go of first, those of the item zoomed last ([trim]).
 * - A page's detail loads once and serves every render until it is dropped; a page
 *   dropped mid-render is freed once that render is done. A failed load is forgotten,
 *   so the next render tries again.
 * - Loads and renders run one at a time on [io], since each holds a full page or
 *   uses every core already.
 *
 * Main thread only, apart from what runs on [io]; [scope] must run on the main thread.
 */
internal class DetailStore<K>(private val scope: CoroutineScope, private val io: CoroutineDispatcher) {

    /** A page's detail; [made] when [offer]ed rather than loaded. */
    private class Entry(val made: Boolean = false) {
        lateinit var deferred: Deferred<PageDetail?>

        /** Renders using the detail; it is freed only once none is. */
        var users = 0

        /** The loaded detail; guarded by the entry. */
        var detail: PageDetail? = null
        var dropped = false
    }

    private val entries = HashMap<K, Entry>()
    private val gate = Semaphore(1)

    /** Most memory the details held may take, made ones let go of to stay within it ([trim]). */
    var budget: Long = MAX_BUDGET

    /** How near a page is to the one read, lower nearer; null for one too far to keep a made detail of. */
    var rank: (K) -> Int? = { null }

    /** The pages the last [prepare] or [render] named: the item zoomed, whose made details go last. */
    private var zoomed: Set<K> = emptySet()

    /** The pages currently held or loading. */
    val keys: Set<K> get() = entries.keys

    /** Memory the details loaded or made hold, those freed once their render ends aside. */
    val heldBytes: Long get() = entries.values.sumOf { e -> synchronized(e) { e.detail?.byteCount ?: 0L } }

    /** True when [key]'s detail is loaded, so a render needs no load first. */
    fun loaded(key: K): Boolean = entries[key]?.let { synchronized(it) { it.detail != null } } == true

    /** True when [key]'s detail is one made for it ([offer]). */
    fun made(key: K): Boolean = entries[key]?.made == true

    /** Starts loading [key] with [load] unless it is held, dropping every loaded page not in [keep]. */
    fun prepare(key: K, keep: Collection<K>, load: () -> PageDetail) {
        entry(key, keep, load)
    }

    /**
     * Renders [request] from [key]'s detail, loading it with [load] first if needed,
     * and hands the result to [done] on the main thread unless the returned job is
     * canceled first. Drops every loaded page not in [keep]. A failed load or render (logged)
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

    /**
     * Takes [detail], made for [key] elsewhere, as its detail in place of any held: renders
     * use it from now on. It is freed once let go of ([trim], [dropMade], [clear]).
     */
    fun offer(key: K, detail: PageDetail) {
        drop(key)
        val entry = Entry(made = true)
        entry.detail = detail
        entry.deferred = CompletableDeferred(detail)
        entries[key] = entry
        trim()
    }

    /**
     * Lets go of made details to keep what is held within [budget]: those of pages too far
     * ([rank] null) at once, then the farthest first while over it, the item zoomed last.
     * Call when what [rank] says may have changed.
     */
    fun trim() {
        for ((key, entry) in entries.entries.toList()) {
            if (entry.made && key !in zoomed && rank(key) == null) drop(key)
        }
        var held = heldBytes
        if (held <= budget) return
        val farthestFirst = entries.filterValues { it.made }.keys
            .sortedWith(compareBy<K>({ it in zoomed }, { -(rank(it) ?: 0) }))
        for (key in farthestFirst) {
            if (held <= budget) break
            held -= entries[key]?.let { synchronized(it) { it.detail?.byteCount } } ?: 0L
            drop(key)
        }
    }

    /** Drops the loaded details of [keys], pages no longer shown; made ones stay while [trim] keeps them, as for any page not zoomed. */
    fun drop(keys: Collection<K>) {
        zoomed = zoomed - keys.toSet()
        keys.forEach { if (entries[it]?.made == false) drop(it) }
        trim()
    }

    /** Drops every made detail, as when what made them changed. */
    fun dropMade() = dropMade(entries.keys.toList())

    /** Drops the made details of [keys], as when what made those changed; loaded ones stay. */
    fun dropMade(keys: Collection<K>) = keys.forEach { if (entries[it]?.made == true) drop(it) }

    /** Drops every page, as when memory runs low. */
    fun clear() = entries.keys.toList().forEach { drop(it) }

    private fun entry(key: K, keep: Collection<K>, load: () -> PageDetail): Entry {
        entries.entries.filter { (k, e) -> k != key && k !in keep && !e.made }.forEach { drop(it.key) }
        zoomed = keep.toHashSet().apply { add(key) }
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
                // What it holds now counts toward the budget.
                if (!failed) trim()
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
         * Most memory the details held may take once loaded: two of the largest pages drawn.
         * Loading one briefly takes more (see [minty.emanata.reader.Target.detail]).
         */
        const val MAX_BUDGET = 200L * 1024 * 1024

        /** The detail budget on a device with [totalMem] bytes of RAM: a sixteenth of it, at most [MAX_BUDGET]. */
        fun budget(totalMem: Long): Long = (totalMem / 16).coerceIn(MIN_BUDGET, MAX_BUDGET)

        private const val MIN_BUDGET = 32L * 1024 * 1024
    }
}
