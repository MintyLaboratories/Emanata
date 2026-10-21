package com.infinity.comicreader.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * A grid cell's cover area. Given a height it keeps it; with a free height it
 * takes the height [ratio] (width / height) asks for its width, or [freeHeight]
 * when [ratio] is 0.
 */
class CoverFrame @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    var ratio = 0f
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    var freeHeight = 0
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = CoverGeometry.freeHeight(width, ratio, freeHeight)
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        )
    }
}

/** The cover box: the largest box of [ratio] (width / height) that fits the space given, or all of it when [ratio] is 0. */
class CoverBox @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    var ratio = 0f
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val (w, h) = CoverGeometry.box(
            MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec), ratio
        ).let { it[0] to it[1] }
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
        )
    }
}
