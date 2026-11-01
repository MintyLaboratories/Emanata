package com.infinity.comicreader.ai

import android.app.Application
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.data.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.RandomAccessFile

/** The chain pages go through: the chosen models in step order, only while on, only once downloaded; its time together. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AiChainTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var prefs: Prefs
    private lateinit var store: ModelStore

    @Before
    fun setUp() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        prefs = Prefs(context)
        store = ModelStore(File(context.cacheDir, "chain-models").apply { deleteRecursively() })
    }

    private fun first(step: AiStep) = ModelCatalog.forStep(step).first()

    private fun install(m: CatalogModel) {
        for (f in m.files) {
            store.dir(m).mkdirs()
            RandomAccessFile(File(store.dir(m), f.name), "rw").use { it.setLength(f.bytes) }
            File(store.dir(m), f.name + ModelStore.STAMP).writeText(f.sha256)
        }
    }

    @Test
    fun nothingRunsWhileAiProcessingIsOff() {
        assertEquals(emptyList<CatalogModel>(), AiChain.chosen(prefs))
    }

    @Test
    fun theChosenModelsComeInStepOrder() {
        prefs.aiEnabled = true
        assertEquals(listOf(ModelCatalog.byId(ModelCatalog.DEFAULT_UPSCALE)), AiChain.chosen(prefs))
        for (step in AiStep.entries.reversed()) prefs.setAiModel(step, first(step).id)
        assertEquals(AiStep.entries.map { first(it) }, AiChain.chosen(prefs))
        prefs.setAiModel(AiStep.DESCREEN, null)
        assertEquals(listOf(first(AiStep.ARTIFACTS), first(AiStep.UPSCALE)), AiChain.chosen(prefs))
    }

    @Test
    fun aModelOfAnotherStepOrNoLongerInTheCatalogIsSkipped() {
        prefs.aiEnabled = true
        prefs.setAiModel(AiStep.ARTIFACTS, first(AiStep.UPSCALE).id)
        prefs.setAiModel(AiStep.UPSCALE, "retired-model")
        assertEquals(emptyList<CatalogModel>(), AiChain.chosen(prefs))
    }

    @Test
    fun onlyDownloadedModelsAreReady() {
        prefs.aiEnabled = true
        prefs.setAiModel(AiStep.ARTIFACTS, first(AiStep.ARTIFACTS).id)
        assertEquals(emptyList<Pair<CatalogModel, ModelFiles>>(), AiChain.ready(prefs, store))
        install(first(AiStep.ARTIFACTS))
        val ready = AiChain.ready(prefs, store)
        assertEquals(listOf(first(AiStep.ARTIFACTS)), ready.map { it.first })
        assertEquals(File(store.dir(first(AiStep.ARTIFACTS)), first(AiStep.ARTIFACTS).bin.name), ready.single().second.bin)
    }

    @Test
    fun aChainIsSlowOnceItsModelsTogetherPassThePageBudget() {
        val m = first(AiStep.UPSCALE)
        val half = ModelCatalog.MAX_PAGE_MS / 2
        val rest = ModelCatalog.MAX_PAGE_MS - half
        assertEquals(0L, AiChain.pageMs(emptyList()))
        assertFalse(AiChain.slow(emptyList()))
        assertEquals(ModelCatalog.MAX_PAGE_MS, AiChain.pageMs(listOf(m.copy(pageMs = half), m.copy(pageMs = rest))))
        assertFalse(AiChain.slow(listOf(m.copy(pageMs = half), m.copy(pageMs = rest))))
        assertTrue(AiChain.slow(listOf(m.copy(pageMs = half), m.copy(pageMs = rest + 1))))
        assertFalse(AiChain.slow(listOf(ModelCatalog.byId(ModelCatalog.DEFAULT_UPSCALE)!!)))
    }
}
