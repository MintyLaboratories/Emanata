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

    /** Width and height of a page by its header: PNG, JPEG, JPEG XL, BMP, WebP, TIFF, AVIF or HEIF; null when unknown. */
    fun sizeOf(bytes: ByteArray): IntArray? = when (ImageKind.sniff(bytes)) {
        ImageKind.AVIF, ImageKind.HEIF -> isoSize(bytes)
        ImageKind.PNG -> ImageHeaders.pngSize(bytes)
        ImageKind.JPEG -> ImageHeaders.jpegSize(bytes)
        ImageKind.JXL -> ImageHeaders.jxlSize(bytes)
        ImageKind.BMP -> if (bytes.size >= 26) intArrayOf(le32(bytes, 18), Math.abs(le32(bytes, 22))) else null
        ImageKind.WEBP -> webpSize(bytes)
        ImageKind.TIFF -> runCatching { Tiff.page(Tiff.ByteInput(bytes)).let { intArrayOf(it.width, it.height) } }.getOrNull()
        else -> null
    }?.takeIf { it[0] > 0 && it[1] > 0 }

    /** True for an AVIF or HEIF image sequence (an animation): its major brand says so. */
    fun isoSequence(bytes: ByteArray): Boolean = type(bytes, 4) == "ftyp" && type(bytes, 8) in SEQUENCE_BRANDS

    /**
     * The largest size an AVIF's or HEIF's `ispe` properties give (a grid's whole image is
     * the largest), from its first [ISO_SCAN] bytes; null when none is found.
     */
    private fun isoSize(b: ByteArray): IntArray? {
        var best: IntArray? = null
        val end = minOf(b.size, ISO_SCAN) - 16
        var at = 4
        while (at <= end) {
            if (b[at] == 'i'.code.toByte() && type(b, at) == "ispe") {
                val w = be32(b, at + 8)
                val h = be32(b, at + 12)
                if (w > 0 && h > 0 && (best == null || w.toLong() * h > best[0].toLong() * best[1])) best = intArrayOf(w, h)
                at += 16
            } else {
                at++
            }
        }
        return best
    }

    /**
     * True for an AVIF stored as libavif stores lossless images, so that it decodes to its
     * samples exactly: each `av1C` 4:4:4 of 8 bits (none monochrome: gray, or alpha, which
     * decodes premultiplied) and each `colr` an `nclx` with identity matrix coefficients
     * (0), full range, BT.709 or unspecified primaries and the sRGB, BT.709 or unspecified
     * curve, at least one of each. With [anyDepth], as any lossless AVIF is stored, its samples
     * of any depth, in any range, with any profile: 4:4:4 with identity matrix coefficients.
     */
    fun avifLossless(b: ByteArray, anyDepth: Boolean = false): Boolean {
        if (ImageKind.sniff(b) != ImageKind.AVIF) return false
        val meta = box(b, 0, b.size, "meta") ?: return false
        val iprp = box(b, meta.first + 4, meta.last + 1, "iprp") ?: return false
        val ipco = box(b, iprp.first, iprp.last + 1, "ipco") ?: return false
        var codecs = 0
        var colors = 0
        var ok = true
        boxes(b, ipco.first, ipco.last + 1) { kind, body ->
            when (kind) {
                "av1C" -> {
                    codecs++
                    if (body.last - body.first < 2) ok = false
                    else {
                        // seq_tier_0, high_bitdepth, twelve_bit, monochrome, chroma_subsampling_x, _y, chroma_sample_position.
                        val flags = b[body.first + 2].toInt() and 0xFF
                        if ((flags and (if (anyDepth) 0x0C else 0x7C)) != 0) ok = false
                    }
                }
                "colr" -> {
                    if (body.last - body.first < 10 || type(b, body.first) != "nclx") {
                        // A profile beside the nclx changes no sample.
                        if (!anyDepth || type(b, body.first) == "nclx") ok = false
                    } else {
                        colors++
                        val primaries = be16(b, body.first + 4)
                        val transfer = be16(b, body.first + 6)
                        val matrix = be16(b, body.first + 8)
                        val full = (b[body.first + 10].toInt() and 0x80) != 0
                        if (matrix != 0 || !anyDepth && (!full || primaries !in SRGB_PRIMARIES || transfer !in SRGB_TRANSFERS)) ok = false
                    }
                }
            }
        }
        return ok && codecs > 0 && colors > 0
    }

    /** The body (`first..last`) of the first box of [kind] among those in `[from, to)` of [b]; null when none. */
    private fun box(b: ByteArray, from: Int, to: Int, kind: String): IntRange? {
        var found: IntRange? = null
        boxes(b, from, to) { k, body -> if (found == null && k == kind) found = body }
        return found
    }

    /** Each box in `[from, to)` of [b], its type and body (`first..last`), until one does not fit. */
    private fun boxes(b: ByteArray, from: Int, to: Int, each: (String, IntRange) -> Unit) {
        var at = from.toLong()
        val end = minOf(to, b.size).toLong()
        while (at + 8 <= end) {
            val i = at.toInt()
            var size = be32(b, i).toLong() and 0xFFFFFFFFL
            var header = 8
            if (size == 1L) {
                if (at + 16 > end) return
                size = (be32(b, i + 8).toLong() shl 32) or (be32(b, i + 12).toLong() and 0xFFFFFFFFL)
                header = 16
            } else if (size == 0L) {
                size = end - at
            }
            if (size < header || at + size > end) return
            each(type(b, i + 4), (i + header) until (at + size).toInt())
            at += size
        }
    }

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

    private fun be16(b: ByteArray, at: Int): Int = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)

    private fun le32(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)

    /** Bytes of an AVIF or HEIF searched for its size: its `meta` box comes first. */
    private const val ISO_SCAN = 64 * 1024

    /** CICP primaries (BT.709, unspecified) and curves (BT.709, unspecified, sRGB) libavif's pixels come out in unchanged. */
    private val SRGB_PRIMARIES = setOf(1, 2)
    private val SRGB_TRANSFERS = setOf(1, 2, 13)

    /** Major brands of AVIF and HEIF image sequences. */
    private val SEQUENCE_BRANDS = setOf("avis", "msf1", "hevc", "hevx")

    private const val VP8X_ANIMATION = 0x02
    private const val VP8X_ICC = 0x20
}
