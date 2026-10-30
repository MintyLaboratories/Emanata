package com.infinity.comicreader.ui

import android.content.Context
import com.infinity.comicreader.data.BookCaches
import com.infinity.comicreader.data.Library
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.files.FileClipboard
import com.infinity.comicreader.files.FileProblem
import com.infinity.comicreader.files.FileSpaces
import com.infinity.comicreader.files.FileWork
import com.infinity.comicreader.files.Node
import com.infinity.comicreader.files.Transfer
import com.infinity.comicreader.util.AppDispatchers
import kotlinx.coroutines.asExecutor
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future

/**
 * The work of queued file operations ([FileWork]): it holds the application, never a
 * screen, so a long queue keeps no screen alive. Favorites, history, bookmarks and all
 * else kept for an item follow it when it moves or is renamed, and go when it is deleted.
 */
internal class FileJobs(context: Context, private val library: Library, private val prefs: Prefs) {
    private val app = context.applicationContext

    /** An item to paste under [name], over what has that name there when [replace]. */
    data class Plan(val item: FileClipboard.Item, val name: String, val replace: Boolean = false)

    /**
     * What is at each of [paths] as they are chosen, looked up at once beside the queue: a
     * queued operation then acts only on what was chosen ([changed]). Null where nothing is,
     * a failure where the lookup failed.
     */
    fun look(paths: List<String>): Future<List<Result<Node?>>> =
        CompletableFuture.supplyAsync({ paths.map { runCatching { FileSpaces.of(it).stat(it) } } }, AppDispatchers.io.asExecutor())

    /**
     * Why [path] is no longer what was [chosen] there: gone, or changed or replaced; null when it
     * is, or when either lookup failed (the operation then fails or not on its own).
     */
    private fun changed(path: String, chosen: Result<Node?>?): FileProblem? {
        val now = runCatching { FileSpaces.of(path).stat(path) }.getOrElse { return null }
        val name = File(path).name
        if (now == null) return FileProblem(FileProblem.Kind.GONE, name)
        if (chosen == null || chosen.isFailure) return null
        val was = chosen.getOrNull()
        val same = was != null && was.isDirectory == now.isDirectory && was.size == now.size && was.modified == now.modified
        return if (same) null else FileProblem(FileProblem.Kind.ALTERED, name)
    }

    /** The lookups of [chosen] once done, none when they failed. */
    private fun settled(chosen: Future<List<Result<Node?>>>): List<Result<Node?>>? = runCatching { chosen.get() }.getOrNull()

    /** Renames [path] to [name], unless it is no longer what was [chosen] ([look]). */
    fun rename(path: String, name: String, chosen: Future<List<Result<Node?>>>): FileWork.Outcome {
        val target = Transfer.child(Transfer.parentOf(path), name)
        changed(path, settled(chosen)?.firstOrNull())?.let { throw it }
        Transfer().rename(path, name)
        followMove(path, target)
        return FileWork.Outcome.Done(target, gone = listOf(path))
    }

    /**
     * Deletes [items] one at a time, going on past one that fails; one no longer what was
     * [chosen] ([look]) is left alone, and said.
     */
    fun delete(items: List<FileClipboard.Item>, chosen: Future<List<Result<Node?>>>, p: FileWork.Progress): FileWork.Outcome {
        val seen = settled(chosen)
        val gone = ArrayList<String>()
        var done = 0
        var failure: Exception? = null
        for ((i, item) in items.withIndex()) {
            if (items.size > 1) {
                if (!p.alive) return FileWork.Outcome.Canceled(done, items.size, gone)
                p.item(item.name, i, items.size)
            }
            val path = item.path
            changed(path, seen?.getOrNull(i))?.let {
                if (failure == null) failure = it
                continue
            }
            val space = FileSpaces.of(path)
            val error = attempt { space.delete(path) }
            // Even a partial failure may have removed it.
            val removed = runCatching { space.stat(path) == null }.getOrDefault(false)
            if (removed) {
                forget(path)
                gone += path
            }
            if (error is CancellationException) return FileWork.Outcome.Canceled(done + (if (removed) 1 else 0), items.size, gone)
            if (error == null || removed) done++ else if (failure == null) failure = error
        }
        return failure?.let { FileWork.Outcome.Failed(it, done, items.size, gone) } ?: FileWork.Outcome.Done(null, gone = gone, count = done)
    }

    /**
     * Pastes [plans] of [clip] into [targetDir] one at a time, going on past one that fails;
     * what a move failed at or never reached is picked up again ([giveBack]).
     */
    fun transfer(clip: FileClipboard.Clip, targetDir: String, plans: List<Plan>, p: FileWork.Progress): FileWork.Outcome {
        val move = clip.move
        var sourceLeft = false
        val gone = ArrayList<String>()
        // Sources not pasted (yet).
        val unsettled = plans.map { it.item.path }.toHashSet()
        var done = 0
        var failure: Exception? = null
        fun ended(outcome: FileWork.Outcome): FileWork.Outcome {
            giveBack(clip, plans.map { it.item }.filter { it.path in unsettled })
            return outcome
        }
        for ((i, plan) in plans.withIndex()) {
            if (plans.size > 1) {
                if (!p.alive) return ended(FileWork.Outcome.Canceled(done, plans.size, gone))
                p.item(plan.item.name, i, plans.size)
            }
            val source = plan.item.path
            val target = Transfer.child(targetDir, plan.name)
            val result = try {
                Transfer(alive = { p.alive }, progress = p::report, stoppable = p::stoppable)
                    .run(source, targetDir, plan.name, move, plan.replace)
            } catch (e: CancellationException) {
                return ended(FileWork.Outcome.Canceled(done, plans.size, gone))
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
        return ended(failure?.let { FileWork.Outcome.Failed(it, done, plans.size, gone) } ?: FileWork.Outcome.Done(Transfer.child(targetDir, plans.first().name), sourceLeft, gone, done))
    }

    /** Picks [items] of a move's [clip] up again, as a new pick; a copy's are let go. */
    fun giveBack(clip: FileClipboard.Clip, items: List<FileClipboard.Item>) {
        if (clip.move && items.isNotEmpty()) FileClipboard.add(clip.copy(items = items, id = FileWork.nextOrder()))
    }

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
        BookCaches.deleted(app, library, path)
        FileClipboard.edit { items -> items.filterNot { Transfer.isWithin(it.path, path) } }
    }

    /** Runs [block]; what it threw, else null. */
    private inline fun attempt(block: () -> Unit): Exception? = try {
        block()
        null
    } catch (e: Exception) {
        e
    }
}
