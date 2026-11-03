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
        // 100 ms a tile: the pace after four tiles predicts a miss of 800 ms, so no fifth runs.
        val run = TileEngine.run(
            s, page(200, 200), PageLimits(timeoutMs = 800), clock = { now }, onTile = { _, _, _ -> now += 100 }
        )
        assertNull(run.bitmap)
        assertEquals(Fallback.TIMEOUT, run.fallback)
        assertEquals(4, run.stats.tiles)
        assertEquals(tiles, run.stats.planned)
    }

    @Test
    fun aStoppedRunsFullTimeCountsItsFirstTileOnce() {
        // 50 ms outside tiles, a 2 s warm-up tile, then 100 ms a tile: 66 more to go.
        assertEquals(50L + 2_000 + 69 * 100, RunStats(4, 70, longArrayOf(2_000, 100, 100, 100), 2_350).fullMs)
        assertEquals("finished: as it took", 3_210L, RunStats(3, 3, longArrayOf(3_000, 100, 100), 3_210).fullMs)
        assertNull("one tile tells no pace", RunStats(1, 70, longArrayOf(2_000), 2_050).fullMs)
        assertNull("two or three tell too little", RunStats(2, 70, longArrayOf(2_000, 100), 2_150).fullMs)
        assertNull(RunStats(3, 70, longArrayOf(2_000, 100, 100), 2_250).fullMs)
        assertNull(RunStats.NONE.fullMs)
    }

    @Test
    fun aSlowFirstTileDoesNotSetThePace() {
        val s = session(FakeModel())
        var now = 0L
        val tiles = TilePlan.tiles(200, 200, s.size, s.geometry).size
        // The first tile takes 2 s (warm-up), the rest 100 ms: the page fits its time.
        val run = TileEngine.run(
            s, page(200, 200), PageLimits(timeoutMs = 2_000L + tiles * 100L), clock = { now },
            onTile = { d, _, _ -> now += if (d == 1) 2_000 else 100 }
        )
        assertNotNull(run.bitmap)
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
    fun aHeldRunWaitsAndTheTimeHeldCountsTowardNeitherLimitNorPace() {
        val model = FakeModel()
        val s = session(model)
        val tiles = TileEngine.run(s, page(200, 200)).stats.planned
        var now = 0L
        var holds = 0
        // Each tile takes 100 ms; before each the run is held twice, 1 s a time, far past the limit.
        val run = TileEngine.run(
            s, page(200, 200), PageLimits(timeoutMs = tiles * 100L + 50), clock = { now },
            hold = { (holds++ % 3 != 2).also { if (it) now += 1_000 } },
            onTile = { _, _, _ -> now += 100 }
        )
        assertNotNull(run.bitmap)
        assertTrue(holds > 3)
        assertEquals("held time is left out", tiles * 100L, run.stats.totalMs)
    }

    @Test
    fun aRunCanceledWhileHeldStops() {
        val s = session(FakeModel())
        assertThrows(CancellationException::class.java) {
            TileEngine.run(s, page(60, 60), canceled = { true }, hold = { true })
        }
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
        val src = page(50, 150).apply {
            setHasAlpha(true)
            setPixel(20, 140, 0x80FFFFFF.toInt())
        }
        val s = session(model)
        val before = model.runs.get()
        assertEquals(Fallback.TRANSLUCENT, TileEngine.run(s, src).fallback)
        assertEquals(before, model.runs.get())
    }

    @Test
    fun anOpaquePageMarkedAsHavingAlphaRuns() {
        // As decoders mark opaque RGBA pages: every pixel opaque, the bitmap saying it has alpha.
        val src = page(50, 150).apply { setHasAlpha(true) }
        assertTrue(src.hasAlpha())
        val run = TileEngine.run(session(FakeModel()), src)
        assertNull(run.fallback)
        assertTrue(TileEngine.opaque(page(3, 3)))
    }

    @Test
    fun anUnreadablePageFallsBackBeforeAnyTile() {
        val model = FakeModel()
        val s = session(model)
        val before = model.runs.get()
        val small = Bitmap.createBitmap(40, 40, Bitmap.Config.RGB_565)
        assertEquals(Fallback.UNREADABLE, TileEngine.run(s, small).fallback)
        val recycled = page(40, 40).apply { recycle() }
        assertEquals(Fallback.UNREADABLE, TileEngine.run(s, recycled).fallback)
        assertEquals(before, model.runs.get())
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
