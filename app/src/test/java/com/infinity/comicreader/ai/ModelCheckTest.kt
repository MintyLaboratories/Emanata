package com.infinity.comicreader.ai

import android.app.Application
import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.atomic.AtomicLong

/** Checking a model to import: it opens as every model does, resembles its input, and is timed. */
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
    fun outputUnlikeItsInputIsRefused() {
        // Right on the CPU, so it measures; nonsense on the GPU's check, so it runs on the CPU, which is nonsense too.
        val runtime = FakeRuntime(gpu = null, make = { onGpu, _ -> FakeModel(onGpu = onGpu, wrong = true) })
        assertEquals(ModelCheck.Result.Refused(ModelRefusal.UNLIKE), check(runtime))
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
    fun aPageIsEstimatedFromItsFirstTiles() {
        // The first carries the warm-up; the rest at their mean.
        assertEquals(500L + 100 * 9, ModelCheck.estimate(listOf(500L, 90, 110), 10))
        assertEquals(700L, ModelCheck.estimate(listOf(500L, 100, 100), 3))
    }

    @Test
    fun largeScalesAreTimedOnASmallerPage() {
        assertEquals(1, ModelCheck.shrink(1))
        assertEquals(1, ModelCheck.shrink(4))
        assertEquals(2, ModelCheck.shrink(8))
    }

    @Test
    fun theDifferenceIsTakenOverBlocks() {
        val src = ModelCheck.picture(16)
        val same = Bitmap.createScaledBitmap(src, 32, 32, false)
        assertEquals(0.0, ModelCheck.meanDifference(src, same, 2), 0.0)
        val gray = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF808080.toInt()) }
        assertTrue(ModelCheck.meanDifference(src, gray, 2) > ModelCheck.UNLIKE_MEAN)
    }
}
