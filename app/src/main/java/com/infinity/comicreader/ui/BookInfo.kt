package com.infinity.comicreader.ui

import com.infinity.comicreader.reader.ArchiveKind
import com.infinity.comicreader.util.Formats
import java.util.Locale

/** What the reader's Book info says of a book's file. */
object BookInfo {
    /** [kind]'s usual name. */
    fun kindName(kind: ArchiveKind): String = when (kind) {
        ArchiveKind.ZIP -> "ZIP"
        ArchiveKind.RAR -> "RAR"
        ArchiveKind.SEVEN_ZIP -> "7z"
        ArchiveKind.TAR -> "TAR"
        ArchiveKind.PDF -> "PDF"
    }

    /**
     * The format of the book [fileName]: its extension, and what its leading bytes say it
     * is ([kind]) when that is another name, as in "CBR (ZIP)" for a zip named `.cbr`.
     */
    fun format(fileName: String, kind: ArchiveKind?): String {
        val ext = Formats.ext(fileName).uppercase(Locale.ROOT)
        val actual = kind?.let { kindName(it) } ?: return ext
        return when {
            ext.isEmpty() -> actual
            ext.equals(actual, ignoreCase = true) -> ext
            else -> "$ext ($actual)"
        }
    }
}
