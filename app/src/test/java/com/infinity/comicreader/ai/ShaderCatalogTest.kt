package com.infinity.comicreader.ai

import com.infinity.comicreader.util.Notices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The shader chains offered: Anime4K at its pinned release, five families in five levels, under its MIT notice. */
class ShaderCatalogTest {

    @Test
    fun everyFamilyComesInEveryLevel() {
        assertEquals(listOf("S", "M", "L", "VL", "UL"), ShaderCatalog.LEVELS)
        val families = ShaderCatalog.all.map { it.level.family }.distinct()
        assertEquals(listOf("anime4k-a", "anime4k-b", "anime4k-c", "anime4k-upscale", "anime4k-upscale-denoise"), families)
        for (f in families) {
            val levels = ShaderCatalog.family(f)
            assertEquals(f, (0..4).toList(), levels.map { it.level.index })
            assertEquals(f, levels.map { "$f-${it.level.name.lowercase()}" }, levels.map { it.id })
            assertTrue(f, levels.all { it.name == "${it.level.familyName}, ${it.level.name}" && it.level.names == ShaderCatalog.LEVELS })
            assertEquals("the AiModel view", levels.map { it.level }, levels.map { (it as AiModel).level })
        }
        assertTrue("ncnn models come in one size", ModelCatalog.all.all { it.level == null })
        assertEquals(
            listOf(ModelBlurb.ANIME4K_A, ModelBlurb.ANIME4K_B, ModelBlurb.ANIME4K_C, ModelBlurb.ANIME4K_UPSCALE, ModelBlurb.ANIME4K_UPSCALE_DENOISE),
            families.map { f -> ShaderCatalog.family(f).map { it.blurb }.distinct().single() }
        )
    }

    @Test
    fun chainsHoldTheFilesOfTheirMode() {
        fun names(id: String) = ShaderCatalog.byId(id)!!.files.map { it.name }
        assertEquals(listOf("Anime4K_Clamp_Highlights.glsl", "Anime4K_Restore_CNN_UL.glsl", "Anime4K_Upscale_CNN_x2_UL.glsl"), names("anime4k-a-ul"))
        assertEquals(listOf("Anime4K_Clamp_Highlights.glsl", "Anime4K_Restore_CNN_Soft_M.glsl", "Anime4K_Upscale_CNN_x2_M.glsl"), names("anime4k-b-m"))
        assertEquals(listOf("Anime4K_Clamp_Highlights.glsl", "Anime4K_Upscale_Denoise_CNN_x2_S.glsl"), names("anime4k-c-s"))
        assertEquals(listOf("Anime4K_Upscale_CNN_x2_VL.glsl"), names("anime4k-upscale-vl"))
        assertEquals(listOf("Anime4K_Upscale_Denoise_CNN_x2_L.glsl"), names("anime4k-upscale-denoise-l"))
    }

    @Test
    fun filesComeUnchangedFromThePinnedRelease() {
        assertTrue(ShaderCatalog.COMMIT.matches(Regex("[0-9a-f]{40}")))
        assertEquals("https://raw.githubusercontent.com/bloc97/Anime4K/${ShaderCatalog.COMMIT}/glsl/", ShaderCatalog.BASE)
        val byName = HashMap<String, ModelFile>()
        for (m in ShaderCatalog.all) {
            assertEquals(m.id, AiStep.UPSCALE, m.step)
            assertEquals(m.id, 2, m.scale)
            assertEquals(ModelSource.ANIME4K, m.source)
            assertEquals(m.files.sumOf { it.bytes }, m.bytes)
            for (f in m.files) {
                assertTrue(f.url, f.url.startsWith(ShaderCatalog.BASE) && f.url.endsWith("/" + f.name) && f.name.endsWith(".glsl"))
                assertTrue(f.sha256, f.sha256.matches(Regex("[0-9a-f]{64}")))
                assertTrue(f.name, f.bytes > 1000)
                // A file shared by chains is the same file everywhere.
                byName.put(f.name, f)?.let { assertEquals(f.name, it, f) }
            }
            assertEquals(m.files.map { it.name }.size, m.files.map { it.name }.toSet().size)
        }
        assertEquals("one clamp, twenty networks", 21, byName.size)
        assertEquals(byName.size, byName.values.map { it.sha256 }.toSet().size)
    }

    @Test
    fun aChainsFingerprintChangesWithAnyOfItsFilesAndTheRunner() {
        val a = ShaderCatalog.byId("anime4k-a-ul")!!
        val b = ShaderCatalog.byId("anime4k-b-ul")!!
        assertTrue(a.fingerprint != b.fingerprint)
        val parts = a.fingerprint.split(':')
        assertEquals("r${ShaderSource.RUNNER}", parts.first())
        assertEquals(a.files.map { it.sha256.take(16) }, parts.drop(1))
    }

    @Test
    fun idsDoNotClashWithTheCatalogsOrTheStores() {
        val ids = ShaderCatalog.all.map { it.id }
        assertEquals(25, ids.size)
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.none { ModelCatalog.byId(it) != null })
        for (m in ShaderCatalog.all) assertEquals(m, Models.byId(m.id))
        for (id in ids) assertTrue(id, id.matches(Regex("[a-z0-9][a-z0-9._-]*")))
        assertTrue(ShaderCatalog.all.all { it in ModelCatalog.sets })
        assertNull(ShaderCatalog.byId(null))
    }

    @Test
    fun pickersOfferEveryLevel() {
        assertEquals(25, ShaderCatalog.forStep(AiStep.UPSCALE).size)
        assertTrue(ShaderCatalog.forStep(AiStep.ARTIFACTS).isEmpty())
        for (simple in listOf(true, false)) {
            // Beside the ncnn upscalers, before the slow ones.
            val offered = Models.offered(AiStep.UPSCALE, keepSimple = simple, current = null)
            assertEquals(ModelCatalog.DEFAULT_UPSCALE, offered.first().id)
            val slow = offered.map { it.slow }
            assertEquals(slow.sorted(), slow)
            assertTrue("kept simple: $simple", offered.containsAll(ShaderCatalog.all))
        }
    }

    @Test
    fun everyChainTakesAPageWithinTheBudget() {
        // Warm batch figures for now (ShaderCatalog's page times), so upper bounds of the cool ones.
        for (m in ShaderCatalog.all) assertTrue("${m.id}: ${m.pageMs} ms", m.pageMs in 1..ModelCatalog.MAX_PAGE_MS)
        for (f in ShaderCatalog.all.map { it.level.family }.distinct()) {
            val times = ShaderCatalog.family(f).map { it.pageMs }
            assertEquals("$f: heavier levels take longer", times.sorted(), times)
        }
        assertTrue(ShaderCatalog.all.none { it.slow })
    }

    @Test
    fun anime4ksNoticeIsShippedListedAndInTheReadme() {
        val s = ModelSource.ANIME4K
        assertEquals("MIT License", s.license)
        assertEquals(ShaderCatalog.REPOSITORY, s.repository)
        val text = File("src/main/assets/licenses/anime4k.txt").readText()
        assertTrue(text.startsWith("MIT License") && text.contains("Copyright (c) 2019 bloc97"))
        val notice = Notices.models.single { it.name == "Anime4K" }
        assertTrue(notice.note!!, notice.note!!.contains("Anime4K A (sharp) 2× (S/M/L/VL/UL)"))
        assertTrue(notice.note!!.contains("from ${ShaderCatalog.REPOSITORY} and run on this device only"))
        assertTrue(Notices.models.single { it.name == "waifu2x" }.note!!.contains("from ${ModelCatalog.REPOSITORY}"))
        assertTrue(File("../README.md").readText().contains("| [Anime4K](https://github.com/bloc97/Anime4K) | bloc97 | MIT |"))
    }
}
