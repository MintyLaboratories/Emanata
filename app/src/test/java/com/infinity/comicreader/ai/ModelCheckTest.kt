package com.infinity.comicreader.ai

import android.app.Application
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
import java.util.concurrent.TimeUnit
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
        // A page holds the engine while the clock passes the check's limit.
        val now = AtomicLong()
        val held = CountDownLatch(1)
        val page = thread {
            TileEngine.exclusive({ false }) {
                held.countDown()
                Thread.sleep(300)
                now.addAndGet(ModelCheck.TIME_LIMIT_MS * 2)
            }
        }
        assertTrue(held.await(10, TimeUnit.SECONDS))
        assertTrue(check(FakeRuntime(), clock = now::get) is ModelCheck.Result.Fit)
        page.join()
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
