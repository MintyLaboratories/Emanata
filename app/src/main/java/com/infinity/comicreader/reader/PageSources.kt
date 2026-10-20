package com.infinity.comicreader.reader

import com.infinity.comicreader.data.EntryType
import com.infinity.comicreader.util.Formats
import java.io.File
import java.io.IOException

/**
 * Chooses the right [PageSource] for a file based on its extension. Sources come
 * wrapped in a [GuardedSource], so closing one never cuts off a read in flight.
 */
object PageSources {
    fun open(file: File, charsetName: String): PageSource = GuardedSource(openRaw(file, charsetName))

    private fun openRaw(file: File, charsetName: String): PageSource = when (Formats.typeOf(file.name)) {
        EntryType.IMAGE -> ImageSource(file)
        EntryType.PDF -> PdfSource(file)
        EntryType.ARCHIVE -> {
            val ext = Formats.ext(file.name)
            when {
                ext in Formats.ZIP -> ZipSource(file, charsetName)
                ext in Formats.RAR -> RarSource(file)
                ext in Formats.SEVEN_ZIP -> SevenZipSource(file)
                else -> throw IOException("Unsupported archive: $ext")
            }
        }
        else -> throw IOException("Unsupported file type: ${file.name}")
    }
}
