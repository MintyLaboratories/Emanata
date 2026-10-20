package com.infinity.comicreader.reader

/** Encoded image formats a page can hold, as told by its leading bytes. */
enum class ImageKind {
    JPEG, PNG, GIF, BMP, WEBP, AVIF, HEIF, JXL;

    companion object {
        /** Bytes [sniff] needs to recognise every kind, ISO-BMFF compatible brands included. */
        const val HEADER_BYTES = 64

        private val JPEG_SIG = bytes(0xFF, 0xD8, 0xFF)
        private val PNG_SIG = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        private val GIF87 = "GIF87a".toByteArray(Charsets.US_ASCII)
        private val GIF89 = "GIF89a".toByteArray(Charsets.US_ASCII)
        private val BMP_SIG = "BM".toByteArray(Charsets.US_ASCII)
        private val JXL_CODESTREAM = bytes(0xFF, 0x0A)
        private val JXL_CONTAINER = bytes(0x00, 0x00, 0x00, 0x0C, 0x4A, 0x58, 0x4C, 0x20, 0x0D, 0x0A, 0x87, 0x0A)

        private val AVIF_BRANDS = setOf("avif", "avis")
        private val HEIF_BRANDS = setOf("heic", "heix", "hevc", "hevx", "heim", "heis", "mif1", "msf1")

        /** Kind named by the first [length] bytes of [header], or null when none matches. */
        fun sniff(header: ByteArray, length: Int = header.size): ImageKind? {
            val n = minOf(length, header.size)
            fun has(sig: ByteArray, at: Int = 0) =
                n >= at + sig.size && sig.indices.all { header[at + it] == sig[it] }
            fun ascii(at: Int) = if (n >= at + 4) String(header, at, 4, Charsets.US_ASCII) else ""
            return when {
                has(JPEG_SIG) -> JPEG
                has(PNG_SIG) -> PNG
                has(GIF87) || has(GIF89) -> GIF
                has(JXL_CODESTREAM) || has(JXL_CONTAINER) -> JXL
                ascii(0) == "RIFF" && ascii(8) == "WEBP" -> WEBP
                ascii(4) == "ftyp" -> isoBrand(header, n)
                // Checked last: "BM" is the weakest signature.
                has(BMP_SIG) -> BMP
                else -> null
            }
        }

        /** AVIF or HEIF from an ISO-BMFF `ftyp` box: the major brand, then the compatible ones. */
        private fun isoBrand(header: ByteArray, n: Int): ImageKind? {
            val boxSize = ((header[0].toInt() and 0xFF) shl 24) or ((header[1].toInt() and 0xFF) shl 16) or
                ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
            val brands = ArrayList<String>()
            if (n >= 12) brands += String(header, 8, 4, Charsets.US_ASCII)
            var at = 16
            while (at + 4 <= minOf(n, boxSize)) {
                brands += String(header, at, 4, Charsets.US_ASCII)
                at += 4
            }
            return when {
                brands.any { it in AVIF_BRANDS } -> AVIF
                brands.any { it in HEIF_BRANDS } -> HEIF
                else -> null
            }
        }

        private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
    }
}
