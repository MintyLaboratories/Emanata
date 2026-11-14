package com.infinity.comicreader.debug

import android.graphics.Bitmap
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A synthetic 2x shader chain ([CHAIN]) and the same arithmetic on the CPU in fp32 ([render]),
 * to check the GLES runner's output against: texture offsets in both axes (orientation),
 * signed values between passes (float textures), a depth-to-space enlargement as Anime4K
 * writes it (`fract(pos * size)` picking a channel), bilinear sampling of a smaller texture,
 * a one-channel texture read past its channel (COMPONENTS), and two PREKERNEL passes, the
 * second binding MAIN (the MAIN stage's result, not the darkened image).
 */
internal object ShaderReference {
    val CHAIN = """
        //!DESC edge
        //!HOOK MAIN
        //!BIND HOOKED
        //!SAVE edge
        //!COMPONENTS 4
        vec4 hook() {
            vec4 c = HOOKED_texOff(vec2(0.0, 0.0));
            return vec4(HOOKED_texOff(vec2(1.0, 0.0)).rgb - c.rgb, HOOKED_texOff(vec2(0.0, -1.0)).g - c.g);
        }
        //!DESC luma
        //!HOOK MAIN
        //!BIND HOOKED
        //!SAVE luma
        //!COMPONENTS 1
        vec4 hook() {
            vec4 c = HOOKED_tex(HOOKED_pos);
            return vec4(dot(c.rgb, vec3(0.299, 0.587, 0.114)), 0.7, 0.7, 0.7);
        }
        //!DESC up
        //!HOOK MAIN
        //!BIND MAIN
        //!BIND edge
        //!SAVE MAIN
        //!WIDTH edge.w 2 *
        //!HEIGHT edge.h 2 *
        //!WHEN OUTPUT.w MAIN.w / 1.200 >
        vec4 hook() {
            vec2 f = fract(edge_pos * edge_size);
            ivec2 i = ivec2(f * vec2(2.0));
            float d = edge_tex((vec2(0.5) - f) * edge_pt + edge_pos)[i.y * 2 + i.x];
            return MAIN_tex(MAIN_pos) + vec4(d * 0.5);
        }
        //!DESC darken
        //!HOOK PREKERNEL
        //!BIND HOOKED
        vec4 hook() {
            return HOOKED_tex(HOOKED_pos) * 0.9;
        }
        //!DESC clamp
        //!HOOK PREKERNEL
        //!BIND HOOKED
        //!BIND luma
        //!BIND MAIN
        vec4 hook() {
            vec4 c = HOOKED_tex(HOOKED_pos);
            vec4 l = luma_tex(HOOKED_pos);
            return vec4(min(c.rgb, vec3(l.x + l.y + 0.25)), 1.0) * 0.8 + MAIN_tex(MAIN_pos) * 0.2;
        }
    """.trimIndent()

    /** A float RGBA image, rows top to bottom as the runner keeps them. */
    class Image(val w: Int, val h: Int) {
        val px = FloatArray(w * h * 4)

        /** Channel [c] of texel ([x], [y]), edges clamped. */
        fun at(x: Int, y: Int, c: Int): Float = px[(y.coerceIn(0, h - 1) * w + x.coerceIn(0, w - 1)) * 4 + c]

        fun set(x: Int, y: Int, c: Int, v: Float) {
            px[(y * w + x) * 4 + c] = v
        }

        /** Channel [c] sampled bilinearly at ([u], [v]) in texture coordinates, edges clamped, as GLES does. */
        fun sample(u: Float, v: Float, c: Int): Float {
            val x = u * w - 0.5f
            val y = v * h - 0.5f
            val x0 = floor(x).toInt()
            val y0 = floor(y).toInt()
            val fx = x - x0
            val fy = y - y0
            val top = at(x0, y0, c) * (1 - fx) + at(x0 + 1, y0, c) * fx
            val bottom = at(x0, y0 + 1, c) * (1 - fx) + at(x0 + 1, y0 + 1, c) * fx
            return top * (1 - fy) + bottom * fy
        }
    }

    /** [CHAIN] on [src] (opaque), computed on the CPU: twice its size. */
    fun render(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h).also { src.getPixels(it, 0, w, 0, 0, w, h) }
        val main = Image(w, h)
        for (i in pixels.indices) {
            val p = pixels[i]
            main.px[i * 4] = ((p shr 16) and 0xFF) / 255f
            main.px[i * 4 + 1] = ((p shr 8) and 0xFF) / 255f
            main.px[i * 4 + 2] = (p and 0xFF) / 255f
            main.px[i * 4 + 3] = 1f
        }
        val edge = Image(w, h)
        val luma = Image(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            for (c in 0..2) edge.set(x, y, c, main.at(x + 1, y, c) - main.at(x, y, c))
            edge.set(x, y, 3, main.at(x, y - 1, 1) - main.at(x, y, 1))
            luma.set(x, y, 0, 0.299f * main.at(x, y, 0) + 0.587f * main.at(x, y, 1) + 0.114f * main.at(x, y, 2))
            // One channel kept: the rest read 0, alpha 1.
            luma.set(x, y, 3, 1f)
        }
        val ow = w * 2
        val oh = h * 2
        val up = Image(ow, oh)
        val out = IntArray(ow * oh)
        for (y in 0 until oh) for (x in 0 until ow) {
            val u = (x + 0.5f) / ow
            val v = (y + 0.5f) / oh
            // The edge texel holding this pixel, and which of its channels the pixel's quarter picks.
            val d = edge.at(x / 2, y / 2, (y % 2) * 2 + x % 2)
            for (c in 0..3) up.set(x, y, c, main.sample(u, v, c) + d * 0.5f)
        }
        for (y in 0 until oh) for (x in 0 until ow) {
            val u = (x + 0.5f) / ow
            val v = (y + 0.5f) / oh
            val l = luma.sample(u, v, 0) + luma.sample(u, v, 1) + 0.25f
            var rgb = 0
            for (c in 0..2) {
                val dark = up.at(x, y, c) * 0.9f
                val value = min(dark, l) * 0.8f + up.at(x, y, c) * 0.2f
                rgb = (rgb shl 8) or floor(value.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            }
            out[y * ow + x] = rgb or (0xFF shl 24)
        }
        return Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888).apply { setPixels(out, 0, ow, 0, 0, ow, oh) }
    }

    /** How two images of the same size differ: the largest channel difference, its mean, and the PSNR over RGB (dB; [PSNR_EQUAL] when equal). */
    class Compared(val maxDiff: Int, val meanDiff: Double, val psnr: Double)

    fun compare(a: Bitmap, b: Bitmap): Compared {
        require(a.width == b.width && a.height == b.height) { "Sizes differ" }
        val n = a.width * a.height
        val pa = IntArray(n).also { a.getPixels(it, 0, a.width, 0, 0, a.width, a.height) }
        val pb = IntArray(n).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
        var worst = 0
        var sum = 0L
        var squares = 0.0
        for (i in 0 until n) {
            for (s in 0..16 step 8) {
                val d = ((pa[i] shr s) and 0xFF) - ((pb[i] shr s) and 0xFF)
                worst = max(worst, kotlin.math.abs(d))
                sum += kotlin.math.abs(d)
                squares += d.toDouble() * d
            }
        }
        val mse = squares / (3.0 * n)
        val psnr = if (mse == 0.0) PSNR_EQUAL else min(PSNR_EQUAL, (10 * log10(255.0 * 255.0 / mse) * 100).roundToInt() / 100.0)
        return Compared(worst, sum.toDouble() / (3.0 * n), psnr)
    }

    /** The PSNR given equal images, in dB. */
    const val PSNR_EQUAL = 100.0
}
