package com.infinity.comicreader.reader

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.File
import java.io.InputStream
import java.nio.channels.SeekableByteChannel

/** Reads a 7z/cb7 archive (via Apache Commons Compress), from a file or a channel it closes. */
class SevenZipReader(private val sz: SevenZFile) : ArchiveReader {
    constructor(file: File) : this(SevenZFile.builder().setFile(file).get())

    constructor(channel: SeekableByteChannel) : this(SevenZFile.builder().setSeekableByteChannel(channel).get())

    private val native: List<SevenZArchiveEntry>

    override val entries: List<ArchiveEntry>

    /**
     * True when no two entries share a compressed block (a "folder"), so each unpacks on
     * its own: the archive is not solid. Told by the description Commons Compress gives of
     * the archive; false when that cannot be read.
     */
    override val randomAccess: Boolean

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
            randomAccess = separateBlocks(sz.toString(), native.count { it.hasStream() })
        } catch (t: Throwable) {
            runCatching { sz.close() }
            throw t
        }
    }

    /**
     * [SevenZFile] keeps decode state on the instance and is not thread-safe: two
     * concurrent [SevenZFile.getInputStream] reads corrupt each other's stream
     * (observed as `CorruptedInputException`). The [PageAdapter] and [ThumbAdapter]
     * load neighbors concurrently, so every access is serialized here.
     */
    @Synchronized
    override fun <T> read(entry: ArchiveEntry, block: (InputStream) -> T): T =
        sz.getInputStream(native[entry.index]).use(block)

    override fun close() {
        runCatching { sz.close() }
    }

    companion object {
        private val FOLDERS = Regex("""(\d+) folders""")

        /** True when [description] (the archive's, as Commons Compress words it) names a block for each of [streams] entries with contents. */
        internal fun separateBlocks(description: String, streams: Int): Boolean {
            val folders = FOLDERS.find(description)?.groupValues?.get(1)?.toIntOrNull() ?: return false
            return folders >= streams
        }
    }
}
