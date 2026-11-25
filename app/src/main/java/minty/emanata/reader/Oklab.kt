package minty.emanata.reader

import kotlin.math.pow

/**
 * sRGB and OKLab conversions in single precision, as the GPU computes them.
 *
 * OKLab's L is perceived lightness (0 = black, 1 = white); a and b are the green-red
 * and blue-yellow axes, so chroma is their length and hue their angle.
 */
object Oklab {
    /** One sRGB-encoded channel (0..1) in linear light. */
    fun toLinear(c: Float): Float = if (c < 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)

    /** One linear channel (0..1) sRGB-encoded. */
    fun fromLinear(c: Float): Float = if (c < 0.0031308f) c * 12.92f else 1.055f * c.pow(1f / 2.4f) - 0.055f

    /** Linear sRGB ([r], [g], [b] in 0..1) as OKLab `[L, a, b]`. */
    fun fromLinearRgb(r: Float, g: Float, b: Float): FloatArray {
        val l = cbrt(0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b)
        val m = cbrt(0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b)
        val s = cbrt(0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b)
        return floatArrayOf(
            0.2104542553f * l + 0.7936177850f * m - 0.0040720468f * s,
            1.9779984951f * l - 2.4285922050f * m + 0.4505937099f * s,
            0.0259040371f * l + 0.7827717662f * m - 0.8086757660f * s
        )
    }

    /** OKLab ([lightness], [a], [b]) as linear sRGB `[r, g, b]`, unclamped. */
    fun toLinearRgb(lightness: Float, a: Float, b: Float): FloatArray {
        val l = cube(lightness + 0.3963377774f * a + 0.2158037573f * b)
        val m = cube(lightness - 0.1055613458f * a - 0.0638541728f * b)
        val s = cube(lightness - 0.0894841775f * a - 1.2914855480f * b)
        return floatArrayOf(
            4.0767416621f * l - 3.3077115913f * m + 0.2309699292f * s,
            -1.2684380046f * l + 2.6097574011f * m - 0.3413193965f * s,
            -0.0041960863f * l - 0.7034186147f * m + 1.7076147010f * s
        )
    }

    /** sRGB-encoded channels (0..1) as OKLab `[L, a, b]`. */
    fun fromSrgb(r: Float, g: Float, b: Float): FloatArray = fromLinearRgb(toLinear(r), toLinear(g), toLinear(b))

    /** OKLab as sRGB-encoded `[r, g, b]`, each clamped to 0..1. */
    fun toSrgb(lightness: Float, a: Float, b: Float): FloatArray {
        val c = toLinearRgb(lightness, a, b)
        return FloatArray(3) { fromLinear(c[it].coerceIn(0f, 1f)) }
    }

    /** Cube root of a non-negative value. */
    private fun cbrt(x: Float): Float = x.coerceAtLeast(0f).pow(1f / 3f)

    private fun cube(x: Float): Float = x * x * x
}
