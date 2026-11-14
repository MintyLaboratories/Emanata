package com.infinity.comicreader.ui

import android.content.DialogInterface
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.preference.ListPreference
import androidx.preference.PreferenceDialogFragmentCompat
import com.infinity.comicreader.R
import com.infinity.comicreader.ai.AiStep
import com.infinity.comicreader.data.Prefs

/**
 * A Settings AI step's picker ([ModelPicker]) for its [ListPreference]: a tap on a row picks
 * and closes, as in [androidx.preference.ListPreferenceDialogFragmentCompat]; a level picked
 * on a slider is stored at once, the picker kept open.
 */
class AiChoiceDialog : PreferenceDialogFragmentCompat() {
    private var picked: String? = null

    private val list get() = preference as ListPreference

    override fun onPrepareDialogBuilder(builder: AlertDialog.Builder) {
        super.onPrepareDialogBuilder(builder)
        val pref = list
        val step = AiStep.entries.first { Prefs.aiKey(it) == pref.key }
        val current = pref.value?.takeIf { it != Prefs.AI_NONE }
        val keepSimple = Prefs(requireContext()).keepSimple
        val picker = ModelPicker.of(requireContext(), step, keepSimple, current) { id, done ->
            val value = id ?: Prefs.AI_NONE
            if (done) {
                picked = value
                // Closes as the positive button would, so onDialogClosed takes the pick.
                dialog?.let { onClick(it, DialogInterface.BUTTON_POSITIVE); it.dismiss() }
            } else {
                store(value)
            }
        }
        builder.setView(picker.view)
        builder.setPositiveButton(null, null)
        builder.setNegativeButton(R.string.close, null)
    }

    override fun onDialogClosed(positiveResult: Boolean) {
        if (!positiveResult) return
        picked?.let(::store)
    }

    private fun store(value: String) {
        val pref = list
        if (pref.callChangeListener(value)) pref.value = value
    }

    companion object {
        const val TAG = "ai_choice"

        /** The picker of the [ListPreference] keyed [key]. */
        fun of(key: String) = AiChoiceDialog().apply { arguments = bundleOf("key" to key) }
    }
}
