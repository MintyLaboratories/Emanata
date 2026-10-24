package com.infinity.comicreader.reader

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * A resampling kernel, [radius] source pixels each way at scale 1:
 * - [HERMITE]: the cubic with no negative lobes, smooth and fastest;
 * - [CATMULL_ROM]: the bicubic with one negative lobe, sharper;
 * - [LANCZOS3] and [LANCZOS6]: `sinc(x) * sinc(x / r)`, sharper still and more ringing as
 *   `r` grows; computed from `sin`, exact for any radius.
 */
enum class ScaleFilter(val key: String, val radius: Int) {
    HERMITE("hermite", 1),
    CATMULL_ROM("bicubic", 2),
    LANCZOS3("lanczos3", 3),
    LANCZOS6("lanczos6", 6);

    /** The kernel's weight at offset [x], in source pixels at scale 1; 0 from [radius] on. */
    fun weight(x: Double): Double {
        val ax = abs(x)
        if (ax >= radius) return 0.0
        return when (this) {
            HERMITE -> (2 * ax - 3) * ax * ax + 1
            CATMULL_ROM -> if (ax < 1) (1.5 * ax - 2.5) * ax * ax + 1 else ((-0.5 * ax + 2.5) * ax - 4) * ax + 2
            LANCZOS3, LANCZOS6 -> {
                if (ax < 1e-12) return 1.0
                val px = PI * ax
                radius * sin(px) * sin(px / radius) / (px * px)
            }
        }
    }

    companion object {
        val DEFAULT = LANCZOS3

        fun of(key: String?): ScaleFilter = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

/**
 * How an image is resampled: through [filter], with anti-ringing at [upRing] percent where
 * an axis is enlarged and [downRing] percent where it is shrunk. Anti-ringing, as in
 * libplacebo, blends each filtered value toward itself clamped to the range of the
 * source pixels nearest it: the two either side when enlarging, those under the output
 * pixel when shrinking; per axis, after each pass. At 0 % there is no clamp step at all.
 */
data class Scaling(val filter: ScaleFilter = ScaleFilter.DEFAULT, val upRing: Int = 0, val downRing: Int = DEFAULT_DOWN_RING) {
    init {
        require(upRing in 0..100 && downRing in 0..100) { "Anti-ringing is 0..100 %" }
    }

    /** Anti-ringing strength, 0..1, for an axis [enlarged] or not. */
    fun ring(enlarged: Boolean): Float = (if (enlarged) upRing else downRing) / 100f

    /**
     * Part of a cache key for images that shrink, as covers do: [filter] and [downRing] only, so
     * a change to [upRing] keeps them. Empty for [DEFAULT]'s, which covers made before these
     * settings carry no token for.
     */
    val shrinkKey: String get() = if (filter == DEFAULT.filter && downRing == DEFAULT.downRing) "" else "${filter.key}r$downRing"

    companion object {
        const val DEFAULT_DOWN_RING = 50

        /** What the settings start at: Lanczos3, no anti-ringing enlarging, half shrinking. */
        val DEFAULT = Scaling()

        /** Lanczos3 without anti-ringing: what scans for page facts (seams, sides, direction) read. */
        val PLAIN = Scaling(ScaleFilter.LANCZOS3, 0, 0)
    }
}
