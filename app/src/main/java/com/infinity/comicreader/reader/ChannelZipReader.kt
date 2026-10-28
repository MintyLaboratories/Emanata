package com.infinity.comicreader.reader

import com.infinity.comicreader.net.RemoteChannel
import org.apache.commons.compress.archivers.zip.UnicodePathExtraField
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.InputStream
import java.nio.channels.SeekableByteChannel
import java.nio.charset.Charset
import java.util.zip.CRC32

/**
 * Reads a zip/cbz archive from a channel it closes (a file on a share, in place):
 * the central directory first, then only the entries asked for; an entry's local
 * header is read only when the entry is. Commons Compress serializes the channel's
 * reads, so entries stream concurrently. Names are read as UTF-8, which never fails, or
 * as their Unicode Path extra fields give them, and those not given as Unicode again in
 * the charset [charsetName] gives ([NameCharset.resolve]), as [ZipReader] reads them.
 * A [RemoteChannel] hears of each entry's data as it is about to be read whole.
 */
class ChannelZipReader(channel: SeekableByteChannel, charsetName: String) : ArchiveReader {
    /** The share's file, told of each entry about to be read whole. */
    private val remote = channel as? RemoteChannel

    private val zip: ZipFile = try {
        ZipFile.builder().setSeekableByteChannel(channel).setCharset(Charsets.UTF_8).setIgnoreLocalFileHeader(true).get()
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
            // Skipping local headers, Commons Compress leaves Unicode Path extra fields unread.
            val unicode = native.map { unicodePath(it) }
            nameCharset = NameCharset.resolve(charsetName, {
                native.mapIndexed { i, e -> NameCharset.Raw(e.rawName ?: ByteArray(0), !unmarked(e) || unicode[i] != null, unicode[i]) }
            })
            val reread = nameCharset != Charsets.UTF_8
            entries = native.mapIndexed { i, e ->
                val name = unicode[i] ?: if (reread && unmarked(e) && e.rawName != null) String(e.rawName, nameCharset) else e.name
                ArchiveEntry(name, e.size, i)
            }
        } catch (t: Throwable) {
            runCatching { zip.close() }
            throw t
        }
    }

    /** True when [e]'s name is stored as is, not marked UTF-8. */
    private fun unmarked(e: ZipArchiveEntry) = e.nameSource == ZipArchiveEntry.NameSource.NAME

    /** The name [e]'s Unicode Path extra field gives, when it was made for [e]'s stored name (its CRC-32); else null. */
    private fun unicodePath(e: ZipArchiveEntry): String? {
        val raw = e.rawName ?: return null
        if (!unmarked(e)) return null
        val field = e.getExtraField(UnicodePathExtraField.UPATH_ID) as? UnicodePathExtraField ?: return null
        val crc = CRC32().apply { update(raw) }.value
        return if (field.nameCRC32 == crc) field.unicodeName?.let { String(it, Charsets.UTF_8) } else null
    }

    override fun <T> read(entry: ArchiveEntry, block: (InputStream) -> T): T {
        val e = native[entry.index]
        return zip.getInputStream(e).use { stream ->
            // Its data starts after its local header, found as the stream opens.
            remote?.expect(e.dataOffset, e.compressedSize)
            block(stream)
        }
    }

    override fun close() {
        runCatching { zip.close() }
    }
}
