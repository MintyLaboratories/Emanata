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

    /** False for a solid archive, where each entry unpacks those stored before it. */
    override val randomAccess: Boolean = runCatching { !archive.mainHeader.isSolid }.getOrDefault(false)

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
     * concurrently, so access is serialized here. junrar unpacks on a thread of its own,
     * so an entry [block] reads only the start of is read to its end before the next
     * read may begin.
     */
    @Synchronized
    override fun <T> read(entry: ArchiveEntry, block: (InputStream) -> T): T =
        archive.getInputStream(native[entry.index]).use { input ->
            try {
                block(input)
            } finally {
                runCatching {
                    val sink = ByteArray(DRAIN_BYTES)
                    while (input.read(sink) >= 0) Unit
                }
            }
        }

    override fun close() {
        runCatching { archive.close() }
    }

    private companion object {
        const val DRAIN_BYTES = 64 * 1024
    }
}
