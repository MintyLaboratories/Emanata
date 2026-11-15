package com.infinity.comicreader.ui

import com.infinity.comicreader.ai.FileSet
import com.infinity.comicreader.ai.Models

/**
 * The models picked in one open model picker ([ModelPicker]), for what stays downloaded as it
 * closes: the model picked last, not the levels passed over on the way. [installed] says
 * whether a model's files are all in the store.
 */
class PickerDownloads(private val installed: (FileSet) -> Boolean) {
    /** Each model picked so far, by id, with whether it was installed when first picked. */
    private val picks = LinkedHashMap<String, Boolean>()

    /** True once anything was picked, none included. */
    var any: Boolean = false
        private set

    /** Notes a pick of [id] (null: none), before its download can start. */
    fun picked(id: String?) {
        any = true
        val m = Models.byId(id) as? FileSet ?: return
        picks.getOrPut(m.id) { installed(m) }
    }

    /**
     * The models to drop as the picker closes on [last]: picked in it, not installed when
     * first picked (so their files, whole or partial, came with the pick), and neither [last]
     * nor in [chosen] (the ids other chains still run).
     */
    fun passedOver(last: String?, chosen: Set<String>): List<FileSet> =
        picks.filter { (id, before) -> !before && id != last && id !in chosen }.keys.mapNotNull { Models.byId(it) as? FileSet }
}
