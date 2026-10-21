package com.infinity.comicreader.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.infinity.comicreader.data.BookEntry
import com.infinity.comicreader.data.PeekMode
import com.infinity.comicreader.reader.PageSource
import com.infinity.comicreader.reader.PageSources
import com.infinity.comicreader.util.AppDispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A grid of a comic's pages over the library, growing out of the comic's tile:
 * [progress] 0 is the tile, 1 the grid open over the list. Open, it scrolls; a
 * tapped page opens the book there, a tap outside, Back or pinching it closes it.
 */
class PeekOverlay(context: Context) : FrameLayout(context) {

    /** Opens [BookEntry] at a page; -1 lets the reader choose where to start. */
    var onOpen: ((BookEntry, Int) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val scrim = View(context).apply { setBackgroundColor(Color.BLACK) }
    private val card = FrameLayout(context)
    private val grid = RecyclerView(context)
    private val spinner = ProgressBar(context)

    private var entry: BookEntry? = null
    private var source: PageSource? = null
    private var adapter: ThumbAdapter? = null
    private var loadJob: Job? = null
    private var animator: ValueAnimator? = null
    private var tile = Box(0f, 0f, 0f, 0f)
    private var full = Box(0f, 0f, 0f, 0f)

    /** How far open it is: 0 is the tile, 1 fully open. */
    var progress = 0f
        private set

    /** True while it shows a comic, growing, open or closing. */
    val isShowing: Boolean get() = entry != null

    /** Pinching the open grid closes it like the spread that opened it, in reverse. */
    private val pinch = PeekGesture(ViewConfiguration.get(context).scaledTouchSlop * 2f, 1f)
    private var pinchStart = 0f
    private var pinching = false

    init {
        visibility = GONE
        addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        card.background = GradientDrawable().apply {
            cornerRadius = 16 * density
            setColor(MaterialColors.getColor(this@PeekOverlay, com.google.android.material.R.attr.colorSurfaceContainerHigh))
        }
        card.pivotX = 0f
        card.pivotY = 0f
        val pad = (8 * density).toInt()
        grid.setPadding(pad, pad, pad, pad)
        grid.clipToPadding = false
        card.addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        card.addView(spinner, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        addView(card)
        scrim.setOnClickListener { close() }
        // The card swallows taps between pages so they do not reach the scrim.
        card.isClickable = true
    }

    /**
     * Starts peeking into [entry], growing from [tile] toward [full] (both in this
     * view's coordinates); its pages load in [scope].
     */
    fun begin(entry: BookEntry, tile: Box, full: Box, scope: CoroutineScope, charset: String, cacheDir: File, linear: Boolean) {
        clear()
        this.entry = entry
        this.tile = tile
        this.full = full
        card.layoutParams = LayoutParams(full.width.roundToInt(), full.height.roundToInt()).apply {
            leftMargin = full.left.roundToInt()
            topMargin = full.top.roundToInt()
        }
        val columns = (full.width / density / CELL_DP).toInt().coerceIn(MIN_COLUMNS, MAX_COLUMNS)
        val cell = (full.width - grid.paddingLeft - grid.paddingRight) / columns
        grid.layoutManager = GridLayoutManager(context, columns)
        spinner.visibility = VISIBLE
        visibility = VISIBLE
        setProgress(0f)
        val started = SystemClock.elapsedRealtime()
        val timing = FillTiming(started)
        loadJob = scope.launch {
            val opened = withContext(AppDispatchers.io) { runCatching { PageSources.open(entry.file, charset, cacheDir) }.getOrNull() }
            if (opened == null || this@PeekOverlay.entry !== entry) {
                opened?.let { withContext(AppDispatchers.io) { runCatching { it.close() } } }
                if (this@PeekOverlay.entry === entry) spinner.visibility = GONE
                return@launch
            }
            source = opened
            spinner.visibility = GONE
            val a = ThumbAdapter(opened, entry.progressPage, linear, thumbHeight = (cell * PAGE_ASPECT).roundToInt()) { page ->
                onOpen?.invoke(entry, page)
                hide()
            }
            val openMs = SystemClock.elapsedRealtime() - started
            a.onShown = { position, exact -> logFill(timing, position, exact) { "pages: opened $openMs ms" } }
            adapter = a
            grid.adapter = a
            if (entry.progressPage > 0) grid.scrollToPosition(entry.progressPage)
        }
    }

    /** Logs how fast the cells in view filled in, once they all show their exact image. */
    private fun logFill(timing: FillTiming, position: Int, exact: Boolean, what: () -> String) {
        val lm = grid.layoutManager as? GridLayoutManager ?: return
        val first = lm.findFirstVisibleItemPosition()
        val visible = if (first < 0) IntRange.EMPTY else first..lm.findLastVisibleItemPosition()
        if (timing.shown(position, exact, visible, SystemClock.elapsedRealtime())) {
            Log.d(TAG, "${what()}, first image ${timing.first} ms, ${visible.count()} in view ${timing.inView} ms, exact ${timing.exact} ms")
        }
    }

    /** Grows or shrinks to [t] of the way from the tile to fully open. */
    fun setProgress(t: Float) {
        progress = t.coerceIn(0f, 1f)
        val frame = PeekFrame.of(Box.lerp(tile, full, progress), full)
        card.scaleX = frame.scale
        card.scaleY = frame.scale
        card.translationX = frame.dx
        card.translationY = frame.dy
        card.clipBounds = Rect(0, 0, full.width.roundToInt(), frame.clipHeight.roundToInt())
        scrim.alpha = SCRIM_ALPHA * progress
    }

    /** Settles after the fingers lift, as [outcome] says. */
    fun release(outcome: PeekGesture.Release) {
        val e = entry ?: return
        when (outcome) {
            PeekGesture.Release.COLLAPSE -> close()
            PeekGesture.Release.KEEP_OPEN -> animateTo(1f) {}
            PeekGesture.Release.OPEN_BOOK -> animateTo(1f) {
                onOpen?.invoke(e, -1)
                hide()
            }
        }
    }

    /** Shrinks back into the tile and goes away. */
    fun close() {
        if (!isShowing) return
        animateTo(0f) { hide() }
    }

    /** Goes away at once. */
    fun hide() {
        clear()
        visibility = GONE
    }

    private fun animateTo(target: Float, end: () -> Unit) {
        animator?.cancel()
        val from = progress
        animator = ValueAnimator.ofFloat(from, target).apply {
            duration = (ANIMATION_MS * kotlin.math.abs(target - from)).toLong().coerceAtLeast(1)
            interpolator = DecelerateInterpolator()
            addUpdateListener { setProgress(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                private var canceled = false
                override fun onAnimationCancel(animation: Animator) {
                    canceled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (!canceled) end()
                }
            })
            start()
        }
    }

    private fun clear() {
        animator?.cancel()
        animator = null
        loadJob?.cancel()
        loadJob = null
        grid.adapter = null
        adapter?.release()
        adapter = null
        runCatching { source?.close() }
        source = null
        entry = null
        pinching = false
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!isShowing) return false
        // Growing or shrinking: touches wait until it settles.
        if (progress < 1f) return true
        return trackPinch(ev)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (pinching) {
            trackPinch(event)
            return true
        }
        return isShowing
    }

    /** Follows two fingers pinching the open grid; true once they drive it. */
    private fun trackPinch(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> if (ev.pointerCount == 2) {
                pinchStart = span(ev)
                pinch.begin(0f)
            }
            MotionEvent.ACTION_MOVE -> if (ev.pointerCount >= 2) {
                val closedBy = pinchStart - span(ev)
                val fullPinch = min(width, height) * PeekGesture.FULL_SPREAD_SHARE
                if (!pinching && pinch.move(closedBy)) pinching = true
                if (pinching) setProgress(1f - (closedBy / fullPinch).coerceIn(0f, 1f))
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (pinching) {
                pinching = false
                release(PeekGesture.outcome(progress, PeekMode.PEEK))
            }
        }
        return pinching
    }

    private fun span(ev: MotionEvent) = hypot(ev.getX(0) - ev.getX(1), ev.getY(0) - ev.getY(1))

    companion object {
        private const val TAG = "Peek"
        private const val SCRIM_ALPHA = 0.55f
        private const val ANIMATION_MS = 220L

        /** Page cells are about this wide. */
        private const val CELL_DP = 140f
        private const val MIN_COLUMNS = 3
        private const val MAX_COLUMNS = 8

        /** Height of a page cell over its width. */
        private const val PAGE_ASPECT = 1.5f
    }
}
