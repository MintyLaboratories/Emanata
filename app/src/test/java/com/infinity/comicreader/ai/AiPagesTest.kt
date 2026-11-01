package com.infinity.comicreader.ai

import android.app.Application
import android.graphics.Bitmap
import android.os.PowerManager
import com.infinity.comicreader.reader.BitmapUtils
import com.infinity.comicreader.reader.Plan
import com.infinity.comicreader.reader.Scaling
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CancellationException
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AiPagesTest {

    private fun page(w: Int, h: Int): Bitmap {
        val r = Random(w + h)
        val px = IntArray(w * h) { 0xFF000000.toInt() or r.nextInt(1 shl 24) }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPixels(px, 0, w, 0, 0, w, h)
            setHasAlpha(false)
        }
    }

    private fun pixels(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    private val plan = Plan(70, 105, 0.0, 0.0, 60.0, 90.0)

    @Test
    fun aFinishedRunIsResampledFromTheModelsOutput() {
        val model = FakeModel(scale = 2, reach = 1)
        val session = Session(model, ModelProbe.measure(model), 48)
        val src = page(60, 90)
        val got = AiPages.render(session, src, plan)
        assertNull(got.run.fallback)
        assertEquals(70, got.bitmap.width)
        assertEquals(105, got.bitmap.height)
        val big = Bitmap.createBitmap(120, 180, Bitmap.Config.ARGB_8888)
        big.setPixels(FakeModel.reference(src, 2, 1), 0, 120, 0, 0, 120, 180)
        big.setHasAlpha(false)
        val expected = BitmapUtils.resample(big, AiPages.scaled(plan, 2), false, Scaling.DEFAULT)
        assertArrayEquals(pixels(expected), pixels(got.bitmap))
    }

    @Test
    fun aFallbackIsThePlainLanczosPage() {
        val model = FakeModel(scale = 2, reach = 1)
        val session = Session(model, ModelProbe.measure(model), 48)
        val src = page(60, 90)
        val got = AiPages.render(session, src, plan, gate = { Fallback.PAUSED })
        assertEquals(Fallback.PAUSED, got.run.fallback)
        assertArrayEquals(pixels(BitmapUtils.resample(src, plan, false, Scaling.DEFAULT)), pixels(got.bitmap))
    }

    @Test
    fun cancelingThrows() {
        val model = FakeModel()
        val session = Session(model, ModelProbe.measure(model), 48)
        assertThrows(CancellationException::class.java) { AiPages.render(session, page(60, 90), plan, canceled = { true }) }
    }

    @Test
    fun scaledPlansReadTheSameRegionOfTheLargerSource() {
        val p = Plan(100, 50, 3.5, 7.0, 40.0, 20.0)
        assertEquals(Plan(100, 50, 7.0, 14.0, 80.0, 40.0), AiPages.scaled(p, 2))
        assertEquals(p, AiPages.scaled(p, 1))
    }

    @Test
    fun anUpscaleShownNoLargerThanThePageDoesNotRun() {
        val model = FakeModel(scale = 2, reach = 1)
        val session = Session(model, ModelProbe.measure(model), 48)
        val before = model.runs.get()
        val src = page(60, 90)
        val shrink = Plan(50, 75, 0.0, 0.0, 60.0, 90.0)
        val got = AiPages.render(session, src, shrink)
        assertEquals(Fallback.UNNEEDED, got.run.fallback)
        assertEquals(before, model.runs.get())
        assertArrayEquals(pixels(BitmapUtils.resample(src, shrink, false, Scaling.DEFAULT)), pixels(got.bitmap))
        // Enlarged along one axis only: it runs.
        assertNull(AiPages.render(session, src, Plan(50, 100, 0.0, 0.0, 60.0, 90.0)).run.fallback)
    }

    @Test
    fun aSameSizeModelRunsForAShrunkPage() {
        val model = FakeModel(scale = 1, reach = 1)
        val session = Session(model, ModelProbe.measure(model), 48)
        val got = AiPages.render(session, page(60, 90), Plan(30, 45, 0.0, 0.0, 60.0, 90.0))
        assertNull(got.run.fallback)
        assertTrue(got.run.stats.tiles > 0)
    }

    @Test
    fun runsStopWithBatterySaverOrOnceHot() {
        assertNull(AiConditions.stop(false, PowerManager.THERMAL_STATUS_NONE))
        assertNull(AiConditions.stop(false, PowerManager.THERMAL_STATUS_LIGHT))
        assertEquals(Fallback.PAUSED, AiConditions.stop(false, PowerManager.THERMAL_STATUS_MODERATE))
        assertEquals(Fallback.PAUSED, AiConditions.stop(false, PowerManager.THERMAL_STATUS_SHUTDOWN))
        assertEquals(Fallback.PAUSED, AiConditions.stop(true, PowerManager.THERMAL_STATUS_NONE))
        assertTrue(AiConditions.HOT > PowerManager.THERMAL_STATUS_LIGHT)
    }

    @Test
    fun runsStopOnceTheForecastHeadroomIsNearlyUsed() {
        assertNull(AiConditions.stop(false, PowerManager.THERMAL_STATUS_NONE, 0.89f))
        assertEquals(Fallback.PAUSED, AiConditions.stop(false, PowerManager.THERMAL_STATUS_NONE, AiConditions.MAX_HEADROOM))
        assertEquals(Fallback.PAUSED, AiConditions.stop(false, PowerManager.THERMAL_STATUS_NONE, 1.2f))
        assertNull("unknown headroom goes on", AiConditions.stop(false, PowerManager.THERMAL_STATUS_NONE, Float.NaN))
    }

    @Test
    fun headroomIsReadAtMostOnceASecondKeepingTheLastNumber() {
        var now = 0L
        var reads = 0
        val answers = ArrayDeque(listOf(0.5f, Float.NaN, 0.95f))
        val h = AiConditions.Headroom({ now }) { reads++; answers.removeFirst() }
        assertEquals(0.5f, h.get())
        now += AiConditions.HEADROOM_EVERY_MS - 1
        assertEquals(0.5f, h.get())
        assertEquals(1, reads)
        now += 1
        assertEquals("NaN keeps the last", 0.5f, h.get())
        now += AiConditions.HEADROOM_EVERY_MS
        assertEquals(0.95f, h.get())
        assertEquals(3, reads)
    }
}
