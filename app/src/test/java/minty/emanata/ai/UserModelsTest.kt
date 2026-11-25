package minty.emanata.ai

import android.app.Application
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import minty.emanata.data.Prefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Imported models: kept beside their files, read back, found and chosen like catalog ones. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class UserModelsTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var store: ModelStore

    @Before
    fun setUp() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        store = ModelStore(File(context.cacheDir, "user-models").apply { deleteRecursively() })
    }

    @After
    fun tearDown() {
        store.root.deleteRecursively()
        UserModels.load(store)
    }

    /** A model of [scale] at [step], [pageMs] a page, its files staged and taken in. */
    private fun import(
        id: String, name: String, step: AiStep, scale: Int = if (step == AiStep.UPSCALE) 2 else 1, pageMs: Long = 2_500, headroom: Float = Float.NaN
    ): UserModel {
        val dir = store.staging(id).apply { mkdirs() }
        val files = store.userFiles(dir)
        files.param.writeText("7767517\n1 1\nInput in0 0 1 in0\n")
        files.bin.writeBytes(ByteArray(100))
        val m = UserModel(id, name, step, scale, pageMs, files.param.length(), 100, "a".repeat(64), "b".repeat(64), headroom)
        store.saveUser(m)
        UserModels.load(store)
        return m
    }

    @Test
    fun anImportedModelReadsBackAsSaved() {
        val m = import("user-1", "My upscaler", AiStep.UPSCALE)
        assertEquals(listOf(m), store.userModels())
        assertEquals(listOf(m), UserModels.all)
        assertTrue(store.installed(m))
        val files = store.files(m)!!
        assertEquals(File(store.userDir("user-1"), ModelStore.USER_PARAM), files.param)
        assertFalse("left in staging", store.staging("user-1").exists())
        assertEquals(m.paramBytes + 100, m.bytes)
    }

    @Test
    fun theHeadroomItsPageWasTimedAtReadsBack() {
        val m = import("user-1", "Timed warm", AiStep.UPSCALE, headroom = 0.42f)
        assertEquals(0.42f, store.userModels().single().pageHeadroom, 0f)
        assertEquals(listOf(m), store.userModels())
        import("user-2", "Timed somewhere", AiStep.UPSCALE)
        assertTrue(store.userModels().single { it.id == "user-2" }.pageHeadroom.isNaN())
    }

    @Test
    fun foldersWithoutWhatTheModelIsAreLeftOutThenPruned() {
        import("user-1", "Kept", AiStep.UPSCALE)
        val broken = store.userDir("user-2").apply { mkdirs() }
        File(broken, ModelStore.USER_PARAM).writeText("7767517")
        val unreadable = store.userDir("user-3").apply { mkdirs() }
        File(unreadable, ModelStore.USER_INFO).writeText("name=x\nstep=nowhere\n")
        assertEquals(listOf("user-1"), store.userModels().map { it.id })
        assertTrue("reading deletes nothing", broken.exists() && unreadable.exists())
        store.prune()
        assertFalse(broken.exists())
        assertFalse(unreadable.exists())
        assertEquals(listOf("user-1"), store.userModels().map { it.id })
    }

    @Test
    fun pruningKeepsImportedModelsButNotUnfinishedImports() {
        import("user-1", "Kept", AiStep.UPSCALE)
        store.staging("user-2").mkdirs()
        File(store.root, "dropped-from-catalog").mkdirs()
        store.prune()
        assertEquals(listOf("user-1"), store.userModels().map { it.id })
        assertFalse(store.staging("user-2").exists())
        assertFalse(File(store.root, "dropped-from-catalog").exists())
    }

    @Test
    fun deletingRemovesItsFolder() {
        val m = import("user-1", "Gone", AiStep.DESCREEN)
        assertTrue(store.delete(m))
        assertFalse(store.userDir("user-1").exists())
        UserModels.load(store)
        assertNull(UserModels.byId("user-1"))
        assertNull(store.files(m))
    }

    @Test
    fun importedModelsAreFoundAndOfferedAfterTheCatalogs() {
        val up = import("user-1", "Mine", AiStep.UPSCALE)
        val clean = import("user-2", "Cleaner", AiStep.ARTIFACTS)
        assertEquals(up, Models.byId("user-1"))
        assertEquals(ModelCatalog.byId(ModelCatalog.DEFAULT_UPSCALE), Models.byId(ModelCatalog.DEFAULT_UPSCALE))
        assertEquals(ModelCatalog.forStep(AiStep.UPSCALE) + ShaderCatalog.forStep(AiStep.UPSCALE) + up, Models.forStep(AiStep.UPSCALE))
        assertEquals(ModelCatalog.forStep(AiStep.ARTIFACTS) + clean, Models.forStep(AiStep.ARTIFACTS))
        // Slow or not, an imported model is offered while keeping it simple.
        import("user-3", "Slow one", AiStep.UPSCALE, pageMs = 60_000)
        assertTrue(Models.offered(AiStep.UPSCALE, keepSimple = true, current = null).any { it.id == "user-3" })
        assertEquals(ModelCatalog.all.size + ShaderCatalog.all.size + 3, Models.all().size)
    }

    @Test
    fun aPickOfAnImportedModelRunsItAndADeletedOneRunsNone() {
        val m = import("user-1", "Mine", AiStep.UPSCALE)
        val prefs = Prefs(context)
        prefs.aiEnabled = true
        prefs.setAiModel(AiStep.UPSCALE, m.id)
        assertEquals(listOf<AiModel>(m), AiChain.chosen(prefs))
        assertEquals(listOf<AiModel>(m), AiPicks(mapOf(AiStep.UPSCALE to m.id)).models)
        // Picked at a step it was not imported for: none.
        assertEquals(emptyList<AiModel>(), AiPicks(mapOf(AiStep.DESCREEN to m.id)).models)
        store.delete(m)
        UserModels.load(store)
        assertEquals(emptyList<AiModel>(), AiChain.chosen(prefs))
    }

    @Test
    fun signaturesNameEachModelsFiles() {
        val m = import("user-1", "Mine", AiStep.UPSCALE)
        val w = ModelCatalog.byId(ModelCatalog.DEFAULT_UPSCALE)!!
        assertEquals("e${AiChain.ENGINE};${w.id}:${w.param.sha256.take(16)}:${w.bin.sha256.take(16)}", AiChain.signature(listOf(w)))
        assertEquals("e${AiChain.ENGINE};user-1:${"a".repeat(16)}:${"b".repeat(16)}", AiChain.signature(listOf(m)))
        assertFalse(AiChain.signature(listOf(m)) == AiChain.signature(listOf(m.copy(binSha256 = "c".repeat(64)))))
    }

    @Test
    fun anImportedModelsSlownessIsItsTimeHere() {
        assertFalse(UserModel("u", "n", AiStep.UPSCALE, 2, 9_499, 1, 1, "", "").slow)
        assertTrue(UserModel("u", "n", AiStep.UPSCALE, 2, ModelCatalog.SLOW_PAGE_MS, 1, 1, "", "").slow)
        assertEquals(10L, UserModel("u", "n", AiStep.UPSCALE, 2, 9_500, 1, 1, "", "").pageSeconds)
    }
}
