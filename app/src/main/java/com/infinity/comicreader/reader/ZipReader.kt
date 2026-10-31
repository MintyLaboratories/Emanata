package com.infinity.comicreader.reader

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Reads a zip/cbz archive, its names in the charset [charsetName] gives ([NameCharset.resolve]),
 * or as their Unicode Path extra fields give them, as [ChannelZipReader] reads them.
 * [ZipFile] streams entries one read at a time across threads; a stored entry is read whole
 * instead ([readWhole]), at its place in the file, where threads never wait for each other.
 */
class ZipReader(file: File, charsetName: String) : ArchiveReader {
    /** The file, for reads at a place ([readWhole]), which take no lock. */
    private val channel: FileChannel = RandomAccessFile(file, "r").channel

    /** The entries as stored, in the zip's order ([ZipNames]). */
    private val listed = runCatching { ZipNames.entries(channel) }.getOrDefault(emptyList())

    private val stored = listed.map { it.name }

    override val nameCharset: Charset = NameCharset.resolve(charsetName, { stored })

    private val zip: ZipFile

    private val native: List<ZipEntry>

    override val entries: List<ArchiveEntry>

    /** Where each entry is ([ZipNames.Entry]), by [ArchiveEntry.index]; null when not known for sure. */
    private val places: List<ZipNames.Entry?>?

    init {
        // With every name known as stored, ZipFile reads them byte for byte (it skips Unicode
        // Path extra fields, and fails on names its charset does not decode); they are decoded here.
        var byBytes = if (stored.isEmpty()) null else runCatching { ZipFile(file, Charsets.ISO_8859_1) }.getOrNull()
        var all = byBytes?.let { z -> runCatching { z.entries().toList() }.getOrNull() }
        if (byBytes != null && all?.size != stored.size) {
            runCatching { byBytes?.close() }
            byBytes = null
        }
        zip = try {
            byBytes ?: runCatching { ZipFile(file, nameCharset) }.getOrElse { ZipFile(file) }
        } catch (t: Throwable) {
            runCatching { channel.close() }
            throw t
        }
        try {
            if (byBytes == null) all = zip.entries().toList()
            val list = all.orEmpty()
            val files = list.indices.filter { !list[it].isDirectory }
            native = files.map { list[it] }
            entries = files.mapIndexed { i, at ->
                val name = if (byBytes == null) list[at].name else nameOf(stored[at])
                ArchiveEntry(name, list[at].size, i, list[at].crc)
            }
            // Read by bytes, the directory's entries are [ZipFile]'s, one for one.
            places = if (byBytes == null) null else files.map { at -> listed[at].takeIf { it.size == list[at].size } }
        } catch (t: Throwable) {
            runCatching { zip.close() }
            runCatching { channel.close() }
            throw t
        }
    }

    /** [raw]'s name: its Unicode one, else its bytes as UTF-8 where marked so, else in [nameCharset]. */
    private fun nameOf(raw: NameCharset.Raw): String =
        raw.unicode ?: String(raw.bytes, if (raw.utf8) Charsets.UTF_8 else nameCharset)

    override fun <T> read(entry: ArchiveEntry, block: (InputStream) -> T): T =
        zip.getInputStream(native[entry.index]).use(block)

    /** A stored entry, read at its place in one go; null for a compressed one, or one too large to hold. */
    override fun readWhole(entry: ArchiveEntry): ByteArray? {
        val place = places?.getOrNull(entry.index) ?: return null
        if (place.method != STORED || place.compressedSize != place.size || place.size !in 0..MAX_WHOLE_BYTES || place.offset < 0) return null
        val head = ByteBuffer.allocate(LOCAL_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        readAt(head, place.offset)
        if (head.getInt(0) != LOCAL_SIG) throw IOException("No local header for ${entry.path}")
        val skip = (head.getShort(26).toInt() and 0xFFFF) + (head.getShort(28).toInt() and 0xFFFF)
        val data = ByteArray(place.size.toInt())
        val from = place.offset + LOCAL_BYTES + skip
        // A chunk at a time: the channel copies through a direct buffer as large as each read, kept per thread.
        var done = 0
        while (done < data.size) {
            val n = minOf(CHUNK_BYTES, data.size - done)
            readAt(ByteBuffer.wrap(data, done, n), from + done)
            done += n
        }
        return data
    }

    /** Fills [buffer] from [position] on; positional reads share no file pointer, so no lock. */
    private fun readAt(buffer: ByteBuffer, position: Long) {
        var at = position
        while (buffer.hasRemaining()) {
            val n = channel.read(buffer, at)
            if (n < 0) throw IOException("Zip ends inside an entry")
            at += n
        }
    }

    override fun close() {
        runCatching { zip.close() }
        runCatching { channel.close() }
    }

    private companion object {
        const val STORED = 0
        const val LOCAL_SIG = 0x04034b50
        const val LOCAL_BYTES = 30

        /** The most read at once ([readWhole]). */
        const val CHUNK_BYTES = 256 * 1024

        /** The largest entry read whole: as large as any page is buffered ([BitmapUtils.readCapped]). */
        const val MAX_WHOLE_BYTES = 96L * 1024 * 1024
    }
}
