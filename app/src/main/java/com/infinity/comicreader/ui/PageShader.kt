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

/**
 * Draws a bitmap on the GPU (AGSL) through one of three [Kernel]s, optionally followed
 * by the color stage of a [Look] in the same pass.
 *
 * [Kernel.LANCZOS], for zooming:
 * - Each screen pixel maps to a source position and weighs the 6 x 6 source pixels
 *   around it, one unfiltered fetch each; the weights are normalized per axis.
 * - The result is clamped to the range of the 2 x 2 source pixels nearest it, which
 *   keeps the kernel's ringing out of flat areas beside hard edges.
 * - Past the bitmap's edges its edge pixels repeat.
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
    dialect: (String) -> String = { it }
) {
    enum class Kernel(val filterMode: Int) {
        LANCZOS(BitmapShader.FILTER_MODE_NEAREST),
        NEAREST(BitmapShader.FILTER_MODE_NEAREST),
        LINEAR(BitmapShader.FILTER_MODE_LINEAR)
    }

    private val shader = RuntimeShader(dialect(source(kernel, colored)))
    private val paint = Paint().apply { shader = this@PageShader.shader }
    private var bitmap: Bitmap? = null
    private var look: Look? = null

    /**
     * The paint drawing [bitmap] with [look] (ignored unless [colored]), then [colorFilter]
     * and [alpha].
     */
    fun paint(bitmap: Bitmap, colorFilter: ColorFilter?, alpha: Int, look: Look = Look.NONE): Paint {
        if (bitmap !== this.bitmap) {
            val image = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            image.filterMode = kernel.filterMode
            shader.setInputShader("image", image)
            this.bitmap = bitmap
        }
        if (colored && look !== this.look) setLook(look)
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
        /** The AGSL for [kernel], with the color stage when [colored]. */
        fun source(kernel: Kernel, colored: Boolean): String {
            val filtered = if (kernel == Kernel.LANCZOS) LANCZOS else SAMPLE
            val main = if (colored) "$COLOR\nhalf4 main(float2 p) {\n    return look(filtered(p));\n}\n"
            else "half4 main(float2 p) {\n    return filtered(p);\n}\n"
            return "uniform shader image;\n$filtered\n$main"
        }

        private const val SAMPLE = """
half4 filtered(float2 p) {
    return image.eval(p);
}
"""

        // Source pixel i has its center at i + 0.5. A position p lies between the
        // pixels at base and base + 1 on each axis; the taps run from base - 2 to
        // base + 3, at offsets k - f for k = -2..3.
        private const val LANCZOS = """
const float PI = 3.14159265358979;

// Lanczos3 at offsets |x| <= 3; the floor keeps sinc(0) finite.
float3 lanczos3(float3 x) {
    float3 px = PI * max(abs(x), float3(1e-4));
    return 3.0 * sin(px) * sin(px / 3.0) / (px * px);
}

// Six pixels from the one centered at 'at' rightward, weighted; the middle two out.
float4 row(float2 at, float3 lo, float3 hi, out float4 left, out float4 right) {
    left = float4(image.eval(at + float2(2.0, 0.0)));
    right = float4(image.eval(at + float2(3.0, 0.0)));
    return float4(image.eval(at)) * lo.x
        + float4(image.eval(at + float2(1.0, 0.0))) * lo.y
        + left * lo.z
        + right * hi.x
        + float4(image.eval(at + float2(4.0, 0.0))) * hi.y
        + float4(image.eval(at + float2(5.0, 0.0))) * hi.z;
}

half4 filtered(float2 p) {
    float2 c = p - 0.5;
    float2 base = floor(c);
    float2 f = c - base;

    float3 xlo = lanczos3(float3(-2.0, -1.0, 0.0) - f.x);
    float3 xhi = lanczos3(float3(1.0, 2.0, 3.0) - f.x);
    float sx = dot(xlo + xhi, float3(1.0));
    xlo /= sx;
    xhi /= sx;
    float3 ylo = lanczos3(float3(-2.0, -1.0, 0.0) - f.y);
    float3 yhi = lanczos3(float3(1.0, 2.0, 3.0) - f.y);
    float sy = dot(ylo + yhi, float3(1.0));
    ylo /= sy;
    yhi /= sy;

    float2 at = base - 1.5;
    float4 a;
    float4 b;
    float4 sum = row(at, xlo, xhi, a, b) * ylo.x;
    sum += row(at + float2(0.0, 1.0), xlo, xhi, a, b) * ylo.y;
    sum += row(at + float2(0.0, 2.0), xlo, xhi, a, b) * ylo.z;
    float4 lo = min(a, b);
    float4 hi = max(a, b);
    sum += row(at + float2(0.0, 3.0), xlo, xhi, a, b) * yhi.x;
    lo = min(lo, min(a, b));
    hi = max(hi, max(a, b));
    sum += row(at + float2(0.0, 4.0), xlo, xhi, a, b) * yhi.y;
    sum += row(at + float2(0.0, 5.0), xlo, xhi, a, b) * yhi.z;
    return half4(clamp(sum, lo, hi));
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
