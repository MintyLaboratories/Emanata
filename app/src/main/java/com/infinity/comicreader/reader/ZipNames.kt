package com.infinity.comicreader.reader

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SeekableByteChannel

/**
 * The file names a zip's central directory lists, as stored ([NameCharset.Raw]): read
 * from its end record (ZIP64 too), without touching any entry. Empty when the directory
 * is not where the end record says, as in a zip with data before it.
 */
object ZipNames {
    fun read(channel: SeekableByteChannel): List<NameCharset.Raw> {
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
        val out = ArrayList<NameCharset.Raw>(count.coerceAtMost(MAX_DIR_BYTES / ENTRY_BYTES).toInt())
        var at = 0
        while (at + ENTRY_BYTES <= dir.limit() && dir.getInt(at) == ENTRY_SIG) {
            val flags = dir.getShort(at + 8).toInt()
            val nameLength = dir.getShort(at + 28).toInt() and 0xFFFF
            val extraLength = dir.getShort(at + 30).toInt() and 0xFFFF
            val commentLength = dir.getShort(at + 32).toInt() and 0xFFFF
            if (at + ENTRY_BYTES + nameLength > dir.limit()) break
            val name = ByteArray(nameLength)
            dir.position(at + ENTRY_BYTES)
            dir.get(name)
            out += NameCharset.Raw(name, utf8 = flags and UTF8_FLAG != 0)
            at += ENTRY_BYTES + nameLength + extraLength + commentLength
        }
        return out
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

    /** General purpose flag bit 11: the name is UTF-8. */
    private const val UTF8_FLAG = 0x0800

    /** The largest directory read: some 300 000 entries. */
    private const val MAX_DIR_BYTES = 32L shl 20
}
