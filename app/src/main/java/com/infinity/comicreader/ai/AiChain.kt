package com.infinity.comicreader.ai

import com.infinity.comicreader.data.Prefs

/** The models pages go through, in [AiStep] order: those chosen in Settings while AI processing is on. */
object AiChain {
    /** [prefs]' chosen catalog models, one per step at most; empty while AI processing is off. */
    fun chosen(prefs: Prefs): List<CatalogModel> =
        if (!prefs.aiEnabled) emptyList()
        else AiStep.entries.mapNotNull { step -> ModelCatalog.byId(prefs.aiModel(step))?.takeIf { it.step == step } }

    /** The [chosen] models [store] holds, with their files; one not downloaded yet is left out. */
    fun ready(prefs: Prefs, store: ModelStore): List<Pair<CatalogModel, ModelFiles>> =
        chosen(prefs).mapNotNull { m -> store.files(m)?.let { m to it } }

    /** [models]' time together for a page on the reference tablet ([CatalogModel.pageMs]). */
    fun pageMs(models: List<CatalogModel>): Long = models.sumOf { it.pageMs }

    /**
     * True when [models] together take longer than [ModelCatalog.MAX_PAGE_MS] on the reference
     * tablet: warmer or slower devices would run past a page's time and show it without AI.
     */
    fun slow(models: List<CatalogModel>): Boolean = pageMs(models) > ModelCatalog.MAX_PAGE_MS
}
