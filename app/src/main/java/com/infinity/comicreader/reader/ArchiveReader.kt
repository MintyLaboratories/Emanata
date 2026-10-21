package com.infinity.comicreader.reader

import java.io.Closeable
import java.io.File
import java.io.InputStream

/** A file inside an archive: its [path] in the archive, its uncompressed [size] (-1 when unknown). */
class ArchiveEntry(val path: String, val size: Long, internal val index: Int)

/** Lists the files of one archive and streams their contents. */
interface ArchiveReader : Closeable {
    /** Files only; directories are left out. */
    val entries: List<ArchiveEntry>

    /** Runs [block] on the contents of [entry]; implementations serialize this where the backend needs it. */
    fun <T> read(entry: ArchiveEntry, block: (InputStream) -> T): T

    companion object {
        /** Reader for [file] as [kind] (by default from its leading bytes), or null when it is not an archive. */
        fun open(
            file: File,
            charsetName: String,
            kind: ArchiveKind? = ArchiveKind.detect(file)
        ): ArchiveReader? = when (kind) {
            ArchiveKind.ZIP -> ZipReader(file, charsetName)
            ArchiveKind.RAR -> RarReader(file)
            ArchiveKind.SEVEN_ZIP -> SevenZipReader(file)
            ArchiveKind.TAR -> TarReader(file, charsetName)
            ArchiveKind.PDF, null -> null
        }
    }
}
