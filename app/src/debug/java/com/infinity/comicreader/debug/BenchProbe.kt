package com.infinity.comicreader.debug

import android.graphics.Rect
import android.view.View
import com.infinity.comicreader.util.ReadProbe

/**
 * Hears the reader and the library for [BenchRunner]: which page each page view stands
 * for and shows ([pages]), the reader's moves, and covers drawn. Main thread only.
 */
internal class BenchProbe : ReadProbe {
    val pages = PageWatch()

    /** Each cover view's position and whether it shows its full cover. */
    val covers = HashMap<View, Pair<Int, Boolean>>()

    /** Nanosecond times of the first quick and the first and last full cover heard; -1 until then. */
    var firstCover = -1L
    var firstFullCover = -1L
    var lastFullCover = -1L
    var coverEvents = 0

    /** Called with the time of each move of the reader. */
    var onMoved: ((Long) -> Unit)? = null

    /** Nanosecond time the reader last started opening a book; -1 until then. */
    var opening = -1L

    /** Pages decoded, for showing or reading ahead. */
    var decodes = 0

    override fun decoded(page: Int) {
        decodes++
    }

    override fun opening(path: String) {
        opening = System.nanoTime()
    }

    override fun moved(item: Int) {
        onMoved?.invoke(System.nanoTime())
    }

    override fun bound(view: View, page: Int) = pages.bound(view, page)

    override fun shown(view: View, page: Int, exact: Boolean) = pages.shown(view, page, exact)

    override fun cover(view: View, position: Int, exact: Boolean) {
        val now = System.nanoTime()
        covers[view] = position to exact
        coverEvents++
        if (firstCover < 0) firstCover = now
        if (exact) {
            if (firstFullCover < 0) firstFullCover = now
            lastFullCover = now
        }
    }

    /** The page views on screen now, in screen order. */
    fun pageViewsOnScreen(): List<View> = pages.views.filterIsInstance<View>().filter { onScreen(it) }.sortedWith(SCREEN_ORDER)

    /** The cover views on screen now. */
    fun coversOnScreen(): List<Pair<Int, Boolean>> = covers.filterKeys { onScreen(it) }.values.toList()

    fun clear() {
        pages.clear()
        covers.clear()
        firstCover = -1
        firstFullCover = -1
        lastFullCover = -1
        coverEvents = 0
        onMoved = null
        opening = -1
        decodes = 0
    }

    private companion object {
        /** Fewest pixels of a view each way that count as on screen. */
        const val MIN_PX = 32

        val SCREEN_ORDER = compareBy<View>({ it.locationOnScreen()[1] }, { it.locationOnScreen()[0] })

        fun View.locationOnScreen(): IntArray = IntArray(2).also { getLocationOnScreen(it) }

        fun onScreen(view: View): Boolean {
            if (!view.isAttachedToWindow || !view.isShown) return false
            val r = Rect()
            return view.getGlobalVisibleRect(r) && r.width() >= MIN_PX && r.height() >= MIN_PX
        }
    }
}
