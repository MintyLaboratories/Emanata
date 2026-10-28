package com.infinity.comicreader.testing

import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.data.BookId
import com.infinity.comicreader.data.BookIds
import com.infinity.comicreader.data.BookRef
import com.infinity.comicreader.data.Library
import java.io.File

/** The Book ID of [file], found now and remembered by the app's index, as when the app had seen it. */
fun bookId(file: File): BookId {
    BookIds.init(ApplicationProvider.getApplicationContext())
    return BookIds.of(file)
}

/** Book [file] as the library keeps it. */
fun ref(file: File): BookRef = BookRef(bookId(file), file.absolutePath)

/** Adds [file], a folder or a book, to the favorites, or removes it. */
fun Library.toggleFavorite(file: File): Boolean =
    if (file.isDirectory) toggleFavoriteFolder(file.absolutePath) else toggleFavorite(ref(file))
