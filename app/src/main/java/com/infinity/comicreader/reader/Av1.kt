package com.infinity.comicreader.reader

/**
 * Just enough of the AV1 bitstream (sequence header and the start of an intra frame's
 * header) to cut the first layer out of layered still-image data ([firstLayer]).
 */
object Av1 {
    private const val OBU_SEQUENCE_HEADER = 1
    private const val OBU_FRAME_HEADER = 3
    private const val OBU_FRAME = 6
    private const val KEY_FRAME = 0
    private const val INTRA_ONLY_FRAME = 2
    private const val SWITCH_FRAME = 3
    private const val SELECT = 2

    /** The first layer of a layered image: its AV1 [data], decoding to [width] x [height]. */
    class Layer(val data: ByteArray, val width: Int, val height: Int)

    /**
     * The first layer of [layer], the first layer's share of an item's AV1 data: its
     * size read from its first frame header, and a copy of it whose sequence header
     * names that size as the largest, so decoders output it as it is. Null when the
     * data is not a shown intra frame after a sequence header.
     */
    fun firstLayer(layer: ByteArray): Layer? = try {
        var seq: Sequence? = null
        var seqPayload = -1
        var size: IntArray? = null
        var at = 0
        while (at < layer.size && size == null) {
            val header = layer[at].toInt() and 0xFF
            val type = (header shr 3) and 15
            val hasExtension = (header shr 2) and 1 == 1
            val hasSize = (header shr 1) and 1 == 1
            var p = at + 1
            var temporalId = 0
            var spatialId = 0
            if (hasExtension) {
                temporalId = (layer[p].toInt() shr 5) and 7
                spatialId = (layer[p].toInt() shr 3) and 3
                p++
            }
            val length: Long
            if (hasSize) {
                val (value, read) = leb128(layer, p)
                length = value
                p += read
            } else {
                length = (layer.size - p).toLong()
            }
            if (p + length > layer.size) return null
            val end = p + length.toInt()
            when (type) {
                OBU_SEQUENCE_HEADER -> {
                    seq = Sequence.parse(Bits(layer, p, end))
                    seqPayload = p
                }
                OBU_FRAME, OBU_FRAME_HEADER -> {
                    val s = seq ?: return null
                    size = frameSize(Bits(layer, p, end), s, temporalId, spatialId) ?: return null
                }
            }
            at = end
        }
        val s = seq
        val wh = size
        if (s == null || wh == null || wh[0] > s.maxWidth || wh[1] > s.maxHeight) null
        else {
            val out = layer.copyOf()
            val bit = seqPayload.toLong() * 8 + s.maxSizeBit
            writeBits(out, bit, s.widthBits, (wh[0] - 1).toLong())
            writeBits(out, bit + s.widthBits, s.heightBits, (wh[1] - 1).toLong())
            Layer(out, wh[0], wh[1])
        }
    } catch (e: IndexOutOfBoundsException) {
        null
    }

    /** The largest frame size `[width, height]` named by the first sequence header in [data]; null without one. */
    internal fun maxFrameSize(data: ByteArray): IntArray? = try {
        var at = 0
        var found: IntArray? = null
        while (at < data.size && found == null) {
            val header = data[at].toInt() and 0xFF
            var p = at + 1 + ((header shr 2) and 1)
            val (length, read) = leb128(data, p)
            p += read
            if ((header shr 3) and 15 == OBU_SEQUENCE_HEADER) {
                found = Sequence.parse(Bits(data, p, p + length.toInt())).let { intArrayOf(it.maxWidth, it.maxHeight) }
            }
            at = p + length.toInt()
        }
        found
    } catch (e: IndexOutOfBoundsException) {
        null
    }

    /** What frame headers need from a sequence header, and where its largest frame size sits ([maxSizeBit], from the payload start). */
    private class Sequence(
        val reduced: Boolean,
        val decoderModel: Boolean,
        val equalPictureInterval: Boolean,
        val removalTimeBits: Int,
        val presentationTimeBits: Int,
        val operatingPoints: List<Pair<Int, Boolean>>,
        val widthBits: Int,
        val heightBits: Int,
        val maxSizeBit: Long,
        val maxWidth: Int,
        val maxHeight: Int,
        val frameIdBits: Int,
        val screenContentTools: Int,
        val integerMv: Int,
        val orderHintBits: Int
    ) {
        companion object {
            /** Sequence header OBU syntax (AV1 spec 5.5), up to the order hint bits. */
            fun parse(r: Bits): Sequence {
                r.f(3)
                r.f(1)
                val reduced = r.f(1) == 1L
                var decoderModel = false
                var equalPictureInterval = false
                var bufferDelayBits = 0
                var removalTimeBits = 0
                var presentationTimeBits = 0
                val points = ArrayList<Pair<Int, Boolean>>()
                if (reduced) {
                    r.f(5)
                    points += 0 to false
                } else {
                    if (r.f(1) == 1L) {
                        r.f(32)
                        r.f(32)
                        equalPictureInterval = r.f(1) == 1L
                        if (equalPictureInterval) r.uvlc()
                        decoderModel = r.f(1) == 1L
                        if (decoderModel) {
                            bufferDelayBits = r.f(5).toInt() + 1
                            r.f(32)
                            removalTimeBits = r.f(5).toInt() + 1
                            presentationTimeBits = r.f(5).toInt() + 1
                        }
                    }
                    val initialDisplayDelay = r.f(1) == 1L
                    val count = r.f(5).toInt() + 1
                    repeat(count) {
                        val idc = r.f(12).toInt()
                        if (r.f(5) > 7) r.f(1)
                        var model = false
                        if (decoderModel) {
                            model = r.f(1) == 1L
                            if (model) {
                                r.f(bufferDelayBits)
                                r.f(bufferDelayBits)
                                r.f(1)
                            }
                        }
                        if (initialDisplayDelay && r.f(1) == 1L) r.f(4)
                        points += idc to model
                    }
                }
                val widthBits = r.f(4).toInt() + 1
                val heightBits = r.f(4).toInt() + 1
                val maxSizeBit = r.bit
                val maxWidth = r.f(widthBits).toInt() + 1
                val maxHeight = r.f(heightBits).toInt() + 1
                var frameIdBits = 0
                if (!reduced && r.f(1) == 1L) {
                    val delta = r.f(4).toInt() + 2
                    frameIdBits = r.f(3).toInt() + 1 + delta
                }
                r.f(3)
                var screenContentTools = SELECT
                var integerMv = SELECT
                var orderHintBits = 0
                if (!reduced) {
                    r.f(4)
                    val orderHint = r.f(1) == 1L
                    if (orderHint) r.f(2)
                    screenContentTools = if (r.f(1) == 1L) SELECT else r.f(1).toInt()
                    integerMv = if (screenContentTools > 0) (if (r.f(1) == 1L) SELECT else r.f(1).toInt()) else SELECT
                    if (orderHint) orderHintBits = r.f(3).toInt() + 1
                }
                return Sequence(
                    reduced, decoderModel, equalPictureInterval, removalTimeBits, presentationTimeBits, points, widthBits, heightBits, maxSizeBit,
                    maxWidth, maxHeight, frameIdBits, screenContentTools, integerMv, orderHintBits
                )
            }
        }
    }

    /** `[width, height]` of a shown intra frame (uncompressed header, AV1 spec 5.9.2); null for any other frame. */
    private fun frameSize(r: Bits, s: Sequence, temporalId: Int, spatialId: Int): IntArray? {
        val type: Int
        if (s.reduced) {
            type = KEY_FRAME
        } else {
            if (r.f(1) == 1L) return null
            type = r.f(2).toInt()
            if (r.f(1) == 0L) return null
            if (s.decoderModel && !s.equalPictureInterval) r.f(s.presentationTimeBits)
        }
        if (type != KEY_FRAME && type != INTRA_ONLY_FRAME) return null
        val errorResilient = if (type == KEY_FRAME || s.reduced) true else r.f(1) == 1L
        r.f(1)
        val screenContent = if (s.screenContentTools == SELECT) r.f(1).toInt() else s.screenContentTools
        if (screenContent > 0 && s.integerMv == SELECT) r.f(1)
        if (s.frameIdBits > 0) r.f(s.frameIdBits)
        val sizeOverride = if (s.reduced) false else r.f(1) == 1L
        r.f(s.orderHintBits)
        if (s.decoderModel && r.f(1) == 1L) {
            for ((idc, model) in s.operatingPoints) {
                if (!model) continue
                val inTemporal = (idc shr temporalId) and 1 == 1
                val inSpatial = (idc shr (spatialId + 8)) and 1 == 1
                if (idc == 0 || (inTemporal && inSpatial)) r.f(s.removalTimeBits)
            }
        }
        if (type == INTRA_ONLY_FRAME) {
            if (r.f(8) == 0xFFL) return null
            if (errorResilient && s.orderHintBits > 0) repeat(8) { r.f(s.orderHintBits) }
        }
        if (!sizeOverride) return intArrayOf(s.maxWidth, s.maxHeight)
        return intArrayOf(r.f(s.widthBits).toInt() + 1, r.f(s.heightBits).toInt() + 1)
    }

    /** An unsigned LEB128 number at [at] and the bytes it took. */
    private fun leb128(b: ByteArray, at: Int): Pair<Long, Int> {
        var value = 0L
        for (i in 0 until 8) {
            val byte = b[at + i].toInt() and 0xFF
            value = value or ((byte and 0x7F).toLong() shl (7 * i))
            if (byte and 0x80 == 0) return value to i + 1
        }
        throw IndexOutOfBoundsException("LEB128 too long")
    }

    /** Writes [value] as [count] bits, most significant first, from bit [bit] of [b]. */
    private fun writeBits(b: ByteArray, bit: Long, count: Int, value: Long) {
        for (i in 0 until count) {
            val at = bit + i
            val mask = 0x80 ushr (at and 7).toInt()
            val index = (at ushr 3).toInt()
            b[index] = if ((value shr (count - 1 - i)) and 1L == 1L) (b[index].toInt() or mask).toByte()
            else (b[index].toInt() and mask.inv()).toByte()
        }
    }

    /** Reads bits most significant first from byte [from] up to byte [to] of [b]; [bit] counts from [from]. */
    private class Bits(private val b: ByteArray, private val from: Int, private val to: Int) {
        var bit = 0L
            private set

        fun f(n: Int): Long {
            var v = 0L
            repeat(n) {
                val at = from + (bit ushr 3).toInt()
                if (at >= to) throw IndexOutOfBoundsException("Past the OBU")
                v = (v shl 1) or ((b[at].toInt() shr (7 - (bit and 7).toInt())) and 1).toLong()
                bit++
            }
            return v
        }

        fun uvlc(): Long {
            var zeros = 0
            while (f(1) == 0L) if (++zeros >= 32) return (1L shl 32) - 1
            return f(zeros) + (1L shl zeros) - 1
        }
    }
}
