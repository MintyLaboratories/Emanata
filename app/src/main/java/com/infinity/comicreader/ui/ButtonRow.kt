package com.infinity.comicreader.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout

/**
 * Buttons, or other views, side by side where they fit at their own widths, else stacked; gone ones
 * take no room. A divider shown between them (`showDividers="middle"`) is their gap either way, so
 * stacked views keep the same room as each other.
 */
class ButtonRow @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        var wanted = 0
        var shown = 0
        val free = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            child.measure(free, free)
            val lp = child.layoutParams as MarginLayoutParams
            wanted += child.measuredWidth + lp.marginStart + lp.marginEnd
            shown++
        }
        if (showDividers and SHOW_DIVIDER_MIDDLE != 0 && shown > 1) wanted += (shown - 1) * (dividerDrawable?.intrinsicWidth ?: 0)
        orientation = if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED && wanted > available) VERTICAL else HORIZONTAL
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
