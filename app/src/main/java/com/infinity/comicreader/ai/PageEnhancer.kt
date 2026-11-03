package com.infinity.comicreader.ai

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.infinity.comicreader.data.BookId
import com.infinity.comicreader.data.BookIds
import com.infinity.comicreader.data.Library
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.reader.Cancellation
import com.infinity.comicreader.reader.HiddenPages
import com.infinity.comicreader.reader.NativeResampler
import com.infinity.comicreader.reader.PageSource
import com.infinity.comicreader.reader.Target
import com.infinity.comicreader.reader.Urgency
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** AI processing of the reader's pages, as [com.infinity.comicreader.ui.PageAdapter] asks for it through an [AiQueue]. */
interface PageEnhancer {
    /** The thread [enhance] runs on. */
    val worker: CoroutineDispatcher

    /** True when the pages of the book [source] reads go through AI: a chain is chosen for it. Cheap; any thread. */
    fun enhances(source: PageSource): Boolean

    /**
     * Page [index] of [source], shown as [target] lays it out at [width] x [height], processed:
     * read back from disk, or made from the page at full detail and stored. Blocking; stops
     * once [canceled].
     */
    fun enhance(source: PageSource, index: Int, target: Target, width: Int, height: Int, canceled: () -> Boolean): AiOutcome
}

/**
 * The reader's AI processing: each book's chain ([AiChain.forBook]: its own picks, else the
 * global ones), models opened once as [Session]s and kept until [close], pages kept on disk
 * ([ProcessedStore]). A page is processed from its full-detail decode (at most
 * [MAX_INPUT_PIXELS]) and resampled to exactly the plain page's size, so it swaps in without
 * a jump. Puts pages off ([AiOutcome.Later]) while [gate] says stop (heat, battery saver) or a
 * model is still downloading ([download] starts one missing); skips pages of vector books,
 * pages drawn in bands, and pages an upscaler would only shrink.
 */
class ReaderAi internal constructor(
    private val prefs: Prefs,
    private val ownPicks: (BookId) -> AiPicks?,
    private val models: ModelStore,
    private val store: ProcessedStore,
    private val runtime: InferenceRuntime,
    private val gate: () -> Fallback?,
    private val download: (CatalogModel) -> Unit,
    private val threads: Int = NativeResampler.threads,
    private val limits: PageLimits = PageLimits()
) : PageEnhancer, AutoCloseable {

    override val worker: CoroutineDispatcher get() = WORKER

    /** True once [session] opened a model, which [close] then closes. */
    @Volatile
    private var opened = false

    /** Each book's chain by its key ("" for none), until [refresh]. */
    private val chains = ConcurrentHashMap<String, List<CatalogModel>>()

    /** Models opened so far by catalog id; a model that would not open maps to none. Worker thread only. */
    private val sessions = HashMap<String, Session?>()

    /** Reads every book's chain again: after the global or a book's own picks changed. */
    fun refresh() = chains.clear()

    /** The chain the pages of the book keyed [book] go through. */
    fun chainFor(book: String?): List<CatalogModel> = chains.getOrPut(book.orEmpty()) {
        val own = book?.let { BookIds.parseKey(it) }?.let(ownPicks)
        AiChain.forBook(prefs, own)
    }

    override fun enhances(source: PageSource): Boolean = !source.isVector && chainFor(source.bookKey).isNotEmpty()

    override fun enhance(source: PageSource, index: Int, target: Target, width: Int, height: Int, canceled: () -> Boolean): AiOutcome {
        val book = source.bookKey
        val chain = chainFor(book)
        if (chain.isEmpty() || source.isVector) return AiOutcome.Skipped(Fallback.UNNEEDED)
        val key = book?.let {
            ProcessedStore.keyOf(it, HiddenPages.bookIndex(source, index), source.pageName(index), target, width, height, AiChain.signature(chain))
        }
        val t0 = SystemClock.elapsedRealtime()
        key?.let { store.get(it, width, height) }?.let { return AiOutcome.Done(it, stored = true, ms = SystemClock.elapsedRealtime() - t0) }
        if (gate() != null) return AiOutcome.Later
        val files = chain.map { m ->
            models.files(m) ?: run {
                download(m)
                return AiOutcome.Later
            }
        }
        val size = source.pageSize(index)
        if (size != null && unneeded(chain, target, size)) return AiOutcome.Skipped(Fallback.UNNEEDED)
        val opened = chain.mapIndexed { i, m -> session(m, files[i], canceled) ?: return AiOutcome.Skipped(Fallback.FAILED) }
        // Nobody waits on it: decoded and resampled on the budget of pages read ahead.
        val input = Urgency.later { Cancellation.during(canceled) { source.loadPage(index, target.detail(size, MAX_INPUT_PIXELS * 4)) } }
        try {
            val plan = target.plan(input.width.toDouble(), input.height.toDouble())
            if (target.banded && Target.bands(plan)) return AiOutcome.Skipped(Fallback.TOO_LARGE)
            val readable = if (input.config == Bitmap.Config.ARGB_8888) input else input.copy(Bitmap.Config.ARGB_8888, false)
            try {
                val run = Urgency.later {
                    AiPages.chain(opened, readable, plan.copy(width = width, height = height), target.scaling, target.linear, limits, canceled, gate)
                }
                val out = run.bitmap ?: return when (run.fallback) {
                    Fallback.PAUSED -> AiOutcome.Later
                    else -> AiOutcome.Skipped(run.fallback ?: Fallback.FAILED)
                }
                val shown = immutable(out)
                key?.let { store.put(it, shown) }
                return AiOutcome.Done(shown, ms = run.ms)
            } finally {
                if (readable !== input) readable.recycle()
            }
        } finally {
            input.recycle()
        }
    }

    /** [m] opened, once; null when it does not open. */
    private fun session(m: CatalogModel, files: ModelFiles, canceled: () -> Boolean): Session? {
        if (m.id in sessions) return sessions[m.id]
        val s = Sessions.open(runtime, files, threads, canceled = canceled)
        opened = true
        if (s == null) Log.w(TAG, "${m.id}: does not open; its books' pages show without AI")
        sessions[m.id] = s
        return s
    }

    /** Closes the models, after any page in progress. */
    override fun close() {
        if (!opened) return
        EXECUTOR.execute {
            sessions.values.forEach { it?.close() }
            sessions.clear()
        }
    }

    companion object {
        private const val TAG = "ReaderAi"

        /** One thread for every reader's AI work, started on first use, below the reader's own decodes: page turns never wait for it. */
        private val EXECUTOR by lazy {
            Executors.newSingleThreadExecutor { r ->
                Thread({
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                    r.run()
                }, "ai-pages").apply { isDaemon = true }
            }
        }

        private val WORKER: CoroutineDispatcher by lazy { EXECUTOR.asCoroutineDispatcher() }

        /** Largest page a chain reads, reduced evenly to fit: 32 MB of ARGB. */
        const val MAX_INPUT_PIXELS = 8_000_000L

        /**
         * True when [chain] only enlarges and a [size] page shows as [target] no larger than it
         * is: nothing to do. Unknown before an auto-crop finds its box, as the crop may enlarge.
         */
        internal fun unneeded(chain: List<CatalogModel>, target: Target, size: IntArray): Boolean {
            if (chain.any { it.scale <= 1 } || (target.autoCrop && target.box == null) || size[0] <= 0 || size[1] <= 0) return false
            val a = target.area(size[0], size[1])
            val w = (a[2] - a[0]).toDouble()
            val h = (a[3] - a[1]).toDouble()
            return w > 0 && h > 0 && target.plan(w, h).shrinks()
        }

        /** [bitmap] immutable, copied if need be, so it shows and is written to disk at once; the GPU keeps its texture. */
        private fun immutable(bitmap: Bitmap): Bitmap {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !bitmap.isMutable) return bitmap
            val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return bitmap
            bitmap.recycle()
            return copy
        }

        /** The reader's AI processing in [context], with the library's per-book picks. */
        fun of(context: Context): ReaderAi {
            val app = context.applicationContext
            val library = Library(app)
            val models = ModelStore.of(app)
            return ReaderAi(
                Prefs(app), library::aiPicks, models, ProcessedStore(ProcessedStore.dirIn(app.cacheDir)), LibNcnn,
                AiConditions.gate(app), { ModelDownloads.start(models, it) }
            )
        }
    }
}
