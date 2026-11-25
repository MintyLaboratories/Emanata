package minty.emanata.testing

import minty.emanata.compact.JxlCodec
import minty.emanata.compact.JxlInfo
import minty.emanata.compact.PageProbe
import minty.emanata.compact.PageWork
import minty.emanata.compact.Pixels
import minty.emanata.compact.PlatformImages
import minty.emanata.compact.Png
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

/**
 * Stands in for libjxl on the JVM, with files of its own making that pass for JPEG XL:
 * a JPEG "transcoded" is a container holding the JPEG deflated in a `jbrd` box; pixels
 * "encoded" are a codestream holding them deflated, lossless (`L`) or lossy (`Y`, made by
 * [lossy]). Each step can be made to fail. Safe to call from several threads at once, as
 * pages convert: what it counts and keeps is guarded by the instance.
 */
class FakeJxl : JxlCodec {
    var available_ = true
    override val available: Boolean get() = available_

    /** Rebuilt JPEGs come out one byte off. */
    var badReconstruct = false

    /** Encoded pixels decode one sample off. */
    var badDecode = false

    /** Lossy files decode to sRGB. */
    var convertsLossy = true

    var transcodes = 0
    var encodes = 0

    /** Efforts asked for, in order. */
    val efforts = ArrayList<Int>()

    /** Channels of the pixels encoded, in order. */
    val channelsEncoded = ArrayList<Int>()

    override fun transcode(jpeg: ByteArray, effort: Int): ByteArray {
        synchronized(this) {
            transcodes++
            efforts += effort
        }
        val packed = deflate(jpeg)
        val out = ByteArrayOutputStream()
        out.write(CONTAINER)
        out.write(be32(8 + packed.size))
        out.write("jbrd".toByteArray())
        out.write(packed)
        return out.toByteArray()
    }

    override fun reconstruct(jxl: ByteArray): ByteArray? {
        if (jxl.size < 20 || !jxl.copyOf(12).contentEquals(CONTAINER) || String(jxl, 16, 4) != "jbrd") return null
        val jpeg = inflate(jxl.copyOfRange(20, jxl.size))
        if (badReconstruct) jpeg[jpeg.size - 1] = (jpeg[jpeg.size - 1] + 1).toByte()
        return jpeg
    }

    override fun info(jxl: ByteArray): JxlInfo? {
        if (jxl.size < 3 || jxl[0] != 0xFF.toByte() || jxl[1] != 0x0A.toByte()) return null
        val h = ByteBuffer.wrap(jxl, 3, 11).order(ByteOrder.BIG_ENDIAN)
        val w = h.int
        val ht = h.int
        val channels = h.get().toInt()
        val bits = h.get().toInt()
        val srgb = h.get().toInt() == 1
        val alpha = channels == 2 || channels == 4
        return JxlInfo(
            w, ht, bits, 0, if (channels <= 2) 1 else 3, if (alpha) bits else 0, if (alpha) 1 else 0,
            originalProfile = jxl[2] == 'L'.code.toByte(), animated = false, srgb = srgb, alphaPremultiplied = false, alphaExponentBits = 0
        )
    }

    override fun profile(jxl: ByteArray): ByteArray = "profile".toByteArray()

    /** ICC profiles given to [encode], by what it made. */
    private val iccs = HashMap<ByteBuffer, ByteArray>()

    override fun iccOfPixels(jxl: ByteArray): ByteArray = synchronized(this) { iccs[ByteBuffer.wrap(jxl)] } ?: "icc".toByteArray()

    override fun decode(jxl: ByteArray, channels: Int, bits: Int, srgb: Boolean): ByteArray? {
        val info = info(jxl) ?: return null
        val lossy = !info.originalProfile
        // Only XYB converts, unless told otherwise.
        if (srgb && (!lossy || !convertsLossy)) return null
        if (bits != info.bits) return null
        val data = inflate(jxl.copyOfRange(14, jxl.size))
        if (badDecode) data[0] = (data[0] + 1).toByte()
        return convert(data, info.channels, channels, bits)
    }

    override fun encode(pixels: ByteArray, width: Int, height: Int, channels: Int, bits: Int, profile: ByteArray?, effort: Int): ByteArray {
        val out = codestream('L', width, height, channels, bits, profile == null, pixels)
        synchronized(this) {
            encodes++
            efforts += effort
            channelsEncoded += channels
            if (profile != null && profile[0] == JxlCodec.PROFILE_ICC) iccs[ByteBuffer.wrap(out)] = profile.copyOfRange(1, profile.size)
        }
        return out
    }

    /** A lossy JPEG XL of RGBA [pixels] (8 bits), as from elsewhere. */
    fun lossy(pixels: Pixels, srgb: Boolean = true): ByteArray = codestream('Y', pixels.width, pixels.height, 4, 8, srgb, pixels.data)

    private fun codestream(kind: Char, width: Int, height: Int, channels: Int, bits: Int, srgb: Boolean, pixels: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0xFF)
        out.write(0x0A)
        out.write(kind.code)
        out.write(ByteBuffer.allocate(11).order(ByteOrder.BIG_ENDIAN).putInt(width).putInt(height).put(channels.toByte()).put(bits.toByte())
            .put((if (srgb) 1 else 0).toByte()).array())
        out.write(deflate(pixels))
        return out.toByteArray()
    }

    companion object {
        val CONTAINER = byteArrayOf(0, 0, 0, 0x0C, 'J'.code.toByte(), 'X'.code.toByte(), 'L'.code.toByte(), ' '.code.toByte(), 0x0D, 0x0A, 0x87.toByte(), 0x0A)

        fun deflate(data: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            DeflaterOutputStream(out, Deflater(Deflater.BEST_COMPRESSION)).use { it.write(data) }
            return out.toByteArray()
        }

        fun inflate(data: ByteArray): ByteArray = InflaterInputStream(data.inputStream()).use { it.readBytes() }

        private fun be32(v: Int) = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(v).array()

        /** [data] of [from] channels as [to] channels (8 or 16 bits): gray spread to RGB, alpha opaque when added. */
        fun convert(data: ByteArray, from: Int, to: Int, bits: Int): ByteArray? {
            if (from == to) return data
            if (to != 4) return null
            val size = bits / 8
            val pixels = data.size / (from * size)
            val out = ByteArray(pixels * 4 * size)
            for (p in 0 until pixels) {
                for (c in 0 until 4) {
                    val src = when {
                        c < 3 -> if (from <= 2) 0 else c
                        from == 2 -> 1
                        from == 4 -> 3
                        else -> -1
                    }
                    for (b in 0 until size) {
                        out[(p * 4 + c) * size + b] = if (src < 0) (-1).toByte() else data[(p * from + src) * size + b]
                    }
                }
            }
            return out
        }
    }
}

/**
 * Stands in for the platform's image codecs: PNGs decoded by [Png], WebPs of its own
 * making (a real header, then the pixels deflated). [losslessWebp] false is Android before 11.
 * Safe to call from several threads at once.
 */
class FakeImages(var losslessWebp: Boolean = true) : PlatformImages {
    /** Decoded WebPs come out one sample off. */
    var badWebp = false

    /**
     * AVIF and HEIF pages it decodes: gray, of the size their `ispe` gives (2 x 2 without
     * one), half transparent with [isoAlpha]; others are taken as not decoding.
     */
    var decodesIso = true
    var isoAlpha = false

    /** JPEG qualities asked for, in order. */
    val jpegQualities = ArrayList<Int>()

    override fun isoPixels(page: ByteArray): Pixels? {
        if (!decodesIso) return null
        val size = PageProbe.sizeOf(page) ?: intArrayOf(2, 2)
        val data = ByteArray(size[0] * size[1] * 4) { if (it % 4 == 3 && !isoAlpha) (-1).toByte() else 0x80.toByte() }
        return Pixels(size[0], size[1], 4, 8, data)
    }

    /** A JPEG of its own making: its start of image, then the pixels deflated. */
    override fun jpeg(pixels: Pixels, quality: Int): ByteArray? {
        if (maxOf(pixels.width, pixels.height) > PageWork.JPEG_MAX_SIDE) return null
        synchronized(this) { jpegQualities += quality }
        return byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + FakeJxl.deflate(pixels.data)
    }

    override fun decode(bytes: ByteArray): Pixels? {
        Png.decode(bytes)?.let { png ->
            if (png.bits != 8) return null
            return Pixels(png.width, png.height, 4, 8, FakeJxl.convert(png.data, png.channels, 4, 8) ?: return null)
        }
        if (bytes.size < 30 || String(bytes, 0, 4) != "RIFF") return null
        val kind = String(bytes, 12, 4)
        val (w, h) = when (kind) {
            "VP8L" -> {
                val bits = ByteBuffer.wrap(bytes, 21, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                ((bits and 0x3FFF) + 1).toInt() to (((bits shr 14) and 0x3FFF) + 1).toInt()
            }
            "VP8 " -> (bytes[26].toInt() and 0xFF or ((bytes[27].toInt() and 0xFF) shl 8)) to (bytes[28].toInt() and 0xFF or ((bytes[29].toInt() and 0xFF) shl 8))
            else -> return null
        }
        val start = if (kind == "VP8L") 25 else 30
        val length = ByteBuffer.wrap(bytes, 16, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val data = FakeJxl.inflate(bytes.copyOfRange(start, 20 + length))
        if (badWebp) data[0] = (data[0] + 1).toByte()
        return Pixels(w, h, 4, 8, data)
    }

    override fun webp(pixels: Pixels, lossless: Boolean, quality: Int): ByteArray? {
        if (lossless && !losslessWebp || maxOf(pixels.width, pixels.height) > PageWork.WEBP_MAX_SIDE) return null
        val packed = FakeJxl.deflate(pixels.data)
        val chunk = ByteArrayOutputStream()
        if (lossless) {
            chunk.write(0x2F)
            val bits = (pixels.width - 1).toLong() or ((pixels.height - 1).toLong() shl 14)
            chunk.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(bits.toInt()).array())
        } else {
            chunk.write(byteArrayOf(0, 0, 0, 0x9D.toByte(), 0x01, 0x2A))
            chunk.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putShort(pixels.width.toShort()).putShort(pixels.height.toShort()).array())
        }
        chunk.write(packed)
        val data = chunk.toByteArray()
        val out = ByteArrayOutputStream()
        out.write("RIFF".toByteArray())
        val padded = data.size + (data.size and 1)
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(4 + 8 + padded).array())
        out.write("WEBP".toByteArray())
        out.write((if (lossless) "VP8L" else "VP8 ").toByteArray())
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(data.size).array())
        out.write(data)
        if (data.size and 1 != 0) out.write(0)
        return out.toByteArray()
    }
}
