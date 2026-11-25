package minty.emanata.reader

/**
 * Image dimensions read from the first bytes of a file, without a decoder. Each
 * reader returns `[width, height]` as displayed, or null when the bytes are not
 * that format or end before the size is known.
 */
object ImageHeaders {
    /** Bytes of a file that every reader here needs at most, short of a JXL container with large leading boxes. */
    const val PREFIX_BYTES = 64 * 1024

    /** Width and height from the IHDR chunk, which must directly follow the signature. */
    fun pngSize(bytes: ByteArray): IntArray? {
        if (ImageKind.sniff(bytes) != ImageKind.PNG || bytes.size < 24) return null
        if (String(bytes, 12, 4, Charsets.US_ASCII) != "IHDR") return null
        val w = be32(bytes, 16)
        val h = be32(bytes, 20)
        return if (w in 1L..Int.MAX_VALUE && h in 1L..Int.MAX_VALUE) intArrayOf(w.toInt(), h.toInt()) else null
    }

    /** True when an eXIf chunk precedes the image data, or [bytes] end before either is found. */
    fun pngMayHaveExif(bytes: ByteArray): Boolean {
        var at = 8L
        while (at + 8 <= bytes.size) {
            when (String(bytes, at.toInt() + 4, 4, Charsets.US_ASCII)) {
                "eXIf" -> return true
                "IDAT" -> return false
            }
            // Length, type, data and CRC.
            at += 12 + be32(bytes, at.toInt())
        }
        return true
    }

    /**
     * Size of a JPEG from its first frame header (SOFn), swapped for EXIF orientations
     * 5-8 as decoders apply them. Null when the bytes end first or the height is left to
     * a later marker (DNL).
     */
    fun jpegSize(bytes: ByteArray): IntArray? {
        val head = jpegHead(bytes) ?: return null
        return if (head[2] in 5..8) intArrayOf(head[1], head[0]) else intArrayOf(head[0], head[1])
    }

    /** The EXIF orientation of a JPEG, 1 without one; null when its frame header is not found. */
    fun jpegOrientation(bytes: ByteArray): Int? = jpegHead(bytes)?.get(2)

    /** True when a JPEG's frame header is progressive (SOF2, 6, 10 or 14), so any part of it decodes every scan. */
    fun jpegProgressive(bytes: ByteArray): Boolean = jpegHead(bytes)?.get(3) in PROGRESSIVE_SOF

    private val PROGRESSIVE_SOF = setOf(0xC2, 0xC6, 0xCA, 0xCE)

    /** `[width, height, orientation, frame marker]` of a JPEG as stored, before any EXIF orientation. */
    private fun jpegHead(bytes: ByteArray): IntArray? {
        if (bytes.size < 4 || bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) return null
        var at = 2
        var orientation = 1
        while (at + 4 <= bytes.size) {
            if (bytes[at] != 0xFF.toByte()) return null
            val marker = bytes[at + 1].toInt() and 0xFF
            // Fill bytes before a marker.
            if (marker == 0xFF) {
                at++
                continue
            }
            // Standalone markers carry no length.
            if (marker == 0x01 || marker in 0xD0..0xD8) {
                at += 2
                continue
            }
            if (marker == 0xDA || marker == 0xD9) return null
            val length = be16(bytes, at + 2)
            if (length < 2) return null
            val body = at + 4
            if (marker == 0xE1) exifOrientation(bytes, body, minOf(at + 2 + length, bytes.size))?.let { orientation = it }
            if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                if (body + 5 > bytes.size) return null
                val h = be16(bytes, body + 1)
                val w = be16(bytes, body + 3)
                if (w <= 0 || h <= 0) return null
                return intArrayOf(w, h, orientation, marker)
            }
            at += 2 + length
        }
        return null
    }

    /** The orientation tag of the EXIF block in `bytes[from, end)`, or null when absent. */
    private fun exifOrientation(bytes: ByteArray, from: Int, end: Int): Int? {
        if (end - from < 14 || String(bytes, from, 6, Charsets.ISO_8859_1) != "Exif\u0000\u0000") return null
        val tiff = from + 6
        val little = when (String(bytes, tiff, 2, Charsets.ISO_8859_1)) {
            "II" -> true
            "MM" -> false
            else -> return null
        }
        fun u16(at: Int) = if (little) (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8) else be16(bytes, at)
        fun u32(at: Int) = if (little) (u16(at).toLong() or (u16(at + 2).toLong() shl 16)) else be32(bytes, at)
        val ifd = tiff + u32(tiff + 4).toInt()
        if (ifd < tiff || ifd + 2 > end) return null
        val count = u16(ifd)
        for (k in 0 until count) {
            val entry = ifd + 2 + k * 12
            if (entry + 12 > end) return null
            if (u16(entry) == 0x0112) return u16(entry + 8)
        }
        return null
    }

    private fun be16(b: ByteArray, at: Int): Int = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)

    /**
     * Size of a JPEG XL image, bare codestream or ISO-BMFF container. Reads the
     * codestream's SizeHeader, then the orientation from ImageMetadata: orientations
     * 5-8 transpose the image, so width and height swap as a decoder reports them.
     */
    fun jxlSize(bytes: ByteArray): IntArray? {
        val start = jxlCodestream(bytes) ?: return null
        if (bytes.size < start + 2 || bytes[start] != 0xFF.toByte() || bytes[start + 1] != 0x0A.toByte()) return null
        return try {
            val bits = Bits(bytes, start + 2)
            val small = bits.flag()
            val height = if (small) (bits.u(5) + 1) * 8L else bits.sizeU32()
            val ratio = bits.u(3).toInt()
            val width = when {
                ratio != 0 -> height * RATIOS[ratio][0] / RATIOS[ratio][1]
                small -> (bits.u(5) + 1) * 8L
                else -> bits.sizeU32()
            }
            var orientation = 1
            if (!bits.flag() && bits.flag()) orientation = 1 + bits.u(3).toInt()
            if (width !in 1L..Int.MAX_VALUE || height !in 1L..Int.MAX_VALUE) return null
            if (orientation > 4) intArrayOf(height.toInt(), width.toInt())
            else intArrayOf(width.toInt(), height.toInt())
        } catch (e: IndexOutOfBoundsException) {
            null
        }
    }

    /** Offset of the codestream in [bytes]: 0 when bare, else the payload of the first `jxlc`/`jxlp` box. */
    private fun jxlCodestream(bytes: ByteArray): Int? {
        if (ImageKind.sniff(bytes) != ImageKind.JXL) return null
        if (bytes[0] == 0xFF.toByte()) return 0
        var pos = 0L
        while (pos + 8 <= bytes.size) {
            val at = pos.toInt()
            var size = be32(bytes, at)
            var header = 8
            if (size == 1L) {
                if (at + 16 > bytes.size) return null
                size = (be32(bytes, at + 8) shl 32) or be32(bytes, at + 12)
                header = 16
            } else if (size == 0L) {
                size = bytes.size - pos
            }
            when (String(bytes, at + 4, 4, Charsets.US_ASCII)) {
                "jxlc" -> return at + header
                // A partial codestream box opens with a 4-byte sequence number.
                "jxlp" -> return at + header + 4
            }
            if (size < header) return null
            pos += size
        }
        return null
    }

    /** Fixed width:height ratios of the SizeHeader, indexed by its 3-bit ratio field. */
    private val RATIOS = arrayOf(
        longArrayOf(0, 1), longArrayOf(1, 1), longArrayOf(12, 10), longArrayOf(4, 3),
        longArrayOf(3, 2), longArrayOf(16, 9), longArrayOf(5, 4), longArrayOf(2, 1)
    )

    private fun be32(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or
            ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)

    /** JPEG XL bit reader: least significant bit of each byte first. */
    private class Bits(private val bytes: ByteArray, start: Int) {
        private var bit = start * 8L

        fun u(n: Int): Long {
            var v = 0L
            for (k in 0 until n) {
                val i = bit++
                val byte = bytes[(i ushr 3).toInt()].toInt()
                v = v or (((byte shr (i and 7).toInt()) and 1).toLong() shl k)
            }
            return v
        }

        fun flag(): Boolean = u(1) == 1L

        /** U32(1 + u(9), 1 + u(13), 1 + u(18), 1 + u(30)): the distribution of a non-small dimension. */
        fun sizeU32(): Long = 1 + u(SIZE_BITS[u(2).toInt()])

        private companion object {
            val SIZE_BITS = intArrayOf(9, 13, 18, 30)
        }
    }
}
