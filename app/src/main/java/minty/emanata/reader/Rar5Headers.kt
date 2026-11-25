package minty.emanata.reader

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.util.zip.CRC32

/**
 * Lists a RAR 5 archive by its headers alone, each checked against its CRC-32, jumping over
 * every data area: for a solid archive, which libarchive can list only by unpacking it whole.
 * Per the RAR 5.0 format notes.
 */
internal object Rar5Headers {
    /**
     * A file header: [name] as stored (UTF-8), the unpacked [size] (-1 unknown), whether it is a
     * regular [file] (not a folder or a link), where its block [start]s, and the dictionary its
     * data unpacks with ([dict] bytes; 0 for a folder or when not known).
     */
    class Header(val name: ByteArray, val size: Long, val file: Boolean, val start: Long, val dict: Long = 0)

    /** The largest dictionary libarchive unpacks with; it turns larger ones down entry by entry. */
    const val MAX_DICT_BYTES = 64L * 1024 * 1024

    private const val SIGNATURE_BYTES = 8L
    private const val HEAD_FILE = 2L
    private const val HEAD_CRYPT = 4L
    private const val HEAD_END = 5L

    /** Header flags: an extra area follows, a data area follows. */
    private const val EXTRA = 0x0001L
    private const val DATA = 0x0002L

    /** File flags: a folder, mtime present, CRC present, size unknown. */
    private const val DIRECTORY = 0x0001L
    private const val MTIME = 0x0002L
    private const val CRC = 0x0004L
    private const val SIZE_UNKNOWN = 0x0008L

    /** Extra record types: encryption, redirection (a link). */
    private const val RECORD_CRYPT = 1L
    private const val RECORD_REDIR = 5L

    /** The longest header read: names are at most 2 KB, extra areas small. */
    private const val MAX_HEADER_BYTES = 2L * 1024 * 1024

    /**
     * Every file header of the RAR 5 archive in [channel], in order, until its end block or its
     * end. Throws [RefusedRar] for an encrypted archive or entry or a file whose dictionary is
     * over [MAX_DICT_BYTES], IOException for a header cut short or damaged; [found] holds the
     * headers read before it.
     */
    fun list(channel: SeekableByteChannel, found: MutableList<Header> = ArrayList()): List<Header> {
        val size = channel.size()
        var at = SIGNATURE_BYTES
        while (at < size) {
            val lead = readAt(channel, at, 4 + 10)
            if (lead.size < 5) throw IOException("RAR header cut short")
            val sizeField = Vints(lead, 4)
            val headerSize = sizeField.next() ?: throw IOException("RAR header cut short")
            if (headerSize <= 0 || headerSize > MAX_HEADER_BYTES) throw IOException("Bad RAR header size")
            val sized = readAt(channel, at + 4, sizeField.at - 4 + headerSize.toInt())
            if (sized.size.toLong() != sizeField.at - 4 + headerSize) throw IOException("RAR header cut short")
            val stored = (lead[0].toLong() and 0xFF) or ((lead[1].toLong() and 0xFF) shl 8) or
                ((lead[2].toLong() and 0xFF) shl 16) or ((lead[3].toLong() and 0xFF) shl 24)
            if (CRC32().apply { update(sized) }.value != stored) throw IOException("RAR header CRC error")
            val r = Vints(sized, sizeField.at - 4)
            val type = r.need()
            val flags = r.need()
            val extraSize = if (flags and EXTRA != 0L) r.need() else 0L
            val dataSize = if (flags and DATA != 0L) r.need() else 0L
            if (extraSize > sized.size - r.at) throw IOException("Bad RAR extra area size")
            when (type) {
                HEAD_CRYPT -> throw RefusedRar("Encrypted archive")
                HEAD_END -> return found
                HEAD_FILE -> {
                    val fileFlags = r.need()
                    val unpacked = r.need()
                    r.need() // attributes
                    if (fileFlags and MTIME != 0L) r.skip(4)
                    if (fileFlags and CRC != 0L) r.skip(4)
                    val dict = dictOf(r.need())
                    r.need() // host OS
                    val name = r.bytes(r.need())
                    var link = false
                    if (extraSize > 0) {
                        val records = Vints(sized, sized.size - extraSize.toInt())
                        while (records.at < sized.size) {
                            val recordSize = records.need()
                            if (recordSize < 1 || recordSize > sized.size - records.at) throw IOException("Bad RAR extra record size")
                            val next = records.at + recordSize.toInt()
                            when (records.need()) {
                                RECORD_CRYPT -> throw RefusedRar("Encrypted archive")
                                RECORD_REDIR -> link = true
                            }
                            records.at = next
                        }
                    }
                    val folder = fileFlags and DIRECTORY != 0L
                    if (!folder && dict > MAX_DICT_BYTES) throw RefusedRar("Dictionary over 64 MiB")
                    found += Header(name, if (fileFlags and SIZE_UNKNOWN != 0L) -1 else unpacked, !folder && !link, at, if (folder) 0 else dict)
                }
            }
            // A data area running past the end: the archive is cut short and this header is its last.
            val after = at + 4 + sized.size
            at = if (dataSize > size - after) size else after + dataSize
        }
        return found
    }

    /**
     * The dictionary size in a file header's compression information [info]: 128 KiB doubled
     * per bits 10-14; RAR 7 (format version 1 in bits 0-5) adds 1/32 of it per bits 15-19.
     */
    internal fun dictOf(info: Long): Long {
        val dict = (128L * 1024) shl ((info shr 10) and 0x1F).toInt()
        return if (info and 0x3F == 1L) dict + dict / 32 * ((info shr 15) and 0x1F) else dict
    }

    private fun readAt(channel: SeekableByteChannel, at: Long, count: Int): ByteArray {
        val buffer = ByteBuffer.allocate(count)
        channel.position(at)
        while (buffer.hasRemaining() && channel.read(buffer) >= 0) Unit
        return buffer.array().copyOf(buffer.position())
    }

    /** RAR 5's variable-length integers in [b] from [at]: 7 bits a byte, low bits first. */
    private class Vints(private val b: ByteArray, var at: Int) {
        fun next(): Long? {
            var value = 0L
            var shift = 0
            while (at < b.size && shift < 64) {
                val x = b[at++].toInt() and 0xFF
                value = value or ((x and 0x7F).toLong() shl shift)
                if (x and 0x80 == 0) return value
                shift += 7
            }
            return null
        }

        fun need(): Long = next()?.takeIf { it >= 0 } ?: throw IOException("Bad RAR header")

        fun skip(n: Int) {
            if (at + n > b.size) throw IOException("Bad RAR header")
            at += n
        }

        fun bytes(n: Long): ByteArray {
            if (n < 0 || n > b.size - at) throw IOException("Bad RAR header")
            return b.copyOfRange(at, at + n.toInt()).also { at += n.toInt() }
        }
    }
}
