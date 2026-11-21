package com.infinity.comicreader.ai

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.testing.awaitUntil
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLog
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/** Importing the user's own models: picked files copied, checked, then named and given a step. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ModelImportsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val store get() = ModelStore.of(context)

    @Before
    fun setUp() {
        // The app's start tidies the store on the imports' thread: done before this test writes it.
        ModelImports.settle()
        store.root.deleteRecursively()
        ModelImports.cancel()
        ModelImports.runtime = FakeRuntime()
        ModelImports.memory = { Long.MAX_VALUE }
    }

    @After
    fun tearDown() {
        ModelImports.runtime = LibNcnn
        ModelImports.memory = ModelMemory::of
        ModelImports.clock = SystemClock::elapsedRealtime
        store.root.deleteRecursively()
        UserModels.load(store)
    }

    /** Waits for the imports' work queued so far ([ModelImports.settle]); fails naming [what] should it hang. */
    private fun settle(what: String) {
        val drained = thread(isDaemon = true) { ModelImports.settle() }
        awaitUntil(what) { !drained.isAlive }
    }

    /** Checks run a model whose every tile takes 2 s on the check's clock: a page of about a minute, warned of as slow. */
    private fun slow() {
        val now = AtomicLong()
        ModelImports.clock = now::get
        ModelImports.runtime = FakeRuntime(gpu = null, make = { onGpu, _ -> FakeModel(onGpu = onGpu, onRun = { now.addAndGet(2_000) }) })
    }

    /** A picked pair: a `.param` named [name] and its `.bin`, as file URIs. */
    private fun pair(name: String = "Sharp lines 2x"): List<Uri> {
        val dir = tmp.newFolder()
        val files = fakeFiles(dir)
        val param = File(dir, "$name.param").also { files.param.renameTo(it) }
        val bin = File(dir, "$name.bin").also { files.bin.renameTo(it) }
        return listOf(Uri.fromFile(bin), Uri.fromFile(param))
    }

    @Test
    fun aGoodPairIsCheckedThenTakenInAsNamed() {
        val fit = ModelImports.importNow(context, pair()) as ImportState.Fit
        assertEquals("Sharp lines 2x", fit.suggested)
        assertEquals(2, fit.scale)
        assertTrue(store.staging(fit.id).isDirectory)
        assertEquals(listOf(AiStep.UPSCALE), ModelImports.steps(fit.scale))
        val m = ModelImports.saveNow(store, fit, "  Lines  ", AiStep.UPSCALE)
        assertEquals("Lines", m.name)
        assertEquals(AiStep.UPSCALE, m.step)
        assertEquals(ModelDownloader.sha256(store.files(m)!!.bin), m.binSha256)
        assertEquals(listOf(m), UserModels.all)
        assertFalse(store.staging(fit.id).exists())
        assertFalse("the check's marker is left", File(store.root, ModelImports.CHECKING).exists())
    }

    @Test
    fun aModelWithWarningsIsNamedOnlyOnceAccepted() {
        slow()
        ModelImports.start(context, pair("Slow lines"))
        settle("the import")
        val fit = ModelImports.state.value as ImportState.Fit
        assertEquals(setOf(ImportWarning.SLOW), fit.warnings)
        assertTrue(fit.warned)
        // Not saved while its warnings wait.
        ModelImports.save(context, "Slow lines", AiStep.UPSCALE)
        assertEquals(fit, ModelImports.state.value)
        // Its before and after wait for its warnings.
        ModelImports.previewed()
        assertEquals(fit, ModelImports.state.value)
        ModelImports.accept()
        val accepted = ModelImports.state.value as ImportState.Fit
        assertFalse(accepted.warned)
        assertEquals(fit.copy(accepted = true), accepted)
        // Not saved while its before and after wait.
        assertTrue(accepted.previewing)
        ModelImports.save(context, "Slow lines", AiStep.UPSCALE)
        assertEquals(accepted, ModelImports.state.value)
        ModelImports.previewed()
        assertEquals(accepted.copy(seen = true), ModelImports.state.value)
        ModelImports.save(context, "Slow lines", AiStep.UPSCALE)
        settle("the save")
        assertEquals("Slow lines", (ModelImports.state.value as ImportState.Imported).model.name)
    }

    @Test
    fun aFitModelComesWithItsBeforeAndAfterOnEachTestPicture() {
        val fit = ModelImports.importNow(context, pair()) as ImportState.Fit
        assertEquals(PreviewPicture.entries, fit.previews.map { it.picture })
        for (p in fit.previews) {
            assertTrue(p.picture.name, p.before.width in 16..128 && p.before.width == p.before.height)
            assertEquals(p.before.width * fit.scale, p.after.width)
            assertEquals(p.before.height * fit.scale, p.after.height)
        }
        assertTrue(fit.previewing)
        assertFalse(fit.copy(seen = true).previewing)
        assertFalse("its warnings first", fit.copy(warnings = setOf(ImportWarning.SLOW)).previewing)
        assertFalse("none to show", fit.copy(previews = emptyList()).previewing)
    }

    @Test
    fun theTestPicturesAreOpaqueTileSizedSquares() {
        val pictures = PreviewPicture.load(context)
        assertEquals(PreviewPicture.entries, pictures.map { it.first })
        for ((p, b) in pictures) {
            assertEquals(p.name, Bitmap.Config.ARGB_8888, b.config)
            assertEquals(p.name, 128, b.width)
            assertEquals(p.name, 128, b.height)
            assertTrue(p.name, (0 until 128).all { x -> (b.getPixel(x, x) ushr 24) == 0xFF })
        }
        // The dot pattern is gray, the others in color.
        assertTrue(GrayPages.isGray(pictures[2].second))
        assertFalse(GrayPages.isGray(pictures[0].second))
        assertFalse(GrayPages.isGray(pictures[1].second))
        // At its scanned size, unresampled: dot centers near black.
        val dots = pictures[2].second
        val dark = (0 until 128 * 128).count { i -> (dots.getPixel(i % 128, i / 128) and 0xFF) < 32 }
        assertTrue("$dark", dark > 128 * 128 * 3 / 100)
    }

    @Test
    fun aTestPictureThatDoesNotDecodeAsARGBIsLoggedAndLeftOut() {
        ShadowLog.clear()
        val argb = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        assertTrue(PreviewPicture.usable(PreviewPicture.GRADIENTS, argb) === argb)
        assertTrue(ShadowLog.getLogsForTag("PreviewPicture").isEmpty())
        val small = Bitmap.createBitmap(4, 4, Bitmap.Config.RGB_565)
        assertNull(PreviewPicture.usable(PreviewPicture.JPEG, small))
        assertTrue(small.isRecycled)
        assertNull(PreviewPicture.usable(PreviewPicture.DOTS, null))
        val logged = ShadowLog.getLogsForTag("PreviewPicture")
        assertEquals(listOf(Log.WARN, Log.WARN), logged.map { it.type })
        assertTrue(logged[0].msg.contains(PreviewPicture.JPEG.asset))
        assertTrue(logged[1].msg.contains(PreviewPicture.DOTS.asset))
    }

    @Test
    fun aModelWithWarningsCanceledKeepsNothing() {
        slow()
        ModelImports.start(context, pair("Slow lines"))
        settle("the import")
        val fit = ModelImports.state.value as ImportState.Fit
        assertTrue(store.staging(fit.id).isDirectory)
        ModelImports.dismiss(context)
        assertEquals(ImportState.Idle, ModelImports.state.value)
        settle("the dismissed model's delete")
        assertFalse(store.staging(fit.id).exists())
        assertTrue(UserModels.all.isEmpty())
    }

    @Test
    fun aModelCanceledAfterItsCheckKeepsNothing() {
        // Canceled as the checked model closes: past the check's last look at it.
        ModelImports.runtime = FakeRuntime(gpu = null, make = { onGpu, _ -> FakeModel(onGpu = onGpu, onClose = { ModelImports.cancel() }) })
        ModelImports.start(context, pair())
        val staged = store.staging("x").parentFile!!
        settle("the canceled import")
        assertEquals(emptyList<String>(), staged.list().orEmpty().toList())
        assertEquals(ImportState.Idle, ModelImports.state.value)
    }

    @Test
    fun aFitModelReplacedByAnotherImportKeepsNothing() {
        slow()
        ModelImports.start(context, pair("Slow lines"))
        settle("the first import")
        val fit = ModelImports.state.value as ImportState.Fit
        val (_, param) = pair("Second")
        ModelImports.start(context, listOf(param))
        settle("the second import")
        assertFalse(store.staging(fit.id).exists())
        ModelImports.cancel()
    }

    @Test
    fun oneFileWaitsForTheOther() {
        val (bin, param) = pair()
        assertEquals(ImportState.Half(param, isParam = true), ModelImports.importNow(context, listOf(param)))
        assertEquals(ImportState.Half(bin, isParam = false), ModelImports.importNow(context, listOf(bin)))
    }

    @Test
    fun anythingButOneParamAndOneBinIsRefusedUnchecked() {
        val a = pair("a")
        val b = pair("b")
        assertEquals(ImportState.Failed(ImportFailure.NOT_A_PAIR), ModelImports.importNow(context, listOf(a[1], b[1])))
        assertEquals(ImportState.Failed(ImportFailure.NOT_A_PAIR), ModelImports.importNow(context, listOf(a[0], b[0])))
        assertEquals(ImportState.Failed(ImportFailure.NOT_A_PAIR), ModelImports.importNow(context, a + b[0]))
        assertEquals(ImportState.Failed(ImportFailure.UNREADABLE), ModelImports.importNow(context, listOf(a[1], Uri.fromFile(File(tmp.root, "gone.bin")))))
        assertTrue((ModelImports.runtime as FakeRuntime).asked.isEmpty())
    }

    @Test
    fun aBrokenParamIsRefusedAsNotLoading() {
        val dir = tmp.newFolder()
        val param = File(dir, "broken.param").apply { writeText("not a model\n") }
        val bin = File(dir, "broken.bin").apply { writeBytes(ByteArray(16)) }
        val r = ModelImports.importNow(context, listOf(Uri.fromFile(param), Uri.fromFile(bin))) as ImportState.Refused
        assertEquals(ModelRefusal.NOT_LOADED, r.why)
        assertEquals("broken", r.name)
        assertNull("kept in staging", store.root.resolve(ModelStore.STAGING).listFiles()?.firstOrNull())
    }

    @Test
    fun aPairThatDoesNotLoadIsRefusedAndNothingKept() {
        ModelImports.runtime = FakeRuntime(make = { _, _ -> null })
        val r = ModelImports.importNow(context, pair()) as ImportState.Refused
        assertEquals(ModelRefusal.NOT_LOADED, r.why)
        assertNull(store.root.resolve(ModelStore.STAGING).listFiles()?.firstOrNull())
        assertTrue(UserModels.all.isEmpty())
    }

    @Test
    fun aModelPastTheDevicesMemoryIsRefusedWithItsNeed() {
        ModelImports.runtime = FakeRuntime(make = { onGpu, _ -> FakeModel(onGpu = onGpu, bytesPerPixel = 1L shl 20) })
        ModelImports.memory = { 1L shl 30 }
        val r = ModelImports.importNow(context, pair()) as ImportState.Refused
        assertEquals(ModelRefusal.MEMORY, r.why)
        assertEquals(1L shl 30, r.limit)
        assertTrue(r.bytes > r.limit)
    }

    @Test
    fun aCheckThatEndedTheAppIsToldOnce() {
        store.root.mkdirs()
        File(store.root, ModelImports.CHECKING).writeText("Heavy one")
        assertEquals("Heavy one", ModelImports.crashed(context))
        assertNull(ModelImports.crashed(context))
    }

    @Test
    fun aCheckThatEndedTheAppIsToldAfterTheStartCleansTheStore() {
        store.root.mkdirs()
        File(store.root, ModelImports.CHECKING).writeText("Heavy one")
        File(store.root, "dropped-model").mkdirs()
        val offered = store.dir(ModelCatalog.all.first()).apply { mkdirs() }
        File(offered, "part.bin.part").writeText("x")
        ModelImports.tidy(store)
        assertFalse("the store is cleaned", File(store.root, "dropped-model").exists())
        assertTrue("a model offered stays", File(offered, "part.bin.part").exists())
        assertEquals("Heavy one", ModelImports.crashed(context))
        assertNull(ModelImports.crashed(context))
    }

    @Test
    fun pruningKeepsTheMarkerOfACheck() {
        store.root.mkdirs()
        File(store.root, ModelImports.CHECKING).writeText("Heavy one")
        store.prune()
        assertEquals("Heavy one", ModelImports.crashed(context))
    }

    @Test
    fun theStartsTidyingWaitsForAnImportUnderWay() {
        val checking = CountDownLatch(1)
        val release = CountDownLatch(1)
        lateinit var files: ModelFiles
        // Whether the checked files were there at each run of the check after the tidy was queued.
        val kept = CopyOnWriteArrayList<Boolean>()
        ModelImports.runtime = FakeRuntime(make = { onGpu, _ ->
            FakeModel(onGpu = onGpu, onRun = {
                checking.countDown()
                release.await()
                kept += files.param.exists() && files.bin.exists()
            })
        })
        try {
            ModelImports.start(context, pair())
            assertTrue(checking.await(10, TimeUnit.SECONDS))
            files = store.userFiles(store.staging("x").parentFile!!.listFiles()!!.single())
            ModelImports.tidyAtStart(store)
        } finally {
            release.countDown()
        }
        settle("the import and the tidy")
        assertTrue("the checked files stay: $kept", kept.isNotEmpty() && kept.all { it })
        assertTrue(ModelImports.state.value is ImportState.Fit)
    }

    @Test
    fun anImportCanceledThenStartedAgainShowsOnlyTheNewOne() {
        val checking = CountDownLatch(1)
        val release = CountDownLatch(1)
        ModelImports.runtime = FakeRuntime(make = { onGpu, _ ->
            FakeModel(onGpu = onGpu, onRun = {
                checking.countDown()
                release.await()
            })
        })
        val param = try {
            ModelImports.start(context, pair("First"))
            assertTrue(checking.await(10, TimeUnit.SECONDS))
            ModelImports.cancel()
            // One file: the new import waits for the other, once the canceled one has ended.
            pair("Second")[1].also { ModelImports.start(context, listOf(it)) }
        } finally {
            release.countDown()
        }
        settle("the canceled import and the new one")
        assertEquals(ImportState.Half(param, isParam = true), ModelImports.state.value)
        ModelImports.cancel()
    }

    @Test
    fun aCanceledCopyKeepsNothing() {
        assertThrows(CancellationException::class.java) { ModelImports.importNow(context, pair(), canceled = { true }) }
        assertNull(store.root.resolve(ModelStore.STAGING).listFiles()?.firstOrNull())
        assertTrue((ModelImports.runtime as FakeRuntime).asked.isEmpty())
    }

    @Test
    fun stepsSuitTheScale() {
        assertEquals(listOf(AiStep.ARTIFACTS, AiStep.DESCREEN), ModelImports.steps(1))
        assertEquals(listOf(AiStep.UPSCALE), ModelImports.steps(3))
    }

    @Test
    fun aModelTheStoreWillNotTakeIsNotBlamedOnSpace() {
        ModelImports.start(context, pair())
        settle("the import")
        // A file where the imported models' folder goes: the move fails.
        store.userRoot.writeText("")
        ModelImports.previewed()
        ModelImports.save(context, "Mine", AiStep.UPSCALE)
        settle("the save")
        assertEquals(ImportState.Failed(ImportFailure.STORAGE), ModelImports.state.value)
    }
}
