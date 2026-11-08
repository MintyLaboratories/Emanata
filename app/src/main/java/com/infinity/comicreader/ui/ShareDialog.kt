package com.infinity.comicreader.ui

import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.text.format.Formatter
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.autofill.AutofillManager
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.textfield.TextInputLayout
import com.infinity.comicreader.R
import com.infinity.comicreader.net.DavUrl
import com.infinity.comicreader.net.Share
import com.infinity.comicreader.net.ShareForm
import com.infinity.comicreader.net.ShareKind
import com.infinity.comicreader.net.ShareSpeed
import com.infinity.comicreader.net.ShareSpeeds
import com.infinity.comicreader.net.SpeedMeter
import com.infinity.comicreader.net.SharePath
import com.infinity.comicreader.net.ShareStore
import com.infinity.comicreader.util.AppDispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The form that adds a network share, or edits one: SMB or WebDAV, name, path
 * (`host/share/folder`, or an `https://` address for WebDAV, plain `http://` to a
 * private address with a warning), user and password (an edit can clear a stored one),
 * whether covers of its books are made, and its speed (Auto, measured from its reads and shown
 * as measured; Fast; Slow); everything else defaulted. Saving checks
 * the path, stores the share (its password sealed) off the main thread and tells the [LocationSheet.Host];
 * a bad path, or a password the keystore could not seal, keeps the form open, saying
 * what is wrong. It comes back after a rotation with what was typed.
 */
class ShareDialog : DialogFragment() {
    private lateinit var form: View

    /** True once "Clear password" was tapped: a blank password then stores none. */
    private var clearing = false

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val store = ShareStore(context)
        val previous = arguments?.getString(ARG_ID)?.let { store.get(it) }
        clearing = savedInstanceState?.getBoolean(STATE_CLEARING) == true
        form = LayoutInflater.from(context).inflate(R.layout.dialog_share, null)
        val kinds = form.findViewById<MaterialButtonToggleGroup>(R.id.share_kind)
        kinds.addOnButtonCheckedListener { _, _, checked -> if (checked) showKind() }
        if (savedInstanceState == null && previous != null) {
            kinds.check(if (previous.kind == ShareKind.WEBDAV) R.id.share_kind_webdav else R.id.share_kind_smb)
            field(R.id.share_name).setText(previous.name)
            field(R.id.share_path).setText(previous.path.toString())
            field(R.id.share_user).setText(previous.user)
            form.findViewById<CompoundButton>(R.id.share_thumbnails).isChecked = previous.thumbnails
            form.findViewById<MaterialButtonToggleGroup>(R.id.share_speed).check(speedButton(previous.speed))
        }
        form.findViewById<TextView>(R.id.share_speed_measured).text =
            measuredText(context, previous?.let { ShareSpeeds.estimate(it.id) })
        field(R.id.share_path).doAfterTextChanged { showPathHint() }
        if (previous != null) showPassword(previous)
        val dialog = AlertDialog.Builder(context)
            .setTitle(if (previous == null) R.string.share_add_title else R.string.share_edit_title)
            .setView(form)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel) { _, _ -> dropAutofill() }
            .create()
        // Saving checks first, and a bad path keeps the form open.
        dialog.setOnShowListener {
            showKind()
            val button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            button.setOnClickListener { save(store, button) }
        }
        return dialog
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_CLEARING, clearing)
    }

    /** What is stored of [previous]'s password, and "Clear password" while there is one to clear. */
    private fun showPassword(previous: Share) {
        val stored = previous.password.isNotEmpty() || previous.passwordLost
        val clear = form.findViewById<View>(R.id.share_password_clear)
        clear.visibility = if (stored && !clearing) View.VISIBLE else View.GONE
        clear.setOnClickListener {
            clearing = true
            field(R.id.share_password).text = null
            showPassword(previous)
        }
        form.findViewById<TextInputLayout>(R.id.share_password_layout).helperText = getString(
            when {
                clearing && stored -> R.string.share_password_cleared
                previous.passwordLost -> R.string.share_password_lost
                stored -> R.string.share_password_keep
                else -> R.string.share_password_none
            }
        )
    }

    override fun onCancel(dialog: DialogInterface) {
        dropAutofill()
        super.onCancel(dialog)
    }

    /**
     * Ends the autofill session before the form closes, so no password manager offers
     * to save what was typed: recent Android versions fill and save even fields marked
     * not important for autofill.
     */
    private fun dropAutofill() {
        runCatching { context?.getSystemService(AutofillManager::class.java)?.cancel() }
    }

    private fun field(id: Int): EditText = form.findViewById(id)

    /** How the share is reached, as chosen at the top. */
    private fun kind(): ShareKind =
        if (form.findViewById<MaterialButtonToggleGroup>(R.id.share_kind).checkedButtonId == R.id.share_kind_webdav) ShareKind.WEBDAV else ShareKind.SMB

    /** Labels the path and user for the chosen [kind]. */
    private fun showKind() {
        val webDav = kind() == ShareKind.WEBDAV
        form.findViewById<TextInputLayout>(R.id.share_path_layout).apply {
            hint = getString(if (webDav) R.string.share_address else R.string.share_path)
            error = null
        }
        showPathHint()
        form.findViewById<TextInputLayout>(R.id.share_user_layout).helperText =
            getString(if (webDav) R.string.share_user_hint_webdav else R.string.share_user_hint)
    }

    /** The path's example, or a warning for a WebDAV address over plain http. */
    private fun showPathHint() {
        val webDav = kind() == ShareKind.WEBDAV
        val plain = webDav && (DavUrl.parse(text(R.id.share_path)) as? DavUrl.Parsed.Valid)?.url?.secure == false
        form.findViewById<TextInputLayout>(R.id.share_path_layout).helperText = getString(
            when {
                plain -> R.string.share_http_warning
                webDav -> R.string.share_address_hint
                else -> R.string.share_path_hint
            }
        )
    }

    private fun text(id: Int): String = field(id).text?.toString().orEmpty()

    /** Stores the share the form describes and closes the form; shows why instead when it cannot. */
    private fun save(store: ShareStore, button: View) {
        val previous = arguments?.getString(ARG_ID)?.let { store.get(it) }
        val pathLayout = form.findViewById<TextInputLayout>(R.id.share_path_layout)
        val passwordLayout = form.findViewById<TextInputLayout>(R.id.share_password_layout)
        val kind = kind()
        val thumbnails = form.findViewById<CompoundButton>(R.id.share_thumbnails).isChecked
        val speed = speedOf(form.findViewById<MaterialButtonToggleGroup>(R.id.share_speed).checkedButtonId)
        val result = ShareForm.build(
            text(R.id.share_name), text(R.id.share_path), text(R.id.share_user), text(R.id.share_password), previous, kind,
            thumbnails = thumbnails, speed = speed, clearPassword = clearing
        )
        when (result) {
            is ShareForm.Result.Invalid -> {
                val onPassword = result.problem == SharePath.Problem.PASSWORD_AGAIN
                pathLayout.error = if (onPassword) null else getString(problemText(result.problem, kind))
                passwordLayout.error = if (onPassword) getString(problemText(result.problem, kind)) else null
            }
            is ShareForm.Result.Ok -> {
                pathLayout.error = null
                passwordLayout.error = null
                button.isEnabled = false
                lifecycleScope.launch {
                    // Finishes even when the form goes meanwhile: the share is stored or not, never half.
                    val stored = withContext(NonCancellable + AppDispatchers.io) { runCatching { store.put(result.share) } }
                    button.isEnabled = true
                    stored.onSuccess {
                        (activity as? LocationSheet.Host)?.onSharesChanged()
                        dropAutofill()
                        dismissAllowingStateLoss()
                    }.onFailure {
                        Log.w(TAG, "Storing the share failed", it)
                        passwordLayout.error = getString(R.string.share_save_failed)
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "share"
        private const val ARG_ID = "id"
        private const val STATE_CLEARING = "clearing"

        /** Opens the form over [activity]: a new share, or the one with [id]. */
        fun show(activity: FragmentActivity, id: String?) {
            val fm = activity.supportFragmentManager
            if (fm.isStateSaved) return
            (fm.findFragmentByTag(TAG) as? DialogFragment)?.dismiss()
            ShareDialog().apply { arguments = bundleOf(ARG_ID to id) }.show(fm, TAG)
        }

        /** The form open over [activity]; null when none is. */
        fun find(activity: FragmentActivity): ShareDialog? = activity.supportFragmentManager.findFragmentByTag(TAG) as? ShareDialog

        private fun speedButton(speed: ShareSpeed): Int = when (speed) {
            ShareSpeed.AUTO -> R.id.share_speed_auto
            ShareSpeed.FAST -> R.id.share_speed_fast
            ShareSpeed.SLOW -> R.id.share_speed_slow
        }

        private fun speedOf(button: Int): ShareSpeed = when (button) {
            R.id.share_speed_fast -> ShareSpeed.FAST
            R.id.share_speed_slow -> ShareSpeed.SLOW
            else -> ShareSpeed.AUTO
        }

        /** What [estimate] says of a share: throughput and first-byte wait, and whether Auto counts it slow. */
        fun measuredText(context: Context, estimate: SpeedMeter.Estimate?): String {
            estimate ?: return context.getString(R.string.share_speed_unmeasured)
            val parts = listOfNotNull(
                estimate.bytesPerSecond?.let { context.getString(R.string.share_speed_rate, Formatter.formatShortFileSize(context, it)) },
                estimate.firstByteMs?.let { context.getString(R.string.share_speed_first_byte, it.toInt()) }
            ).joinToString(", ")
            return context.getString(if (estimate.slow) R.string.share_speed_measured_slow else R.string.share_speed_measured_fast, parts)
        }

        fun problemText(problem: SharePath.Problem, kind: ShareKind = ShareKind.SMB): Int = when (problem) {
            SharePath.Problem.EMPTY -> if (kind == ShareKind.WEBDAV) R.string.share_problem_empty_webdav else R.string.share_problem_empty
            SharePath.Problem.NO_SHARE -> R.string.share_problem_no_share
            SharePath.Problem.BAD_HOST -> R.string.share_problem_host
            SharePath.Problem.BAD_PORT -> R.string.share_problem_port
            SharePath.Problem.BAD_NAME -> if (kind == ShareKind.WEBDAV) R.string.share_problem_name_webdav else R.string.share_problem_name
            SharePath.Problem.WEB_ADDRESS -> R.string.share_problem_web
            SharePath.Problem.INSECURE -> R.string.share_problem_insecure
            SharePath.Problem.PASSWORD_AGAIN -> R.string.share_problem_password_again
        }
    }
}
