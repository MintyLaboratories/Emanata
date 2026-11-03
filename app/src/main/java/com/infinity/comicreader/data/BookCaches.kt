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
 * listings and copies of nested archives. Library data (favorites, read marks, history,
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
        return dirs(app.cacheDir).sumOf { CacheFiles.bytesUnder(it) } + files.filter { it.isFile }.sumOf { CacheFiles.onDisk(it) }
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
     * Deletes what is cached for book [id] alone, as when it is deleted: its facts, network
     * copy, page sizes, seams, previews, pages processed by AI and nested archives. Blocking.
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
    }
}
