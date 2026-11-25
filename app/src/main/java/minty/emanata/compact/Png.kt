package minty.emanata.compact

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.Inflater

/**
 * Decoded pixels: [channels] samples per pixel (1 gray, 2 gray and alpha, 3 RGB, 4 RGBA)
 * of [bits] (8, or 16 little-endian), interleaved, rows top to bottom.
 */
class Pixels(val width: Int, val height: Int, val channels: Int, val bits: Int, val data: ByteArray) {
    init {
        require(channels in 1..4 && (bits == 8 || bits == 16))
        require(data.size.toLong() == width.toLong() * height * channels * (bits / 8))
    }

    /** True when [other] holds the same samples in the same layout. */
    fun same(other: Pixels?): Boolean =
        other != null && other.width == width && other.height == height && other.channels == channels && other.bits == bits &&
            other.data.contentEquals(data)
}

/**
 * PNG writing for [Pixels], with an ICC profile when given, and reading back what it
 * wrote (non-interlaced, any color type but palette) to check it.
 */
object Png {
    private val SIGNATURE = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10)

    /**
     * [pixels] as a PNG, embedding [icc] when given, and [significant], the bits of each
     * channel that hold the image, as `sBIT` when fewer than [Pixels.bits].
     */
    fun encode(pixels: Pixels, icc: ByteArray? = null, significant: IntArray? = null): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(SIGNATURE)
        val header = ByteArrayOutputStream(13)
        DataOutputStream(header).apply {
            writeInt(pixels.width)
            writeInt(pixels.height)
            writeByte(pixels.bits)
            writeByte(COLOR_TYPES[pixels.channels - 1])
            writeByte(0)
            writeByte(0)
            writeByte(0)
        }
        chunk(out, "IHDR", header.toByteArray())
        if (icc != null) {
            val profile = ByteArrayOutputStream()
            profile.write(ICC_NAME.toByteArray(Charsets.ISO_8859_1))
            profile.write(0)
            profile.write(0)
            DeflaterOutputStream(profile, Deflater(Deflater.BEST_COMPRESSION)).use { it.write(icc) }
            chunk(out, "iCCP", profile.toByteArray())
        }
        if (significant != null && significant.any { it < pixels.bits }) {
            require(significant.size == pixels.channels && significant.all { it in 1..pixels.bits })
            chunk(out, "sBIT", ByteArray(significant.size) { significant[it].toByte() })
        }
        chunk(out, "IDAT", deflate(filtered(pixels)))
        chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    /** The pixels of [png], as [encode] takes them; null for one it cannot read. */
    fun decode(png: ByteArray): Pixels? {
        if (png.size < 8 || !png.copyOf(8).contentEquals(SIGNATURE)) return null
        var at = 8
        var width = 0
        var height = 0
        var bits = 0
        var channels = 0
        val data = ByteArrayOutputStream()
        while (at + 12 <= png.size) {
            val length = be32(png, at)
            if (length < 0 || length > png.size - at - 12) return null
            val type = String(png, at + 4, 4, Charsets.ISO_8859_1)
            when (type) {
                "IHDR" -> {
                    width = be32(png, at + 8)
                    height = be32(png, at + 12)
                    bits = png[at + 16].toInt() and 0xFF
                    channels = COLOR_TYPES.indexOf(png[at + 17].toInt() and 0xFF) + 1
                    if (png[at + 20].toInt() != 0) return null
                }
                "IDAT" -> data.write(png, at + 8, length)
                "IEND" -> break
            }
            at += 12 + length
        }
        if (width <= 0 || height <= 0 || channels <= 0 || (bits != 8 && bits != 16)) return null
        val bytesPerPixel = channels * bits / 8
        val stride = width.toLong() * bytesPerPixel
        val raw = inflate(data.toByteArray(), (stride + 1) * height) ?: return null
        val out = ByteArray((stride * height).toInt())
        val row = stride.toInt()
        var prev = ByteArray(row)
        for (y in 0 until height) {
            val start = y * (row + 1)
            val line = raw.copyOfRange(start + 1, start + 1 + row)
            if (!unfilter(raw[start].toInt(), line, prev, bytesPerPixel)) return null
            if (bits == 16) {
                for (i in 0 until row step 2) {
                    out[y * row + i] = line[i + 1]
                    out[y * row + i + 1] = line[i]
                }
            } else {
                System.arraycopy(line, 0, out, y * row, row)
            }
            prev = line
        }
        return Pixels(width, height, channels, bits, out)
    }

    /** Each row with the filter that leaves the smallest sum of absolute values, PNG's usual guess at what deflates best. */
    private fun filtered(pixels: Pixels): ByteArray {
        val bpp = pixels.channels * pixels.bits / 8
        val row = pixels.width * bpp
        val out = ByteArray((row + 1) * pixels.height)
        var prev = ByteArray(row)
        val line = ByteArray(row)
        val trial = ByteArray(row)
        for (y in 0 until pixels.height) {
            // Big-endian samples in the file.
            if (pixels.bits == 16) {
                for (i in 0 until row step 2) {
                    line[i] = pixels.data[y * row + i + 1]
                    line[i + 1] = pixels.data[y * row + i]
                }
            } else {
                System.arraycopy(pixels.data, y * row, line, 0, row)
            }
            var best = 0
            var bestCost = Long.MAX_VALUE
            for (f in 0..4) {
                filter(f, line, prev, bpp, trial)
                var cost = 0L
                for (b in trial) cost += kotlin.math.abs(b.toInt())
                if (cost < bestCost) {
                    bestCost = cost
                    best = f
                }
            }
            val at = y * (row + 1)
            out[at] = best.toByte()
            filter(best, line, prev, bpp, trial)
            System.arraycopy(trial, 0, out, at + 1, row)
            prev = line.copyOf()
        }
        return out
    }

    private fun filter(type: Int, line: ByteArray, prev: ByteArray, bpp: Int, out: ByteArray) {
        for (i in line.indices) {
            val x = line[i].toInt() and 0xFF
            val a = if (i >= bpp) line[i - bpp].toInt() and 0xFF else 0
            val b = prev[i].toInt() and 0xFF
            val c = if (i >= bpp) prev[i - bpp].toInt() and 0xFF else 0
            out[i] = (x - predict(type, a, b, c)).toByte()
        }
    }

    /** Undoes filter [type] on [line] in place; false for an unknown filter. */
    private fun unfilter(type: Int, line: ByteArray, prev: ByteArray, bpp: Int): Boolean {
        if (type !in 0..4) return false
        for (i in line.indices) {
            val a = if (i >= bpp) line[i - bpp].toInt() and 0xFF else 0
            val b = prev[i].toInt() and 0xFF
            val c = if (i >= bpp) prev[i - bpp].toInt() and 0xFF else 0
            line[i] = ((line[i].toInt() and 0xFF) + predict(type, a, b, c)).toByte()
        }
        return true
    }

    private fun predict(type: Int, a: Int, b: Int, c: Int): Int = when (type) {
        1 -> a
        2 -> b
        3 -> (a + b) / 2
        4 -> {
            val p = a + b - c
            val pa = kotlin.math.abs(p - a)
            val pb = kotlin.math.abs(p - b)
            val pc = kotlin.math.abs(p - c)
            if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
        }
        else -> 0
    }

    private fun deflate(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size / 2 + 64)
        DeflaterOutputStream(out, Deflater(Deflater.BEST_COMPRESSION), 64 * 1024).use { it.write(data) }
        return out.toByteArray()
    }

    /** [data] inflated, which must give exactly [size] bytes. */
    private fun inflate(data: ByteArray, size: Long): ByteArray? {
        if (size > Int.MAX_VALUE) return null
        val inflater = Inflater()
        return try {
            inflater.setInput(data)
            val out = ByteArray(size.toInt())
            var n = 0
            while (n < out.size && !inflater.finished()) {
                val got = inflater.inflate(out, n, out.size - n)
                if (got == 0 && (inflater.needsInput() || inflater.needsDictionary())) return null
                n += got
            }
            if (n == out.size) out else null
        } finally {
            inflater.end()
        }
    }

    private fun chunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val head = DataOutputStream(out)
        head.writeInt(data.size)
        val typeBytes = type.toByteArray(Charsets.ISO_8859_1)
        out.write(typeBytes)
        out.write(data)
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data)
        head.writeInt(crc.value.toInt())
    }

    private fun be32(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)

    /** PNG color types for 1 to 4 channels: gray, gray and alpha, RGB, RGBA. */
    private val COLOR_TYPES = intArrayOf(0, 4, 2, 6)

    private const val ICC_NAME = "ICC profile"
}
