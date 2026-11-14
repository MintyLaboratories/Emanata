package com.infinity.comicreader.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Model stars: the by-eye ratings as given, none made up, and speed from page times. */
class ModelRatingsTest {

    private fun of(id: String) = ModelRatings.of(Models.byId(id)!!)

    @Test
    fun ratingsAreTheTablesInHalfStars() {
        assertEquals(
            mapOf(RatingCategory.CRISP to 1, RatingCategory.COLOR to 10, RatingCategory.DETAIL to 10, RatingCategory.DESHARP to 4, RatingCategory.LINES to 2),
            of("waifu2x-upconv7-2x")
        )
        assertEquals(listOf(4, 10, 4, 6, 8), of("waifu2x-upconv7-2x-denoise3").values.toList())
        assertEquals(listOf(8, 8, 5, 4, 5), of("4x-lsdir-compact-c3").values.toList())
        assertEquals(listOf(10, 6, 2, 10, 10), of("realesr-animevideov3-x4").values.toList())
        assertEquals(of("opencomic-ai-upscale-2x-compact-v2"), of("opencomic-ai-upscale-4x-compact-v2"))
        // Makes it worse.
        assertEquals(ModelRatings.WORSE, of("anime4k-a-ul")[RatingCategory.DESHARP])
        assertEquals(listOf(2, 10, 7, 3, 5), of("anime4k-upscale-denoise-ul").values.toList())
    }

    @Test
    fun modelsNeverRatedHaveNoStars() {
        for (id in listOf("cunny-8x32-ds-rp", "artcnn-c4f32-ds", "waifu2x-upconv7-2x-denoise1", "anime4k-a-vl", "anime4k-c-s")) {
            assertTrue(id, of(id).isEmpty())
        }
        for (step in listOf(AiStep.ARTIFACTS, AiStep.DESCREEN)) for (m in Models.forStep(step)) assertTrue(m.id, ModelRatings.of(m).isEmpty())
        // Every rating is half stars within five, or worse.
        for (m in Models.all()) for (h in ModelRatings.of(m).values) assertTrue(m.id, h == ModelRatings.WORSE || h in 1..ModelRatings.MAX)
    }

    @Test
    fun speedIsFiveStarsForTheFastestAndHalfAStarAtTheCatalogsLongest() {
        val fastest = ModelRatings.fastestMs()
        assertEquals((ModelCatalog.all + ShaderCatalog.all).minOf { it.pageMs }, fastest)
        assertEquals(10, ModelRatings.speed(fastest))
        assertEquals(1, ModelRatings.speed(ModelCatalog.MAX_PAGE_MS))
        assertEquals(1, ModelRatings.speed(ModelCatalog.MAX_PAGE_MS * 3))
        assertNull(ModelRatings.speed(0))
        assertNull(ModelRatings.speed(1000, fastestMs = 0))
        // Slower never rates faster.
        val times = (ModelCatalog.all + ShaderCatalog.all).map { it.pageMs }.sorted()
        val stars = times.map { ModelRatings.speed(it, fastest)!! }
        assertEquals(stars.sortedDescending(), stars)
        // A log scale: 100 ms to 20 s is a factor of 200, nine half stars; 100 to 1414 ms, half of it.
        assertEquals(10, ModelRatings.speed(100, fastestMs = 100))
        assertEquals(6, ModelRatings.speed(1_414, fastestMs = 100))
        assertEquals(1, ModelRatings.speed(20_000, fastestMs = 100))
    }
}
