package com.infinity.comicreader.data

/** Cover thumbnail sizes, kept free of Android types so they can be unit-tested. */
object ThumbSizing {

    /**
     * Side lengths a requested cover box is rounded up to. Covers are cached per
     * tier, so nearby cell sizes share one bitmap and a much larger cell gets its own.
     */
    val TIERS = intArrayOf(96, 128, 192, 256, 384, 512, 768, 1024, 1536, 2048)

    /** The smallest tier at least [px] long; the largest one for anything above it. */
    fun tier(px: Int): Int = TIERS.firstOrNull { it >= px } ?: TIERS.last()

    /**
     * Scale that makes a [w] x [h] image just cover a [boxW] x [boxH] box, as a
     * center-cropped cover needs. Never above 1: small images are kept as they are.
     */
    fun coverScale(w: Int, h: Int, boxW: Int, boxH: Int): Float {
        if (w <= 0 || h <= 0) return 1f
        return maxOf(boxW.toFloat() / w, boxH.toFloat() / h).coerceAtMost(1f)
    }
}
