package com.infinity.comicreader.reader

import com.infinity.comicreader.util.Formats
import java.io.File
import java.io.IOException

/** Container formats a book file can hold, as told by its leading bytes. */
enum class ArchiveKind {
    ZIP, RAR, SEVEN_ZIP, PDF;

    companion object {
        /** Bytes [sniff] needs to recognise every kind. */
        const val HEADER_BYTES = 8

        private val ZIP_LOCAL = bytes(0x50, 0x4B, 0x03, 0x04)
        private val ZIP_EMPTY = bytes(0x50, 0x4B, 0x05, 0x06)
        private val ZIP_SPANNED = bytes(0x50, 0x4B, 0x07, 0x08)
        private val ZIP_SPANNED_SINGLE = bytes(0x50, 0x4B, 0x30, 0x30)
        private val RAR4 = bytes(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00)
        private val RAR5 = bytes(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00)
        private val SEVEN_ZIP_SIG = bytes(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C)
        private val PDF_SIG = bytes(0x25, 0x50, 0x44, 0x46)

        /** Kind named by the first [length] bytes of [header], or null when none matches. */
        fun sniff(header: ByteArray, length: Int = header.size): ArchiveKind? {
            fun has(sig: ByteArray) = length >= sig.size && sig.indices.all { header[it] == sig[it] }
            return when {
                has(ZIP_LOCAL) || has(ZIP_EMPTY) || has(ZIP_SPANNED) || has(ZIP_SPANNED_SINGLE) -> ZIP
                has(RAR4) || has(RAR5) -> RAR
                has(SEVEN_ZIP_SIG) -> SEVEN_ZIP
                has(PDF_SIG) -> PDF
                else -> null
            }
        }

        /** Kind from the leading bytes of [file]; null when unreadable or unrecognised. */
        fun sniff(file: File): ArchiveKind? = try {
            file.inputStream().use { input ->
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

        /** Kind implied by the extension of [name] alone. */
        fun ofName(name: String): ArchiveKind? = when (Formats.ext(name)) {
            in Formats.ZIP -> ZIP
            in Formats.RAR -> RAR
            in Formats.SEVEN_ZIP -> SEVEN_ZIP
            in Formats.PDF -> PDF
            else -> null
        }

        /** Content first, so a `.cbr` that is really a zip opens as one; extension otherwise. */
        fun detect(file: File): ArchiveKind? = sniff(file) ?: ofName(file.name)

        private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
    }
}
