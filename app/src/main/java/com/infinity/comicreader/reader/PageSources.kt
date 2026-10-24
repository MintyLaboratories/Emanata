package com.infinity.comicreader.reader

import com.infinity.comicreader.data.EntryType
import com.infinity.comicreader.net.RemoteCache
import com.infinity.comicreader.net.RemoteFile
import com.infinity.comicreader.net.Remotes
import com.infinity.comicreader.util.Formats
import java.io.File
import java.io.IOException

/**
 * Chooses the right [PageSource] for a file: images by extension, archives and PDFs
 * by their leading bytes ([ArchiveKind.detect]). Archives inside archives are copied
 * under [cacheDir] ([NestedArchives]). Sources come wrapped in a [GuardedSource], so
 * closing one never cuts off a read in flight, and leave page previews under
 * [cacheDir] as they decode ([PreviewingSource]).
 *
 * A book on a share ([RemoteFile]) is read from its local copy when one is cached
 * ([RemoteCache]). Otherwise archives are read in place, fetching only the parts
 * needed: zip, tar, and rar and 7z that are not solid straight from their entries; a
 * solid one is streamed once, front to back, its pages kept as they are unpacked
 * ([ExtractingReader], [RemoteCache.pagesOf]). Loose images are fetched first, and PDFs
 * too except for covers, which a PDF not fetched yet lacks. On a server that sends
 * files only whole ([Remotes.seekable]), no book is read in place: each is fetched,
 * except for a cover, which it then lacks.
 */
object PageSources {
    /** Fetching a book from a share before reading it: stops once [canceled] turns true; [progress] hears bytes done of the total. */
    class Fetch(val canceled: () -> Boolean = { false }, val progress: (Long, Long) -> Unit = { _, _ -> })

    /**
     * Opens [file]. Nested-archive copying stops once [canceled] turns true; without
     * [copyNested] only cached copies are used, and a book needing a new one fails.
     * [fetch] hears a book on a share fetched whole first, where one must be.
     */
    fun open(
        file: File,
        charsetName: String,
        cacheDir: File,
        canceled: () -> Boolean = { false },
        copyNested: Boolean = true,
        fetch: Fetch? = null
    ): PageSource {
        val nested = NestedArchives(NestedArchives.dirIn(cacheDir), charsetName, canceled = canceled, copy = copyNested)
        val raw = openRaw(file, charsetName, cacheDir, nested, pageLimit = Int.MAX_VALUE, fetch = fetch ?: Fetch(canceled), fetchWhole = true)
        return previewing(file, cacheDir, GuardedSource(raw))
    }

    /**
     * Like [open], but an archive holds only its pages up to [page], the cover's (its first
     * page unless that is hidden): enough for a cover, and cheaper.
     */
    fun openCover(file: File, charsetName: String, cacheDir: File, page: Int = 0): PageSource = previewing(
        file, cacheDir,
        GuardedSource(
            openRaw(
                file, charsetName, cacheDir, NestedArchives(NestedArchives.dirIn(cacheDir), charsetName),
                pageLimit = page.coerceAtLeast(0) + 1, fetch = Fetch(), fetchWhole = false
            )
        )
    )

    /** [source] leaving page previews under [cacheDir] ([PreviewingSource]). */
    private fun previewing(file: File, cacheDir: File, source: PageSource): PageSource =
        PreviewingSource(source, PreviewStore(PreviewStore.dirIn(cacheDir)), PreviewStore.bookId(file))

    private fun openRaw(
        file: File,
        charsetName: String,
        cacheDir: File,
        nested: NestedArchives,
        pageLimit: Int,
        fetch: Fetch,
        fetchWhole: Boolean
    ): PageSource {
        val type = Formats.typeOf(file.name)
        if (file is RemoteFile && (type == EntryType.IMAGE || type == EntryType.PDF || type == EntryType.ARCHIVE)) {
            RemoteCache.cached(file, cacheDir)?.let { return local(it, file, charsetName, nested, pageLimit) }
            if (type == EntryType.PDF && !fetchWhole) throw IOException("Not fetched: ${file.name}")
            // From a server sending files only whole, even sniffing a book's first bytes starts a whole download.
            if (type != EntryType.IMAGE && !Remotes.seekable(file.path)) {
                if (!fetchWhole) throw IOException("Not fetched: ${file.name}")
                return local(RemoteCache.fetch(file, cacheDir, fetch.canceled, fetch.progress), file, charsetName, nested, pageLimit)
            }
            val kind = if (type == EntryType.IMAGE) null else ArchiveKind.detect(file)
            if (kind == ArchiveKind.PDF && !fetchWhole) throw IOException("Not fetched: ${file.name}")
            if (type == EntryType.IMAGE || kind == ArchiveKind.PDF) {
                val copy = RemoteCache.fetch(file, cacheDir, fetch.canceled, fetch.progress)
                return local(copy, file, charsetName, nested, pageLimit)
            }
            return inPlace(file, charsetName, cacheDir, nested, pageLimit, kind ?: throw IOException("Unsupported archive: ${file.name}"))
        }
        return local(file, file, charsetName, nested, pageLimit)
    }

    /** Archive [file] on a share read in place; a solid one through its kept pages ([ExtractingReader]). */
    private fun inPlace(file: RemoteFile, charsetName: String, cacheDir: File, nested: NestedArchives, pageLimit: Int, kind: ArchiveKind): PageSource {
        val opened = ArchiveReader.open(file, charsetName, kind) ?: throw IOException("Unsupported archive: ${file.name}")
        val reader = if (opened.randomAccess) {
            opened
        } else {
            val pages = RemoteCache.pagesOf(file, cacheDir)
            ExtractingReader(opened, pages) { RemoteCache.release(pages, cacheDir) }
        }
        // Failing, the source closes the reader.
        return ArchiveSource(reader, NestedArchives.keyOf(file), nested, pageLimit)
    }

    /** Opens [file], which is [book] or its local copy; nested copies are keyed by [book]. */
    private fun local(file: File, book: File, charsetName: String, nested: NestedArchives, pageLimit: Int): PageSource =
        when (Formats.typeOf(file.name)) {
            EntryType.IMAGE -> ImageSource(file)
            EntryType.PDF, EntryType.ARCHIVE -> {
                val kind = ArchiveKind.detect(file)
                if (kind == ArchiveKind.PDF) PdfSource(file) else archive(file, book, charsetName, nested, pageLimit, kind)
            }
            else -> throw IOException("Unsupported file type: ${file.name}")
        }

    private fun archive(file: File, book: File, charsetName: String, nested: NestedArchives, pageLimit: Int, kind: ArchiveKind?): PageSource {
        val reader = ArchiveReader.open(file, charsetName, kind) ?: throw IOException("Unsupported archive: ${file.name}")
        return ArchiveSource(reader, NestedArchives.keyOf(book), nested, pageLimit)
    }
}
