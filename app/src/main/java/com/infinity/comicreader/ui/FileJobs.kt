package com.infinity.comicreader.ui

import android.content.Context
import com.infinity.comicreader.compact.AndroidImages
import com.infinity.comicreader.compact.CompactWorkers
import com.infinity.comicreader.compact.Compactor
import com.infinity.comicreader.compact.FolderReader
import com.infinity.comicreader.compact.Handovers
import com.infinity.comicreader.compact.LibJxl
import com.infinity.comicreader.compact.PackFolders
import com.infinity.comicreader.compact.PageWork
import com.infinity.comicreader.data.BookCaches
import com.infinity.comicreader.data.BookId
import com.infinity.comicreader.data.BookIds
import com.infinity.comicreader.data.BookRef
import com.infinity.comicreader.data.Library
import com.infinity.comicreader.data.LikenessStore
import com.infinity.comicreader.data.OpenBooks
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.files.DeviceSpace
import com.infinity.comicreader.files.FileClipboard
import com.infinity.comicreader.files.FileProblem
import com.infinity.comicreader.files.FileSpaces
import com.infinity.comicreader.files.FileWork
import com.infinity.comicreader.files.Node
import com.infinity.comicreader.files.Transfer
import com.infinity.comicreader.net.ChangeRefused
import com.infinity.comicreader.net.RemoteException
import com.infinity.comicreader.net.Remotes
import com.infinity.comicreader.reader.ArchiveReader
import com.infinity.comicreader.reader.NameCharset
import com.infinity.comicreader.util.AppDispatchers
import kotlinx.coroutines.asExecutor
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future
import java.util.Locale

/**
 * The work of queued file operations ([FileWork]): it holds the application, never a
 * screen, so a long queue keeps no screen alive. Favorites, history, bookmarks and all
 * else kept for an item follow it when it moves or is renamed, and go when it is deleted.
 */
internal class FileJobs(
    context: Context,
    private val library: Library,
    private val prefs: Prefs,
    /** What makes pages anew for Compact and Make compatible; null where libjxl is missing. Tests stand in their own. */
    private val pageWork: () -> PageWork? = ::platformPages
) {
    private val app = context.applicationContext

    /** Books' data on its way to their rebuilt files ([recover]). */
    internal val handovers = Handovers(File(app.filesDir, HANDOVERS))

    /**
     * An item to paste under [name], over what has that name there when [replace]: the item
     * [over], found there as the user chose to replace it.
     */
    data class Plan(val item: FileClipboard.Item, val name: String, val replace: Boolean = false, val over: Node? = null)

    /**
     * The folder at [folder] to pack into [name] beside it, over the file there when
     * [replace]: the one [over], found there as the user chose to replace it.
     */
    data class PackPlan(val folder: String, val name: String, val replace: Boolean = false, val over: Node? = null)

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
        return if (same(chosen.getOrNull(), now)) null else FileProblem(FileProblem.Kind.ALTERED, name)
    }

    /** True when [was] and [now] look like the same item, unchanged. */
    private fun same(was: Node?, now: Node?): Boolean =
        was != null && now != null && was.isDirectory == now.isDirectory && was.size == now.size && was.modified == now.modified

    /**
     * [plan] as things are now in [targetDir], whose names [taken] (lowercase) holds once
     * listed: a name taken since it was planned keeps both, as does a Replace when what it
     * would replace is no longer what the user chose to replace.
     */
    private fun settle(plan: Plan, targetDir: String, taken: Lazy<MutableSet<String>>): Plan {
        val space = FileSpaces.of(targetDir)
        // Unknown: the transfer finds out, and fails as it would.
        val there = runCatching { space.stat(Transfer.child(targetDir, plan.name)) }.getOrElse { return plan }
        if (there == null) return plan.copy(replace = false)
        if (plan.replace && same(plan.over, there)) return plan
        val name = Transfer.keepBothName(plan.item.name, plan.item.isDir) { it.lowercase(Locale.ROOT) in taken.value }
        return plan.copy(name = name, replace = false)
    }

    /**
     * The shares one operation found unreachable: its items left on them are not tried, so
     * a share going away mid-operation stops them at once, with one failure told.
     */
    internal class Down {
        private val ids = HashSet<String>()

        /** Notes the shares of [paths] unreachable when [error] says one is: those that count as such now, else all of them. */
        fun heard(error: Throwable?, vararg paths: String) {
            if (!unreachable(error)) return
            val shares = paths.mapNotNull { Remotes.idOf(it) }.distinct()
            val now = shares.filter { Remotes.unreachable(it) }
            ids += now.ifEmpty { shares }
        }

        /** True when any of [paths] is on a share found unreachable. */
        fun blocks(vararg paths: String): Boolean = paths.any { p -> Remotes.idOf(p)?.let { it in ids } == true }

        private fun unreachable(error: Throwable?): Boolean =
            generateSequence(error) { it.cause }.take(8).any { it is RemoteException && it.reason == RemoteException.Reason.UNREACHABLE }
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
        val down = Down()
        var done = 0
        var failure: Exception? = null
        for ((i, item) in items.withIndex()) {
            val path = item.path
            if (down.blocks(path)) continue
            if (items.size > 1) {
                if (!p.alive) return FileWork.Outcome.Canceled(done, items.size, gone)
                p.item(item.name, i, items.size)
            }
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
            down.heard(error, path)
        }
        return failure?.let { FileWork.Outcome.Failed(it, done, items.size, gone) } ?: FileWork.Outcome.Done(null, gone = gone, count = done)
    }

    /**
     * Pastes [plans] of [clip] into [targetDir] one at a time, going on past one that fails;
     * what a move failed at or never reached is picked up again ([giveBack]).
     */
    fun transfer(clip: FileClipboard.Clip, targetDir: String, plans: List<Plan>, p: FileWork.Progress): FileWork.Outcome {
        val move = clip.move
        val taken = lazy { FileSpaces.of(targetDir).list(targetDir).mapTo(HashSet()) { it.name.lowercase(Locale.ROOT) } }
        var first: String? = null
        var sourceLeft = false
        val gone = ArrayList<String>()
        // Sources not pasted (yet).
        val unsettled = plans.map { it.item.path }.toHashSet()
        val down = Down()
        var done = 0
        var failure: Exception? = null
        fun ended(outcome: FileWork.Outcome): FileWork.Outcome {
            val back = giveBack(clip, plans.map { it.item }.filter { it.path in unsettled })
            return if (outcome is FileWork.Outcome.Canceled) outcome.copy(givenBack = back) else outcome
        }
        for ((i, planned) in plans.withIndex()) {
            if (down.blocks(planned.item.path, targetDir)) continue
            if (plans.size > 1) {
                if (!p.alive) return ended(FileWork.Outcome.Canceled(done, plans.size, gone))
                p.item(planned.item.name, i, plans.size)
            }
            val plan = try {
                settle(planned, targetDir, taken)
            } catch (e: Exception) {
                if (failure == null) failure = e
                down.heard(e, planned.item.path, targetDir)
                continue
            }
            if (taken.isInitialized()) taken.value += plan.name.lowercase(Locale.ROOT)
            if (first == null) first = Transfer.child(targetDir, plan.name)
            val source = plan.item.path
            val target = Transfer.child(targetDir, plan.name)
            val result = try {
                Transfer(alive = { p.alive }, progress = p::report, stoppable = p::stoppable)
                    .run(source, targetDir, plan.name, move, plan.replace)
            } catch (e: CancellationException) {
                return ended(FileWork.Outcome.Canceled(done, plans.size, gone))
            } catch (e: Exception) {
                if (failure == null) failure = e
                down.heard(e, source, targetDir)
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
        return ended(failure?.let { FileWork.Outcome.Failed(it, done, plans.size, gone) } ?: FileWork.Outcome.Done(first, sourceLeft, gone, done))
    }

    /**
     * Rebuilds the books at [paths] on the device one at a time ([Compactor]): with
     * [compatible], their pages in formats few readers open made ones every reader opens, else compacted
     * losslessly. Goes on past one that fails; one no longer what was [chosen] ([look]) is
     * left alone. Everything kept for each book follows its new file, which takes the
     * book's place when [inPlace]; [skipHidden] leaves its hidden pages out.
     */
    fun rebuild(
        paths: List<String>,
        compatible: Boolean,
        inPlace: Boolean,
        skipHidden: Boolean,
        chosen: Future<List<Result<Node?>>>,
        p: FileWork.Progress
    ): FileWork.Outcome {
        val pages = pageWork() ?: return FileWork.Outcome.Failed(IOException("JPEG XL is not available"), 0, paths.size)
        recover()
        val seen = settled(chosen)
        // One operation at a time: whatever is there was left by one that ended early.
        val scratch = File(app.cacheDir, SCRATCH).apply { deleteRecursively(); mkdirs() }
        val workers = CompactWorkers.max()
        val compactor = Compactor(scratch, workers, CompactWorkers.limit(app, workers), CompactWorkers.threads)
        val convert: (ByteArray) -> PageWork.Made? = if (compatible) pages::compatible else pages::compact
        val progress = object : Compactor.Progress {
            override val alive: Boolean get() = p.alive

            override fun report(done: Long, total: Long) = p.report(done, total)
        }
        var done = 0
        var unchanged = 0
        var nested = 0
        var saved = 0L
        var last: String? = null
        val gone = ArrayList<String>()
        val reencoded = LinkedHashMap<String, List<String>>()
        val failedPages = LinkedHashMap<String, List<String>>()
        var failure: Exception? = null
        fun summary() = FileWork.Summary(saved, unchanged, reencoded, failedPages, nested)
        try {
            for ((i, path) in paths.withIndex()) {
                if (!p.alive) return FileWork.Outcome.Canceled(done, paths.size, gone, summary())
                val book = File(path)
                if (paths.size > 1) p.item(book.name, i, paths.size)
                changed(path, seen?.getOrNull(i))?.let {
                    if (failure == null) failure = it
                    continue
                }
                // The reader would go on saving progress for its old file.
                if (OpenBooks.isOpen(path)) {
                    if (failure == null) failure = ChangeRefused(ChangeRefused.Why.IN_READER)
                    continue
                }
                try {
                    val id = BookIds.find(book)
                    val hidden = if (skipHidden && id != null) library.hiddenPages(id) else emptySet()
                    // Not opened meanwhile: the reader waits while the new file takes the book's place and its data follows.
                    val placer = Compactor.Placer { result, part, swap ->
                        OpenBooks.unlessOpen(path) {
                            val newId = BookIds.peek(part)
                            val target = result.file.absolutePath
                            val removed = result.removed != null
                            // Kept on the device until the data has followed, should the app stop in between.
                            val handover = id?.takeIf { it != newId }
                                ?.let { handovers.add(Handovers.Entry(it, newId, path, target, removed, result.moves, result.pageCount)) }
                            swap()
                            follow(path, id, newId, target, removed, result.moves, result.pageCount)
                            handover?.let(handovers::remove)
                        }
                    }
                    val request = Compactor.Request(
                        book, convert, inPlace, if (compatible) SUFFIX_COMPATIBLE else SUFFIX_COMPACTED, hidden, smallerOnly = !compatible,
                        cost = pages::memoryFor, placer = placer
                    )
                    val open = { ArchiveReader.open(book, NameCharset.forBook(book, prefs.charset)) ?: throw IOException("Not an archive: ${book.name}") }
                    when (val result = compactor.run(request, open, progress)) {
                        is Compactor.Result.Unchanged -> {
                            unchanged++
                            if (result.why == Compactor.Why.NESTED) nested++
                            if (result.failed.isNotEmpty()) failedPages[book.name] = result.failed
                        }
                        is Compactor.Result.Rebuilt -> {
                            done++
                            saved += result.before - result.after
                            last = result.file.absolutePath
                            result.removed?.let { gone += it.absolutePath }
                            if (result.reencoded.isNotEmpty()) reencoded[result.file.name] = result.reencoded
                            if (result.failed.isNotEmpty()) failedPages[result.file.name] = result.failed
                        }
                    }
                } catch (e: CancellationException) {
                    return FileWork.Outcome.Canceled(done, paths.size, gone, summary())
                } catch (e: Exception) {
                    if (failure == null) failure = e
                } catch (e: OutOfMemoryError) {
                    if (failure == null) failure = IOException("Out of memory", e)
                }
            }
        } finally {
            scratch.deleteRecursively()
        }
        return failure?.let { FileWork.Outcome.Failed(it, done + unchanged, paths.size, gone, summary()) }
            ?: FileWork.Outcome.Done(last, gone = gone, count = done, summary = summary())
    }

    /**
     * Packs the folders of loose images [plans] name, on the device, one at a time: each
     * into a stored `.cbz` beside it of its images as they are, or with [compacted] made
     * smaller losslessly ([PageWork.compact]). Once the new file is written and checked, it
     * takes what was kept for the folder and its images ([Library.carryFolder]), and the
     * folder goes. Goes on past one that fails; one no longer what was [chosen] ([look]), or
     * holding more than images now, is left alone.
     */
    fun pack(plans: List<PackPlan>, compacted: Boolean, chosen: Future<List<Result<Node?>>>, p: FileWork.Progress): FileWork.Outcome {
        val pages = if (compacted) pageWork() ?: return FileWork.Outcome.Failed(IOException("JPEG XL is not available"), 0, plans.size) else null
        val seen = settled(chosen)
        val scratch = File(app.cacheDir, SCRATCH).apply { deleteRecursively(); mkdirs() }
        val workers = CompactWorkers.max()
        val compactor = Compactor(scratch, workers, CompactWorkers.limit(app, workers), CompactWorkers.threads)
        val convert: (ByteArray) -> PageWork.Made? = pages?.let { it::compact } ?: { null }
        val cost: (ByteArray) -> Long = pages?.let { it::memoryFor } ?: { it.size.toLong() }
        val progress = object : Compactor.Progress {
            override val alive: Boolean get() = p.alive

            override fun report(done: Long, total: Long) = p.report(done, total)
        }
        var done = 0
        var saved = 0L
        var last: String? = null
        val gone = ArrayList<String>()
        val failedPages = LinkedHashMap<String, List<String>>()
        var failure: Exception? = null
        fun summary() = FileWork.Summary(saved, failed = failedPages)
        try {
            for ((i, plan) in plans.withIndex()) {
                if (!p.alive) return FileWork.Outcome.Canceled(done, plans.size, gone, summary())
                val dir = File(plan.folder)
                if (plans.size > 1) p.item(dir.name, i, plans.size)
                changed(plan.folder, seen?.getOrNull(i))?.let {
                    if (failure == null) failure = it
                    continue
                }
                try {
                    val contents = PackFolders.of(dir)
                    if (contents.why != null) throw FileProblem(FileProblem.Kind.ALTERED, dir.name)
                    val images = contents.images
                    val stats = images.map { it.length() to it.lastModified() }
                    // Found as for any book: an image never seen here may share what an identical copy keeps.
                    val ids = images.map { BookIds.find(it) }
                    val (target, replace) = packTarget(plan, dir.parentFile ?: throw IOException("No folder"))
                    var removed = false
                    val placer = Compactor.Placer { result, part, swap ->
                        // Neither an image nor the file replaced open meanwhile: the reader waits.
                        OpenBooks.unlessOpenIn(plan.folder) {
                            OpenBooks.unlessOpen(target.absolutePath) {
                                val newId = BookIds.peek(part)
                                swap()
                                if (replace) BookCaches.deleted(app, library, target.absolutePath)
                                val folderPages = Compactor.pagesOf(images.map { it.name to it.length() })
                                val byPage = arrayOfNulls<BookId>(result.pageCount)
                                folderPages.forEachIndexed { k, at -> result.moves.getOrNull(k)?.takeIf { it in byPage.indices }?.let { byPage[it] = ids[at] } }
                                library.carryFolder(plan.folder, byPage.toList(), BookRef(newId, target.absolutePath), result.pageCount)
                                removed = removeFolder(dir, images, stats)
                            }
                        }
                    }
                    val request = Compactor.Request(dir, convert, inPlace = false, suffix = "", cost = cost, placer = placer, target = target, always = true)
                    val result = compactor.run(request, { FolderReader(images) }, progress) as? Compactor.Result.Rebuilt
                        ?: throw IOException("Not packed: ${dir.name}")
                    done++
                    saved += result.before - result.after
                    last = result.file.absolutePath
                    if (result.failed.isNotEmpty()) failedPages[result.file.name] = result.failed
                    if (removed) {
                        forget(plan.folder)
                        gone += plan.folder
                    } else if (failure == null) {
                        failure = IOException("Could not remove ${dir.name}")
                    }
                } catch (e: CancellationException) {
                    return FileWork.Outcome.Canceled(done, plans.size, gone, summary())
                } catch (e: Exception) {
                    if (failure == null) failure = e
                } catch (e: OutOfMemoryError) {
                    if (failure == null) failure = IOException("Out of memory", e)
                }
            }
        } finally {
            scratch.deleteRecursively()
        }
        return failure?.let { FileWork.Outcome.Failed(it, done, plans.size, gone, summary()) }
            ?: FileWork.Outcome.Done(last, gone = gone, count = done, summary = summary())
    }

    /**
     * Where [plan]'s new file goes in [parent], and whether over the file there: as planned,
     * unless that name was taken since, or what is there is no longer what the user chose
     * to replace; then beside it under a free name ("Keep both").
     */
    private fun packTarget(plan: PackPlan, parent: File): Pair<File, Boolean> {
        val there = File(parent, plan.name)
        val now = DeviceSpace.stat(there.absolutePath) ?: return there to false
        if (plan.replace && !now.isDirectory && same(plan.over, now)) return there to true
        return File(parent, Transfer.keepBothName(plan.name, false) { File(parent, it).exists() }) to false
    }

    /**
     * Removes folder [dir] once packed: its [images], each still as it was ([stats], size and
     * time), and its litter, then the folder itself. False, and nothing removed, when an image
     * changed; false, the folder left, when anything else is in it now.
     */
    private fun removeFolder(dir: File, images: List<File>, stats: List<Pair<Long, Long>>): Boolean {
        if (images.indices.any { images[it].length() != stats[it].first || images[it].lastModified() != stats[it].second }) return false
        for (f in images) f.delete()
        dir.listFiles()?.filter { it.isFile && PackFolders.isJunk(it.name) }?.forEach { it.delete() }
        return dir.delete()
    }

    /**
     * Hands the data of books whose new file took their place, the app stopping before
     * their data followed it, to those files; each once.
     */
    fun recover() = synchronized(RECOVERY) {
        for (e in handovers.entries()) {
            val placed = File(e.target).isFile && runCatching { BookIds.peek(File(e.target)) }.getOrNull() == e.new
            if (placed && library.mayHaveData(e.old) && !library.mayHaveData(e.new)) follow(e.book, e.old, e.new, e.target, e.removed, e.moves, e.pageCount)
            handovers.remove(e)
        }
    }

    /**
     * Hands everything kept for the book at [path] (Book ID [old]) to its rebuilt file at
     * [target] (Book ID [new]): book page `i` is now page [moves]`[i]` of [pageCount], its own
     * charset dropped (the new file names its pages in UTF-8). When the book's own file
     * went ([removed]) or was replaced, what is cached for it goes too, unless a copy
     * elsewhere keeps it, and picks waiting follow the new file.
     */
    private fun follow(path: String, old: BookId?, new: BookId, target: String, removed: Boolean, moves: IntArray, pageCount: Int) {
        val replaced = removed || target == path
        if (replaced && old != null && BookIds.elsewhere(setOf(old), path).isEmpty()) BookCaches.forget(app, old)
        if (replaced) BookIds.removed(path)
        val ref = BookRef(new, target)
        if (old != null && old != ref.id) {
            library.carryOver(old, ref, moves, pageCount)
            library.setNameCharset(ref, null)
            if (replaced) LikenessStore(app).remove(listOf(old))
        } else {
            library.seen(ref)
        }
        if (removed) FileClipboard.edit { items -> items.map { if (it.path == path) it.copy(path = target) else it } }
    }

    /** Picks [items] of a move's [clip] up again, as a new pick; a copy's are let go. Returns how many were picked up. */
    fun giveBack(clip: FileClipboard.Clip, items: List<FileClipboard.Item>): Int {
        if (!clip.move || items.isEmpty()) return 0
        FileClipboard.add(clip.copy(items = items, id = FileWork.nextOrder()))
        return items.size
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

    private companion object {
        /** libjxl and the platform's codecs; null where libjxl is missing. */
        fun platformPages(): PageWork? = if (runCatching { LibJxl.available }.getOrDefault(false)) PageWork(LibJxl, AndroidImages) else null

        /** Under the cache: the pieces of a book being rebuilt. */
        const val SCRATCH = "compact"

        /** Under the app's files: books' data on its way to their rebuilt files. */
        const val HANDOVERS = "handovers"

        /** Held while handing data over, so two never hand over one book. */
        val RECOVERY = Any()
        const val SUFFIX_COMPACTED = "compacted"
        const val SUFFIX_COMPATIBLE = "compatible"
    }

    /** Runs [block]; what it threw, else null. */
    private inline fun attempt(block: () -> Unit): Exception? = try {
        block()
        null
    } catch (e: Exception) {
        e
    }
}
