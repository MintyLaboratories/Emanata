package com.infinity.comicreader.reader

import kotlin.math.abs

/**
 * What auto-crop found on an image decoded smaller than it is (a cap's power-of-two
 * sampling, [Target.capPixels]), told in terms of the full image, as a decode of the full
 * image with that crop ([Target.box]) cuts it. The margin auto-crop leaves around what it
 * finds ([BitmapUtils.contentBounds]) is wider than a sampled pixel, so the full image
 * never loses content to the sampling.
 */
object SampledCrop {

    /**
     * [found] (`[left, top, right, bottom]` pixels of a [w] x [h] decode, null when it has
     * no blank border) as a [Box], and the size to report it on: the full [source] size
     * when it has the decode's shape, else the decode's own.
     */
    fun of(found: IntArray?, w: Int, h: Int, source: IntArray?): Pair<Box, IntArray> =
        Box.of(found, w, h) to (source?.takeIf { sameShape(it, w, h) } ?: intArrayOf(w, h))

    /** True when [source] is at least [w] x [h] and of its width / height, as power-of-two sampling rounds it. */
    private fun sameShape(source: IntArray, w: Int, h: Int): Boolean {
        if (source.size < 2 || w <= 0 || h <= 0 || source[0] < w || source[1] < h) return false
        val scale = source[0].toDouble() / w
        return abs(source[1] / scale - h) <= 1.0 + h * 0.01
    }
}
