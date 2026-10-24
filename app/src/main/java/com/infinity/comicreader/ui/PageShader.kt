package com.infinity.comicreader.ui

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import com.infinity.comicreader.reader.Look
import com.infinity.comicreader.reader.ScaleFilter

/**
 * Draws a bitmap on the GPU (AGSL) through one of three [Kernel]s, optionally followed
 * by the color stage of a [Look] in the same pass.
 *
 * [Kernel.FILTERED], for zooming in, runs [filter] as the CPU resampler ([Resampler])
 * does when enlarging:
 * - Each screen pixel maps to a source position and weighs the `2r x 2r` source pixels
 *   around it (`r` the filter's radius), one unfiltered fetch each; the weights are
 *   normalized per axis.
 * - With [antiRing], each row's horizontal sum is blended toward itself clamped to the
 *   two source pixels either side, by the `ring` strength ([paint]), then the vertical
 *   sum toward itself clamped to the two rows either side: the CPU's steps, per axis.
 *   Without it there is no clamp code at all.
 * - Past the bitmap's edges its edge pixels repeat; the result is clamped into range,
 *   as the CPU's is.
 * - Made for enlarging: shrinking keeps the kernel at source size, so it aliases as
 *   point sampling would.
 *
 * [Kernel.NEAREST] (1:1) and [Kernel.LINEAR] (shrinking) fetch once, unfiltered or
 * bilinear, as a plain bitmap draw would; they exist for the color stage, which mirrors
 * [Look.apply] step for step.
 *
 * The bitmap's own coordinates are the draw coordinates: draw a rectangle of its size
 * under the matrix that places it. [dialect] rewrites the source only for tests.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class PageShader internal constructor(
    val kernel: Kernel,
    val colored: Boolean,
    val filter: ScaleFilter = ScaleFilter.DEFAULT,
    val antiRing: Boolean = false,
    dialect: (String) -> String = { it }
) {
    enum class Kernel(val filterMode: Int) {
        FILTERED(BitmapShader.FILTER_MODE_NEAREST),
        NEAREST(BitmapShader.FILTER_MODE_NEAREST),
        LINEAR(BitmapShader.FILTER_MODE_LINEAR)
    }

    private val ringing = kernel == Kernel.FILTERED && antiRing
    private val shader = RuntimeShader(dialect(source(kernel, colored, filter, ringing)))
    private val paint = Paint().apply { shader = this@PageShader.shader }
    private var bitmap: Bitmap? = null
    private var look: Look? = null
    private var ring = -1f

    /**
     * The paint drawing [bitmap] with [look] (ignored unless [colored]), then [colorFilter]
     * and [alpha]; [ring] is the anti-ringing strength, 0..1, used with [antiRing].
     */
    fun paint(bitmap: Bitmap, colorFilter: ColorFilter?, alpha: Int, look: Look = Look.NONE, ring: Float = 0f): Paint {
        if (bitmap !== this.bitmap) {
            val image = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            image.filterMode = kernel.filterMode
            shader.setInputShader("image", image)
            this.bitmap = bitmap
        }
        if (colored && look !== this.look) setLook(look)
        if (ringing && ring != this.ring) {
            shader.setFloatUniform("ring", floatArrayOf(ring))
            this.ring = ring
        }
        paint.colorFilter = colorFilter
        paint.alpha = alpha
        return paint
    }

    // Uniforms go in as arrays: the host Skia that runs the tests misreads the other overloads.
    private fun setLook(look: Look) {
        val rows = look.rows()
        shader.setFloatUniform("matR", rows[0])
        shader.setFloatUniform("matG", rows[1])
        shader.setFloatUniform("matB", rows[2])
        shader.setFloatUniform("invert", floatArrayOf(if (look.invert) 1f else 0f))
        val a = look.adjustments
        shader.setFloatUniform("brightness", floatArrayOf(a.brightnessAmount))
        shader.setFloatUniform("contrast", floatArrayOf(a.contrastFactor))
        shader.setFloatUniform("saturation", floatArrayOf(a.saturationFactor))
        this.look = look
    }

    /** Lets go of the bitmap last drawn, which the shader and its paint would otherwise keep alive. */
    fun clear() {
        if (bitmap == null) return
        shader.setInputShader("image", empty)
        // Setting the paint's shader again drops the native copy it took at the last draw.
        paint.shader = null
        paint.shader = shader
        bitmap = null
    }

    /** The bitmap the shader holds; for tests. */
    internal val held: Bitmap? get() = bitmap

    private val empty by lazy { LinearGradient(0f, 0f, 1f, 0f, Color.TRANSPARENT, Color.TRANSPARENT, Shader.TileMode.CLAMP) }

    internal companion object {
        /** The AGSL for [kernel], with the color stage when [colored]; [filter] and [antiRing] for [Kernel.FILTERED]. */
        fun source(kernel: Kernel, colored: Boolean, filter: ScaleFilter = ScaleFilter.DEFAULT, antiRing: Boolean = false): String {
            val filtered = if (kernel == Kernel.FILTERED) filtered(filter, antiRing) else SAMPLE
            val main = if (colored) "$COLOR\nhalf4 main(float2 p) {\n    return look(filtered(p));\n}\n"
            else "half4 main(float2 p) {\n    return filtered(p);\n}\n"
            return "uniform shader image;\n$filtered\n$main"
        }

        private const val SAMPLE = """
half4 filtered(float2 p) {
    return image.eval(p);
}
"""

        /** [filter]'s weights at four offsets at once, as [ScaleFilter.weight] computes each. */
        private fun weight(filter: ScaleFilter): String = when (filter) {
            ScaleFilter.HERMITE -> """
float4 weight(float4 x) {
    float4 a = abs(x);
    return ((2.0 * a - 3.0) * a * a + 1.0) * (1.0 - step(1.0, a));
}
"""
            ScaleFilter.CATMULL_ROM -> """
float4 weight(float4 x) {
    float4 a = abs(x);
    float4 inner = (1.5 * a - 2.5) * a * a + 1.0;
    float4 outer = (((-0.5 * a + 2.5) * a - 4.0) * a + 2.0) * (1.0 - step(2.0, a));
    return mix(outer, inner, 1.0 - step(1.0, a));
}
"""
            ScaleFilter.LANCZOS3, ScaleFilter.LANCZOS6 -> """
const float PI = 3.14159265358979;
const float R = ${filter.radius}.0;

// sinc(x) * sinc(x / R) on |x| < R, from sin itself; the floor keeps sinc(0) finite.
float4 weight(float4 x) {
    float4 a = abs(x);
    float4 px = PI * max(a, float4(1e-4));
    return R * sin(px) * sin(px / R) / (px * px) * (1.0 - step(R, a));
}
"""
        }

        /**
         * The filtered sample for [filter], weights unrolled: source pixel i has its center at
         * i + 0.5, and a position p lies between the pixels at base and base + 1 on each
         * axis; the taps run from base - r + 1 to base + r, at offsets k - f. Taps r - 1
         * and r are the two nearest, which anti-ringing clamps to.
         */
        private fun filtered(filter: ScaleFilter, antiRing: Boolean): String {
            val r = filter.radius
            val n = 2 * r
            val (a, b) = r - 1 to r
            val out = StringBuilder()
            if (antiRing) out.append("\nuniform float ring;\n")
            out.append(weight(filter))
            out.append("\nhalf4 filtered(float2 p) {\n")
            out.append("    float2 c = p - 0.5;\n    float2 base = floor(c);\n    float2 f = c - base;\n")
            out.append("    float wx[$n];\n    float wy[$n];\n")
            // Two taps on both axes a call: x and y of one tap, then of the next.
            for (t in 0 until n step 2) {
                out.append("    float4 w$t = weight(float4(${t - r + 1}.0, ${t - r + 1}.0, ${t - r + 2}.0, ${t - r + 2}.0) - f.xyxy);\n")
                out.append("    wx[$t] = w$t.x;\n    wy[$t] = w$t.y;\n    wx[${t + 1}] = w$t.z;\n    wy[${t + 1}] = w$t.w;\n")
            }
            for (axis in listOf("x", "y")) {
                out.append("    float s$axis = 1.0 / (${(0 until n).joinToString(" + ") { "w$axis[$it]" }});\n")
            }
            out.append("    float2 at = base - ${r - 1}.0 + 0.5;\n")
            // Rows, then taps, in loops: few values live, a row's sum h, its two nearest
            // pixels (na, nb) and the two nearest rows' sums (ha, hb).
            out.append("    float4 sum = float4(0.0);\n")
            if (antiRing) out.append("    float4 ha = float4(0.0);\n    float4 hb = float4(0.0);\n")
            out.append("    for (int j = 0; j < $n; j++) {\n        float4 h = float4(0.0);\n")
            if (antiRing) out.append("        float4 na = float4(0.0);\n        float4 nb = float4(0.0);\n")
            out.append("        for (int i = 0; i < $n; i++) {\n")
            out.append("            float4 e = float4(image.eval(at + float2(float(i), float(j))));\n")
            out.append("            h += e * wx[i];\n")
            if (antiRing) out.append("            if (i == $a) {\n                na = e;\n            }\n            if (i == $b) {\n                nb = e;\n            }\n")
            out.append("        }\n        h *= sx;\n")
            if (antiRing) {
                out.append("        h += ring * (clamp(h, min(na, nb), max(na, nb)) - h);\n")
                out.append("        if (j == $a) {\n            ha = h;\n        }\n        if (j == $b) {\n            hb = h;\n        }\n")
            }
            out.append("        sum += h * wy[j];\n    }\n")
            out.append("    sum *= sy;\n")
            if (antiRing) out.append("    sum += ring * (clamp(sum, min(ha, hb), max(ha, hb)) - sum);\n")
            // Into range as the CPU packs it: alpha 0..1, premultiplied color 0..alpha.
            out.append("    float alpha = clamp(sum.a, 0.0, 1.0);\n")
            out.append("    return half4(half3(clamp(sum.rgb, 0.0, alpha)), half(alpha));\n}\n")
            return out.toString()
        }

        // Look.apply on the GPU: the filter matrix on sRGB values, then in OKLab the
        // invert, brightness, contrast and saturation, then the gamut clamp by bisection.
        private const val COLOR = """
uniform float4 matR;
uniform float4 matG;
uniform float4 matB;
uniform float invert;
uniform float brightness;
uniform float contrast;
uniform float saturation;

float3 toLinear(float3 c) {
    return mix(c / 12.92, pow((c + 0.055) / 1.055, float3(2.4)), step(0.04045, c));
}

float3 fromLinear(float3 c) {
    return mix(c * 12.92, 1.055 * pow(c, float3(1.0 / 2.4)) - 0.055, step(0.0031308, c));
}

float3 linearToOklab(float3 c) {
    float3 lms = float3(
        dot(c, float3(0.4122214708, 0.5363325363, 0.0514459929)),
        dot(c, float3(0.2119034982, 0.6806995451, 0.1073969566)),
        dot(c, float3(0.0883024619, 0.2817188376, 0.6299787005)));
    lms = pow(max(lms, float3(0.0)), float3(1.0 / 3.0));
    return float3(
        dot(lms, float3(0.2104542553, 0.7936177850, -0.0040720468)),
        dot(lms, float3(1.9779984951, -2.4285922050, 0.4505937099)),
        dot(lms, float3(0.0259040371, 0.7827717662, -0.8086757660)));
}

float3 oklabToLinear(float3 lab) {
    float3 lms = float3(
        dot(lab, float3(1.0, 0.3963377774, 0.2158037573)),
        dot(lab, float3(1.0, -0.1055613458, -0.0638541728)),
        dot(lab, float3(1.0, -0.0894841775, -1.2914855480)));
    lms = lms * lms * lms;
    return float3(
        dot(lms, float3(4.0767416621, -3.3077115913, 0.2309699292)),
        dot(lms, float3(-1.2684380046, 2.6097574011, -0.3413193965)),
        dot(lms, float3(-0.0041960863, -0.7034186147, 1.7076147010)));
}

bool inGamut(float3 c) {
    return min(min(c.r, c.g), c.b) >= -1e-4 && max(max(c.r, c.g), c.b) <= 1.0001;
}

float3 gamut(float3 lab) {
    float3 c = oklabToLinear(lab);
    if (inGamut(c)) {
        return c;
    }
    float lo = 0.0;
    float hi = 1.0;
    for (int i = 0; i < 12; i++) {
        float mid = 0.5 * (lo + hi);
        if (inGamut(oklabToLinear(float3(lab.x, lab.yz * mid)))) {
            lo = mid;
        } else {
            hi = mid;
        }
    }
    return clamp(oklabToLinear(float3(lab.x, lab.yz * lo)), 0.0, 1.0);
}

half4 look(half4 color) {
    float alpha = float(color.a);
    if (alpha <= 0.0) {
        return color;
    }
    float4 c = float4(float3(color.rgb) / alpha, 1.0);
    float3 s = clamp(float3(dot(matR, c), dot(matG, c), dot(matB, c)), 0.0, 1.0);
    float3 lab = linearToOklab(toLinear(s));
    lab.x = mix(lab.x, 1.0 - lab.x, invert);
    lab = mix(lab, float3(step(0.0, brightness), 0.0, 0.0), abs(brightness));
    lab.yz *= min(contrast, 1.0);
    lab.x = clamp(0.5 + (lab.x - 0.5) * contrast, 0.0, 1.0);
    lab.yz *= saturation;
    return half4(half3(fromLinear(gamut(lab)) * alpha), half(alpha));
}
"""
    }
}
