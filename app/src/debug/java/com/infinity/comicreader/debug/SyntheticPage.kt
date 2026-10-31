package com.infinity.comicreader.debug

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import kotlin.random.Random

/** Synthetic pages for the check services. */
internal object SyntheticPage {
    /** A synthetic comic page: white paper, ink lines and hatching, flat and graded color, a noisy panel. */
    fun draw(w: Int, h: Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.WHITE)
        val r = Random(w)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val panelH = h / 3f
        for (i in 0 until 3) {
            val top = i * panelH + w * 0.02f
            val bottom = (i + 1) * panelH - w * 0.02f
            paint.shader = LinearGradient(0f, top, 0f, bottom, Color.rgb(250, 220, 160), Color.rgb(60, 110, 190), Shader.TileMode.CLAMP)
            paint.style = Paint.Style.FILL
            c.drawRect(w * 0.04f, top, w * 0.96f, bottom, paint)
            paint.shader = null
            paint.color = Color.BLACK
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = w / 400f
            c.drawRect(w * 0.04f, top, w * 0.96f, bottom, paint)
            repeat(60) {
                paint.strokeWidth = w / (300f + r.nextInt(900))
                c.drawLine(w * (0.05f + 0.9f * r.nextFloat()), top + (bottom - top) * r.nextFloat(), w * (0.05f + 0.9f * r.nextFloat()), top + (bottom - top) * r.nextFloat(), paint)
            }
        }
        // Fine hatching, the hardest case for ringing.
        paint.strokeWidth = 1f
        var x = w * 0.1f
        while (x < w * 0.4f) {
            c.drawLine(x, h * 0.4f, x, h * 0.55f, paint)
            x += 3f
        }
        val noisy = IntArray(w / 4 * (h / 8)) { 0xFF000000.toInt() or r.nextInt(1 shl 24) }
        b.setPixels(noisy, 0, w / 4, w / 2, h / 2, w / 4, h / 8)
        // Opaque, as a decoded JPEG page is.
        b.setHasAlpha(false)
        return b
    }
}
