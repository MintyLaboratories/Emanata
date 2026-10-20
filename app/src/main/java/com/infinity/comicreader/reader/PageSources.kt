package com.infinity.comicreader.reader

import com.infinity.comicreader.data.EntryType
import com.infinity.comicreader.util.Formats
import java.io.File
import java.io.IOException

/**
 * Chooses the right [PageSource] for a file: images by extension, archives and PDFs
 * by their leading bytes ([ArchiveKind.detect]). Sources come wrapped in a
 * [GuardedSource], so closing one never cuts off a read in flight.
 */
object PageSources {
    fun open(file: File, charsetName: String): PageSource = GuardedSource(openRaw(file, charsetName))

    private fun openRaw(file: File, charsetName: String): PageSource = when (Formats.typeOf(file.name)) {
        EntryType.IMAGE -> ImageSource(file)
        EntryType.PDF, EntryType.ARCHIVE -> {
            val kind = ArchiveKind.detect(file)
            if (kind == ArchiveKind.PDF) {
                PdfSource(file)
            } else {
                val reader = ArchiveReader.open(file, charsetName, kind)
                    ?: throw IOException("Unsupported archive: ${file.name}")
                ArchiveSource(reader)
            }
        }
        else -> throw IOException("Unsupported file type: ${file.name}")
    }
}
