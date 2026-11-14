package com.infinity.comicreader.ai

import kotlin.math.ln
import kotlin.math.roundToInt

/** A quality upscalers were rated on by eye ([ModelRatings.of]). */
enum class RatingCategory {
    /** Makes blurry or artifacted art crisp. */
    CRISP,

    /** Keeps the colors of the original. */
    COLOR,

    /** Keeps the detail and texture of the original. */
    DETAIL,

    /** Cleans up sharpening artifacts. */
    DESHARP,

    /** Clarifies lines and fixes aliasing. */
    LINES
}

/**
 * Stars for models, counted in half stars from 1 to [MAX], or [WORSE]: the by-eye ratings of
 * upscalers compared on the same pages ([of]; models never rated have none), and a speed
 * rating from page times ([speed]).
 */
object ModelRatings {
    /** Five stars, in halves. */
    const val MAX = 10

    /** A rating saying the model makes the page worse at it. */
    const val WORSE = -1

    private fun stars(crisp: Int, color: Int, detail: Int, desharp: Int, lines: Int): Map<RatingCategory, Int> = mapOf(
        RatingCategory.CRISP to crisp,
        RatingCategory.COLOR to color,
        RatingCategory.DETAIL to detail,
        RatingCategory.DESHARP to desharp,
        RatingCategory.LINES to lines
    )

    /** Ratings by model id. OpenComic and Real-ESRGAN were rated as models, at every scale; Anime4K at UL only. */
    private val RATED: Map<String, Map<RatingCategory, Int>> = buildMap {
        put("waifu2x-upconv7-2x", stars(1, 10, 10, 4, 2))
        put("waifu2x-upconv7-2x-denoise0", stars(2, 10, 8, 4, 2))
        put("waifu2x-upconv7-2x-denoise3", stars(4, 10, 4, 6, 8))
        for (x in 2..4) put("opencomic-ai-upscale-${x}x-compact-v2", stars(6, 10, 8, 8, 5))
        put("4x-lsdir-compact-c3", stars(8, 8, 5, 4, 5))
        for (x in 2..4) put("realesr-animevideov3-x$x", stars(10, 6, 2, 10, 10))
        put("anime4k-a-ul", stars(3, 10, 6, WORSE, 6))
        put("anime4k-b-ul", stars(3, 10, 6, 4, 7))
        put("anime4k-c-ul", stars(3, 10, 7, 3, 5))
        put("anime4k-upscale-ul", stars(3, 10, 7, WORSE, 4))
        put("anime4k-upscale-denoise-ul", stars(2, 10, 7, 3, 5))
    }

    /** [m]'s ratings, in [RatingCategory] order; empty when it was never rated. */
    fun of(m: AiModel): Map<RatingCategory, Int> = RATED[m.id].orEmpty()

    /** The shortest page time of the catalog's models and shader chains; 0 when none is known. */
    fun fastestMs(): Long = (ModelCatalog.all + ShaderCatalog.all).map { it.pageMs }.filter { it > 0 }.minOrNull() ?: 0

    /**
     * Speed in half stars for page time [pageMs], relative to the fastest model's [fastestMs]:
     * [MAX] for the fastest, one half star at [ModelCatalog.MAX_PAGE_MS] (the catalog's longest)
     * and beyond, in between on a log scale, so each half star stands for the same factor of
     * time; null when either time is unknown.
     */
    fun speed(pageMs: Long, fastestMs: Long = fastestMs()): Int? {
        if (pageMs <= 0 || fastestMs <= 0) return null
        val top = fastestMs.toDouble()
        val bottom = maxOf(ModelCatalog.MAX_PAGE_MS.toDouble(), top * 2)
        val t = pageMs.toDouble().coerceIn(top, bottom)
        return (MAX - (MAX - 1) * ln(t / top) / ln(bottom / top)).roundToInt().coerceIn(1, MAX)
    }
}
