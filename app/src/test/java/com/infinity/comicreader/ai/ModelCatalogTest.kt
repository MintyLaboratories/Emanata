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
        assertEquals("${ModelCatalog.HOSTED_REPOSITORY}/resolve/${ModelCatalog.HOSTED_REVISION}/", ModelCatalog.HOSTED_BASE)
        assertTrue(ModelCatalog.HOSTED_REPOSITORY.startsWith("https://huggingface.co/"))
        assertTrue(ModelCatalog.HOSTED_REVISION.matches(Regex("[0-9a-f]{40}")))
        for (m in ModelCatalog.all) {
            assertTrue(m.param.name, m.param.name.endsWith(".param"))
            assertTrue(m.bin.name, m.bin.name.endsWith(".bin"))
            assertEquals(m.param.url.removeSuffix(".param"), m.bin.url.removeSuffix(".bin"))
            val base = if (m.source.repository == ModelCatalog.HOSTED_REPOSITORY) ModelCatalog.HOSTED_BASE else ModelCatalog.BASE
            for (f in m.files) {
                assertTrue(f.url, f.url.startsWith(base) && f.url.endsWith("/" + f.name))
                assertTrue(f.sha256, f.sha256.matches(Regex("[0-9a-f]{64}")))
                assertTrue(f.name, f.bytes > 0)
            }
            assertTrue("${m.id}: weights are the larger file", m.bin.bytes > m.param.bytes)
        }
    }

    @Test
    fun theTextDetectorIsPinnedToARevisionWithChecksums() {
        val d = ModelCatalog.TEXT_DETECTOR
        assertTrue(ModelCatalog.DETECTOR_REVISION.matches(Regex("[0-9a-f]{40}")))
        assertTrue(ModelCatalog.DETECTOR_REPOSITORY.startsWith("https://huggingface.co/"))
        for (f in d.files) {
            assertTrue(f.url, f.url.startsWith("${ModelCatalog.DETECTOR_REPOSITORY}/resolve/${ModelCatalog.DETECTOR_REVISION}/") && f.url.endsWith("/" + f.name))
            assertTrue(f.sha256, f.sha256.matches(Regex("[0-9a-f]{64}")))
        }
        assertEquals(listOf(d.param, d.bin), d.files)
        assertTrue(d.bin.bytes > d.param.bytes)
        assertEquals(d.param.bytes + d.bin.bytes, d.bytes)
    }

    @Test
    fun theStoreKeepsEverySetAndNoIdsClash() {
        val ids = ModelCatalog.sets.map { it.id }
        assertEquals(ModelCatalog.all.size + ShaderCatalog.all.size + ModelCatalog.tools.size, ids.size)
        assertEquals(ids.size, ids.toSet().size)
        for (id in ids) assertTrue(id, id.matches(Regex("[a-z0-9][a-z0-9._-]*")) && ".." !in id)
        for (reserved in listOf(ModelStore.USER, ModelStore.STAGING, ModelImports.CHECKING)) assertTrue(reserved, reserved !in ids)
        assertTrue(ModelCatalog.TEXT_DETECTOR in ModelCatalog.sets)
        assertTrue(ModelCatalog.all.none { it.id == ModelCatalog.TEXT_DETECTOR.id })
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
        val free = setOf("CC BY 4.0", "MIT License", "BSD 3-Clause License", "LGPL 3.0 or later")
        for (s in ModelSource.entries) {
            assertTrue(s.license, s.license in free)
            assertTrue(s.url, s.url.startsWith("https://"))
            for (t in s.texts) assertTrue("${s.name}: missing $t", File(assetDir, t).isFile)
            val text = s.texts.joinToString { File(assetDir, it).readText() }
            if (s.license == "CC BY 4.0") assertTrue(s.name, text.contains("Attribution 4.0 International"))
            else assertTrue("${s.name} lacks a copyright line", text.contains("Copyright"))
            if (s.license == "LGPL 3.0 or later") {
                // The LGPL is the GPL plus permissions: both texts ship.
                assertTrue(s.name, text.contains("GNU LESSER GENERAL PUBLIC LICENSE") && text.contains("GNU GENERAL PUBLIC LICENSE\n"))
            }
        }
    }

    @Test
    fun sourcesDownloadFromAPinnedRepository() {
        for (s in ModelSource.entries) {
            assertTrue(s.name, s.repository in setOf(ModelCatalog.REPOSITORY, ModelCatalog.HOSTED_REPOSITORY))
            // Conversions made for the app come from the repository that also holds their sources.
            assertEquals(s.name, s.repository == ModelCatalog.HOSTED_REPOSITORY, s.conversion != null)
        }
        assertEquals(listOf(ModelSource.CUNNY, ModelSource.ARTCNN), ModelSource.entries.filter { it.conversion != null })
    }

    @Test
    fun theCuNNyNoticeNamesItsLicenseUpstreamAndSource() {
        val text = File(assetDir, "cunny.txt").readText()
        assertTrue(text.contains("Copyright (c) 2024 funnyplanter"))
        assertTrue(text.contains(ModelSource.CUNNY.url))
        assertTrue(text.contains("GNU Lesser General Public License") && text.contains("version 3 of the License, or (at your option) any later version"))
        assertTrue(text.contains("${ModelCatalog.HOSTED_REPOSITORY} at revision ${ModelCatalog.HOSTED_REVISION}"))
        assertTrue(text.contains("Corresponding Source") && text.contains("CuNNy-8x32-DS.glsl") && text.contains("cn-conversion-script/"))
        assertTrue(ModelSource.CUNNY.conversion!!.contains("conversion scripts"))
    }

    @Test
    fun theArtCnnNoticeNamesItsAuthorAsUpstreamDoes() {
        val text = File(assetDir, "artcnn.txt").readText()
        assertTrue(text.startsWith("MIT License") && text.contains("Copyright (c) 2024 João Chrisóstomo"))
        assertTrue(ModelSource.ARTCNN.author.startsWith("João Chrisóstomo"))
    }

    @Test
    fun theNewUpscalersAreTwoTimesAndFasterThanTheDefault() {
        val default = ModelCatalog.byId(ModelCatalog.DEFAULT_UPSCALE)!!
        for (id in listOf("cunny-8x32-ds-rp", "artcnn-c4f32-ds")) {
            val m = ModelCatalog.byId(id)!!
            assertEquals(AiStep.UPSCALE, m.step)
            assertEquals(2, m.scale)
            assertTrue(id, m.pageMs < default.pageMs)
            assertTrue(m.files.all { it.url.startsWith(ModelCatalog.HOSTED_BASE + id + "/") })
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

    @Test
    fun upscalersListTheDefaultFirstAndTheSlowLast() {
        val ids = ModelCatalog.forStep(AiStep.UPSCALE).map { it.id }
        assertEquals(ModelCatalog.DEFAULT_UPSCALE, ids.first())
        assertEquals(
            listOf(
                "waifu2x-upconv7-2x", "waifu2x-upconv7-2x-denoise0", "waifu2x-upconv7-2x-denoise1", "waifu2x-upconv7-2x-denoise2",
                "waifu2x-upconv7-2x-denoise3", "opencomic-ai-upscale-2x-compact-v2", "opencomic-ai-upscale-3x-compact-v2",
                "opencomic-ai-upscale-4x-compact-v2", "artcnn-c4f32-ds", "cunny-8x32-ds-rp", "realesr-animevideov3-x2", "realesr-animevideov3-x3", "realesr-animevideov3-x4",
                "4x-lsdir-compact-c3"
            ),
            ids
        )
        for (step in AiStep.entries) {
            val slow = ModelCatalog.forStep(step).map { it.slow }
            assertEquals("${step.key}: slow ones last", slow.sorted(), slow)
        }
    }

    @Test
    fun modelsOfTenSecondsAPageOrMoreAreSlow() {
        val slow = ModelCatalog.all.filter { it.slow }.map { it.id }.toSet()
        assertEquals(
            setOf("opencomic-ai-descreen-hard-fast-v3-256", "realesr-animevideov3-x2", "realesr-animevideov3-x3", "realesr-animevideov3-x4", "4x-lsdir-compact-c3"),
            slow
        )
        for (m in ModelCatalog.all) assertEquals(m.id, m.pageMs >= 10_000, m.slow)
        assertEquals(11L, ModelCatalog.byId("realesr-animevideov3-x2")!!.pageSeconds)
        assertEquals(15L, ModelCatalog.byId("opencomic-ai-descreen-hard-fast-v3-256")!!.pageSeconds)
        assertEquals(14L, ModelCatalog.byId("4x-lsdir-compact-c3")!!.pageSeconds)
    }

    @Test
    fun eachModelSaysWhatItDoes() {
        fun blurbOf(id: String) = ModelCatalog.byId(id)!!.blurb
        assertEquals(ModelBlurb.WAIFU2X, blurbOf("waifu2x-upconv7-2x"))
        for (n in 0..3) assertEquals(ModelBlurb.WAIFU2X_DENOISE, blurbOf("waifu2x-upconv7-2x-denoise$n"))
        for (x in 2..4) assertEquals(ModelBlurb.OPENCOMIC_UPSCALE, blurbOf("opencomic-ai-upscale-${x}x-compact-v2"))
        for (x in 2..4) assertEquals(ModelBlurb.REAL_ESRGAN_ANIME, blurbOf("realesr-animevideov3-x$x"))
        assertEquals(ModelBlurb.DESCREEN_V2, blurbOf("opencomic-ai-descreen-hard-compact-v2"))
        assertEquals(ModelBlurb.DESCREEN_V3, blurbOf("opencomic-ai-descreen-hard-fast-v3-256"))
        assertEquals(ModelBlurb.ARTIFACT_REMOVAL, blurbOf("opencomic-ai-artifact-removal-compact-v2"))
        assertEquals(ModelBlurb.CUNNY, blurbOf("cunny-8x32-ds-rp"))
        assertEquals(ModelBlurb.ARTCNN, blurbOf("artcnn-c4f32-ds"))
        // No description was given for it: it shows as slow alone.
        assertNull(blurbOf("4x-lsdir-compact-c3"))
    }

    @Test
    fun keepItSimpleOffersTheSlowModelsOnlyWhenChosen() {
        val all = ModelCatalog.forStep(AiStep.UPSCALE)
        assertEquals(all, ModelCatalog.offered(AiStep.UPSCALE, keepSimple = false, current = null))
        val simple = ModelCatalog.offered(AiStep.UPSCALE, keepSimple = true, current = ModelCatalog.DEFAULT_UPSCALE)
        assertEquals(all.filter { !it.slow }, simple)
        assertTrue(simple.size in 1 until all.size)
        val chosen = ModelCatalog.offered(AiStep.UPSCALE, keepSimple = true, current = "realesr-animevideov3-x3")
        assertEquals(all.filter { !it.slow || it.id == "realesr-animevideov3-x3" }, chosen)
        // Both descreens stay in the catalog; the slow v3 waits for advanced settings.
        assertEquals(listOf("opencomic-ai-descreen-hard-compact-v2"), ModelCatalog.offered(AiStep.DESCREEN, true, null).map { it.id })
        assertEquals(2, ModelCatalog.offered(AiStep.DESCREEN, false, null).size)
    }
}
