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
import java.io.File
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
 * default chain of its [ChainKind]), models opened once as [Session]s and kept until closed ([closeModels]), pages kept on disk
 * ([ProcessedStore]). A page is processed from its full-detail decode (at most
 * [MAX_INPUT_PIXELS]) and resampled to exactly the plain page's size, so it swaps in without
 * a jump. Puts pages off ([AiOutcome.Later]) while [gate] says stop (heat, battery saver) or a
 * model is still downloading ([download] starts one missing, unless its download failed: a pick
 * or Settings starts that again); skips pages of vector books,
 * pages drawn in bands, and pages shown at a size where no model of the chain runs
 * ([thresholds], read for each page: [AiPages.steps]).
 */
class ReaderAi internal constructor(
    private val prefs: Prefs,
    private val ownPicks: (BookId) -> AiPicks?,
    private val models: ModelStore,
    private val store: ProcessedStore,
    private val runtime: InferenceRuntime,
    private val gate: () -> Fallback?,
    private val download: (AiModel) -> Unit,
    private val threads: Int = NativeResampler.threads,
    private val limits: PageLimits = PageLimits(),
    /** The memory a model may take as it opens ([ModelMemory]). */
    private val memory: () -> Long = { Long.MAX_VALUE },
    /** Milliseconds on a clock that never jumps. */
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    /** The display sizes at which the chain's models run, as set now. */
    private val thresholds: () -> AiThresholds = { AiThresholds() },
    /** What runs shader chains ([ShaderModel]); [runtime] runs the rest. */
    private val shaders: ShaderRuntime = GlesShaders,
    /** Where shader chains keep their compiled passes; null: compiled at every open. */
    private val programs: File? = null,
    /** Where this device's tile sizes for catalog models are kept ([TileTuning]); null: [TileSizing.DEFAULT_MAX] at most. */
    private val tiles: TileChoices? = null
) : PageEnhancer, AutoCloseable {

    override val worker: CoroutineDispatcher get() = WORKER

    /** True once [close]d: no model opens any more. */
    @Volatile
    private var closed = false

    /** True once a model was asked for: [closeModels] has something to close, or soon may. */
    @Volatile
    private var used = false

    /** Each book's chain by its key ("" for none), until [refresh]. */
    private val chains = ConcurrentHashMap<String, List<AiModel>>()

    /** Each model's milliseconds per megapixel of input as measured here, by model id ([rateOf]). */
    private val rates = ConcurrentHashMap<String, Double>()

    /** How long a page took from start to shown, by chain ([AiChain.signature]) and the models that ran ([AiPages.runKey]), smoothed. */
    private val pageTimes = ConcurrentHashMap<String, Long>()

    /** The models that ran on each chain's last page made ([AiPages.runKey]), by chain ([AiChain.signature]). */
    private val lastRuns = ConcurrentHashMap<String, String>()

    /** Page sizes read so far, by book key, page and entry ([placeOf]): [stored] decides the models that run from them without a read. */
    private val sizes = ConcurrentHashMap<String, IntArray>()

    /** The thresholds [thresholdsMoved] last saw. */
    @Volatile
    private var thresholdsSeen: AiThresholds = thresholds()

    /** Models opened so far by model id; a model that would not open maps to none. Worker thread only. */
    private val sessions = HashMap<String, Session?>()

    /** Why each model that did not open last time did not, by model id; gone once it opens. */
    private val refusals = ConcurrentHashMap<String, ModelRefusal>()

    /** Times in a row each model was refused for memory, by model id. Worker thread only. */
    private val shortOfMemory = HashMap<String, Int>()

    /** When each model was last refused for memory ([clock]), by model id. Worker thread only. */
    private val shortOfMemoryAt = HashMap<String, Long>()

    /** The Book IDs of books whose key holds none ([BookIds.keyOf] made it from the path when the ID could not be read). */
    private val ids = ConcurrentHashMap<String, BookId>()

    /** The default chain each book runs without picks of its own, by its key ("" for none) as [kind] told it; [ChainKind.OTHER] untold. */
    private val kinds = ConcurrentHashMap<String, ChainKind>()

    /** Reads every book's chain again: after the global or a book's own picks changed. */
    fun refresh() = chains.clear()

    /** Says the book keyed [key] is [id], as the reader found it, so its own picks apply though its key holds no ID. */
    fun know(key: String?, id: BookId?) {
        if (key == null || id == null || BookIds.parseKey(key) != null) return
        if (ids.put(key, id) != id) chains.remove(key)
    }

    /** Says the book keyed [key] runs [kind]'s default chain unless it has picks of its own ([ChainKind.resolve]). */
    fun kind(key: String?, kind: ChainKind) {
        val k = key.orEmpty()
        if (kinds.put(k, kind) != kind) chains.remove(k)
    }

    /** The default chain the book keyed [key] runs without picks of its own, as last told ([kind]). */
    fun kindOf(key: String?): ChainKind = kinds[key.orEmpty()] ?: ChainKind.OTHER

    /** The chain the pages of the book keyed [book] go through. */
    fun chainFor(book: String?): List<AiModel> = chains.getOrPut(book.orEmpty()) {
        val own = book?.let { BookIds.parseKey(it) ?: ids[it] }?.let(ownPicks)
        AiChain.forBook(prefs, own, kindOf(book))
    }

    override fun enhances(source: PageSource): Boolean = !source.isVector && chainFor(source.bookKey).isNotEmpty()

    /** The time of a page that runs the models its chain ran on its last page made: pages shown alike run alike. */
    override fun pageMs(source: PageSource): Long {
        val chain = chainFor(source.bookKey)
        if (chain.isEmpty()) return 0
        val signature = AiChain.signature(chain)
        return lastRuns[signature]?.let { pageTimes["$signature|$it"] } ?: chain.sumOf { (rateOf(it) * REFERENCE_MP).toLong() }
    }

    /**
     * True when the thresholds moved since this was last asked (or since this was made): pages
     * shown processed may need other models now. Only the first caller after a move hears it.
     */
    fun thresholdsMoved(): Boolean {
        val now = thresholds()
        if (now == thresholdsSeen) return false
        thresholdsSeen = now
        return true
    }

    /** [m]'s milliseconds per megapixel: as measured here, else from its page time ([AiModel.pageMs]). */
    internal fun rateOf(m: AiModel): Double = rates[m.id] ?: priorRate(m)

    override fun stored(source: PageSource, index: Int, target: Target, width: Int, height: Int): Boolean {
        val chain = chainFor(source.bookKey)
        if (chain.isEmpty() || source.isVector || source.bookKey == null) return false
        val thresholds = thresholds()
        // Called on the main thread: a page size not read yet is not read here.
        val size = sizes[placeOf(source, index)]
        if (size != null) return runKeyOf(chain, target, size, width, height, thresholds)?.let { stored(source, index, target, width, height, chain, it) } == true
        // Any copy the chain's models may have made: the run is not known without the page's size.
        return runKeys(chain.size).any { stored(source, index, target, width, height, chain, it) } ||
            stored(source, index, target, width, height, chain, AiPages.handles(chain.map(ChainModel::of), thresholds))
    }

    private fun stored(source: PageSource, index: Int, target: Target, width: Int, height: Int, chain: List<AiModel>, run: String): Boolean =
        keyOf(source, index, target, width, height, chain, run)?.let(store::has) == true

    /**
     * The key [store] keeps page [index] of [source] by, made by [chain] for [target] at
     * [width] x [height] running [run] ([runKeyOf]); null for a book without a key. Models
     * make pages unsharpened, so [target]'s sharpening is left out.
     */
    private fun keyOf(source: PageSource, index: Int, target: Target, width: Int, height: Int, chain: List<AiModel>, run: String): String? =
        source.bookKey?.let {
            val plain = target.copy(scaling = target.scaling.unsharpened)
            ProcessedStore.keyOf(it, HiddenPages.bookIndex(source, index), source.pageName(index), plain, width, height, AiChain.signature(chain), run)
        }

    /** Where page [index] of [source] is, for [sizes]. */
    private fun placeOf(source: PageSource, index: Int): String = "${source.bookKey}|${HiddenPages.bookIndex(source, index)}|${source.pageName(index)}"

    /** Page [index]'s size, read once per page of a keyed book; null when [source] cannot tell it cheaply. */
    private fun sizeOf(source: PageSource, index: Int): IntArray? {
        if (source.bookKey == null) return source.pageSize(index)
        val place = placeOf(source, index)
        return sizes[place] ?: source.pageSize(index)?.also { sizes[place] = it }
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

    private fun enhanceNow(source: PageSource, index: Int, pageTarget: Target, width: Int, height: Int, held: () -> Boolean, canceled: () -> Boolean): AiOutcome {
        // Models see and make pages unsharpened: sharpening is for plain pages, so its strength keys no processed page.
        val target = pageTarget.copy(scaling = pageTarget.scaling.unsharpened)
        val chain = chainFor(source.bookKey)
        if (chain.isEmpty() || source.isVector) return AiOutcome.Skipped(Fallback.UNNEEDED)
        val t0 = clock()
        val thresholds = thresholds()
        val chainModels = chain.map(ChainModel::of)
        val size = sizeOf(source, index)
        // Decided before the decode, from the page's size, so the key names the models that run: thresholds that run the same ones share it.
        val planned = size?.let { planned(chain, target, it, width, height, thresholds) }
        if (planned != null && planned.isEmpty()) return AiOutcome.Skipped(Fallback.UNNEEDED)
        val runKey = planned?.let { p -> AiPages.runKey(p.map { it.index }) } ?: AiPages.handles(chainModels, thresholds)
        val key = keyOf(source, index, target, width, height, chain, runKey)
        // Kept pages show even while paused, as those processed in memory do: a read costs about a plain decode, no GPU.
        key?.let { store.get(it, width, height) }?.let { return AiOutcome.Done(it, stored = true, ms = clock() - t0) }
        if (gate() != null) return AiOutcome.Later
        for (m in chain) {
            if (!models.installed(m)) {
                download(m)
                return AiOutcome.Later
            }
        }
        val opened = chain.map { m ->
            session(m, canceled, held) ?: return if (!closed && m.id !in sessions) AiOutcome.Later else AiOutcome.Skipped(Fallback.UNOPENED)
        }
        // Nobody waits on it: decoded and resampled on the budget of pages read ahead.
        val input = Urgency.later { Cancellation.during(canceled) { source.loadPage(index, target.detail(size, MAX_INPUT_PIXELS * 4)) } }
        try {
            val plan = target.plan(input.width.toDouble(), input.height.toDouble())
            if (target.banded && Target.bands(plan)) return AiOutcome.Skipped(Fallback.TOO_LARGE)
            val readable = if (input.config == Bitmap.Config.ARGB_8888) input else input.copy(Bitmap.Config.ARGB_8888, false)
            try {
                val shownPlan = plan.copy(width = width, height = height)
                val steps = planned?.let { p -> AiPages.stepsOf(p.map { it.index }, chainModels, readable.width, readable.height) }
                    ?: AiPages.steps(chainModels, readable.width, readable.height, shownPlan, thresholds)
                val run = Urgency.later {
                    AiPages.chain(
                        opened, readable, shownPlan, target.scaling, target.linear, limitsFor(chain, steps), canceled, gate, hold = held,
                        expect = { i, px -> (rateOf(chain[i]) * px / 1e6).toLong() },
                        onStep = { i, px, stats ->
                            learn(chain[i], px, stats)
                            opened[i].heard(stats)
                        },
                        steps = steps
                    )
                }
                val out = run.bitmap ?: return when (run.fallback) {
                    Fallback.PAUSED -> AiOutcome.Later
                    else -> {
                        if (run.early) forgetSome(chain)
                        if (run.fallback == Fallback.FAILED) steps.getOrNull(run.ran)?.let { shrink(chain[it.index], opened[it.index]) }
                        AiOutcome.Skipped(run.fallback ?: Fallback.FAILED)
                    }
                }
                val shown = immutable(out)
                key?.let { store.put(it, shown) }
                val ms = clock() - t0
                val signature = AiChain.signature(chain)
                val ran = AiPages.runKey(steps.map { it.index })
                pageTimes.merge("$signature|$ran", ms) { old, now -> (old + now) / 2 }
                lastRuns[signature] = ran
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
     * or for a slower chain [WARM] times what its models' page times ([AiModel.pageMs]) say
     * it takes started cool, up to [MAX_TIMEOUT_MS], so a slow chain picked for a book can finish.
     */
    internal fun limitsFor(chain: List<AiModel>, steps: List<ChainStep>): PageLimits {
        val cool = steps.sumOf { priorRate(chain[it.index]) * it.pixels / 1e6 }
        return limits.copy(timeoutMs = (cool * WARM).toLong().coerceIn(limits.timeoutMs, maxOf(limits.timeoutMs, MAX_TIMEOUT_MS)))
    }

    /**
     * Takes [m]'s run on an input of [pixels] into its measured rate, a run stopped early by
     * its whole input's expected time ([RunStats.fullMs]): its first tile, which carries the
     * GPU's warm-up, counted once, not as the pace of the rest.
     */
    internal fun learn(m: AiModel, pixels: Long, stats: RunStats) {
        val ms = stats.fullMs ?: return
        if (pixels <= 0) return
        val rate = ms / (pixels / 1e6)
        rates.merge(m.id, rate) { old, now -> (old + now) / 2 }
    }

    /**
     * After a run of [m] in [s] failed: when its tiles were larger than the default by this
     * device's choice ([Session.shrink]), that size is remembered as too large and [m] closed,
     * to open at its next page in smaller tiles.
     */
    private fun shrink(m: AiModel, s: Session) {
        if (!s.shrink()) return
        Log.w(TAG, "${m.id}: failed in ${s.size} px tiles; smaller from now on")
        sessions.remove(m.id)?.close()
    }

    /**
     * Moves [chain]'s measured rates a step back toward their page times after a page skipped as
     * too slow, so a device that has cooled down tries the chain again after a few pages.
     */
    private fun forgetSome(chain: List<AiModel>) {
        for (m in chain) rates.computeIfPresent(m.id) { _, r -> r + (priorRate(m) - r) * RECOVER }
    }

    /**
     * [m] opened, once; null when it does not open ([refusal] says why). A model refused for
     * memory is not kept refused at first, as what is free changes: its pages are put off and
     * it is tried once more with them no sooner than [AiQueue.BACKOFF_MS] later ([MEMORY_TRIES]
     * tries in a row in all), then left out until [closeModels] closes it. Timing its tile sizes
     * waits while [held] ([Sessions.attempt]).
     */
    private fun session(m: AiModel, canceled: () -> Boolean, held: () -> Boolean): Session? {
        used = true
        if (closed) return null
        if (m.id in sessions) return sessions[m.id]
        // Put off again, untried, until the back-off since its last refusal has passed: a page turn or wake-up skips the queue's.
        shortOfMemoryAt[m.id]?.let { if (clock() - it < AiQueue.BACKOFF_MS) return null }
        val o = when (m) {
            is ShaderModel -> models.shaderFiles(m)?.let { files ->
                ShaderSessions.attempt(shaders, { files.map { it.readText() } }, m.scale, memoryBytes = memory(), cache = programs, canceled = canceled)
            }
            else -> models.files(m)?.let { files -> Sessions.attempt(runtime, files, threads, memoryBytes = memory(), canceled = canceled, tuning = tuningOf(m), clock = clock, hold = held) }
        } ?: Opening.refused(ModelRefusal.NOT_LOADED)
        val s = o.session
        if (s != null) {
            refusals.remove(m.id)
            shortOfMemory.remove(m.id)
            shortOfMemoryAt.remove(m.id)
            sessions[m.id] = s
            return s
        }
        val why = o.refusal ?: ModelRefusal.NOT_LOADED
        refusals[m.id] = why
        if (why == ModelRefusal.MEMORY) shortOfMemoryAt[m.id] = clock()
        val tries = if (why == ModelRefusal.MEMORY) shortOfMemory.merge(m.id, 1, Int::plus)!! else MEMORY_TRIES
        if (tries < MEMORY_TRIES) {
            Log.w(TAG, "${m.id}: not enough memory free now (${o.bytes shr 20} of ${o.limit shr 20} MB); tried again later")
        } else {
            Log.w(TAG, "${m.id}: does not open ($why); its books' pages show without AI")
            sessions[m.id] = null
        }
        return null
    }

    /** [m]'s tile sizes to choose among on this device: a catalog model's ([CatalogModel.tiles]), kept by its files; null for others. */
    private fun tuningOf(m: AiModel): TileTuning? {
        val choices = tiles ?: return null
        val sizes = (m as? CatalogModel)?.tiles?.takeIf { it.isNotEmpty() } ?: return null
        return TileTuning(sizes, choices, TileTuning.keyOf(m.fingerprint))
    }

    /** Why model [id] did not open when last tried; null when it opened or was not tried. Any thread. */
    fun refusal(id: String): ModelRefusal? = refusals[id]

    /**
     * Closes the models open but those of [keep] (model ids), after any page in progress,
     * freeing their native and GPU memory; they open again when next needed, those that
     * would not open tried afresh.
     */
    fun closeModels(keep: Set<String> = emptySet()) {
        // No model was ever asked for: nothing is open, and no thread need start.
        if (!used) return
        EXECUTOR.execute {
            val gone = sessions.keys.filter { it !in keep }
            for (id in gone) sessions.remove(id)?.close()
            shortOfMemory.keys.retainAll(keep)
            shortOfMemoryAt.keys.retainAll(keep)
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

        /** Megapixels of the page [AiModel.pageMs] is timed on ([AiModel.pageMs]): 1000 x 1500. */
        const val REFERENCE_MP = 1.5

        /**
         * How much longer than the catalog's cool time a model may take in the reader: up to
         * 1.5x warm, and its thread runs at background priority beside the reader's own work
         * (on the reference tablet waifu2x took 5.2 s a page there against 3.4 s alone, cool).
         */
        const val WARM = 2.5

        /** The longest a page may take, however slow its chain. */
        const val MAX_TIMEOUT_MS = 120_000L

        /** Times in a row a model refused for memory is tried, the first included, before its pages show without it: one retry. */
        const val MEMORY_TRIES = 2

        /** Share of the way back to the rate of its page time a measured rate moves at each page skipped as too slow. */
        const val RECOVER = 0.25

        /** [m]'s milliseconds per megapixel on the reference tablet, started cool. */
        fun priorRate(m: AiModel): Double = m.pageMs / REFERENCE_MP

        /** Largest page a chain reads, reduced evenly to fit: 32 MB of ARGB. */
        const val MAX_INPUT_PIXELS = 8_000_000L

        /**
         * The models of [chain] that run ([AiPages.steps] under [thresholds]) on a [size] page
         * shown as [target] lays it out at [width] x [height], each with its input's pixels;
         * null when unknown: before an auto-crop finds its box (the crop changes the size), or
         * for a size that tells nothing. Measured against the chain's input, the page reduced
         * to [MAX_INPUT_PIXELS] as [enhance] decodes it.
         */
        internal fun planned(
            chain: List<AiModel>, target: Target, size: IntArray, width: Int, height: Int, thresholds: AiThresholds = AiThresholds()
        ): List<ChainStep>? {
            if ((target.autoCrop && target.box == null) || size[0] <= 0 || size[1] <= 0 || width <= 0 || height <= 0) return null
            val a = target.area(size[0], size[1])
            val w = (a[2] - a[0]).toDouble()
            val h = (a[3] - a[1]).toDouble()
            if (w <= 0 || h <= 0) return null
            val input = target.detail(size, MAX_INPUT_PIXELS * 4).plan(w, h)
            val shown = target.plan(input.width.toDouble(), input.height.toDouble()).copy(width = width, height = height)
            return AiPages.steps(chain.map(ChainModel::of), input.width, input.height, shown, thresholds)
        }

        /** True when no model of [chain] runs on a [size] page shown as [target] lays it out at [width] x [height] ([planned]): nothing to do. */
        internal fun unneeded(
            chain: List<AiModel>, target: Target, size: IntArray, width: Int, height: Int, thresholds: AiThresholds = AiThresholds()
        ): Boolean = planned(chain, target, size, width, height, thresholds)?.isEmpty() == true

        /** The models of [chain] that run on such a page ([planned]) as a processed page's key part ([AiPages.runKey]); null when unknown. */
        internal fun runKeyOf(chain: List<AiModel>, target: Target, size: IntArray, width: Int, height: Int, thresholds: AiThresholds): String? =
            planned(chain, target, size, width, height, thresholds)?.let { p -> AiPages.runKey(p.map { it.index }) }

        /** The key parts ([AiPages.runKey]) of every set of models a chain of [n] may run, none aside. */
        internal fun runKeys(n: Int): List<String> =
            (1 until (1 shl n)).map { mask -> AiPages.runKey((0 until n).filter { mask shr it and 1 == 1 }) }

        /** [bitmap] immutable, copied if need be, so it shows and is written to disk at once; the GPU keeps its texture. */
        private fun immutable(bitmap: Bitmap): Bitmap {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !bitmap.isMutable) return bitmap
            val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return bitmap
            bitmap.recycle()
            return copy
        }

        /** Where shader chains keep their compiled passes in [context]: a cache, made again when cleared. */
        fun programsIn(context: Context): File = File(context.cacheDir, "shader-programs")

        /** The reader's AI processing in [context], with the library's per-book picks. */
        fun of(context: Context): ReaderAi {
            val app = context.applicationContext
            val library = Library(app)
            val models = ModelStore.of(app)
            val prefs = Prefs(app)
            return ReaderAi(
                prefs, library::aiPicks, models, ProcessedStore(ProcessedStore.dirIn(app.cacheDir)), LibNcnn,
                AiConditions.gate(app),
                { m -> if (m is FileSet && ModelDownloads.mayStartBySelf(ModelDownloads.state.value, m.id)) ModelDownloads.start(models, m, optional = true) },
                memory = { ModelMemory.of(app) },
                thresholds = { prefs.aiThresholds },
                programs = programsIn(app),
                tiles = TileChoices(TileChoices.dirIn(app))
            )
        }
    }
}
