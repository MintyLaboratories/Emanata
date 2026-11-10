package com.infinity.comicreader.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.util.AttributeSet
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.RangeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import com.google.android.material.color.MaterialColors
import com.infinity.comicreader.R
import com.infinity.comicreader.ai.AiThresholds
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The AI size setting's slider: one axis ([AiSizeScale]) with two independent handles that may
 * cross. The upscale handle hangs above the track and stops at [AiThresholds.UPSCALE_FLOOR],
 * which a wall and a dashed lane left of it show; the cleanup handle stands below the track and
 * goes down to "Always". Each lane is filled from its handle rightward, where its models run.
 * A touch above the track's middle moves the upscale handle, below it the cleanup handle.
 */
class AiSizeSlider @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    enum class Handle { UPSCALE, CLEANUP }

    private val access = Access()

    /** The handles' values, as set or as the user left them. */
    var thresholds = AiThresholds()
        set(v) {
            if (field == v) return
            field = v
            invalidate()
            access.invalidateRoot()
        }

    /** Hears each change by the user; [done] once a drag ends, or on a key or accessibility step. */
    var onChange: ((thresholds: AiThresholds, done: Boolean) -> Unit)? = null

    val upscaleColor = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, Color.BLUE)
    val cleanupColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorTertiary, Color.MAGENTA)
    private val onUpscale = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnPrimary, Color.WHITE)
    private val onCleanup = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnTertiary, Color.WHITE)
    private val trackColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceVariant, Color.LTGRAY)
    private val labelColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.DKGRAY)

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private fun Float.sp() = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, this, resources.displayMetrics)

    private val pointer = dp(6f)
    private val band = dp(4f)
    private val labelGap = dp(6f)
    private val padX = dp(8f)

    /** Room around a bubble for the key focus ring: its gap plus half its stroke. */
    private val ring = dp(RING_GAP_DP + RING_STROKE_DP / 2)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val focusRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(RING_STROKE_DP)
    }
    private val dashed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        pathEffect = DashPathEffect(floatArrayOf(dp(3f), dp(3f)), 0f)
    }
    private val bubbleText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TEXT_SP.sp()
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TEXT_SP.sp()
        textAlign = Paint.Align.CENTER
        color = labelColor
    }
    private val rect = RectF()
    private val path = Path()

    /** A bubble's height: its text's line plus padding, at least [BUBBLE_DP] at any font scale. */
    internal val bubbleH: Float = bubbleText.fontMetrics.let { maxOf(dp(BUBBLE_DP), it.descent - it.ascent + 2 * dp(BUBBLE_PAD_DP)) }

    /** Room each side of the axis for a bubble or label centered at its end. */
    private val inset: Float by lazy {
        AiSizeScale.LABELS.maxOf { bubbleText.measureText(AiSizeText.value(context, it)) } / 2 + padX + ring
    }

    /** The axis labels drawn at this width: from the left, each one too close to the last drawn is skipped. */
    private var labels = AiSizeScale.LABELS

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        labels = shownLabels()
    }

    /** Where [pct]'s axis label spans, left to right. */
    internal fun labelSpan(pct: Int): ClosedFloatingPointRange<Float> {
        val half = labelText.measureText(AiSizeText.value(context, pct)) / 2
        return (xOf(pct) - half)..(xOf(pct) + half)
    }

    /** The axis labels that fit side by side at the current width, from the left. */
    internal fun shownLabels(): List<Int> {
        val out = ArrayList<Int>(AiSizeScale.LABELS.size)
        var right = Float.NEGATIVE_INFINITY
        for (pct in AiSizeScale.LABELS) {
            val span = labelSpan(pct)
            if (span.start < right + labelGap) continue
            out += pct
            right = span.endInclusive
        }
        return out
    }

    private val trackTop get() = paddingTop + ring + bubbleH + pointer
    private val trackMid get() = trackTop + band
    private val trackBottom get() = trackMid + band
    private val labelsTop get() = trackBottom + pointer + bubbleH + labelGap

    private fun axisStart() = paddingLeft + inset
    private fun axisEnd() = width - paddingRight - inset
    private fun xOf(pct: Int) = axisStart() + AiSizeScale.fraction(pct) * (axisEnd() - axisStart())
    private fun fractionAt(x: Float) = (x - axisStart()) / (axisEnd() - axisStart()).coerceAtLeast(1f)

    private fun pct(h: Handle) = if (h == Handle.UPSCALE) thresholds.upscalePct else thresholds.cleanupPct
    private fun stops(h: Handle) = if (h == Handle.UPSCALE) AiSizeScale.UPSCALE_STOPS else AiSizeScale.CLEANUP_STOPS

    init {
        isFocusable = true
        ViewCompat.setAccessibilityDelegate(this, access)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val fm = labelText.fontMetrics
        val h = labelsTop + (fm.descent - fm.ascent) + paddingBottom
        setMeasuredDimension(
            getDefaultSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(h.toInt() + 1, heightMeasureSpec)
        )
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else DISABLED_ALPHA
    }

    /** [color] as this slider shows it: dimmed like the slider while it is off. */
    fun shown(color: Int): Int = if (isEnabled) color else ColorUtils.setAlphaComponent(color, (Color.alpha(color) * DISABLED_ALPHA).roundToInt())

    override fun onDraw(canvas: Canvas) {
        val start = axisStart()
        val end = axisEnd()
        val floorX = xOf(AiThresholds.UPSCALE_FLOOR)
        val upX = xOf(thresholds.upscalePct)
        val cleanX = xOf(thresholds.cleanupPct)

        // Upscale lane: dashed left of the floor, a wall at it, filled from the handle.
        dashed.color = trackColor
        val laneY = trackTop + band / 2
        canvas.drawLine(start, laneY, floorX, laneY, dashed)
        fill.color = trackColor
        rect.set(floorX, trackTop, end, trackMid)
        canvas.drawRoundRect(rect, band / 2, band / 2, fill)
        fill.color = upscaleColor
        rect.set(upX, trackTop, end, trackMid)
        canvas.drawRoundRect(rect, band / 2, band / 2, fill)
        rect.set(floorX - dp(1.5f), trackTop - pointer - bubbleH / 2, floorX + dp(1.5f), trackMid)
        canvas.drawRoundRect(rect, dp(1.5f), dp(1.5f), fill)

        // Cleanup lane: the whole axis, filled from the handle.
        fill.color = trackColor
        rect.set(start, trackMid, end, trackBottom)
        canvas.drawRoundRect(rect, band / 2, band / 2, fill)
        fill.color = cleanupColor
        rect.set(cleanX, trackMid, end, trackBottom)
        canvas.drawRoundRect(rect, band / 2, band / 2, fill)

        bubble(canvas, Handle.UPSCALE, upX)
        bubble(canvas, Handle.CLEANUP, cleanX)
        focusedHandle()?.let { h ->
            bubbleBounds(h, rect)
            rect.inset(-dp(RING_GAP_DP), -dp(RING_GAP_DP))
            focusRing.color = if (h == Handle.UPSCALE) upscaleColor else cleanupColor
            canvas.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, focusRing)
        }

        val fm = labelText.fontMetrics
        val baseline = labelsTop - fm.ascent
        for (pct in labels) canvas.drawText(AiSizeText.value(context, pct), xOf(pct), baseline, labelText)
    }

    /** [h]'s handle at [x]: a bubble with its value, pointing at the track from its side. */
    private fun bubble(canvas: Canvas, h: Handle, x: Float) {
        val up = h == Handle.UPSCALE
        bubbleBounds(h, rect)
        fill.color = if (up) upscaleColor else cleanupColor
        canvas.drawRoundRect(rect, bubbleH / 2, bubbleH / 2, fill)
        path.reset()
        val base = if (up) rect.bottom else rect.top
        val tip = if (up) trackTop else trackBottom
        path.moveTo(x - pointer, base)
        path.lineTo(x + pointer, base)
        path.lineTo(x, tip)
        path.close()
        canvas.drawPath(path, fill)
        bubbleText.color = if (up) onUpscale else onCleanup
        val fm = bubbleText.fontMetrics
        canvas.drawText(AiSizeText.value(context, pct(h)), rect.centerX(), rect.centerY() - (fm.ascent + fm.descent) / 2, bubbleText)
    }

    /** Where [h]'s bubble is drawn. */
    private fun bubbleBounds(h: Handle, out: RectF) {
        val x = xOf(pct(h))
        val half = bubbleText.measureText(AiSizeText.value(context, pct(h))) / 2 + padX
        val top = if (h == Handle.UPSCALE) paddingTop + ring else trackBottom + pointer
        out.set(x - half, top, x + half, top + bubbleH)
    }

    // ---------------------------------------------------------------- input

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var dragged: Handle? = null
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var atDown = thresholds

    /** The handle a touch at height [y] moves. */
    fun handleAt(y: Float): Handle = if (y < trackMid) Handle.UPSCALE else Handle.CLEANUP

    /** Thresholds with [h] moved to [pct]. */
    private fun moved(h: Handle, pct: Int) =
        if (h == Handle.UPSCALE) thresholds.copy(upscalePct = pct) else thresholds.copy(cleanupPct = pct)

    private fun moveTo(h: Handle, x: Float) {
        val f = fractionAt(x)
        val pct = if (h == Handle.UPSCALE) AiSizeScale.upscaleAt(f) else AiSizeScale.cleanupAt(f)
        if (pct == pct(h)) return
        thresholds = moved(h, pct)
        onChange?.invoke(thresholds, false)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragged = handleAt(event.y)
                downX = event.x
                downY = event.y
                dragging = false
                atDown = thresholds
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val h = dragged ?: return false
                // In the scrolling settings list a drag starts once it is more sideways than not.
                if (!dragging && abs(event.x - downX) > touchSlop && abs(event.x - downX) > abs(event.y - downY)) {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (dragging) moveTo(h, event.x)
            }
            MotionEvent.ACTION_UP -> {
                val h = dragged ?: return false
                moveTo(h, event.x)
                end()
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> end()
        }
        return true
    }

    private fun end() {
        dragged = null
        dragging = false
        parent?.requestDisallowInterceptTouchEvent(false)
        if (thresholds != atDown) onChange?.invoke(thresholds, true)
        access.invalidateRoot()
    }

    override fun performClick(): Boolean = super.performClick()

    /** Moves [h] by [by] stops; true when it moved. */
    fun step(h: Handle, by: Int): Boolean {
        val pct = AiSizeScale.step(stops(h), pct(h), by)
        if (pct == pct(h)) return false
        thresholds = moved(h, pct)
        onChange?.invoke(thresholds, true)
        return true
    }

    /** The handle ringed as the one keys move while the view has key focus, else null. */
    internal fun focusedHandle(): Handle? = if (isFocused) keyHandle() else null

    /** The handle keys move: the one accessibility focus is on, else upscale. */
    private fun keyHandle(): Handle =
        if (access.keyboardFocusedVirtualViewId == Handle.CLEANUP.ordinal) Handle.CLEANUP else Handle.UPSCALE

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isEnabled) {
            val by = when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> -1
                KeyEvent.KEYCODE_DPAD_RIGHT -> 1
                else -> 0
            }
            if (by != 0) {
                step(keyHandle(), by)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean = access.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    /** Up and down move key focus between the handles; left and right step the focused one ([onKeyDown]). */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val sideways = event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
        return (!sideways && access.dispatchKeyEvent(event)) || super.dispatchKeyEvent(event)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        access.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        invalidate()
    }

    // ---------------------------------------------------------------- accessibility

    /** [h]'s handle's name: "Upscale" or "Clean up". */
    fun name(h: Handle): String =
        context.getString(if (h == Handle.UPSCALE) R.string.ai_sizes_upscale_name else R.string.ai_sizes_cleanup_name)

    /** [h]'s handle's value in words: "Above 100%", or "Always". */
    fun state(h: Handle): String =
        if (pct(h) == AiThresholds.ALWAYS) AiSizeText.value(context, pct(h))
        else context.getString(R.string.ai_sizes_above, AiSizeText.value(context, pct(h)))

    /** Each handle as its own slider for accessibility services, moved a stop per scroll action. */
    private inner class Access : ExploreByTouchHelper(this@AiSizeSlider) {
        private val bounds = RectF()

        override fun onVirtualViewKeyboardFocusChanged(virtualViewId: Int, hasFocus: Boolean) = invalidate()

        override fun getVirtualViewAt(x: Float, y: Float): Int = handleAt(y).ordinal

        override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>) {
            for (h in Handle.entries) virtualViewIds.add(h.ordinal)
        }

        @Suppress("DEPRECATION")
        override fun onPopulateNodeForVirtualView(virtualViewId: Int, node: AccessibilityNodeInfoCompat) {
            val h = Handle.entries[virtualViewId]
            val stops = stops(h)
            node.className = "android.widget.SeekBar"
            // The name, then the value: services read stateDescription in place of a position within the range.
            node.contentDescription = name(h)
            node.stateDescription = state(h)
            node.rangeInfo = RangeInfoCompat.obtain(
                RangeInfoCompat.RANGE_TYPE_INT, stops.first().toFloat(), stops.last().toFloat(), pct(h).toFloat()
            )
            node.isEnabled = isEnabled
            node.isFocusable = true
            if (isEnabled) {
                if (pct(h) != stops.last()) node.addAction(AccessibilityActionCompat.ACTION_SCROLL_FORWARD)
                if (pct(h) != stops.first()) node.addAction(AccessibilityActionCompat.ACTION_SCROLL_BACKWARD)
            }
            bubbleBounds(h, bounds)
            node.setBoundsInParent(Rect(bounds.left.toInt(), bounds.top.toInt(), bounds.right.toInt(), bounds.bottom.toInt()))
        }

        override fun onPerformActionForVirtualView(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
            val h = Handle.entries[virtualViewId]
            val by = when (action) {
                AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD -> 1
                AccessibilityNodeInfoCompat.ACTION_SCROLL_BACKWARD -> -1
                else -> return false
            }
            if (!isEnabled || !step(h, by)) return false
            invalidateVirtualView(virtualViewId)
            return true
        }
    }

    private companion object {
        const val DISABLED_ALPHA = 0.38f
        const val TEXT_SP = 12f
        const val BUBBLE_DP = 24f
        const val BUBBLE_PAD_DP = 4f
        const val RING_GAP_DP = 3f
        const val RING_STROKE_DP = 2f
    }
}
