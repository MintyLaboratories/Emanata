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
    fun itsLinesAreTheMapsTextRegionsOnTheImage() {
        // A map at half the image's size with one line of text, 8 x 4 at (2, 4).
        val half = object : TileModel by FakeModel(onGpu = false) {
            override fun map(src: Bitmap, mean: FloatArray, norm: FloatArray, bgr: Boolean) =
                ScoreMap(16, 16, FloatArray(256) { i -> if (i % 16 in 2 until 10 && i / 16 in 4 until 8) 0.9f else 0f })
        }
        val detector: CoverTextDetector = TextDetector(FakeRuntime(make = { _, _ -> half }), ::files)
        assertEquals("ppocrv5", detector.id)
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

    /** A runtime that opens only files in [opens], noting each open's files. */
    private class FilesRuntime(private val opens: Set<ModelFiles>) : InferenceRuntime {
        val asked = ArrayList<ModelFiles>()
        override val available = true
        override fun gpu(): GpuInfo? = null
        override fun open(files: ModelFiles, gpu: Boolean, threads: Int, fp16: Boolean): TileModel? {
            asked += files
            return if (files in opens) MapModel() else null
        }
    }

    @Test
    fun filesThatDoNotOpenFallBackToTheOthers() {
        val downloaded = fakeFiles(tmp.newFolder("downloaded"))
        val assets = fakeFiles(tmp.newFolder("assets"))
        val runtime = FilesRuntime(setOf(assets))
        val detector = TextDetector(runtime, { downloaded }, fallback = { assets })
        assertNotNull(detector.detect(image))
        assertEquals(listOf(downloaded, assets), runtime.asked)
        detector.close()
        // The same files are not tried twice; nothing opens, so nothing is tried again.
        val none = FilesRuntime(emptySet())
        val same = TextDetector(none, { assets }, fallback = { assets })
        assertNull(same.detect(image))
        assertNull(same.detect(image))
        assertEquals(listOf(assets), none.asked)
    }

    @Test
    fun theDownloadedModelIsUsedOnceVerifiedElseTheAssets() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val store = ModelStore.of(context)
        val d = ModelCatalog.TEXT_DETECTOR
        store.delete(d)
        assertNull(TextDetector.downloaded(store))
        val assets = TextDetector.copy(context)!!
        assertEquals(File(context.noBackupFilesDir, "${TextDetector.DIR}/${TextDetector.PARAM}"), assets.files.param)
        assertEquals(TextDetector.ID, assets.id)
        // In place at its size but not yet verified: not used.
        placeDownload(store)
        assertNull(TextDetector.downloaded(store))
        for (f in d.files) store.stamped(d, f)
        val files = TextDetector.downloaded(store)!!
        assertEquals(ModelFiles(store.target(d, d.param), store.target(d, d.bin)), files)
        assertEquals(TextDetector.Copy(files, TextDetector.DOWNLOADED_ID), TextDetector.copy(context))
        store.prune()
        assertEquals(files, TextDetector.copy(context)!!.files)
        store.delete(d)
    }

    /** Puts the downloaded model's files in [store] at their sizes, not yet verified. */
    private fun placeDownload(store: ModelStore) {
        val d = ModelCatalog.TEXT_DETECTOR
        store.dir(d).mkdirs()
        for (f in d.files) java.io.RandomAccessFile(store.target(d, f), "rw").use { it.setLength(f.bytes) }
    }

    /** Puts the downloaded model in [store], verified. */
    private fun download(store: ModelStore) {
        placeDownload(store)
        for (f in ModelCatalog.TEXT_DETECTOR.files) store.stamped(ModelCatalog.TEXT_DETECTOR, f)
    }

    @Test
    fun eachCopyHasItsOwnIdKeptForTheDetectorsLife() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val store = ModelStore.of(context)
        val d = ModelCatalog.TEXT_DETECTOR
        store.delete(d)
        val assets = TextDetector.install(context)!!
        val runtime = FilesRuntime(setOf(assets, ModelFiles(store.target(d, d.param), store.target(d, d.bin))))
        val before = TextDetector.forApp(context, runtime)
        assertEquals(TextDetector.ID, before.id)
        download(store)
        val after = TextDetector.forApp(context, runtime)
        assertEquals(TextDetector.DOWNLOADED_ID, after.id)
        assertTrue(TextDetector.DOWNLOADED_ID.startsWith(TextDetector.ID + "-"))
        assertTrue(TextDetector.DOWNLOADED_ID != TextDetector.ID)
        // A download that lands while a detector lives is not used by it.
        assertNotNull(before.detect(image))
        assertEquals(listOf(assets), runtime.asked)
        assertEquals(TextDetector.ID, before.id)
        assertNotNull(after.detect(image))
        assertEquals(store.target(d, d.param), runtime.asked.last().param)
        before.close()
        after.close()
        store.delete(d)
    }

    @Test
    fun aCopyThatDoesNotOpenFallsBackForGoodUnderTheFallbacksId() {
        val downloaded = fakeFiles(tmp.newFolder("downloaded"))
        val assets = fakeFiles(tmp.newFolder("assets"))
        val runtime = FilesRuntime(setOf(assets))
        val detector = TextDetector(runtime, { downloaded }, idleMs = 50, fallback = { assets }, filesId = "dl", fallbackId = "assets")
        assertEquals("dl", detector.id)
        // The run that falls back finds nothing for the id its caller read.
        assertNull(detector.lines(image))
        assertEquals("assets", detector.id)
        assertEquals(emptyList<Any>(), detector.lines(image))
        // Closed when idle, it opens the fallback again without trying the copy that failed.
        val until = System.currentTimeMillis() + 5_000
        while (detector.isOpen && System.currentTimeMillis() < until) Thread.sleep(10)
        assertFalse(detector.isOpen)
        assertNotNull(detector.detect(image))
        assertEquals(listOf(downloaded, assets, assets), runtime.asked)
        assertEquals("assets", detector.id)
        detector.close()
    }

    @Test
    fun missingFilesFallBackToo() {
        val assets = fakeFiles(tmp.newFolder("assets"))
        val runtime = FilesRuntime(setOf(assets))
        val detector = TextDetector(runtime, { null }, fallback = { assets }, filesId = "dl", fallbackId = "assets")
        assertNull(detector.lines(image))
        assertEquals("assets", detector.id)
        assertEquals(listOf(assets), runtime.asked)
        detector.close()
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
    fun theModelsNoticeStatesItsRewrittenReshapes() {
        val dir = File("src/main/assets/${TextDetector.DIR}")
        val rewritten = Regex("Reshape\\s+\\S+\\s+1 1 \\S+ \\S+ 0=1 1=1 2=-1\\s*$", RegexOption.MULTILINE)
            .findAll(File(dir, TextDetector.PARAM).readText()).count()
        assertTrue(rewritten > 0)
        val notice = File(dir, "NOTICE").readText()
        assertTrue(notice, notice.contains("$rewritten squeeze-and-excite reshape layers rewritten"))
        assertTrue(notice.contains("Apache License, Version 2.0"))
        assertTrue(notice.contains("fp16"))
    }

    @Test
    fun aCloseTimedAfterOneRunLeavesTheModelToTheNext() {
        val models = ArrayList<MapModel>()
        val idle = 400L
        val detector = TextDetector(FakeRuntime(make = { _, _ -> MapModel().also { models += it } }), ::files, idleMs = idle)
        detector.detect(image)
        // The first run's close comes due while the second holds the detector: it waits, then must leave the model open.
        synchronized(detector) {
            Thread.sleep(idle + 200)
            assertNotNull(detector.detect(image))
        }
        Thread.sleep(idle / 2)
        assertTrue(detector.isOpen)
        assertFalse(models[0].closed)
        // The second run's own close still comes.
        val until = System.currentTimeMillis() + 5_000
        while (detector.isOpen && System.currentTimeMillis() < until) Thread.sleep(10)
        assertFalse(detector.isOpen)
        assertEquals(1, models.size)
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
