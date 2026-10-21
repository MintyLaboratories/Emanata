package com.infinity.comicreader.reader

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.File
import java.io.InputStream

/** Reads a 7z/cb7 archive (via Apache Commons Compress). */
class SevenZipReader(file: File) : ArchiveReader {
    private val sz: SevenZFile = SevenZFile.builder().setFile(file).get()

    private val native: List<SevenZArchiveEntry>

    override val entries: List<ArchiveEntry>

    override val randomAccess: Boolean get() = false

    init {
        try {
            val list = ArrayList<SevenZArchiveEntry>()
            var e = sz.nextEntry
            while (e != null) {
                if (!e.isDirectory) list.add(e)
                e = sz.nextEntry
            }
            native = list
            entries = native.mapIndexed { i, n -> ArchiveEntry(n.name, n.size, i) }
        } catch (t: Throwable) {
            runCatching { sz.close() }
            throw t
        }
    }

    /**
     * [SevenZFile] keeps decode state on the instance and is not thread-safe: two
     * concurrent [SevenZFile.getInputStream] reads corrupt each other's stream
     * (observed as `CorruptedInputException`). The [PageAdapter] and [ThumbAdapter]
     * load neighbours concurrently, so every access is serialized here.
     */
    @Synchronized
    override fun <T> read(entry: ArchiveEntry, block: (InputStream) -> T): T =
        sz.getInputStream(native[entry.index]).use(block)

    override fun close() {
        runCatching { sz.close() }
    }
}
