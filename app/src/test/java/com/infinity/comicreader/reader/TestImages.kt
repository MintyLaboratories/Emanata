package com.infinity.comicreader.reader

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/** Tiny synthetic encoded images for decoder tests. */
object TestImages {
    private val PNG_SIG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    /** A [width] x [height] grey PNG, 8 or 16 bits per channel, RGBA; [extra] chunks go before IDAT. */
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
}
