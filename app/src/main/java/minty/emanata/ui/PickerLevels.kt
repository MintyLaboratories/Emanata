package minty.emanata.ui

import android.content.Context
import minty.emanata.ai.AiStep

/**
 * The level each family ([PickerRow.group]) was last picked at, per AI step, so its slider
 * shows that level again once another row is picked ([ModelPicker]). A file of its own, out
 * of backups: a convenience of the pickers, not a setting.
 */
class PickerLevels(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** The id of the model [group] was last picked at in [step]'s pickers; null when never. */
    fun get(step: AiStep, group: String): String? = sp.getString(key(step, group), null)

    fun put(step: AiStep, group: String, id: String) {
        sp.edit().putString(key(step, group), id).apply()
    }

    private fun key(step: AiStep, group: String) = "${step.name}/$group"

    companion object {
        private const val FILE = "picker_levels"
    }
}
