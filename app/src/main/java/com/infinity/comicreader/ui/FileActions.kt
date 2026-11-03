package com.infinity.comicreader.ui

import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.materialswitch.MaterialSwitch
import com.infinity.comicreader.R
import com.infinity.comicreader.compact.PackFolders
import com.infinity.comicreader.data.Library
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.files.FileClipboard
import com.infinity.comicreader.files.FileProblem
import com.infinity.comicreader.files.FileSpaces
import com.infinity.comicreader.files.FileWork
import com.infinity.comicreader.files.Node
import com.infinity.comicreader.files.Transfer
import com.infinity.comicreader.net.ChangeRefused
import com.infinity.comicreader.net.RemoteException
import com.infinity.comicreader.util.AppDispatchers
import com.infinity.comicreader.util.FileUtils
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * The file operations of the library's selection, on the device and on shares: Rename,
 * Delete, and Move or Copy, which pick the items up ([FileClipboard]) for a Paste bubble
 * to put into the folder browsed; for books on the device, Compact and Make
 * compatible ([FileJobs.rebuild]); and for folders of loose images, Pack ([FileJobs.pack]). Each runs as one queued [FileWork], over its items one
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

    /** The operations' work, holding nothing of this screen; lambdas queued take it as a local. */
    private val jobs = FileJobs(activity, library, prefs)

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
        val jobs = this.jobs
        val chosen = jobs.look(listOf(path))
        start(FileWork.Kind.RENAME, File(path).name, 1) { jobs.rename(path, name, chosen) }
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
            val jobs = this.jobs
            val chosen = jobs.look(items.map { it.path })
            start(FileWork.Kind.DELETE, first.name, items.size) { p -> jobs.delete(items, chosen, p) }
        }
    }

    // ------------------------------------------------------------------ compact

    /** [rebuild] compacting the books at [paths], or with [compatible] making them compatible. */
    fun rebuild(paths: List<String>, compatible: Boolean, onConfirmed: () -> Unit = {}) =
        rebuild(paths, if (compatible) FileJobs.Rebuild.COMPATIBLE else FileJobs.Rebuild.COMPACT, onConfirmed)

    /**
     * Rebuilds the books at [paths] as [how] says once confirmed: compacts them losslessly,
     * makes their pages in formats few readers open ones every reader opens, or repacks
     * them as CBZ. The dialog warns, and offers In place (remembered, shared by all three)
     * and, for Compact, Skip hidden pages (off each time). One queued operation, a book at
     * a time; [onConfirmed] runs as it is queued.
     */
    fun rebuild(paths: List<String>, how: FileJobs.Rebuild, onConfirmed: () -> Unit = {}) {
        val first = paths.firstOrNull() ?: return
        val (one, many) = when (how) {
            FileJobs.Rebuild.COMPACT -> R.string.compact_title_one to R.plurals.compact_title
            FileJobs.Rebuild.COMPATIBLE -> R.string.compatible_title_one to R.plurals.compatible_title
            FileJobs.Rebuild.REPACK -> R.string.repack_title_one to R.plurals.repack_title
        }
        val title = if (paths.size == 1) activity.getString(one, File(first).name) else activity.resources.getQuantityString(many, paths.size, paths.size)
        val view = compactOptions(activity, prefs, skipHidden = how == FileJobs.Rebuild.COMPACT)
        val inPlace = view.findViewById<MaterialSwitch>(R.id.compact_in_place)
        val skip = view.findViewById<MaterialSwitch>(R.id.compact_skip_hidden)
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(
                when (how) {
                    FileJobs.Rebuild.COMPACT -> R.string.compact_warning
                    FileJobs.Rebuild.COMPATIBLE -> R.string.compatible_message
                    FileJobs.Rebuild.REPACK -> R.string.repack_message
                }
            )
            .setView(view)
            .setPositiveButton(
                when (how) {
                    FileJobs.Rebuild.COMPACT -> R.string.more_compact
                    FileJobs.Rebuild.COMPATIBLE -> R.string.more_make_compatible
                    FileJobs.Rebuild.REPACK -> R.string.more_repack
                }
            ) { _, _ ->
                prefs.compactInPlace = inPlace.isChecked
                onConfirmed()
                val skipHidden = how == FileJobs.Rebuild.COMPACT && skip.isChecked
                startRebuild(jobs, paths, how, inPlace.isChecked, skipHidden)
                // Asked once, as the first operation starts: operations show their progress there while the app is away.
                notificationAsk.once()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------------ pack

    /**
     * Packs [folders], each holding images only, into a `.cbz` beside it once confirmed: its
     * images as they are, or with [compacted] made smaller losslessly (warned as Compact is).
     * Asks once for all holding unusual files ([PackFolders.isUnusual]) whether to pack them
     * too, and once for all whose `.cbz` name is taken: Replace, Keep both, or Skip them. One
     * queued operation, a folder at a time; [onConfirmed] runs as the dialog is confirmed.
     */
    fun pack(folders: List<File>, compacted: Boolean, onConfirmed: () -> Unit = {}) {
        val first = folders.firstOrNull() ?: return
        val title = if (folders.size == 1) activity.getString(R.string.pack_title_one, first.name)
        else activity.resources.getQuantityString(R.plurals.pack_title, folders.size, folders.size)
        val message = activity.getString(if (folders.size == 1) R.string.pack_message_one else R.string.pack_message) +
            if (compacted) "\n\n" + activity.getString(R.string.compact_warning) else ""
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(if (compacted) R.string.more_pack_compacted else R.string.more_pack) { _, _ ->
                onConfirmed()
                activity.lifecycleScope.launch { planPack(folders, compacted) }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Packs [folders], asking first about their unusual files, then about the `.cbz` names taken beside them. */
    private suspend fun planPack(folders: List<File>, compacted: Boolean) {
        val unusual = withContext(AppDispatchers.io) { folders.associateWith { PackFolders.of(it).unusual } }
        if (unusual.values.all { it.isEmpty() }) return planPack(folders, compacted, unusual, false)
        val list = withContext(AppDispatchers.io) {
            unusual.filterValues { it.isNotEmpty() }.entries.joinToString("\n") { (dir, files) ->
                val names = files.joinToString(", ") { unusualLabel(it) }
                if (folders.size > 1) activity.getString(R.string.pack_why_named, dir.name, names) else names
            }
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.pack_unusual_title)
            .setMessage(activity.getString(R.string.pack_unusual_message, list))
            .setPositiveButton(R.string.pack_unusual_yes) { _, _ -> activity.lifecycleScope.launch { planPack(folders, compacted, unusual, true) } }
            .setNegativeButton(R.string.pack_unusual_no) { _, _ -> activity.lifecycleScope.launch { planPack(folders, compacted, unusual, false) } }
            .setNeutralButton(R.string.cancel, null)
            .show()
    }

    /** How the unusual [file] shows in the question: a dot-folder, an empty file, or a dot-file. */
    private fun unusualLabel(file: File): String = when {
        file.isDirectory -> activity.getString(R.string.pack_unusual_folder, file.name)
        file.length() == 0L -> activity.getString(R.string.pack_unusual_empty, file.name)
        else -> activity.getString(R.string.pack_unusual_hidden, file.name)
    }

    /** Packs [folders] with their [unusual] files when [unusualToo], asking first about the `.cbz` names taken beside them. */
    private suspend fun planPack(folders: List<File>, compacted: Boolean, unusual: Map<File, List<File>>, unusualToo: Boolean) {
        fun plan(dir: File, name: String, replace: Boolean = false, over: Node? = null) =
            FileJobs.PackPlan(dir.absolutePath, name, replace, over, unusual[dir].orEmpty().map { it.name }, unusualToo)
        val there = withContext(AppDispatchers.io) {
            folders.associateWith { FileSpaces.of(it.absolutePath).stat(Transfer.child(it.parent ?: "", packName(it))) }
        }
        val plans = folders.filter { there[it] == null }.map { plan(it, packName(it)) }
        val clashes = folders.filter { there[it] != null }
        if (clashes.isEmpty()) return startPack(plans, compacted)
        fun keepBoth(dir: File) = Transfer.keepBothName(packName(dir), false) { File(dir.parentFile, it).exists() }
        AlertDialog.Builder(activity).apply {
            setTitle(
                if (clashes.size == 1) activity.getString(R.string.clash_title, packName(clashes[0]))
                else activity.resources.getQuantityString(R.plurals.clash_many_title, clashes.size, clashes.size)
            )
            // A folder of that name is never replaced: it would go, with everything in it.
            if (clashes.none { there[it]?.isDirectory == true }) {
                setPositiveButton(R.string.clash_replace) { _, _ ->
                    startPack(plans + clashes.map { plan(it, packName(it), replace = true, over = there[it]) }, compacted)
                }
            }
            setNeutralButton(R.string.clash_keep_both) { _, _ ->
                activity.lifecycleScope.launch {
                    val kept = withContext(AppDispatchers.io) { clashes.map { plan(it, keepBoth(it)) } }
                    startPack(plans + kept, compacted)
                }
            }
            setNegativeButton(R.string.clash_skip) { _, _ -> startPack(plans, compacted) }
        }.show()
    }

    /** Queues packing as [plans] say, in the order the folders were selected. */
    private fun startPack(plans: List<FileJobs.PackPlan>, compacted: Boolean) {
        val first = plans.firstOrNull() ?: return
        val jobs = this.jobs
        val chosen = jobs.look(plans.map { it.folder })
        start(FileWork.Kind.PACK, File(first.folder).name, plans.size) { p -> jobs.pack(plans, compacted, chosen, p) }
    }

    /** The name of the `.cbz` folder [dir] is packed into. */
    private fun packName(dir: File) = "${dir.name}.cbz"

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

    /**
     * Pastes the items of pick [id] (the latest when null) into [dir], one at a time as one
     * queued operation, asking once for all whose names are taken there: Replace, Keep both,
     * or Skip them (the others are pasted all the same). A copy beside itself, or an item whose
     * name an earlier one takes, keeps both; so does one whose name is taken by its turn, or
     * whose Replace finds another item there than the one chosen. A pick is pasted once: its
     * Paste turns into the operation, newest in the stack, and only what a move failed at or
     * never reached comes back, as a new Paste.
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
        val plans = ArrayList<FileJobs.Plan>()
        val clashes = ArrayList<FileClipboard.Item>()
        val claimed = HashSet<String>()
        for ((item, node) in there) {
            val first = claimed.add(item.name.lowercase(Locale.ROOT))
            when {
                // A copy beside itself is a second copy, as is an item whose name an earlier one takes.
                !first || (node != null && Transfer.parentOf(item.path) == targetDir) -> plans += FileJobs.Plan(item, "")
                node == null -> plans += FileJobs.Plan(item, item.name)
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
                setPositiveButton(R.string.clash_replace) { _, _ -> transfer(clip, targetDir, plans + clashes.map { FileJobs.Plan(it, it.name, replace = true, over = there[it]) }) }
            }
            setNeutralButton(R.string.clash_keep_both) { _, _ -> transfer(clip, targetDir, plans + clashes.map { FileJobs.Plan(it, keepBoth(it)) }) }
            // Skipping every one is the pick done with too.
            setNegativeButton(R.string.clash_skip) { _, _ -> if (plans.isNotEmpty()) transfer(clip, targetDir, plans) else drop(clip.id) }
        }.show()
    }

    /**
     * Queues pasting [plans] of [clip] into [targetDir] in place of its Paste; what a move
     * failed at or never reached, canceled before it began too, is picked up again ([paste]).
     */
    private fun transfer(clip: FileClipboard.Clip, targetDir: String, plans: List<FileJobs.Plan>) {
        // Canceled while asked about.
        if (FileClipboard[clip.id] == null) return
        FileClipboard.remove(clip.id)
        onClipChanged()
        val move = clip.move
        val kind = if (move) FileWork.Kind.MOVE else FileWork.Kind.COPY
        val jobs = this.jobs
        start(kind, plans.first().item.name, plans.size, folderNameOf(targetDir), { jobs.giveBack(clip, plans.map { it.item }) }) { p ->
            jobs.transfer(clip, targetDir, plans, p)
        }
    }

    // ------------------------------------------------------------------ queue

    /** Queues [work]; see [FileWork.start]. */
    private fun start(
        kind: FileWork.Kind,
        name: String,
        count: Int,
        into: String? = null,
        unrun: () -> Int = { 0 },
        work: (FileWork.Progress) -> FileWork.Outcome
    ) {
        FileWork.start(kind, name, count, into, unrun, work)
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
                FileWork.Kind.COMPACT, FileWork.Kind.COMPATIBLE, FileWork.Kind.REPACK -> rebuilt(status.kind, outcome)
                FileWork.Kind.PACK -> packed(outcome)
            }
            is FileWork.Outcome.Canceled -> canceled(status.kind, outcome)
            is FileWork.Outcome.Failed ->
                if (outcome.done > 0) activity.getString(R.string.file_failed_part, part(status.kind, outcome.done, outcome.count, outcome.summary), describe(outcome.error))
                else activity.getString(R.string.file_failed, describe(outcome.error))
        }
        toast(text, long = outcome is FileWork.Outcome.Canceled && outcome.givenBack > 0)
        // Also when other books failed or the run was canceled: those books were rebuilt all the same.
        outcome.rebuilt?.let { tellPages(it) }
    }

    /** What a canceled operation did; for a move, how many items it did not reach are back as a Paste. */
    private fun canceled(kind: FileWork.Kind, outcome: FileWork.Outcome.Canceled): String {
        val back = outcome.givenBack
        val returned = activity.resources.getQuantityString(R.plurals.move_back, back, back)
        return when {
            back > 0 && outcome.done > 0 -> activity.getString(R.string.file_canceled_back_part, part(kind, outcome.done, outcome.count), returned)
            back > 0 -> activity.getString(R.string.file_canceled_back, returned)
            outcome.done > 0 -> activity.getString(R.string.file_canceled_part, part(kind, outcome.done, outcome.count, outcome.summary))
            else -> activity.getString(R.string.file_canceled)
        }
    }

    /**
     * What rebuilding books did: how many, the bytes Compact saved, how many were left as
     * they were, and how many pages could not be made anew; or why nothing was done.
     */
    private fun rebuilt(kind: FileWork.Kind, outcome: FileWork.Outcome.Done): String {
        val res = activity.resources
        val compatible = kind == FileWork.Kind.COMPATIBLE
        val count = outcome.count
        val summary = outcome.summary ?: FileWork.Summary(0)
        val failed = summary.failedPages
        val unchanged = summary.unchanged
        var text = when {
            count == 0 && failed > 0 -> res.getQuantityString(R.plurals.rebuild_failed_only, failed, failed)
            count == 0 && summary.nested > 0 && summary.nested == unchanged -> res.getQuantityString(R.plurals.rebuild_nested, unchanged, unchanged)
            count == 0 && summary.already > 0 && summary.already == unchanged && kind != FileWork.Kind.REPACK ->
                res.getQuantityString(if (compatible) R.plurals.compatible_already else R.plurals.compact_already, unchanged, unchanged)
            count == 0 && kind == FileWork.Kind.REPACK -> activity.getString(R.string.repack_nothing)
            count == 0 -> activity.getString(if (compatible) R.string.compatible_nothing else R.string.compact_nothing)
            kind == FileWork.Kind.REPACK -> res.getQuantityString(R.plurals.repacked_count, count, count)
            compatible -> res.getQuantityString(R.plurals.compatible_count, count, count)
            else -> res.getQuantityString(R.plurals.compacted_count, count, count, FileUtils.formatSize(activity, summary.saved.coerceAtLeast(0)))
        }
        if (count > 0 && unchanged > 0) text = res.getQuantityString(R.plurals.rebuild_unchanged, unchanged, text, unchanged)
        text = withReencoded(text, summary)
        if (count > 0 && failed > 0) text = res.getQuantityString(R.plurals.rebuild_failed_pages, failed, text, failed)
        return text
    }

    /** What packing folders did: how many, the bytes Pack compacted saved, and the new files named anew as their name was taken. */
    private fun packed(outcome: FileWork.Outcome.Done): String {
        var text = activity.resources.getQuantityString(R.plurals.packed_count, outcome.count, outcome.count)
        val saved = outcome.summary?.saved ?: 0L
        if (saved > 0) text = activity.getString(R.string.rebuild_saved, text, FileUtils.formatSize(activity, saved))
        val kept = outcome.summary?.keptBoth.orEmpty()
        if (kept.isNotEmpty()) text = activity.resources.getQuantityString(R.plurals.pack_kept_both, kept.size, text, kept.size, kept.joinToString(", "))
        return text
    }

    /** [text] with how many pages [summary] re-encoded, if any. */
    private fun withReencoded(text: String, summary: FileWork.Summary?): String {
        val pages = summary?.reencoded?.values?.sumOf { it.size } ?: 0
        return if (pages > 0) activity.resources.getQuantityString(R.plurals.rebuild_reencoded, pages, text, pages) else text
    }

    /** Lists by book the pages a rebuild could not make anew, if any; those re-encoded are only counted ([withReencoded]). */
    private fun tellPages(summary: FileWork.Summary) {
        if (summary.failed.isEmpty()) return
        val list = summary.failed.entries.joinToString("\n") { (book, names) -> activity.getString(R.string.reencoded_book, book, names.joinToString(", ")) }
        AlertDialog.Builder(activity)
            .setTitle(R.string.unconverted_title)
            .setMessage(activity.getString(R.string.unconverted_compact) + "\n\n" + list)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /**
     * How many of [count] items an operation of [kind] did: "Deleted 3 of 5 items"; for
     * Compact, with the bytes its [summary] saved.
     */
    private fun part(kind: FileWork.Kind, done: Int, count: Int, summary: FileWork.Summary? = null): String {
        val text = activity.resources.getQuantityString(
            when (kind) {
                FileWork.Kind.COPY -> R.plurals.copied_part
                FileWork.Kind.MOVE -> R.plurals.moved_part
                FileWork.Kind.DELETE, FileWork.Kind.RENAME -> R.plurals.deleted_part
                FileWork.Kind.COMPACT -> R.plurals.compacted_part
                FileWork.Kind.COMPATIBLE -> R.plurals.compatible_part
                FileWork.Kind.PACK -> R.plurals.packed_part
                FileWork.Kind.REPACK -> R.plurals.repacked_part
            },
            count, done, count
        )
        val saved = summary?.saved ?: 0L
        if ((kind == FileWork.Kind.COMPACT || kind == FileWork.Kind.PACK) && saved > 0) return activity.getString(R.string.rebuild_saved, text, FileUtils.formatSize(activity, saved))
        return withReencoded(text, summary)
    }

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
                FileProblem.Kind.GONE -> R.string.file_gone
                FileProblem.Kind.ALTERED -> R.string.file_altered
            },
            error.name
        )
        error is ChangeRefused -> activity.getString(
            when (error.why) {
                ChangeRefused.Why.READ_ONLY -> R.string.file_read_only
                ChangeRefused.Why.FULL -> R.string.file_full
                ChangeRefused.Why.IN_USE -> R.string.file_in_use
                ChangeRefused.Why.IN_READER -> R.string.file_in_reader
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

    private fun toast(text: String, long: Boolean = false) = Toast.makeText(activity, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()

    companion object {
        /**
         * The options of a rebuild's dialog: In place, as last chosen, and when [skipHidden]
         * Skip hidden pages (off each time) with its note.
         */
        fun compactOptions(activity: AppCompatActivity, prefs: Prefs, skipHidden: Boolean): View {
            val view = activity.layoutInflater.inflate(R.layout.dialog_compact, null)
            view.findViewById<MaterialSwitch>(R.id.compact_in_place).isChecked = prefs.compactInPlace
            if (!skipHidden) {
                view.findViewById<View>(R.id.compact_skip_hidden).visibility = View.GONE
                view.findViewById<View>(R.id.compact_skip_hidden_hint).visibility = View.GONE
            }
            return view
        }

        /** Queues rebuilding the books at [paths] as [how] says through [jobs], in place when [inPlace]. */
        fun startRebuild(jobs: FileJobs, paths: List<String>, how: FileJobs.Rebuild, inPlace: Boolean, skipHidden: Boolean = false) {
            val first = paths.firstOrNull() ?: return
            val chosen = jobs.look(paths)
            FileWork.start(how.kind, File(first).name, paths.size) { p -> jobs.rebuild(paths, how, inPlace, skipHidden, chosen, p) }
        }
    }
}
