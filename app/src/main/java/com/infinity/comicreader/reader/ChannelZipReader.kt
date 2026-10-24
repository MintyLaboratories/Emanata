package com.infinity.comicreader.reader

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.InputStream
import java.nio.channels.SeekableByteChannel
import java.nio.charset.Charset

/**
 * Reads a zip/cbz archive from a channel it closes (a file on a share, in place):
 * the central directory first, then only the entries asked for; an entry's local
 * header is read only when the entry is. Commons Compress serializes the channel's
 * reads, so entries stream concurrently. For [NameCharset.AUTO], names are read as
 * UTF-8, and those not marked as such again in the charset found in them.
 */
class ChannelZipReader(channel: SeekableByteChannel, charsetName: String) : ArchiveReader {
    private val auto = charsetName == NameCharset.AUTO

    private val zip: ZipFile = try {
        val charset = if (auto) Charsets.UTF_8 else NameCharset.charset(charsetName) ?: Charsets.UTF_8
        ZipFile.builder().setSeekableByteChannel(channel).setCharset(charset).setIgnoreLocalFileHeader(true).get()
    } catch (t: Throwable) {
        runCatching { channel.close() }
        throw t
    }

    private val native: List<ZipArchiveEntry>

    override val entries: List<ArchiveEntry>

    override val nameCharset: Charset

    init {
        try {
            native = zip.entries.asSequence().filter { !it.isDirectory }.toList()
            nameCharset = if (!auto) NameCharset.charset(charsetName) ?: Charsets.UTF_8 else {
                NameCharset.resolve(NameCharset.AUTO, { native.map { NameCharset.Raw(it.rawName ?: ByteArray(0), !unmarked(it)) } })
            }
            val reread = auto && nameCharset != Charsets.UTF_8
            entries = native.mapIndexed { i, e ->
                val name = if (reread && unmarked(e) && e.rawName != null) String(e.rawName, nameCharset) else e.name
                ArchiveEntry(name, e.size, i)
            }
        } catch (t: Throwable) {
            runCatching { zip.close() }
            throw t
        }
    }

    /** True when [e]'s name is stored as is: neither marked UTF-8 nor given in a Unicode extra field. */
    private fun unmarked(e: ZipArchiveEntry) = e.nameSource == ZipArchiveEntry.NameSource.NAME

    override fun <T> read(entry: ArchiveEntry, block: (InputStream) -> T): T =
        zip.getInputStream(native[entry.index]).use(block)

    override fun close() {
        runCatching { zip.close() }
    }
}
