package com.infinity.comicreader.ui

import com.infinity.comicreader.data.DetailDisplay
import com.infinity.comicreader.data.ListDensity
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.TabView
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** A quick view option on the ring a long press on the grid/list button opens. */
internal enum class RingOption {
    /** Grid or list. */
    LAYOUT,

    /** More grid columns (and rows, when set), or a smaller list density. */
    SMALLER,

    /** Fewer grid columns (and rows, when set), or a larger list density. */
    LARGER,

    /** Detail display: Hidden, Comfortable, Dense in turn. */
    DETAIL,

    /** The first sort level's direction. */
    SORT,

    /** Folders first, on or off (Browse). */
    FOLDERS_FIRST,

    /** Favorites under Libraries, Sets and Comics, on or off (Favorites). */
    SECTIONS
}

/** What the view ring offers per tab, and what each option changes; free of Android types. */
internal object ViewRing {

    private val COMMON = listOf(RingOption.LAYOUT, RingOption.SMALLER, RingOption.LARGER, RingOption.DETAIL, RingOption.SORT)

    /** [tab]'s options, from the top of the ring inward: the common five, plus Folders first in Browse or Sections in Favorites. */
    fun options(tab: String): List<RingOption> = when (tab) {
        Prefs.TAB_BROWSE -> COMMON + RingOption.FOLDERS_FIRST
        Prefs.TAB_FAVORITES -> COMMON + RingOption.SECTIONS
        else -> COMMON
    }

    /** [view] with larger covers or rows: one column fewer than the [columns] shown (and one row, when set), or the next density up; null at the end. */
    fun larger(view: TabView, columns: Int): TabView? = if (view.grid) grid(view, columns, -1) else density(view, +1)

    /** [view] with smaller covers or rows: one column more than the [columns] shown (and one row, when set), or the next density down; null at the end. */
    fun smaller(view: TabView, columns: Int): TabView? = if (view.grid) grid(view, columns, +1) else density(view, -1)

    private fun grid(view: TabView, columns: Int, step: Int): TabView? {
        val c = (columns + step).coerceIn(1, TabView.MAX_CELLS)
        val r = if (view.rows > 0) (view.rows + step).coerceIn(1, TabView.MAX_CELLS) else 0
        return if (c == columns && r == view.rows) null else view.copy(columns = c, rows = r)
    }

    private fun density(view: TabView, step: Int): TabView? {
        val d = ListDensity.ofStep(view.density.step + step)
        return if (d == view.density) null else view.copy(density = d)
    }

    /** The detail display after [detail], round and round. */
    fun nextDetail(detail: DetailDisplay): DetailDisplay = DetailDisplay.entries[(detail.ordinal + 1) % DetailDisplay.entries.size]
}

/**
 * Where the view ring's buttons go: on a quarter arc around the hub (the floating
 * button's place), from straight up to straight toward the screen's inside. Angles
 * are in degrees, 90 straight up, 180 straight left; y grows downward.
 */
internal object RingGeometry {
    const val SWEEP = 90.0

    /** The angle of each of [count] buttons, from straight up toward the left ([left]) or the right. */
    fun angles(count: Int, left: Boolean): List<Double> {
        val side = if (left) 1 else -1
        if (count == 1) return listOf(90.0 + side * SWEEP / 2)
        val step = SWEEP / (count - 1)
        return List(count) { 90.0 + side * step * it }
    }

    /** The radius at which [count] buttons sit [spacing] apart center to center over the sweep, never below [min]. */
    fun radius(count: Int, spacing: Float, min: Float): Float {
        if (count < 2) return min
        val half = Math.toRadians(SWEEP / (count - 1) / 2)
        return max(min, (spacing / (2 * sin(half))).toFloat())
    }

    /** The offset from the hub's center of a button at [angle] on a ring of [radius]. */
    fun spot(angle: Double, radius: Float): Pair<Float, Float> {
        val a = Math.toRadians(angle)
        return (radius * cos(a)).toFloat() to (-radius * sin(a)).toFloat()
    }

    /**
     * The center of a label [width] x [height] beside a button at [angle], outward from the
     * hub: its side or corner facing the button lies [reach] from the button's center.
     */
    fun labelCenter(angle: Double, reach: Float, width: Float, height: Float): Pair<Float, Float> {
        val a = Math.toRadians(angle)
        val ux = cos(a).toFloat()
        val uy = -sin(a).toFloat()
        val k = max(abs(ux), abs(uy))
        return (ux * reach + ux / k * width / 2) to (uy * reach + uy / k * height / 2)
    }
}
