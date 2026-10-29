package com.infinity.comicreader.data

/**
 * An item of the selection's ⋮ menu. Those not [ready] are kept in the setting's order
 * but never offered until their work is built.
 */
enum class MoreAction(val key: String, val ready: Boolean = true) {
    MARK_READ("mark_read"),
    MARK_UNREAD("mark_unread"),
    MARK_ALL_READ("mark_all_read"),
    MARK_ALL_UNREAD("mark_all_unread"),
    SET_STARTUP("set_startup"),
    SHOW_IN_FOLDER("show_in_folder"),
    BOOK_INFO("book_info"),
    NAME_CHARSET("name_charset"),
    COMPACT("compact", ready = false),
    MAKE_COMPATIBLE("make_compatible", ready = false),
    PEEK("peek");

    companion object {
        fun of(key: String): MoreAction? = entries.firstOrNull { it.key == key }

        /** Shown unless turned off: all but Peek. */
        val SHOWN_BY_DEFAULT: Set<MoreAction> = entries.toSet() - PEEK
    }
}

/**
 * The selection's ⋮ menu items in [order], each [shown] or not. Stored as
 * `mark_read,-peek,…`: a leading `-` marks a hidden one.
 */
data class MoreActions(val order: List<MoreAction>, val shown: Set<MoreAction>) {

    /** The items the setting lists: those [MoreAction.ready], in order. */
    val offered: List<MoreAction> get() = order.filter { it.ready }

    /** The items to offer, in order. */
    val visible: List<MoreAction> get() = offered.filter { it in shown }

    fun encode(): String = order.joinToString(",") { if (it in shown) it.key else "-${it.key}" }

    /** [action] shown or hidden. */
    fun with(action: MoreAction, show: Boolean) = copy(shown = if (show) shown + action else shown - action)

    /** The offered item at [from] moved to [to] among the offered ones, the others closing up; unready items keep their places. */
    fun moved(from: Int, to: Int): MoreActions {
        val list = offered
        if (from !in list.indices || to !in list.indices || from == to) return this
        val reordered = list.toMutableList().apply { add(to, removeAt(from)) }
        val slots = order.indices.filter { order[it].ready }
        val out = order.toMutableList()
        for ((i, slot) in slots.withIndex()) out[slot] = reordered[i]
        return copy(order = out)
    }

    companion object {
        val DEFAULT = MoreActions(MoreAction.entries, MoreAction.SHOWN_BY_DEFAULT)

        /** Reads [encode]'s text; unknown names and repeats are dropped, items it lacks follow as shown by default. */
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
            for (action in MoreAction.entries) if (action !in order) {
                order.add(action)
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
