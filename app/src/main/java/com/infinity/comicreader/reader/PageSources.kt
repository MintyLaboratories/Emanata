package com.infinity.comicreader.reader

import com.infinity.comicreader.data.EntryType
import com.infinity.comicreader.util.Formats
import java.io.File
import java.io.IOException

/**
 * Chooses the right [PageSource] for a file: images by extension, archives and PDFs
 * by their leading bytes ([ArchiveKind.detect]). Archives inside archives are copied
 * under [cacheDir] ([NestedArchives]). Sources come wrapped in a [GuardedSource], so
 * closing one never cuts off a read in flight, and leave page previews under
 * [cacheDir] as they decode ([PreviewingSource]).
 */
object PageSources {
    /**
     * Opens [file]. Nested-archive copying stops once [canceled] turns true; without
     * [copyNested] only cached copies are used, and a book needing a new one fails.
     */
    fun open(
        file: File,
        charsetName: String,
        cacheDir: File,
        canceled: () -> Boolean = { false },
        copyNested: Boolean = true
    ): PageSource {
        val nested = NestedArchives(NestedArchives.dirIn(cacheDir), charsetName, canceled = canceled, copy = copyNested)
        return previewing(file, cacheDir, GuardedSource(openRaw(file, charsetName, nested, firstPageOnly = false)))
    }

    /** Like [open], but an archive holds only its first page: enough for a cover, and cheaper. */
    fun openCover(file: File, charsetName: String, cacheDir: File): PageSource = previewing(
        file, cacheDir, GuardedSource(openRaw(file, charsetName, NestedArchives(NestedArchives.dirIn(cacheDir), charsetName), firstPageOnly = true))
    )

    /** [source] leaving page previews under [cacheDir] ([PreviewingSource]). */
    private fun previewing(file: File, cacheDir: File, source: PageSource): PageSource =
        PreviewingSource(source, PreviewStore(PreviewStore.dirIn(cacheDir)), PreviewStore.bookId(file))

    private fun openRaw(file: File, charsetName: String, nested: NestedArchives, firstPageOnly: Boolean): PageSource =
        when (Formats.typeOf(file.name)) {
            EntryType.IMAGE -> ImageSource(file)
            EntryType.PDF, EntryType.ARCHIVE -> {
                val kind = ArchiveKind.detect(file)
                if (kind == ArchiveKind.PDF) {
                    PdfSource(file)
                } else {
                    val reader = ArchiveReader.open(file, charsetName, kind)
                        ?: throw IOException("Unsupported archive: ${file.name}")
                    ArchiveSource(reader, NestedArchives.keyOf(file), nested, firstPageOnly)
                }
            }
            else -> throw IOException("Unsupported file type: ${file.name}")
        }
}
