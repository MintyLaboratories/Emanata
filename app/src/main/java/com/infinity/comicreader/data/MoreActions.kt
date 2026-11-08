package com.infinity.comicreader.data

/** An item of the selection's ⋮ menu. */
enum class MoreAction(val key: String) {
    CONTINUE_READING("continue_reading"),
    MARK_READ("mark_read"),
    MARK_UNREAD("mark_unread"),
    MARK_ALL_READ("mark_all_read"),
    MARK_ALL_UNREAD("mark_all_unread"),
    SET_STARTUP("set_startup"),
    SHOW_IN_FOLDER("show_in_folder"),
    BOOK_INFO("book_info"),
    NAME_CHARSET("name_charset"),
    COMPACT("compact"),
    MAKE_COMPATIBLE("make_compatible"),

    /** Archives that are not ZIP (CBR, CB7, CBT), and folders holding them. */
    REPACK_CBZ("repack_cbz"),

    /** Folders of loose images only. */
    PACK("pack"),
    PACK_COMPACTED("pack_compacted"),

    /** History only. */
    REMOVE_FROM_HISTORY("remove_from_history"),

    /** Bookmarks only. */
    REMOVE_BOOKMARK("remove_bookmark"),
    PEEK("peek");

    companion object {
        fun of(key: String): MoreAction? = entries.firstOrNull { it.key == key }

        /** Shown unless turned off: all but Peek. */
        val SHOWN_BY_DEFAULT: Set<MoreAction> = entries.toSet() - PEEK

        /** The items that write each book selected anew. */
        val REBUILDS: Set<MoreAction> = setOf(COMPACT, MAKE_COMPATIBLE, REPACK_CBZ)

        /** The items that write a new book, which the menu holds together under "Convert ›" when two or more of them apply. */
        val CONVERT_GROUP: Set<MoreAction> = setOf(PACK, PACK_COMPACTED, COMPACT, MAKE_COMPATIBLE, REPACK_CBZ)
    }
}

/**
 * The selection's ⋮ menu items in [order], each [shown] or not. Stored as
 * `mark_read,-peek,…`: a leading `-` marks a hidden one.
 */
data class MoreActions(val order: List<MoreAction>, val shown: Set<MoreAction>) {

    /** The items the setting lists, in order. */
    val offered: List<MoreAction> get() = order

    /** The items to offer, in order. */
    val visible: List<MoreAction> get() = order.filter { it in shown }

    fun encode(): String = order.joinToString(",") { if (it in shown) it.key else "-${it.key}" }

    /** [action] shown or hidden. */
    fun with(action: MoreAction, show: Boolean) = copy(shown = if (show) shown + action else shown - action)

    /** The item at [from] moved to [to], the others closing up. */
    fun moved(from: Int, to: Int): MoreActions {
        if (from !in order.indices || to !in order.indices || from == to) return this
        return copy(order = order.toMutableList().apply { add(to, removeAt(from)) })
    }

    companion object {
        val DEFAULT = MoreActions(MoreAction.entries, MoreAction.SHOWN_BY_DEFAULT)

        /**
         * Reads [encode]'s text; unknown names and repeats are dropped, and each item it
         * lacks, shown by default, goes after the one before it by default (first when none).
         */
        fun decode(text: String?): MoreActions {
            if (text.isNullOrBlank()) return DEFAULT
            val order = ArrayList<MoreAction>()
            val shown = HashSet<MoreAction>()
            for (part in text.split(',')) {
                val hidden = part.trim().startsWith("-")
                val action = MoreAction.of(part.trim().removePrefix("-")) ?: continue
                if (action in order) continue
                order.add(action)
                if (!hidden) shown.add(action)
            }
            for ((i, action) in MoreAction.entries.withIndex()) if (action !in order) {
                val before = MoreAction.entries.subList(0, i).lastOrNull { it in order }
                order.add(if (before == null) 0 else order.indexOf(before) + 1, action)
                if (action in MoreAction.SHOWN_BY_DEFAULT) shown.add(action)
            }
            return MoreActions(order, shown)
        }
    }
}

/** What the floating button does while items are selected. */
enum class FabAction(val key: String) {
    NONE("none"),
    OPEN("open"),
    MOVE("move"),
    COPY("copy"),
    DELETE("delete"),
    SHARE("share"),
    FAVORITE("favorite");

    companion object {
        fun of(key: String?, default: FabAction): FabAction = entries.firstOrNull { it.key == key } ?: default

        /** The choices with one item selected. */
        val FOR_ONE = entries.toList()

        /** The choices with several selected: Open takes one. */
        val FOR_MANY = entries - OPEN
    }
}
