package com.infinity.comicreader.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The model catalog: well-formed entries, from the pinned repository, under licenses the app can use, each within a page's time. */
class ModelCatalogTest {

    private val assetDir = File("src/main/assets/licenses")

    @Test
    fun theCatalogOffersEveryStep() {
        for (step in AiStep.entries) assertTrue(step.name, ModelCatalog.forStep(step).isNotEmpty())
        assertEquals(ModelCatalog.all, AiStep.entries.flatMap { ModelCatalog.forStep(it) }.sortedBy { ModelCatalog.all.indexOf(it) })
    }

    @Test
    fun idsAreUniqueAndSafeAsFolderNames() {
        val ids = ModelCatalog.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        for (id in ids) assertTrue(id, id.matches(Regex("[a-z0-9][a-z0-9._-]*")) && ".." !in id)
        assertEquals(ModelCatalog.all.map { it.name }.size, ModelCatalog.all.map { it.name }.toSet().size)
    }

    @Test
    fun filesComeFromThePinnedRepositoryWithChecksums() {
        assertTrue(ModelCatalog.BASE.startsWith("https://raw.githubusercontent.com/ollm/opencomic-ai-models/${ModelCatalog.COMMIT}/"))
        assertTrue(ModelCatalog.COMMIT.matches(Regex("[0-9a-f]{40}")))
        for (m in ModelCatalog.all) {
            assertTrue(m.param.name, m.param.name.endsWith(".param"))
            assertTrue(m.bin.name, m.bin.name.endsWith(".bin"))
            assertEquals(m.param.url.removeSuffix(".param"), m.bin.url.removeSuffix(".bin"))
            for (f in m.files) {
                assertTrue(f.url, f.url.startsWith(ModelCatalog.BASE) && f.url.endsWith("/" + f.name))
                assertTrue(f.sha256, f.sha256.matches(Regex("[0-9a-f]{64}")))
                assertTrue(f.name, f.bytes > 0)
            }
            assertTrue("${m.id}: weights are the larger file", m.bin.bytes > m.param.bytes)
        }
    }

    @Test
    fun cleanupStepsKeepTheSizeAndUpscalersEnlarge() {
        for (m in ModelCatalog.all) {
            if (m.step == AiStep.UPSCALE) assertTrue(m.id, m.scale in 2..4) else assertEquals(m.id, 1, m.scale)
        }
    }

    @Test
    fun theDefaultUpscalerIsInTheCatalog() {
        // waifu2x UpConv7 2x: the fastest upscaler that works well.
        assertEquals("waifu2x-upconv7-2x", ModelCatalog.DEFAULT_UPSCALE)
        assertEquals(AiStep.UPSCALE, ModelCatalog.byId(ModelCatalog.DEFAULT_UPSCALE)!!.step)
        assertNull(ModelCatalog.byId("none"))
        assertNull(ModelCatalog.byId(null))
        assertNotNull(ModelCatalog.byId(ModelCatalog.all.last().id))
    }

    @Test
    fun everySourceIsFreeToUseAndShipsItsLicense() {
        val free = setOf("CC BY 4.0", "MIT License", "BSD 3-Clause License")
        for (s in ModelSource.entries) {
            assertTrue(s.license, s.license in free)
            assertTrue(s.url, s.url.startsWith("https://"))
            for (t in s.texts) assertTrue("${s.name}: missing $t", File(assetDir, t).isFile)
            val text = s.texts.joinToString { File(assetDir, it).readText() }
            if (s.license == "CC BY 4.0") assertTrue(s.name, text.contains("Attribution 4.0 International"))
            else assertTrue("${s.name} lacks a copyright line", text.contains("Copyright"))
        }
    }

    @Test
    fun everySourceHasModels() {
        for (s in ModelSource.entries) assertTrue(s.name, s.models.isNotEmpty())
    }

    @Test
    fun theOpenComicNoticeNamesItsAuthorAndLicense() {
        val text = File(assetDir, "opencomic-ai-models.txt").readText()
        assertTrue(text.contains("Oleguer Llopart Mora"))
        assertTrue(text.contains("https://creativecommons.org/licenses/by/4.0/"))
    }

    @Test
    fun theReadmeListsEveryModelSource() {
        val readme = File("../README.md").readText()
        for (s in ModelSource.entries) assertTrue("${s.title} not in README", readme.contains("| [${s.title}](${s.url}) |"))
    }

    @Test
    fun everyModelTakesAPageWithinTheBudget() {
        for (m in ModelCatalog.all) assertTrue("${m.id}: ${m.pageMs} ms", m.pageMs in 1..ModelCatalog.MAX_PAGE_MS)
    }

    @Test
    fun ncnnConversionsKeepTheirNotices() {
        for (t in listOf("real-esrgan.txt", "waifu2x.txt")) {
            val text = File(assetDir, t).readText()
            assertTrue(t, text.contains("The MIT License") && text.contains("Copyright (c) 2019 nihui"))
        }
    }
}
