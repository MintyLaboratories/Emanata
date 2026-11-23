package com.infinity.comicreader.reader

/**
 * A RAR archive's format version, solid flag and where its main header ends ([end], bytes from
 * the start), from the signature and main header in its leading bytes; libarchive reports
 * neither the solid flag nor the main header's end.
 */
internal data class RarMain(val version: Int, val solid: Boolean, val end: Long) {
    companion object {
        /** Bytes enough for the signature and the main header's fields read here. */
        const val HEAD_BYTES = 64

        /** The longest RAR 5 header the format allows. */
        private const val MAX_RAR5_HEADER_BYTES = 2L * 1024 * 1024

        private val RAR4 = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00)
        private val RAR5 = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00)

        /** Of the archive starting [head]; null when it is no RAR or its main header does not follow the signature. */
        fun of(head: ByteArray): RarMain? = when {
            startsWith(head, RAR5) -> rar5(head, RAR5.size)
            startsWith(head, RAR4) -> rar4(head, RAR4.size)
            else -> null
        }

        private fun startsWith(head: ByteArray, sig: ByteArray) = head.size >= sig.size && sig.indices.all { head[it] == sig[it] }

        /**
         * HEAD_CRC (2), HEAD_TYPE (1, 0x73 = main), HEAD_FLAGS (2, little-endian; 0x0008 = solid,
         * 0x8000 = an ADD_SIZE (4) of data follows the header), HEAD_SIZE (2, the whole header).
         */
        private fun rar4(head: ByteArray, at: Int): RarMain? {
            if (head.size < at + 7 || head[at + 2] != 0x73.toByte()) return null
            val flags = u16(head, at + 3)
            val size = u16(head, at + 5)
            if (size < 7) return null
            var end = at.toLong() + size
            if (flags and 0x8000 != 0) {
                if (head.size < at + 11) return null
                end += u16(head, at + 7).toLong() or (u16(head, at + 9).toLong() shl 16)
            }
            return RarMain(4, flags and 0x0008 != 0, end)
        }

        private fun u16(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

        /**
         * CRC32 (4), then variable-length integers: header size (of what follows it), type (1 =
         * main), header flags (0x0001: an extra area size follows, 0x0002: a data size follows, the
         * data after the header), archive flags (0x0004 = solid).
         */
        private fun rar5(head: ByteArray, at: Int): RarMain? {
            val r = VintReader(head, at + 4)
            val size = r.next() ?: return null
            val body = r.at
            if (r.next() != 1L) return null
            val flags = r.next() ?: return null
            if (flags and 0x0001L != 0L) r.next() ?: return null
            val data = if (flags and 0x0002L != 0L) r.next() ?: return null else 0L
            val archive = r.next() ?: return null
            if (size !in 1..MAX_RAR5_HEADER_BYTES || data < 0) return null
            val end = runCatching { Math.addExact(body + size, data) }.getOrNull() ?: return null
            return RarMain(5, archive and 0x0004L != 0L, end)
        }
    }

    /** RAR5's variable-length integers: 7 bits a byte, low bits first, the high bit set on all but the last. */
    private class VintReader(private val bytes: ByteArray, var at: Int) {
        fun next(): Long? {
            var value = 0L
            var shift = 0
            while (at < bytes.size && shift < 64) {
                val b = bytes[at++].toInt() and 0xFF
                value = value or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return value
                shift += 7
            }
            return null
        }
    }
}
