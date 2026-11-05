package com.infinity.comicreader.reader

/**
 * Whether AV1 data codes its frame losslessly, as the AV1 specification defines it
 * (`CodedLossless`): every segment's quantizer index 0 and no DC or AC delta. Reads the
 * sequence header and the last frame header of an item's OBUs up to its segmentation;
 * nothing is decoded.
 */
object Av1Lossless {
    private const val OBU_SEQUENCE_HEADER = 1
    private const val OBU_FRAME_HEADER = 3
    private const val OBU_FRAME = 6
    private const val KEY_FRAME = 0
    private const val INTRA_ONLY_FRAME = 2
    private const val SWITCH_FRAME = 3
    private const val SELECT = 2
    private val SEGMENT_BITS = intArrayOf(8, 6, 6, 6, 6, 3, 0, 0)
    private val SEGMENT_SIGNED = booleanArrayOf(true, true, true, true, true, false, false, false)

    /**
     * True when the frame shown last in the OBUs in `[from, to)` of [b] is coded lossless,
     * false when it is not; null when that cannot be told: no sequence header, a frame
     * predicted from others (a later layer), or bytes that do not parse.
     */
    fun of(b: ByteArray, from: Int, to: Int): Boolean? = try {
        var sequence: Sequence? = null
        var last: Boolean? = null
        var seen = false
        var at = from.toLong()
        val end = minOf(to, b.size).toLong()
        while (at < end) {
            val header = b[at.toInt()].toInt() and 0xFF
            val type = (header shr 3) and 15
            val extension = header and 4 != 0
            val sized = header and 2 != 0
            var p = at + 1
            var temporal = 0
            var spatial = 0
            if (extension) {
                val e = b[p.toInt()].toInt() and 0xFF
                temporal = e shr 5
                spatial = (e shr 3) and 3
                p++
            }
            var size = end - p
            if (sized) {
                size = 0
                var shift = 0
                while (true) {
                    val v = b[p.toInt()].toInt() and 0xFF
                    p++
                    size = size or ((v and 0x7F).toLong() shl shift)
                    if (v and 0x80 == 0) break
                    shift += 7
                    if (shift > 56) return null
                }
            }
            if (size < 0 || size > end - p) return null
            val body = Bits(b, p.toInt(), (p + size).toInt())
            when (type) {
                OBU_SEQUENCE_HEADER -> sequence = Sequence.read(body)
                OBU_FRAME_HEADER, OBU_FRAME -> {
                    val s = sequence ?: return null
                    seen = true
                    last = frame(body, s, temporal, spatial)
                }
            }
            at = p + size
        }
        if (seen) last else null
    } catch (e: IndexOutOfBoundsException) {
        null
    }

    /** The sequence header fields a frame header's parse needs. */
    private class Sequence(
        val reduced: Boolean,
        val decoderModel: Boolean,
        val equalInterval: Boolean,
        val presentationBits: Int,
        val removalBits: Int,
        val operatingPoints: IntArray,
        val decoderModelFor: BooleanArray,
        val widthBits: Int,
        val heightBits: Int,
        val maxWidth: Int,
        val maxHeight: Int,
        val frameIdBits: Int,
        val superblock128: Boolean,
        val screenContent: Int,
        val integerMv: Int,
        val orderHintBits: Int,
        val superres: Boolean,
        val planes: Int,
        val separateUvDelta: Boolean
    ) {
        companion object {
            fun read(r: Bits): Sequence {
                val profile = r.f(3)
                r.f(1) // still_picture
                val reduced = r.f(1) == 1
                var decoderModel = false
                var equalInterval = false
                var presentationBits = 0
                var removalBits = 0
                var delayBits = 0
                val points: IntArray
                val modelFor: BooleanArray
                if (reduced) {
                    r.f(5) // seq_level_idx
                    points = intArrayOf(0)
                    modelFor = booleanArrayOf(false)
                } else {
                    if (r.f(1) == 1) {
                        r.f(32)
                        r.f(32)
                        equalInterval = r.f(1) == 1
                        if (equalInterval) r.uvlc()
                        decoderModel = r.f(1) == 1
                        if (decoderModel) {
                            delayBits = r.f(5) + 1
                            r.f(32)
                            removalBits = r.f(5) + 1
                            presentationBits = r.f(5) + 1
                        }
                    }
                    val displayDelay = r.f(1) == 1
                    val count = r.f(5) + 1
                    points = IntArray(count)
                    modelFor = BooleanArray(count)
                    for (i in 0 until count) {
                        points[i] = r.f(12)
                        if (r.f(5) > 7) r.f(1)
                        if (decoderModel) {
                            modelFor[i] = r.f(1) == 1
                            if (modelFor[i]) {
                                r.f(delayBits)
                                r.f(delayBits)
                                r.f(1)
                            }
                        }
                        if (displayDelay && r.f(1) == 1) r.f(4)
                    }
                }
                val widthBits = r.f(4) + 1
                val heightBits = r.f(4) + 1
                val maxWidth = r.f(widthBits) + 1
                val maxHeight = r.f(heightBits) + 1
                var frameIdBits = 0
                if (!reduced && r.f(1) == 1) {
                    val delta = r.f(4) + 2
                    frameIdBits = r.f(3) + 1 + delta
                }
                val superblock128 = r.f(1) == 1
                r.f(1) // enable_filter_intra
                r.f(1) // enable_intra_edge_filter
                var screenContent = SELECT
                var integerMv = SELECT
                var orderHintBits = 0
                if (!reduced) {
                    r.f(4) // interintra, masked compound, warped motion, dual filter
                    val orderHint = r.f(1) == 1
                    if (orderHint) r.f(2) // jnt_comp, ref_frame_mvs
                    screenContent = if (r.f(1) == 1) SELECT else r.f(1)
                    integerMv = if (screenContent > 0) (if (r.f(1) == 1) SELECT else r.f(1)) else SELECT
                    if (orderHint) orderHintBits = r.f(3) + 1
                }
                val superres = r.f(1) == 1
                r.f(1) // enable_cdef
                r.f(1) // enable_restoration
                // color_config
                val high = r.f(1) == 1
                val depth = if (profile == 2 && high) (if (r.f(1) == 1) 12 else 10) else if (high) 10 else 8
                val mono = profile != 1 && r.f(1) == 1
                var primaries = 2
                var transfer = 2
                var matrix = 2
                if (r.f(1) == 1) {
                    primaries = r.f(8)
                    transfer = r.f(8)
                    matrix = r.f(8)
                }
                var separateUvDelta = false
                if (mono) {
                    r.f(1) // color_range
                } else {
                    if (!(primaries == 1 && transfer == 13 && matrix == 0)) {
                        r.f(1) // color_range
                        var x = 1
                        var y = 1
                        when (profile) {
                            0 -> Unit
                            1 -> { x = 0; y = 0 }
                            else -> if (depth == 12) {
                                x = r.f(1)
                                y = if (x == 1) r.f(1) else 0
                            } else {
                                y = 0
                            }
                        }
                        if (x == 1 && y == 1) r.f(2) // chroma_sample_position
                    }
                    separateUvDelta = r.f(1) == 1
                }
                return Sequence(
                    reduced, decoderModel, equalInterval, presentationBits, removalBits, points, modelFor,
                    widthBits, heightBits, maxWidth, maxHeight, frameIdBits, superblock128, screenContent, integerMv,
                    orderHintBits, superres, if (mono) 1 else 3, separateUvDelta
                )
            }
        }
    }

    /** The frame header in [r] coded lossless; null for a frame that is not intra or shows an earlier one. */
    private fun frame(r: Bits, s: Sequence, temporal: Int, spatial: Int): Boolean? {
        var frameType = KEY_FRAME
        var showFrame = true
        var errorResilient = true
        if (!s.reduced) {
            if (r.f(1) == 1) return null // show_existing_frame
            frameType = r.f(2)
            showFrame = r.f(1) == 1
            if (showFrame && s.decoderModel && !s.equalInterval) r.f(s.presentationBits)
            if (!showFrame) r.f(1) // showable_frame
            errorResilient = if (frameType == SWITCH_FRAME || frameType == KEY_FRAME && showFrame) true else r.f(1) == 1
        }
        if (frameType != KEY_FRAME && frameType != INTRA_ONLY_FRAME) return null
        val disableCdfUpdate = r.f(1) == 1
        val screenContent = if (s.screenContent == SELECT) r.f(1) else s.screenContent
        if (screenContent == 1 && s.integerMv == SELECT) r.f(1)
        if (s.frameIdBits > 0) r.f(s.frameIdBits)
        val sizeOverride = if (s.reduced) false else r.f(1) == 1
        r.f(s.orderHintBits)
        if (s.decoderModel && r.f(1) == 1) {
            for (i in s.operatingPoints.indices) {
                if (!s.decoderModelFor[i]) continue
                val idc = s.operatingPoints[i]
                if (idc == 0 || (idc shr temporal) and 1 == 1 && (idc shr (spatial + 8)) and 1 == 1) r.f(s.removalBits)
            }
        }
        val refreshAll = frameType == KEY_FRAME && showFrame
        val refresh = if (refreshAll) 0xFF else r.f(8)
        if (refresh != 0xFF && errorResilient && s.orderHintBits > 0) repeat(8) { r.f(s.orderHintBits) }
        // frame_size, superres_params
        var width = if (sizeOverride) r.f(s.widthBits) + 1 else s.maxWidth
        val height = if (sizeOverride) r.f(s.heightBits) + 1 else s.maxHeight
        val upscaled = width
        if (s.superres && r.f(1) == 1) {
            val denom = r.f(3) + 9
            width = (upscaled * 8 + denom / 2) / denom
        }
        // render_size
        if (r.f(1) == 1) {
            r.f(16)
            r.f(16)
        }
        if (screenContent == 1 && upscaled == width) r.f(1) // allow_intrabc
        if (!(s.reduced || disableCdfUpdate)) r.f(1) // disable_frame_end_update_cdf
        tileInfo(r, s, 2 * ((width + 7) shr 3), 2 * ((height + 7) shr 3))
        // quantization_params
        val base = r.f(8)
        var deltas = if (deltaQ(r) != 0) 1 else 0
        if (s.planes > 1) {
            val diffUv = s.separateUvDelta && r.f(1) == 1
            if (deltaQ(r) != 0) deltas++
            if (deltaQ(r) != 0) deltas++
            if (diffUv) {
                if (deltaQ(r) != 0) deltas++
                if (deltaQ(r) != 0) deltas++
            }
        }
        if (r.f(1) == 1) {
            r.f(4)
            r.f(4)
            if (s.separateUvDelta) r.f(4)
        }
        if (deltas > 0) return false
        // segmentation_params: an intra frame's segments all come with their data.
        if (r.f(1) == 0) return base == 0
        var lossless = true
        for (segment in 0 until 8) {
            var q = base
            for (feature in 0 until 8) {
                if (r.f(1) == 0) continue
                val bits = SEGMENT_BITS[feature]
                val value = if (SEGMENT_SIGNED[feature]) r.su(1 + bits) else r.f(bits)
                if (feature == 0) q = (base + value.coerceIn(-255, 255)).coerceIn(0, 255)
            }
            if (q != 0) lossless = false
        }
        return lossless
    }

    private fun deltaQ(r: Bits): Int = if (r.f(1) == 1) r.su(7) else 0

    /** Reads past tile_info for a frame of [miCols] x [miRows] mode-info units. */
    private fun tileInfo(r: Bits, s: Sequence, miCols: Int, miRows: Int) {
        val sbCols = if (s.superblock128) (miCols + 31) shr 5 else (miCols + 15) shr 4
        val sbRows = if (s.superblock128) (miRows + 31) shr 5 else (miRows + 15) shr 4
        val sbSize = (if (s.superblock128) 5 else 4) + 2
        val maxTileWidthSb = 4096 shr sbSize
        var maxTileAreaSb = (4096 * 2304) shr (2 * sbSize)
        val minLog2TileCols = tileLog2(maxTileWidthSb, sbCols)
        val maxLog2TileCols = tileLog2(1, minOf(sbCols, 64))
        val maxLog2TileRows = tileLog2(1, minOf(sbRows, 64))
        val minLog2Tiles = maxOf(minLog2TileCols, tileLog2(maxTileAreaSb, sbRows * sbCols))
        val colsLog2: Int
        val rowsLog2: Int
        if (r.f(1) == 1) {
            var cols = minLog2TileCols
            while (cols < maxLog2TileCols && r.f(1) == 1) cols++
            colsLog2 = cols
            var rows = maxOf(minLog2Tiles - cols, 0)
            while (rows < maxLog2TileRows && r.f(1) == 1) rows++
            rowsLog2 = rows
        } else {
            var widest = 0
            var start = 0
            var cols = 0
            while (start < sbCols) {
                val size = r.ns(minOf(sbCols - start, maxTileWidthSb)) + 1
                widest = maxOf(widest, size)
                start += size
                cols++
            }
            colsLog2 = tileLog2(1, cols)
            maxTileAreaSb = if (minLog2Tiles > 0) (sbRows * sbCols) shr (minLog2Tiles + 1) else sbRows * sbCols
            val maxTileHeightSb = maxOf(maxTileAreaSb / widest, 1)
            start = 0
            var rows = 0
            while (start < sbRows) {
                start += r.ns(minOf(sbRows - start, maxTileHeightSb)) + 1
                rows++
            }
            rowsLog2 = tileLog2(1, rows)
        }
        if (colsLog2 > 0 || rowsLog2 > 0) {
            r.f(rowsLog2 + colsLog2) // context_update_tile_id
            r.f(2) // tile_size_bytes_minus_1
        }
    }

    private fun tileLog2(block: Int, target: Int): Int {
        var k = 0
        while ((block shl k) < target) k++
        return k
    }

    /** Bits of `[from, to)` of [b], most significant first. */
    private class Bits(private val b: ByteArray, from: Int, private val to: Int) {
        private var bit = from.toLong() * 8

        fun f(n: Int): Int {
            var v = 0L
            repeat(n) {
                val byte = (bit shr 3).toInt()
                if (byte >= to) throw IndexOutOfBoundsException()
                v = (v shl 1) or ((b[byte].toLong() shr (7 - (bit and 7).toInt())) and 1)
                bit++
            }
            return v.toInt()
        }

        fun su(n: Int): Int {
            val v = f(n)
            val sign = 1 shl (n - 1)
            return if (v and sign != 0) v - 2 * sign else v
        }

        fun uvlc(): Long {
            var zeros = 0
            while (f(1) == 0) {
                zeros++
                if (zeros >= 32) return (1L shl 32) - 1
            }
            return (1L shl zeros) - 1 + f(zeros).toLong()
        }

        fun ns(n: Int): Int {
            var w = 0
            var x = n
            while (x != 0) {
                x = x shr 1
                w++
            }
            val m = (1 shl w) - n
            val v = f(w - 1)
            return if (v < m) v else (v shl 1) - m + f(1)
        }
    }
}
