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

    /** RGBA [pixels] (8 bits) as a WebP: lossless, else lossy at [quality]; null where the platform cannot (lossless before Android 11). */
    fun webp(pixels: Pixels, lossless: Boolean, quality: Int): ByteArray?
}

/**
 * Each page made anew, or left alone (null). [compact]: the smallest page without a
 * pixel changed, kept only when smaller. [compatible]: a JPEG XL page as one every reader
 * opens. Every page made is checked before it is kept: a JPEG rebuilt byte for byte, or
 * the pixels decoded back the same.
 */
class PageWork(private val jxl: JxlCodec, private val images: PlatformImages) {
    /** A page made anew: its [bytes] and extension [ext]; [reencoded] when its pixels changed slightly. */
    class Made(val bytes: ByteArray, val ext: String, val reencoded: Boolean = false)

    /** [page] made smaller without changing a pixel; null to keep it. */
    fun compact(page: ByteArray): Made? {
        val made = when (PageProbe.formOf(page)) {
            PageForm.JPEG -> transcode(page, EFFORT_TRANSCODE)
            PageForm.LOSSLESS -> exactPixels(page)?.let { lossless(reduced(it), null, EFFORT_LOSSLESS) }
            PageForm.JXL -> recompress(page)
            PageForm.OTHER -> null
        }
        return made?.takeIf { it.bytes.size < page.size }
    }

    /**
     * [page] as one every reader opens: a JPEG XL holding a JPEG as that JPEG, a lossless
     * one as lossless WebP (PNG before Android 11, and for 16 bits), a lossy one as WebP
     * at [LOSSY_QUALITY] ([Made.reencoded]); null for every other page.
     */
    fun compatible(page: ByteArray): Made? {
        if (ImageKind.sniff(page) != ImageKind.JXL) return null
        if (PageProbe.jxlHoldsJpeg(page)) jxl.reconstruct(page)?.let { return Made(it, "jpg") }
        val info = jxl.info(page) ?: return null
        if (info.animated) return null
        return if (info.lossless) losslessOut(page, info) else lossyOut(page, info)
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

    /** A lossless JPEG XL as lossless WebP, or PNG; its profile embedded unless sRGB. */
    private fun losslessOut(page: ByteArray, info: JxlInfo): Made? {
        val icc = if (info.srgb) null else jxl.iccOfPixels(page) ?: return null
        val bits = info.sampleBits
        if (bits == 8) {
            val rgba = jxl.decode(page, 4, 8)?.let { Pixels(info.width, info.height, 4, 8, it) } ?: return null
            val webp = images.webp(rgba, lossless = true, quality = WEBP_LOSSLESS_EFFORT)
            if (webp != null) {
                if (!rgba.same(images.decode(webp))) return null
                return Made(if (icc == null) webp else WebpIcc.embed(webp, icc) ?: return null, "webp")
            }
        }
        val pixels = jxl.decode(page, info.channels, bits)?.let { Pixels(info.width, info.height, info.channels, bits, it) } ?: return null
        val png = Png.encode(pixels, icc)
        return if (pixels.same(Png.decode(png))) Made(png, "png") else null
    }

    /** A lossy JPEG XL as lossy WebP, in sRGB where it converts, else in its own profile embedded. */
    private fun lossyOut(page: ByteArray, info: JxlInfo): Made? {
        var icc: ByteArray? = null
        val data = jxl.decode(page, 4, 8, srgb = true) ?: run {
            if (!info.srgb) icc = jxl.iccOfPixels(page) ?: return null
            jxl.decode(page, 4, 8)
        } ?: return null
        val webp = images.webp(Pixels(info.width, info.height, 4, 8, data), lossless = false, quality = LOSSY_QUALITY) ?: return null
        val out = icc?.let { WebpIcc.embed(webp, it) ?: return null } ?: webp
        return Made(out, "webp", reencoded = true)
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

        /** WebP quality of a lossy JPEG XL made compatible. */
        const val LOSSY_QUALITY = 90

        /** Android's lossless WebP takes its effort as "quality": 100 is the smallest. */
        const val WEBP_LOSSLESS_EFFORT = 100

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
