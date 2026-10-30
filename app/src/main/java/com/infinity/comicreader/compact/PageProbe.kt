package com.infinity.comicreader.compact

import com.infinity.comicreader.reader.ImageHeaders
import com.infinity.comicreader.reader.ImageKind
import com.infinity.comicreader.reader.Tiff
import java.io.IOException
import java.util.zip.InflaterInputStream

/** What a page's bytes are, as compaction tells them apart. */
enum class PageForm {
    JPEG,

    /** PNG, BMP, TIFF or WebP whose pixels decode exactly as stored ([PageProbe.formOf]). */
    LOSSLESS,

    JXL,

    /** Left as it is: lossy WebP, AVIF, HEIF, GIF, animations, PNG of 16 bits, anything else. */
    OTHER
}

/** Tells pages apart by their bytes alone. */
object PageProbe {
    /**
     * The form of [bytes]: [PageForm.LOSSLESS] for a still PNG of at most 8 bits per
     * sample, a BMP, a TIFF that decodes exactly ([Tiff.exact]) and a still lossless WebP.
     */
    fun formOf(bytes: ByteArray): PageForm = when (ImageKind.sniff(bytes)) {
        ImageKind.JPEG -> PageForm.JPEG
        ImageKind.PNG -> if (pngPlain(bytes)) PageForm.LOSSLESS else PageForm.OTHER
        ImageKind.BMP -> PageForm.LOSSLESS
        ImageKind.TIFF -> if (Tiff.exact(Tiff.ByteInput(bytes))) PageForm.LOSSLESS else PageForm.OTHER
        ImageKind.WEBP -> if (webpLossless(bytes)) PageForm.LOSSLESS else PageForm.OTHER
        ImageKind.JXL -> PageForm.JXL
        else -> PageForm.OTHER
    }

    /** True for a PNG of at most 8 bits per sample that is no animation (no `acTL` before its image data). */
    fun pngPlain(bytes: ByteArray): Boolean {
        if (bytes.size < 33 || type(bytes, 12) != "IHDR") return false
        if ((bytes[24].toInt() and 0xFF) > 8) return false
        var at = 8
        while (at + 8 <= bytes.size) {
            val length = be32(bytes, at)
            if (length < 0) return false
            when (type(bytes, at + 4)) {
                "acTL" -> return false
                "IDAT", "IEND" -> return true
            }
            at += 12 + length
        }
        return false
    }

    /**
     * The color profile of PNG [bytes] (its `iCCP` chunk, inflated); null without one,
     * and empty when it cannot be read. The platform decodes such a PNG converted, or as
     * sRGB, so [PageWork] reads its samples itself.
     */
    fun pngIcc(bytes: ByteArray): ByteArray? {
        val chunk = pngChunk(bytes, "iCCP") ?: return null
        // A name of 1-79 Latin-1 characters, a zero, the method (0: deflate), the profile.
        val end = chunk.indexOf(0)
        if (end !in 1..79 || end + 2 > chunk.size || chunk[end + 1].toInt() != 0) return ByteArray(0)
        return try {
            InflaterInputStream(chunk.inputStream(end + 2, chunk.size - end - 2)).use { it.readBytes() }
        } catch (e: IOException) {
            ByteArray(0)
        }
    }

    /** The data of PNG [bytes]' first chunk of [kind] before its image data; null without one. */
    fun pngChunk(bytes: ByteArray, kind: String): ByteArray? {
        if (bytes.size < 33 || type(bytes, 12) != "IHDR") return null
        var at = 8
        while (at + 8 <= bytes.size) {
            val length = be32(bytes, at)
            if (length < 0 || at + 12 + length > bytes.size) return null
            when (type(bytes, at + 4)) {
                kind -> return bytes.copyOfRange(at + 8, at + 8 + length)
                "IDAT", "IEND" -> return null
            }
            at += 12 + length
        }
        return null
    }

    /**
     * True for a still lossless WebP: its image a `VP8L` chunk, with no animation and no
     * color profile (which the platform may convert from, or drop).
     */
    fun webpLossless(bytes: ByteArray): Boolean {
        if (bytes.size < 20 || type(bytes, 0) != "RIFF" || type(bytes, 8) != "WEBP") return false
        var at = 12
        while (at + 8 <= bytes.size) {
            val length = le32(bytes, at + 4)
            if (length < 0) return false
            when (type(bytes, at)) {
                "VP8L" -> return true
                "VP8 ", "ANIM", "ANMF" -> return false
                "VP8X" -> if (at + 8 < bytes.size && bytes[at + 8].toInt() and (VP8X_ANIMATION or VP8X_ICC) != 0) return false
                "ICCP" -> return false
            }
            at += 8 + length + (length and 1)
        }
        return false
    }

    /** Width and height of a page by its header: PNG, JPEG, JPEG XL, BMP, WebP or TIFF; null when unknown. */
    fun sizeOf(bytes: ByteArray): IntArray? = when (ImageKind.sniff(bytes)) {
        ImageKind.PNG -> ImageHeaders.pngSize(bytes)
        ImageKind.JPEG -> ImageHeaders.jpegSize(bytes)
        ImageKind.JXL -> ImageHeaders.jxlSize(bytes)
        ImageKind.BMP -> if (bytes.size >= 26) intArrayOf(le32(bytes, 18), Math.abs(le32(bytes, 22))) else null
        ImageKind.WEBP -> webpSize(bytes)
        ImageKind.TIFF -> runCatching { Tiff.page(Tiff.ByteInput(bytes)).let { intArrayOf(it.width, it.height) } }.getOrNull()
        else -> null
    }?.takeIf { it[0] > 0 && it[1] > 0 }

    /** A WebP's canvas (`VP8X`) or image size (`VP8L`, `VP8 `), from its first chunk. */
    private fun webpSize(b: ByteArray): IntArray? {
        if (b.size < 30 || type(b, 0) != "RIFF" || type(b, 8) != "WEBP") return null
        fun u24(at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or ((b[at + 2].toInt() and 0xFF) shl 16)
        return when (type(b, 12)) {
            "VP8X" -> intArrayOf(u24(24) + 1, u24(27) + 1)
            "VP8L" -> le32(b, 21).let { intArrayOf((it and 0x3FFF) + 1, ((it shr 14) and 0x3FFF) + 1) }
            "VP8 " -> intArrayOf(u24(26) and 0x3FFF, u24(28) and 0x3FFF)
            else -> null
        }
    }

    /** True for a JPEG XL in a container holding the data to rebuild its JPEG (a `jbrd` box). */
    fun jxlHoldsJpeg(bytes: ByteArray): Boolean {
        if (bytes.size < 12 || type(bytes, 4) != "JXL ") return false
        var at = 0L
        while (at + 8 <= bytes.size) {
            val i = at.toInt()
            var size = be32(bytes, i).toLong() and 0xFFFFFFFFL
            val kind = type(bytes, i + 4)
            if (kind == "jbrd") return true
            if (size == 1L) {
                if (i + 16 > bytes.size) return false
                size = (be32(bytes, i + 8).toLong() shl 32) or (be32(bytes, i + 12).toLong() and 0xFFFFFFFFL)
            }
            // 0: the box runs to the end.
            if (size == 0L) return false
            if (size < 8) return false
            at += size
        }
        return false
    }

    private fun type(b: ByteArray, at: Int): String = if (at + 4 <= b.size) String(b, at, 4, Charsets.ISO_8859_1) else ""

    private fun be32(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)

    private fun le32(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)

    private const val VP8X_ANIMATION = 0x02
    private const val VP8X_ICC = 0x20
}
