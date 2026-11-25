package minty.emanata.debug

import minty.emanata.net.Remotes
import minty.emanata.util.Formats

/** Finds books on shares for the debug checks, naming none in any log. */
internal object DebugBooks {
    /** The share id that stands for the first share kept. */
    const val FIRST = "first"

    /** The share [id] names, [FIRST] standing for the first share kept; null when there is none. */
    fun shareId(id: String?): String? = if (id == FIRST) Remotes.shares().firstOrNull()?.id else id

    /**
     * The first book found in [dir], breadth first, at most [depth] folders down (with
     * [largest], the largest in the first folder holding any); null when none.
     */
    fun firstBook(dir: String, depth: Int, largest: Boolean): String? {
        var level = listOf(dir)
        repeat(depth + 1) {
            val next = ArrayList<String>()
            for (d in level) {
                val entries = runCatching { Remotes.list(d) }.getOrNull() ?: continue
                val books = entries.filter { !it.isDirectory && Formats.isArchiveName(it.name) }
                (if (largest) books.maxByOrNull { it.length() } else books.firstOrNull())?.let { return it.absolutePath }
                entries.filter { it.isDirectory && !it.name.startsWith(".") }.take(4).mapTo(next) { it.absolutePath }
            }
            level = next
        }
        return null
    }
}
