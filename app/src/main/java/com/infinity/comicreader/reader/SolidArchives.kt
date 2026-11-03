package com.infinity.comicreader.reader

import java.io.File
import java.io.IOException

/**
 * Tells solid archives (RAR 4, RAR 5 and 7z), where each entry unpacks those stored
 * before it, from their headers alone: slow to jump around in, so worth repacking.
 */
object SolidArchives {
    private val RAR4 = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00)
    private val RAR5 = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00)

    /** RAR 4 main header type, and its flag for a solid archive. */
    private const val RAR4_MAIN = 0x73
    private const val RAR4_SOLID = 0x0008

    /** RAR 5 main header type, its header flags for an extra area and a data area, and its archive flag for solid. */
    private const val RAR5_MAIN = 1L
    private const val RAR5_EXTRA = 0x0001L
    private const val RAR5_DATA = 0x0002L
    private const val RAR5_SOLID = 0x0004L

    /**
     * True for a solid RAR (by its main header) or 7z (some block holding several entries),
     * false for any other archive or file, or one whose headers cannot be read. Blocking.
     */
    fun isSolid(file: File): Boolean = when (ArchiveKind.detect(file)) {
        ArchiveKind.RAR -> rarSolid(head(file)) == true
        ArchiveKind.SEVEN_ZIP -> try {
            SevenZipReader(file).use { it.solid == true }
        } catch (e: Exception) {
            false
        }
        else -> false
    }

    /**
     * Whether the RAR whose first bytes are [header] is solid, by its main header; null when
     * it is no RAR or its main header is not where it starts (encrypted headers, cut short).
     */
    fun rarSolid(header: ByteArray): Boolean? = when {
        starts(header, RAR5) -> rar5Solid(header)
        starts(header, RAR4) -> rar4Solid(header)
        else -> null
    }

    /** RAR 4: CRC (2), type (1), flags (2) after the marker. */
    private fun rar4Solid(b: ByteArray): Boolean? {
        val at = RAR4.size
        if (b.size < at + 5 || (b[at + 2].toInt() and 0xFF) != RAR4_MAIN) return null
        val flags = (b[at + 3].toInt() and 0xFF) or ((b[at + 4].toInt() and 0xFF) shl 8)
        return flags and RAR4_SOLID != 0
    }

    /** RAR 5: CRC (4), then variable-length size, type, flags, [extra size], [data size], archive flags. */
    private fun rar5Solid(b: ByteArray): Boolean? {
        val r = VarInts(b, RAR5.size + 4)
        r.next() ?: return null
        if (r.next() != RAR5_MAIN) return null
        val flags = r.next() ?: return null
        if (flags and RAR5_EXTRA != 0L) r.next() ?: return null
        if (flags and RAR5_DATA != 0L) r.next() ?: return null
        val archive = r.next() ?: return null
        return archive and RAR5_SOLID != 0L
    }

    /** RAR 5's 7-bit variable-length integers read from [b] at [at] on; null past its end or beyond 64 bits. */
    private class VarInts(private val b: ByteArray, private var at: Int) {
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
    }

    private fun starts(b: ByteArray, sig: ByteArray) = b.size >= sig.size && sig.indices.all { b[it] == sig[it] }

    /** The first [ArchiveKind.HEADER_BYTES] of [file], fewer when shorter; empty when unreadable. */
    private fun head(file: File): ByteArray = try {
        file.inputStream().use { input ->
            val out = ByteArray(ArchiveKind.HEADER_BYTES)
            var n = 0
            while (n < out.size) {
                val r = input.read(out, n, out.size - n)
                if (r < 0) break
                n += r
            }
            out.copyOf(n)
        }
    } catch (e: IOException) {
        ByteArray(0)
    }
}
