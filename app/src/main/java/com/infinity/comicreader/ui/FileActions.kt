package com.infinity.comicreader.ui

import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.infinity.comicreader.R
import com.infinity.comicreader.data.Library
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.files.FileClipboard
import com.infinity.comicreader.files.FileSpaces
import com.infinity.comicreader.files.FileWork
import com.infinity.comicreader.files.Transfer
import com.infinity.comicreader.net.RemoteException
import com.infinity.comicreader.util.AppDispatchers
import com.infinity.comicreader.util.FileUtils
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * The file operations of the library's long-press menu, on the device and on shares:
 * Rename, Delete, and Move or Copy, which pick the item up ([FileClipboard]) for the
 * Paste button to put into the folder browsed. Each runs as [FileWork]; its progress
 * shows in a dialog with Cancel once it takes a moment, and its outcome as a toast.
 * Favorites, history, bookmarks and all else kept for an item follow it when it moves
 * or is renamed, and go when it is deleted.
 */
internal class FileActions(
    private val activity: AppCompatActivity,
    private val library: Library,
    private val prefs: Prefs,
    /** Names the location a path is on: "Device", or the share's name. */
    private val placeOf: (String) -> String,
    /** Hears each finished operation, after its outcome was told. */
    private val onFinished: (FileWork.Outcome) -> Unit,
    /** Hears the clipboard change. */
    private val onClipChanged: () -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private var progress: AlertDialog? = null
    private var progressName: TextView? = null
    private var progressBar: LinearProgressIndicator? = null
    private var progressAmount: TextView? = null

    /** When the operation this screen saw running started; null when it saw none. */
    private var watching: Long? = null

    private val showLater = Runnable { FileWork.status.value?.takeIf { it.outcome == null }?.let { showProgress(it) } }

    init {
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                FileWork.status.collect { follow(it) }
            }
        }
    }

    /** Lets go of the progress dialog, as the screen goes. */
    fun release() {
        handler.removeCallbacks(showLater)
        progress?.dismiss()
        progress = null
    }

    // ------------------------------------------------------------------ rename

    fun rename(file: File) {
        val input = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(file.name)
            // The name without its extension is picked, ready to type over.
            val dot = file.name.lastIndexOf('.')
            if (dot > 0 && !file.isDirectory) setSelection(0, dot) else selectAll()
        }
        val container = LinearLayout(activity).apply {
            setPadding(48, 24, 48, 0)
            addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.menu_rename)
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty() || name == file.name) return@setPositiveButton
                if (!FileUtils.isSafeName(name)) {
                    toast(activity.getString(R.string.invalid_name))
                    return@setPositiveButton
                }
                renameTo(file.absolutePath, name)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun renameTo(path: String, name: String) {
        val old = File(path).name
        val target = Transfer.child(Transfer.parentOf(path), name)
        start(FileWork.Kind.RENAME, old) {
            val space = FileSpaces.of(path)
            // Only the case changing: on storage that ignores case the "other" item is this one.
            val caseOnly = name.equals(old, ignoreCase = true)
            if (!caseOnly && space.stat(target) != null) throw FileWork.NameTaken(name)
            if (!space.rename(path, target)) throw IOException("Could not rename $path")
            followMove(path, target)
            FileWork.Outcome.Done(target, gone = path)
        }
    }

    // ------------------------------------------------------------------ delete

    fun delete(file: File, isDir: Boolean) {
        val path = file.absolutePath
        val place = placeOf(path)
        confirm(
            activity,
            activity.getString(R.string.delete_item_title, file.name),
            activity.getString(if (isDir) R.string.delete_folder_msg else R.string.delete_file_msg, place),
            R.string.delete
        ) {
            start(FileWork.Kind.DELETE, file.name) {
                val space = FileSpaces.of(path)
                try {
                    space.delete(path)
                } finally {
                    // Even a partial failure may have removed it: forget what is gone.
                    if (runCatching { space.stat(path) == null }.getOrDefault(false)) forget(path)
                }
                FileWork.Outcome.Done(null, gone = path)
            }
        }
    }

    // ------------------------------------------------------------------ move / copy

    /** Puts [file] on the clipboard, for Paste to move or copy. */
    fun pickUp(file: File, isDir: Boolean, move: Boolean) {
        FileClipboard.clip = FileClipboard.Clip(file.absolutePath, isDir, move)
        onClipChanged()
    }

    /** Takes the item off the clipboard. */
    fun drop() {
        FileClipboard.clip = null
        onClipChanged()
    }

    /** Pastes the item picked up into [dir], asking first when its name is taken there. */
    fun paste(dir: File) {
        val clip = FileClipboard.clip ?: return toast(activity.getString(R.string.paste_dropped))
        if (FileWork.busy) return toast(activity.getString(R.string.file_busy))
        val targetDir = dir.absolutePath
        if (Transfer.isWithin(targetDir, clip.path)) return toast(activity.getString(R.string.paste_into_itself))
        val sameFolder = Transfer.parentOf(clip.path) == targetDir
        if (clip.move && sameFolder) return toast(activity.getString(R.string.paste_already_here))
        activity.lifecycleScope.launch {
            val found = withContext(AppDispatchers.io) {
                runCatching {
                    val source = FileSpaces.of(clip.path).stat(clip.path)
                    val names = if (source == null) emptySet() else FileSpaces.of(targetDir).list(targetDir).map { it.name }.toSet()
                    (source != null) to names
                }
            }
            val (there, names) = found.getOrElse {
                toast(activity.getString(R.string.file_failed, describe(it)))
                return@launch
            }
            if (!there) {
                toast(activity.getString(R.string.paste_gone, clip.name))
                drop()
                return@launch
            }
            fun keepBoth() = Transfer.keepBothName(clip.name, clip.isDir) { it in names }
            when {
                clip.name !in names -> transfer(clip, targetDir, clip.name, replace = false)
                // A copy beside itself is a second copy.
                sameFolder -> transfer(clip, targetDir, keepBoth(), replace = false)
                else -> AlertDialog.Builder(activity)
                    .setTitle(activity.getString(R.string.clash_title, clip.name))
                    .setPositiveButton(R.string.clash_replace) { _, _ -> transfer(clip, targetDir, clip.name, replace = true) }
                    .setNeutralButton(R.string.clash_keep_both) { _, _ -> transfer(clip, targetDir, keepBoth(), replace = false) }
                    .setNegativeButton(R.string.clash_skip, null)
                    .show()
            }
        }
    }

    private fun transfer(clip: FileClipboard.Clip, targetDir: String, name: String, replace: Boolean) {
        val kind = if (clip.move) FileWork.Kind.MOVE else FileWork.Kind.COPY
        start(kind, clip.name) { p ->
            val target = Transfer.child(targetDir, name)
            val result = Transfer(alive = { p.alive }, progress = p::report).run(clip.path, targetDir, name, clip.move, replace)
            // What was kept for a replaced item goes with it.
            if (replace) library.removePathsUnder(target)
            if (clip.move) {
                if (result.sourceLeft) {
                    FileClipboard.clip = null
                } else {
                    followMove(clip.path, target)
                    if (FileClipboard.clip?.path == target) FileClipboard.clip = null
                }
            }
            FileWork.Outcome.Done(target, result.sourceLeft, gone = clip.path.takeIf { clip.move && !result.sourceLeft })
        }
    }

    // ------------------------------------------------------------------ bookkeeping

    /** Re-points everything kept for [old] and inside it to [new]: the library, start folders and the clipboard. */
    private fun followMove(old: String, new: String) {
        library.replacePath(old, new)
        prefs.followPath(old, new)
        FileClipboard.clip?.let { c ->
            if (Transfer.isWithin(c.path, old)) FileClipboard.clip = c.copy(path = new + c.path.removePrefix(old))
        }
    }

    /** Forgets everything kept for the deleted [path] and inside it, the clipboard's item too. */
    private fun forget(path: String) {
        library.removePathsUnder(path)
        FileClipboard.clip?.let { if (Transfer.isWithin(it.path, path)) FileClipboard.clip = null }
    }

    private fun start(kind: FileWork.Kind, name: String, work: (FileWork.Progress) -> FileWork.Outcome.Done) {
        if (!FileWork.start(kind, name, work)) toast(activity.getString(R.string.file_busy))
    }

    // ------------------------------------------------------------------ progress

    private fun follow(status: FileWork.Status?) {
        if (status == null) {
            hideProgress()
            // Another screen showed the outcome of the operation this one saw: it lists again all the same.
            if (watching != null) {
                watching = null
                onClipChanged()
                onFinished(FileWork.Outcome.Done(null))
            }
            return
        }
        val outcome = status.outcome
        if (outcome == null) watching = status.started
        if (outcome != null) {
            watching = null
            hideProgress()
            FileWork.acknowledge()
            tell(status, outcome)
            onClipChanged()
            onFinished(outcome)
            return
        }
        val wait = PROGRESS_DELAY_MS - (FileWork.clock() - status.started)
        if (progress == null && wait > 0) {
            handler.removeCallbacks(showLater)
            handler.postDelayed(showLater, wait)
            return
        }
        showProgress(status)
    }

    private fun showProgress(status: FileWork.Status) {
        val dialog = progress ?: makeProgress(status.kind).also { progress = it }
        progressName?.text = status.name
        val bar = progressBar ?: return
        val amount = progressAmount ?: return
        if (status.total > 0) {
            if (bar.isIndeterminate) {
                // Switching modes only while hidden: the bar draws it without a jump.
                bar.visibility = View.INVISIBLE
                bar.isIndeterminate = false
                bar.max = PROGRESS_STEPS
                bar.visibility = View.VISIBLE
            }
            bar.setProgressCompat((status.done.coerceIn(0, status.total) * PROGRESS_STEPS / status.total).toInt(), true)
            amount.text = activity.getString(
                R.string.file_amount,
                FileUtils.formatSize(activity, status.done),
                FileUtils.formatSize(activity, status.total)
            )
            amount.visibility = View.VISIBLE
        } else {
            amount.visibility = View.GONE
        }
        if (!dialog.isShowing) dialog.show()
    }

    private fun makeProgress(kind: FileWork.Kind): AlertDialog {
        val view = activity.layoutInflater.inflate(R.layout.dialog_progress, null)
        progressName = view.findViewById(R.id.progress_name)
        progressBar = view.findViewById(R.id.progress_bar)
        progressAmount = view.findViewById(R.id.progress_amount)
        // Only copies stop part way: a share deletes or renames in one request.
        val cancelable = kind == FileWork.Kind.COPY || kind == FileWork.Kind.MOVE
        val builder = AlertDialog.Builder(activity)
            .setTitle(
                when (kind) {
                    FileWork.Kind.COPY -> R.string.file_copying
                    FileWork.Kind.MOVE -> R.string.file_moving
                    FileWork.Kind.DELETE -> R.string.file_deleting
                    FileWork.Kind.RENAME -> R.string.file_renaming
                }
            )
            .setView(view)
            .setCancelable(false)
        if (cancelable) builder.setNegativeButton(R.string.cancel, null)
        val dialog = builder.show()
        // Cancel waits for the work to clean up: the dialog goes with its outcome.
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setOnClickListener {
            it.isEnabled = false
            FileWork.cancel()
        }
        return dialog
    }

    private fun hideProgress() {
        handler.removeCallbacks(showLater)
        progress?.dismiss()
        progress = null
    }

    private fun tell(status: FileWork.Status, outcome: FileWork.Outcome) {
        val text = when (outcome) {
            is FileWork.Outcome.Done -> when (status.kind) {
                FileWork.Kind.COPY -> activity.getString(R.string.file_copied)
                FileWork.Kind.MOVE -> activity.getString(if (outcome.sourceLeft) R.string.file_source_left else R.string.file_moved)
                FileWork.Kind.DELETE -> activity.getString(R.string.deleted_format, 1)
                FileWork.Kind.RENAME -> activity.getString(R.string.renamed)
            }
            FileWork.Outcome.Canceled -> activity.getString(R.string.file_canceled)
            is FileWork.Outcome.Failed -> activity.getString(R.string.file_failed, describe(outcome.error))
        }
        toast(text)
    }

    private fun describe(error: Throwable): String = when {
        error is FileWork.NameTaken -> activity.getString(R.string.file_exists)
        error is RemoteException -> activity.getString(
            when (error.reason) {
                RemoteException.Reason.DENIED -> R.string.file_denied
                RemoteException.Reason.MISSING -> R.string.file_missing
                else -> R.string.share_unreachable
            }
        )
        else -> error.message ?: error.javaClass.simpleName
    }

    private fun toast(text: String) = Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()

    private companion object {
        /** How long an operation runs before its progress shows. */
        const val PROGRESS_DELAY_MS = 400L
        const val PROGRESS_STEPS = 1000
    }
}
