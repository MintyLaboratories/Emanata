package com.infinity.comicreader.compact

import java.io.ByteArrayOutputStream

/** Embeds an ICC profile in a WebP: Android's encoder writes none for pixels in another profile. */
object WebpIcc {
    /**
     * [webp] with [icc] embedded: an extended header (`VP8X`) marking it, the `ICCP` chunk
     * after it, then the image's chunks as they were. Null for what is not a WebP, or one
     * that already holds a profile.
     */
    fun embed(webp: ByteArray, icc: ByteArray): ByteArray? {
        if (webp.size < 20 || type(webp, 0) != "RIFF" || type(webp, 8) != "WEBP") return null
        val chunks = ArrayList<Pair<String, ByteArray>>()
        var at = 12
        while (at + 8 <= webp.size) {
            val length = le32(webp, at + 4)
            if (length < 0 || length > webp.size - at - 8) return null
            chunks += type(webp, at) to webp.copyOfRange(at + 8, at + 8 + length)
            at += 8 + length + (length and 1)
        }
        if (chunks.isEmpty() || chunks.any { it.first == "ICCP" }) return null
        val first = chunks[0]
        val extended: ByteArray = when (first.first) {
            "VP8X" -> first.second.copyOf().also { it[0] = (it[0].toInt() or FLAG_ICC).toByte() }
            "VP8L" -> {
                val d = first.second
                if (d.size < 5 || d[0].toInt() and 0xFF != 0x2F) return null
                val bits = (d[1].toLong() and 0xFF) or ((d[2].toLong() and 0xFF) shl 8) or ((d[3].toLong() and 0xFF) shl 16) or ((d[4].toLong() and 0xFF) shl 24)
                val width = (bits and 0x3FFF).toInt() + 1
                val height = ((bits shr 14) and 0x3FFF).toInt() + 1
                val alpha = (bits shr 28) and 1L != 0L
                header(FLAG_ICC or (if (alpha) FLAG_ALPHA else 0), width, height)
            }
            "VP8 " -> {
                val d = first.second
                if (d.size < 10) return null
                val width = ((d[6].toInt() and 0xFF) or ((d[7].toInt() and 0xFF) shl 8)) and 0x3FFF
                val height = ((d[8].toInt() and 0xFF) or ((d[9].toInt() and 0xFF) shl 8)) and 0x3FFF
                header(FLAG_ICC, width, height)
            }
            else -> return null
        }
        val out = ByteArrayOutputStream(webp.size + icc.size + 64)
        val rest = if (first.first == "VP8X") chunks.drop(1) else chunks
        val body = ByteArrayOutputStream()
        write(body, "VP8X", extended)
        write(body, "ICCP", icc)
        for ((kind, data) in rest) write(body, kind, data)
        out.write("RIFF".toByteArray(Charsets.ISO_8859_1))
        writeLe32(out, 4 + body.size())
        out.write("WEBP".toByteArray(Charsets.ISO_8859_1))
        body.writeTo(out)
        return out.toByteArray()
    }

    /** A `VP8X` chunk's data: [flags], then the canvas size. */
    private fun header(flags: Int, width: Int, height: Int): ByteArray {
        val d = ByteArray(10)
        d[0] = flags.toByte()
        val w = width - 1
        val h = height - 1
        d[4] = w.toByte()
        d[5] = (w shr 8).toByte()
        d[6] = (w shr 16).toByte()
        d[7] = h.toByte()
        d[8] = (h shr 8).toByte()
        d[9] = (h shr 16).toByte()
        return d
    }

    private fun write(out: ByteArrayOutputStream, kind: String, data: ByteArray) {
        out.write(kind.toByteArray(Charsets.ISO_8859_1))
        writeLe32(out, data.size)
        out.write(data)
        if (data.size and 1 != 0) out.write(0)
    }

    private fun writeLe32(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
        out.write((v shr 16) and 0xFF)
        out.write((v shr 24) and 0xFF)
    }

    private fun type(b: ByteArray, at: Int): String = String(b, at, 4, Charsets.ISO_8859_1)

    private fun le32(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)

    private const val FLAG_ICC = 0x20
    private const val FLAG_ALPHA = 0x10
}
