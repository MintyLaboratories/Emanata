package com.infinity.comicreader.reader

import com.github.junrar.Archive
import com.github.junrar.rarfile.FileHeader
import java.io.File
import java.io.InputStream

/** Reads a rar/cbr archive, RAR4 or RAR5 (via junrar). */
class RarReader(file: File) : ArchiveReader {
    private val archive = Archive(file)

    private val native: List<FileHeader>

    override val entries: List<ArchiveEntry>

    override val randomAccess: Boolean get() = false

    init {
        try {
            native = archive.fileHeaders.filter { !it.isDirectory }
            entries = native.mapIndexed { i, h -> ArchiveEntry(h.fileName, h.fullUnpackSize, i) }
        } catch (t: Throwable) {
            runCatching { archive.close() }
            throw t
        }
    }

    /**
     * junrar's [Archive] is not thread-safe; the adapter loads neighbors
     * concurrently, so access is serialized here.
     */
    @Synchronized
    override fun <T> read(entry: ArchiveEntry, block: (InputStream) -> T): T =
        archive.getInputStream(native[entry.index]).use(block)

    override fun close() {
        runCatching { archive.close() }
    }
}
