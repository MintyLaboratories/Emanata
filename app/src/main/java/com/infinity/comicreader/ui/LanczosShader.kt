package com.infinity.comicreader.ui

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * Draws a bitmap through Lanczos3 on the GPU (AGSL), for zooming.
 *
 * - Each screen pixel maps to a source position and weighs the 6 x 6 source pixels
 *   around it, one unfiltered fetch each; the weights are normalized per axis.
 * - The result is clamped to the range of the 2 x 2 source pixels nearest it, which
 *   keeps the kernel's ringing out of flat areas beside hard edges.
 * - Past the bitmap's edges its edge pixels repeat.
 * - Made for enlarging: shrinking keeps the kernel at source size, so it aliases as
 *   point sampling would.
 *
 * The bitmap's own coordinates are the draw coordinates: draw a rectangle of its size
 * under the matrix that places it.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class LanczosShader {
    private val shader = RuntimeShader(SOURCE)
    private val paint = Paint().apply { shader = this@LanczosShader.shader }
    private var bitmap: Bitmap? = null

    /** The paint drawing [bitmap], with [colorFilter] and [alpha] applied after filtering. */
    fun paint(bitmap: Bitmap, colorFilter: ColorFilter?, alpha: Int): Paint {
        if (bitmap !== this.bitmap) {
            val image = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            image.filterMode = BitmapShader.FILTER_MODE_NEAREST
            shader.setInputShader("image", image)
            this.bitmap = bitmap
        }
        paint.colorFilter = colorFilter
        paint.alpha = alpha
        return paint
    }

    private companion object {
        // Source pixel i has its center at i + 0.5. A position p lies between the
        // pixels at base and base + 1 on each axis; the taps run from base - 2 to
        // base + 3, at offsets k - f for k = -2..3.
        const val SOURCE = """
uniform shader image;

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

half4 main(float2 p) {
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
    }
}
