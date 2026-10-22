package com.infinity.comicreader.ui

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import com.google.android.material.textfield.TextInputLayout
import com.infinity.comicreader.R
import com.infinity.comicreader.net.ShareForm
import com.infinity.comicreader.net.SharePath
import com.infinity.comicreader.net.ShareStore

/**
 * The form that adds a network share, or edits one: name, path (`host/share/folder`),
 * user and password, everything else defaulted. Saving checks the path, stores the
 * share (its password sealed) and tells the [LocationSheet.Host]; a bad path keeps the
 * form open, saying what is wrong. It comes back after a rotation with what was typed.
 */
class ShareDialog : DialogFragment() {
    private lateinit var form: View

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val store = ShareStore(context)
        val previous = arguments?.getString(ARG_ID)?.let { store.get(it) }
        form = LayoutInflater.from(context).inflate(R.layout.dialog_share, null)
        if (savedInstanceState == null && previous != null) {
            field(R.id.share_name).setText(previous.name)
            field(R.id.share_path).setText(previous.path.toString())
            field(R.id.share_user).setText(previous.user)
        }
        if (previous != null) {
            form.findViewById<TextInputLayout>(R.id.share_password_layout).helperText = getString(R.string.share_password_keep)
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle(if (previous == null) R.string.share_add_title else R.string.share_edit_title)
            .setView(form)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        // Saving checks first, and a bad path keeps the form open.
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { if (save(store)) dismissAllowingStateLoss() }
        }
        return dialog
    }

    private fun field(id: Int): EditText = form.findViewById(id)

    private fun text(id: Int): String = field(id).text?.toString().orEmpty()

    /** Stores the share the form describes; false, showing why, when its path is bad. */
    private fun save(store: ShareStore): Boolean {
        val previous = arguments?.getString(ARG_ID)?.let { store.get(it) }
        val pathLayout = form.findViewById<TextInputLayout>(R.id.share_path_layout)
        val result = ShareForm.build(text(R.id.share_name), text(R.id.share_path), text(R.id.share_user), text(R.id.share_password), previous)
        when (result) {
            is ShareForm.Result.Invalid -> {
                pathLayout.error = getString(problemText(result.problem))
                return false
            }
            is ShareForm.Result.Ok -> {
                pathLayout.error = null
                store.put(result.share)
                (activity as? LocationSheet.Host)?.onSharesChanged()
                return true
            }
        }
    }

    companion object {
        private const val TAG = "share"
        private const val ARG_ID = "id"

        /** Opens the form over [activity]: a new share, or the one with [id]. */
        fun show(activity: FragmentActivity, id: String?) {
            val fm = activity.supportFragmentManager
            if (fm.isStateSaved) return
            (fm.findFragmentByTag(TAG) as? DialogFragment)?.dismiss()
            ShareDialog().apply { arguments = bundleOf(ARG_ID to id) }.show(fm, TAG)
        }

        /** The form open over [activity]; null when none is. */
        fun find(activity: FragmentActivity): ShareDialog? = activity.supportFragmentManager.findFragmentByTag(TAG) as? ShareDialog

        fun problemText(problem: SharePath.Problem): Int = when (problem) {
            SharePath.Problem.EMPTY -> R.string.share_problem_empty
            SharePath.Problem.NO_SHARE -> R.string.share_problem_no_share
            SharePath.Problem.BAD_HOST -> R.string.share_problem_host
            SharePath.Problem.BAD_PORT -> R.string.share_problem_port
            SharePath.Problem.BAD_NAME -> R.string.share_problem_name
        }
    }
}
