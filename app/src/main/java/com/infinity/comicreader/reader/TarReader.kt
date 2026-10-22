package com.infinity.comicreader.reader

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.archivers.tar.TarFile
import java.io.File
import java.io.InputStream
import java.nio.channels.SeekableByteChannel

/**
 * Reads a tar/cbt archive (via Apache Commons Compress). [TarFile] indexes the
 * headers once and then seeks straight to an entry's data, so pages open in any order.
 */
class TarReader(private val tar: TarFile) : ArchiveReader {
    constructor(file: File, charsetName: String) : this(tarFile(file, charsetName))

    /** Reads the tar from [channel], which it closes. */
    constructor(channel: SeekableByteChannel, charsetName: String) : this(
        TarFile(channel, TarConstants.DEFAULT_BLKSIZE, TarConstants.DEFAULT_RCDSIZE, charsetName, false)
    )

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

    private companion object {
        fun tarFile(file: File, charsetName: String): TarFile = try {
            TarFile(file, charsetName)
        } catch (e: Exception) {
            TarFile(file)
        }
    }
}
