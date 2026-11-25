package minty.emanata.ui

import minty.emanata.ai.FileSet
import minty.emanata.ai.ModelDownloads
import minty.emanata.ai.ModelStore
import minty.emanata.ai.Models

/**
 * The models picked in one open model picker ([ModelPicker]), for what stays downloaded as it
 * closes: the model picked last, not the levels passed over on the way. [held] says whether
 * a model is in the store or on its way already: installed, partly downloaded, or asked for.
 */
class PickerDownloads(private val held: (FileSet) -> Boolean) {
    /** Each model picked so far, by id, with whether it was held when first picked. */
    private val picks = LinkedHashMap<String, Boolean>()

    /** True once anything was picked, none included. */
    var any: Boolean = false
        private set

    /** Notes a pick of [id] (null: none), before its download can start. */
    fun picked(id: String?) {
        any = true
        val m = Models.byId(id) as? FileSet ?: return
        picks.getOrPut(m.id) { held(m) }
    }

    /**
     * The models passed over for [last]: picked in this picker, not held when first picked (so
     * their files, whole or partial, and their downloads came with the pick), and neither
     * [last] nor in [chosen] (the ids other chains still run).
     */
    fun passedOver(last: String?, chosen: Set<String>): List<FileSet> =
        picks.filter { (id, before) -> !before && id != last && id !in chosen }.keys.mapNotNull { Models.byId(it) as? FileSet }

    companion object {
        /** Picks in [store], a model held when installed, partly downloaded or asked for ([ModelDownloads]). */
        fun of(store: ModelStore) = PickerDownloads { m ->
            store.installed(m) || ModelDownloads.state.value.states.containsKey(m.id) || store.bytesHeld(m) > 0
        }
    }
}
