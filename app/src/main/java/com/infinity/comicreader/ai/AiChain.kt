package com.infinity.comicreader.ai

import com.infinity.comicreader.data.Prefs

/**
 * A model per step by id ([Models]), a step missing or null running none: the global choice
 * ([of]) or a book's own ([com.infinity.comicreader.data.Library.aiPicks]).
 */
data class AiPicks(val ids: Map<AiStep, String?> = emptyMap()) {
    /** [step]'s model id; null for none. */
    fun id(step: AiStep): String? = ids[step]

    /** The models picked, in step order; an id no model has (a model dropped or deleted), or of another step, runs none. */
    val models: List<AiModel>
        get() = AiStep.entries.mapNotNull { step -> Models.byId(ids[step])?.takeIf { it.step == step } }

    /** These picks with [step] running [id] (null: none). */
    fun with(step: AiStep, id: String?): AiPicks = AiPicks(ids + (step to id))

    companion object {
        /** No model at any step: a book read without AI. */
        val NONE = AiPicks(AiStep.entries.associateWith { null })

        /** The models chosen per step in Settings, whether AI processing is on or not. */
        fun of(prefs: Prefs): AiPicks = AiPicks(AiStep.entries.associateWith { prefs.aiModel(it) })
    }
}

/** The models pages go through, in [AiStep] order: those chosen in Settings while AI processing is on, or a book's own. */
object AiChain {
    /**
     * Version of how a chain renders a page ([AiPages.chain]); part of every processed page's
     * key ([signature]), so a change to it never shows pages processed the old way.
     */
    const val ENGINE = 1

    /** [prefs]' chosen models, one per step at most; empty while AI processing is off. */
    fun chosen(prefs: Prefs): List<AiModel> =
        if (!prefs.aiEnabled) emptyList() else AiPicks.of(prefs).models

    /** The models a book's pages go through: its [own] picks when it has them, which win, else the global [chosen]. */
    fun forBook(prefs: Prefs, own: AiPicks?): List<AiModel> = own?.models ?: chosen(prefs)

    /** The picks a book starts from when given its own: the global ones while AI processing is on, else none. */
    fun startingPicks(prefs: Prefs): AiPicks = if (prefs.aiEnabled) AiPicks.of(prefs) else AiPicks.NONE

    /** The [chosen] models [store] holds, with their files; one not downloaded yet is left out. */
    fun ready(prefs: Prefs, store: ModelStore): List<Pair<AiModel, ModelFiles>> =
        chosen(prefs).mapNotNull { m -> store.files(m)?.let { m to it } }

    /**
     * What identifies [models]' output: the [ENGINE], then each model's id and its files'
     * checksums ([AiModel.fingerprint]), so a model replaced never shows pages its old files made.
     */
    fun signature(models: List<AiModel>): String =
        "e$ENGINE;" + models.joinToString(";") { "${it.id}:${it.fingerprint}" }

    /** [models]' time together for a page ([AiModel.pageMs]). */
    fun pageMs(models: List<AiModel>): Long = models.sumOf { it.pageMs }

    /**
     * True when [models] together take longer than [ModelCatalog.MAX_PAGE_MS] on the reference
     * tablet: each page takes long and costs battery, and a slower device may run past even the
     * longer time the reader gives such a chain ([ReaderAi.limitsFor]) and show it without AI.
     */
    fun slow(models: List<AiModel>): Boolean = pageMs(models) > ModelCatalog.MAX_PAGE_MS
}
