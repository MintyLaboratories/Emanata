package com.infinity.comicreader.ai

import android.app.Application
import android.graphics.Bitmap
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

    private fun reader(): ReaderAi = ReaderAi(
        prefs, { own[it] }, models, ProcessedStore(tmp.newFolder(), PngPageCodec, Executor { it.run() }), runtime,
        { stop }, { downloads += it.id }, threads = 2, thresholds = { thresholds }
    )

    private fun install(m: CatalogModel) {
        for (f in m.files) {
            models.dir(m).mkdirs()
            RandomAccessFile(File(models.dir(m), f.name), "rw").use { it.setLength(f.bytes) }
            File(models.dir(m), f.name + ModelStore.STAMP).writeText(f.sha256)
        }
    }

    /** A book of [count] pages of [w] x [h], counting full loads. */
    private class Book(val w: Int, val h: Int, override val bookKey: String?, override val isVector: Boolean = false) : PageSource {
        val loads = AtomicInteger()
        override val pageCount = 3

        override fun pageSize(index: Int) = intArrayOf(w, h)

        override fun pageName(index: Int) = "p$index.png"

        override fun loadPage(index: Int, target: Target): Bitmap {
            loads.incrementAndGet()
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
    fun zoomingChangesWhichModelsRun() {
        val size = intArrayOf(80, 120)
        assertTrue("shown at half its side", ReaderAi.unneeded(upscales, target, size, 40, 60))
        assertFalse("zoomed in to twice that", ReaderAi.unneeded(upscales, target, size, 160, 240))
    }

    @Test
    fun theThresholdsAreReadForEachPage() {
        install(upscale)
        val b = bookOf(60, 90)
        assertEquals(Fallback.UNNEEDED, (ai.enhance(b, 0, target, 40, 60) { false } as AiOutcome.Skipped).why)
        thresholds = AiThresholds(upscalePct = 50)
        assertFalse((ai.enhance(b, 0, target, 40, 60) { false } as AiOutcome.Done).stored)
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
}
