package minty.emanata.ui

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
import minty.emanata.reader.Look
import minty.emanata.reader.Rcas
import minty.emanata.reader.ScaleFilter

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
 *   sum toward itself clamped to the two rows either side: the CPU's steps and ranges
 *   ([Resampler.ringRange]) per axis, down to 0.5×. Without it there is no clamp code at all.
 * - Past the bitmap's edges its edge pixels repeat; the result is clamped into range,
 *   as the CPU's is.
 * - With [sharpen], [Rcas] follows, as the CPU's sharpening follows its resample: the
 *   filtered sample and its four neighbors one screen pixel away on each axis ([paint]'s
 *   `stepX` and `stepY`) come from one (2r + 2)² window of fetches, each anti-ringed, put
 *   into range and rounded to 8-bit codes as the CPU's output is, then sharpened by the
 *   `amount`. Without it there is no sharpening code at all.
 * - Made for enlarging: shrinking keeps the kernel at source size, so it aliases as
 *   point sampling would.
 * - Lanczos6 costs four times Lanczos3's fetches: moving pictures draw through
 *   [moving]'s filter.
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
    val sharpen: Boolean = false,
    dialect: (String) -> String = { it }
) {
    enum class Kernel(val filterMode: Int) {
        FILTERED(BitmapShader.FILTER_MODE_NEAREST),
        NEAREST(BitmapShader.FILTER_MODE_NEAREST),
        LINEAR(BitmapShader.FILTER_MODE_LINEAR)
    }

    private val ringing = kernel == Kernel.FILTERED && antiRing
    private val sharpening = kernel == Kernel.FILTERED && sharpen
    private val shader = RuntimeShader(dialect(source(kernel, colored, filter, ringing, sharpening)))
    private val paint = Paint().apply { shader = this@PageShader.shader }
    private var bitmap: Bitmap? = null
    private var look: Look? = null
    private var ring = -1f
    private var amount = -1f
    private var stepX = -1f
    private var stepY = -1f

    /**
     * The paint drawing [bitmap] with [look] (ignored unless [colored]), then [colorFilter]
     * and [alpha]; [ring] is the anti-ringing strength, 0..1, used with [antiRing]; [amount]
     * the sharpening ([Rcas.amount]) and [stepX] and [stepY] the bitmap pixels one screen
     * pixel spans on each axis (1 / scale), used with [sharpen].
     */
    fun paint(
        bitmap: Bitmap,
        colorFilter: ColorFilter?,
        alpha: Int,
        look: Look = Look.NONE,
        ring: Float = 0f,
        amount: Float = 0f,
        stepX: Float = 1f,
        stepY: Float = 1f
    ): Paint {
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
        if (sharpening && amount != this.amount) {
            shader.setFloatUniform("amount", floatArrayOf(amount))
            this.amount = amount
        }
        if (sharpening && (stepX != this.stepX || stepY != this.stepY)) {
            shader.setFloatUniform("spacing", floatArrayOf(stepX, stepY))
            this.stepX = stepX
            this.stepY = stepY
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
        /**
         * The filter drawn with while the picture moves, in place of [filter]: one pass
         * fetches `(2r)²` source pixels a screen pixel, so Lanczos6 (144) draws as Lanczos3
         * (36) until the view is still; the others as they are.
         */
        fun moving(filter: ScaleFilter): ScaleFilter = if (filter == ScaleFilter.LANCZOS6) ScaleFilter.LANCZOS3 else filter

        /** The AGSL for [kernel], with the color stage when [colored]; [filter], [antiRing] and [sharpen] for [Kernel.FILTERED]. */
        fun source(
            kernel: Kernel, colored: Boolean, filter: ScaleFilter = ScaleFilter.DEFAULT, antiRing: Boolean = false, sharpen: Boolean = false
        ): String {
            val filtered = when {
                kernel != Kernel.FILTERED -> SAMPLE
                sharpen -> sharpened(filter, antiRing)
                else -> filtered(filter, antiRing)
            }
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

        /**
         * [filtered] followed by [Rcas]: the samples at the position, a screen pixel left and
         * right of it (`M` and `P` along x) and above and below it (`M` and `P` along y), from one
         * window of `2r + 2` taps each way, base - r to base + r + 1, which holds every
         * sample's taps while a step is at most a pixel (enlarging). Each sample weighs the
         * window with its own weights, zero past its taps; anti-ringing clamps each to its own
         * two nearest pixels, then rows. The five are put into range and rounded to codes as
         * the CPU's output is, then sharpened.
         */
        private fun sharpened(filter: ScaleFilter, antiRing: Boolean): String {
            val r = filter.radius
            val n = 2 * r + 2
            val out = StringBuilder()
            out.append("\nuniform float amount;\nuniform float2 spacing;\n")
            if (antiRing) out.append("uniform float ring;\n")
            out.append(weight(filter))
            out.append(RCAS)
            out.append("\nhalf4 filtered(float2 p) {\n")
            out.append("    float2 c = p - 0.5;\n    float2 base = floor(c);\n    float2 f = c - base;\n")
            out.append("    float2 d = min(spacing, float2(1.0));\n    float2 fm = f - d;\n    float2 fp = f + d;\n")
            for (k in listOf("M", "C", "P")) out.append("    float wx$k[$n];\n    float wy$k[$n];\n")
            for (t in 0 until n step 2) {
                for ((k, at) in listOf("M" to "fm", "C" to "f", "P" to "fp")) {
                    out.append("    float4 w$k$t = weight(float4(${t - r}.0, ${t - r}.0, ${t - r + 1}.0, ${t - r + 1}.0) - $at.xyxy);\n")
                    out.append("    wx$k[$t] = w$k$t.x;\n    wy$k[$t] = w$k$t.y;\n    wx$k[${t + 1}] = w$k$t.z;\n    wy$k[${t + 1}] = w$k$t.w;\n")
                }
            }
            for (axis in listOf("x", "y")) {
                for (k in listOf("M", "C", "P")) {
                    out.append("    float s$axis$k = 1.0 / (${(0 until n).joinToString(" + ") { "w$axis$k[$it]" }});\n")
                }
            }
            out.append("    float2 at = base - $r.0 + 0.5;\n")
            if (antiRing) {
                // Each sample's nearest pixel as a window index: r for the position itself.
                out.append("    int mx = $r + int(floor(fm.x));\n    int px = $r + int(floor(fp.x));\n")
                out.append("    int my = $r + int(floor(fm.y));\n    int py = $r + int(floor(fp.y));\n")
                for (v in listOf("hCa", "hCb", "hLa", "hLb", "hRa", "hRb", "hUa", "hUb", "hDa", "hDb")) out.append("    float4 $v = float4(0.0);\n")
            }
            out.append("    float4 sL = float4(0.0);\n    float4 sC = float4(0.0);\n    float4 sR = float4(0.0);\n")
            out.append("    float4 sU = float4(0.0);\n    float4 sD = float4(0.0);\n")
            out.append("    for (int j = 0; j < $n; j++) {\n")
            out.append("        float4 hL = float4(0.0);\n        float4 hC = float4(0.0);\n        float4 hR = float4(0.0);\n")
            if (antiRing) for (v in listOf("la", "lb", "ca", "cb", "ra", "rb")) out.append("        float4 $v = float4(0.0);\n")
            out.append("        for (int i = 0; i < $n; i++) {\n")
            out.append("            float4 e = float4(image.eval(at + float2(float(i), float(j))));\n")
            out.append("            hL += e * wxM[i];\n            hC += e * wxC[i];\n            hR += e * wxP[i];\n")
            if (antiRing) {
                for ((v, idx) in listOf("la" to "mx", "lb" to "mx + 1", "ca" to "$r", "cb" to "${r + 1}", "ra" to "px", "rb" to "px + 1")) {
                    out.append("            if (i == $idx) {\n                $v = e;\n            }\n")
                }
            }
            out.append("        }\n        hL *= sxM;\n        hC *= sxC;\n        hR *= sxP;\n")
            if (antiRing) {
                for ((h, a, b) in listOf(Triple("hL", "la", "lb"), Triple("hC", "ca", "cb"), Triple("hR", "ra", "rb"))) {
                    out.append("        $h += ring * (clamp($h, min($a, $b), max($a, $b)) - $h);\n")
                }
            }
            out.append("        sL += hL * wyC[j];\n        sC += hC * wyC[j];\n        sR += hR * wyC[j];\n")
            out.append("        sU += hC * wyM[j];\n        sD += hC * wyP[j];\n")
            if (antiRing) {
                out.append("        if (j == $r) {\n            hCa = hC;\n            hLa = hL;\n            hRa = hR;\n        }\n")
                out.append("        if (j == ${r + 1}) {\n            hCb = hC;\n            hLb = hL;\n            hRb = hR;\n        }\n")
                for ((v, idx) in listOf("hUa" to "my", "hUb" to "my + 1", "hDa" to "py", "hDb" to "py + 1")) {
                    out.append("        if (j == $idx) {\n            $v = hC;\n        }\n")
                }
            }
            out.append("    }\n")
            out.append("    sL *= syC;\n    sC *= syC;\n    sR *= syC;\n    sU *= syM;\n    sD *= syP;\n")
            if (antiRing) {
                for ((sum, a) in listOf("sL" to "hL", "sC" to "hC", "sR" to "hR", "sU" to "hU", "sD" to "hD")) {
                    out.append("    $sum += ring * (clamp($sum, min(${a}a, ${a}b), max(${a}a, ${a}b)) - $sum);\n")
                }
            }
            out.append("    return half4(rcas(code(sU), code(sL), code(sC), code(sR), code(sD)));\n}\n")
            return out.toString()
        }

        /**
         * [Rcas.pixel] on the GPU, in 0..1 (its peak), each sample put into range and rounded
         * to an 8-bit code first ([code]) as the CPU's resample packs it; premultiplied color.
         */
        private const val RCAS = """
float4 code(float4 v) {
    float a = clamp(v.a, 0.0, 1.0);
    return floor(float4(clamp(v.rgb, 0.0, a), a) * 255.0 + 0.5) / 255.0;
}

float rcasLobe(float b, float d, float e, float f, float h) {
    float mn = min(min(b, d), min(f, h));
    float mx = max(max(b, d), max(f, h));
    float hitMin = mx > 0.0 ? min(mn, e) / (4.0 * mx) : 1.0;
    float hitMax = mn < 1.0 ? (1.0 - max(mx, e)) / (4.0 * mn - 4.0) : -1.0;
    return max(-hitMin, hitMax);
}

float rcasLuma(float4 p) {
    return p.b * 0.5 + (p.r * 0.5 + p.g);
}

float4 rcas(float4 b, float4 d, float4 e, float4 f, float4 h) {
    if (min(min(b.a, d.a), min(min(e.a, f.a), h.a)) < 1.0) {
        return e;
    }
    float bL = rcasLuma(b);
    float dL = rcasLuma(d);
    float eL = rcasLuma(e);
    float fL = rcasLuma(f);
    float hL = rcasLuma(h);
    float pass = 0.25 * bL + 0.25 * dL + 0.25 * fL + 0.25 * hL - eL;
    float range = max(max(max(bL, dL), max(eL, fL)), hL) - min(min(min(bL, dL), min(eL, fL)), hL);
    float noise = range > 0.0 ? min(abs(pass) / range, 1.0) : 0.0;
    float nz = 1.0 - 0.5 * noise;
    float lr = rcasLobe(b.r, d.r, e.r, f.r, h.r);
    float lg = rcasLobe(b.g, d.g, e.g, f.g, h.g);
    float lb = rcasLobe(b.b, d.b, e.b, f.b, h.b);
    float lobe = max(-0.1875, min(max(lr, max(lg, lb)), 0.0)) * amount * nz;
    float3 rgb = (lobe * (b.rgb + d.rgb + f.rgb + h.rgb) + e.rgb) / (4.0 * lobe + 1.0);
    return float4(clamp(rgb, 0.0, 1.0), 1.0);
}
"""

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
