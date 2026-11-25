package minty.emanata.reader

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Brightness, contrast and saturation, each -100..100 with 0 leaving the page unchanged. */
data class Adjustments(val brightness: Int = 0, val contrast: Int = 0, val saturation: Int = 0) {

    val neutral: Boolean get() = brightness == 0 && contrast == 0 && saturation == 0

    /** -1..1: how far colors blend toward black (negative) or white (positive). */
    val brightnessAmount: Float get() = clamp(brightness) / 100f

    /**
     * What lightness distances from mid-gray are multiplied by: 0 (flat mid-gray) at -100,
     * 1 at 0, and 1 / (1 - c) above, capped at [MAX_CONTRAST] short of a hard threshold.
     */
    val contrastFactor: Float
        get() {
            val c = clamp(contrast) / 100f
            return if (c <= 0f) 1f + c else 1f / (1f - min(c, MAX_CONTRAST))
        }

    /** What chroma is multiplied by: 0 (gray) at -100, 2 at +100. */
    val saturationFactor: Float get() = 1f + clamp(saturation) / 100f

    /** These values within -100..100. */
    fun coerced() = Adjustments(clamp(brightness), clamp(contrast), clamp(saturation))

    /** True when a book with these adjustments needs its own: they differ from [defaults]. */
    fun ownAgainst(defaults: Adjustments): Boolean = coerced() != defaults.coerced()

    companion object {
        val NEUTRAL = Adjustments()
        const val MIN = -100
        const val MAX = 100

        /** Contrast +100 multiplies by 1 / (1 - this) = 50. */
        const val MAX_CONTRAST = 0.98f

        private fun clamp(v: Int) = v.coerceIn(MIN, MAX)
    }
}

/**
 * What a page's colors go through, in order: a filter's color [matrix] on sRGB values,
 * then in OKLab a lightness [invert] (hue kept), then the [adjustments], then a chroma
 * clamp back into the sRGB gamut that keeps lightness and hue.
 *
 * [apply] is the reference for the GPU's color stage ([minty.emanata.ui.PageShader]),
 * which follows it step for step.
 */
class Look(val matrix: FloatArray?, val invert: Boolean, val adjustments: Adjustments) {

    /** True when only the [matrix], if any, applies: no OKLab stage is needed. */
    val plain: Boolean get() = !invert && adjustments.neutral

    /** The matrix's color rows as `[r, g, b, offset]` with the offset in 0..1, for R, G and B. */
    fun rows(): Array<FloatArray> {
        val m = matrix ?: IDENTITY
        return Array(3) { row -> floatArrayOf(m[row * 5], m[row * 5 + 1], m[row * 5 + 2], m[row * 5 + 4] / 255f) }
    }

    /** An unpremultiplied sRGB color ([r], [g], [b] in 0..1) through the look. */
    fun apply(r: Float, g: Float, b: Float): FloatArray {
        val rows = rows()
        val s = FloatArray(3) { (rows[it][0] * r + rows[it][1] * g + rows[it][2] * b + rows[it][3]).coerceIn(0f, 1f) }
        val lab = Oklab.fromSrgb(s[0], s[1], s[2])
        adjust(lab)
        val c = gamut(lab[0], lab[1], lab[2])
        return FloatArray(3) { Oklab.fromLinear(c[it]) }
    }

    /** An opaque or translucent ARGB color through the look; alpha is kept. */
    fun apply(argb: Int): Int {
        val alpha = argb ushr 24
        if (alpha == 0) return argb
        val out = apply(channel(argb, 16), channel(argb, 8), channel(argb, 0))
        return (alpha shl 24) or (byte(out[0]) shl 16) or (byte(out[1]) shl 8) or byte(out[2])
    }

    /** Applies [invert] and [adjustments] to OKLab `[L, a, b]` in place. */
    fun adjust(lab: FloatArray) {
        var l = lab[0]
        var a = lab[1]
        var b = lab[2]
        if (invert) l = 1f - l
        // Brightness: a blend toward black (0, 0, 0) or white (1, 0, 0).
        val t = abs(adjustments.brightnessAmount)
        val target = if (adjustments.brightnessAmount >= 0f) 1f else 0f
        l += (target - l) * t
        a *= 1f - t
        b *= 1f - t
        // Contrast: lightness about mid-gray; lowering it also fades toward gray.
        val k = adjustments.contrastFactor
        a *= min(k, 1f)
        b *= min(k, 1f)
        l = 0.5f + (l - 0.5f) * k
        val s = adjustments.saturationFactor
        lab[0] = l.coerceIn(0f, 1f)
        lab[1] = a * s
        lab[2] = b * s
    }

    companion object {
        /** Bisection steps that find the largest in-gamut chroma. */
        const val GAMUT_STEPS = 12

        /** How far outside 0..1 a linear channel may stray and still count as in gamut. */
        const val GAMUT_SLACK = 1e-4f

        val NONE = Look(null, false, Adjustments.NEUTRAL)

        private val IDENTITY = floatArrayOf(
            1f, 0f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )

        /** The look of page filter [filter] (one of [Filters]) with [adjustments]. */
        fun of(filter: String, adjustments: Adjustments): Look =
            if (filter == Filters.INVERT) Look(null, true, adjustments)
            else Look(Filters.matrix(filter), false, adjustments)

        /**
         * OKLab ([lightness], [a], [b]) in linear sRGB, its chroma scaled down by bisection
         * until it fits the gamut; lightness and hue stay.
         */
        fun gamut(lightness: Float, a: Float, b: Float): FloatArray {
            val c = Oklab.toLinearRgb(lightness, a, b)
            if (inGamut(c)) return c
            var lo = 0f
            var hi = 1f
            repeat(GAMUT_STEPS) {
                val mid = 0.5f * (lo + hi)
                if (inGamut(Oklab.toLinearRgb(lightness, a * mid, b * mid))) lo = mid else hi = mid
            }
            val fit = Oklab.toLinearRgb(lightness, a * lo, b * lo)
            return FloatArray(3) { fit[it].coerceIn(0f, 1f) }
        }

        private fun inGamut(c: FloatArray): Boolean =
            min(min(c[0], c[1]), c[2]) >= -GAMUT_SLACK && max(max(c[0], c[1]), c[2]) <= 1f + GAMUT_SLACK

        private fun channel(argb: Int, shift: Int): Float = ((argb shr shift) and 0xFF) / 255f

        private fun byte(v: Float): Int = (v * 255f + 0.5f).toInt().coerceIn(0, 255)
    }
}
