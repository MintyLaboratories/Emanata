package com.infinity.comicreader.ui

import android.content.DialogInterface
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.preference.ListPreference
import androidx.preference.PreferenceDialogFragmentCompat

/**
 * A Settings AI step's picker: its [ListPreference]'s choices, each spaced apart from the next
 * ([ModelText.adapter]) as they run to several lines. A choice is picked on tap, as in
 * [androidx.preference.ListPreferenceDialogFragmentCompat].
 */
class AiChoiceDialog : PreferenceDialogFragmentCompat() {
    private var picked = -1

    private val list get() = preference as ListPreference

    override fun onPrepareDialogBuilder(builder: AlertDialog.Builder) {
        super.onPrepareDialogBuilder(builder)
        val pref = list
        val entries = pref.entries?.toList().orEmpty()
        builder.setSingleChoiceItems(ModelText.adapter(requireContext(), entries), pref.findIndexOfValue(pref.value)) { dialog, which ->
            picked = which
            // Closes as the positive button would, so onDialogClosed takes the pick.
            onClick(dialog, DialogInterface.BUTTON_POSITIVE)
            dialog.dismiss()
        }
        builder.setPositiveButton(null, null)
    }

    override fun onDialogClosed(positiveResult: Boolean) {
        if (!positiveResult || picked < 0) return
        val pref = list
        val value = pref.entryValues?.getOrNull(picked)?.toString() ?: return
        if (pref.callChangeListener(value)) pref.value = value
    }

    companion object {
        const val TAG = "ai_choice"

        /** The picker of the [ListPreference] keyed [key]. */
        fun of(key: String) = AiChoiceDialog().apply { arguments = bundleOf("key" to key) }
    }
}
