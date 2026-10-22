package com.infinity.comicreader.reader

import com.infinity.comicreader.data.EntryType
import com.infinity.comicreader.net.RemoteCache
import com.infinity.comicreader.net.RemoteFile
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
 * ([RemoteCache]). Otherwise a zip is read in place; loose images are fetched
 * first, and PDFs too except for covers, which a PDF not fetched yet lacks; rar, 7z
 * and tar books are fetched when a [Fetch] asks for it (the reader, where reading in
 * place would unpack solid blocks over and over), else read in place too (covers,
 * peeks, page counts).
 */
object PageSources {
    /** Fetching a book from a share before reading it: stops once [canceled] turns true; [progress] hears bytes done of the total. */
    class Fetch(val canceled: () -> Boolean = { false }, val progress: (Long, Long) -> Unit = { _, _ -> })

    /**
     * Opens [file]. Nested-archive copying stops once [canceled] turns true; without
     * [copyNested] only cached copies are used, and a book needing a new one fails.
     * With [fetch], a rar, 7z or tar book on a share is fetched whole first.
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
        val raw = openRaw(
            file, charsetName, cacheDir, nested,
            firstPageOnly = false, fetch = fetch ?: Fetch(canceled), fetchArchives = fetch != null, fetchPdf = true
        )
        return previewing(file, cacheDir, GuardedSource(raw))
    }

    /** Like [open], but an archive holds only its first page: enough for a cover, and cheaper. */
    fun openCover(file: File, charsetName: String, cacheDir: File): PageSource = previewing(
        file, cacheDir,
        GuardedSource(
            openRaw(
                file, charsetName, cacheDir, NestedArchives(NestedArchives.dirIn(cacheDir), charsetName),
                firstPageOnly = true, fetch = Fetch(), fetchArchives = false, fetchPdf = false
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
        firstPageOnly: Boolean,
        fetch: Fetch,
        fetchArchives: Boolean,
        fetchPdf: Boolean
    ): PageSource {
        val type = Formats.typeOf(file.name)
        if (file is RemoteFile && (type == EntryType.IMAGE || type == EntryType.PDF || type == EntryType.ARCHIVE)) {
            RemoteCache.cached(file, cacheDir)?.let { return local(it, file, charsetName, nested, firstPageOnly) }
            if (type == EntryType.PDF && !fetchPdf) throw IOException("Not fetched: ${file.name}")
            val kind = if (type == EntryType.IMAGE) null else ArchiveKind.detect(file)
            if (kind == ArchiveKind.PDF && !fetchPdf) throw IOException("Not fetched: ${file.name}")
            val inPlace = kind == ArchiveKind.ZIP || (kind != null && kind != ArchiveKind.PDF && !fetchArchives)
            if (!inPlace) {
                val copy = RemoteCache.fetch(file, cacheDir, fetch.canceled, fetch.progress)
                return local(copy, file, charsetName, nested, firstPageOnly)
            }
            return archive(file, file, charsetName, nested, firstPageOnly, kind)
        }
        return local(file, file, charsetName, nested, firstPageOnly)
    }

    /** Opens [file], which is [book] or its local copy; nested copies are keyed by [book]. */
    private fun local(file: File, book: File, charsetName: String, nested: NestedArchives, firstPageOnly: Boolean): PageSource =
        when (Formats.typeOf(file.name)) {
            EntryType.IMAGE -> ImageSource(file)
            EntryType.PDF, EntryType.ARCHIVE -> {
                val kind = ArchiveKind.detect(file)
                if (kind == ArchiveKind.PDF) PdfSource(file) else archive(file, book, charsetName, nested, firstPageOnly, kind)
            }
            else -> throw IOException("Unsupported file type: ${file.name}")
        }

    private fun archive(file: File, book: File, charsetName: String, nested: NestedArchives, firstPageOnly: Boolean, kind: ArchiveKind?): PageSource {
        val reader = ArchiveReader.open(file, charsetName, kind) ?: throw IOException("Unsupported archive: ${file.name}")
        return ArchiveSource(reader, NestedArchives.keyOf(book), nested, firstPageOnly)
    }
}
