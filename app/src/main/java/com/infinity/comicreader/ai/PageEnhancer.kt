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

    /** About how long a page of the book [source] reads takes to process, from start to shown; 0 when unknown. Cheap; any thread. */
    fun pageMs(source: PageSource): Long = 0

    /** True when page [index] of [source], as [enhance] would make it, is kept on disk: read back at once. Cheap. */
    fun stored(source: PageSource, index: Int, target: Target, width: Int, height: Int): Boolean = false

    /**
     * Page [index] of [source], shown as [target] lays it out at [width] x [height], processed:
     * read back from disk, or made from the page at full detail and stored. Blocking; stops
     * once [canceled]; waits between tiles while [held] (the reader moving).
     */
    fun enhance(
        source: PageSource, index: Int, target: Target, width: Int, height: Int, held: () -> Boolean = { false }, canceled: () -> Boolean
    ): AiOutcome
}

/**
 * The reader's AI processing: each book's chain ([AiChain.forBook]: its own picks, else the
 * global ones), models opened once as [Session]s and kept until closed ([closeModels]), pages kept on disk
 * ([ProcessedStore]). A page is processed from its full-detail decode (at most
 * [MAX_INPUT_PIXELS]) and resampled to exactly the plain page's size, so it swaps in without
 * a jump. Puts pages off ([AiOutcome.Later]) while [gate] says stop (heat, battery saver) or a
 * model is still downloading ([download] starts one missing, unless its download failed: a pick
 * or Settings starts that again); skips pages of vector books,
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

    /** True once [close]d: no model opens any more. */
    @Volatile
    private var closed = false

    /** True once a model was asked for: [closeModels] has something to close, or soon may. */
    @Volatile
    private var used = false

    /** Each book's chain by its key ("" for none), until [refresh]. */
    private val chains = ConcurrentHashMap<String, List<CatalogModel>>()

    /** Each model's milliseconds per megapixel of input as measured here, by catalog id ([rateOf]). */
    private val rates = ConcurrentHashMap<String, Double>()

    /** How long a page took from start to shown, by chain ([AiChain.signature]), smoothed. */
    private val pageTimes = ConcurrentHashMap<String, Long>()

    /** Models opened so far by catalog id; a model that would not open maps to none. Worker thread only. */
    private val sessions = HashMap<String, Session?>()

    /** The Book IDs of books whose key holds none ([BookIds.keyOf] made it from the path when the ID could not be read). */
    private val ids = ConcurrentHashMap<String, BookId>()

    /** Reads every book's chain again: after the global or a book's own picks changed. */
    fun refresh() = chains.clear()

    /** Says the book keyed [key] is [id], as the reader found it, so its own picks apply though its key holds no ID. */
    fun know(key: String?, id: BookId?) {
        if (key == null || id == null || BookIds.parseKey(key) != null) return
        if (ids.put(key, id) != id) chains.remove(key)
    }

    /** The chain the pages of the book keyed [book] go through. */
    fun chainFor(book: String?): List<CatalogModel> = chains.getOrPut(book.orEmpty()) {
        val own = book?.let { BookIds.parseKey(it) ?: ids[it] }?.let(ownPicks)
        AiChain.forBook(prefs, own)
    }

    override fun enhances(source: PageSource): Boolean = !source.isVector && chainFor(source.bookKey).isNotEmpty()

    override fun pageMs(source: PageSource): Long {
        val chain = chainFor(source.bookKey)
        if (chain.isEmpty()) return 0
        return pageTimes[AiChain.signature(chain)] ?: chain.sumOf { (rateOf(it) * REFERENCE_MP).toLong() }
    }

    /** [m]'s milliseconds per megapixel: as measured here, else from the catalog's reference page. */
    internal fun rateOf(m: CatalogModel): Double = rates[m.id] ?: priorRate(m)

    override fun stored(source: PageSource, index: Int, target: Target, width: Int, height: Int): Boolean {
        val chain = chainFor(source.bookKey)
        if (chain.isEmpty() || source.isVector) return false
        return keyOf(source, index, target, width, height, chain)?.let(store::has) == true
    }

    /** The key [store] keeps page [index] of [source] by, made by [chain] for [target] at [width] x [height]; null for a book without a key. */
    private fun keyOf(source: PageSource, index: Int, target: Target, width: Int, height: Int, chain: List<CatalogModel>): String? =
        source.bookKey?.let {
            ProcessedStore.keyOf(it, HiddenPages.bookIndex(source, index), source.pageName(index), target, width, height, AiChain.signature(chain))
        }

    override fun enhance(source: PageSource, index: Int, target: Target, width: Int, height: Int, held: () -> Boolean, canceled: () -> Boolean): AiOutcome {
        val t0 = SystemClock.elapsedRealtime()
        val outcome = enhanceNow(source, index, target, width, height, held, canceled)
        val ms = SystemClock.elapsedRealtime() - t0
        when (outcome) {
            is AiOutcome.Done -> Log.d(TAG, "page $index: ${if (outcome.stored) "read back" else "made"} in $ms ms")
            is AiOutcome.Skipped -> Log.d(TAG, "page $index: skipped (${outcome.why}) after $ms ms")
            AiOutcome.Later -> Log.d(TAG, "page $index: put off")
        }
        return outcome
    }

    private fun enhanceNow(source: PageSource, index: Int, target: Target, width: Int, height: Int, held: () -> Boolean, canceled: () -> Boolean): AiOutcome {
        val chain = chainFor(source.bookKey)
        if (chain.isEmpty() || source.isVector) return AiOutcome.Skipped(Fallback.UNNEEDED)
        val key = keyOf(source, index, target, width, height, chain)
        val t0 = SystemClock.elapsedRealtime()
        // Kept pages show even while paused, as those processed in memory do: a read costs about a plain decode, no GPU.
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
                val shownPlan = plan.copy(width = width, height = height)
                val steps = AiPages.steps(chain.map { it.scale }, readable.width, readable.height, shownPlan)
                val run = Urgency.later {
                    AiPages.chain(
                        opened, readable, shownPlan, target.scaling, target.linear, limitsFor(chain, steps), canceled, gate, hold = held,
                        expect = { i, px -> (rateOf(chain[i]) * px / 1e6).toLong() },
                        onStep = { i, px, ms, done -> learn(chain[i], px, ms, done) }
                    )
                }
                val out = run.bitmap ?: return when (run.fallback) {
                    Fallback.PAUSED -> AiOutcome.Later
                    else -> {
                        if (run.early) forgetSome(chain)
                        AiOutcome.Skipped(run.fallback ?: Fallback.FAILED)
                    }
                }
                val shown = immutable(out)
                key?.let { store.put(it, shown) }
                val ms = SystemClock.elapsedRealtime() - t0
                pageTimes.merge(AiChain.signature(chain), ms) { old, now -> (old + now) / 2 }
                return AiOutcome.Done(shown, ms = run.ms)
            } finally {
                if (readable !== input) readable.recycle()
            }
        } finally {
            input.recycle()
        }
    }

    /**
     * The limits of a page whose models run [steps] of [chain]: [PageLimits.DEFAULT_TIMEOUT_MS],
     * or for a slower chain [WARM] times what the catalog says it takes on the reference tablet
     * started cool, up to [MAX_TIMEOUT_MS], so a slow chain picked for a book can finish.
     */
    internal fun limitsFor(chain: List<CatalogModel>, steps: List<ChainStep>): PageLimits {
        val cool = steps.sumOf { priorRate(chain[it.index]) * it.pixels / 1e6 }
        return limits.copy(timeoutMs = (cool * WARM).toLong().coerceIn(limits.timeoutMs, maxOf(limits.timeoutMs, MAX_TIMEOUT_MS)))
    }

    /**
     * Takes [m]'s time of [ms] for [done] of an input of [pixels] into its measured rate; a run
     * stopped before half its tiles says little, its first tiles carrying the GPU's warm-up.
     */
    private fun learn(m: CatalogModel, pixels: Long, ms: Long, done: Double) {
        if (pixels <= 0 || done < MIN_LEARNED) return
        val rate = ms / (pixels / 1e6 * done)
        rates.merge(m.id, rate) { old, now -> (old + now) / 2 }
    }

    /**
     * Moves [chain]'s measured rates a step back toward the catalog's after a page skipped as
     * too slow, so a device that has cooled down tries the chain again after a few pages.
     */
    private fun forgetSome(chain: List<CatalogModel>) {
        for (m in chain) rates.computeIfPresent(m.id) { _, r -> r + (priorRate(m) - r) * RECOVER }
    }

    /** [m] opened, once; null when it does not open. */
    private fun session(m: CatalogModel, files: ModelFiles, canceled: () -> Boolean): Session? {
        used = true
        if (closed) return null
        if (m.id in sessions) return sessions[m.id]
        val s = Sessions.open(runtime, files, threads, canceled = canceled)
        if (s == null) Log.w(TAG, "${m.id}: does not open; its books' pages show without AI")
        sessions[m.id] = s
        return s
    }

    /**
     * Closes the models open but those of [keep] (catalog ids), after any page in progress,
     * freeing their native and GPU memory; they open again when next needed.
     */
    fun closeModels(keep: Set<String> = emptySet()) {
        // No model was ever asked for: nothing is open, and no thread need start.
        if (!used) return
        EXECUTOR.execute {
            val gone = sessions.keys.filter { it !in keep }
            for (id in gone) sessions.remove(id)?.close()
        }
    }

    /** Closes the models for good, after any page in progress, also one opening now. */
    override fun close() {
        closed = true
        closeModels()
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

        /** Megapixels of the catalog's reference page ([CatalogModel.pageMs]): 1000 x 1500. */
        const val REFERENCE_MP = 1.5

        /** How much longer than the catalog's cool time a model may take warm. */
        const val WARM = 1.5

        /** The longest a page may take, however slow its chain. */
        const val MAX_TIMEOUT_MS = 120_000L

        /** Least share of a model's tiles run for its time to count toward its rate. */
        const val MIN_LEARNED = 0.5

        /** Share of the way back to the catalog's rate a measured one moves at each page skipped as too slow. */
        const val RECOVER = 0.25

        /** [m]'s milliseconds per megapixel on the reference tablet, started cool. */
        fun priorRate(m: CatalogModel): Double = m.pageMs / REFERENCE_MP

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
                AiConditions.gate(app), { m -> if (ModelDownloads.state.value.states[m.id] !is DownloadState.Failed) ModelDownloads.start(models, m) }
            )
        }
    }
}
