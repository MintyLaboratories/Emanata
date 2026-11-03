package com.infinity.comicreader.ui

import android.content.Context
import android.util.AttributeSet
import com.google.android.material.tabs.TabLayout

/**
 * Tabs that share the width equally while every label fits its share on one line, and scroll
 * otherwise (large font scales, narrow screens), so no label wraps mid-word.
 */
class FitTabLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.tabStyle
) : TabLayout(context, attrs, defStyleAttr) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED && tabCount > 0) {
            val fixed = labelsFit(MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight)
            val mode = if (fixed) MODE_FIXED else MODE_SCROLLABLE
            if (tabMode != mode) {
                // Scrolling tabs cannot fill; they start at the edge.
                tabGravity = if (fixed) GRAVITY_FILL else GRAVITY_START
                tabMode = mode
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    /**
     * True when each tab, measured alone with its label on one line, fits an equal share of
     * [width]; a tab at its minimum width holds a short label, which fits either way.
     */
    private fun labelsFit(width: Int): Boolean {
        val share = width / tabCount
        val free = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        for (i in 0 until tabCount) {
            val view = getTabAt(i)?.view ?: continue
            view.measure(free, free)
            if (view.measuredWidth > share && view.measuredWidth > view.minimumWidth) return false
        }
        return true
    }
}
