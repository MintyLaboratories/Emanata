package com.infinity.comicreader.compact

import com.infinity.comicreader.reader.ImageKind
import com.infinity.comicreader.reader.Tiff

/** What compaction needs of the platform's image codecs. */
interface PlatformImages {
    /**
     * A PNG, BMP or WebP as RGBA of 8 bits, not premultiplied; null unless decoded exactly
     * as stored, in sRGB.
     */
    fun decode(bytes: ByteArray): Pixels?

    /**
     * RGBA [pixels] (8 bits) as a WebP: lossless, else lossy at [quality]; null where the
     * platform cannot (lossless before Android 11, a side over [PageWork.WEBP_MAX_SIDE]).
     */
    fun webp(pixels: Pixels, lossless: Boolean, quality: Int): ByteArray?

    /** Opaque RGBA [pixels] (8 bits) as a JPEG at [quality]; null where the platform cannot. */
    fun jpeg(pixels: Pixels, quality: Int): ByteArray?

    /**
     * An AVIF (its full image, layered ones too) or HEIF page as RGBA of 8 bits, not
     * premultiplied, in sRGB; null where it does not decode.
     */
    fun isoPixels(page: ByteArray): Pixels?
}

/**
 * Each page made anew, or left alone (null). [compact]: the smallest page without a
 * pixel changed, kept only when smaller. [compatible]: a page in a format few readers
 * open (JPEG XL, AVIF, HEIF) as one every reader opens. Every page made losslessly is
 * checked before it is kept: a JPEG rebuilt byte for byte, or the pixels decoded back the same.
 */
class PageWork(private val jxl: JxlCodec, private val images: PlatformImages) {
    /** A page made anew: its [bytes] and extension [ext]; [reencoded] when its pixels changed slightly. */
    class Made(val bytes: ByteArray, val ext: String, val reencoded: Boolean = false)

    /** A page that should have been made anew and could not: it stays as it is. */
    class Unconverted(why: String) : Exception(why)

    /**
     * About the memory (Java heap) making [page] anew takes at most: its bytes three times
     * over (the page, what it becomes, what that gives back), and for decoded pixels
     * [BYTES_PER_PIXEL] per pixel; [Long.MAX_VALUE] when its size is unknown.
     */
    fun memoryFor(page: ByteArray): Long {
        val bytes = page.size * 3L
        if (ImageKind.sniff(page) == ImageKind.JPEG) return bytes
        val size = PageProbe.sizeOf(page) ?: return Long.MAX_VALUE
        return bytes + size[0].toLong() * size[1] * BYTES_PER_PIXEL
    }

    /** [page] made smaller without changing a pixel; null to keep it. */
    fun compact(page: ByteArray): Made? {
        val made = when (PageProbe.formOf(page)) {
            PageForm.JPEG -> transcode(page, EFFORT_TRANSCODE)
            PageForm.LOSSLESS -> losslessIn(page)
            PageForm.JXL -> recompress(page)
            PageForm.OTHER -> null
        }
        return made?.takeIf { it.bytes.size < page.size }
    }

    /**
     * [page] as one every reader opens: a JPEG XL holding a JPEG as that JPEG, a lossless
     * one, and an AVIF stored lossless ([PageProbe.avifLossless]), as lossless WebP (PNG
     * before Android 11, for 16 bits, for a side too long, and where the WebP does not
     * decode back the same); a lossy JPEG XL, any other AVIF or a HEIF as very-high-quality
     * WebP ([lossy]); null for every other page. Throws [Unconverted]
     * for one it cannot make so, an animation among them.
     */
    fun compatible(page: ByteArray): Made? {
        when (ImageKind.sniff(page)) {
            ImageKind.JXL -> Unit
            ImageKind.AVIF -> return if (PageProbe.avifLossless(page)) losslessIso(page) else reencoded(page)
            ImageKind.HEIF -> return reencoded(page)
            else -> return null
        }
        if (PageProbe.jxlHoldsJpeg(page)) jxl.reconstruct(page)?.let { return Made(it, "jpg") }
        val info = jxl.info(page) ?: throw Unconverted("Unreadable JPEG XL")
        if (info.animated) throw Unconverted("Animated JPEG XL")
        return (if (info.lossless) losslessOut(page, info) else lossyOut(page, info)) ?: throw Unconverted("JPEG XL not converted")
    }

    /** An AVIF or HEIF still image made [lossy]. */
    private fun reencoded(page: ByteArray): Made = lossy(isoPixels(page), null) ?: throw Unconverted("Image not converted")

    /** A lossless AVIF still image as lossless WebP, else PNG. */
    private fun losslessIso(page: ByteArray): Made {
        val pixels = isoPixels(page)
        return losslessWebp(pixels, null) ?: png(reduced(pixels), null) ?: throw Unconverted("Image not converted")
    }

    private fun isoPixels(page: ByteArray): Pixels {
        if (PageProbe.isoSequence(page)) throw Unconverted("Animated image")
        return images.isoPixels(page) ?: throw Unconverted("Image not decoded")
    }

    /** The JPEG [jpeg] transcoded at [effort], kept once it rebuilds byte for byte. */
    private fun transcode(jpeg: ByteArray, effort: Int): Made? {
        val out = jxl.transcode(jpeg, effort) ?: return null
        return if (jxl.reconstruct(out)?.contentEquals(jpeg) == true) Made(out, "jxl") else null
    }

    /** [pixels] in [profile] (sRGB when null) as lossless JPEG XL, kept once it decodes back the same. */
    private fun lossless(pixels: Pixels, profile: ByteArray?, effort: Int): Made? {
        val out = jxl.encode(pixels.data, pixels.width, pixels.height, pixels.channels, pixels.bits, profile, effort) ?: return null
        val back = jxl.decode(out, pixels.channels, pixels.bits) ?: return null
        return if (back.contentEquals(pixels.data)) Made(out, "jxl") else null
    }

    /** A JPEG XL made smaller: one holding a JPEG transcoded anew, a lossless one encoded anew in its own profile. */
    private fun recompress(page: ByteArray): Made? {
        if (PageProbe.jxlHoldsJpeg(page)) return jxl.reconstruct(page)?.let { transcode(it, EFFORT_REPACK) }
        val info = jxl.info(page) ?: return null
        if (!info.lossless || (info.bits != 8 && info.bits != 16) || (info.alphaBits != 0 && info.alphaBits != info.bits)) return null
        val data = jxl.decode(page, info.channels, info.bits) ?: return null
        val profile = jxl.profile(page) ?: return null
        val made = lossless(Pixels(info.width, info.height, info.channels, info.bits, data), profile, EFFORT_RECOMPRESS) ?: return null
        val icc = jxl.iccOfPixels(page) ?: return null
        return made.takeIf { jxl.iccOfPixels(it.bytes)?.contentEquals(icc) == true }
    }

    /**
     * A lossless JPEG XL as lossless WebP, else PNG (the WebP encoder may change the color
     * of pixels fully transparent); its profile embedded unless sRGB.
     */
    private fun losslessOut(page: ByteArray, info: JxlInfo): Made? {
        val icc = if (info.srgb) null else jxl.iccOfPixels(page) ?: return null
        val bits = info.sampleBits
        if (bits == 8) {
            val rgba = jxl.decode(page, 4, 8)?.let { Pixels(info.width, info.height, 4, 8, it) } ?: return null
            losslessWebp(rgba, icc)?.let { return it }
        }
        val pixels = jxl.decode(page, info.channels, bits)?.let { Pixels(info.width, info.height, info.channels, bits, it) } ?: return null
        return png(pixels, icc)
    }

    /** RGBA [rgba] (8 bits) as lossless WebP, profile [icc] embedded, once it decodes back the same; else null. */
    private fun losslessWebp(rgba: Pixels, icc: ByteArray?): Made? {
        val webp = images.webp(rgba, lossless = true, quality = WEBP_LOSSLESS_EFFORT) ?: return null
        if (!rgba.same(images.decode(webp))) return null
        val out = if (icc == null) webp else WebpIcc.embed(webp, icc) ?: return null
        return Made(out, "webp")
    }

    /** [pixels] as PNG, profile [icc] embedded, once it decodes back the same; else null. */
    private fun png(pixels: Pixels, icc: ByteArray?): Made? {
        val png = Png.encode(pixels, icc)
        return if (pixels.same(Png.decode(png))) Made(png, "png") else null
    }

    /** A lossy JPEG XL made [lossy], in sRGB where it converts, else in its own profile. */
    private fun lossyOut(page: ByteArray, info: JxlInfo): Made? {
        var icc: ByteArray? = null
        val data = jxl.decode(page, 4, 8, srgb = true) ?: run {
            if (!info.srgb) icc = jxl.iccOfPixels(page) ?: return null
            jxl.decode(page, 4, 8)
        } ?: return null
        return lossy(Pixels(info.width, info.height, 4, 8, data), icc)
    }

    /**
     * The decoded RGBA pixels of a lossy page, in sRGB or in profile [icc] (embedded), as
     * very-high-quality WebP ([LOSSY_QUALITY], [Made.reencoded]); where a side is too long
     * for WebP ([WEBP_MAX_SIDE]), as a JPEG ([JPEG_QUALITY]) when opaque, in sRGB and
     * short enough ([JPEG_MAX_SIDE]), else as a PNG of those pixels.
     */
    private fun lossy(pixels: Pixels, icc: ByteArray?): Made? {
        val side = maxOf(pixels.width, pixels.height)
        if (side <= WEBP_MAX_SIDE) {
            val webp = images.webp(pixels, lossless = false, quality = LOSSY_QUALITY) ?: return null
            val out = icc?.let { WebpIcc.embed(webp, it) ?: return null } ?: webp
            return Made(out, "webp", reencoded = true)
        }
        if (icc == null && side <= JPEG_MAX_SIDE && !hasAlpha(pixels)) images.jpeg(pixels, JPEG_QUALITY)?.let { return Made(it, "jpg", reencoded = true) }
        return png(reduced(pixels), icc)
    }

    /**
     * A lossless page as lossless JPEG XL: a PNG with a color profile read here, its samples
     * as stored and the profile kept as it is; any other as the platform decodes it, in sRGB.
     */
    private fun losslessIn(page: ByteArray): Made? {
        val icc = if (ImageKind.sniff(page) == ImageKind.PNG) PageProbe.pngIcc(page) else null
        if (icc == null) return exactPixels(page)?.let { lossless(reduced(it), null, EFFORT_LOSSLESS) }
        // Unreadable, or with a transparent color, which this reading leaves out.
        if (icc.isEmpty() || PageProbe.pngChunk(page, "tRNS") != null) return null
        val pixels = Png.decode(page)?.takeIf { it.bits == 8 } ?: return null
        val made = lossless(pixels, byteArrayOf(JxlCodec.PROFILE_ICC) + icc, EFFORT_LOSSLESS) ?: return null
        return made.takeIf { jxl.iccOfPixels(it.bytes)?.contentEquals(icc) == true }
    }

    /** A lossless page's pixels as stored; null unless decoded exactly. */
    private fun exactPixels(page: ByteArray): Pixels? =
        if (ImageKind.sniff(page) == ImageKind.TIFF) tiff(page) else images.decode(page)

    /** A TIFF that decodes exactly ([Tiff.exact]) as RGBA. */
    private fun tiff(page: ByteArray): Pixels? = try {
        val input = Tiff.ByteInput(page)
        val p = Tiff.page(input)
        val out = ByteArray(p.width * p.height * 4)
        Tiff.decode(input, p, 1, { _, _ -> throw IllegalStateException("JPEG in TIFF") }) { x, y, pixels, count ->
            var o = (y * p.width + x) * 4
            for (i in 0 until count) {
                val c = pixels[i]
                out[o++] = (c shr 16).toByte()
                out[o++] = (c shr 8).toByte()
                out[o++] = c.toByte()
                out[o++] = (c ushr 24).toByte()
            }
        }
        Pixels(p.width, p.height, 4, 8, out)
    } catch (e: Exception) {
        null
    }

    companion object {
        /** libjxl efforts: JPEG transcode and lossless as quick as useful, and higher for files already JPEG XL, which a quick pass would not shrink. */
        const val EFFORT_TRANSCODE = 7
        const val EFFORT_LOSSLESS = 7
        const val EFFORT_REPACK = 9
        const val EFFORT_RECOMPRESS = 9

        /** WebP quality of a lossy page made compatible: very high, the smallest that shows no loss in comparisons. */
        const val LOSSY_QUALITY = 90

        /** JPEG quality of a lossy page too tall or wide for WebP: very high too. */
        const val JPEG_QUALITY = 95

        /** Longest side WebP holds. */
        const val WEBP_MAX_SIDE = 16_383

        /** Longest side the platform's JPEG encoder (libjpeg-turbo) writes. */
        const val JPEG_MAX_SIDE = 65_500

        /** Android's lossless WebP takes its effort as "quality": 100 is the smallest. */
        const val WEBP_LOSSLESS_EFFORT = 100

        /** Bytes per pixel decoded pages hold at most: RGBA, its fewest channels and what decodes back, or RGBA and PNG's filtered rows. */
        const val BYTES_PER_PIXEL = 16

        /** True when any pixel of RGBA [rgba] (8 bits) is not opaque. */
        fun hasAlpha(rgba: Pixels): Boolean {
            require(rgba.channels == 4 && rgba.bits == 8)
            val d = rgba.data
            var i = 3
            while (i < d.size) {
                if (d[i] != (-1).toByte()) return true
                i += 4
            }
            return false
        }

        /** RGBA [rgba] in the fewest channels that hold it: gray when every pixel is, alpha only when any is not opaque. */
        fun reduced(rgba: Pixels): Pixels {
            require(rgba.channels == 4 && rgba.bits == 8)
            val d = rgba.data
            var gray = true
            var alpha = false
            var i = 0
            while (i < d.size && (gray || !alpha)) {
                if (d[i] != d[i + 1] || d[i] != d[i + 2]) gray = false
                if (d[i + 3] != (-1).toByte()) alpha = true
                i += 4
            }
            val channels = (if (gray) 1 else 3) + (if (alpha) 1 else 0)
            if (channels == 4) return rgba
            val out = ByteArray(rgba.width * rgba.height * channels)
            var o = 0
            i = 0
            while (i < d.size) {
                out[o++] = d[i]
                if (!gray) {
                    out[o++] = d[i + 1]
                    out[o++] = d[i + 2]
                }
                if (alpha) out[o++] = d[i + 3]
                i += 4
            }
            return Pixels(rgba.width, rgba.height, channels, 8, out)
        }
    }
}
