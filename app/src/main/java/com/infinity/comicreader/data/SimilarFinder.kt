package com.infinity.comicreader.data

import com.infinity.comicreader.util.LimitGate
import com.infinity.comicreader.util.SimilarBooks
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Finds books probably alike ([SimilarBooks]) among files: each content once (by [idOf]),
 * its [SimilarStore.Entry] from [store], else [read] by up to [workers] threads, [limit]
 * of them at once (read anew before each book, as the device warms up or saves power),
 * and kept unless the read says not to ([Read.keep]). Free of Android types.
 */
class SimilarFinder(
    private val store: SimilarStore,
    private val idOf: (File) -> BookId?,
    private val read: (File, canceled: () -> Boolean) -> Read?,
    private val workers: Int = WORKERS,
    private val limit: () -> Int = { workers }
) {
    /** What was read of a book: its [entry], and whether to [keep] it, which a read that may go better next time is not. */
    data class Read(val entry: SimilarStore.Entry, val keep: Boolean = true)

    /** One content: its [id], its bit-identical [files] in the order given, and what was read of it. */
    data class Book(val id: BookId, val files: List<File>, val entry: SimilarStore.Entry)

    /** The [groups] found, and how many contents were [compared] of the [total] found. */
    data class Found(val groups: List<List<Book>>, val compared: Int, val total: Int)

    /** The contents among some files: each one's files ([byId], in the order given), and those with nothing kept of them yet ([unread]). */
    class Contents(val byId: Map<BookId, List<File>>, val unread: List<BookId>)

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

    /** [find] over the [contents] of [files], reading every one with nothing kept. */
    fun find(files: List<File>, alive: () -> Boolean = { true }, progress: (Int, Int) -> Unit = { _, _ -> }): Found =
        find(contents(files, alive), alive, progress)

    /**
     * The groups of two or more of [contents] alike, in the order of their first files,
     * with how many could be compared: those with something kept, and with [readNew] the
     * others once read. Starts no read once [alive] turns false, and compares what was
     * read by then. [progress] hears contents done of all to compare, from the reading threads.
     */
    fun find(contents: Contents, alive: () -> Boolean = { true }, progress: (Int, Int) -> Unit = { _, _ -> }, readNew: Boolean = true): Found {
        val byId = contents.byId
        val ids = byId.keys.toList()
        val entries = HashMap<BookId, SimilarStore.Entry>()
        val missing = ArrayList<BookId>()
        for (id in ids) store.get(id)?.let { entries[id] = it } ?: missing.add(id)
        val done = AtomicInteger(entries.size)
        progress(done.get(), ids.size)
        if (readNew && missing.isNotEmpty() && alive()) {
            val pool = Executors.newFixedThreadPool(workers.coerceIn(1, missing.size)) { r ->
                Thread(r, "similar-books").apply {
                    isDaemon = true
                    priority = Thread.NORM_PRIORITY - 1
                }
            }
            val gate = LimitGate(limit)
            try {
                val tasks = missing.map { id ->
                    Callable {
                        gate.pass {
                            if (!alive()) return@pass null
                            val got = runCatching { read(byId.getValue(id).first()) { !alive() } }.getOrNull()
                            if (got != null && got.keep) store.put(id, got.entry)
                            progress(done.incrementAndGet(), ids.size)
                            got?.let { id to it.entry }
                        }
                    }
                }
                for (future in pool.invokeAll(tasks)) runCatching { future.get() }.getOrNull()?.let { entries[it.first] = it.second }
            } finally {
                pool.shutdownNow()
            }
        }
        val books = ids.mapNotNull { id -> entries[id]?.let { Book(id, byId.getValue(id), it) } }
        return Found(SimilarBooks.groups(books) { it.entry.print }, books.size, ids.size)
    }

    companion object {
        /** Books read at once: a few, so the search stays light on battery and heat. */
        const val WORKERS = 3
    }
}
