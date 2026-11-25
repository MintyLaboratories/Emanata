package minty.emanata.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.appcompat.content.res.AppCompatResources
import com.google.android.material.color.MaterialColors
import minty.emanata.R
import minty.emanata.ai.ModelRatings

/**
 * A rating as stars, sized with the text ([STAR_SP], so with the font size): [halves] half
 * stars (filled stars, the last one half filled where odd), one empty star for
 * [ModelRatings.WORSE], nothing for 0. Says nothing to screen readers: the row it is on does.
 */
class StarsView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var halves: Int = 0
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
            invalidate()
        }

    private val size = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, STAR_SP, resources.displayMetrics).toInt()
    private val gap = size / 8

    private val full: Drawable = star(R.drawable.ic_star)
    private val empty: Drawable = star(R.drawable.ic_star_border)

    private fun star(id: Int): Drawable = AppCompatResources.getDrawable(context, id)!!.mutate().apply {
        val color = MaterialColors.getColor(context, androidx.appcompat.R.attr.colorPrimary, 0)
        if (color != 0) setTint(color)
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** Stars drawn, whole or half. */
    val count: Int get() = if (halves == ModelRatings.WORSE) 1 else (halves.coerceIn(0, ModelRatings.MAX) + 1) / 2

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val n = count
        val w = paddingLeft + paddingRight + if (n == 0) 0 else n * size + (n - 1) * gap
        setMeasuredDimension(resolveSize(w, widthMeasureSpec), resolveSize(paddingTop + paddingBottom + size, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        val top = paddingTop
        for (i in 0 until count) {
            val left = if (rtl) width - paddingRight - (i + 1) * size - i * gap else paddingLeft + i * (size + gap)
            when {
                halves == ModelRatings.WORSE -> draw(canvas, empty, left, top)
                i * 2 + 1 == halves -> {
                    // A half star: its outline, filled on the side the stars start from.
                    draw(canvas, empty, left, top)
                    canvas.save()
                    if (rtl) canvas.clipRect(left + size / 2, top, left + size, top + size)
                    else canvas.clipRect(left, top, left + size / 2, top + size)
                    draw(canvas, full, left, top)
                    canvas.restore()
                }
                else -> draw(canvas, full, left, top)
            }
        }
    }

    private fun draw(canvas: Canvas, d: Drawable, left: Int, top: Int) {
        d.setBounds(left, top, left + size, top + size)
        d.draw(canvas)
    }

    companion object {
        /** A star's size, in sp. */
        const val STAR_SP = 16f
    }
}
