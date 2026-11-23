package com.infinity.comicreader.data

import android.content.Context
import com.infinity.comicreader.ai.ProcessedStore
import com.infinity.comicreader.net.FileEnds
import com.infinity.comicreader.net.Listings
import com.infinity.comicreader.net.RemoteCache
import com.infinity.comicreader.net.Remotes
import com.infinity.comicreader.reader.NestedArchives
import com.infinity.comicreader.reader.PageSeams
import com.infinity.comicreader.reader.PageSizes
import com.infinity.comicreader.reader.PreviewStore
import com.infinity.comicreader.util.CacheFiles
import java.io.File

/**
 * What the app keeps that it can make again ("Clear cache"): covers, page previews, pages
 * processed by AI, page sizes, seams and pages seen, book facts, the Book ID index, network copies, share
 * listings, copies of nested archives and what the similar-books search read of books. Library data (favorites, read marks, history,
 * bookmarks, hidden pages, books' own settings) is never part of it.
 */
object BookCaches {
    /** Databases of what can be read again from the books, each with its journal. */
    private val DATABASES = listOf("book_facts.db", "book_ids.db")

    private fun dirs(cacheDir: File): List<File> = listOf(
        Thumbs.dirIn(cacheDir),
        PreviewStore.dirIn(cacheDir),
        ProcessedStore.dirIn(cacheDir),
        PageSizes.dirIn(cacheDir),
        PageSeams.dirIn(cacheDir),
        RemoteCache.dirIn(cacheDir),
        RemoteCache.pagesIn(cacheDir),
        Listings.dirIn(cacheDir),
        NestedArchives.dirIn(cacheDir)
    )

    /** The bytes the cache takes on disk. Blocking: walks every folder of it. */
    fun bytes(context: Context): Long {
        val app = context.applicationContext
        val files = DATABASES.flatMap { db -> listOf(db, "$db-journal", "$db-wal").map { app.getDatabasePath(it) } }
        val loose = files + SimilarStore.fileIn(app.cacheDir)
        return dirs(app.cacheDir).sumOf { CacheFiles.bytesUnder(it) } + loose.filter { it.isFile }.sumOf { CacheFiles.onDisk(it) }
    }

    /**
     * Deletes everything cached; copies in use now (an open book's network copy or nested
     * archives) stay until let go. Each is made again when next needed. Blocking.
     */
    fun clear(context: Context) {
        val app = context.applicationContext
        val cacheDir = app.cacheDir
        Thumbs.clear(app)
        PreviewStore.clear(PreviewStore.dirIn(cacheDir))
        ProcessedStore.clear(ProcessedStore.dirIn(cacheDir))
        PageSizes.dirIn(cacheDir).deleteRecursively()
        PageSeams.dirIn(cacheDir).deleteRecursively()
        BookFactsCache(app).clear()
        BookIds.init(app)
        BookIds.clear()
        FileEnds.clear()
        RemoteCache.trim(RemoteCache.dirIn(cacheDir), maxBytes = 0)
        Remotes.clearListings()
        Listings.dirIn(cacheDir).deleteRecursively()
        NestedArchives.trim(NestedArchives.dirIn(cacheDir), maxBytes = 0)
        SimilarStore.of(cacheDir).clear()
    }

    /**
     * After the app deleted [path], a file or folder: forgets what [library] keeps there
     * ([Library.removePathsUnder]), and what is cached for the books there ([forget]),
     * unless a copy elsewhere keeps them. Blocking.
     */
    fun deleted(context: Context, library: Library, path: String) {
        val known = BookIds.knownUnder(path)
        val books = known - BookIds.elsewhere(known, path).keys
        library.removePathsUnder(path)
        for (id in books) forget(context, id)
    }

    /**
     * The pages of a book found byte-identical in its rebuilt file: book pages [pages]
     * (ascending), the pages named as the reader lists them in the book ([oldNames]) and in
     * the new file ([newNames]).
     */
    class SamePages(val pages: IntArray, val oldNames: List<String>, val newNames: List<String>)

    /**
     * Hands what is cached for the pages of book [old] that [same] finds byte-identical in its
     * rebuilt file, book [new], to that file, book page `i` as new page [moves]`[i]`: their
     * sizes, seams, pages seen and previews; the book's facts too when every page is
     * identical, in the same order. With [move] [old] keeps none of what was handed over,
     * else all. Blocking.
     */
    fun carry(context: Context, old: BookId, new: BookId, moves: IntArray, same: SamePages, move: Boolean) {
        val app = context.applicationContext
        val cacheDir = app.cacheDir
        val count = same.oldNames.size
        if (moves.size != count) return
        val map = IntArray(count) { -1 }
        for (i in same.pages) if (i in map.indices && moves[i] in same.newNames.indices) map[i] = moves[i]
        val from = keyOf(old)
        val to = keyOf(new)
        PageSizes(PageSizes.dirIn(cacheDir)).carry(from, same.oldNames, to, same.newNames, map, move)
        PageSeams(PageSeams.dirIn(cacheDir)).carry(from, same.oldNames, to, same.newNames, map, move)
        PreviewStore.carry(PreviewStore.dirIn(cacheDir), from, same.oldNames, to, same.newNames, map, move)
        if (count == same.newNames.size && map.indices.all { map[it] == it }) BookFactsCache(app).carry(old, new, move)
    }

    /** The cache key of book [id]'s contents, as [BookIds.keyOf] makes it. */
    private fun keyOf(id: BookId) = "id:${id.hex}"

    /**
     * Deletes what is cached for book [id] alone, as when it is deleted: its facts, network
     * copy, page sizes, seams, previews, pages processed by AI, nested archives and what the
     * similar-books search read of it. Blocking.
     */
    fun forget(context: Context, id: BookId) {
        val app = context.applicationContext
        val cacheDir = app.cacheDir
        BookFactsCache(app).remove(id)
        RemoteCache.forget(id, cacheDir)
        PageSizes.forget(PageSizes.dirIn(cacheDir), id)
        PageSeams.forget(PageSeams.dirIn(cacheDir), id)
        PreviewStore.forget(PreviewStore.dirIn(cacheDir), id)
        ProcessedStore.forget(ProcessedStore.dirIn(cacheDir), id)
        NestedArchives.forget(NestedArchives.dirIn(cacheDir), id)
        SimilarStore.of(cacheDir).forget(id)
    }
}
