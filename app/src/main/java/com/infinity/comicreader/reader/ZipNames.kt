package com.infinity.comicreader.reader

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SeekableByteChannel
import java.util.zip.CRC32

/**
 * The file names a zip's central directory lists, as stored ([NameCharset.Raw]), with the
 * names their Unicode Path extra fields give: read from its end record (ZIP64 too),
 * without touching any entry. Empty when the directory is not where the end record
 * says, as in a zip with data before it. [entries] also tells where each entry is.
 */
object ZipNames {
    /**
     * An entry as the central directory lists it: its [name], compression [method] (0 stored),
     * [compressedSize] and [size], and the [offset] of its local header, ZIP64 values included.
     */
    class Entry(val name: NameCharset.Raw, val method: Int, val compressedSize: Long, val size: Long, val offset: Long)

    fun read(channel: SeekableByteChannel): List<NameCharset.Raw> = entries(channel).map { it.name }

    /** The entries in directory order, as [read] finds them. */
    fun entries(channel: SeekableByteChannel): List<Entry> {
        val size = channel.size()
        val tailLength = minOf(size, (END_BYTES + MAX_COMMENT).toLong()).toInt()
        if (tailLength < END_BYTES) return emptyList()
        val tail = readAt(channel, size - tailLength, tailLength) ?: return emptyList()
        val end = (tailLength - END_BYTES downTo 0).firstOrNull { tail.getInt(it) == END_SIG } ?: return emptyList()
        var count = tail.getShort(end + 10).toLong() and 0xFFFF
        var dirSize = tail.getInt(end + 12).toLong() and 0xFFFFFFFFL
        var dirOffset = tail.getInt(end + 16).toLong() and 0xFFFFFFFFL
        val locator = end - ZIP64_LOCATOR_BYTES
        if ((count == 0xFFFFL || dirSize == 0xFFFFFFFFL || dirOffset == 0xFFFFFFFFL) && locator >= 0 && tail.getInt(locator) == ZIP64_LOCATOR_SIG) {
            val record = readAt(channel, tail.getLong(locator + 8), ZIP64_END_BYTES)
            if (record != null && record.getInt(0) == ZIP64_END_SIG) {
                count = record.getLong(32)
                dirSize = record.getLong(40)
                dirOffset = record.getLong(48)
            }
        }
        if (dirSize <= 0 || dirSize > MAX_DIR_BYTES || dirOffset < 0 || dirOffset + dirSize > size) return emptyList()
        val dir = readAt(channel, dirOffset, dirSize.toInt()) ?: return emptyList()
        val out = ArrayList<Entry>(count.coerceAtMost(MAX_DIR_BYTES / ENTRY_BYTES).toInt())
        var at = 0
        while (at + ENTRY_BYTES <= dir.limit() && dir.getInt(at) == ENTRY_SIG) {
            val flags = dir.getShort(at + 8).toInt()
            val method = dir.getShort(at + 10).toInt() and 0xFFFF
            var compressed = dir.getInt(at + 20).toLong() and 0xFFFFFFFFL
            var plain = dir.getInt(at + 24).toLong() and 0xFFFFFFFFL
            var offset = dir.getInt(at + 42).toLong() and 0xFFFFFFFFL
            val nameLength = dir.getShort(at + 28).toInt() and 0xFFFF
            val extraLength = dir.getShort(at + 30).toInt() and 0xFFFF
            val commentLength = dir.getShort(at + 32).toInt() and 0xFFFF
            if (at + ENTRY_BYTES + nameLength > dir.limit()) break
            val name = ByteArray(nameLength)
            dir.position(at + ENTRY_BYTES)
            dir.get(name)
            val marked = flags and UTF8_FLAG != 0
            val extraAt = at + ENTRY_BYTES + nameLength
            val extraEnd = minOf(extraAt + extraLength, dir.limit())
            val unicode = if (marked) null else unicodePath(dir, extraAt, extraEnd, name)
            if (plain == MAX32 || compressed == MAX32 || offset == MAX32) {
                zip64(dir, extraAt, extraEnd)?.let { values ->
                    var k = 0
                    if (plain == MAX32) plain = values.getOrElse(k++) { -1 }
                    if (compressed == MAX32) compressed = values.getOrElse(k++) { -1 }
                    if (offset == MAX32) offset = values.getOrElse(k) { -1 }
                }
            }
            out += Entry(NameCharset.Raw(name, utf8 = marked || unicode != null, unicode = unicode), method, compressed, plain, offset)
            at += ENTRY_BYTES + nameLength + extraLength + commentLength
        }
        return out
    }

    /**
     * The name an Info-ZIP Unicode Path extra field among [dir]'s bytes [from] to [until]
     * gives for [name], as Commons Compress takes it: version 1, made from these very bytes
     * (their CRC-32); null when there is none.
     */
    private fun unicodePath(dir: ByteBuffer, from: Int, until: Int, name: ByteArray): String? {
        var at = from
        while (at + 4 <= until) {
            val id = dir.getShort(at).toInt() and 0xFFFF
            val size = dir.getShort(at + 2).toInt() and 0xFFFF
            val data = at + 4
            if (data + size > until) return null
            if (id == UNICODE_PATH_ID && size >= 5 && dir.get(data).toInt() == 1) {
                val crc = CRC32().apply { update(name) }.value
                if ((dir.getInt(data + 1).toLong() and 0xFFFFFFFFL) != crc) return null
                val text = ByteArray(size - 5)
                dir.position(data + 5)
                dir.get(text)
                return String(text, Charsets.UTF_8)
            }
            at = data + size
        }
        return null
    }

    /** The 64-bit values of the ZIP64 extra field among [dir]'s bytes [from] to [until], in order; null when there is none. */
    private fun zip64(dir: ByteBuffer, from: Int, until: Int): List<Long>? {
        var at = from
        while (at + 4 <= until) {
            val id = dir.getShort(at).toInt() and 0xFFFF
            val size = dir.getShort(at + 2).toInt() and 0xFFFF
            val data = at + 4
            if (data + size > until) return null
            if (id == ZIP64_EXTRA_ID) return List(size / 8) { dir.getLong(data + it * 8) }
            at = data + size
        }
        return null
    }

    /** [length] bytes of [channel] from [position], little-endian; null when it ends before. */
    private fun readAt(channel: SeekableByteChannel, position: Long, length: Int): ByteBuffer? {
        if (position < 0) return null
        val buf = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)
        channel.position(position)
        while (buf.hasRemaining()) if (channel.read(buf) < 0) return null
        buf.flip()
        return buf
    }

    private const val END_SIG = 0x06054b50
    private const val END_BYTES = 22
    private const val MAX_COMMENT = 0xFFFF
    private const val ZIP64_LOCATOR_SIG = 0x07064b50
    private const val ZIP64_LOCATOR_BYTES = 20
    private const val ZIP64_END_SIG = 0x06064b50
    private const val ZIP64_END_BYTES = 56
    private const val ENTRY_SIG = 0x02014b50
    private const val ENTRY_BYTES = 46

    /** A 32-bit size or offset that stands for one in the ZIP64 extra field. */
    private const val MAX32 = 0xFFFFFFFFL

    /** Header ID of the ZIP64 extended information extra field. */
    private const val ZIP64_EXTRA_ID = 0x0001

    /** Header ID of the Info-ZIP Unicode Path extra field. */
    private const val UNICODE_PATH_ID = 0x7075

    /** General purpose flag bit 11: the name is UTF-8. */
    private const val UTF8_FLAG = 0x0800

    /** The largest directory read: some 300 000 entries. */
    private const val MAX_DIR_BYTES = 32L shl 20
}
