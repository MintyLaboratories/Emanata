package minty.emanata.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import minty.emanata.R

/**
 * A thin track from [BrightnessPill.MIN] to [BrightnessPill.MAX] with a mark at 0, filled
 * from 0 to [value]: the brightness drag indicator's bar.
 */
class SignedBar @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.pill_track) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, android.R.color.white) }
    private val zero = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.reader_accent) }
    private val rect = RectF()

    var value = 0
        set(v) {
            if (field == v) return
            field = v
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = h / 2
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, r, r, track)
        val mid = w * BrightnessPill.fraction(0)
        val end = w * BrightnessPill.fraction(value)
        rect.set(minOf(mid, end), 0f, maxOf(mid, end), h)
        canvas.drawRoundRect(rect, r, r, fill)
        val mark = h / 2
        rect.set(mid - mark, 0f, mid + mark, h)
        canvas.drawRect(rect, if (value == 0) zero else fill)
    }
}
