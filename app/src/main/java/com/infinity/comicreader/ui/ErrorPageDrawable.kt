package com.infinity.comicreader.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils

/**
 * Stands in for a page that could not be decoded: a page-shaped card with a
 * crossed-out frame, [title], the page's [name] and the [reason]. Sized like a
 * portrait page so every fit mode lays it out as one.
 */
class ErrorPageDrawable(
    private val title: String,
    private val name: String,
    private val reason: String
) : Drawable() {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CARD }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = LINE
        style = Paint.Style.STROKE
        strokeWidth = WIDTH * 0.006f
    }
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = TEXT
        textSize = WIDTH * 0.06f
        typeface = Typeface.DEFAULT_BOLD
    }
    private val namePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = TEXT
        textSize = WIDTH * 0.045f
    }
    private val reasonPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MUTED
        textSize = WIDTH * 0.04f
    }
    private val textWidth = (WIDTH * 0.8f).toInt()
    private val layouts = listOf(
        layout(title, titlePaint, 2, TextUtils.TruncateAt.END),
        // One line, so the middle of a long path gives way and the file name stays.
        layout(name, namePaint, 1, TextUtils.TruncateAt.MIDDLE),
        layout(reason, reasonPaint, 4, TextUtils.TruncateAt.END)
    )

    /** Multi-line layouts only ellipsize at the [TextUtils.TruncateAt.END]. */
    private fun layout(text: String, paint: TextPaint, maxLines: Int, cut: TextUtils.TruncateAt): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, textWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setEllipsize(cut)
            .setMaxLines(maxLines)
            .build()

    override fun getIntrinsicWidth(): Int = WIDTH

    override fun getIntrinsicHeight(): Int = HEIGHT

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        canvas.scale(b.width() / WIDTH.toFloat(), b.height() / HEIGHT.toFloat())

        val inset = WIDTH * 0.04f
        val card = RectF(inset, inset, WIDTH - inset, HEIGHT - inset)
        val corner = WIDTH * 0.03f
        canvas.drawRoundRect(card, corner, corner, fill)
        canvas.drawRoundRect(card, corner, corner, stroke)

        // A crossed-out picture frame above the text.
        val icon = WIDTH * 0.22f
        val cx = WIDTH / 2f
        val top = HEIGHT * 0.3f
        val frame = RectF(cx - icon / 2, top, cx + icon / 2, top + icon * 0.8f)
        canvas.drawRect(frame, stroke)
        canvas.drawLine(frame.left, frame.bottom, frame.right, frame.top, stroke)

        var y = frame.bottom + WIDTH * 0.08f
        val left = (WIDTH - textWidth) / 2f
        for (l in layouts) {
            canvas.save()
            canvas.translate(left, y)
            l.draw(canvas)
            canvas.restore()
            y += l.height + WIDTH * 0.04f
        }
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {
        listOf(fill, stroke, titlePaint, namePaint, reasonPaint).forEach { it.alpha = alpha }
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        listOf(fill, stroke, titlePaint, namePaint, reasonPaint).forEach { it.colorFilter = colorFilter }
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        /** A portrait page: 1:1.414, the ISO paper ratio. */
        const val WIDTH = 1000
        const val HEIGHT = 1414
        const val CARD = 0xFF262626.toInt()
        const val LINE = 0xFF5A5A5A.toInt()
        const val TEXT = 0xFFE0E0E0.toInt()
        const val MUTED = 0xFF9E9E9E.toInt()
    }
}
