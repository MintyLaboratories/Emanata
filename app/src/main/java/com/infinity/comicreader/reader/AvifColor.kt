package com.infinity.comicreader.reader

import android.graphics.ColorSpace
import android.os.Build

/**
 * The color space of decoded AVIF pixels, from the file's CICP (`nclx`) color primaries
 * and transfer characteristics (ITU-T H.273). The matrix coefficients and range are
 * already applied by libavif's YUV to RGB conversion.
 *
 * Pixels need no conversion ([space] null) with BT.709 primaries (also when unspecified
 * or unknown) and the sRGB curve or any other curve without its own handling here: the
 * SDR video curves of BT.709, BT.601 and BT.2020 are shown as sRGB, as browsers and
 * image viewers do. Other primaries, pure power curves and linear light are converted
 * to sRGB; PQ and HLG only with BT.2020 primaries from API 34, else shown with the sRGB
 * curve. An ICC profile is not read: such files are taken as their CICP says.
 */
object AvifColor {
    /** `[rx, ry, gx, gy, bx, by]` of each H.273 ColourPrimaries value that has them. */
    private val PRIMARIES = mapOf(
        1 to floatArrayOf(0.640f, 0.330f, 0.300f, 0.600f, 0.150f, 0.060f),
        4 to floatArrayOf(0.67f, 0.33f, 0.21f, 0.71f, 0.14f, 0.08f),
        5 to floatArrayOf(0.64f, 0.33f, 0.29f, 0.60f, 0.15f, 0.06f),
        6 to floatArrayOf(0.630f, 0.340f, 0.310f, 0.595f, 0.155f, 0.070f),
        7 to floatArrayOf(0.630f, 0.340f, 0.310f, 0.595f, 0.155f, 0.070f),
        8 to floatArrayOf(0.681f, 0.319f, 0.243f, 0.692f, 0.145f, 0.049f),
        9 to floatArrayOf(0.708f, 0.292f, 0.170f, 0.797f, 0.131f, 0.046f),
        11 to floatArrayOf(0.680f, 0.320f, 0.265f, 0.690f, 0.150f, 0.060f),
        12 to floatArrayOf(0.680f, 0.320f, 0.265f, 0.690f, 0.150f, 0.060f),
        22 to floatArrayOf(0.630f, 0.340f, 0.295f, 0.605f, 0.155f, 0.077f),
    )

    private val D65 = floatArrayOf(0.3127f, 0.3290f)
    private val ILLUMINANT_C = floatArrayOf(0.310f, 0.316f)
    private val DCI_WHITE = floatArrayOf(0.314f, 0.351f)

    /** Exponents of the pure power curves: BT.470 M and B/G, SMPTE ST 428, and linear light. */
    private val GAMMAS = mapOf(4 to 2.2, 5 to 2.8, 8 to 1.0, 17 to 2.6)

    private const val BT709 = 1
    private const val BT2020 = 9
    private const val PQ = 16
    private const val HLG = 18

    /** The sRGB curve, for other primaries. */
    private val SRGB_CURVE = ColorSpace.Rgb.TransferParameters(1 / 1.055, 0.055 / 1.055, 1 / 12.92, 0.04045, 2.4)

    /** `[rx, ry, gx, gy, bx, by]` of [primaries]; null for BT.709's (sRGB's) and for values without any. */
    internal fun primariesOf(primaries: Int): FloatArray? = if (primaries == BT709) null else PRIMARIES[primaries]

    /** The white point (`[x, y]`) that goes with [primaries]. */
    internal fun whiteOf(primaries: Int): FloatArray = when (primaries) {
        4, 8 -> ILLUMINANT_C
        11 -> DCI_WHITE
        else -> D65
    }

    /** The exponent of [transfer] when it is a pure power curve. */
    internal fun gammaOf(transfer: Int): Double? = GAMMAS[transfer]

    /** True when pixels of these CICP values are shown as they are, as sRGB. */
    fun isSrgb(primaries: Int, transfer: Int): Boolean =
        primariesOf(primaries) == null && gammaOf(transfer) == null

    /** The color space of pixels with these CICP values; null when they are sRGB ([isSrgb]). */
    fun space(primaries: Int, transfer: Int): ColorSpace? {
        if (primaries == BT2020 && (transfer == PQ || transfer == HLG) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return ColorSpace.get(if (transfer == PQ) ColorSpace.Named.BT2020_PQ else ColorSpace.Named.BT2020_HLG)
        }
        if (isSrgb(primaries, transfer)) return null
        val own = primariesOf(primaries)
        val xy = own ?: PRIMARIES.getValue(BT709)
        val white = if (own == null) D65 else whiteOf(primaries)
        val name = "AVIF $primaries/$transfer"
        val gamma = gammaOf(transfer)
        return if (gamma != null) ColorSpace.Rgb(name, xy, white, gamma) else ColorSpace.Rgb(name, xy, white, SRGB_CURVE)
    }
}
