package com.infinity.comicreader.data

import com.infinity.comicreader.util.SimilarBooks
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Finds books probably alike ([SimilarBooks]) among files: each content once (by [idOf]),
 * its [SimilarStore.Entry] from [store], else [read] by up to [workers] threads at once
 * and kept. Free of Android types.
 */
class SimilarFinder(
    private val store: SimilarStore,
    private val idOf: (File) -> BookId?,
    private val read: (File, canceled: () -> Boolean) -> SimilarStore.Entry?,
    private val workers: Int = WORKERS
) {
    /** One content: its [id], its bit-identical [files] in the order given, and what was read of it. */
    data class Book(val id: BookId, val files: List<File>, val entry: SimilarStore.Entry)

    /**
     * The groups of two or more contents alike among [files], in the order of their first
     * files. Starts no read once [alive] turns false, and compares what was read by then.
     * [progress] hears contents done of all to compare, from the reading threads.
     */
    fun find(files: List<File>, alive: () -> Boolean = { true }, progress: (Int, Int) -> Unit = { _, _ -> }): List<List<Book>> {
        val byId = LinkedHashMap<BookId, MutableList<File>>()
        for (f in files) {
            if (!alive()) break
            val id = idOf(f) ?: continue
            byId.getOrPut(id) { ArrayList() } += f
        }
        val ids = byId.keys.toList()
        val entries = HashMap<BookId, SimilarStore.Entry>()
        val missing = ArrayList<BookId>()
        for (id in ids) store.get(id)?.let { entries[id] = it } ?: missing.add(id)
        val done = AtomicInteger(entries.size)
        progress(done.get(), ids.size)
        if (missing.isNotEmpty() && alive()) {
            val pool = Executors.newFixedThreadPool(workers.coerceIn(1, missing.size)) { r ->
                Thread(r, "similar-books").apply {
                    isDaemon = true
                    priority = Thread.NORM_PRIORITY - 1
                }
            }
            try {
                val tasks = missing.map { id ->
                    Callable {
                        if (!alive()) return@Callable null
                        val entry = runCatching { read(byId.getValue(id).first()) { !alive() } }.getOrNull()
                        if (entry != null) store.put(id, entry)
                        progress(done.incrementAndGet(), ids.size)
                        entry?.let { id to it }
                    }
                }
                for (future in pool.invokeAll(tasks)) runCatching { future.get() }.getOrNull()?.let { entries[it.first] = it.second }
            } finally {
                pool.shutdownNow()
            }
        }
        val books = ids.mapNotNull { id -> entries[id]?.let { Book(id, byId.getValue(id), it) } }
        return SimilarBooks.groups(books) { it.entry.print }
    }

    companion object {
        /** Books read at once: a few, so the search stays light on battery and heat. */
        const val WORKERS = 3
    }
}
