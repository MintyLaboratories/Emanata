package com.infinity.comicreader.data

import com.infinity.comicreader.util.LimitGate
import com.infinity.comicreader.util.SimilarBooks
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger

/**
 * Finds books probably alike ([SimilarBooks]) among files: each content once (by [idOf]),
 * its [SimilarStore.Entry] from [store], else [read] by up to [workers] threads, [limit]
 * of them at once (read anew before each book, as the device warms up or saves power),
 * and kept unless the read says not to ([Read.keep]). Books only on a share ([remote])
 * are read beside them by [shareWorkers] threads of their own, as share reads wait on the
 * network more than they work: only their device work, run through the [Pace] given to
 * [read], counts in [limit]. Free of Android types.
 */
class SimilarFinder(
    private val store: SimilarStore,
    private val idOf: (File) -> BookId?,
    private val read: (File, canceled: () -> Boolean, pace: Pace) -> Read?,
    private val workers: Int = WORKERS,
    private val limit: () -> Int = { workers },
    private val remote: (File) -> Boolean = { false },
    private val shareWorkers: Int = SHARE_WORKERS
) {
    /** Runs a read's device work (a page's decode and hash) as heat and battery allow. */
    interface Pace {
        fun <T> run(work: () -> T): T

        companion object {
            /** Runs work at once: the read already holds its turn. */
            val FREE = object : Pace {
                override fun <T> run(work: () -> T): T = work()
            }
        }
    }

    /** What was read of a book: its [entry], and whether to [keep] it, which a read that may go better next time is not. */
    data class Read(val entry: SimilarStore.Entry, val keep: Boolean = true)

    /** One content: its [id], its bit-identical [files] in the order given, and what was read of it. */
    data class Book(val id: BookId, val files: List<File>, val entry: SimilarStore.Entry)

    /**
     * The [groups] found, how many contents were [compared] of the [total] found, and how
     * many were left [unread]: not read, as asked or as the search stopped, its read then
     * under way included (a book that could not be read is neither compared nor unread);
     * [unreadShares] of them only on shares.
     */
    data class Found(val groups: List<List<Book>>, val compared: Int, val total: Int, val unread: Int = 0, val unreadShares: Int = 0)

    /** The contents among some files: each one's files ([byId], in the order given), and those with nothing kept of them yet ([unread]). */
    class Contents(val byId: Map<BookId, List<File>>, val unread: List<BookId>) {
        /** These contents without the files at [paths], and without contents left with none. */
        fun without(paths: Set<String>): Contents {
            if (paths.isEmpty()) return this
            val left = LinkedHashMap<BookId, List<File>>()
            for ((id, files) in byId) files.filterNot { it.absolutePath in paths }.takeIf { it.isNotEmpty() }?.let { left[id] = it }
            return Contents(left, unread.filter { it in left })
        }
    }

    /** The contents among [files], as far as they were told apart before [alive] turned false. */
    fun contents(files: List<File>, alive: () -> Boolean = { true }): Contents {
        val byId = LinkedHashMap<BookId, MutableList<File>>()
        for (f in files) {
            if (!alive()) break
            val id = idOf(f) ?: continue
            byId.getOrPut(id) { ArrayList() } += f
        }
        return Contents(byId, byId.keys.filter { store.get(it) == null })
    }

    /** True when every file of a content is on a share. */
    private fun onShares(files: List<File>): Boolean = files.all(remote)

    /** [find] over the [contents] of [files], reading every one with nothing kept. */
    fun find(files: List<File>, alive: () -> Boolean = { true }, progress: (Int, Int) -> Unit = { _, _ -> }): Found =
        find(contents(files, alive), alive, progress)

    /**
     * The groups of two or more of [contents] alike, in the order of their first files,
     * with how many could be compared: those with something kept, and with [readNew] the
     * others, read first (those only on shares only with [readShares]; of a content on the
     * device and on a share, the device copy). Starts no read once [alive] turns false, nor a
     * share read once [sharesAlive] does for the file it would read, and compares what was read by then. [progress] hears
     * books read of all to read, from the reading threads.
     */
    fun find(
        contents: Contents,
        alive: () -> Boolean = { true },
        progress: (Int, Int) -> Unit = { _, _ -> },
        readNew: Boolean = true,
        readShares: Boolean = true,
        sharesAlive: (File) -> Boolean = { true }
    ): Found {
        val byId = contents.byId
        val ids = byId.keys.toList()
        val entries = HashMap<BookId, SimilarStore.Entry>()
        val missing = ArrayList<BookId>()
        for (id in ids) store.get(id)?.let { entries[id] = it } ?: missing.add(id)
        val (shared, local) = missing.partition { onShares(byId.getValue(it)) }
        val localReads = if (readNew) local else emptyList()
        val shareReads = if (readNew && readShares) shared else emptyList()
        val toRead = localReads.size + shareReads.size
        val done = AtomicInteger()
        val tried = AtomicInteger()
        val triedShares = AtomicInteger()
        progress(0, toRead)
        if (toRead > 0 && alive()) {
            val gate = LimitGate(limit)
            val gated = object : Pace {
                override fun <T> run(work: () -> T): T = gate.pass(work)
            }
            fun task(id: BookId, onShare: Boolean) = Callable {
                val files = byId.getValue(id)
                val file = files.firstOrNull { !remote(it) } ?: files.first()
                val going = if (onShare) { -> alive() && sharesAlive(file) } else alive
                val readOne = readOne@{
                    if (!going()) return@readOne null
                    tried.incrementAndGet()
                    if (onShare) triedShares.incrementAndGet()
                    val got = runCatching { read(file, { !going() }, if (onShare) gated else Pace.FREE) }.getOrNull()
                    if (got == null && !going()) {
                        tried.decrementAndGet()
                        if (onShare) triedShares.decrementAndGet()
                    }
                    if (got != null && got.keep) store.put(id, got.entry)
                    progress(done.incrementAndGet(), toRead)
                    got?.let { id to it.entry }
                }
                // Share reads wait on the network: only their pages' device work waits for a turn.
                if (onShare) readOne() else gate.pass(readOne)
            }
            val pools = ArrayList<ExecutorService>()
            try {
                val futures = ArrayList<Future<Pair<BookId, SimilarStore.Entry>?>>()
                for ((batch, onShare, threads) in listOf(Triple(localReads, false, workers), Triple(shareReads, true, shareWorkers))) {
                    if (batch.isEmpty()) continue
                    val pool = pool(threads.coerceIn(1, batch.size)).also { pools += it }
                    batch.mapTo(futures) { pool.submit(task(it, onShare)) }
                }
                for (future in futures) runCatching { future.get() }.getOrNull()?.let { entries[it.first] = it.second }
            } finally {
                pools.forEach { it.shutdownNow() }
            }
        }
        val books = ids.mapNotNull { id -> entries[id]?.let { Book(id, byId.getValue(id), it) } }
        return Found(
            SimilarBooks.groups(books) { it.entry.print }, books.size, ids.size, missing.size - tried.get(), shared.size - triedShares.get()
        )
    }

    /** [threads] reading threads, below the screen's priority. */
    private fun pool(threads: Int): ExecutorService = Executors.newFixedThreadPool(threads) { r ->
        Thread(r, "similar-books").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
        }
    }

    companion object {
        /** Books read at once: a few, so the search stays light on battery and heat. */
        const val WORKERS = 3

        /** Books on shares read at once: share reads are slow, and one at a time keeps Stop quick. */
        const val SHARE_WORKERS = 1
    }
}
