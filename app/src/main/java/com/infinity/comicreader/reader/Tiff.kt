package com.infinity.comicreader.reader

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.zip.InflaterInputStream

/**
 * A small baseline TIFF decoder; Android has none. Free of Android types: pixels go
 * to a [Sink] row by row, and JPEG-compressed chunks to a [JpegDecoder] the caller
 * supplies (the platform's).
 *
 * Reads the first full-resolution page of classic or BigTIFF files, either byte
 * order, in strips or tiles, chunky or planar. Compression: none, PackBits, LZW,
 * Deflate, and JPEG ("new-style", with shared tables). Pixels: bilevel and gray of
 * 1-16 bits, palette of 1-8 bits, RGB and CMYK of 8 or 16 bits, each with an optional
 * alpha channel; 16-bit samples are reduced to 8. Horizontal differencing is undone.
 * Not read: CCITT fax and old-style JPEG compression, floating-point samples,
 * uncompressed YCbCr, and the Orientation tag (sizes and pixels both ignore it).
 */
object Tiff {

    /** Random access to the encoded file. */
    interface Input {
        val length: Long

        /** Reads exactly [len] bytes at [pos] into [buf] from [off]; throws [EOFException] past the end. */
        fun read(pos: Long, buf: ByteArray, off: Int, len: Int)
    }

    class ByteInput(private val bytes: ByteArray) : Input {
        override val length: Long get() = bytes.size.toLong()

        override fun read(pos: Long, buf: ByteArray, off: Int, len: Int) {
            if (pos < 0 || pos + len > bytes.size) throw EOFException("TIFF data ends early")
            System.arraycopy(bytes, pos.toInt(), buf, off, len)
        }
    }

    class FileInput(private val file: RandomAccessFile) : Input {
        override val length: Long = file.length()

        override fun read(pos: Long, buf: ByteArray, off: Int, len: Int) {
            if (pos < 0 || pos + len > length) throw EOFException("TIFF data ends early")
            file.seek(pos)
            file.readFully(buf, off, len)
        }
    }

    /** Takes [count] ARGB pixels (not premultiplied) from [pixels] for output row [y], columns [x] on. */
    fun interface Sink {
        fun row(x: Int, y: Int, pixels: IntArray, count: Int)
    }

    /** A decoded JPEG chunk: [width] x [height] ARGB [pixels], one per [scale] x [scale] chunk pixels. */
    class Raster(val width: Int, val height: Int, val pixels: IntArray, val scale: Int = 1)

    /** Decodes one complete JPEG stream, reduced by up to [sample] (a power of two) when the decoder can. */
    fun interface JpegDecoder {
        fun decode(jpeg: ByteArray, sample: Int): Raster
    }

    /** The page a decode shows and how it is stored. */
    class Page internal constructor(
        val width: Int,
        val height: Int,
        internal val compression: Int,
        internal val photometric: Int,
        internal val bits: Int,
        internal val samples: Int,
        internal val planar: Boolean,
        internal val predictor: Int,
        internal val alpha: Int,
        internal val chunkWidth: Int,
        internal val chunkHeight: Int,
        internal val offsets: LongArray,
        internal val counts: LongArray?,
        internal val colorMap: IntArray?,
        internal val jpegTables: ByteArray?,
        internal val bigEndian: Boolean,
        internal val colorMap8: Boolean = false
    ) {
        /** True when the page has an alpha channel. */
        val hasAlpha: Boolean get() = alpha != 0

        /** True when the page is stored JPEG-compressed. */
        val jpegCompressed: Boolean get() = compression == COMPRESSION_JPEG

        /** True when the page is gray (bilevel or gray samples), with or without alpha. */
        val isGray: Boolean get() = photometric == PHOTO_WHITE_IS_ZERO || photometric == PHOTO_BLACK_IS_ZERO

        /**
         * True when [decodeSamples] reads the page: 16-bit gray or RGB samples, alpha
         * unassociated or none, not JPEG-compressed.
         */
        val sixteenBit: Boolean
            get() = bits == 16 && compression != COMPRESSION_JPEG && (isGray || photometric == PHOTO_RGB) && alpha != ALPHA_ASSOCIATED

        /** Samples per pixel [decodeSamples] gives: gray or RGB, then alpha when there is one. */
        val channels: Int get() = (if (isGray) 1 else 3) + (if (hasAlpha) 1 else 0)
    }

    /** Raw samples of a run of [count] pixels starting at ([x], [y]): [Page.channels] per pixel in [samples]. */
    fun interface SampleSink {
        fun row(x: Int, y: Int, samples: IntArray, count: Int)
    }

    /** `[width, height]` of the page a decode shows, from the headers alone; null when not a readable TIFF. */
    fun size(input: Input): IntArray? = try {
        val ifd = pageIfd(input)
        val w = ifd.int(WIDTH)
        val h = ifd.int(HEIGHT)
        if (w > 0 && h > 0) intArrayOf(w, h) else null
    } catch (e: IOException) {
        null
    } catch (e: RuntimeException) {
        null
    }

    /** The page a decode shows; throws [DecodeException] when it is stored in a way not read here. */
    fun page(input: Input): Page {
        val ifd = pageIfd(input)
        val width = ifd.int(WIDTH)
        val height = ifd.int(HEIGHT)
        if (width <= 0 || height <= 0) throw DecodeException("TIFF has no image size")
        val compression = ifd.int(COMPRESSION, 1)
        if (compression !in COMPRESSIONS) {
            throw DecodeException("TIFF compression ${COMPRESSION_NAMES[compression] ?: compression.toString()} is not supported")
        }
        val samples = ifd.int(SAMPLES_PER_PIXEL, 1)
        val bitsPerSample = ifd.longs(BITS_PER_SAMPLE) ?: longArrayOf(1)
        val bits = bitsPerSample[0].toInt()
        if (bitsPerSample.any { it.toInt() != bits }) throw DecodeException("TIFF samples of mixed sizes are not supported")
        val format = ifd.int(SAMPLE_FORMAT, 1)
        if (format != 1 && compression != COMPRESSION_JPEG) throw DecodeException("TIFF sample format $format is not supported")
        val photometric = ifd.int(PHOTOMETRIC, if (samples >= 3) PHOTO_RGB else PHOTO_BLACK_IS_ZERO)
        val base = when (photometric) {
            PHOTO_WHITE_IS_ZERO, PHOTO_BLACK_IS_ZERO, PHOTO_PALETTE -> 1
            PHOTO_RGB -> 3
            PHOTO_CMYK -> 4
            PHOTO_YCBCR -> if (compression == COMPRESSION_JPEG) 3 else throw DecodeException("Uncompressed YCbCr TIFF is not supported")
            else -> throw DecodeException("TIFF color model $photometric is not supported")
        }
        if (samples < base) throw DecodeException("TIFF has too few samples per pixel")
        if (compression != COMPRESSION_JPEG) {
            val allowed = when (photometric) {
                PHOTO_PALETTE -> bits in 1..8
                PHOTO_RGB, PHOTO_CMYK -> bits == 8 || bits == 16
                else -> bits in 1..16
            }
            if (!allowed) throw DecodeException("$bits-bit TIFF of this color model is not supported")
        }
        val predictor = ifd.int(PREDICTOR, 1)
        if (predictor != 1 && (predictor != 2 || (bits != 8 && bits != 16))) throw DecodeException("TIFF predictor $predictor is not supported")
        // The first extra sample is alpha when marked so; unspecified extras are ignored.
        val extra = ifd.longs(EXTRA_SAMPLES)?.firstOrNull()?.toInt() ?: 0
        val alpha = if (samples > base && (extra == ALPHA_ASSOCIATED || extra == ALPHA_UNASSOCIATED)) extra else 0
        val planar = ifd.int(PLANAR_CONFIG, 1) == 2 && samples > 1
        if (planar && compression == COMPRESSION_JPEG) throw DecodeException("Planar JPEG TIFF is not supported")
        val tiled = ifd.has(TILE_WIDTH)
        val chunkWidth = if (tiled) ifd.int(TILE_WIDTH) else width
        val chunkHeight = if (tiled) ifd.int(TILE_LENGTH) else ifd.long(ROWS_PER_STRIP, height.toLong()).coerceIn(1, height.toLong()).toInt()
        if (chunkWidth <= 0 || chunkHeight <= 0) throw DecodeException("TIFF tiles have no size")
        val offsets = ifd.longs(if (tiled) TILE_OFFSETS else STRIP_OFFSETS) ?: throw DecodeException("TIFF has no image data")
        val counts = ifd.longs(if (tiled) TILE_BYTE_COUNTS else STRIP_BYTE_COUNTS)
        val across = (width + chunkWidth - 1) / chunkWidth
        val down = (height + chunkHeight - 1) / chunkHeight
        val needed = across.toLong() * down * (if (planar) samples else 1)
        if (offsets.size < needed || (counts != null && counts.size < needed)) throw DecodeException("TIFF lists too few strips or tiles")
        if (counts == null && compression != 1) throw DecodeException("TIFF has no strip sizes")
        // Palette colors of 8 bits: each 16-bit value a multiple of 257.
        var colorMap8 = false
        val colorMap = if (photometric == PHOTO_PALETTE) {
            val map = ifd.longs(COLOR_MAP) ?: throw DecodeException("Palette TIFF has no palette")
            val n = 1 shl bits
            if (map.size < 3 * n) throw DecodeException("TIFF palette is too short")
            colorMap8 = (0 until 3 * n).all { map[it] % 257 == 0L }
            IntArray(n) { i -> argb(to8(map[i].toInt()), to8(map[n + i].toInt()), to8(map[2 * n + i].toInt())) }
        } else {
            null
        }
        return Page(
            width, height, compression, photometric, bits, samples, planar, predictor, alpha,
            chunkWidth, chunkHeight, offsets, counts, colorMap, ifd.bytes(JPEG_TABLES), ifd.bigEndian, colorMap8
        )
    }

    /**
     * Decodes [page] of [input], keeping every [sample]th pixel of every [sample]th row,
     * into [sink]: `ceil(width / sample)` x `ceil(height / sample)` pixels. A file that
     * ends early keeps the rows decoded before; one with none throws, as does any other
     * failure.
     */
    fun decode(input: Input, page: Page, sample: Int, jpeg: JpegDecoder, sink: Sink) {
        require(sample >= 1)
        val across = (page.width + page.chunkWidth - 1) / page.chunkWidth
        val down = (page.height + page.chunkHeight - 1) / page.chunkHeight
        val outWidth = (page.width + sample - 1) / sample
        val out = IntArray(minOf(outWidth, (page.chunkWidth + sample - 1) / sample + 1))
        var decoded = false
        for (cy in 0 until down) {
            for (cx in 0 until across) {
                try {
                    val index = cy * across + cx
                    if (page.compression == COMPRESSION_JPEG) {
                        jpegChunk(input, page, index, cx, cy, sample, jpeg, out, sink)
                    } else {
                        chunk(input, page, index, cx, cy, sample) { buffers, y, firstOut, count ->
                            val x0 = cx * page.chunkWidth
                            for (i in 0 until count) out[i] = pixel(page, buffers, (firstOut + i) * sample - x0)
                            sink.row(firstOut, y / sample, out, count)
                        }
                    }
                    decoded = true
                } catch (e: EOFException) {
                    if (!decoded) throw e
                    return
                }
            }
        }
    }

    // ------------------------------------------------------------------ chunks

    /** Columns of chunk column [cx] kept at [sample]: first output column and how many. */
    private fun columns(page: Page, cx: Int, sample: Int): Pair<Int, Int> {
        val x0 = cx * page.chunkWidth
        val x1 = minOf(x0 + page.chunkWidth, page.width)
        val first = (x0 + sample - 1) / sample
        val end = (x1 + sample - 1) / sample
        return first to (end - first)
    }

    /**
     * The stored samples of a [Page.sixteenBit] page, every pixel, into [sink]: gray
     * inverted where white is zero, nothing else changed. Throws on any failure, a file
     * that ends early too.
     */
    fun decodeSamples(input: Input, page: Page, sink: SampleSink) {
        require(page.sixteenBit)
        val across = (page.width + page.chunkWidth - 1) / page.chunkWidth
        val down = (page.height + page.chunkHeight - 1) / page.chunkHeight
        val n = page.channels
        val max = (1 shl page.bits) - 1
        val out = IntArray(minOf(page.width, page.chunkWidth) * n)
        for (cy in 0 until down) {
            for (cx in 0 until across) {
                chunk(input, page, cy * across + cx, cx, cy, 1) { buffers, y, firstOut, count ->
                    val x0 = cx * page.chunkWidth
                    var o = 0
                    for (i in 0 until count) {
                        for (s in 0 until n) {
                            val v = raw(page, buffers, firstOut + i - x0, s)
                            out[o++] = if (s == 0 && page.photometric == PHOTO_WHITE_IS_ZERO) max - v else v
                        }
                    }
                    sink.row(firstOut, y, out, count)
                }
            }
        }
    }

    /** Decodes chunk [index] (column [cx], row [cy]) row by row, giving [row] the rows kept at [sample]: buffers, y, first output column, columns. */
    private inline fun chunk(
        input: Input, page: Page, index: Int, cx: Int, cy: Int, sample: Int,
        row: (Array<ByteArray>, Int, Int, Int) -> Unit
    ) {
        val planes = if (page.planar) page.samples else 1
        val perPlane = if (page.planar) 1 else page.samples
        val rowBytes = (page.chunkWidth.toLong() * perPlane * page.bits + 7) / 8
        if (rowBytes > Int.MAX_VALUE / 2) throw DecodeException("TIFF rows are too long")
        val chunksPerPlane = (page.width + page.chunkWidth - 1) / page.chunkWidth *
            ((page.height + page.chunkHeight - 1) / page.chunkHeight)
        val y0 = cy * page.chunkHeight
        val rows = minOf(page.chunkHeight, page.height - y0)
        val streams = Array(planes) { p -> stream(input, page, p * chunksPerPlane + index, rowBytes * rows) }
        try {
            val buffers = Array(planes) { ByteArray(rowBytes.toInt()) }
            val (firstOut, count) = columns(page, cx, sample)
            for (r in 0 until rows) {
                for (p in 0 until planes) {
                    readFully(streams[p], buffers[p])
                    if (page.predictor == 2) undoDifferencing(buffers[p], page, perPlane)
                }
                val y = y0 + r
                if (y % sample != 0 || count <= 0) continue
                row(buffers, y, firstOut, count)
            }
        } finally {
            for (s in streams) runCatching { s.close() }
        }
    }

    private fun jpegChunk(
        input: Input, page: Page, index: Int, cx: Int, cy: Int, sample: Int,
        jpeg: JpegDecoder, out: IntArray, sink: Sink
    ) {
        val counts = page.counts ?: throw DecodeException("TIFF has no strip sizes")
        val size = counts[index]
        if (size <= 0 || size > MAX_JPEG_CHUNK) throw DecodeException("TIFF JPEG strip has a bad size")
        val data = ByteArray(size.toInt())
        input.read(page.offsets[index], data, 0, data.size)
        val raster = jpeg.decode(jpegStream(data, page.jpegTables, page.photometric == PHOTO_RGB), sample)
        val scale = raster.scale.coerceAtLeast(1)
        val y0 = cy * page.chunkHeight
        val rows = minOf(page.chunkHeight, page.height - y0)
        val x0 = cx * page.chunkWidth
        val (firstOut, count) = columns(page, cx, sample)
        for (r in 0 until rows) {
            val y = y0 + r
            if (y % sample != 0 || count <= 0) continue
            val ry = r / scale
            if (ry >= raster.height) break
            var n = 0
            while (n < count) {
                val rx = ((firstOut + n) * sample - x0) / scale
                if (rx >= raster.width) break
                out[n] = raster.pixels[ry * raster.width + rx]
                n++
            }
            if (n > 0) sink.row(firstOut, y / sample, out, n)
        }
    }

    /**
     * One complete JPEG from a chunk: the shared [tables] go in after its start marker.
     * With [rgb] an Adobe marker says the components are R, G, B, which decoders
     * otherwise read as YCbCr.
     */
    internal fun jpegStream(chunk: ByteArray, tables: ByteArray?, rgb: Boolean): ByteArray {
        if (chunk.size < 2 || chunk[0] != 0xFF.toByte() || chunk[1] != 0xD8.toByte()) throw DecodeException("TIFF JPEG strip is not a JPEG")
        val out = java.io.ByteArrayOutputStream(chunk.size + (tables?.size ?: 0) + ADOBE_RGB.size)
        out.write(chunk, 0, 2)
        if (rgb) out.write(ADOBE_RGB)
        if (tables != null && tables.size > 4) {
            // The tables stream is SOI, tables, EOI: only the tables are wanted.
            val end = if (tables[tables.size - 2] == 0xFF.toByte() && tables[tables.size - 1] == 0xD9.toByte()) tables.size - 2 else tables.size
            out.write(tables, 2, end - 2)
        }
        out.write(chunk, 2, chunk.size - 2)
        return out.toByteArray()
    }

    /** The decompressed bytes of chunk [index]: [expected] bytes at most are needed. */
    private fun stream(input: Input, page: Page, index: Int, expected: Long): InputStream {
        val offset = page.offsets[index]
        val count = page.counts?.get(index)?.takeIf { it > 0 } ?: expected
        val raw = RangeStream(input, offset, minOf(count, input.length - offset).coerceAtLeast(0))
        return when (page.compression) {
            1 -> raw
            COMPRESSION_PACKBITS -> PackBitsStream(raw)
            COMPRESSION_LZW -> LzwStream(raw)
            COMPRESSION_DEFLATE, COMPRESSION_DEFLATE_OLD -> InflaterInputStream(raw)
            else -> throw DecodeException("TIFF compression ${page.compression} is not supported")
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray) {
        var n = 0
        while (n < buf.size) {
            val r = input.read(buf, n, buf.size - n)
            if (r < 0) throw EOFException("TIFF strip ends early")
            n += r
        }
    }

    /** Undoes horizontal differencing over one row of [perPixel] samples per pixel. */
    private fun undoDifferencing(row: ByteArray, page: Page, perPixel: Int) {
        if (page.bits == 8) {
            for (i in perPixel until row.size) row[i] = (row[i] + row[i - perPixel]).toByte()
            return
        }
        // 16 bits: sums of whole samples in the file's byte order.
        val values = row.size / 2
        for (i in perPixel until values) {
            val sum = (u16(row, i, page.bigEndian) + u16(row, i - perPixel, page.bigEndian)) and 0xFFFF
            if (page.bigEndian) {
                row[2 * i] = (sum ushr 8).toByte()
                row[2 * i + 1] = sum.toByte()
            } else {
                row[2 * i] = sum.toByte()
                row[2 * i + 1] = (sum ushr 8).toByte()
            }
        }
    }

    private fun u16(row: ByteArray, i: Int, bigEndian: Boolean): Int {
        val a = row[2 * i].toInt() and 0xFF
        val b = row[2 * i + 1].toInt() and 0xFF
        return if (bigEndian) (a shl 8) or b else (b shl 8) or a
    }

    /** Sample [s] of pixel [x], raw: up to [Page.bits] wide. */
    private fun raw(page: Page, buffers: Array<ByteArray>, x: Int, s: Int): Int {
        val row: ByteArray
        val i: Int
        if (page.planar) {
            row = buffers[s]
            i = x
        } else {
            row = buffers[0]
            i = x * page.samples + s
        }
        return when (page.bits) {
            8 -> row[i].toInt() and 0xFF
            16 -> u16(row, i, page.bigEndian)
            in 1..7 -> {
                val bit = i * page.bits
                val byte = row[bit ushr 3].toInt() and 0xFF
                (byte ushr (8 - page.bits - (bit and 7))) and ((1 shl page.bits) - 1)
            }
            else -> {
                // 9-15 bits, most significant first, across two or three bytes.
                val bit = i * page.bits
                val end = bit + page.bits
                var acc = 0
                for (b in (bit ushr 3) until ((end + 7) ushr 3)) acc = (acc shl 8) or (row[b].toInt() and 0xFF)
                (acc ushr ((8 - (end and 7)) and 7)) and ((1 shl page.bits) - 1)
            }
        }
    }

    /** Sample [s] of pixel [x] as 0..255. */
    private fun sample8(page: Page, buffers: Array<ByteArray>, x: Int, s: Int): Int {
        val v = raw(page, buffers, x, s)
        return when (page.bits) {
            8 -> v
            16 -> to8(v)
            else -> v * 255 / ((1 shl page.bits) - 1)
        }
    }

    private fun pixel(page: Page, buffers: Array<ByteArray>, x: Int): Int {
        val color = when (page.photometric) {
            PHOTO_PALETTE -> page.colorMap!![raw(page, buffers, x, 0)]
            PHOTO_WHITE_IS_ZERO -> gray(255 - sample8(page, buffers, x, 0))
            PHOTO_BLACK_IS_ZERO -> gray(sample8(page, buffers, x, 0))
            PHOTO_CMYK -> {
                val k = 255 - sample8(page, buffers, x, 3)
                argb(
                    (255 - sample8(page, buffers, x, 0)) * k / 255,
                    (255 - sample8(page, buffers, x, 1)) * k / 255,
                    (255 - sample8(page, buffers, x, 2)) * k / 255
                )
            }
            else -> argb(sample8(page, buffers, x, 0), sample8(page, buffers, x, 1), sample8(page, buffers, x, 2))
        }
        if (page.alpha == 0) return color
        val base = when (page.photometric) {
            PHOTO_RGB -> 3
            PHOTO_CMYK -> 4
            else -> 1
        }
        val a = sample8(page, buffers, x, base)
        if (page.alpha == ALPHA_UNASSOCIATED || a == 0 || a == 255) return (a shl 24) or (color and 0xFFFFFF)
        // Associated alpha: the colors are premultiplied.
        fun un(c: Int) = minOf(255, (c * 255 + a / 2) / a)
        return (a shl 24) or (un((color shr 16) and 0xFF) shl 16) or (un((color shr 8) and 0xFF) shl 8) or un(color and 0xFF)
    }

    private fun gray(v: Int) = argb(v, v, v)

    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    /** A 16-bit value rounded to 8 bits. */
    private fun to8(v: Int) = (v * 255 + 32767) / 65535

    // ------------------------------------------------------------------ IFD

    /** The tags of one image file directory. */
    private class Ifd(val bigEndian: Boolean, val entries: Map<Int, Entry>, private val input: Input) {
        class Entry(val type: Int, val count: Long, val valueAt: Long, val inline: ByteArray)

        fun has(tag: Int) = tag in entries

        fun long(tag: Int, default: Long): Long = longs(tag)?.firstOrNull() ?: default

        fun int(tag: Int, default: Int = 0): Int = long(tag, default.toLong()).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()

        fun longs(tag: Int): LongArray? {
            val e = entries[tag] ?: return null
            val size = TYPE_SIZES[e.type] ?: return null
            if (e.count <= 0 || e.count > MAX_VALUES) return null
            val n = e.count.toInt()
            val data = data(e, n * size) ?: return null
            return LongArray(n) { i ->
                when (e.type) {
                    TYPE_BYTE, TYPE_UNDEFINED -> data[i].toLong() and 0xFF
                    TYPE_SHORT -> read(data, i * 2, 2)
                    TYPE_LONG, TYPE_IFD -> read(data, i * 4, 4)
                    TYPE_LONG8, TYPE_IFD8 -> read(data, i * 8, 8)
                    else -> 0
                }
            }
        }

        fun bytes(tag: Int): ByteArray? {
            val e = entries[tag] ?: return null
            if ((e.type != TYPE_UNDEFINED && e.type != TYPE_BYTE) || e.count <= 0 || e.count > MAX_VALUES) return null
            return data(e, e.count.toInt())
        }

        private fun data(e: Entry, bytes: Int): ByteArray? {
            if (bytes <= e.inline.size) return e.inline
            val buf = ByteArray(bytes)
            return try {
                input.read(e.valueAt, buf, 0, bytes)
                buf
            } catch (x: IOException) {
                null
            }
        }

        fun read(b: ByteArray, at: Int, n: Int): Long = Tiff.read(b, at, n, bigEndian)
    }

    /**
     * True when [input] decodes to exactly the pixels it stores, none reduced or converted,
     * so they can be stored anew losslessly: one directory alone, top-left orientation, no
     * color profile; gray or RGB of at most 8 bits, or a palette of 8-bit colors; alpha
     * unassociated; not JPEG-compressed.
     */
    fun exact(input: Input): Boolean = try {
        val (ifd, next) = firstIfd(input)
        next == 0L && ifd.int(ORIENTATION, 1) == 1 && !ifd.has(ICC_PROFILE) && samplesExact(input)
    } catch (e: IOException) {
        false
    } catch (e: RuntimeException) {
        false
    }

    /**
     * True when [decode] gives [input]'s page exactly as its samples are stored, none
     * reduced or converted: gray or RGB of at most 8 bits, or a palette of 8-bit colors;
     * alpha unassociated; not JPEG-compressed. Its profile, orientation and other pages
     * are not looked at.
     */
    fun samplesExact(input: Input): Boolean = try {
        val page = page(input)
        page.compression != COMPRESSION_JPEG && page.photometric != PHOTO_CMYK && page.photometric != PHOTO_YCBCR &&
            page.bits <= 8 && page.alpha != ALPHA_ASSOCIATED &&
            (page.photometric != PHOTO_PALETTE || page.colorMap8)
    } catch (e: IOException) {
        false
    } catch (e: RuntimeException) {
        false
    }

    /** The ICC color profile stored with [input]'s page; null when none or unreadable. */
    fun icc(input: Input): ByteArray? = try {
        pageIfd(input).bytes(ICC_PROFILE)?.takeIf { it.size >= ICC_HEADER }
    } catch (e: IOException) {
        null
    } catch (e: RuntimeException) {
        null
    }

    /** The first directory of [input] and where the next one is (0: none). */
    private fun firstIfd(input: Input): Pair<Ifd, Long> {
        val (offset, big, bigEndian) = header(input)
        return readIfd(input, offset, big, bigEndian)
    }

    /** Where [input]'s first directory is, whether it is a BigTIFF, and whether big-endian. */
    private fun header(input: Input): Triple<Long, Boolean, Boolean> {
        val head = ByteArray(16)
        input.read(0, head, 0, minOf(16L, input.length).toInt())
        val bigEndian = when {
            head[0] == 'I'.code.toByte() && head[1] == 'I'.code.toByte() -> false
            head[0] == 'M'.code.toByte() && head[1] == 'M'.code.toByte() -> true
            else -> throw DecodeException("Not a TIFF image")
        }
        val big = when (read(head, 2, 2, bigEndian).toInt()) {
            42 -> false
            43 -> true
            else -> throw DecodeException("Not a TIFF image")
        }
        return Triple(if (big) read(head, 8, 8, bigEndian) else read(head, 4, 4, bigEndian), big, bigEndian)
    }

    /** The first page's directory, skipping reduced-resolution images (thumbnails) when a full one follows. */
    private fun pageIfd(input: Input): Ifd {
        val (start, big, bigEndian) = header(input)
        var offset = start
        var first: Ifd? = null
        val seen = HashSet<Long>()
        while (offset > 0 && seen.size < MAX_PAGES && seen.add(offset)) {
            val (ifd, next) = readIfd(input, offset, big, bigEndian)
            if (first == null) first = ifd
            if (ifd.long(NEW_SUBFILE_TYPE, 0) and 1L == 0L) return ifd
            offset = next
        }
        return first ?: throw DecodeException("TIFF has no pages")
    }

    private fun readIfd(input: Input, offset: Long, big: Boolean, bigEndian: Boolean): Pair<Ifd, Long> {
        val countSize = if (big) 8 else 2
        val entrySize = if (big) 20 else 12
        val valueSize = if (big) 8 else 4
        val head = ByteArray(countSize)
        input.read(offset, head, 0, countSize)
        val n = read(head, 0, countSize, bigEndian)
        if (n <= 0 || n > MAX_ENTRIES) throw DecodeException("TIFF directory is damaged")
        val table = ByteArray(n.toInt() * entrySize + valueSize)
        input.read(offset + countSize, table, 0, table.size)
        val entries = HashMap<Int, Ifd.Entry>()
        for (i in 0 until n.toInt()) {
            val at = i * entrySize
            val tag = read(table, at, 2, bigEndian).toInt()
            val type = read(table, at + 2, 2, bigEndian).toInt()
            val count = read(table, at + 4, if (big) 8 else 4, bigEndian)
            val valueAt = at + if (big) 12 else 8
            val inline = table.copyOfRange(valueAt, valueAt + valueSize)
            val pointer = read(table, valueAt, valueSize, bigEndian)
            entries.putIfAbsent(tag, Ifd.Entry(type, count, pointer, inline))
        }
        val next = read(table, n.toInt() * entrySize, valueSize, bigEndian)
        return Ifd(bigEndian, entries, input) to next
    }

    private fun read(b: ByteArray, at: Int, n: Int, bigEndian: Boolean): Long {
        var v = 0L
        for (k in 0 until n) {
            val byte = b[if (bigEndian) at + k else at + n - 1 - k].toLong() and 0xFF
            v = (v shl 8) or byte
        }
        return v
    }

    // ------------------------------------------------------------------ codecs

    /** [length] bytes of [input] from [offset], read in blocks. */
    private class RangeStream(private val input: Input, private var pos: Long, length: Long) : InputStream() {
        private val end = pos + length
        private val buf = ByteArray(BLOCK)
        private var bufStart = 0L
        private var bufLen = 0

        override fun read(): Int {
            if (pos >= end) return -1
            fill()
            return buf[(pos++ - bufStart).toInt()].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (pos >= end) return -1
            fill()
            val n = minOf(len.toLong(), bufStart + bufLen - pos).toInt()
            System.arraycopy(buf, (pos - bufStart).toInt(), b, off, n)
            pos += n
            return n
        }

        /** Loads the block holding [pos] unless it already is in [buf]. */
        private fun fill() {
            if (pos >= bufStart && pos < bufStart + bufLen) return
            bufStart = pos
            bufLen = minOf(BLOCK.toLong(), end - pos).toInt()
            input.read(pos, buf, 0, bufLen)
        }

        private companion object {
            const val BLOCK = 64 * 1024
        }
    }

    /** PackBits run-length decoding: a count byte, then literal bytes or one repeated byte. */
    internal class PackBitsStream(private val input: InputStream) : InputStream() {
        private var literal = 0
        private var repeat = 0
        private var value = 0

        override fun read(): Int {
            while (literal == 0 && repeat == 0) {
                val n = input.read()
                if (n < 0) return -1
                val count = n.toByte().toInt()
                when {
                    count >= 0 -> literal = count + 1
                    count != -128 -> {
                        value = input.read()
                        if (value < 0) return -1
                        repeat = 1 - count
                    }
                }
            }
            if (repeat > 0) {
                repeat--
                return value
            }
            literal--
            return input.read()
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            var n = 0
            while (n < len) {
                val v = read()
                if (v < 0) return if (n == 0) -1 else n
                b[off + n++] = v.toByte()
            }
            return n
        }

        override fun close() = input.close()
    }

    /**
     * TIFF's LZW: codes of 9-12 bits, most significant bit first, 256 clears the
     * table and 257 ends the data; codes widen one entry early, as libtiff writes them.
     */
    internal class LzwStream(private val input: InputStream) : InputStream() {
        private val prefix = IntArray(TABLE)
        private val suffix = ByteArray(TABLE)
        private val lengths = IntArray(TABLE)
        private val firsts = ByteArray(TABLE)
        private var next = FIRST
        private var width = 9
        private var old = -1
        private var done = false

        /** Bits read but not used yet. */
        private var acc = 0L
        private var accBits = 0

        /** Bytes decoded but not returned yet: [pending] from [pendingAt] to [pendingEnd]. */
        private val pending = ByteArray(TABLE)
        private var pendingAt = 0
        private var pendingEnd = 0

        init {
            for (i in 0 until 256) {
                suffix[i] = i.toByte()
                firsts[i] = i.toByte()
                lengths[i] = 1
                prefix[i] = -1
            }
        }

        private fun code(): Int {
            while (accBits < width) {
                val b = input.read()
                if (b < 0) return EOI
                acc = (acc shl 8) or b.toLong()
                accBits += 8
            }
            accBits -= width
            return ((acc ushr accBits) and ((1L shl width) - 1)).toInt()
        }

        /** Writes the string of [code] into [pending]. */
        private fun emit(code: Int) {
            val n = lengths[code]
            var c = code
            for (i in n - 1 downTo 0) {
                pending[i] = suffix[c]
                c = prefix[c]
            }
            pendingAt = 0
            pendingEnd = n
        }

        private fun add(base: Int, last: Byte) {
            if (next >= TABLE) return
            prefix[next] = base
            suffix[next] = last
            lengths[next] = lengths[base] + 1
            firsts[next] = firsts[base]
            next++
            if (next >= (1 shl width) - 1 && width < 12) width++
        }

        /** Decodes the next string; false at the end of the data. */
        private fun fill(): Boolean {
            while (!done) {
                val code = code()
                when {
                    code == EOI -> done = true
                    code == CLEAR -> {
                        next = FIRST
                        width = 9
                        old = -1
                    }
                    old < 0 -> {
                        if (code >= 256) throw IOException("Bad LZW code")
                        emit(code)
                        old = code
                        return true
                    }
                    code < next -> {
                        emit(code)
                        add(old, firsts[code])
                        old = code
                        return true
                    }
                    code == next -> {
                        add(old, firsts[old])
                        emit(code)
                        old = code
                        return true
                    }
                    else -> throw IOException("Bad LZW code")
                }
            }
            return false
        }

        override fun read(): Int {
            if (pendingAt >= pendingEnd && !fill()) return -1
            return pending[pendingAt++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            var n = 0
            while (n < len) {
                if (pendingAt >= pendingEnd && !fill()) return if (n == 0) -1 else n
                val k = minOf(len - n, pendingEnd - pendingAt)
                System.arraycopy(pending, pendingAt, b, off + n, k)
                pendingAt += k
                n += k
            }
            return n
        }

        override fun close() = input.close()

        private companion object {
            const val TABLE = 4096
            const val CLEAR = 256
            const val EOI = 257
            const val FIRST = 258
        }
    }

    // ------------------------------------------------------------------ constants

    private const val NEW_SUBFILE_TYPE = 254
    private const val WIDTH = 256
    private const val HEIGHT = 257
    private const val BITS_PER_SAMPLE = 258
    private const val COMPRESSION = 259
    private const val PHOTOMETRIC = 262
    private const val STRIP_OFFSETS = 273
    private const val SAMPLES_PER_PIXEL = 277
    private const val ROWS_PER_STRIP = 278
    private const val STRIP_BYTE_COUNTS = 279
    private const val PLANAR_CONFIG = 284
    private const val PREDICTOR = 317
    private const val COLOR_MAP = 320
    private const val TILE_WIDTH = 322
    private const val TILE_LENGTH = 323
    private const val TILE_OFFSETS = 324
    private const val TILE_BYTE_COUNTS = 325
    private const val EXTRA_SAMPLES = 338
    private const val SAMPLE_FORMAT = 339
    private const val JPEG_TABLES = 347
    private const val ORIENTATION = 274
    private const val ICC_PROFILE = 34675

    private const val COMPRESSION_LZW = 5
    private const val COMPRESSION_JPEG = 7
    private const val COMPRESSION_DEFLATE = 8
    private const val COMPRESSION_PACKBITS = 32773
    private const val COMPRESSION_DEFLATE_OLD = 32946
    private val COMPRESSIONS = setOf(1, COMPRESSION_LZW, COMPRESSION_JPEG, COMPRESSION_DEFLATE, COMPRESSION_PACKBITS, COMPRESSION_DEFLATE_OLD)
    private val COMPRESSION_NAMES = mapOf(2 to "CCITT RLE", 3 to "CCITT G3", 4 to "CCITT G4", 6 to "old-style JPEG")

    private const val PHOTO_WHITE_IS_ZERO = 0
    private const val PHOTO_BLACK_IS_ZERO = 1
    private const val PHOTO_RGB = 2
    private const val PHOTO_PALETTE = 3
    private const val PHOTO_CMYK = 5
    private const val PHOTO_YCBCR = 6

    private const val ALPHA_ASSOCIATED = 1
    private const val ALPHA_UNASSOCIATED = 2

    private const val TYPE_BYTE = 1
    private const val TYPE_SHORT = 3
    private const val TYPE_LONG = 4
    private const val TYPE_UNDEFINED = 7
    private const val TYPE_IFD = 13
    private const val TYPE_LONG8 = 16
    private const val TYPE_IFD8 = 18
    private val TYPE_SIZES = mapOf(
        TYPE_BYTE to 1, 2 to 1, TYPE_SHORT to 2, TYPE_LONG to 4, 5 to 8, 6 to 1, TYPE_UNDEFINED to 1,
        8 to 2, 9 to 4, 10 to 8, 11 to 4, 12 to 8, TYPE_IFD to 4, TYPE_LONG8 to 8, 17 to 8, TYPE_IFD8 to 8
    )

    /** Bytes of an ICC profile's header. */
    private const val ICC_HEADER = 128

    private const val MAX_ENTRIES = 4096L
    private const val MAX_VALUES = 16L * 1024 * 1024
    private const val MAX_PAGES = 64
    private const val MAX_JPEG_CHUNK = 256L * 1024 * 1024

    /** APP14 "Adobe", version 100, transform 0: the three components are R, G, B. */
    private val ADOBE_RGB = byteArrayOf(
        0xFF.toByte(), 0xEE.toByte(), 0, 14, 'A'.code.toByte(), 'd'.code.toByte(), 'o'.code.toByte(), 'b'.code.toByte(),
        'e'.code.toByte(), 0, 100, 0, 0, 0, 0, 0
    )
}
