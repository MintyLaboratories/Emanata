package com.infinity.comicreader.ai

import com.infinity.comicreader.data.Prefs

/**
 * A model per step by catalog id, a step missing or null running none: the global choice
 * ([of]) or a book's own ([com.infinity.comicreader.data.Library.aiPicks]).
 */
data class AiPicks(val ids: Map<AiStep, String?> = emptyMap()) {
    /** [step]'s catalog id; null for none. */
    fun id(step: AiStep): String? = ids[step]

    /** The catalog models picked, in step order; an id the catalog lacks, or of another step, runs none. */
    val models: List<CatalogModel>
        get() = AiStep.entries.mapNotNull { step -> ModelCatalog.byId(ids[step])?.takeIf { it.step == step } }

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

    /** [prefs]' chosen catalog models, one per step at most; empty while AI processing is off. */
    fun chosen(prefs: Prefs): List<CatalogModel> =
        if (!prefs.aiEnabled) emptyList() else AiPicks.of(prefs).models

    /** The models a book's pages go through: its [own] picks when it has them, which win, else the global [chosen]. */
    fun forBook(prefs: Prefs, own: AiPicks?): List<CatalogModel> = own?.models ?: chosen(prefs)

    /** The picks a book starts from when given its own: the global ones while AI processing is on, else none. */
    fun startingPicks(prefs: Prefs): AiPicks = if (prefs.aiEnabled) AiPicks.of(prefs) else AiPicks.NONE

    /** The [chosen] models [store] holds, with their files; one not downloaded yet is left out. */
    fun ready(prefs: Prefs, store: ModelStore): List<Pair<CatalogModel, ModelFiles>> =
        chosen(prefs).mapNotNull { m -> store.files(m)?.let { m to it } }

    /**
     * What identifies [models]' output: the [ENGINE], then each model's id and its files'
     * checksums, so a model replaced in the catalog never shows pages its old files made.
     */
    fun signature(models: List<CatalogModel>): String =
        "e$ENGINE;" + models.joinToString(";") { "${it.id}:${it.param.sha256.take(16)}:${it.bin.sha256.take(16)}" }

    /** [models]' time together for a page on the reference tablet ([CatalogModel.pageMs]). */
    fun pageMs(models: List<CatalogModel>): Long = models.sumOf { it.pageMs }

    /**
     * True when [models] together take longer than [ModelCatalog.MAX_PAGE_MS] on the reference
     * tablet: warmer or slower devices would run past a page's time and show it without AI.
     */
    fun slow(models: List<CatalogModel>): Boolean = pageMs(models) > ModelCatalog.MAX_PAGE_MS
}
