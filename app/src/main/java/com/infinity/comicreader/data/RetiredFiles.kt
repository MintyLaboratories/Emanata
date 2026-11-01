package com.infinity.comicreader.data

import android.content.Context
import com.infinity.comicreader.ai.ModelStore
import java.io.File

/** Storage that earlier versions wrote and nothing reads any more. */
object RetiredFiles {
    /** Preferences files replaced by other storage: `book_facts` moved to [BookFactsCache]'s database. */
    internal val PREFS = listOf("book_facts")

    /**
     * Cache folders of covers and page previews made before AVIF decoded through libavif,
     * some with wrong colors; and of page sizes and seams kept by path before Book IDs.
     */
    internal val CACHE_DIRS = listOf("thumbnails", "previews", "page-sizes", "page-seams")

    /**
     * Deletes every retired file still present, and AI models the catalog no longer has (they
     * can no longer be chosen); does nothing once they are gone. Blocking.
     */
    fun drop(context: Context) {
        for (name in PREFS) context.deleteSharedPreferences(name)
        for (name in CACHE_DIRS) File(context.cacheDir, name).deleteRecursively()
        ModelStore.of(context).prune()
    }
}
