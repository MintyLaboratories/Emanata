package minty.emanata.reader

import minty.emanata.net.RemoteChannel
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.archivers.tar.TarFile
import java.io.File
import java.io.InputStream
import java.nio.channels.SeekableByteChannel
import java.nio.charset.Charset

/**
 * Reads a tar/cbt archive (via Apache Commons Compress). [TarFile] indexes the
 * headers once and then seeks straight to an entry's data, so pages open in any order.
 * Read [latin1] (for [NameCharset.AUTO]), names are read again in the charset found in them.
 */
class TarReader(
    private val tar: TarFile,
    latin1: Boolean = false,
    charset: Charset? = null,
    private val remote: RemoteChannel? = null
) : ArchiveReader {
    constructor(file: File, charsetName: String) : this(tarFile(file, encodingOf(charsetName)), charsetName == NameCharset.AUTO, charsetOf(charsetName))

    /** Reads the tar from [channel], which it closes; a [RemoteChannel] hears of each entry about to be read whole. */
    constructor(channel: SeekableByteChannel, charsetName: String) : this(
        TarFile(channel, TarConstants.DEFAULT_BLKSIZE, TarConstants.DEFAULT_RCDSIZE, encodingOf(charsetName), false),
        charsetName == NameCharset.AUTO,
        charsetOf(charsetName),
        channel as? RemoteChannel
    )

    private val native: List<TarArchiveEntry>

    override val entries: List<ArchiveEntry>

    override val nameCharset: Charset?

    init {
        try {
            native = tar.entries.filter { it.isFile }
            val names = native.map { it.name }
            val (read, found) = if (latin1) NameCharset.fromLatin1(names) else names to charset
            nameCharset = found
            entries = native.mapIndexed { i, e -> ArchiveEntry(read[i], e.size, i) }
        } catch (t: Throwable) {
            runCatching { tar.close() }
            throw t
        }
    }

    /** Entry streams share the file's channel position, so reads are serialized. */
    @Synchronized
    override fun <T> read(entry: ArchiveEntry, block: (InputStream) -> T): T {
        val e = native[entry.index]
        remote?.expect(e.dataOffset, e.size)
        return tar.getInputStream(e).use(block)
    }

    override fun close() {
        runCatching { tar.close() }
    }

    private companion object {
        fun tarFile(file: File, encoding: String): TarFile = try {
            TarFile(file, encoding)
        } catch (e: Exception) {
            TarFile(file)
        }

        /** The charset [charsetName] names; for [NameCharset.AUTO], none yet. */
        fun charsetOf(charsetName: String): Charset? =
            if (charsetName == NameCharset.AUTO) null else NameCharset.charset(charsetName) ?: Charsets.UTF_8

        /** The encoding the headers are read in: ISO-8859-1, byte for byte, for [NameCharset.AUTO]. */
        fun encodingOf(charsetName: String): String = (charsetOf(charsetName) ?: Charsets.ISO_8859_1).name()
    }
}
