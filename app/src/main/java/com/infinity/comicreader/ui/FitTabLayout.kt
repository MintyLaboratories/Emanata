package com.infinity.comicreader.ui

import android.content.Context
import android.util.AttributeSet
import com.google.android.material.tabs.TabLayout
import kotlin.math.roundToInt

/**
 * Tabs that share the width equally while every label fits its share on one line, first
 * with the styled side padding, then with less, down to [MIN_PAD_DP] a side; past that they
 * scroll, at the styled padding. A label's font never shrinks: the two-line text size
 * Material switches to is the one-line size (`design_tab_text_size_2line`).
 */
class FitTabLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.tabStyle
) : TabLayout(context, attrs, defStyleAttr) {

    /** Each tab's side padding as styled; read from the first tab. */
    private var styledPad = -1

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED && tabCount > 0) {
            val pad = fitPadding(MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight)
            val fixed = pad != null
            setTabPadding(pad ?: styledPad)
            val mode = if (fixed) MODE_FIXED else MODE_SCROLLABLE
            if (tabMode != mode) {
                // Scrolling tabs cannot fill; they start at the edge.
                tabGravity = if (fixed) GRAVITY_FILL else GRAVITY_START
                tabMode = mode
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    /** The side padding at which every tab's label fits an equal share of [width] on one line; null when none down to the least does. */
    private fun fitPadding(width: Int): Int? {
        val tabs = (0 until tabCount).mapNotNull { getTabAt(it)?.view }
        if (styledPad < 0) styledPad = tabs.firstOrNull()?.paddingStart ?: 0
        val free = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        val contents = tabs.map { view ->
            view.measure(free, free)
            view.measuredWidth - view.paddingStart - view.paddingEnd
        }
        val least = (MIN_PAD_DP * resources.displayMetrics.density).roundToInt()
        return TabPadding.pick(contents, tabs.map { it.minimumWidth }, width / tabCount, styledPad, least)
    }

    private fun setTabPadding(pad: Int) {
        for (i in 0 until tabCount) {
            val view = getTabAt(i)?.view ?: continue
            if (view.paddingStart != pad || view.paddingEnd != pad) view.setPaddingRelative(pad, view.paddingTop, pad, view.paddingBottom)
        }
    }

    /** The tabs' side paddings now; for tests. */
    internal val tabPaddings: List<Int> get() = (0 until tabCount).mapNotNull { getTabAt(it)?.view?.paddingStart }

    companion object {
        /** The least side padding a tab keeps before the tabs scroll. */
        const val MIN_PAD_DP = 4f
    }
}

/** How much side padding equal-width tabs can keep; free of Android types. */
internal object TabPadding {

    /**
     * The largest side padding from [start] down to [least], a pixel at a time, at which each
     * tab of label width [contents] fits a [share] of the width, or stays within its
     * [minimums] width (a short label fits either way); null when even [least] is too much.
     */
    fun pick(contents: List<Int>, minimums: List<Int>, share: Int, start: Int, least: Int): Int? {
        for (pad in start downTo minOf(least, start)) {
            if (contents.indices.all { contents[it] + 2 * pad <= share || contents[it] + 2 * pad <= minimums[it] }) return pad
        }
        return null
    }
}
