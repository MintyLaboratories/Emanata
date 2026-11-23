package com.infinity.comicreader.ui

import com.infinity.comicreader.ai.AiModel
import com.infinity.comicreader.ai.AiStep
import com.infinity.comicreader.ai.ModelRatings
import com.infinity.comicreader.ai.Models
import com.infinity.comicreader.ai.RatingCategory
import com.infinity.comicreader.ai.UserModel

/**
 * A row of an AI step's picker ([ModelPicker]): none ([models] empty), one model, or a
 * family's levels behind one slider, lightest or weakest first, named [levelNames]. [name] is
 * short: without what the step's name already says. [sizes]: the levels are sizes of one
 * network, so the family's ratings are those of its largest level, which it offers "up to";
 * else each level is a strength with ratings of its own.
 */
data class PickerRow(val name: String, val models: List<AiModel>, val levelNames: List<String> = emptyList(), val sizes: Boolean = false) {
    /** Names the row across rebuilt pickers: its first model's id; empty for none. */
    val key: String get() = models.firstOrNull()?.id.orEmpty()

    /** True for a family: its levels behind one slider. */
    val family: Boolean get() = models.size > 1

    /** The level of model [id] (0 for a single model); -1 when it is not this row's. */
    fun indexOf(id: String?): Int = models.indexOfFirst { it.id == id }

    /** The level a family shows until one of its levels is picked: the largest, which its ratings describe. */
    val firstLevel: Int get() = models.lastIndex

    /** The model whose ratings the row shows at [level]: the largest of sizes, else the level's own. */
    fun rated(level: Int): AiModel = if (sizes) models.last() else models[level]

    /** The by-eye ratings shown at [level] ([ModelRatings.of]); empty when never rated. */
    fun ratings(level: Int): Map<RatingCategory, Int> = ModelRatings.of(rated(level))

    /**
     * Speed in half stars at [level], from its own page time ([ModelRatings.speed]); none for an
     * imported model, whose time was measured on this device, not on the reference tablet the
     * stars are scaled to.
     */
    fun speed(level: Int, fastestMs: Long = ModelRatings.fastestMs()): Int? =
        models[level].takeIf { it !is UserModel }?.let { ModelRatings.speed(it.pageMs, fastestMs) }
}

/** The rows of AI steps' pickers: short names, and a family's levels on one row. */
object PickerRows {
    /** Short names of the catalog's single models, without the step and the version picked for the app. */
    private val SHORT: Map<String, String> = buildMap {
        put("opencomic-ai-artifact-removal-compact-v2", "OpenComic")
        put("opencomic-ai-descreen-hard-compact-v2", "OpenComic v2")
        put("opencomic-ai-descreen-hard-fast-v3-256", "OpenComic v3")
        put("waifu2x-upconv7-2x", "waifu2x 2×")
        for (n in 0..3) put("waifu2x-upconv7-2x-denoise$n", "waifu2x denoise $n 2×")
        for (x in 2..4) put("opencomic-ai-upscale-${x}x-compact-v2", "OpenComic $x×")
        for (x in 2..4) put("realesr-animevideov3-x$x", "Real-ESRGAN $x×")
        put("4x-lsdir-compact-c3", "LSDIR 4×")
    }

    /** Models in strengths of one kind, picked on one row: [ids], weakest first, named [names]. */
    private class Strengths(val id: String, val name: String, val ids: List<String>, val names: List<String>)

    private val STRENGTHS = listOf(
        Strengths("waifu2x-denoise", "waifu2x denoise 2×", (0..3).map { "waifu2x-upconv7-2x-denoise$it" }, (0..3).map { "$it" })
    )

    private fun strengthsOf(m: AiModel): Strengths? = STRENGTHS.firstOrNull { m.id in it.ids }

    /** [m]'s short name: the catalog's, else its family's with its level, else its own (an imported model's, as the user named it). */
    fun shortName(m: AiModel): String = SHORT[m.id] ?: m.level?.let { "${it.familyName}, ${it.name}" } ?: m.name

    /** No model: [name]. */
    fun none(name: String) = PickerRow(name, emptyList())

    /**
     * [step]'s rows: none (named [noneName]), then the models it offers ([Models.offered]) in
     * order, each family on one row where its first offered level is.
     */
    fun of(step: AiStep, keepSimple: Boolean, current: String?, noneName: String): List<PickerRow> {
        val groups = LinkedHashMap<String, MutableList<AiModel>>()
        for (m in Models.offered(step, keepSimple, current)) {
            val key = m.level?.let { "size:${it.family}" } ?: strengthsOf(m)?.let { "strength:${it.id}" } ?: "model:${m.id}"
            groups.getOrPut(key) { ArrayList() } += m
        }
        return listOf(none(noneName)) + groups.values.map(::row)
    }

    private fun row(models: List<AiModel>): PickerRow {
        val m = models[0]
        if (models.size == 1) return PickerRow(shortName(m), models)
        m.level?.let { l ->
            val sorted = models.sortedBy { it.level!!.index }
            return PickerRow(l.familyName, sorted, sorted.map { it.level!!.name }, sizes = true)
        }
        val s = strengthsOf(m)!!
        val sorted = models.sortedBy { s.ids.indexOf(it.id) }
        return PickerRow(s.name, sorted, sorted.map { s.names[s.ids.indexOf(it.id)] })
    }
}
