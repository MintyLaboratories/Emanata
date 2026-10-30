package com.infinity.comicreader.ui

import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.infinity.comicreader.R
import com.infinity.comicreader.data.BookCaches
import com.infinity.comicreader.data.Library
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.files.FileClipboard
import com.infinity.comicreader.files.FileProblem
import com.infinity.comicreader.files.FileSpaces
import com.infinity.comicreader.files.FileWork
import com.infinity.comicreader.files.Transfer
import com.infinity.comicreader.net.ChangeRefused
import com.infinity.comicreader.net.RemoteException
import com.infinity.comicreader.util.AppDispatchers
import com.infinity.comicreader.util.FileUtils
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.CancellationException

/**
 * The file operations of the library's selection, on the device and on shares: Rename,
 * Delete, and Move or Copy, which pick the items up ([FileClipboard]) for a Paste bubble
 * to put into the folder browsed. Each runs as one queued [FileWork], over its items one
 * at a time, going on past one that fails; its progress shows on its bubble, and its
 * outcome as a toast, saying how many were done when not all. Favorites, history,
 * bookmarks and all else kept for an item follow it when it moves or is renamed, and go
 * when it is deleted.
 */
internal class FileActions(
    private val activity: AppCompatActivity,
    private val library: Library,
    private val prefs: Prefs,
    /** Names the location a path is on: "Device", or the share's name. */
    private val placeOf: (String) -> String,
    /** Hears each finished operation, after its outcome was told. */
    private val onFinished: (FileWork.Outcome) -> Unit,
    /** Hears the picks waiting change. */
    private val onClipChanged: () -> Unit,
    /** Names the folder at a path, as its crumb does. */
    private val folderNameOf: (String) -> String = { File(it).name }
) {
    /** Operations this screen saw unfinished, by id. */
    private val watching = HashSet<Long>()

    /** Picks whose Paste is being checked or asked about, by id: another tap on it waits. */
    private val pasting = HashSet<Long>()

    private val notificationAsk = NotificationAsk(activity, prefs)

    init {
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                FileWork.operations.collect { follow(it) }
            }
        }
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
        start(FileWork.Kind.RENAME, old, 1) {
            Transfer().rename(path, name)
            followMove(path, target)
            FileWork.Outcome.Done(target, gone = listOf(path))
        }
    }

    // ------------------------------------------------------------------ delete

    /**
     * Deletes [selected] once confirmed, asking once for all of them, but for those inside a
     * folder among them ([FileClipboard.outermost]); [onConfirmed] runs as the deleting is queued.
     */
    fun delete(selected: List<FileClipboard.Item>, onConfirmed: () -> Unit = {}) {
        val items = FileClipboard.outermost(selected)
        val first = items.firstOrNull() ?: return
        val place = items.map { placeOf(it.path) }.distinct().joinToString(", ")
        val res = activity.resources
        val (title, message) = if (items.size == 1) {
            activity.getString(R.string.delete_item_title, first.name) to
                activity.getString(if (first.isDir) R.string.delete_folder_msg else R.string.delete_file_msg, place)
        } else {
            res.getQuantityString(R.plurals.delete_items_title, items.size, items.size) to
                res.getQuantityString(
                    if (items.any { it.isDir }) R.plurals.delete_items_folders_msg else R.plurals.delete_items_msg,
                    items.size, items.size, place
                )
        }
        confirm(activity, title, message, R.string.delete) {
            onConfirmed()
            start(FileWork.Kind.DELETE, first.name, items.size) { p ->
                val gone = ArrayList<String>()
                var done = 0
                var failure: Exception? = null
                for ((i, item) in items.withIndex()) {
                    if (items.size > 1) {
                        if (!p.alive) return@start FileWork.Outcome.Canceled(done, items.size, gone)
                        p.item(item.name, i, items.size)
                    }
                    val path = item.path
                    val space = FileSpaces.of(path)
                    val error = attempt { space.delete(path) }
                    // Even a partial failure may have removed it, and what was gone already counts as deleted.
                    val removed = runCatching { space.stat(path) == null }.getOrDefault(false)
                    if (removed) {
                        forget(path)
                        gone += path
                    }
                    if (error is CancellationException) return@start FileWork.Outcome.Canceled(done + (if (removed) 1 else 0), items.size, gone)
                    if (error == null || removed) done++ else if (failure == null) failure = error
                }
                failure?.let { FileWork.Outcome.Failed(it, done, items.size, gone) } ?: FileWork.Outcome.Done(null, gone = gone, count = done)
            }
        }
    }

    // ------------------------------------------------------------------ move / copy

    /** Picks [items] up together, for a Paste bubble to move or copy; a folder among them takes along what is in it. */
    fun pickUp(items: List<FileClipboard.Item>, move: Boolean): FileClipboard.Clip? {
        if (items.isEmpty()) return null
        val clip = FileClipboard.Clip(FileClipboard.outermost(items), move)
        FileClipboard.add(clip)
        onClipChanged()
        return clip
    }

    /** Lets go of the pick [id]. */
    fun drop(id: Long) {
        FileClipboard.remove(id)
        onClipChanged()
    }

    /** An item to paste under [name], over what has that name there when [replace]; an empty name is still to be found. */
    private data class Plan(val item: FileClipboard.Item, val name: String, val replace: Boolean = false)

    /**
     * Pastes the items of pick [id] (the latest when null) into [dir], one at a time as one
     * queued operation, asking once for all whose names are taken there: Replace, Keep both,
     * or Skip them (the others are pasted all the same). A copy beside itself, or an item whose
     * name an earlier one takes, keeps both. A pick is pasted once: its Paste turns into the
     * operation, newest in the stack, and only what a move failed at or never reached comes
     * back, as a new Paste.
     */
    fun paste(dir: File, id: Long? = null) {
        val clip = (if (id == null) FileClipboard.clips.value.lastOrNull() else FileClipboard[id])
            ?: return toast(activity.getString(R.string.paste_dropped))
        if (clip.id in pasting) return
        val targetDir = dir.absolutePath
        if (clip.items.any { Transfer.isWithin(targetDir, it.path, ignoreCase = true) }) return toast(activity.getString(R.string.paste_into_itself))
        // Moving what is already here leaves it where it is.
        val items = if (clip.move) clip.items.filter { Transfer.parentOf(it.path) != targetDir } else clip.items
        if (items.isEmpty()) return toast(activity.getString(R.string.paste_already_here))
        pasting += clip.id
        activity.lifecycleScope.launch {
            try {
                plan(clip, targetDir, items)
            } finally {
                pasting -= clip.id
            }
        }
    }

    /** Pastes [items] of [clip] into [targetDir], asking first about the names taken there. */
    private suspend fun plan(clip: FileClipboard.Clip, targetDir: String, items: List<FileClipboard.Item>) {
        // Names are taken as the folder's storage tells: most storage ignores case.
        val found = withContext(AppDispatchers.io) {
            runCatching {
                val space = FileSpaces.of(targetDir)
                val there = items.filter { FileSpaces.of(it.path).stat(it.path) != null }
                    .associateWith { space.stat(Transfer.child(targetDir, it.name)) }
                val twins = there.keys.map { it.name.lowercase(Locale.ROOT) }.distinct().size < there.size
                val names = if (there.values.all { it == null } && !twins) emptySet() else space.list(targetDir).map { it.name.lowercase(Locale.ROOT) }.toSet()
                there to names
            }
        }
        val (there, names) = found.getOrElse {
            toast(activity.getString(R.string.file_failed, describe(it)))
            return
        }
        if (there.isEmpty()) {
            toast(activity.getString(R.string.paste_gone, items.first().name))
            drop(clip.id)
            return
        }
        val plans = ArrayList<Plan>()
        val clashes = ArrayList<FileClipboard.Item>()
        val claimed = HashSet<String>()
        for ((item, node) in there) {
            val first = claimed.add(item.name.lowercase(Locale.ROOT))
            when {
                // A copy beside itself is a second copy, as is an item whose name an earlier one takes.
                !first || (node != null && Transfer.parentOf(item.path) == targetDir) -> plans += Plan(item, "")
                node == null -> plans += Plan(item, item.name)
                else -> clashes += item
            }
        }
        val taken = (names + claimed).toHashSet()
        fun keepBoth(item: FileClipboard.Item) =
            Transfer.keepBothName(item.name, item.isDir) { it.lowercase(Locale.ROOT) in taken }.also { taken += it.lowercase(Locale.ROOT) }
        for (i in plans.indices) if (plans[i].name.isEmpty()) plans[i] = plans[i].copy(name = keepBoth(plans[i].item))
        if (clashes.isEmpty()) return transfer(clip, targetDir, plans)
        AlertDialog.Builder(activity).apply {
            setTitle(
                if (clashes.size == 1) activity.getString(R.string.clash_title, clashes[0].name)
                else activity.resources.getQuantityString(R.plurals.clash_many_title, clashes.size, clashes.size)
            )
            // Folders are replaced whole, never merged.
            if (clashes.any { there[it]?.isDirectory == true }) setMessage(R.string.clash_folder)
            // Never replacing what holds an item: it would go with it.
            if (clashes.none { Transfer.isWithin(it.path, Transfer.child(targetDir, it.name), ignoreCase = true) }) {
                setPositiveButton(R.string.clash_replace) { _, _ -> transfer(clip, targetDir, plans + clashes.map { Plan(it, it.name, replace = true) }) }
            }
            setNeutralButton(R.string.clash_keep_both) { _, _ -> transfer(clip, targetDir, plans + clashes.map { Plan(it, keepBoth(it)) }) }
            // Skipping every one is the pick done with too.
            setNegativeButton(R.string.clash_skip) { _, _ -> if (plans.isNotEmpty()) transfer(clip, targetDir, plans) else drop(clip.id) }
        }.show()
    }

    /**
     * Queues pasting [plans] of [clip] into [targetDir] in place of its Paste; what a move
     * failed at or never reached is picked up again as it was ([paste]).
     */
    private fun transfer(clip: FileClipboard.Clip, targetDir: String, plans: List<Plan>) {
        // Canceled while asked about.
        if (FileClipboard[clip.id] == null) return
        FileClipboard.remove(clip.id)
        onClipChanged()
        val move = clip.move
        val kind = if (move) FileWork.Kind.MOVE else FileWork.Kind.COPY
        start(kind, plans.first().item.name, plans.size, folderNameOf(targetDir)) { p ->
            var sourceLeft = false
            val gone = ArrayList<String>()
            // Sources not pasted (yet).
            val unsettled = plans.map { it.item.path }.toHashSet()
            var done = 0
            var failure: Exception? = null
            fun ended(outcome: FileWork.Outcome): FileWork.Outcome {
                val left = plans.map { it.item }.filter { it.path in unsettled }
                if (move && left.isNotEmpty()) FileClipboard.add(clip.copy(items = left, id = FileWork.nextOrder()))
                return outcome
            }
            for ((i, plan) in plans.withIndex()) {
                if (plans.size > 1) {
                    if (!p.alive) return@start ended(FileWork.Outcome.Canceled(done, plans.size, gone))
                    p.item(plan.item.name, i, plans.size)
                }
                val source = plan.item.path
                val target = Transfer.child(targetDir, plan.name)
                val result = try {
                    Transfer(alive = { p.alive }, progress = p::report, stoppable = p::stoppable)
                        .run(source, targetDir, plan.name, move, plan.replace)
                } catch (e: CancellationException) {
                    return@start ended(FileWork.Outcome.Canceled(done, plans.size, gone))
                } catch (e: Exception) {
                    if (failure == null) failure = e
                    continue
                }
                done++
                unsettled -= source
                // What was kept for a replaced item goes with it.
                if (plan.replace) library.removePathsUnder(target)
                if (move) {
                    if (result.sourceLeft) {
                        sourceLeft = true
                    } else {
                        followMove(source, target)
                        gone += source
                    }
                }
            }
            ended(failure?.let { FileWork.Outcome.Failed(it, done, plans.size, gone) } ?: FileWork.Outcome.Done(Transfer.child(targetDir, plans.first().name), sourceLeft, gone, done))
        }
    }

    // ------------------------------------------------------------------ bookkeeping

    /** Re-points everything kept for [old] and inside it to [new]: the library, start folders and the picks waiting. */
    private fun followMove(old: String, new: String) {
        library.replacePath(old, new)
        prefs.followPath(old, new)
        FileClipboard.edit { items -> items.map { if (Transfer.isWithin(it.path, old)) it.copy(path = new + it.path.removePrefix(old)) else it } }
    }

    /**
     * Forgets everything kept for the deleted [path] and inside it, the picks' items too;
     * what is cached for the books there goes with them, unless a copy elsewhere keeps it ([BookCaches.deleted]).
     */
    private fun forget(path: String) {
        BookCaches.deleted(activity, library, path)
        FileClipboard.edit { items -> items.filterNot { Transfer.isWithin(it.path, path) } }
    }

    /** Runs [block]; what it threw, else null. */
    private inline fun attempt(block: () -> Unit): Exception? = try {
        block()
        null
    } catch (e: Exception) {
        e
    }

    /** Queues [work]; see [FileWork.start]. */
    private fun start(
        kind: FileWork.Kind,
        name: String,
        count: Int,
        into: String? = null,
        work: (FileWork.Progress) -> FileWork.Outcome
    ) {
        FileWork.start(kind, name, count, into, work)
        // Asked once, as the first operation starts: operations show their progress there while the app is away.
        notificationAsk.once()
    }

    // ------------------------------------------------------------------ outcomes

    /** Tells each operation's outcome once, then lets it go; lists again for one another screen told. */
    private fun follow(operations: List<FileWork.Status>) {
        for (status in operations) {
            val outcome = status.outcome
            if (outcome == null) {
                watching += status.id
                continue
            }
            watching -= status.id
            FileWork.acknowledge(status.id)
            tell(status, outcome)
            onClipChanged()
            onFinished(outcome)
        }
        val ids = operations.map { it.id }.toSet()
        val told = watching.filter { it !in ids }
        if (told.isNotEmpty()) {
            watching -= told.toSet()
            onClipChanged()
            onFinished(FileWork.Outcome.Done(null))
        }
    }

    private fun tell(status: FileWork.Status, outcome: FileWork.Outcome) {
        val text = when (outcome) {
            is FileWork.Outcome.Done -> when (status.kind) {
                FileWork.Kind.COPY -> counted(R.string.file_copied, R.plurals.copied_count, outcome.count)
                FileWork.Kind.MOVE ->
                    if (outcome.sourceLeft) activity.getString(R.string.file_source_left) else counted(R.string.file_moved, R.plurals.moved_count, outcome.count)
                FileWork.Kind.DELETE -> activity.resources.getQuantityString(R.plurals.deleted_format, outcome.count, outcome.count)
                FileWork.Kind.RENAME -> activity.getString(R.string.renamed)
            }
            is FileWork.Outcome.Canceled ->
                if (outcome.done > 0) activity.getString(R.string.file_canceled_part, part(status.kind, outcome.done, outcome.count))
                else activity.getString(R.string.file_canceled)
            is FileWork.Outcome.Failed ->
                if (outcome.done > 0) activity.getString(R.string.file_failed_part, part(status.kind, outcome.done, outcome.count), describe(outcome.error))
                else activity.getString(R.string.file_failed, describe(outcome.error))
        }
        toast(text)
    }

    /** How many of [count] items an operation of [kind] did: "Deleted 3 of 5 items". */
    private fun part(kind: FileWork.Kind, done: Int, count: Int): String = activity.resources.getQuantityString(
        when (kind) {
            FileWork.Kind.COPY -> R.plurals.copied_part
            FileWork.Kind.MOVE -> R.plurals.moved_part
            FileWork.Kind.DELETE, FileWork.Kind.RENAME -> R.plurals.deleted_part
        },
        count, done, count
    )

    /** [one] for a single item, else [many] counting [count]. */
    private fun counted(one: Int, many: Int, count: Int): String =
        if (count == 1) activity.getString(one) else activity.resources.getQuantityString(many, count, count)

    /** [error] for the user: what went wrong, naming items without their paths. */
    private fun describe(error: Throwable): String = when {
        error is FileWork.NameTaken -> activity.getString(R.string.file_exists)
        error is FileProblem -> activity.getString(
            when (error.kind) {
                FileProblem.Kind.HOLDS_SOURCE -> R.string.file_holds_source
                FileProblem.Kind.LINK -> R.string.file_link
                FileProblem.Kind.CHANGED -> R.string.file_changed
                FileProblem.Kind.DIFFERS -> R.string.file_differs
                FileProblem.Kind.INCOMPLETE -> R.string.file_incomplete
            },
            error.name
        )
        error is ChangeRefused -> activity.getString(
            when (error.why) {
                ChangeRefused.Why.READ_ONLY -> R.string.file_read_only
                ChangeRefused.Why.FULL -> R.string.file_full
                ChangeRefused.Why.IN_USE -> R.string.file_in_use
                ChangeRefused.Why.TAKEN -> R.string.file_exists
            }
        )
        error is RemoteException -> activity.getString(
            when (error.reason) {
                RemoteException.Reason.DENIED -> R.string.file_denied
                RemoteException.Reason.MISSING -> R.string.file_missing
                else -> R.string.share_unreachable
            }
        )
        else -> FileProblem.namesOnly(error.message ?: error.javaClass.simpleName)
    }

    private fun toast(text: String) = Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()
}
