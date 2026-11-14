package com.infinity.comicreader.ai

import android.app.Application
import android.graphics.Bitmap
import android.os.PowerManager
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.data.BookId
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.reader.PageSource
import com.infinity.comicreader.reader.Target
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/** The reader's AI: a book's chain, pages made and kept on disk, put off when it must wait, skipped when nothing would change. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderAiTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var prefs: Prefs
    private lateinit var models: ModelStore
    private lateinit var ai: ReaderAi
    private val runtime = FakeRuntime()
    private val own = HashMap<BookId, AiPicks>()
    private var stop: Fallback? = null
    private val downloads = ArrayList<String>()

    private val book = BookId(0xaa)
    private val upscale = ModelCatalog.byId(ModelCatalog.DEFAULT_UPSCALE)!!

    @Before
    fun setUp() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        prefs = Prefs(context)
        prefs.aiEnabled = true
        models = ModelStore(tmp.newFolder("models"))
        ai = reader()
    }

    @After
    fun tearDown() = ai.close()

    /** The thresholds [ai] reads for each page. */
    private var thresholds = AiThresholds()

    /** Milliseconds on the clock of a reader made by [reader]. */
    private var now = 0L

    /** The store of every reader [reader] makes. */
    private val store by lazy { ProcessedStore(tmp.newFolder(), PngPageCodec, Executor { it.run() }) }

    /** What runs shader chains for every reader [reader] makes. */
    private var shaders: ShaderRuntime = FakeShaders()

    private fun reader(): ReaderAi = ReaderAi(
        prefs, { own[it] }, models, store, runtime,
        { stop }, { downloads += it.id }, threads = 2, clock = { now }, thresholds = { thresholds }, shaders = shaders
    )

    /** Installs [m] with each file a synthetic upscaler at the size the catalog says. */
    private fun install(m: ShaderModel) {
        models.dir(m).mkdirs()
        for (f in m.files) {
            val text = (FakeShaderFiles.UPSCALER + "\n//").toByteArray()
            File(models.dir(m), f.name).writeBytes(text + ByteArray(f.bytes.toInt() - text.size) { ' '.code.toByte() })
            File(models.dir(m), f.name + ModelStore.STAMP).writeText(f.sha256)
        }
    }

    private fun install(m: CatalogModel) {
        for (f in m.files) {
            models.dir(m).mkdirs()
            RandomAccessFile(File(models.dir(m), f.name), "rw").use { it.setLength(f.bytes) }
            File(models.dir(m), f.name + ModelStore.STAMP).writeText(f.sha256)
        }
    }

    /** A book of [count] pages of [w] x [h], counting full loads, each calling [onLoad] with its index. */
    private class Book(
        val w: Int, val h: Int, override val bookKey: String?, override val isVector: Boolean = false, val onLoad: (Int) -> Unit = {}
    ) : PageSource {
        val loads = AtomicInteger()
        override val pageCount = 3

        override fun pageSize(index: Int) = intArrayOf(w, h)

        override fun pageName(index: Int) = "p$index.png"

        override fun loadPage(index: Int, target: Target): Bitmap {
            loads.incrementAndGet()
            onLoad(index)
            val r = Random(index)
            val px = IntArray(w * h) { 0xFF000000.toInt() or r.nextInt(1 shl 24) }
            return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { setPixels(px, 0, w, 0, 0, w, h) }
        }

        override fun close() {}
    }

    private fun bookOf(w: Int = 30, h: Int = 45, id: BookId = book) = Book(w, h, "id:${id.hex}")

    /** Shows a page at 40 x 60: a 30 x 45 page enlarged. */
    private val target = Target(40, 60)

    @Test
    fun aPageIsMadeAtThePlainPagesSizeThenReadBack() {
        install(upscale)
        val b = bookOf()
        val made = ai.enhance(b, 0, target, 40, 60) { false } as AiOutcome.Done
        assertFalse(made.stored)
        assertEquals(40, made.bitmap.width)
        assertEquals(60, made.bitmap.height)
        assertFalse(made.bitmap.isMutable)
        assertEquals(1, b.loads.get())
        val again = ai.enhance(b, 0, target, 40, 60) { false } as AiOutcome.Done
        assertTrue(again.stored)
        assertEquals("read back, not decoded", 1, b.loads.get())
    }

    @Test
    fun aHotOrSavingDeviceOrAMissingModelPutsPagesOff() {
        val b = bookOf()
        assertTrue(ai.enhance(b, 0, target, 40, 60) { false } is AiOutcome.Later)
        assertEquals("a model missing starts downloading", listOf(upscale.id), downloads)
        install(upscale)
        stop = Fallback.PAUSED
        assertTrue(ai.enhance(b, 0, target, 40, 60) { false } is AiOutcome.Later)
        assertEquals(0, b.loads.get())
    }

    @Test
    fun aShaderChainDownloadsThenRunsOnTheShaderRuntime() {
        val chain = ShaderCatalog.byId("anime4k-a-ul")!!
        prefs.setAiModel(AiStep.UPSCALE, chain.id)
        val shaders = FakeShaders().also { shaders = it }
        val reader = reader()
        try {
            val b = bookOf()
            assertTrue(reader.enhance(b, 0, target, 40, 60) { false } is AiOutcome.Later)
            assertEquals(listOf(chain.id), downloads)
            install(chain)
            val made = reader.enhance(b, 0, target, 40, 60) { false } as AiOutcome.Done
            assertEquals(40, made.bitmap.width)
            assertEquals(60, made.bitmap.height)
            assertEquals("its three files, one chain", 6, shaders.chains.single().programs.size)
            assertTrue("ncnn untouched", runtime.opened.isEmpty())
            assertTrue((reader.enhance(b, 0, target, 40, 60) { false } as AiOutcome.Done).stored)
        } finally {
            reader.close()
        }
    }

    @Test
    fun aShaderChainWithoutAUsableGpuLeavesPagesPlain() {
        val chain = ShaderCatalog.byId("anime4k-upscale-s")!!
        prefs.setAiModel(AiStep.UPSCALE, chain.id)
        install(chain)
        shaders = FakeShaders(available = false)
        val reader = reader()
        try {
            assertEquals(Fallback.UNOPENED, (reader.enhance(bookOf(), 0, target, 40, 60) { false } as AiOutcome.Skipped).why)
            assertEquals(ModelRefusal.RUNTIME, reader.refusal(chain.id))
        } finally {
            reader.close()
        }
    }

    @Test
    fun aPausedReaderStillShowsStoredPagesButMakesNone() {
        install(upscale)
        val b = bookOf()
        assertTrue(ai.enhance(b, 0, target, 40, 60) { false } is AiOutcome.Done)
        stop = Fallback.PAUSED
        assertTrue((ai.enhance(b, 0, target, 40, 60) { false } as AiOutcome.Done).stored)
        assertTrue(ai.enhance(b, 1, target, 40, 60) { false } is AiOutcome.Later)
        assertEquals("page 1 not decoded", 1, b.loads.get())
    }

    @Test
    fun aPageAnUpscalerWouldOnlyShrinkIsSkippedUnread() {
        install(upscale)
        val b = bookOf(80, 120)
        val out = ai.enhance(b, 0, target, 40, 60) { false }
        assertEquals(Fallback.UNNEEDED, (out as AiOutcome.Skipped).why)
        assertEquals(0, b.loads.get())
    }

    @Test
    fun aBooksOwnPicksWinAndAreReadAgainOnRefresh() {
        install(upscale)
        val b = bookOf()
        assertTrue(ai.enhances(b))
        own[book] = AiPicks.NONE
        assertTrue("kept until refreshed", ai.enhances(b))
        ai.refresh()
        assertFalse(ai.enhances(b))
        assertEquals(Fallback.UNNEEDED, (ai.enhance(b, 0, target, 40, 60) { false } as AiOutcome.Skipped).why)
        assertTrue("another book follows the global picks", ai.enhances(bookOf(id = BookId(0xbb))))
        prefs.aiEnabled = false
        ai.refresh()
        assertFalse(ai.enhances(bookOf(id = BookId(0xbb))))
    }

    @Test
    fun vectorBooksAreNotProcessed() {
        install(upscale)
        assertFalse(ai.enhances(Book(30, 45, "id:${book.hex}", isVector = true)))
    }

    @Test
    fun anUpscaleIsUnneededOnlyWhenThePageShowsNoLarger() {
        assertTrue(ReaderAi.unneeded(upscales, target, intArrayOf(80, 120), 40, 60))
        assertFalse(ReaderAi.unneeded(upscales, target, intArrayOf(30, 45), 40, 60))
        assertFalse("a crop to be found may change the size", ReaderAi.unneeded(upscales, target.copy(autoCrop = true), intArrayOf(80, 120), 40, 60))
        assertFalse("the upscale handle lowered", ReaderAi.unneeded(upscales, target, intArrayOf(60, 90), 40, 60, AiThresholds(upscalePct = 50)))
    }

    private val upscales = listOf(upscale)

    @Test
    fun aCleanupIsUnneededAtOrBelowItsHandleButADescreenNever() {
        val cleanup = listOf(ModelCatalog.forStep(AiStep.ARTIFACTS).first())
        assertTrue("shown at half its side", ReaderAi.unneeded(cleanup, target, intArrayOf(80, 120), 40, 60))
        assertFalse(ReaderAi.unneeded(cleanup, target, intArrayOf(78, 117), 40, 60))
        assertFalse("always", ReaderAi.unneeded(cleanup, target, intArrayOf(800, 1200), 40, 60, AiThresholds(cleanupPct = AiThresholds.ALWAYS)))
        val descreen = listOf(ModelCatalog.forStep(AiStep.DESCREEN).first())
        assertFalse(ReaderAi.unneeded(descreen, target, intArrayOf(800, 1200), 40, 60, AiThresholds(cleanupPct = 200)))
        assertFalse("a descreen keeps the chain running", ReaderAi.unneeded(cleanup + descreen + upscales, target, intArrayOf(800, 1200), 40, 60))
    }

    @Test
    fun unneededMeasuresAgainstThePageReducedToTheChainsInput() {
        // 4000 x 6000 (24 MP) is read reduced to fit 8 MP: 2309 x 3464.
        val size = intArrayOf(4000, 6000)
        assertFalse("75 % of the source, 130 % of the input", ReaderAi.unneeded(upscales, Target(3000, 4500), size, 3000, 4500))
        assertTrue("55 % of the source, 95 % of the input", ReaderAi.unneeded(upscales, Target(2200, 3300), size, 2200, 3300))
    }

    @Test
    fun theRenderSizeChangesWhichModelsRun() {
        val size = intArrayOf(80, 120)
        assertTrue("shown at half its side", ReaderAi.unneeded(upscales, target, size, 40, 60))
        assertFalse("laid out at twice that", ReaderAi.unneeded(upscales, target, size, 160, 240))
    }

    @Test
    fun theThresholdsAreReadForEachPageAndTheModelsThatRanKeyItsStoredCopy() {
        install(upscale)
        // Shown at 67 % of its side.
        val b = bookOf(60, 90)
        assertEquals(Fallback.UNNEEDED, (ai.enhance(b, 0, target, 40, 60) { false } as AiOutcome.Skipped).why)
        thresholds = AiThresholds(upscalePct = 50)
        assertFalse(ai.stored(b, 0, target, 40, 60))
        assertFalse((ai.enhance(b, 0, target, 40, 60) { false } as AiOutcome.Done).stored)
        assertTrue(ai.stored(b, 0, target, 40, 60))
        thresholds = AiThresholds(upscalePct = 60, cleanupPct = 120)
        assertTrue("the same model runs", ai.stored(b, 0, target, 40, 60))
        assertTrue((ai.enhance(b, 0, target, 40, 60) { false } as AiOutcome.Done).stored)
        assertEquals("read back, not made again", 1, b.loads.get())
        thresholds = AiThresholds(upscalePct = 70)
        assertFalse("the upscaler no longer runs", ai.stored(b, 0, target, 40, 60))
        assertEquals(Fallback.UNNEEDED, (ai.enhance(b, 0, target, 40, 60) { false } as AiOutcome.Skipped).why)
    }

    @Test
    fun thresholdsThatRunOtherModelsMissTheStoredCopy() {
        val cleanup = ModelCatalog.forStep(AiStep.ARTIFACTS).first()
        prefs.setAiModel(AiStep.ARTIFACTS, cleanup.id)
        ai.refresh()
        install(cleanup)
        install(upscale)
        val b = bookOf(60, 90)
        thresholds = AiThresholds(upscalePct = 50)
        assertFalse((ai.enhance(b, 0, target, 40, 60) { false } as AiOutcome.Done).stored)
        thresholds = AiThresholds(upscalePct = 50, cleanupPct = 60)
        assertTrue("both still run", (ai.enhance(b, 0, target, 40, 60) { false } as AiOutcome.Done).stored)
        thresholds = AiThresholds(upscalePct = 50, cleanupPct = 70)
        assertFalse("the cleanup no longer runs", ai.stored(b, 0, target, 40, 60))
        assertFalse((ai.enhance(b, 0, target, 40, 60) { false } as AiOutcome.Done).stored)
        assertEquals(2, b.loads.get())
    }

    @Test
    fun aPageStoredBeforeIsFoundBeforeItsSizeIsRead() {
        install(upscale)
        val b = bookOf()
        assertTrue(ai.enhance(b, 0, target, 40, 60) { false } is AiOutcome.Done)
        val fresh = reader()
        try {
            assertTrue("by any set of the chain's models", fresh.stored(b, 0, target, 40, 60))
            assertFalse(fresh.stored(b, 1, target, 40, 60))
        } finally {
            fresh.close()
        }
        assertEquals(listOf("r0", "r1", "r0,1", "r2", "r0,2", "r1,2", "r0,1,2"), ReaderAi.runKeys(3))
    }

    @Test
    fun aPageEnhancedForASharpenedTargetIsFoundStoredForIt() {
        install(upscale)
        thresholds = AiThresholds(upscalePct = 50)
        val sharp = target.copy(scaling = target.scaling.copy(sharpen = 50))
        val b = bookOf(60, 90)
        assertFalse((ai.enhance(b, 0, sharp, 40, 60) { false } as AiOutcome.Done).stored)
        assertTrue(ai.stored(b, 0, sharp, 40, 60))
        assertTrue("sharpening keys no processed page", ai.stored(b, 0, target, 40, 60))
        val fresh = reader()
        try {
            assertTrue("before the page's size is read", fresh.stored(b, 0, sharp, 40, 60))
        } finally {
            fresh.close()
        }
    }

    @Test
    fun thresholdsMovedIsHeardOnce() {
        assertFalse(ai.thresholdsMoved())
        thresholds = AiThresholds(cleanupPct = 60)
        assertTrue(ai.thresholdsMoved())
        assertFalse(ai.thresholdsMoved())
        thresholds = AiThresholds()
        assertTrue(ai.thresholdsMoved())
    }

    @Test
    fun theAppsReaderFollowsTheStoredHandles() {
        // Battery saver pauses AI: a page some model would run on is put off before any download or decode.
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsPowerSaveMode(true)
        val app = ReaderAi.of(context)
        try {
            // Shown at 67 % of its side: under the default upscale handle.
            val b = bookOf(60, 90)
            assertEquals(Fallback.UNNEEDED, (app.enhance(b, 0, target, 40, 60) { false } as AiOutcome.Skipped).why)
            assertFalse(app.thresholdsMoved())
            prefs.aiThresholds = AiThresholds(upscalePct = 60)
            assertTrue(app.thresholdsMoved())
            assertEquals("the upscale runs: put off", AiOutcome.Later, app.enhance(b, 0, target, 40, 60) { false })
            prefs.aiThresholds = AiThresholds(upscalePct = 70)
            assertEquals(Fallback.UNNEEDED, (app.enhance(b, 0, target, 40, 60) { false } as AiOutcome.Skipped).why)
            assertEquals(0, b.loads.get())
        } finally {
            app.close()
        }
    }

    private fun until(done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!done() && System.currentTimeMillis() < deadline) Thread.sleep(5)
    }

    @Test
    fun closingClosesTheModelsAndOpensNoneAfter() {
        install(upscale)
        val b = bookOf()
        assertTrue(ai.enhance(b, 0, target, 40, 60) { false } is AiOutcome.Done)
        val model = runtime.opened.last() as FakeModel
        ai.close()
        until { model.closed }
        assertTrue(model.closed)
        val opens = runtime.opened.size
        // A page asked for after closing, as one already queued: no model opens again.
        assertEquals(Fallback.UNOPENED, (ai.enhance(b, 1, target, 40, 60) { false } as AiOutcome.Skipped).why)
        assertEquals(opens, runtime.opened.size)
    }

    @Test
    fun closingModelsKeepsThoseStillUsedAndOpensTheOthersAgainWhenNeeded() {
        install(upscale)
        val b = bookOf()
        assertTrue(ai.enhance(b, 0, target, 40, 60) { false } is AiOutcome.Done)
        val first = runtime.opened.last() as FakeModel
        val opens = runtime.opened.size
        ai.closeModels(keep = setOf(upscale.id))
        ai.closeModels(keep = setOf("another"))
        until { first.closed }
        assertTrue(first.closed)
        assertTrue(ai.enhance(b, 1, target, 40, 60) { false } is AiOutcome.Done)
        assertTrue("opened again", runtime.opened.size > opens)
    }

    @Test
    fun aBookWhoseKeyHoldsNoIdFollowsItsOwnPicksOnceItsIdIsKnown() {
        install(upscale)
        val b = Book(30, 45, "/books/a.cbz|100|5")
        own[book] = AiPicks.NONE
        assertTrue("its key holds no ID: the global picks", ai.enhances(b))
        ai.know(b.bookKey, book)
        assertFalse(ai.enhances(b))
        assertEquals(Fallback.UNNEEDED, (ai.enhance(b, 0, target, 40, 60) { false } as AiOutcome.Skipped).why)
    }

    @Test
    fun aBookRunsTheDefaultChainOfTheKindItIsToldUnlessItHasItsOwn() {
        val descreen = ModelCatalog.forStep(AiStep.DESCREEN).first()
        prefs.setAiModel(AiStep.DESCREEN, descreen.id, ChainKind.MANGA)
        val b = bookOf()
        assertEquals("untold: everything else", ChainKind.OTHER, ai.kindOf(b.bookKey))
        assertEquals(listOf(upscale), ai.chainFor(b.bookKey))
        ai.kind(b.bookKey, ChainKind.MANGA)
        assertEquals(ChainKind.MANGA, ai.kindOf(b.bookKey))
        assertEquals(listOf(descreen, upscale), ai.chainFor(b.bookKey))
        assertEquals("another book keeps its own", listOf(upscale), ai.chainFor(bookOf(id = BookId(0xbb)).bookKey))
        own[book] = AiPicks.NONE.with(AiStep.UPSCALE, upscale.id)
        ai.refresh()
        assertEquals("its own picks win", listOf(upscale), ai.chainFor(b.bookKey))
        own.remove(book)
        ai.refresh()
        ai.kind(b.bookKey, ChainKind.OTHER)
        assertEquals(listOf(upscale), ai.chainFor(b.bookKey))
        ai.kind(null, ChainKind.MANGA)
        assertEquals("a book without a key", listOf(descreen, upscale), ai.chainFor(null))
    }

    @Test
    fun aChainReadAsTheKindOrPicksChangeIsNotKept() {
        val descreen = ModelCatalog.forStep(AiStep.DESCREEN).first()
        prefs.setAiModel(AiStep.DESCREEN, descreen.id, ChainKind.MANGA)
        val b = bookOf()
        var during: (() -> Unit)? = null
        val reader = ReaderAi(prefs, { val picks = own[it]; during?.invoke(); during = null; picks }, models, store, runtime, { stop }, { downloads += it.id }, threads = 2)
        try {
            // The kind changes on another thread while the chain is being read.
            during = { reader.kind(b.bookKey, ChainKind.MANGA) }
            assertEquals("read as it was", listOf(upscale), reader.chainFor(b.bookKey))
            assertEquals(listOf(descreen, upscale), reader.chainFor(b.bookKey))
            // So do the picks: a refresh while it is read.
            reader.refresh()
            during = {
                own[book] = AiPicks.NONE
                reader.refresh()
            }
            assertEquals(listOf(descreen, upscale), reader.chainFor(b.bookKey))
            assertEquals(emptyList<AiModel>(), reader.chainFor(b.bookKey))
        } finally {
            reader.close()
        }
    }

    @Test
    fun aModelShortOfMemoryPutsPagesOffAndOpensOnceMemoryIsFree() {
        install(upscale)
        // 100 KB per input pixel: measuring needs more than 128 MB; a 64 tile fits a quarter of the GPU.
        var memory = 128L shl 20
        var now = 1_000_000L
        val reader = shortReader(heavyRuntime(), { memory }, { now })
        try {
            val b = bookOf()
            assertTrue(reader.enhance(b, 0, target, 40, 60) { false } is AiOutcome.Later)
            assertEquals(ModelRefusal.MEMORY, reader.refusal(upscale.id))
            memory = 64L shl 30
            now += AiQueue.BACKOFF_MS
            assertTrue(reader.enhance(b, 0, target, 40, 60) { false } is AiOutcome.Done)
            assertNull(reader.refusal(upscale.id))
        } finally {
            reader.close()
        }
    }

    /** A reader whose models are each refused for memory until [memory] grows, on a clock read from [now]. */
    private fun shortReader(heavy: FakeRuntime, memory: () -> Long, now: () -> Long) = ReaderAi(
        prefs, { own[it] }, models, ProcessedStore(tmp.newFolder(), PngPageCodec, Executor { it.run() }), heavy,
        { stop }, { downloads += it.id }, threads = 2, memory = memory, clock = now
    )

    private fun heavyRuntime() = FakeRuntime(make = { onGpu, _ -> FakeModel(onGpu = onGpu, bytesPerPixel = 100_000) })

    @Test
    fun aPageFailingInTunedTilesReopensTheModelSmaller() {
        install(upscale)
        var failing = false
        val widths = ArrayList<Int>()
        // On the GPU, tile times like a light 2x model's: 384 px the cheapest per pixel; failing runs while asked to.
        val tuned = FakeRuntime(make = { onGpu, _ ->
            val inner = FakeModel(onGpu = onGpu)
            if (!onGpu) inner else object : TileModel by inner {
                override fun run(src: Bitmap, input: Box, dst: Bitmap?, crop: Box?, dx: Int, dy: Int): Dims? {
                    widths += input.width
                    now += if (input.width <= 192) 42L else if (input.width <= 256) 60L else 97L
                    return if (failing) null else inner.run(src, input, dst, crop, dx, dy)
                }
            }
        })
        val dir = tmp.newFolder()
        val reader = ReaderAi(
            prefs, { own[it] }, models, store, tuned, { stop }, { downloads += it.id }, threads = 2, clock = { now },
            thresholds = { thresholds }, tiles = TileChoices(dir)
        )
        try {
            val b = bookOf()
            assertTrue(reader.enhance(b, 0, target, 40, 60) { false } is AiOutcome.Done)
            assertEquals("pages in 384 px tiles", 384, widths.last())
            failing = true
            assertEquals(Fallback.FAILED, (reader.enhance(b, 1, target, 40, 60) { false } as AiOutcome.Skipped).why)
            failing = false
            widths.clear()
            assertTrue(reader.enhance(b, 2, target, 40, 60) { false } is AiOutcome.Done)
            assertEquals("opened again and timed below the size that failed", 256, widths.last())
            assertTrue(widths.none { it >= 384 })
        } finally {
            reader.close()
        }
    }

    @Test
    fun aModelShortOfMemoryTimeAfterTimeIsLeftOut() {
        install(upscale)
        val heavy = heavyRuntime()
        var now = 1_000_000L
        val reader = shortReader(heavy, { 128L shl 20 }, { now })
        try {
            val b = bookOf()
            // One try, put off; one retry after the back-off, then left out.
            assertEquals(2, ReaderAi.MEMORY_TRIES)
            assertTrue(reader.enhance(b, 0, target, 40, 60) { false } is AiOutcome.Later)
            now += AiQueue.BACKOFF_MS
            assertEquals(Fallback.UNOPENED, (reader.enhance(b, 0, target, 40, 60) { false } as AiOutcome.Skipped).why)
            val opens = heavy.asked.size
            now += AiQueue.BACKOFF_MS
            assertTrue(reader.enhance(b, 1, target, 40, 60) { false } is AiOutcome.Skipped)
            assertEquals("not opened again", opens, heavy.asked.size)
            assertEquals(ModelRefusal.MEMORY, reader.refusal(upscale.id))
        } finally {
            reader.close()
        }
    }

    @Test
    fun aModelShortOfMemoryIsNotTriedAgainWithinTheBackOff() {
        install(upscale)
        val heavy = heavyRuntime()
        var now = 1_000_000L
        val reader = shortReader(heavy, { 128L shl 20 }, { now })
        try {
            val b = bookOf()
            assertTrue(reader.enhance(b, 0, target, 40, 60) { false } is AiOutcome.Later)
            val opens = heavy.asked.size
            // A page turn or wake-up asks again at once: put off, untried, its retry kept.
            now += AiQueue.BACKOFF_MS - 1
            assertTrue(reader.enhance(b, 1, target, 40, 60) { false } is AiOutcome.Later)
            assertTrue(reader.enhance(b, 0, target, 40, 60) { false } is AiOutcome.Later)
            assertEquals("not opened within the back-off", opens, heavy.asked.size)
            now += 1
            assertTrue("the one retry", reader.enhance(b, 0, target, 40, 60) { false } is AiOutcome.Skipped)
            assertTrue(heavy.asked.size > opens)
        } finally {
            reader.close()
        }
    }

    @Test
    fun closingModelsGivesAModelLeftOutForMemoryAFreshTryAndRetry() {
        install(upscale)
        val heavy = heavyRuntime()
        var now = 1_000_000L
        var memory = 128L shl 20
        val reader = shortReader(heavy, { memory }, { now })
        try {
            val b = bookOf()
            assertTrue(reader.enhance(b, 0, target, 40, 60) { false } is AiOutcome.Later)
            now += AiQueue.BACKOFF_MS
            assertTrue(reader.enhance(b, 0, target, 40, 60) { false } is AiOutcome.Skipped)
            reader.closeModels()
            // Runs after the close: one thread does all AI work.
            runBlocking(reader.worker) {}
            val opens = heavy.asked.size
            assertTrue("tried afresh, put off", reader.enhance(b, 0, target, 40, 60) { false } is AiOutcome.Later)
            assertTrue(heavy.asked.size > opens)
            memory = 64L shl 30
            now += AiQueue.BACKOFF_MS
            assertTrue("its retry", reader.enhance(b, 0, target, 40, 60) { false } is AiOutcome.Done)
            assertNull(reader.refusal(upscale.id))
        } finally {
            reader.close()
        }
    }

    @Test
    fun aSlowChainGetsMoreTimeUpToALimit() {
        val fast = listOf(upscale)
        val slowPair = listOf(ModelCatalog.byId("opencomic-ai-descreen-hard-fast-v3-256")!!, ModelCatalog.byId("realesr-animevideov3-x2")!!)
        val reference = 1_500_000L
        assertEquals(PageLimits.DEFAULT_TIMEOUT_MS, ai.limitsFor(fast, listOf(ChainStep(0, reference))).timeoutMs)
        // Cool 15.4 + 10.8 s on the reference page: 2.5 times that in the reader.
        assertEquals(65_500L, ai.limitsFor(slowPair, listOf(ChainStep(0, reference), ChainStep(1, reference))).timeoutMs)
        // A 3 MP scan through the slow descreen alone: 30.8 s cool, 77 s in the reader.
        assertEquals(77_000L, ai.limitsFor(slowPair.take(1), listOf(ChainStep(0, 2 * reference))).timeoutMs)
        // A 6 MP one: held to the most a page may take.
        assertEquals(ReaderAi.MAX_TIMEOUT_MS, ai.limitsFor(slowPair.take(1), listOf(ChainStep(0, 4 * reference))).timeoutMs)
    }

    @Test
    fun aPagesTimeIsLearnedFromTheModelsRuns() {
        install(upscale)
        val b = bookOf()
        val before = ai.pageMs(b)
        assertEquals("the catalog's reference time", upscale.pageMs, before)
        assertTrue(ai.enhance(b, 0, target, 40, 60) { false } is AiOutcome.Done)
        assertTrue("measured: fake models are fast", ai.pageMs(b) < before)
        assertTrue(ai.rateOf(upscale) < ReaderAi.priorRate(upscale))
    }

    @Test
    fun aPagesTimeFollowsTheModelsItsLastPageRan() {
        val cleanup = ModelCatalog.forStep(AiStep.ARTIFACTS).first()
        prefs.setAiModel(AiStep.ARTIFACTS, cleanup.id)
        ai.refresh()
        install(cleanup)
        install(upscale)
        // Pages 0 and 2 shown enlarged run both models in 1 s; page 1 shown at 67 % runs the cleanup alone in 0.1 s.
        val b = Book(30, 45, "id:${book.hex}") { now += if (it == 1) 100 else 1_000 }
        assertTrue(ai.enhance(b, 0, target, 40, 60) { false } is AiOutcome.Done)
        assertEquals(1_000, ai.pageMs(b))
        assertTrue(ai.enhance(b, 1, Target(20, 30), 20, 30) { false } is AiOutcome.Done)
        assertEquals(100, ai.pageMs(b))
        assertTrue(ai.enhance(b, 2, target, 40, 60) { false } is AiOutcome.Done)
        assertEquals("not averaged with the cheaper page", 1_000, ai.pageMs(b))
    }

    @Test
    fun aRunStoppedByItsPaceIsLearnedWithItsWarmUpCountedOnce() {
        val prior = ReaderAi.priorRate(upscale)
        // Fewer tiles than the pace check needs: nothing learned.
        ai.learn(upscale, 1_500_000, RunStats(1, 70, longArrayOf(9_000), 9_000))
        ai.learn(upscale, 1_500_000, RunStats(2, 70, longArrayOf(2_000, 50), 2_050))
        ai.learn(upscale, 1_500_000, RunStats(3, 70, longArrayOf(2_000, 50, 50), 2_100))
        assertEquals(prior, ai.rateOf(upscale), 1e-9)
        // Stopped after 4 of 70 tiles: 2 s warm-up, then 300 ms a tile, 21.3 s for the page.
        ai.learn(upscale, 1_500_000, RunStats(4, 70, longArrayOf(2_000, 300, 300, 300), 2_900))
        assertEquals((2_000 + 69 * 300) / 1.5, ai.rateOf(upscale), 1e-9)
    }

    @Test
    fun aLearnedRateIsKeptAsTheModelsPageTimeHere() {
        val times = PageTimes(File(tmp.newFolder(), "times"))
        val reader = ReaderAi(prefs, { own[it] }, models, store, runtime, { stop }, { downloads += it.id }, threads = 2, times = times)
        try {
            reader.learn(upscale, 1_500_000, RunStats(3, 3, longArrayOf(1_000, 1_000, 1_000), 3_000))
            assertEquals(3_000L, times.pageMs(upscale))
            // A 3 MP page at the same pace: the same time for a reference page.
            reader.learn(upscale, 3_000_000, RunStats(3, 3, longArrayOf(2_000, 2_000, 2_000), 6_000))
            assertEquals(3_000L, times.pageMs(upscale))
            // Each page moves it a quarter of the way.
            reader.learn(upscale, 1_500_000, RunStats(3, 3, longArrayOf(3_000, 3_000, 3_000), 9_000))
            assertEquals(4_500L, times.pageMs(upscale))
        } finally {
            reader.close()
        }
    }

    @Test
    fun aNewReadersFirstPageDoesNotReplaceTheTimeKept() {
        val times = PageTimes(File(tmp.newFolder(), "times"))
        times.keep(upscale, 3_000)
        val reader = ReaderAi(prefs, { own[it] }, models, store, runtime, { stop }, { downloads += it.id }, threads = 2, times = times)
        try {
            reader.learn(upscale, 1_500_000, RunStats(3, 3, longArrayOf(2_000, 2_000, 2_000), 6_000))
            assertEquals(3_750L, times.pageMs(upscale))
        } finally {
            reader.close()
        }
    }
}
