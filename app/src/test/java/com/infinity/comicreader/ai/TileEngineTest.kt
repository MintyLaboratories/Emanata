package com.infinity.comicreader.ai

import android.app.Application
import android.graphics.Bitmap
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TileEngineTest {

    private fun page(w: Int, h: Int, seed: Int = w * 31 + h): Bitmap {
        val r = Random(seed)
        val px = IntArray(w * h) { 0xFF000000.toInt() or r.nextInt(1 shl 24) }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPixels(px, 0, w, 0, 0, w, h)
            setHasAlpha(false)
        }
    }

    private fun pixels(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    private fun session(model: FakeModel, size: Int = 48) = Session(model, ModelProbe.measure(model), size)

    @Test
    fun tiledOutputMatchesTheWholePageRunAtOnce() {
        for (padded in listOf(true, false)) for (scale in listOf(1, 2, 3)) {
            for ((w, h) in listOf(5 to 7, 40 to 40, 97 to 61, 130 to 211)) {
                val model = FakeModel(scale = scale, reach = 3, padded = padded)
                val src = page(w, h)
                val run = TileEngine.run(session(model), src)
                val out = run.bitmap
                assertNotNull("fell back: ${run.fallback}", out)
                assertEquals(w * scale, out!!.width)
                assertEquals(h * scale, out.height)
                assertArrayEquals("padded=$padded x$scale ${w}x$h", FakeModel.reference(src, scale, 3), pixels(out))
                assertEquals(run.stats.planned, run.stats.tiles)
                assertNull(run.fallback)
            }
        }
    }

    @Test
    fun everyTileInputHasTheSameSize() {
        val model = FakeModel()
        val s = session(model, size = 40)
        model.inputs.clear()
        TileEngine.run(s, page(150, 100))
        assertTrue(model.inputs.isNotEmpty())
        assertTrue(model.inputs.all { it.width == 40 && it.height == 40 })
    }

    @Test
    fun cancelingStopsBetweenTiles() {
        val model = FakeModel()
        val s = session(model)
        val before = model.runs.get()
        val trims = model.trims.get()
        var done = 0
        assertThrows(CancellationException::class.java) {
            TileEngine.run(s, page(200, 200), canceled = { done >= 3 }, onTile = { d, _, _ -> done = d })
        }
        assertEquals(3, model.runs.get() - before)
        assertEquals(trims + 1, model.trims.get())
    }

    @Test
    fun aPageOverItsTimeFallsBack() {
        val model = FakeModel()
        val s = session(model)
        var now = 0L
        val tiles = TilePlan.tiles(200, 200, s.size, s.geometry).size
        assertTrue(tiles > 10)
        // 100 ms a tile: the pace after two tiles predicts a miss of 500 ms, so no third runs.
        val run = TileEngine.run(
            s, page(200, 200), PageLimits(timeoutMs = 500), clock = { now }, onTile = { _, _, _ -> now += 100 }
        )
        assertNull(run.bitmap)
        assertEquals(Fallback.TIMEOUT, run.fallback)
        assertEquals(2, run.stats.tiles)
        assertEquals(tiles, run.stats.planned)
    }

    @Test
    fun aPageOnPaceRunsToTheEnd() {
        val model = FakeModel()
        val s = session(model)
        var now = 0L
        val tiles = TilePlan.tiles(200, 200, s.size, s.geometry).size
        val run = TileEngine.run(
            s, page(200, 200), PageLimits(timeoutMs = tiles * 100L + 50), clock = { now }, onTile = { _, _, _ -> now += 100 }
        )
        assertNotNull(run.bitmap)
        assertEquals(tiles, run.stats.tiles)
        assertEquals(tiles * 100L, run.stats.totalMs)
    }

    @Test
    fun aVeryLongLimitNeverOverflowsThePace() {
        val model = FakeModel()
        val s = session(model)
        var now = 0L
        val run = TileEngine.run(s, page(200, 200), PageLimits(timeoutMs = Long.MAX_VALUE / 2), clock = { now }, onTile = { _, _, _ -> now += 100 })
        assertNotNull(run.bitmap)
    }

    @Test
    fun aSlowFirstTileStopsOnceTimeRunsOut() {
        val model = FakeModel()
        val s = session(model)
        var now = 0L
        val run = TileEngine.run(s, page(200, 200), PageLimits(timeoutMs = 500), clock = { now }, onTile = { _, _, _ -> now += 600 })
        assertEquals(Fallback.TIMEOUT, run.fallback)
        assertEquals(1, run.stats.tiles)
    }

    @Test
    fun theGateStopsARun() {
        val model = FakeModel()
        val s = session(model)
        val before = model.runs.get()
        assertEquals(Fallback.PAUSED, TileEngine.run(s, page(100, 100), gate = { Fallback.PAUSED }).fallback)
        assertEquals(before, model.runs.get())
        var done = 0
        val run = TileEngine.run(s, page(200, 200), gate = { if (done >= 2) Fallback.PAUSED else null }, onTile = { d, _, _ -> done = d })
        assertEquals(Fallback.PAUSED, run.fallback)
        assertEquals(2, run.stats.tiles)
    }

    @Test
    fun anOutputOverTheLimitFallsBackBeforeAnyTile() {
        val model = FakeModel(scale = 2)
        val s = session(model)
        val before = model.runs.get()
        val run = TileEngine.run(s, page(100, 100), PageLimits(maxOutputPixels = 200L * 200 - 1))
        assertEquals(Fallback.TOO_LARGE, run.fallback)
        assertEquals(before, model.runs.get())
        assertNotNull(TileEngine.run(s, page(100, 100), PageLimits(maxOutputPixels = 200L * 200)).bitmap)
    }

    @Test
    fun aTranslucentPageFallsBack() {
        val model = FakeModel()
        val src = page(50, 50).apply { setHasAlpha(true) }
        assertEquals(Fallback.TRANSLUCENT, TileEngine.run(session(model), src).fallback)
    }

    @Test
    fun closingTheSessionMidRunFallsBack() {
        lateinit var s: Session
        var done = 0
        val model = FakeModel(onRun = { if (done == 2) s.close() })
        s = session(model)
        val run = TileEngine.run(s, page(200, 200), onTile = { d, _, _ -> done = d })
        // The tile running when it closed still ends; the next finds it closed.
        assertEquals(Fallback.FAILED, run.fallback)
        assertEquals(3, run.stats.tiles)
    }

    @Test
    fun aFailedTileFallsBack() {
        // Measuring takes two runs for the scale and two per pattern for the reach.
        val probeRuns = 6
        val model = FakeModel(failAt = probeRuns + 4)
        val s = session(model)
        assertEquals(probeRuns, model.runs.get())
        val run = TileEngine.run(s, page(200, 200))
        assertEquals(Fallback.FAILED, run.fallback)
        assertEquals(4, run.stats.tiles)
        assertEquals(1, model.trims.get())
    }

    @Test
    fun pagesRunOneAtATime() {
        val model = FakeModel(onRun = { Thread.sleep(1) })
        val s = session(model)
        val threads = (0 until 4).map { i -> thread { TileEngine.run(s, page(120, 90, seed = i)) } }
        threads.forEach { it.join() }
        assertEquals(1, model.mostInside)
    }

    @Test
    fun aRunWaitingForAnotherCanBeCanceled() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        var armed = false
        val blocking = FakeModel(onRun = { if (armed) { started.countDown(); release.await(5, TimeUnit.SECONDS) } })
        val s = session(blocking)
        armed = true
        val first = thread { TileEngine.run(s, page(60, 60)) }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        val other = FakeModel()
        val o = session(other)
        val before = other.runs.get()
        assertThrows(CancellationException::class.java) { TileEngine.run(o, page(60, 60), canceled = { true }) }
        assertEquals(before, other.runs.get())
        release.countDown()
        first.join()
    }
}
