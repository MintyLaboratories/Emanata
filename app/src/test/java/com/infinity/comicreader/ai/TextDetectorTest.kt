package com.infinity.comicreader.ai

import android.app.Application
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TextDetectorTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** A detector model: maps any image to a 2 x 2 score map, noting how it was asked. */
    private class MapModel : TileModel by FakeModel(onGpu = false) {
        var maps = 0
        var closed = false
        var asked: Triple<FloatArray, FloatArray, Boolean>? = null

        override fun map(src: Bitmap, mean: FloatArray, norm: FloatArray, bgr: Boolean): ScoreMap? {
            maps++
            asked = Triple(mean, norm, bgr)
            return ScoreMap(2, 2, floatArrayOf(0f, 1f, 1f, 0f))
        }

        override fun close() {
            closed = true
        }
    }

    private val image = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)

    private fun files() = fakeFiles(tmp.root)

    @Test
    fun opensOnFirstUseOnTheCpuAndFeedsBgrNormalized() {
        val models = ArrayList<MapModel>()
        val runtime = FakeRuntime(make = { _, _ -> MapModel().also { models += it } })
        val detector = TextDetector(runtime, ::files)
        assertFalse(detector.isOpen)
        val map = detector.detect(image)
        assertNotNull(map)
        assertEquals(1f, map!![1, 0])
        detector.detect(image)
        assertEquals(1, models.size)
        assertEquals(2, models[0].maps)
        assertEquals(listOf(false), runtime.asked)
        val (mean, norm, bgr) = models[0].asked!!
        assertArrayEquals(TextDetector.MEAN, mean, 0f)
        assertArrayEquals(TextDetector.NORM, norm, 0f)
        assertTrue(bgr)
        assertEquals(0.485f * 255, mean[0], 1e-4f)
        detector.close()
    }

    @Test
    fun closesWhenIdleAndOpensAgainWhenAsked() {
        val models = ArrayList<MapModel>()
        val detector = TextDetector(FakeRuntime(make = { _, _ -> MapModel().also { models += it } }), ::files, idleMs = 50)
        detector.detect(image)
        val until = System.currentTimeMillis() + 5_000
        while (detector.isOpen && System.currentTimeMillis() < until) Thread.sleep(10)
        assertFalse(detector.isOpen)
        assertTrue(models[0].closed)
        assertNotNull(detector.detect(image))
        assertEquals(2, models.size)
        detector.close()
    }

    @Test
    fun aModelThatDoesNotOpenIsNotTriedAgain() {
        val runtime = FakeRuntime(make = { _, _ -> null })
        val detector = TextDetector(runtime, ::files)
        assertNull(detector.detect(image))
        assertNull(detector.detect(image))
        assertEquals(1, runtime.asked.size)
    }

    @Test
    fun missingFilesFindNothingThisTimeAndNoRuntimeNothingEver() {
        assertNull(TextDetector(FakeRuntime(make = { _, _ -> MapModel() }), { null }).detect(image))
        val off = FakeRuntime(available = false, make = { _, _ -> MapModel() })
        assertSame(TextDetector.ABSENT, TextDetector(off, ::files).detect(image))
        assertTrue(off.asked.isEmpty())
    }

    @Test
    fun theShippedModelHasTheSizesTheAppChecks() {
        val dir = File("src/main/assets/${TextDetector.DIR}")
        assertEquals(TextDetector.PARAM_BYTES, File(dir, TextDetector.PARAM).length())
        assertEquals(TextDetector.BIN_BYTES, File(dir, TextDetector.BIN).length())
        // Input and output as the detector's native call expects them.
        val param = File(dir, TextDetector.PARAM).readText()
        assertTrue(param.contains(Regex("Input\\s+in0 ")))
        assertTrue(param.contains(Regex("Sigmoid\\s+\\S+\\s+1 1 \\S+ out0")))
        assertFalse("size expressions do not load", param.contains("6=\""))
    }

    @Test
    fun installsFromTheAssetsOnce() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val files = TextDetector.install(context)!!
        assertEquals(TextDetector.PARAM_BYTES, files.param.length())
        assertEquals(TextDetector.BIN_BYTES, files.bin.length())
        files.bin.setLastModified(1_000_000L)
        TextDetector.install(context)
        assertEquals(1_000_000L, files.bin.lastModified())
        // A copy cut short is replaced.
        files.param.writeText("7767517\n")
        assertEquals(TextDetector.PARAM_BYTES, TextDetector.install(context)!!.param.length())
        // So is one an earlier install of the app made, though the same size.
        File(files.bin.parentFile, "installed").writeText("1")
        TextDetector.install(context)
        assertTrue(files.bin.lastModified() != 1_000_000L)
        files.bin.setLastModified(2_000_000L)
        TextDetector.install(context)
        assertEquals(2_000_000L, files.bin.lastModified())
    }
}
