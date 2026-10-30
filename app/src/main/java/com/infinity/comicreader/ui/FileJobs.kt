package com.infinity.comicreader.ui

import android.content.Context
import com.infinity.comicreader.data.BookCaches
import com.infinity.comicreader.data.Library
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.files.FileClipboard
import com.infinity.comicreader.files.FileSpaces
import com.infinity.comicreader.files.FileWork
import com.infinity.comicreader.files.Transfer
import java.util.concurrent.CancellationException

/**
 * The work of queued file operations ([FileWork]): it holds the application, never a
 * screen, so a long queue keeps no screen alive. Favorites, history, bookmarks and all
 * else kept for an item follow it when it moves or is renamed, and go when it is deleted.
 */
internal class FileJobs(context: Context, private val library: Library, private val prefs: Prefs) {
    private val app = context.applicationContext

    /** An item to paste under [name], over what has that name there when [replace]. */
    data class Plan(val item: FileClipboard.Item, val name: String, val replace: Boolean = false)

    /** Renames [path] to [name]. */
    fun rename(path: String, name: String): FileWork.Outcome {
        val target = Transfer.child(Transfer.parentOf(path), name)
        Transfer().rename(path, name)
        followMove(path, target)
        return FileWork.Outcome.Done(target, gone = listOf(path))
    }

    /** Deletes [items] one at a time, going on past one that fails. */
    fun delete(items: List<FileClipboard.Item>, p: FileWork.Progress): FileWork.Outcome {
        val gone = ArrayList<String>()
        var done = 0
        var failure: Exception? = null
        for ((i, item) in items.withIndex()) {
            if (items.size > 1) {
                if (!p.alive) return FileWork.Outcome.Canceled(done, items.size, gone)
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
