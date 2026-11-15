package com.infinity.comicreader.ai

import com.infinity.comicreader.data.Prefs

/**
 * A model per step by id ([Models]), a step missing or null running none: a global default
 * chain ([of]) or a book's own ([com.infinity.comicreader.data.Library.aiPicks]).
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

        /** The models chosen per step in Settings for [kind]'s default chain, whether AI processing is on or not. */
        fun of(prefs: Prefs, kind: ChainKind = ChainKind.OTHER): AiPicks = AiPicks(AiStep.entries.associateWith { prefs.aiModel(it, kind) })
    }
}

/**
 * The models pages go through, in [AiStep] order: those chosen in Settings for the book's
 * [ChainKind] while AI processing is on, or a book's own.
 */
object AiChain {
    /**
     * Version of how a chain renders a page ([AiPages.chain]); part of every processed page's
     * key ([signature]), so a change to it never shows pages processed the old way.
     */
    const val ENGINE = 2

    /** [prefs]' chosen models of [kind]'s default chain, one per step at most; empty while AI processing is off. */
    fun chosen(prefs: Prefs, kind: ChainKind = ChainKind.OTHER): List<AiModel> =
        if (!prefs.aiEnabled) emptyList() else AiPicks.of(prefs, kind).models

    /** The models a book's pages go through: its [own] picks when it has them, which win, else [kind]'s [chosen]. */
    fun forBook(prefs: Prefs, own: AiPicks?, kind: ChainKind = ChainKind.OTHER): List<AiModel> = own?.models ?: chosen(prefs, kind)

    /** The picks a book of [kind] starts from when given its own: that default chain while AI processing is on, else none. */
    fun startingPicks(prefs: Prefs, kind: ChainKind = ChainKind.OTHER): AiPicks =
        if (prefs.aiEnabled) AiPicks.of(prefs, kind) else AiPicks.NONE

    /** True when no default chain picks a model, so AI processing has nothing to run without a book's own. */
    fun noneChosen(prefs: Prefs): Boolean = ChainKind.entries.all { AiPicks.of(prefs, it).models.isEmpty() }

    /** The [chosen] models of [kind] that [store] holds, with their files; one not downloaded yet is left out. */
    fun ready(prefs: Prefs, store: ModelStore, kind: ChainKind = ChainKind.OTHER): List<Pair<AiModel, ModelFiles>> =
        chosen(prefs, kind).mapNotNull { m -> store.files(m)?.let { m to it } }

    /**
     * What identifies [models]' output: the [ENGINE], then each model's id and its files'
     * checksums ([AiModel.fingerprint]), so a model replaced never shows pages its old files made.
     */
    fun signature(models: List<AiModel>): String =
        "e$ENGINE;" + models.joinToString(";") { "${it.id}:${it.fingerprint}" }

    /** [models]' time together for a page ([AiModel.pageMs]). */
    fun pageMs(models: List<AiModel>): Long = models.sumOf { it.pageMs }

    /**
     * True for a chain that takes [pageMs] a page, past [ModelCatalog.MAX_PAGE_MS] (where the
     * load bar turns red, [AiLoad.HEAVY_MS]): each page takes long and costs battery, and the
     * reader may run past even the longer time it gives such a chain ([ReaderAi.limitsFor]) and
     * show it without AI.
     */
    fun slow(pageMs: Long): Boolean = pageMs > ModelCatalog.MAX_PAGE_MS
}
