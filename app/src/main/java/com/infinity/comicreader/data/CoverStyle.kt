package com.infinity.comicreader.data

import kotlin.math.abs

/** How a cover meets its box. */
enum class ThumbFit(val key: String) {
    /** Crops the cover to fill the box. */
    FILL("fill"),

    /** Shows the whole cover over a blurred, filled copy of itself. */
    FIT("fit"),

    /** [FILL] when the cover's shape is within [AUTO_TOLERANCE] of the box's, else [FIT]. */
    AUTO("auto");

    companion object {
        /** How far a cover's width / height may stray from the box's, relatively, and still fill it under [AUTO]. */
        const val AUTO_TOLERANCE = 0.10

        fun of(key: String?): ThumbFit = entries.firstOrNull { it.key == key } ?: AUTO
    }
}

/** Which part of a cover too tall for its box stays when it is cropped. */
enum class CropAnchor(val key: String) {
    TOP("top"),
    CENTER("center");

    companion object {
        fun of(key: String?): CropAnchor = entries.firstOrNull { it.key == key } ?: TOP
    }
}

/** How covers are made; a different style is a different cached cover. */
data class CoverStyle(
    val fit: ThumbFit = ThumbFit.AUTO,
    val anchor: CropAnchor = CropAnchor.TOP,
    val linear: Boolean = false
) {
    /** Part of the cache key: one token per setting. */
    val key: String get() = "${fit.key}|${anchor.key}" + if (linear) "|linear" else ""

    /** True when a [srcW] x [srcH] cover fills a [boxW] x [boxH] box (cropped) rather than fits inside it. */
    fun fills(srcW: Int, srcH: Int, boxW: Int, boxH: Int): Boolean = when (fit) {
        ThumbFit.FILL -> true
        ThumbFit.FIT -> false
        ThumbFit.AUTO -> {
            val src = srcW.toDouble() / srcH
            val box = boxW.toDouble() / boxH
            abs(src - box) <= ThumbFit.AUTO_TOLERANCE * box
        }
    }
}
