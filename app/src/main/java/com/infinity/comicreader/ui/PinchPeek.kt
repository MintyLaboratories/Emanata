package com.infinity.comicreader.ui

import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.infinity.comicreader.data.BookEntry
import com.infinity.comicreader.data.PeekMode
import com.infinity.comicreader.data.Prefs
import kotlin.math.hypot

/**
 * Pinch to peek on a list: spreading two fingers on a comic in [recycler] grows a
 * [PeekOverlay] of its pages out of its cover ([PeekGesture]). [target] names the
 * comic under a list point and the view its cover is drawn in; [mode] is the
 * "Pinch to peek" setting. The second finger landing on a comic takes the touch
 * from its row, so the row neither clicks nor opens its long-press menu.
 */
class PinchPeek(
    private val activity: AppCompatActivity,
    private val recycler: RecyclerView,
    private val mode: () -> PeekMode,
    private val target: (x: Float, y: Float) -> Pair<BookEntry, View>?,
    onOpen: (BookEntry, Int) -> Unit
) {
    private val prefs = Prefs(activity)

    /** The pages grid, over the whole screen. */
    val overlay = PeekOverlay(activity).apply {
        elevation = ELEVATION_DP * resources.displayMetrics.density
        this.onOpen = onOpen
    }

    init {
        (activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup)
            .addView(overlay, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val slop = ViewConfiguration.get(activity).scaledTouchSlop * 2f
        recycler.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            private var gesture: PeekGesture? = null
            private var picked: Pair<BookEntry, View>? = null

            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                if (e.actionMasked != MotionEvent.ACTION_POINTER_DOWN || e.pointerCount != 2) return false
                if (overlay.isShowing || mode() == PeekMode.OFF || rv.scrollState != RecyclerView.SCROLL_STATE_IDLE) return false
                val midX = (e.getX(0) + e.getX(1)) / 2
                val midY = (e.getY(0) + e.getY(1)) / 2
                picked = target(midX, midY) ?: target(e.getX(0), e.getY(0)) ?: return false
                gesture = PeekGesture(slop, fullSpread()).also { it.begin(span(e)) }
                return true
            }

            override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
                val g = gesture ?: return
                when (e.actionMasked) {
                    MotionEvent.ACTION_MOVE -> if (e.pointerCount >= 2) {
                        val t = picked ?: return
                        if (g.move(span(e))) start(t.first, t.second)
                        if (g.peeking) overlay.setProgress(g.progress)
                    }
                    MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (g.peeking) {
                            val outcome = if (e.actionMasked == MotionEvent.ACTION_CANCEL) PeekGesture.Release.COLLAPSE else g.release(mode())
                            overlay.release(outcome)
                        }
                        gesture = null
                        picked = null
                    }
                }
            }
        })
    }

    private fun span(e: MotionEvent) = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))

    /** Spread growth that opens a peek fully. */
    private fun fullSpread(): Float {
        val metrics = activity.resources.displayMetrics
        return minOf(metrics.widthPixels, metrics.heightPixels) * PeekGesture.FULL_SPREAD_SHARE
    }

    /** Starts a peek into [entry] growing out of [tileView], or out of the list's middle without one. */
    fun start(entry: BookEntry, tileView: View?) {
        val full = boxOf(recycler).let {
            val inset = INSET_DP * activity.resources.displayMetrics.density
            Box(it.left + inset, it.top + inset, it.width - 2 * inset, it.height - 2 * inset)
        }
        val tile = tileView?.let { boxOf(it) } ?: Box(full.left + full.width / 2, full.top + full.height / 2, 1f, 1f)
        overlay.begin(entry, tile, full, activity.lifecycleScope, prefs.charset, activity.cacheDir, prefs.linearLight)
    }

    /** Peeks into [entry] growing out of [tileView] and leaves it open. */
    fun open(entry: BookEntry, tileView: View?) {
        start(entry, tileView)
        overlay.release(PeekGesture.Release.KEEP_OPEN)
    }

    /** [view]'s bounds in the overlay's coordinates. */
    private fun boxOf(view: View): Box {
        val at = IntArray(2)
        val origin = IntArray(2)
        view.getLocationInWindow(at)
        overlay.getLocationInWindow(origin)
        return Box((at[0] - origin[0]).toFloat(), (at[1] - origin[1]).toFloat(), view.width.toFloat(), view.height.toFloat())
    }

    private companion object {
        /** Room between an open peek and the list's edges. */
        const val INSET_DP = 12

        /** Above the list, the app bar and any button. */
        const val ELEVATION_DP = 16
    }
}
