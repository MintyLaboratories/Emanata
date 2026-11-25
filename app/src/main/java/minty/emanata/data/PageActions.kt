package minty.emanata.data

/** A row of the reader's page long-press menu, below its fixed row of Share, Save, Crop and Bookmark. */
enum class PageAction(val key: String) {
    HIDE("hide"),

    /** The page becomes the book's cover in the library ([Library.coverPage]). */
    SET_COVER("set_cover"),
    PAGE_INFO("page_info"),

    /** "Show alone" for a page sharing a screen, "Pair with next" for one alone ([SpreadOverrides]). */
    SPREAD("spread");

    companion object {
        fun of(key: String): PageAction? = entries.firstOrNull { it.key == key }
    }
}

/**
 * The page menu's rows in [order], each [shown] or not. Stored as `hide,-page_info,…`:
 * a leading `-` marks a hidden one.
 */
data class PageActions(val order: List<PageAction>, val shown: Set<PageAction>) {

    /** The rows to offer, in order. */
    val visible: List<PageAction> get() = order.filter { it in shown }

    fun encode(): String = order.joinToString(",") { if (it in shown) it.key else "-${it.key}" }

    /** [action] shown or hidden. */
    fun with(action: PageAction, show: Boolean) = copy(shown = if (show) shown + action else shown - action)

    /** The row at [from] moved to [to], the others closing up. */
    fun moved(from: Int, to: Int): PageActions {
        if (from !in order.indices || to !in order.indices || from == to) return this
        return copy(order = order.toMutableList().apply { add(to, removeAt(from)) })
    }

    companion object {
        val DEFAULT = PageActions(PageAction.entries, PageAction.entries.toSet())

        /** Reads [encode]'s text; unknown names and repeats are dropped, and rows it lacks go last, shown. */
        fun decode(text: String?): PageActions {
            if (text.isNullOrBlank()) return DEFAULT
            val order = ArrayList<PageAction>()
            val shown = HashSet<PageAction>()
            for (part in text.split(',')) {
                val hidden = part.trim().startsWith("-")
                val action = PageAction.of(part.trim().removePrefix("-")) ?: continue
                if (action in order) continue
                order.add(action)
                if (!hidden) shown.add(action)
            }
            for (action in PageAction.entries) if (action !in order) {
                order.add(action)
                shown.add(action)
            }
            return PageActions(order, shown)
        }
    }
}
