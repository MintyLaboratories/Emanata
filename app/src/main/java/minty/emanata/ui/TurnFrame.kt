package minty.emanata.ui

import android.content.Context
import android.widget.FrameLayout

/**
 * Shows its children turned [turn] quarters, filling it: each is laid out at the frame's
 * size swapped, centered, and rotated, so a wide page fills a portrait screen. Touches
 * reach the children in their own turned coordinates, so a pinch or a pan follows the
 * picture as shown; a child that needs the device's axes asks [deviceX] and [deviceY].
 */
class TurnFrame(context: Context) : FrameLayout(context) {

    /** Quarter turns the children show at: 1 clockwise (their top to the right), -1 counter-clockwise, 0 upright. */
    var turn = 0
        set(value) {
            val v = value.coerceIn(-1, 1)
            if (field == v) return
            field = v
            requestLayout()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (turn == 0) return super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        val across = MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
        val down = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY)
        for (i in 0 until childCount) getChildAt(i).measure(across, down)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        if (turn == 0) {
            super.onLayout(changed, left, top, right, bottom)
            for (i in 0 until childCount) getChildAt(i).apply {
                rotation = 0f
                translationX = 0f
                translationY = 0f
            }
            return
        }
        val w = right - left
        val h = bottom - top
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val l = (w - h) / 2
            val t = (h - w) / 2
            child.layout(l, t, l + h, t + w)
            // Centered exactly, also when the sides differ by an odd number of pixels.
            child.translationX = w / 2f - (l + h / 2f)
            child.translationY = h / 2f - (t + w / 2f)
            child.rotation = 90f * turn
        }
    }

    companion object {
        /** The device's x part of a move ([x], [y]) in a view turned [turn] quarters. */
        fun deviceX(turn: Int, x: Float, y: Float): Float = when (turn) {
            1 -> -y
            -1 -> y
            else -> x
        }

        /** The device's y part of a move ([x], [y]) in a view turned [turn] quarters. */
        fun deviceY(turn: Int, x: Float, y: Float): Float = when (turn) {
            1 -> x
            -1 -> -x
            else -> y
        }
    }
}
