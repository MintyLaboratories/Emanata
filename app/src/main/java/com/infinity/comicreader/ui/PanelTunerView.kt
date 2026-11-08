package com.infinity.comicreader.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * A comic page whose gutters slide out and back one at a time ([PanelPage], [GutterMotion]),
 * drawn square in [PrintStyle]: each panel a flat fill with halftone dots, black borders,
 * paper gutters. Each panel's dots are drawn once per size into an alpha mask fixed to the
 * page, covering every place the panel reaches; a frame only clips the masks by the panels'
 * quads. At most [FPS] frames a second, none while hidden ([onVisibilityAggregated]), and a
 * still page where animations are off (reduced motion) or while [held].
 */
class PanelTunerView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var page: PanelPage = PanelPage.TUNER
        set(value) {
            field = value
            motion = GutterMotion(value.gutters.size)
            dropMasks()
            invalidate()
        }

    var style: PrintStyle = PrintStyle.FOUR_COLOR
        set(value) {
            field = value
            dropMasks()
            invalidate()
        }

    private var motion = GutterMotion(page.gutters.size)

    /** Holds the page still where it is; it moves on from there once let go. */
    var held: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            pace()
        }

    /** True while frames are being made ([FPS]). */
    val playing: Boolean get() = clockedFrom >= 0

    private val reduced: Boolean get() = !ValueAnimator.areAnimatorsEnabled()

    private var visible = false

    /** Seconds of motion before the clock last started; when it started (ms, -1 while stopped). */
    private var playedS = 0.0
    private var clockedFrom = -1L

    private var masks: Array<Bitmap?> = emptyArray()
    private var maskLeft = FloatArray(0)
    private var maskTop = FloatArray(0)
    private var maskSize = 0

    private val path = Path()
    private val fill = Paint()
    private val dots = Paint().apply { isFilterBitmap = false }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.MITER
    }

    private val tick = Runnable { invalidate() }

    /** Seconds of motion so far. */
    private fun seconds(): Double = playedS + if (clockedFrom >= 0) (SystemClock.uptimeMillis() - clockedFrom) / 1000.0 else 0.0

    /** Starts or stops the clock as visibility, reduced motion and [held] say. */
    private fun pace() {
        val play = visible && !held && !reduced
        if (play == playing) return
        if (play) {
            clockedFrom = SystemClock.uptimeMillis()
            invalidate()
        } else {
            playedS = seconds()
            clockedFrom = -1
            removeCallbacks(tick)
        }
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        visible = isVisible
        pace()
    }

    override fun onDetachedFromWindow() {
        visible = false
        pace()
        dropMasks()
        super.onDetachedFromWindow()
    }

    /** Square: the smaller of the sizes offered. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val side = when {
            MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED -> h
            MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED -> w
            else -> min(w, h)
        }
        setMeasuredDimension(side, side)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        dropMasks()
    }

    private fun dropMasks() {
        masks.forEach { it?.recycle() }
        masks = emptyArray()
        maskSize = 0
    }

    /** Each panel's dots in an alpha mask covering where it reaches ([PanelPage.reach]), for a page [size] pixels square. */
    private fun makeMasks(size: Int) {
        val reach = page.reach()
        val centers = page.centers()
        val pad = border.strokeWidth
        masks = arrayOfNulls(page.panels.size)
        maskLeft = FloatArray(page.panels.size)
        maskTop = FloatArray(page.panels.size)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        for (i in page.panels.indices) {
            val ink = style.panels.getOrNull(i) ?: continue
            val r = reach[i]
            val left = max(0f, floor(r[0] * size - pad).toFloat())
            val top = max(0f, floor(r[1] * size - pad).toFloat())
            val right = min(size.toFloat(), ceil(r[2] * size + pad).toFloat())
            val bottom = min(size.toFloat(), ceil(r[3] * size + pad).toFloat())
            if (right <= left || bottom <= top) continue
            val mask = Bitmap.createBitmap((right - left).toInt(), (bottom - top).toInt(), Bitmap.Config.ALPHA_8)
            val c = Canvas(mask)
            c.translate(-left, -top)
            for (d in Halftone.dots(size, ink, centers[i].x, centers[i].y, floatArrayOf(left, top, right, bottom))) c.drawCircle(d.x, d.y, d.r, paint)
            masks[i] = mask
            maskLeft[i] = left
            maskTop[i] = top
        }
        maskSize = size
    }

    override fun onDraw(canvas: Canvas) {
        val size = min(width - paddingLeft - paddingRight, height - paddingTop - paddingBottom)
        if (size <= 0) return
        val started = SystemClock.uptimeMillis()
        border.strokeWidth = max(2f, size * 0.011f)
        if (maskSize != size) {
            dropMasks()
            makeMasks(size)
        }
        canvas.save()
        canvas.translate(paddingLeft + (width - paddingLeft - paddingRight - size) / 2f, paddingTop + (height - paddingTop - paddingBottom - size) / 2f)
        fill.color = style.paper
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), fill)
        border.color = style.ink
        val quads = page.quads(motion.offsets(page, seconds()))
        for ((i, q) in quads.withIndex()) {
            path.rewind()
            q.forEachIndexed { j, p -> if (j == 0) path.moveTo((p.x * size).toFloat(), (p.y * size).toFloat()) else path.lineTo((p.x * size).toFloat(), (p.y * size).toFloat()) }
            path.close()
            val ink = style.panels.getOrNull(i)
            if (ink != null) {
                canvas.save()
                canvas.clipPath(path)
                fill.color = ink.fill
                canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), fill)
                masks.getOrNull(i)?.let {
                    dots.color = ink.dot
                    canvas.drawBitmap(it, maskLeft[i], maskTop[i], dots)
                }
                canvas.restore()
            }
            canvas.drawPath(path, border)
        }
        canvas.restore()
        if (playing) {
            // One frame waiting at a time, whatever else made this one.
            removeCallbacks(tick)
            postOnAnimationDelayed(tick, max(0L, FRAME_MS - (SystemClock.uptimeMillis() - started)))
        }
    }

    companion object {
        /** The frame cap: a little choppy, light beside calibration's timings. */
        const val FPS = 20
        private const val FRAME_MS = 1000L / FPS
    }
}
