package com.infinity.comicreader.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.google.android.material.color.MaterialColors
import com.infinity.comicreader.R
import com.infinity.comicreader.ai.AiLoad

/**
 * A chain's load estimate ([AiLoad]): a rounded bar filled to [pageMs]'s share of
 * [AiLoad.FULL_MS], green, yellow or red by its [AiLoad.Level], with no unit or numbers;
 * TalkBack reads the level in words. Dimmed while disabled.
 */
class AiLoadBar @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    /** The chain's page time the bar shows. */
    var pageMs: Long = 0
        set(v) {
            val ms = v.coerceAtLeast(0)
            if (ms == field && contentDescription != null) return
            field = ms
            contentDescription = describe(context, ms)
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val track = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceContainerHighest, 0x33808080)

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        contentDescription = describe(context, 0)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val height = resources.getDimensionPixelSize(R.dimen.ai_load_bar_height)
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), resolveSize(height, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val w = (width - paddingLeft - paddingRight).toFloat()
        val h = (height - paddingTop - paddingBottom).toFloat()
        if (w <= 0 || h <= 0) return
        val r = h / 2
        paint.alpha = 255
        paint.color = track
        rect.set(paddingLeft.toFloat(), paddingTop.toFloat(), paddingLeft + w, paddingTop + h)
        canvas.drawRoundRect(rect, r, r, paint)
        val fraction = AiLoad.fraction(pageMs)
        if (fraction <= 0f) return
        // At least a dot, so a light chain still shows.
        val fill = maxOf(h, w * fraction)
        paint.color = ContextCompat.getColor(context, colorOf(AiLoad.level(pageMs)))
        if (!isEnabled) paint.alpha = DISABLED_ALPHA
        // Drawn from the start side, as text reads.
        if (layoutDirection == LAYOUT_DIRECTION_RTL) rect.set(paddingLeft + w - fill, paddingTop.toFloat(), paddingLeft + w, paddingTop + h)
        else rect.set(paddingLeft.toFloat(), paddingTop.toFloat(), paddingLeft + fill, paddingTop + h)
        canvas.drawRoundRect(rect, r, r, paint)
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        invalidate()
    }

    companion object {
        private const val DISABLED_ALPHA = 97

        /** The color of [level]'s fill. */
        fun colorOf(level: AiLoad.Level): Int = when (level) {
            AiLoad.Level.LIGHT -> R.color.ai_load_light
            AiLoad.Level.MODERATE -> R.color.ai_load_moderate
            AiLoad.Level.HEAVY -> R.color.ai_load_heavy
        }

        /** What TalkBack reads for a chain of [ms]: its level in plain words. */
        fun describe(context: Context, ms: Long): String = context.getString(
            R.string.ai_load_reading,
            context.getString(
                when (AiLoad.level(ms)) {
                    AiLoad.Level.LIGHT -> R.string.ai_load_light
                    AiLoad.Level.MODERATE -> R.string.ai_load_moderate
                    AiLoad.Level.HEAVY -> R.string.ai_load_heavy
                }
            )
        )
    }
}
