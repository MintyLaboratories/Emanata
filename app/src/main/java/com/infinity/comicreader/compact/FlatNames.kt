package com.infinity.comicreader.compact

import com.infinity.comicreader.util.Formats
import com.infinity.comicreader.util.NaturalOrder
import java.util.Locale

/** Names in a rebuilt archive, and of the file it becomes. */
object FlatNames {
    private val JUNK_NAMES = setOf("thumbs.db", "desktop.ini", ".ds_store")

    /**
     * Left out: OS litter (`__MACOSX/`, `._*` resource forks, `Thumbs.db`, `desktop.ini`,
     * `.DS_Store`) and whatever is in a dot-folder (`.cache/`), which the reader never shows.
     */
    fun isJunk(path: String): Boolean {
        val parts = parts(path)
        val name = parts.lastOrNull() ?: return true
        return parts.any { it.equals("__MACOSX", ignoreCase = true) } || parts.dropLast(1).any(::isDotFolder) ||
            name.startsWith("._") || name.lowercase(Locale.ROOT) in JUNK_NAMES
    }

    /** A hidden folder's name; `...Extras` and other names opening with an ellipsis are not hidden. */
    private fun isDotFolder(name: String) = name.startsWith('.') && !name.startsWith("..")

    /** The file name at the end of archive path [path]. */
    fun baseName(path: String): String = parts(path).lastOrNull().orEmpty()

    /**
     * Flat names for the files at archive [paths], to be named [names] (their own, or
     * with another extension), keeping the pages' reading order (natural order of their
     * paths): bare when no two match ignoring case and the pages read in the same order;
     * otherwise each one under a folder takes the folder's path in front (`sub_001.jxl`,
     * folders joined by `_`), a name still taken getting ` 2`, ` 3`... after its stem; and
     * should even that change the order, each page also takes its place in it in front
     * (`07_sub_001.jxl`), other files keeping their names (`ComicInfo.xml`) unless a page
     * took one.
     */
    fun of(paths: List<String>, names: List<String>): List<String> {
        require(paths.size == names.size)
        val pages = paths.indices.filter { Formats.isImageName(paths[it]) }
        if (names.map { it.lowercase(Locale.ROOT) }.toSet().size == names.size && keepsOrder(pages, paths, names)) return names
        val prefixed = paths.indices.map { i ->
            val folders = parts(paths[i]).dropLast(1)
            if (folders.isEmpty()) names[i] else folders.joinToString("_") + "_" + names[i]
        }
        val unique = distinct(prefixed, emptySet())
        if (keepsOrder(pages, paths, unique)) return unique
        val rank = IntArray(paths.size)
        pages.sortedWith(compareBy(NaturalOrder) { paths[it] }).forEachIndexed { place, i -> rank[i] = place + 1 }
        val width = pages.size.toString().length
        val numbered = unique.indices.map { i -> if (rank[i] == 0) unique[i] else rank[i].toString().padStart(width, '0') + "_" + unique[i] }
        return distinct(numbered, pages.toSet())
    }

    /** True when [pages] (places in [paths]) read in the same order named [flat]. */
    private fun keepsOrder(pages: List<Int>, paths: List<String>, flat: List<String>): Boolean =
        pages.sortedWith(compareBy(NaturalOrder) { paths[it] }) == pages.sortedWith(compareBy(NaturalOrder) { flat[it] })

    /**
     * [names] with no two matching ignoring case: those at [kept] (already distinct) as
     * they are, and of the others each name taken getting ` 2`, ` 3`... after its stem.
     */
    private fun distinct(names: List<String>, kept: Set<Int>): List<String> {
        val used = kept.mapTo(HashSet()) { names[it].lowercase(Locale.ROOT) }
        return names.mapIndexed { i, name ->
            if (i in kept) return@mapIndexed name
            var out = name
            var n = 1
            while (!used.add(out.lowercase(Locale.ROOT))) out = "${stem(name)} ${++n}${extension(name)}"
            out
        }
    }

    /** [name] with its extension made [ext] (without the dot). */
    fun withExtension(name: String, ext: String): String = "${stem(name)}.$ext"

    /** [name] without its extension. */
    fun stem(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) else name
    }

    /** [name]'s extension with its dot, or nothing. */
    private fun extension(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(dot) else ""
    }

    /**
     * The name of a book [name] rebuilt as a `.cbz` beside it: `name.cbz` unless [taken],
     * else `name (suffix).cbz`, `name (suffix 2).cbz`...
     */
    fun output(name: String, suffix: String, taken: (String) -> Boolean): String {
        val stem = stem(name)
        val plain = "$stem.cbz"
        if (!taken(plain)) return plain
        var n = 1
        while (true) {
            val candidate = if (n == 1) "$stem ($suffix).cbz" else "$stem ($suffix $n).cbz"
            if (!taken(candidate)) return candidate
            n++
        }
    }

    private fun parts(path: String): List<String> = path.split('/', '\\').filter { it.isNotEmpty() && it != "." }
}
