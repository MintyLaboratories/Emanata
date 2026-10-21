package com.infinity.comicreader.reader

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarFile
import java.io.File
import java.io.InputStream

/**
 * Reads a tar/cbt archive (via Apache Commons Compress). [TarFile] indexes the
 * headers once and then seeks straight to an entry's data, so pages open in any order.
 */
class TarReader(file: File, charsetName: String) : ArchiveReader {
    private val tar: TarFile = runCatching { TarFile(file, charsetName) }.getOrElse { TarFile(file) }

    private val native: List<TarArchiveEntry>

    override val entries: List<ArchiveEntry>

    init {
        try {
            native = tar.entries.filter { it.isFile }
            entries = native.mapIndexed { i, e -> ArchiveEntry(e.name, e.size, i) }
        } catch (t: Throwable) {
            runCatching { tar.close() }
            throw t
        }
    }

    /** Entry streams share the file's channel position, so reads are serialized. */
    @Synchronized
    override fun <T> read(entry: ArchiveEntry, block: (InputStream) -> T): T =
        tar.getInputStream(native[entry.index]).use(block)

    override fun close() {
        runCatching { tar.close() }
    }
}
