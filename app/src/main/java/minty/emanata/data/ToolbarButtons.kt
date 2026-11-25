package minty.emanata.data

/** A button the library toolbar can show. */
enum class ToolbarButton(val key: String) {
    SORT("sort"),
    FILTER("filter"),

    /** Adds the folder browsed to the favorites, or removes it. */
    FAVORITE("favorite"),
    SEARCH("search");

    companion object {
        fun of(key: String): ToolbarButton? = entries.firstOrNull { it.key == key }
    }
}

/**
 * The library toolbar's buttons in [order], each [shown] or not. Stored as
 * `sort,filter,-favorite,search`: a leading `-` marks a hidden one.
 */
data class ToolbarButtons(val order: List<ToolbarButton>, val shown: Set<ToolbarButton>) {

    /** The buttons to show, in order. */
    val visible: List<ToolbarButton> get() = order.filter { it in shown }

    fun encode(): String = order.joinToString(",") { if (it in shown) it.key else "-${it.key}" }

    /** [button] shown or hidden. */
    fun with(button: ToolbarButton, show: Boolean) = copy(shown = if (show) shown + button else shown - button)

    /** The button at [from] moved to [to], the others closing up. */
    fun moved(from: Int, to: Int): ToolbarButtons {
        if (from !in order.indices || to !in order.indices || from == to) return this
        val list = order.toMutableList()
        list.add(to, list.removeAt(from))
        return copy(order = list)
    }

    companion object {
        /** Sort, Filter, Favorite, Search, all shown. */
        val DEFAULT = ToolbarButtons(ToolbarButton.entries, ToolbarButton.entries.toSet())

        /** Reads [encode]'s text; unknown names and repeats are dropped, buttons it lacks follow, shown. */
        fun decode(text: String?): ToolbarButtons {
            if (text.isNullOrBlank()) return DEFAULT
            val order = ArrayList<ToolbarButton>()
            val shown = HashSet<ToolbarButton>()
            for (part in text.split(',')) {
                val hidden = part.startsWith("-")
                val button = ToolbarButton.of(part.trim().removePrefix("-")) ?: continue
                if (button in order) continue
                order.add(button)
                if (!hidden) shown.add(button)
            }
            for (button in ToolbarButton.entries) if (button !in order) {
                order.add(button)
                shown.add(button)
            }
            return ToolbarButtons(order, shown)
        }
    }
}
