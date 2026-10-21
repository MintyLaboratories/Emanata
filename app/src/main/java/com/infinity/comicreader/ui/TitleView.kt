package com.infinity.comicreader.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.View
import android.widget.TextView
import com.google.android.material.color.MaterialColors
import com.infinity.comicreader.R
import com.infinity.comicreader.data.TitleDisplay
import kotlin.math.ceil

/**
 * An entry's title as [mode] says, with an optional [detail] right-aligned at the
 * end of the title's last line in small gray tabular digits:
 * - single-line modes cut the title before the detail;
 * - [TitleDisplay.DOUBLE] wraps its second line around the detail ("…" just before
 *   it when full), or puts the detail on a line of its own when the title fills its
 *   only line;
 * - moving titles scroll in their own area, faded at the cut edges, while the
 *   detail stays put; only titles that overflow move, all off one [clock];
 * - a hidden title leaves the detail alone, right-aligned.
 * With [reserve] the view is always as tall as the mode's lines, so grid cells match;
 * an empty (not null) [detail] then holds a hidden title's line.
 */
class TitleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var text: CharSequence = ""
        set(value) {
            if (field == value) return
            field = value
            contentDescription = value
            relayout()
        }

    var mode = TitleDisplay.DOUBLE
        set(value) {
            if (field == value) return
            field = value
            relayout()
        }

    var detail: CharSequence? = null
        set(value) {
            if (field?.toString() == value?.toString()) return
            field = value
            relayout()
        }

    /** Keep the height of the mode's full line count, whatever the title needs. */
    var reserve = false
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    /** Drives moving titles; without one they hold still. */
    var clock: TitleClock? = null
        set(value) {
            if (field === value) return
            field?.remove(this)
            field = value
            updateMotion()
        }

    private val titlePaint: TextPaint
    private val detailPaint: TextPaint
    private val density = resources.displayMetrics.density
    private val gap = DETAIL_GAP_DP * density
    private val fade = FADE_DP * density
    private val fadePaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
    private var leftFade: LinearGradient? = null
    private var rightFade: LinearGradient? = null
    private var fadeFor = Float.NaN

    /** The title as laid out for static modes, or one unbroken line for moving ones. */
    private var layout: Layout? = null
    private var laidWidth = -1

    /** Where the title's area ends (the detail sits right of it) and whether the detail has its own line. */
    private var areaWidth = 0f
    private var detailOwnLine = false
    private var detailWidth = 0f

    /** For moving titles: the unbroken title width, how far it overflows, and its separator. */
    private var fullWidth = 0f
    private var overflow = 0f
    private var separatorWidth = 0f

    /** Milliseconds this title's glide takes; 0 when it fits. */
    var travelMs = 0L
        private set

    /** The scroll offset last drawn, NaN before any. */
    private var drawnOffset = Float.NaN

    init {
        val a = context.obtainStyledAttributes(attrs, ATTRS)
        val appearance = a.getResourceId(0, R.style.TextAppearance_ComicReader_ItemTitle)
        val size = a.getDimension(1, 0f)
        a.recycle()
        titlePaint = paintFrom(appearance).apply { if (size > 0f) textSize = size }
        detailPaint = paintFrom(R.style.TextAppearance_ComicReader_ItemSubtitle).apply {
            textSize = titlePaint.textSize * DETAIL_SCALE
            color = MaterialColors.getColor(this@TitleView, com.google.android.material.R.attr.colorOnSurfaceVariant)
            fontFeatureSettings = "tnum"
        }
        separatorWidth = titlePaint.measureText(TitleMotion.MARQUEE_SEPARATOR)
    }

    /** A paint set up as a text view with [appearance] would draw. */
    private fun paintFrom(appearance: Int): TextPaint {
        val probe = TextView(context)
        probe.setTextAppearance(appearance)
        return TextPaint(probe.paint).apply {
            isAntiAlias = true
            color = probe.currentTextColor
        }
    }

    private val lineHeight: Int get() = titlePaint.getFontMetricsInt(null)

    private val lines: Int get() = when (mode) {
        TitleDisplay.HIDDEN -> {
            val d = detail
            if (d == null || (d.isEmpty() && !reserve)) 0 else 1
        }
        TitleDisplay.DOUBLE -> 2
        else -> 1
    }

    private fun relayout() {
        laidWidth = -1
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        build(width - paddingLeft - paddingRight)
        val content = when {
            reserve || mode != TitleDisplay.DOUBLE -> lines * lineHeight
            detailOwnLine -> 2 * lineHeight
            else -> layout?.height ?: lineHeight
        }
        setMeasuredDimension(width, resolveSize(content + paddingTop + paddingBottom, heightMeasureSpec))
    }

    /** Lays the title out [width] px wide. */
    private fun build(width: Int) {
        if (width == laidWidth) return
        laidWidth = width
        val detailText = detail?.takeIf { it.isNotEmpty() }
        detailWidth = detailText?.let { detailPaint.measureText(it, 0, it.length) } ?: 0f
        val beside = if (detailText != null) detailWidth + gap else 0f
        areaWidth = (width - beside).coerceAtLeast(0f)
        detailOwnLine = false
        overflow = 0f
        travelMs = 0L
        val w = width.coerceAtLeast(0)
        layout = when (mode) {
            TitleDisplay.HIDDEN -> null
            TitleDisplay.SINGLE -> line(TextUtils.ellipsize(text, titlePaint, areaWidth, TextUtils.TruncateAt.END), w)
            TitleDisplay.MIDDLE -> line(TextUtils.ellipsize(text, titlePaint, areaWidth, TextUtils.TruncateAt.MIDDLE), w)
            TitleDisplay.DOUBLE -> double(w, beside)
            TitleDisplay.BOUNCE, TitleDisplay.MARQUEE -> {
                fullWidth = Layout.getDesiredWidth(text, titlePaint)
                overflow = (fullWidth - areaWidth).coerceAtLeast(0f)
                clock?.let { travelMs = TitleMotion.travelMs(overflow, it.speed) }
                line(text, ceil(fullWidth).toInt() + 1)
            }
        }
        updateMotion()
    }

    private fun line(text: CharSequence, width: Int): Layout =
        StaticLayout.Builder.obtain(text, 0, text.length, titlePaint, width.coerceAtLeast(1))
            .setMaxLines(1)
            .setIncludePad(false)
            .build()

    /** Two lines, the second shortened by [beside] for the detail; the detail gets a line of its own when needed. */
    private fun double(width: Int, beside: Float): Layout {
        val shortened = (width - beside).toInt().coerceAtLeast(1)
        if (Layout.getDesiredWidth(text, titlePaint) <= shortened) return line(text, width)
        val builder = StaticLayout.Builder.obtain(text, 0, text.length, titlePaint, width.coerceAtLeast(1))
            .setMaxLines(2)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setIncludePad(false)
        if (beside > 0f) builder.setIndents(null, intArrayOf(0, ceil(beside).toInt()))
        val laid = builder.build()
        // One full line leaves no room beside it: the detail goes below.
        if (laid.lineCount == 1 && beside > 0f) detailOwnLine = true
        return laid
    }

    private val moving: Boolean get() = mode.animated && overflow > 0f && clock != null

    private fun updateMotion() {
        val c = clock ?: return
        if (moving && isAttachedToWindow) c.add(this) else c.remove(this)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateMotion()
    }

    override fun onDetachedFromWindow() {
        clock?.remove(this)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val left = paddingLeft.toFloat()
        val top = paddingTop.toFloat()
        val right = (width - paddingRight).toFloat()
        val l = layout
        if (l != null) {
            if (moving) drawMoving(canvas, l, left, top) else drawStatic(canvas, l, left, top)
        }
        val d = detail?.takeIf { it.isNotEmpty() } ?: return
        val line = when {
            l == null -> 0
            detailOwnLine -> 1
            mode == TitleDisplay.DOUBLE -> l.lineCount - 1
            else -> 0
        }
        val baseline = top + baselineOf(l, line)
        canvas.drawText(d, 0, d.length, right - detailWidth, baseline, detailPaint)
    }

    /** Baseline of [line], counted from the top of the content. */
    private fun baselineOf(l: Layout?, line: Int): Float {
        if (l != null && line < l.lineCount) return l.getLineBaseline(line).toFloat()
        val fm = titlePaint.fontMetricsInt
        return (line * lineHeight - fm.ascent).toFloat()
    }

    private fun drawStatic(canvas: Canvas, l: Layout, left: Float, top: Float) {
        canvas.save()
        canvas.translate(left, top)
        l.draw(canvas)
        canvas.restore()
    }

    /** How far the moving title is scrolled now by [c]. */
    private fun offsetAt(c: TitleClock): Float =
        if (mode == TitleDisplay.MARQUEE) {
            TitleMotion.marqueeOffset(c.elapsed, c.speed, fullWidth + separatorWidth)
        } else {
            TitleMotion.bounceOffset(c.local, overflow, c.speed, c.cycleTravel)
        }

    /** Redraws when the clock moved the title since it was last drawn. */
    fun tick() {
        val c = clock ?: return
        if (moving && offsetAt(c) != drawnOffset) invalidate()
    }

    /** Milliseconds until the clock next moves this title: 0 while it moves. */
    fun stillMs(): Long {
        val c = clock ?: return Long.MAX_VALUE
        return when {
            !moving -> Long.MAX_VALUE
            mode == TitleDisplay.MARQUEE -> 0L
            else -> TitleMotion.bounceStillMs(c.local, travelMs, c.cycleTravel)
        }
    }

    private fun drawMoving(canvas: Canvas, l: Layout, left: Float, top: Float) {
        val c = clock ?: return
        val marquee = mode == TitleDisplay.MARQUEE
        val loop = fullWidth + separatorWidth
        val offset = offsetAt(c)
        drawnOffset = offset
        val right = left + areaWidth
        val bottom = top + lineHeight
        val save = canvas.saveLayer(left, top, right, bottom, null)
        canvas.clipRect(left, top, right, bottom)
        canvas.save()
        canvas.translate(left - offset, top)
        l.draw(canvas)
        if (marquee) {
            canvas.drawText(TitleMotion.MARQUEE_SEPARATOR, fullWidth, l.getLineBaseline(0).toFloat(), titlePaint)
            canvas.translate(loop, 0f)
            l.draw(canvas)
        }
        canvas.restore()
        fades(left, right)
        if (marquee || offset > 0f) {
            fadePaint.shader = leftFade
            canvas.drawRect(left, top, left + fade, bottom, fadePaint)
        }
        if (marquee || offset < overflow) {
            fadePaint.shader = rightFade
            canvas.drawRect(right - fade, top, right, bottom, fadePaint)
        }
        canvas.restoreToCount(save)
    }

    /** Edge fades for an area from [left] to [right]: opaque at the edge, clear inside. */
    private fun fades(left: Float, right: Float) {
        if (fadeFor == right - left && leftFade != null) return
        fadeFor = right - left
        leftFade = LinearGradient(left, 0f, left + fade, 0f, Color.BLACK, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        rightFade = LinearGradient(right - fade, 0f, right, 0f, Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)
    }

    private companion object {
        /** `android:textAppearance` and `android:textSize`, in the ascending order styled attributes need. */
        val ATTRS = intArrayOf(android.R.attr.textAppearance, android.R.attr.textSize)
        const val DETAIL_SCALE = 0.85f
        const val DETAIL_GAP_DP = 6f
        const val FADE_DP = 12f
    }
}
