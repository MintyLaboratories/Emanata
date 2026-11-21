package com.infinity.comicreader.ai

import android.app.Application
import android.graphics.Bitmap
import com.infinity.comicreader.testing.awaitUntil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/** Checking a model to import: it opens as every model does, runs on a picture, and is timed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ModelCheckTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun check(runtime: InferenceRuntime, memory: Long = Long.MAX_VALUE, clock: () -> Long = System::currentTimeMillis) =
        ModelCheck.check(runtime, fakeFiles(tmp.root), 4, memory, clock = clock)

    @Test
    fun aModelThatRunsWellIsFitWithItsScaleAndTime() {
        val r = check(FakeRuntime()) as ModelCheck.Result.Fit
        assertEquals(2, r.scale)
        assertTrue(r.pageMs > 0)
        val one = check(FakeRuntime(make = { onGpu, _ -> FakeModel(scale = 1, onGpu = onGpu) })) as ModelCheck.Result.Fit
        assertEquals(1, one.scale)
    }

    @Test
    fun eachTestPictureComesBackBeforeAndAfterInItsOrder() {
        val wide = ModelCheck.picture(128)
        val small = ModelCheck.picture(40).also { it.eraseColor(0xFF336699.toInt()) }
        val r = ModelCheck.check(FakeRuntime(), fakeFiles(tmp.root), 4, Long.MAX_VALUE, pictures = listOf(wide, small)) as ModelCheck.Result.Fit
        assertEquals(2, r.previews.size)
        assertTrue(r.previews[0].before.sameAs(wide))
        assertTrue(r.previews[1].before.sameAs(small))
        for (p in r.previews) {
            assertEquals(p.before.width * 2, p.after.width)
            assertEquals(p.before.height * 2, p.after.height)
        }
        // A flat picture stays flat through the averaging model.
        assertEquals(0xFF336699.toInt(), r.previews[1].after.getPixel(40, 40))
        // Without test pictures, none.
        assertTrue((check(FakeRuntime()) as ModelCheck.Result.Fit).previews.isEmpty())
    }

    /** A runtime on the CPU whose [failing]th model opened fails its run [failAt]; [made] gets each model. */
    private fun counting(made: MutableList<FakeModel>, failing: Int = -1, failAt: Int = -1, onRun: (Int) -> Unit = {}) =
        FakeRuntime(gpu = null, make = { onGpu, _ ->
            FakeModel(onGpu = onGpu, failAt = if (made.size == failing) failAt else -1, onRun = onRun).also { made += it }
        })

    @Test
    fun aTestPictureTheModelFailsOnIsRefusedAsNotRunning() {
        val pictures = listOf(ModelCheck.picture(40), ModelCheck.picture(48))
        val fit = ArrayList<FakeModel>()
        assertTrue(ModelCheck.check(counting(fit), fakeFiles(tmp.root), 4, Long.MAX_VALUE, pictures = pictures) is ModelCheck.Result.Fit)
        // The last run is the second picture's one tile.
        val failing = ArrayList<FakeModel>()
        val runtime = counting(failing, failing = fit.size - 1, failAt = fit.last().runs.get() - 1)
        val r = ModelCheck.check(runtime, fakeFiles(tmp.root), 4, Long.MAX_VALUE, pictures = pictures)
        assertEquals(ModelCheck.Result.Refused(ModelRefusal.DOES_NOT_RUN), r)
        assertEquals(fit.last().runs.get(), failing.last().runs.get())
        // The caller's pictures are left to the caller.
        assertTrue(pictures.none { it.isRecycled })
    }

    @Test
    fun aRefusedModelRunsOnlyTheFirstTestPicture() {
        // Each timed run takes 10 s on the check's clock: a page far past the reader's limit.
        fun runs(pictures: List<Bitmap>): Int {
            val now = AtomicLong()
            var timing = false
            val made = ArrayList<FakeModel>()
            val runtime = counting(made, onRun = { if (timing) now.addAndGet(10_000) })
            val r = ModelCheck.check(
                runtime, fakeFiles(tmp.root), 4, Long.MAX_VALUE, clock = now::get, headroom = { timing = true; Float.NaN }, pictures = pictures
            )
            assertEquals(ModelRefusal.TOO_SLOW, (r as ModelCheck.Result.Refused).why)
            assertTrue("${r.pageMs}", r.pageMs > ReaderAi.MAX_TIMEOUT_MS)
            return made.sumOf { it.runs.get() }
        }
        val one = runs(listOf(ModelCheck.picture(40)))
        assertEquals(one, runs(listOf(ModelCheck.picture(40), ModelCheck.picture(44), ModelCheck.picture(48))))
        // A fit one runs each, one tile apiece.
        fun fitRuns(n: Int): Int {
            val made = ArrayList<FakeModel>()
            val pictures = List(n) { ModelCheck.picture(40 + 4 * it) }
            assertEquals(n, (ModelCheck.check(counting(made), fakeFiles(tmp.root), 4, Long.MAX_VALUE, pictures = pictures) as ModelCheck.Result.Fit).previews.size)
            return made.sumOf { it.runs.get() }
        }
        assertEquals(fitRuns(1) + 2, fitRuns(3))
    }

    @Test
    fun aTestPictureLargerThanATileIsCutToOneTileAtItsMiddle() {
        val s = Session(FakeModel(), Geometry(2, 0, 20), 64)
        assertEquals(24, TilePlan.core(64, s.geometry))
        val p = ModelCheck.picture(128)
        val cut = ModelCheck.oneTile(s, p)
        assertEquals(24, cut.width)
        assertEquals(24, cut.height)
        assertEquals(p.getPixel(52, 52), cut.getPixel(0, 0))
        assertEquals(p.getPixel(75, 75), cut.getPixel(23, 23))
        assertEquals(1, TilePlan.tiles(cut.width, cut.height, s.sizeFor(cut.width, cut.height), s.geometry).size)
        // One that fits is run whole.
        val small = ModelCheck.picture(20)
        assertTrue(ModelCheck.oneTile(s, small) === small)
    }

    @Test
    fun outputUnlikeItsInputIsFitWithoutAWarning() {
        // Mid-gray whatever the input: how output looks is the user's to judge.
        val runtime = FakeRuntime(gpu = null, make = { onGpu, _ -> FakeModel(onGpu = onGpu, wrong = true) })
        val r = check(runtime) as ModelCheck.Result.Fit
        assertEquals(emptySet<ImportWarning>(), r.warnings)
    }

    @Test
    fun aSlowModelIsFitWithTheSlowWarning() {
        // Each tile run takes 2 s on the check's clock: a page of a few dozen tiles takes about a minute.
        val now = AtomicLong()
        val runtime = FakeRuntime(gpu = null, make = { onGpu, _ -> FakeModel(onGpu = onGpu, onRun = { now.addAndGet(2_000) }) })
        val r = check(runtime, clock = now::get) as ModelCheck.Result.Fit
        assertTrue("${r.pageMs}", r.pageMs in ModelCheck.WARN_PAGE_MS + 1..ReaderAi.MAX_TIMEOUT_MS)
        assertEquals(setOf(ImportWarning.SLOW), r.warnings)
    }

    @Test
    fun aSlowPageIsWarnedOfAPagePastTheReadersLimitRefused() {
        val slow = ModelCheck.WARN_PAGE_MS
        val most = ReaderAi.MAX_TIMEOUT_MS
        assertEquals(30_000L, slow)
        assertEquals(120_000L, most)
        assertEquals(ModelCheck.Result.Fit(2, slow, warnings = emptySet()), ModelCheck.judge(2, slow))
        assertEquals(setOf(ImportWarning.SLOW), (ModelCheck.judge(2, slow + 1) as ModelCheck.Result.Fit).warnings)
        assertEquals(setOf(ImportWarning.SLOW), (ModelCheck.judge(2, most) as ModelCheck.Result.Fit).warnings)
        assertEquals(ModelCheck.Result.Refused(ModelRefusal.TOO_SLOW, pageMs = most + 1), ModelCheck.judge(2, most + 1))
    }

    @Test
    fun aSlowModelKeepsItsTimeAndHeadroom() {
        val r = ModelCheck.judge(2, 45_000, headroom = 0.4f) as ModelCheck.Result.Fit
        assertEquals(setOf(ImportWarning.SLOW), r.warnings)
        assertEquals(45_000L, r.pageMs)
        assertEquals(0.4f, r.headroom, 0f)
    }

    @Test
    fun refusalsWhileOpeningAreKept() {
        assertEquals(ModelRefusal.NOT_LOADED, (check(FakeRuntime(make = { _, _ -> null })) as ModelCheck.Result.Refused).why)
        val heavy = FakeRuntime(make = { onGpu, _ -> FakeModel(onGpu = onGpu, bytesPerPixel = 1L shl 20) })
        val r = check(heavy, memory = 1L shl 30) as ModelCheck.Result.Refused
        assertEquals(ModelRefusal.MEMORY, r.why)
        assertEquals(1L shl 30, r.limit)
        assertTrue(r.bytes > r.limit)
    }

    @Test
    fun aCheckPastItsTimeIsRefusedAsTooSlow() {
        // Every run takes a minute: the check runs out of time while the model opens.
        val now = AtomicLong()
        val runtime = FakeRuntime(make = { onGpu, _ -> FakeModel(onGpu = onGpu, onRun = { now.addAndGet(60_000) }) })
        assertEquals(ModelCheck.Result.Refused(ModelRefusal.TOO_SLOW), check(runtime, clock = now::get))
    }

    @Test
    fun timeWaitingForAPageIsNotCounted() {
        // A page holds the engine while the clock passes the check's limit, once the check waits for it.
        val now = AtomicLong()
        val held = CountDownLatch(1)
        val waiting = CountDownLatch(1)
        val page = thread {
            TileEngine.exclusive({ false }) {
                held.countDown()
                waiting.await()
                now.addAndGet(ModelCheck.TIME_LIMIT_MS * 2)
            }
        }
        try {
            awaitUntil("the page to hold the engine") { held.count == 0L }
            // Asked first by the engine's lock, between tries while it waits for the page.
            val r = ModelCheck.check(FakeRuntime(), fakeFiles(tmp.root), 4, Long.MAX_VALUE, canceled = { waiting.countDown(); false }, clock = now::get)
            assertTrue(r is ModelCheck.Result.Fit)
        } finally {
            waiting.countDown()
        }
        awaitUntil("the page to end") { !page.isAlive }
    }

    @Test
    fun aPageIsEstimatedFromItsFirstTiles() {
        // The first carries the warm-up; the rest at their mean.
        assertEquals(500L + 100 * 9, ModelCheck.estimate(listOf(500L, 90, 110), 10))
        assertEquals(700L, ModelCheck.estimate(listOf(500L, 100, 100), 3))
    }

    @Test
    fun aCheapPageIsTimedWholeASlowOneFromItsFirstTiles() {
        val tiles = ModelCheck.TIMED_TILES
        assertFalse("7 s: timed whole", ModelCheck.enough(List(tiles) { 100L }, 70, 1))
        assertTrue("70 s: estimated", ModelCheck.enough(List(tiles) { 1000L }, 70, 1))
        assertFalse("too few to tell", ModelCheck.enough(List(tiles - 1) { 1000L }, 70, 1))
        assertTrue("past the reader's limit", ModelCheck.enough(listOf(5000L, 5000L), 70, 1))
        assertTrue("a smaller page counts for more", ModelCheck.enough(List(tiles) { 100L }, 70, 2))
    }

    @Test
    fun aFitModelKeepsTheHeadroomItWasTimedAt() {
        val r = ModelCheck.check(FakeRuntime(), fakeFiles(tmp.root), 4, Long.MAX_VALUE, headroom = { 0.5f }) as ModelCheck.Result.Fit
        assertEquals(0.5f, r.headroom, 0f)
    }

    @Test
    fun largeScalesAreTimedOnASmallerPage() {
        assertEquals(1, ModelCheck.shrink(1))
        assertEquals(1, ModelCheck.shrink(4))
        assertEquals(2, ModelCheck.shrink(8))
    }
}
