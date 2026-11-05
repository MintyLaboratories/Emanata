package com.infinity.comicreader.reader

import com.infinity.comicreader.compact.PageProbe

/**
 * What a page's bytes are, for Page info: its [kind]; whether it is [lossless] (null where
 * its bytes do not tell: GIF, JPEG XL); a [progressive] JPEG; an [animated] image; and how
 * many [layers] a layered AVIF holds (0 for none).
 */
data class PageFormat(
    val kind: ImageKind?,
    val lossless: Boolean?,
    val progressive: Boolean = false,
    val animated: Boolean = false,
    val layers: Int = 0
) {
    companion object {
        /** The format of page [bytes], by its header and boxes alone. */
        fun of(bytes: ByteArray): PageFormat = when (val kind = ImageKind.sniff(bytes)) {
            ImageKind.JPEG -> PageFormat(kind, false, progressive = ImageHeaders.jpegProgressive(bytes))
            ImageKind.PNG -> PageFormat(kind, true, animated = pngAnimated(bytes))
            ImageKind.BMP -> PageFormat(kind, true)
            ImageKind.TIFF -> PageFormat(kind, runCatching { Tiff.exact(Tiff.ByteInput(bytes)) }.getOrDefault(false))
            ImageKind.WEBP -> webp(bytes)
            ImageKind.AVIF -> PageFormat(kind, PageProbe.avifLossless(bytes, anyDepth = true), animated = PageProbe.isoSequence(bytes), layers = AvifPreview.layers(bytes))
            ImageKind.HEIF -> PageFormat(kind, false, animated = PageProbe.isoSequence(bytes))
            ImageKind.GIF, ImageKind.JXL -> PageFormat(kind, null)
            null -> PageFormat(null, null)
        }

        /** A PNG holding an animation: an `acTL` chunk before its image data. */
        private fun pngAnimated(bytes: ByteArray): Boolean = PageProbe.pngChunk(bytes, "acTL") != null

        /** A WebP's image chunk tells: `VP8L` lossless, `VP8 ` lossy; `ANIM` an animation, whose frames may be either. */
        private fun webp(bytes: ByteArray): PageFormat {
            var at = 12
            var animated = false
            while (at + 8 <= bytes.size) {
                val length = (bytes[at + 4].toInt() and 0xFF) or ((bytes[at + 5].toInt() and 0xFF) shl 8) or
                    ((bytes[at + 6].toInt() and 0xFF) shl 16) or ((bytes[at + 7].toInt() and 0xFF) shl 24)
                when (String(bytes, at, 4, Charsets.US_ASCII)) {
                    "VP8L" -> return PageFormat(ImageKind.WEBP, true, animated = animated)
                    "VP8 " -> return PageFormat(ImageKind.WEBP, false, animated = animated)
                    "ANIM" -> animated = true
                }
                if (length < 0 || length > bytes.size - at - 8) break
                at += 8 + length + (length and 1)
            }
            return PageFormat(ImageKind.WEBP, null, animated = animated)
        }
    }
}
