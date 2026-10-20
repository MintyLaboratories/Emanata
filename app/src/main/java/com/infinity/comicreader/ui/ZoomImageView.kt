package com.infinity.comicreader.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import androidx.appcompat.widget.AppCompatImageView
import com.infinity.comicreader.reader.Filters
import com.infinity.comicreader.reader.Look
import com.infinity.comicreader.reader.Region
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Image view with pinch-to-zoom, panning, double-tap zoom and configurable fit modes.
 * Uses [ScaleType.MATRIX] and manipulates the image matrix directly; at 1:1 the
 * bitmap is drawn unfiltered on whole pixels ([applyMatrix]); enlarged through
 * Lanczos3 on the GPU ([PageShader], API 33+); shrunk, or below API 33, bilinear.
 * A zoom that settles is re-rendered from the full-detail page ([DetailSource]).
 * A [look] beyond a plain color matrix runs in the same GPU pass, at every scale.
 *
 * [onTap] receives the tap position as fractions of the view's width and height, for
 * tap zones. [verticalDrag] receives drags locked to the vertical axis while the
 * image cannot pan (the brightness gesture); a drag locked horizontally goes to the
 * parent, to turn the page.
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

    var onTap: ((Float, Float) -> Unit)? = null
    var verticalDrag: VerticalDrag? = null

    /** A drag the view locked to the vertical axis ([AxisLock]); the parent sees none of it. */
    interface VerticalDrag {
        fun start()

        /** The finger moved [dy] px, down positive. */
        fun move(dy: Float)

        fun end()
    }

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var downX = 0f
    private var downY = 0f
    private var axis = AxisLock.NONE

    /** The finger whose movement drags; another takes over when it lifts. */
    private var activePointer = MotionEvent.INVALID_POINTER_ID

    /** True once a second finger joined the gesture before it locked vertically. */
    private var pinched = false
    var doubleTapEnabled = true

    /**
     * The page's colors. A plain one is the view's color filter; any other runs on the
     * GPU, where it can, with the page's resampling; elsewhere the filter alone applies,
     * an invert as an RGB invert.
     */
    var look: Look = Look.NONE
        set(value) {
            val old = field
            field = value
            if (value.invert != old.invert || !value.matrix.contentEquals(old.matrix)) {
                colorFilter = if (value.invert) Filters.colorFilter(Filters.INVERT) else value.matrix?.let { ColorMatrixColorFilter(it) }
            }
            invalidate()
        }

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                detailSource?.prepare()
                return true
            }

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
                val x = if (width > 0) e.x / width.toFloat() else 0.5f
                val y = if (height > 0) e.y / height.toFloat() else 0.5f
                onTap?.invoke(x, y)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (!doubleTapEnabled) return false
                if (currentScale > minScale * 1.1f) {
                    fit(fitMode)
                } else {
                    detailSource?.prepare()
                    zoomTo(minScale * 3f, e.x, e.y)
                }
                return true
            }
        }
    )

    init {
        scaleType = ScaleType.MATRIX
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
        val oneToOne = snapOneToOne(values)
        if (oneToOne) drawMatrix.setValues(values) else drawMatrix.set(matrix)
        (drawable as? BitmapDrawable)?.isFilterBitmap = !oneToOne
        imageMatrix = drawMatrix
        // Every move restarts the wait for the view to settle.
        cancelDetailRender()
        settler.removeCallbacks(settle)
        if (oneToOne || !zoomed) dropDetail() else if (detailSource != null) settler.postDelayed(settle, SETTLE_MS)
    }

    // ------------------------------------------------------------------ detail

    /**
     * Renders part of the shown page at full detail. Once a zoom or pan has been still
     * for [SETTLE_MS], the view asks for exactly the pixels it shows and draws the result
     * over the page at 1:1; it then moves with the page until the next settle replaces it.
     */
    interface DetailSource {
        /** A zoom is starting: the moment to get the full-detail page ready. */
        fun prepare()

        /**
         * Starts rendering [region] of the shown bitmap's page at [width] x [height]; [done]
         * receives it on the main thread. Returns what cancels it.
         */
        fun render(region: Region, width: Int, height: Int, done: (Bitmap) -> Unit): () -> Unit
    }

    /** Where settled zooms get their detail; none keeps the scaled page as it is. */
    var detailSource: DetailSource? = null
        set(value) {
            if (field === value) return
            field = value
            dropDetail()
        }

    private var detail: Bitmap? = null

    /** Maps [detail]'s pixels onto the shown bitmap's. */
    private val detailMatrix = Matrix()
    private val detailDraw = Matrix()
    private var detailCancel: (() -> Unit)? = null
    private var detailRequest = 0
    private val settle = Runnable { requestDetail() }

    /** Runs [settle] whether or not the view is attached yet. */
    private val settler = Handler(Looper.getMainLooper())
    private val plainPaint = Paint()
    private val filteredPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val tileRect = RectF()
    private val shownRect = RectF()

    /** True when zoomed in past the fitted scale. */
    private val zoomed: Boolean get() = currentScale > minScale * (1f + ONE_TO_ONE)

    /** The detail drawn over the page, if any; for tests. */
    internal val detailShown: Bitmap? get() = detail

    private fun cancelDetailRender() {
        detailRequest++
        detailCancel?.invoke()
        detailCancel = null
    }

    private fun dropDetail() {
        cancelDetailRender()
        settler.removeCallbacks(settle)
        clearShaders(detailShaders)
        if (detail != null) {
            detail = null
            invalidate()
        }
    }

    /** Asks [detailSource] for the pixels now shown, unless they are already on screen. */
    private fun requestDetail() {
        val source = detailSource ?: return
        val d = drawable as? BitmapDrawable ?: return
        val bitmap = d.bitmap ?: return
        if (!d.isFilterBitmap) return
        drawMatrix.getValues(values)
        if (values[Matrix.MSKEW_X] != 0f || values[Matrix.MSKEW_Y] != 0f) return
        val tile = DetailTile.of(
            values[Matrix.MSCALE_X], values[Matrix.MSCALE_Y], values[Matrix.MTRANS_X], values[Matrix.MTRANS_Y],
            bitmap.width, bitmap.height, width - paddingLeft - paddingRight, height - paddingTop - paddingBottom
        ) ?: return
        val toBitmap = Matrix()
        if (!drawMatrix.invert(toBitmap)) return
        toBitmap.preTranslate(tile.left.toFloat(), tile.top.toFloat())
        val current = detail
        if (current != null && current.width == tile.width && current.height == tile.height && toBitmap == detailMatrix) return

        cancelDetailRender()
        val request = detailRequest
        var delivered = false
        val cancel = source.render(tile.region, tile.width, tile.height) { result ->
            delivered = true
            if (request != detailRequest) return@render
            detailCancel = null
            // The shaders that drew the old tile would keep it alive.
            clearShaders(detailShaders)
            detail = result
            detailMatrix.set(toBitmap)
            invalidate()
        }
        if (!delivered && request == detailRequest) detailCancel = cancel
    }

    override fun setImageDrawable(drawable: Drawable?) {
        dropDetail()
        clearShaders(pageShaders)
        super.setImageDrawable(drawable)
        post { fit(fitMode) }
    }

    /** A zoomed view coming back from a list's cache, which keeps its zoom, renders its detail again. */
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        resettle()
    }

    /** Waits for the view to settle again, if it is zoomed, to render its detail. */
    private fun resettle() {
        if (!zoomed || detailSource == null) return
        settler.removeCallbacks(settle)
        settler.postDelayed(settle, SETTLE_MS)
    }

    /** A hidden window drops the detail and lets go of shaded bitmaps; shown again, it renders the detail anew. */
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) {
            resettle()
        } else {
            dropDetail()
            clearShaders(pageShaders)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        dropDetail()
        clearShaders(pageShaders)
    }

    /** True when the content is drawn at 1:1 without filtering; for tests. */
    internal val drawsOneToOne: Boolean
        get() = (drawable as? BitmapDrawable)?.isFilterBitmap == false

    /** True when [look] runs on the GPU. */
    private val colored: Boolean
        get() = !look.plain && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !shaderFailed

    /**
     * How the page bitmap is drawn through a [PageShader], or null for a plain draw: an
     * enlarged page through Lanczos3; with a [colored] look also a 1:1 page unfiltered and
     * a shrunk one bilinear, which aliases less than the Lanczos3 shader would.
     */
    private val pageKernel: PageShader.Kernel?
        get() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || shaderFailed) return null
            val d = drawable as? BitmapDrawable ?: return null
            if (d.bitmap?.isRecycled != false) return null
            if (!d.isFilterBitmap) return if (colored) PageShader.Kernel.NEAREST else null
            drawMatrix.getValues(drawValues)
            if (drawValues[Matrix.MSCALE_X] < 1f - ONE_TO_ONE || drawValues[Matrix.MSCALE_Y] < 1f - ONE_TO_ONE) {
                return if (colored) PageShader.Kernel.LINEAR else null
            }
            return PageShader.Kernel.LANCZOS
        }

    /** The bitmap drawn through a [PageShader]; for tests. */
    internal val shadedBitmap: Bitmap?
        get() = if (pageKernel == null) null else (drawable as? BitmapDrawable)?.bitmap

    private val drawValues = FloatArray(9)

    /** Shaders by [shaderSlot], each holding the bitmap it last drew: for the page and for its detail. */
    private val pageShaders = arrayOfNulls<PageShader>(SHADER_SLOTS)
    private val detailShaders = arrayOfNulls<PageShader>(SHADER_SLOTS)
    private var shaderFailed = false

    /** The bitmaps the shaders hold; for tests. */
    internal val shadersHold: List<Bitmap> get() = (pageShaders + detailShaders).mapNotNull { it?.held }

    private fun shaderSlot(kernel: PageShader.Kernel, colored: Boolean) = kernel.ordinal * 2 + if (colored) 1 else 0

    /**
     * The shader in [shaders] for [kernel], with the color stage when [colored]: made on
     * first use, or null where it cannot run, after which the view draws plainly.
     */
    private fun shader(shaders: Array<PageShader?>, kernel: PageShader.Kernel, colored: Boolean): PageShader? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || shaderFailed) return null
        val slot = shaderSlot(kernel, colored)
        shaders[slot]?.let { return it }
        return runCatching { PageShader(kernel, colored, shaderDialect) }
            .onFailure {
                Log.w(TAG, "Page shader unavailable, drawing plainly", it)
                shaderFailed = true
                invalidate()
            }
            .getOrNull()
            ?.also { shaders[slot] = it }
    }

    private fun clearShaders(shaders: Array<PageShader?>) = shaders.forEach { it?.clear() }

    override fun onDraw(canvas: Canvas) {
        val d = drawable as? BitmapDrawable
        val tile = detail?.takeUnless { it.isRecycled || d == null }
        var tileAtOneToOne = false
        var hole: RectF? = null
        if (tile != null) {
            detailDraw.set(detailMatrix)
            detailDraw.postConcat(drawMatrix)
            detailDraw.getValues(values)
            tileAtOneToOne = snapOneToOne(values)
            if (tileAtOneToOne) detailDraw.setValues(values)
            // Shrinking it through Lanczos3 at source size would alias: the page shows instead.
            if (values[Matrix.MSCALE_X] >= 1f - ONE_TO_ONE && values[Matrix.MSCALE_Y] >= 1f - ONE_TO_ONE) {
                tileRect.set(0f, 0f, tile.width.toFloat(), tile.height.toFloat())
                detailDraw.mapRect(tileRect)
                hole = tileRect
            }
        }
        if (hole == null || !covers(hole)) drawPage(canvas, hole)
        if (tile != null && d != null && hole != null) drawDetail(canvas, tile, d, tileAtOneToOne)
    }

    /**
     * The page bitmap, through a [PageShader] when [pageKernel] calls for one, leaving out
     * [hole], where the detail goes. The shader runs only on the bands around the hole,
     * each scissored by a plain rectangle clip, so the GPU never shades what the detail hides.
     */
    private fun drawPage(canvas: Canvas, hole: RectF?) {
        val kernel = pageKernel
        val colored = this.colored
        val shader = if (kernel == null) null else shader(pageShaders, kernel, colored)
        val saved = canvas.save()
        canvas.translate(paddingLeft.toFloat(), paddingTop.toFloat())
        if (kernel == null || shader == null) {
            if (hole != null) canvas.clipOutRect(hole)
            canvas.translate(-paddingLeft.toFloat(), -paddingTop.toFloat())
            super.onDraw(canvas)
        } else {
            val d = drawable as BitmapDrawable
            val bitmap = d.bitmap
            val paint = shader.paint(bitmap, if (colored) null else d.colorFilter, d.paint.alpha, look)
            val w = (width - paddingLeft - paddingRight).toFloat()
            val h = (height - paddingTop - paddingBottom).toFloat()
            val bands = if (hole == null) listOf(RectF(0f, 0f, w, h)) else bandsAround(0f, 0f, w, h, hole)
            for (band in bands) {
                val inBand = canvas.save()
                canvas.clipRect(band)
                canvas.concat(drawMatrix)
                canvas.drawRect(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat(), paint)
                canvas.restoreToCount(inBand)
            }
        }
        canvas.restoreToCount(saved)
    }

    /** True when the detail, drawn over [hole], hides every view pixel the page shows on. */
    private fun covers(hole: RectF): Boolean {
        val bitmap = (drawable as? BitmapDrawable)?.bitmap ?: return true
        shownRect.set(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
        drawMatrix.mapRect(shownRect)
        if (!shownRect.intersect(0f, 0f, (width - paddingLeft - paddingRight).toFloat(), (height - paddingTop - paddingBottom).toFloat())) return true
        // A rectangle covers the pixels whose centers it holds.
        return first(hole.left) <= first(shownRect.left) && first(hole.top) <= first(shownRect.top) &&
            first(hole.right) >= first(shownRect.right) && first(hole.bottom) >= first(shownRect.bottom)
    }

    /** The first pixel whose center lies at or past [edge]. */
    private fun first(edge: Float): Int = ceil(edge - 0.5f).toInt()

    /**
     * [tile] where [detailDraw] puts it: at 1:1 unfiltered, else enlarged through Lanczos3;
     * a [colored] look runs in the same shader.
     */
    private fun drawDetail(canvas: Canvas, tile: Bitmap, d: BitmapDrawable, oneToOne: Boolean) {
        val saved = canvas.save()
        canvas.translate(paddingLeft.toFloat(), paddingTop.toFloat())
        canvas.concat(detailDraw)
        val colored = this.colored
        val shader = when {
            !oneToOne -> shader(detailShaders, PageShader.Kernel.LANCZOS, colored)
            colored -> shader(detailShaders, PageShader.Kernel.NEAREST, true)
            else -> null
        }
        if (shader != null) {
            val paint = shader.paint(tile, if (colored) null else d.colorFilter, d.paint.alpha, look)
            canvas.drawRect(0f, 0f, tile.width.toFloat(), tile.height.toFloat(), paint)
        } else {
            val paint = if (oneToOne) plainPaint else filteredPaint
            paint.colorFilter = d.colorFilter
            paint.alpha = d.paint.alpha
            canvas.drawBitmap(tile, 0f, 0f, paint)
        }
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
        val action = event.actionMasked
        // A drag locked to the vertical axis is brightness only: further fingers do not pinch.
        if (axis != AxisLock.VERTICAL || action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            scaleDetector.onTouchEvent(event)
        }
        gestureDetector.onTouchEvent(event)

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                activePointer = event.getPointerId(0)
                lastX = event.x
                lastY = event.y
                downX = event.x
                downY = event.y
                axis = AxisLock.NONE
                pinched = false
                // Until the drag picks its direction, keep it from a parent that would turn the
                // page: a pannable page may pan, and a vertical drag must not turn it.
                parent?.requestDisallowInterceptTouchEvent(canPan() || verticalDrag != null)
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (axis != AxisLock.VERTICAL) pinched = true
            MotionEvent.ACTION_POINTER_UP -> {
                // The drag goes on with a finger still down, from where that finger is.
                val up = event.actionIndex
                if (event.getPointerId(up) == activePointer) {
                    val next = if (up == 0) 1 else 0
                    activePointer = event.getPointerId(next)
                    lastX = event.getX(next)
                    lastY = event.getY(next)
                }
            }
            MotionEvent.ACTION_MOVE -> handleMove(event)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (axis == AxisLock.VERTICAL) verticalDrag?.end()
                axis = AxisLock.NONE
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    /**
     * Follows the finger the drag moves with. A vertical lock moves brightness whatever other
     * fingers do; a gesture that has pinched never locks an axis.
     */
    private fun handleMove(event: MotionEvent) {
        val index = event.findPointerIndex(activePointer)
        if (index < 0) return
        val x = event.getX(index)
        val y = event.getY(index)
        if (axis == AxisLock.VERTICAL) {
            verticalDrag?.move(y - lastY)
        } else if (scaleDetector.isInProgress) {
            parent?.requestDisallowInterceptTouchEvent(true)
        } else if (event.pointerCount == 1) {
            val dx = x - lastX
            val dy = y - lastY
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
            } else if (verticalDrag != null && axis == AxisLock.NONE && !pinched) {
                axis = AxisLock.decide(x - downX, y - downY, touchSlop)
                when (axis) {
                    AxisLock.VERTICAL -> verticalDrag?.start()
                    AxisLock.HORIZONTAL -> parent?.requestDisallowInterceptTouchEvent(false)
                    AxisLock.NONE -> return
                }
            }
        }
        lastX = x
        lastY = y
    }

    internal companion object {
        private const val TAG = "ZoomImageView"
        private const val EDGE_SLOP = 2f

        /** Scale error below which the content counts as 1:1. */
        private const val ONE_TO_ONE = 1e-4f

        /** Rewrites the views' AGSL; tests swap in their host's dialect. */
        internal var shaderDialect: (String) -> String = { it }

        private const val SHADER_SLOTS = 6

        /** How long the view must be still before it renders its detail. */
        private const val SETTLE_MS = 150L

        /**
         * The rectangle ([left], [top], [right], [bottom]) less [hole], as up to four
         * bands: full-width above and below the hole, and beside it at its height.
         */
        fun bandsAround(left: Float, top: Float, right: Float, bottom: Float, hole: RectF): List<RectF> {
            val l = hole.left.coerceIn(left, right)
            val r = hole.right.coerceIn(left, right)
            val t = hole.top.coerceIn(top, bottom)
            val b = hole.bottom.coerceIn(top, bottom)
            if (l >= r || t >= b) return listOf(RectF(left, top, right, bottom))
            return listOf(
                RectF(left, top, right, t),
                RectF(left, b, right, bottom),
                RectF(left, t, l, b),
                RectF(r, t, right, b)
            ).filter { it.left < it.right && it.top < it.bottom }
        }

        /**
         * True when matrix [values] draw at 1:1, which they are then snapped to: scale
         * exactly 1 and the translation on whole pixels.
         */
        fun snapOneToOne(values: FloatArray): Boolean {
            val oneToOne = abs(values[Matrix.MSCALE_X] - 1f) < ONE_TO_ONE && abs(values[Matrix.MSCALE_Y] - 1f) < ONE_TO_ONE &&
                values[Matrix.MSKEW_X] == 0f && values[Matrix.MSKEW_Y] == 0f
            if (oneToOne) {
                values[Matrix.MSCALE_X] = 1f
                values[Matrix.MSCALE_Y] = 1f
                values[Matrix.MTRANS_X] = values[Matrix.MTRANS_X].roundToInt().toFloat()
                values[Matrix.MTRANS_Y] = values[Matrix.MTRANS_Y].roundToInt().toFloat()
            }
            return oneToOne
        }
    }
}
