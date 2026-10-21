package com.infinity.comicreader.reader

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/** Tiny synthetic encoded images for decoder tests. */
object TestImages {
    private val PNG_SIG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    /** A [width] x [height] gray PNG, 8 or 16 bits per channel, RGBA; [extra] chunks go before IDAT. */
    fun png(width: Int, height: Int, bitDepth: Int = 8, extra: List<Pair<String, ByteArray>> = emptyList()): ByteArray {
        val bytesPerPixel = 4 * bitDepth / 8
        val raw = ByteArrayOutputStream()
        repeat(height) {
            raw.write(0) // filter: none
            raw.write(ByteArray(width * bytesPerPixel) { 0x80.toByte() })
        }
        val ihdr = be32(width.toLong()) + be32(height.toLong()) + byteArrayOf(bitDepth.toByte(), 6, 0, 0, 0)
        val out = ByteArrayOutputStream()
        out.write(PNG_SIG)
        chunk(out, "IHDR", ihdr)
        for ((type, data) in extra) chunk(out, type, data)
        chunk(out, "IDAT", deflate(raw.toByteArray()))
        chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    fun chunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val body = type.toByteArray(Charsets.US_ASCII) + data
        out.write(be32(data.size.toLong()))
        out.write(body)
        out.write(be32(CRC32().apply { update(body) }.value))
    }

    fun be32(v: Long) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())

    private fun deflate(data: ByteArray): ByteArray {
        val d = Deflater().apply { setInput(data); finish() }
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end()
        return out.toByteArray()
    }

    /** A [width] x [height] 24-bit BMP, bottom-up rows, filled with the RGB [color]. */
    fun bmp(width: Int, height: Int, color: Int): ByteArray {
        val stride = (width * 3 + 3) / 4 * 4
        val pixels = stride * height
        val out = Le()
        out.raw("BM".toByteArray()).u32(54L + pixels).u32(0).u32(54)
        // BITMAPINFOHEADER: size, width, height, planes, bits, no compression, image size, 72 dpi, no palette.
        out.u32(40).u32(width.toLong()).u32(height.toLong()).u16(1).u16(24).u32(0).u32(pixels.toLong())
            .u32(2835).u32(2835).u32(0).u32(0)
        val row = ByteArray(stride)
        for (x in 0 until width) {
            row[x * 3] = color.toByte()
            row[x * 3 + 1] = (color shr 8).toByte()
            row[x * 3 + 2] = (color shr 16).toByte()
        }
        repeat(height) { out.raw(row) }
        return out.bytes()
    }

    /**
     * A [width] x [height] GIF89a with one frame per RGB color in [frames], each frame a
     * single color. The LZW data is uncompressed: a clear code before the table would
     * widen codes past 9 bits, so every pixel is one literal code.
     */
    fun gif(width: Int, height: Int, vararg frames: Int): ByteArray {
        val out = Le()
        out.raw("GIF89a".toByteArray()).u16(width).u16(height)
        // Global color table of 256 entries holding the frame colors.
        out.u8(0xF7).u8(0).u8(0)
        for (i in 0 until 256) {
            val c = frames.getOrElse(i) { 0 }
            out.u8(c shr 16).u8(c shr 8).u8(c)
        }
        // Loops forever (NETSCAPE2.0), each frame shown for 0.1 s.
        out.u8(0x21).u8(0xFF).u8(11).raw("NETSCAPE2.0".toByteArray()).u8(3).u8(1).u16(0).u8(0)
        for (index in frames.indices) {
            out.u8(0x21).u8(0xF9).u8(4).u8(0x04).u16(10).u8(0).u8(0)
            out.u8(0x2C).u16(0).u16(0).u16(width).u16(height).u8(0)
            out.u8(8)
            val codes = ArrayList<Int>()
            repeat(width * height) { i ->
                if (i % 250 == 0) codes += 256
                codes += index
            }
            codes += 257
            val data = ByteArrayOutputStream()
            var acc = 0
            var bits = 0
            for (code in codes) {
                acc = acc or (code shl bits)
                bits += 9
                while (bits >= 8) {
                    data.write(acc and 0xFF)
                    acc = acc ushr 8
                    bits -= 8
                }
            }
            if (bits > 0) data.write(acc and 0xFF)
            val packed = data.toByteArray()
            for (at in packed.indices step 255) {
                val n = minOf(255, packed.size - at)
                out.u8(n).raw(packed.copyOfRange(at, at + n))
            }
            out.u8(0)
        }
        out.u8(0x3B)
        return out.bytes()
    }

    /** Little-endian writer. */
    private class Le {
        private val out = ByteArrayOutputStream()
        fun u8(v: Int) = apply { out.write(v and 0xFF) }
        fun u16(v: Int) = apply { u8(v).u8(v ushr 8) }
        fun u32(v: Long) = apply { u16((v and 0xFFFF).toInt()).u16(((v ushr 16) and 0xFFFF).toInt()) }
        fun raw(b: ByteArray) = apply { out.write(b) }
        fun bytes(): ByteArray = out.toByteArray()
    }
}
