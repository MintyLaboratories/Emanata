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

    /** The container the lowercase extension [ext] promises, or null for one that promises none. */
    fun promised(ext: String): ArchiveKind? = when (ext) {
        "zip", "cbz" -> ArchiveKind.ZIP
        "rar", "cbr" -> ArchiveKind.RAR
        "7z", "cb7" -> ArchiveKind.SEVEN_ZIP
        "tar", "cbt" -> ArchiveKind.TAR
        "pdf" -> ArchiveKind.PDF
        else -> null
    }

    /**
     * The format of the book [fileName]: its extension, and what its leading bytes say it
     * is ([kind]) when that is another name, as in "CBZ (ZIP)". An extension promising
     * another container is shown through [mismatch] (extension, then container), as in
     * "CBR (actually ZIP)".
     */
    fun format(fileName: String, kind: ArchiveKind?, mismatch: String): String {
        val ext = Formats.ext(fileName).lowercase(Locale.ROOT)
        val shown = ext.uppercase(Locale.ROOT)
        val actual = kind?.let { kindName(it) } ?: return shown
        val promised = promised(ext)
        return when {
            ext.isEmpty() -> actual
            ext.equals(actual, ignoreCase = true) -> actual
            promised != null && promised != kind -> String.format(Locale.ROOT, mismatch, shown, actual)
            else -> "$shown ($actual)"
        }
    }
}
