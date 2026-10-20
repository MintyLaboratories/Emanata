package com.infinity.comicreader.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.AttributeSet
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Image view with pinch-to-zoom, panning, double-tap zoom and configurable fit modes.
 * Uses [ScaleType.MATRIX] and manipulates the image matrix directly; at 1:1 the
 * bitmap is drawn unfiltered on whole pixels ([applyMatrix]); at any other scale
 * through Lanczos3 on the GPU ([LanczosShader], API 33+), or bilinear below that.
 *
 * [onTap] receives the tap position as a fraction of the view width so callers can
 * implement left/centre/right tap zones. [onVerticalDrag] receives the vertical
 * delta of a drag while the image is not zoomed (used for the brightness gesture).
 */
class ZoomImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : AppCompatImageView(context, attrs, defStyle) {

    enum class FitMode { SCREEN, WIDTH, HEIGHT, STRETCH }

    private val matrix = Matrix()
    private val drawMatrix = Matrix()
    private val baseMatrix = Matrix()
    private var fitMode = FitMode.SCREEN
    private var minScale = 1f
    private var currentScale = 1f
    private var maxScale = 8f
    private var lastX = 0f
    private var lastY = 0f
    private val values = FloatArray(9)

    var onTap: ((Float) -> Unit)? = null
    var onVerticalDrag: ((Float) -> Unit)? = null
    var doubleTapEnabled = true

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (drawable == null) return false
                val newScale = (currentScale * detector.scaleFactor).coerceIn(minScale, maxScale)
                val factor = newScale / currentScale
                currentScale = newScale
                matrix.postScale(factor, factor, detector.focusX, detector.focusY)
                clamp()
                applyMatrix()
                return true
            }
        }
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                performClick()
                val fraction = if (width > 0) e.x / width.toFloat() else 0.5f
                onTap?.invoke(fraction)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (!doubleTapEnabled) return false
                if (currentScale > minScale * 1.1f) {
                    fit(fitMode)
                } else {
                    zoomTo(minScale * 3f, e.x, e.y)
                }
                return true
            }
        }
    )

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        post { fit(fitMode) }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fit(fitMode)
    }

    fun setFitMode(mode: FitMode) {
        fitMode = mode
        fit(mode)
    }

    fun fit(mode: FitMode = fitMode) {
        val drawable = drawable ?: return
        val viewW = width.toFloat()
        val viewH = height.toFloat()
        if (viewW <= 0f || viewH <= 0f) return
        val drawableW = drawable.intrinsicWidth.toFloat()
        val drawableH = drawable.intrinsicHeight.toFloat()
        if (drawableW <= 0f || drawableH <= 0f) return

        if (mode == FitMode.STRETCH) {
            baseMatrix.reset()
            baseMatrix.postScale(viewW / drawableW, viewH / drawableH)
            matrix.set(baseMatrix)
            currentScale = 1f
            minScale = 1f
            maxScale = 1f
            applyMatrix()
            return
        }

        val scale = when (mode) {
            FitMode.WIDTH -> viewW / drawableW
            FitMode.HEIGHT -> viewH / drawableH
            else -> minOf(viewW / drawableW, viewH / drawableH)
        }
        baseMatrix.reset()
        baseMatrix.postScale(scale, scale)
        val scaledW = drawableW * scale
        val scaledH = drawableH * scale
        // Centre what fits; when a page is taller (or wider) than the view start at its
        // top (left) edge so reading begins at the beginning and the rest can be panned to.
        // Whole-pixel offsets, so a page at 1:1 lands on the pixel grid.
        val startX = if (scaledW > viewW) 0f else ((viewW - scaledW) / 2f).roundToInt().toFloat()
        val startY = if (scaledH > viewH) 0f else ((viewH - scaledH) / 2f).roundToInt().toFloat()
        baseMatrix.postTranslate(startX, startY)
        matrix.set(baseMatrix)
        currentScale = scale
        minScale = scale
        maxScale = scale * 8f
        applyMatrix()
    }

    internal fun zoomTo(target: Float, focusX: Float, focusY: Float) {
        val clamped = target.coerceIn(minScale, maxScale)
        val factor = clamped / currentScale
        currentScale = clamped
        matrix.postScale(factor, factor, focusX, focusY)
        clamp()
        applyMatrix()
    }

    private fun clamp() {
        matrix.getValues(values)
        val transX = values[Matrix.MTRANS_X]
        val transY = values[Matrix.MTRANS_Y]
        val scaleX = values[Matrix.MSCALE_X]
        val scaleY = values[Matrix.MSCALE_Y]
        val drawable = drawable ?: return
        val drawableW = drawable.intrinsicWidth * scaleX
        val drawableH = drawable.intrinsicHeight * scaleY
        val viewW = width.toFloat()
        val viewH = height.toFloat()

        var dx = 0f
        if (drawableW <= viewW) {
            dx = (viewW - drawableW) / 2f - transX
        } else {
            val minX = viewW - drawableW
            if (transX > 0f) dx = -transX else if (transX < minX) dx = minX - transX
        }

        var dy = 0f
        if (drawableH <= viewH) {
            dy = (viewH - drawableH) / 2f - transY
        } else {
            val minY = viewH - drawableH
            if (transY > 0f) dy = -transY else if (transY < minY) dy = minY - transY
        }
        matrix.postTranslate(dx, dy)
    }

    /**
     * Shows [matrix]. At 1:1 a copy snapped to whole pixels is drawn, unfiltered, so a
     * page decoded at its on-screen size reaches the screen pixel for pixel while
     * [matrix] keeps the exact pan; any other scale draws filtered.
     */
    private fun applyMatrix() {
        matrix.getValues(values)
        val oneToOne = abs(values[Matrix.MSCALE_X] - 1f) < ONE_TO_ONE && abs(values[Matrix.MSCALE_Y] - 1f) < ONE_TO_ONE &&
            values[Matrix.MSKEW_X] == 0f && values[Matrix.MSKEW_Y] == 0f
        if (oneToOne) {
            values[Matrix.MSCALE_X] = 1f
            values[Matrix.MSCALE_Y] = 1f
            values[Matrix.MTRANS_X] = values[Matrix.MTRANS_X].roundToInt().toFloat()
            values[Matrix.MTRANS_Y] = values[Matrix.MTRANS_Y].roundToInt().toFloat()
            drawMatrix.setValues(values)
        } else {
            drawMatrix.set(matrix)
        }
        (drawable as? BitmapDrawable)?.isFilterBitmap = !oneToOne
        imageMatrix = drawMatrix
    }

    /** True when the content is drawn at 1:1 without filtering; for tests. */
    internal val drawsOneToOne: Boolean
        get() = (drawable as? BitmapDrawable)?.isFilterBitmap == false

    /** The bitmap to draw through [LanczosShader]: a scaled one, where the GPU filter supports it. */
    internal val shadedBitmap: Bitmap?
        get() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || shaderFailed) return null
            val d = drawable as? BitmapDrawable ?: return null
            if (!d.isFilterBitmap) return null
            return d.bitmap?.takeUnless { it.isRecycled }
        }

    private var lanczos: LanczosShader? = null
    private var shaderFailed = false

    override fun onDraw(canvas: Canvas) {
        val bitmap = shadedBitmap
        if (bitmap == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            super.onDraw(canvas)
            return
        }
        val shader = lanczos ?: runCatching { LanczosShader() }
            .onFailure {
                Log.w(TAG, "Lanczos3 shader unavailable, drawing bilinear", it)
                shaderFailed = true
            }
            .getOrNull()?.also { lanczos = it }
        if (shader == null) {
            super.onDraw(canvas)
            return
        }
        val d = drawable as BitmapDrawable
        val saved = canvas.save()
        canvas.translate(paddingLeft.toFloat(), paddingTop.toFloat())
        canvas.concat(drawMatrix)
        canvas.drawRect(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat(), shader.paint(bitmap, d.colorFilter, d.paint.alpha))
        canvas.restoreToCount(saved)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    /** How far the content may still move in the drag direction (dx, dy), per axis. */
    private fun roomX(dx: Float): Boolean {
        val d = drawable ?: return false
        matrix.getValues(values)
        val contentW = d.intrinsicWidth * values[Matrix.MSCALE_X]
        if (contentW <= width + EDGE_SLOP) return false
        val tx = values[Matrix.MTRANS_X]
        return if (dx > 0) tx < -EDGE_SLOP else tx > width - contentW + EDGE_SLOP
    }

    private fun roomY(dy: Float): Boolean {
        val d = drawable ?: return false
        matrix.getValues(values)
        val contentH = d.intrinsicHeight * values[Matrix.MSCALE_Y]
        if (contentH <= height + EDGE_SLOP) return false
        val ty = values[Matrix.MTRANS_Y]
        return if (dy > 0) ty < -EDGE_SLOP else ty > height - contentH + EDGE_SLOP
    }

    /** True when the content is larger than the view in either direction. */
    private fun canPan(): Boolean {
        val d = drawable ?: return false
        matrix.getValues(values)
        return d.intrinsicWidth * values[Matrix.MSCALE_X] > width + EDGE_SLOP ||
            d.intrinsicHeight * values[Matrix.MSCALE_Y] > height + EDGE_SLOP
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                // Until we know the drag direction, let a pannable page keep the gesture.
                parent?.requestDisallowInterceptTouchEvent(canPan())
            }
            MotionEvent.ACTION_MOVE -> handleMove(event)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    private fun handleMove(event: MotionEvent) {
        if (scaleDetector.isInProgress) {
            parent?.requestDisallowInterceptTouchEvent(true)
        } else if (event.pointerCount == 1) {
            val dx = event.x - lastX
            val dy = event.y - lastY
            if (canPan()) {
                // Pan while there is room in the dominant direction; at an edge hand the
                // gesture back so the pager / list can move to the next page.
                val horizontal = abs(dx) >= abs(dy)
                val room = if (horizontal) roomX(dx) else roomY(dy)
                parent?.requestDisallowInterceptTouchEvent(room)
                if (room) {
                    matrix.postTranslate(if (roomX(dx)) dx else 0f, if (roomY(dy)) dy else 0f)
                    clamp()
                    applyMatrix()
                }
            } else {
                onVerticalDrag?.invoke(dy)
            }
        }
        lastX = event.x
        lastY = event.y
    }

    private companion object {
        const val TAG = "ZoomImageView"
        const val EDGE_SLOP = 2f

        /** Scale error below which the content counts as 1:1. */
        const val ONE_TO_ONE = 1e-4f
    }
}
