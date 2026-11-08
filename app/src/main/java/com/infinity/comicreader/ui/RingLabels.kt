package com.infinity.comicreader.ui

import android.content.Context
import com.infinity.comicreader.R
import com.infinity.comicreader.data.DetailDisplay
import com.infinity.comicreader.data.ListDensity
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.RingOption
import com.infinity.comicreader.data.SortKey
import com.infinity.comicreader.data.SortLevel
import com.infinity.comicreader.data.TabView
import com.infinity.comicreader.data.TitleDisplay

/**
 * What the view ring's buttons show: each option's icon, name and "current→next" value
 * forms, fullest first ([ringForms]), e.g. Smaller "4 × 3→5 × 4", then "4×3→5×4".
 */
internal class RingLabels(private val context: Context) {

    /** [tab]'s ring items, as the settings in [prefs] choose them, for [view] showing [columns] columns. */
    fun items(prefs: Prefs, tab: String, view: TabView, columns: Int): List<ViewRingOverlay.Item> =
        ViewRing.options(tab, prefs.ringOptions).map { item(it, prefs, tab, view, columns) }

    /** [option]'s ring item in [tab]. */
    fun item(option: RingOption, prefs: Prefs, tab: String, view: TabView, columns: Int): ViewRingOverlay.Item {
        val name = nameOf(option)
        val icon = iconOf(option, view.grid)
        return when (option) {
            RingOption.LAYOUT -> toggle(option, icon, name, layout(view.grid), layout(!view.grid))
            RingOption.SMALLER -> step(option, icon, name, view, columns, ViewRing.smaller(view, columns))
            RingOption.LARGER -> step(option, icon, name, view, columns, ViewRing.larger(view, columns))
            RingOption.DETAIL -> {
                val (now, next) = detail(view.detail) to detail(ViewRing.nextDetail(view.detail))
                ViewRingOverlay.Item(option, icon, name, ringForms(now, next))
            }
            RingOption.SORT -> {
                val by = prefs.sort(tab).by
                val next = by.flip()
                ViewRingOverlay.Item(
                    option, icon, name, ringForms(sort(by), sort(next)),
                    longLabel = context.getString(R.string.view_ring_sort_more),
                    spoken = RingValue(SortDialog.levelText(context, by), SortDialog.levelText(context, next))
                )
            }
            RingOption.TITLE_LINES -> {
                val (now, next) = title(prefs.titleDisplay) to title(ViewRing.nextTitle(prefs.titleDisplay))
                ViewRingOverlay.Item(option, icon, name, ringForms(now, next))
            }
            RingOption.FOLDERS_FIRST -> toggle(option, icon, name, onOff(prefs.sort(tab).foldersFirst), onOff(!prefs.sort(tab).foldersFirst))
            RingOption.SECTIONS -> toggle(option, icon, name, onOff(prefs.groupFavorites), onOff(!prefs.groupFavorites))
        }
    }

    /** The name a ring label shows for [option]. */
    fun nameOf(option: RingOption): String = context.getString(
        when (option) {
            RingOption.LAYOUT -> R.string.view_ring_layout
            RingOption.SMALLER -> R.string.view_ring_smaller
            RingOption.LARGER -> R.string.view_ring_larger
            RingOption.DETAIL -> R.string.view_ring_details
            RingOption.SORT -> R.string.menu_sort
            RingOption.TITLE_LINES -> R.string.view_ring_title_lines
            RingOption.FOLDERS_FIRST -> R.string.sort_folders_first
            RingOption.SECTIONS -> R.string.view_ring_sections
        }
    )

    /** [option]'s name in Settings: the tab-only ones say their tab. */
    fun settingName(option: RingOption): String = when (option) {
        RingOption.FOLDERS_FIRST -> context.getString(R.string.view_ring_folders_first_tab)
        RingOption.SECTIONS -> context.getString(R.string.view_ring_sections_tab)
        else -> nameOf(option)
    }

    private fun toggle(option: RingOption, icon: Int, name: String, now: String, next: String) =
        ViewRingOverlay.Item(option, icon, name, ringForms(listOf(now), listOf(next)))

    /** Smaller or Larger: [view]'s size now and [to]'s, or the size alone, grayed, at the end. */
    private fun step(option: RingOption, icon: Int, name: String, view: TabView, columns: Int, to: TabView?) =
        ViewRingOverlay.Item(option, icon, name, ringForms(size(view, columns), to?.let { size(it, it.columns) }), enabled = to != null)

    /** A grid's [columns] (by rows, when set) or a list's density, fullest first. */
    private fun size(view: TabView, columns: Int): List<String> = when {
        !view.grid -> density(view.density)
        view.rows > 0 -> listOf(context.getString(R.string.view_ring_grid, columns, view.rows), context.getString(R.string.view_ring_grid_short, columns, view.rows))
        else -> listOf(context.resources.getQuantityString(R.plurals.view_ring_columns, columns, columns))
    }

    private fun layout(grid: Boolean) = context.getString(if (grid) R.string.layout_grid else R.string.layout_list)

    private fun onOff(on: Boolean) = context.getString(if (on) R.string.view_ring_on else R.string.view_ring_off)

    private fun density(density: ListDensity): List<String> {
        val full = TabViewDialogs.densityLabel(context, density)
        return if (density == ListDensity.EXTRA_LARGE) listOf(full, context.getString(R.string.density_extra_large_short)) else listOf(full)
    }

    private fun detail(detail: DetailDisplay): List<String> {
        val full = TabViewDialogs.detailLabel(context, detail)
        return if (detail == DetailDisplay.COMFORTABLE) listOf(full, context.getString(R.string.detail_comfortable_short)) else listOf(full)
    }

    private fun title(title: TitleDisplay): List<String> = when (title) {
        TitleDisplay.HIDDEN -> listOf(R.string.title_hidden)
        TitleDisplay.SINGLE -> listOf(R.string.title_single, R.string.title_single_short)
        TitleDisplay.MIDDLE -> listOf(R.string.title_middle, R.string.title_middle_short)
        TitleDisplay.DOUBLE -> listOf(R.string.title_double, R.string.title_double_short)
        TitleDisplay.BOUNCE -> listOf(R.string.title_bounce)
        TitleDisplay.MARQUEE -> listOf(R.string.title_marquee)
    }.map(context::getString)

    /** A sort level, fullest first: "Year newest first", "Year newest", then with the key short ("Dir. LTR"); Name goes ↑ or ↓. */
    private fun sort(level: SortLevel): List<String> {
        val key = context.getString(SortDialog.nameOf(level.key))
        val short = context.getString(shortKey(level.key))
        val full = if (level.key == SortKey.NAME) arrow(level) else context.getString(SortDialog.directionOf(level))
        val brief = context.getString(shortDirection(level))
        return listOf("$key $full", "$key $brief", "$short $brief")
    }

    private fun arrow(level: SortLevel) = context.getString(if (level.flipped) R.string.sort_down else R.string.sort_up)

    private fun shortKey(key: SortKey): Int = when (key) {
        SortKey.ACCESSED -> R.string.sort_accessed_short
        SortKey.LENGTH -> R.string.sort_length_short
        SortKey.TYPE -> R.string.sort_type_short
        SortKey.DIRECTION -> R.string.sort_direction_short
        else -> SortDialog.nameOf(key)
    }

    private fun shortDirection(level: SortLevel): Int {
        val (first, second) = when (level.key) {
            SortKey.NAME -> R.string.sort_up to R.string.sort_down
            SortKey.YEAR, SortKey.ADDED -> R.string.sort_newest_short to R.string.sort_oldest_short
            SortKey.ACCESSED -> R.string.sort_recent_short to R.string.sort_oldest_short
            SortKey.SIZE -> R.string.sort_largest_short to R.string.sort_smallest_short
            SortKey.LENGTH -> R.string.sort_longest_short to R.string.sort_shortest_short
            SortKey.TYPE -> R.string.sort_archives_short to R.string.sort_images_short
            SortKey.DIRECTION -> R.string.sort_ltr_short to R.string.sort_rtl_short
        }
        return if (level.flipped) second else first
    }

    companion object {
        /** [option]'s icon; Layout's shows the layout now ([grid]). */
        fun iconOf(option: RingOption, grid: Boolean): Int = when (option) {
            RingOption.LAYOUT -> if (grid) R.drawable.ic_grid_view else R.drawable.ic_view_list
            RingOption.SMALLER -> R.drawable.ic_zoom_out
            RingOption.LARGER -> R.drawable.ic_zoom_in
            RingOption.DETAIL -> R.drawable.ic_info
            RingOption.SORT -> R.drawable.ic_sort
            RingOption.TITLE_LINES -> R.drawable.ic_title
            RingOption.FOLDERS_FIRST -> R.drawable.ic_folder
            RingOption.SECTIONS -> R.drawable.ic_view_agenda
        }
    }
}
