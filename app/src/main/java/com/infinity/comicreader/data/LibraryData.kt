package com.infinity.comicreader.data

import android.content.Context
import com.infinity.comicreader.files.BookUris

/**
 * What the app keeps that cannot be made again, beyond what [Library] holds: each book's
 * [Likeness] and the links of books shared. Cleared and forgotten together with it.
 */
object LibraryData {
    /**
     * Clears the library data ([Library.clearLibraryData]: books' and folders', not reading
     * statistics), the books' likenesses and the links of books shared (but those open now).
     * Blocking.
     */
    fun clear(context: Context, library: Library) {
        library.clearLibraryData()
        LikenessStore(context).clear()
        BookUris.forgetKeys(context)
    }

    /** Forgets everything kept for books [ids] ([Library.forgetBooks]), their likenesses and what is cached for them. Blocking. */
    fun forget(context: Context, library: Library, ids: Set<BookId>) {
        library.forgetBooks(ids)
        LikenessStore(context).remove(ids)
        for (id in ids) BookCaches.forget(context, id)
    }
}
