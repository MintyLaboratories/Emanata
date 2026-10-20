package com.infinity.comicreader.reader

import android.graphics.ColorFilter
import android.graphics.ColorMatrixColorFilter

/** Named color filters applied to rendered pages. */
object Filters {
    const val NONE = "none"
    const val INVERT = "invert"
    const val GRAYSCALE = "grayscale"
    const val CONTRAST = "contrast"
    const val SEPIA = "sepia"

    /**
     * The filter's 4 x 5 color matrix in [android.graphics.ColorMatrix] layout (rows R, G, B, A;
     * offsets in 0..255), or null for none. [INVERT]'s is the plain RGB invert, the fallback
     * where the GPU's lightness invert ([Look]) cannot run.
     */
    fun matrix(name: String): FloatArray? = when (name) {
        INVERT -> floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f
        )

        // ColorMatrix.setSaturation(0): Rec. 709 luma in every channel.
        GRAYSCALE -> floatArrayOf(
            0.213f, 0.715f, 0.072f, 0f, 0f,
            0.213f, 0.715f, 0.072f, 0f, 0f,
            0.213f, 0.715f, 0.072f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )

        CONTRAST -> {
            val c = 1.35f
            val t = (1f - c) * 127.5f + 8f
            floatArrayOf(
                c, 0f, 0f, 0f, t,
                0f, c, 0f, 0f, t,
                0f, 0f, c, 0f, t,
                0f, 0f, 0f, 1f, 0f
            )
        }

        SEPIA -> floatArrayOf(
            0.393f, 0.769f, 0.189f, 0f, 0f,
            0.349f, 0.686f, 0.168f, 0f, 0f,
            0.272f, 0.534f, 0.131f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )

        else -> null
    }

    fun colorFilter(name: String): ColorFilter? = matrix(name)?.let { ColorMatrixColorFilter(it) }
}
