package com.infinity.comicreader.reader

import com.infinity.comicreader.net.FileEnds
import com.infinity.comicreader.net.RemoteFile
import com.infinity.comicreader.net.Remotes
import com.infinity.comicreader.util.Formats
import java.io.File
import java.io.IOException
import java.io.InputStream

/** Container formats a book file can hold, as told by its leading bytes. */
enum class ArchiveKind {
    ZIP, RAR, SEVEN_ZIP, TAR, PDF;

    companion object {
        private const val TAR_HEADER = 512

        /** Bytes [sniff] needs to recognize every kind: a whole tar header. */
        const val HEADER_BYTES = TAR_HEADER

        /** POSIX and GNU tar headers carry `ustar` here; pre-POSIX ones carry nothing. */
        private const val TAR_MAGIC_AT = 257

        private const val TAR_CHECKSUM_AT = 148
        private const val TAR_CHECKSUM_BYTES = 8

        private val ZIP_LOCAL = bytes(0x50, 0x4B, 0x03, 0x04)
        private val ZIP_EMPTY = bytes(0x50, 0x4B, 0x05, 0x06)
        private val ZIP_SPANNED = bytes(0x50, 0x4B, 0x07, 0x08)
        private val ZIP_SPANNED_SINGLE = bytes(0x50, 0x4B, 0x30, 0x30)
        private val RAR4 = bytes(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00)
        private val RAR5 = bytes(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00)
        private val SEVEN_ZIP_SIG = bytes(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C)
        private val PDF_SIG = bytes(0x25, 0x50, 0x44, 0x46)
        private val TAR_MAGIC = "ustar".toByteArray(Charsets.US_ASCII)

        /** Kind named by the first [length] bytes of [header], or null when none matches. */
        fun sniff(header: ByteArray, length: Int = header.size): ArchiveKind? {
            val n = minOf(length, header.size)
            fun has(sig: ByteArray, at: Int = 0) = n >= at + sig.size && sig.indices.all { header[at + it] == sig[it] }
            return when {
                has(ZIP_LOCAL) || has(ZIP_EMPTY) || has(ZIP_SPANNED) || has(ZIP_SPANNED_SINGLE) -> ZIP
                has(RAR4) || has(RAR5) -> RAR
                has(SEVEN_ZIP_SIG) -> SEVEN_ZIP
                has(PDF_SIG) -> PDF
                has(TAR_MAGIC, TAR_MAGIC_AT) -> TAR
                isTarHeader(header, n) -> TAR
                else -> null
            }
        }

        /**
         * True when the first [n] bytes of [header] hold a whole tar header, pre-POSIX
         * included: a name, and the octal checksum it stores matches its bytes (summed
         * with the checksum field as spaces, unsigned as POSIX says or signed as some
         * old writers did).
         */
        private fun isTarHeader(header: ByteArray, n: Int): Boolean {
            if (n < TAR_HEADER || header[0] == 0.toByte()) return false
            val field = String(header, TAR_CHECKSUM_AT, TAR_CHECKSUM_BYTES, Charsets.US_ASCII).trim(' ', '\u0000')
            if (field.isEmpty() || field.any { it !in '0'..'7' }) return false
            val stored = field.toLong(8)
            var unsigned = 0L
            var signed = 0L
            for (i in 0 until TAR_HEADER) {
                val b = if (i - TAR_CHECKSUM_AT in 0 until TAR_CHECKSUM_BYTES) ' '.code.toByte() else header[i]
                unsigned += b.toInt() and 0xFF
                signed += b
            }
            return stored == unsigned || stored == signed
        }

        private const val MAX_REMEMBERED = 512

        /** Kinds of files on shares by path, size and time, so each is read for its kind once. */
        private val remembered = object : LinkedHashMap<String, ArchiveKind>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ArchiveKind>) = size > MAX_REMEMBERED
        }

        /** Kind from the leading bytes of [file], on a share too (read once per version); null when unreadable or unrecognized. */
        fun sniff(file: File): ArchiveKind? {
            val stat = (file as? RemoteFile)?.stat()
            val key = stat?.let { "${file.path}\n${it.size}\n${it.modified}" }
            if (key != null) synchronized(remembered) { remembered[key] }?.let { return it }
            // Its first bytes just read for its Book ID tell without asking the share again.
            val kept = stat?.let { FileEnds.head(file.path, it.size, it.modified, HEADER_BYTES) }
            val kind = if (kept != null) sniff(kept) else try {
                open(file).use { input ->
                    val header = ByteArray(HEADER_BYTES)
                    var n = 0
                    while (n < header.size) {
                        val r = input.read(header, n, header.size - n)
                        if (r < 0) break
                        n += r
                    }
                    sniff(header, n)
                }
            } catch (e: IOException) {
                null
            }
            if (key != null && kind != null) synchronized(remembered) { remembered[key] = kind }
            return kind
        }

        private fun open(file: File): InputStream = if (file is RemoteFile) Remotes.inputStream(file.path) else file.inputStream()

        /** Kind implied by the extension of [name] alone. */
        fun ofName(name: String): ArchiveKind? = when (Formats.ext(name)) {
            in Formats.ZIP -> ZIP
            in Formats.RAR -> RAR
            in Formats.SEVEN_ZIP -> SEVEN_ZIP
            in Formats.TAR -> TAR
            in Formats.PDF -> PDF
            else -> null
        }

        /** Content first, so a `.cbr` that is really a zip opens as one; extension otherwise. */
        fun detect(file: File): ArchiveKind? = sniff(file) ?: ofName(file.name)

        private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
    }
}
