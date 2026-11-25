package minty.emanata.data

/** Where Browse opens when the app starts. */
object StartupLocation {
    const val LAST = "last"
    const val FIRST_FAVORITE = "first_favorite"
    const val RECENT_FAVORITE = "recent_favorite"
    const val CUSTOM = "custom"

    /**
     * The folder for [choice], or the last folder seen when that one is gone, or null;
     * only the lists [choice] needs are read.
     * - [LAST]: [last];
     * - [FIRST_FAVORITE]: the first folder of [shown], the favorites in Favorites tab order;
     * - [RECENT_FAVORITE]: the favorite folder holding the most recently read book,
     *   else the most recently added favorite folder ([added] is newest first);
     * - [CUSTOM]: [custom].
     *
     * [last] and [custom] are kept while [reachable] (by default [isDir]) says they or a
     * parent within their root exist, so Browse can go to that parent and say the folder is gone.
     */
    fun resolve(
        choice: String,
        custom: String?,
        last: String?,
        shown: () -> List<String>,
        added: () -> List<String>,
        history: () -> List<String>,
        reachable: ((String) -> Boolean)? = null,
        isDir: (String) -> Boolean
    ): String? {
        val keep = reachable ?: isDir
        val picked = when (choice) {
            FIRST_FAVORITE -> shown().firstOrNull(isDir)
            RECENT_FAVORITE -> {
                val folders = added().filter(isDir)
                history().firstNotNullOfOrNull { book ->
                    folders.filter { isUnder(book, it) }.maxByOrNull { it.length }
                } ?: folders.firstOrNull()
            }
            CUSTOM -> custom?.takeIf(keep)
            else -> null
        }
        return picked ?: last?.takeIf(keep)
    }

    private fun isUnder(path: String, dir: String): Boolean =
        path.length > dir.length && path.startsWith(dir) && isSeparator(path[dir.length])
}
