package com.infinity.comicreader.ai

import android.app.Application
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.testing.awaitUntil
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
import java.util.concurrent.CountDownLatch

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

    /** A detector model whose close says it started, then waits for [letGo]. */
    private class GatedModel(private val closing: CountDownLatch, private val letGo: CountDownLatch) : TileModel by MapModel() {
        override fun close() {
            closing.countDown()
            letGo.await()
        }
    }

    /** The detectors' idle-close thread, once a close was timed. */
    private fun closer(): Thread? = Thread.getAllStackTraces().keys.firstOrNull { it.name == "text-detector-idle" }

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
        awaitUntil("the idle close") { !detector.isOpen }
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
    fun itsLinesAreTheMapsTextRegionsOnTheImage() {
        // A map at half the image's size with one line of text, 8 x 4 at (2, 4).
        val half = object : TileModel by FakeModel(onGpu = false) {
            override fun map(src: Bitmap, mean: FloatArray, norm: FloatArray, bgr: Boolean) =
                ScoreMap(16, 16, FloatArray(256) { i -> if (i % 16 in 2 until 10 && i / 16 in 4 until 8) 0.9f else 0f })
        }
        val detector: CoverTextDetector = TextDetector(FakeRuntime(make = { _, _ -> half }), ::files)
        assertEquals(TextDetector.ID, detector.id)
        // Grown by 1.5 * 32 / 24 = 2 on each side, then doubled onto the image.
        val line = detector.lines(image)!!.single()
        assertEquals(listOf(0.0, 4.0, 24.0, 20.0), listOf(line.left, line.top, line.right, line.bottom))
        // A 2 x 2 map's text is too thin to be any.
        assertEquals(emptyList<Any>(), TextDetector(FakeRuntime(make = { _, _ -> MapModel() }), ::files).lines(image))
        assertNull(TextDetector(FakeRuntime(make = { _, _ -> null }), ::files).lines(image))
        assertEquals(emptyList<Any>(), TextDetector(FakeRuntime(available = false, make = { _, _ -> MapModel() }), ::files).lines(image))
        (detector as TextDetector).close()
    }

    @Test
    fun missingFilesFindNothingThisTimeAndNoRuntimeNothingEver() {
        assertNull(TextDetector(FakeRuntime(make = { _, _ -> MapModel() }), { null }).detect(image))
        val off = FakeRuntime(available = false, make = { _, _ -> MapModel() })
        assertSame(TextDetector.ABSENT, TextDetector(off, ::files).detect(image))
        assertTrue(off.asked.isEmpty())
    }

    @Test
    fun filesThatArriveLaterOpenThen() {
        var present: ModelFiles? = null
        val runtime = FakeRuntime(make = { _, _ -> MapModel() })
        val detector = TextDetector(runtime, { present })
        assertFalse(detector.ready())
        assertNull(detector.lines(image))
        assertTrue("missing files are no failure to open", runtime.asked.isEmpty())
        present = files()
        assertTrue(detector.ready())
        assertNotNull(detector.lines(image))
        assertEquals(1, runtime.asked.size)
        detector.close()
    }

    @Test
    fun readyAsksTheModelUnlessTheRuntimeNeverLoads() {
        var asked = 0
        val waiting = { asked++; false }
        assertFalse(TextDetector(FakeRuntime(make = { _, _ -> MapModel() }), ::files, inPlace = waiting).ready())
        assertEquals(1, asked)
        // Nothing to wait for where the runtime does not load: covers go on without text.
        assertTrue(TextDetector(FakeRuntime(available = false, make = { _, _ -> MapModel() }), { null }, inPlace = waiting).ready())
        assertEquals(1, asked)
        assertTrue(TextDetector(FakeRuntime(make = { _, _ -> MapModel() }), { null }, inPlace = { true }).ready())
    }

    @Test
    fun itsIdNamesTheDownloadsChecksums() {
        val d = ModelCatalog.TEXT_DETECTOR
        assertEquals("ppocrv5-${d.param.sha256.take(6)}${d.bin.sha256.take(6)}", TextDetector.ID)
    }

    @Test
    fun theAppsDetectorRunsTheDownloadOnceVerified() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val store = ModelStore.of(context)
        val d = ModelCatalog.TEXT_DETECTOR
        store.delete(d)
        val runtime = FakeRuntime(make = { _, _ -> MapModel() })
        val detector = TextDetector.forApp(context, runtime)
        assertNull(TextDetector.downloaded(store))
        assertNull(detector.detect(image))
        // In place at its size but not yet verified: not used.
        store.dir(d).mkdirs()
        for (f in d.files) java.io.RandomAccessFile(store.target(d, f), "rw").use { it.setLength(f.bytes) }
        assertNull(TextDetector.downloaded(store))
        assertNull(detector.detect(image))
        for (f in d.files) store.stamped(d, f)
        val files = TextDetector.downloaded(store)!!
        assertEquals(ModelFiles(store.target(d, d.param), store.target(d, d.bin)), files)
        assertNotNull(detector.detect(image))
        assertEquals(1, runtime.asked.size)
        store.prune()
        assertEquals(files, TextDetector.downloaded(store))
        detector.close()
        store.delete(d)
    }

    @Test
    fun aCloseTimedAfterOneRunLeavesTheModelToTheNext() {
        val models = ArrayList<MapModel>()
        val idle = 400L
        val detector = TextDetector(FakeRuntime(make = { _, _ -> MapModel().also { models += it } }), ::files, idleMs = idle)
        // Its close, due at once, holds back the closes due after it until let go.
        val closing = CountDownLatch(1)
        val letGo = CountDownLatch(1)
        val blocker = TextDetector(FakeRuntime(make = { _, _ -> GatedModel(closing, letGo) }), ::files, idleMs = 0)
        try {
            // The first run's close comes due while the second holds the detector: it waits, then must leave the model open.
            synchronized(detector) {
                detector.detect(image)
                awaitUntil("the first run's close to come due") { closer()?.state == Thread.State.BLOCKED }
                blocker.detect(image)
                assertNotNull(detector.detect(image))
            }
            awaitUntil("the first run's close to end") { closing.count == 0L }
            assertTrue(detector.isOpen)
            assertFalse(models[0].closed)
        } finally {
            letGo.countDown()
        }
        // The second run's own close still comes.
        awaitUntil("the second run's close") { !detector.isOpen }
        assertFalse(detector.isOpen)
        assertEquals(1, models.size)
    }
}
