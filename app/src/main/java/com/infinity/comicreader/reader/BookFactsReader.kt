package com.infinity.comicreader.reader

import com.infinity.comicreader.data.BookFacts
import com.infinity.comicreader.data.EntryType
import com.infinity.comicreader.net.RemoteCache
import com.infinity.comicreader.net.RemoteFile
import com.infinity.comicreader.util.Formats
import com.infinity.comicreader.util.NaturalOrder
import java.io.File
import java.io.IOException

/**
 * Reads what sorting and filtering need from inside a book: its page count, ComicInfo
 * year, the reading direction its contents tell ([DirectionDetection], without the
 * cover, which would take a decode) and whether its pages' headers tell slices ([Slices]).
 */
object BookFactsReader {
    /**
     * The facts of [file]; pages are -1 when they cannot be counted without copying
     * an inner archive out first. Blocking; throws when the book cannot be opened.
     */
    fun read(file: File, charsetName: String, cacheDir: File): BookFacts = when (Formats.typeOf(file.name)) {
        EntryType.IMAGE -> BookFacts(pages = 1, year = 0)
        EntryType.PDF, EntryType.ARCHIVE -> {
            val kind = ArchiveKind.detect(file)
            if (kind == ArchiveKind.PDF) {
                // A PDF on a share is counted only once fetched for reading.
                val local = if (file is RemoteFile) RemoteCache.cached(file, cacheDir) ?: throw IOException("Not fetched") else file
                PdfSource(local).use { BookFacts(it.pageCount, 0, DirectionDetection.detect(null, file.name, emptyList())) }
            } else {
                // A book on a share is read from its local copy when there is one: on a server
                // sending files only whole, that is the only way.
                val source = if (file is RemoteFile) RemoteCache.cached(file, cacheDir) ?: file else file
                val reader = ArchiveReader.open(source, charsetName, kind) ?: throw IOException("Not an archive")
                reader.use { readArchive(it, file, charsetName, cacheDir) }
            }
        }
        else -> throw IOException("Not a book")
    }

    private fun readArchive(reader: ArchiveReader, file: File, charsetName: String, cacheDir: File): BookFacts {
        val entries = reader.entries.filter { !ArchiveJunk.isJunk(it.path, it.size) }
        val info = ComicInfo.read(reader)
        val images = entries.filter { Formats.isImageName(it.path) }
        val nested = entries.any { Formats.isArchiveName(it.path) }
        val pages = if (nested) {
            // Inner archives count as chapters; only ones already copied out can be counted,
            // else ComicInfo's own count stands in.
            runCatching { PageSources.open(file, charsetName, cacheDir, copyNested = false).use { it.pageCount } }.getOrDefault(-1)
                .takeIf { it >= 0 } ?: info?.pageCount?.takeIf { it > 0 } ?: -1
        } else {
            images.size
        }
        // Inner archives' names carry tags as page names do.
        val names = entries.filter { Formats.isImageName(it.path) || Formats.isArchiveName(it.path) }.map { it.path }
        val slices = if (nested) null else runCatching { slicesOf(reader, images) }.getOrNull()
        return BookFacts(pages, info?.year ?: 0, DirectionDetection.detect(info, file.name, names), slices)
    }

    /**
     * What the headers of the first [images], in page order, settle of slices
     * ([Slices.settled]); null when the seams must tell, or where reading an entry
     * unpacks those before it.
     */
    private fun slicesOf(reader: ArchiveReader, images: List<ArchiveEntry>): Slices.Checked? {
        if (!reader.randomAccess) return null
        val sizes = ArrayList<IntArray?>()
        for (entry in images.sortedWith(compareBy(NaturalOrder) { it.path }).take(Slices.SAMPLE)) {
            if (Slices.ordinary(sizes)) break
            sizes += runCatching { reader.read(entry) { Decoders.headerSize(it) } }.getOrNull()
        }
        return Slices.settled(sizes)
    }
}
