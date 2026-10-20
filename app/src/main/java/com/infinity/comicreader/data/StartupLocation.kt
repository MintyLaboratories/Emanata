package com.infinity.comicreader.data

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
     */
    fun resolve(
        choice: String,
        custom: String?,
        last: String?,
        shown: () -> List<String>,
        added: () -> List<String>,
        history: () -> List<String>,
        isDir: (String) -> Boolean
    ): String? {
        val picked = when (choice) {
            FIRST_FAVORITE -> shown().firstOrNull(isDir)
            RECENT_FAVORITE -> {
                val folders = added().filter(isDir)
                history().firstNotNullOfOrNull { book ->
                    folders.filter { isUnder(book, it) }.maxByOrNull { it.length }
                } ?: folders.firstOrNull()
            }
            CUSTOM -> custom?.takeIf(isDir)
            else -> null
        }
        return picked ?: last?.takeIf(isDir)
    }

    private fun isUnder(path: String, dir: String): Boolean =
        path.length > dir.length && path.startsWith(dir) && isSeparator(path[dir.length])
}
