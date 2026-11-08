package com.infinity.comicreader.data

/** An action of the view ring a long press on the grid/list button opens; the button's tap runs one too. */
enum class RingOption(val key: String) {
    /** Grid or list. */
    LAYOUT("layout"),

    /** More grid columns (and rows, when set), or a smaller list density. */
    SMALLER("smaller"),

    /** Fewer grid columns (and rows, when set), or a larger list density. */
    LARGER("larger"),

    /** Detail display: Hidden, Comfortable, Dense in turn. */
    DETAIL("detail"),

    /** The first sort level's direction. */
    SORT("sort"),

    /** How titles show ([TitleDisplay]), in turn; the same in every tab. */
    TITLE_LINES("title_lines"),

    /** Folders first, on or off (Browse). */
    FOLDERS_FIRST("folders_first"),

    /** Favorites under Libraries, Sets and Comics, on or off (Favorites). */
    SECTIONS("sections");

    /** Whether this shows in [tab]: Folders first in Browse only, Sections in Favorites only, the rest everywhere. */
    fun appliesTo(tab: String): Boolean = when (this) {
        FOLDERS_FIRST -> tab == Prefs.TAB_BROWSE
        SECTIONS -> tab == Prefs.TAB_FAVORITES
        else -> true
    }

    companion object {
        fun of(key: String?): RingOption? = entries.firstOrNull { it.key == key }
    }
}

/**
 * The view ring's options in [order], each [shown] or not; [MIN] to [MAX] are shown. Stored
 * as `smaller,larger,…,-layout`: a leading `-` marks a hidden one.
 */
data class RingOptions(val order: List<RingOption>, val shown: Set<RingOption>) {

    /** The options shown, in order, whatever the tab. */
    val visible: List<RingOption> get() = order.filter { it in shown }

    /** The options [tab] shows, in order: those that apply to it ([RingOption.appliesTo]). */
    fun forTab(tab: String): List<RingOption> = visible.filter { it.appliesTo(tab) }

    fun encode(): String = order.joinToString(",") { if (it in shown) it.key else "-${it.key}" }

    /** [option] shown or hidden; unchanged when that would leave [MIN]..[MAX] shown. */
    fun with(option: RingOption, show: Boolean): RingOptions {
        val next = if (show) shown + option else shown - option
        return if (next.size in MIN..MAX) copy(shown = next) else this
    }

    /** The option at [from] moved to [to], the others closing up. */
    fun moved(from: Int, to: Int): RingOptions {
        if (from !in order.indices || to !in order.indices || from == to) return this
        return copy(order = order.toMutableList().apply { add(to, removeAt(from)) })
    }

    companion object {
        const val MIN = 3
        const val MAX = 6

        /**
         * Smaller, Larger, Details, Sort, Folders first (Browse) and Sections (Favorites)
         * shown; Layout, which the button itself toggles, and Title lines hidden.
         */
        val DEFAULT = RingOptions(
            listOf(
                RingOption.SMALLER, RingOption.LARGER, RingOption.DETAIL, RingOption.SORT,
                RingOption.FOLDERS_FIRST, RingOption.SECTIONS, RingOption.LAYOUT, RingOption.TITLE_LINES
            ),
            setOf(RingOption.SMALLER, RingOption.LARGER, RingOption.DETAIL, RingOption.SORT, RingOption.FOLDERS_FIRST, RingOption.SECTIONS)
        )

        /** Reads [encode]'s text; unknown names and repeats are dropped, options it lacks follow, hidden; [DEFAULT] unless [MIN]..[MAX] are shown. */
        fun decode(text: String?): RingOptions {
            if (text.isNullOrBlank()) return DEFAULT
            val order = ArrayList<RingOption>()
            val shown = HashSet<RingOption>()
            for (part in text.split(',')) {
                val hidden = part.trim().startsWith("-")
                val option = RingOption.of(part.trim().removePrefix("-")) ?: continue
                if (option in order) continue
                order.add(option)
                if (!hidden) shown.add(option)
            }
            for (option in RingOption.entries) if (option !in order) order.add(option)
            return if (shown.size in MIN..MAX) RingOptions(order, shown) else DEFAULT
        }
    }
}
