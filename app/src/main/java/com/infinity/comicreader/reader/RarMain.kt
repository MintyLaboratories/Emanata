package com.infinity.comicreader.reader

/**
 * A RAR archive's format version and solid flag, from its main header in the leading bytes;
 * libarchive does not report whether an archive is solid.
 */
internal data class RarMain(val version: Int, val solid: Boolean) {
    companion object {
        /** Bytes enough for the signature and the main header's fields read here. */
        const val HEAD_BYTES = 64

        private val RAR4 = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00)
        private val RAR5 = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00)

        /** Of the archive starting [head]; null when it is no RAR or its main header does not follow the signature. */
        fun of(head: ByteArray): RarMain? = when {
            startsWith(head, RAR5) -> rar5(head, RAR5.size)
            startsWith(head, RAR4) -> rar4(head, RAR4.size)
            else -> null
        }

        private fun startsWith(head: ByteArray, sig: ByteArray) = head.size >= sig.size && sig.indices.all { head[it] == sig[it] }

        /** HEAD_CRC (2), HEAD_TYPE (1, 0x73 = main), HEAD_FLAGS (2, little-endian; 0x0008 = solid). */
        private fun rar4(head: ByteArray, at: Int): RarMain? {
            if (head.size < at + 5 || head[at + 2] != 0x73.toByte()) return null
            val flags = (head[at + 3].toInt() and 0xFF) or ((head[at + 4].toInt() and 0xFF) shl 8)
            return RarMain(4, flags and 0x0008 != 0)
        }

        /**
         * CRC32 (4), then variable-length integers: header size, type (1 = main), header flags
         * (0x0001: an extra area size follows, 0x0002: a data size follows), archive flags (0x0004 = solid).
         */
        private fun rar5(head: ByteArray, at: Int): RarMain? {
            val r = VintReader(head, at + 4)
            r.next() ?: return null
            if (r.next() != 1L) return null
            val flags = r.next() ?: return null
            if (flags and 0x0001L != 0L) r.next() ?: return null
            if (flags and 0x0002L != 0L) r.next() ?: return null
            val archive = r.next() ?: return null
            return RarMain(5, archive and 0x0004L != 0L)
        }
    }

    /** RAR5's variable-length integers: 7 bits a byte, low bits first, the high bit set on all but the last. */
    private class VintReader(private val bytes: ByteArray, private var at: Int) {
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
