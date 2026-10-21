package com.infinity.comicreader.reader

import com.infinity.comicreader.data.BookFacts
import com.infinity.comicreader.data.EntryType
import com.infinity.comicreader.util.Formats
import java.io.File

/** Reads what sorting and filtering need from inside a book: its page count and ComicInfo year. */
object BookFactsReader {
    /** Largest ComicInfo.xml read; real ones are a few kilobytes. */
    private const val MAX_COMIC_INFO_BYTES = 256 * 1024

    /**
     * The facts of [file]; pages are -1 when they cannot be counted without copying
     * an inner archive out first. Blocking; throws when the book cannot be opened.
     */
    fun read(file: File, charsetName: String, cacheDir: File): BookFacts = when (Formats.typeOf(file.name)) {
        EntryType.IMAGE -> BookFacts(pages = 1, year = 0)
        EntryType.PDF, EntryType.ARCHIVE -> {
            val kind = ArchiveKind.detect(file)
            if (kind == ArchiveKind.PDF) {
                PdfSource(file).use { BookFacts(it.pageCount, 0) }
            } else {
                val reader = ArchiveReader.open(file, charsetName, kind) ?: throw java.io.IOException("Not an archive")
                reader.use { readArchive(it, file, charsetName, cacheDir) }
            }
        }
        else -> throw java.io.IOException("Not a book")
    }

    /** At most [max] bytes of [input]. */
    private fun readUpTo(input: java.io.InputStream, max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8 * 1024)
        while (out.size() < max) {
            val n = input.read(buf, 0, minOf(buf.size, max - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun readArchive(reader: ArchiveReader, file: File, charsetName: String, cacheDir: File): BookFacts {
        val entries = reader.entries.filter { !ArchiveJunk.isJunk(it.path, it.size) }
        val info = reader.entries.firstOrNull { it.path.substringAfterLast('/').equals(ComicInfo.FILE_NAME, ignoreCase = true) }
        val year = info?.let { entry ->
            runCatching { reader.read(entry) { ComicInfo.year(String(readUpTo(it, MAX_COMIC_INFO_BYTES), Charsets.UTF_8)) } }
                .getOrDefault(0)
        } ?: 0
        val images = entries.count { Formats.isImageName(it.path) }
        val pages = if (entries.any { Formats.isArchiveName(it.path) }) {
            // Inner archives count as chapters; only ones already copied out can be counted.
            runCatching { PageSources.open(file, charsetName, cacheDir, copyNested = false).use { it.pageCount } }.getOrDefault(-1)
        } else {
            images
        }
        return BookFacts(pages, year)
    }
}

/** The parts of a ComicInfo.xml the library uses. */
object ComicInfo {
    const val FILE_NAME = "ComicInfo.xml"

    private val YEAR = Regex("""<Year>\s*(\d{1,4})\s*</Year>""", RegexOption.IGNORE_CASE)

    /** The `<Year>` of [xml], or 0 when it has none that makes sense. */
    fun year(xml: String): Int = YEAR.find(xml)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1800..2200 } ?: 0
}
