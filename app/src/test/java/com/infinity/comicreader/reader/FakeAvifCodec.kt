package com.infinity.comicreader.reader

import android.graphics.Bitmap
import java.io.IOException

/**
 * An [AvifCodec] for JVM tests, which cannot load libavif: a file of [images] (one per
 * layer, the last the full image), each rendered as one solid color ([colors]). Opened
 * without layers, a layered file steps straight to its last image, as libavif does.
 * An incomplete file shorter than [metadataBytes] does not open; one shorter than
 * [layerBytes] has none of its layers. [failOpen] and [failRender] fail as libavif does.
 */
internal class FakeAvifCodec(
    private val file: IntArray,
    private val images: List<IntArray>,
    private val colors: List<Int>,
    private val metadataBytes: Int = 0,
    private val layerBytes: Int = 0,
    private val failOpen: Boolean = false,
    private val failRender: Boolean = false
) : AvifCodec {
    var layersAsked: Boolean? = null
    var opened = 0
    var closed = 0
    var nexts = 0
    val samples = ArrayList<Int>()
    private var layers = false
    private var index = -1
    private var available = 0

    override fun open(data: ByteArray, length: Int, complete: Boolean, threads: Int, layers: Boolean): Long {
        if (failOpen) throw IOException("Could not read AVIF: BMFF parsing failed")
        layersAsked = layers
        if (!complete && length < metadataBytes) return 0
        this.layers = layers
        available = if (complete) Int.MAX_VALUE else length
        index = -1
        opened++
        return 7
    }

    override fun info(handle: Long): IntArray {
        if (index >= 0) return images[index]
        val layered = images.size > 1
        return file.copyOf().also {
            it[6] = if (layers && layered) images.size else 1
            it[7] = if (!layered) 0 else if (layers) AvifInfo.ACTIVE else AvifInfo.AVAILABLE
        }
    }

    override fun next(handle: Long): Boolean {
        if (available < layerBytes) return false
        nexts++
        index = if (layers) index + 1 else images.size - 1
        return true
    }

    override fun render(handle: Long, bitmap: Bitmap, sample: Int, threads: Int) {
        if (failRender) throw IOException("Could not convert AVIF")
        val image = images[index]
        check(bitmap.width == (image[0] + sample - 1) / sample && bitmap.height == (image[1] + sample - 1) / sample)
        samples += sample
        bitmap.eraseColor(colors[index])
    }

    override fun close(handle: Long) {
        closed++
    }

    companion object {
        /** What [AvifCodec.info] returns for an image of these properties. */
        fun info(
            w: Int,
            h: Int,
            primaries: Int = 1,
            transfer: Int = 13,
            alpha: Boolean = false,
            rotation: Int? = null,
            mirror: Int? = null,
            crop: IntArray? = null
        ): IntArray = IntArray(20).also {
            it[0] = w
            it[1] = h
            it[2] = 8
            it[3] = 1
            it[4] = 1
            it[5] = if (alpha) 1 else 0
            it[6] = 1
            it[8] = primaries
            it[9] = transfer
            var transforms = 0
            if (rotation != null) {
                transforms = transforms or AvifInfo.IROT
                it[12] = rotation
            }
            if (mirror != null) {
                transforms = transforms or AvifInfo.IMIR
                it[13] = mirror
            }
            if (crop != null) {
                transforms = transforms or AvifInfo.CLAP
                crop.copyInto(it, 14)
            } else {
                intArrayOf(-1, -1, -1, -1).copyInto(it, 14)
            }
            it[11] = transforms
        }
    }
}
