package com.infinity.comicreader.ai

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.data.RetiredFiles
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
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
        store.root.deleteRecursively()
        ModelImports.cancel()
        ModelImports.runtime = FakeRuntime()
        ModelImports.memory = { Long.MAX_VALUE }
    }

    @After
    fun tearDown() {
        ModelImports.runtime = LibNcnn
        ModelImports.memory = ModelMemory::of
        store.root.deleteRecursively()
        UserModels.load(store)
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
        ModelImports.runtime = FakeRuntime(gpu = null, make = { onGpu, _ -> FakeModel(onGpu = onGpu, wrong = true) })
        ModelImports.start(context, pair("Sepia"))
        val until = System.currentTimeMillis() + 20_000
        while (ModelImports.state.value !is ImportState.Fit && System.currentTimeMillis() < until) Thread.sleep(10)
        val fit = ModelImports.state.value as ImportState.Fit
        assertEquals(setOf(ImportWarning.UNLIKE), fit.warnings)
        assertTrue(fit.warned)
        // Not saved while its warnings wait.
        ModelImports.save(context, "Sepia", AiStep.UPSCALE)
        assertEquals(fit, ModelImports.state.value)
        ModelImports.accept()
        val accepted = ModelImports.state.value as ImportState.Fit
        assertFalse(accepted.warned)
        assertEquals(fit.copy(accepted = true), accepted)
        ModelImports.save(context, "Sepia", AiStep.UPSCALE)
        while (ModelImports.state.value !is ImportState.Imported && System.currentTimeMillis() < until) Thread.sleep(10)
        assertEquals("Sepia", (ModelImports.state.value as ImportState.Imported).model.name)
    }

    @Test
    fun aModelWithWarningsCanceledKeepsNothing() {
        ModelImports.runtime = FakeRuntime(gpu = null, make = { onGpu, _ -> FakeModel(onGpu = onGpu, wrong = true) })
        ModelImports.start(context, pair("Sepia"))
        val until = System.currentTimeMillis() + 20_000
        while (ModelImports.state.value !is ImportState.Fit && System.currentTimeMillis() < until) Thread.sleep(10)
        val fit = ModelImports.state.value as ImportState.Fit
        assertTrue(store.staging(fit.id).isDirectory)
        ModelImports.dismiss(context)
        assertEquals(ImportState.Idle, ModelImports.state.value)
        while (store.staging(fit.id).exists() && System.currentTimeMillis() < until) Thread.sleep(10)
        assertFalse(store.staging(fit.id).exists())
        assertTrue(UserModels.all.isEmpty())
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
        RetiredFiles.drop(context)
        assertFalse("the store is cleaned", File(store.root, "dropped-model").exists())
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
    fun anImportCanceledThenStartedAgainShowsOnlyTheNewOne() {
        val checking = CountDownLatch(1)
        val release = CountDownLatch(1)
        ModelImports.runtime = FakeRuntime(make = { onGpu, _ ->
            FakeModel(onGpu = onGpu, onRun = {
                checking.countDown()
                release.await(10, TimeUnit.SECONDS)
            })
        })
        ModelImports.start(context, pair("First"))
        assertTrue(checking.await(10, TimeUnit.SECONDS))
        ModelImports.cancel()
        // One file: the new import waits for the other, once the canceled one has ended.
        val (_, param) = pair("Second")
        ModelImports.start(context, listOf(param))
        release.countDown()
        val until = System.currentTimeMillis() + 10_000
        while (ModelImports.state.value !is ImportState.Half && System.currentTimeMillis() < until) Thread.sleep(10)
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
        val until = System.currentTimeMillis() + 10_000
        while (ModelImports.state.value !is ImportState.Fit && System.currentTimeMillis() < until) Thread.sleep(10)
        // A file where the imported models' folder goes: the move fails.
        store.userRoot.writeText("")
        ModelImports.save(context, "Mine", AiStep.UPSCALE)
        while (ModelImports.state.value !is ImportState.Failed && System.currentTimeMillis() < until) Thread.sleep(10)
        assertEquals(ImportState.Failed(ImportFailure.STORAGE), ModelImports.state.value)
    }
}
