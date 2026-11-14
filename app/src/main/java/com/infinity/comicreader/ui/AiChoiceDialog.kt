package com.infinity.comicreader.ui

import android.content.DialogInterface
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.preference.ListPreference
import androidx.preference.PreferenceDialogFragmentCompat
import com.infinity.comicreader.R
import com.infinity.comicreader.ai.AiStep
import com.infinity.comicreader.data.Prefs

/**
 * A Settings AI step's picker ([ModelPicker]) for its [ListPreference]: a tap on a row picks
 * and closes, as in [androidx.preference.ListPreferenceDialogFragmentCompat]; a level picked
 * on a slider is stored at once, the picker kept open. Rebuilt (on rotation or a window
 * resize), it keeps its open info panels and scroll position.
 */
class AiChoiceDialog : PreferenceDialogFragmentCompat() {
    private var picked: String? = null
    private var picker: ModelPicker? = null
    private var saved: Bundle? = null

    private val list get() = preference as ListPreference

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        saved = savedInstanceState
    }

    override fun onPrepareDialogBuilder(builder: AlertDialog.Builder) {
        super.onPrepareDialogBuilder(builder)
        val pref = list
        val step = AiStep.valueOf(requireArguments().getString(ARG_STEP)!!)
        val current = pref.value?.takeIf { it != Prefs.AI_NONE }
        val keepSimple = Prefs(requireContext()).keepSimple
        val open = saved?.getStringArrayList(STATE_OPEN).orEmpty()
        val y = saved?.takeIf { it.containsKey(STATE_Y) }?.getInt(STATE_Y)
        val p = ModelPicker.of(requireContext(), step, keepSimple, current, open, y) { id, done ->
            val value = id ?: Prefs.AI_NONE
            if (done) {
                picked = value
                // Closes as the positive button would, so onDialogClosed takes the pick.
                dialog?.let { onClick(it, DialogInterface.BUTTON_POSITIVE); it.dismiss() }
            } else {
                store(value)
            }
        }
        picker = p
        builder.setView(p.view)
        builder.setPositiveButton(null, null)
        builder.setNegativeButton(R.string.close, null)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        picker?.let {
            outState.putStringArrayList(STATE_OPEN, it.expandedKeys)
            outState.putInt(STATE_Y, it.scrollY)
        }
    }

    override fun onDialogClosed(positiveResult: Boolean) {
        // A level still settling on a slider is picked as the picker closes.
        picker?.flush()
        if (!positiveResult) return
        picked?.let(::store)
    }

    private fun store(value: String) {
        val pref = list
        if (pref.callChangeListener(value)) pref.value = value
    }

    companion object {
        const val TAG = "ai_choice"
        private const val ARG_STEP = "step"
        private const val STATE_OPEN = "open"
        private const val STATE_Y = "y"

        /** The picker of [step]'s models for the [ListPreference] keyed [key]. */
        fun of(key: String, step: AiStep) = AiChoiceDialog().apply {
            arguments = Bundle().apply {
                putString("key", key)
                putString(ARG_STEP, step.name)
            }
        }
    }
}
