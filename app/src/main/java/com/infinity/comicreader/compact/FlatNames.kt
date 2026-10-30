package com.infinity.comicreader.compact

import java.util.Locale

/** Names in a rebuilt archive, and of the file it becomes. */
object FlatNames {
    private val JUNK_NAMES = setOf("thumbs.db", "desktop.ini", ".ds_store")

    /** OS litter left out: `__MACOSX/`, `._*` resource forks, `Thumbs.db`, `desktop.ini`, `.DS_Store`. */
    fun isJunk(path: String): Boolean {
        val parts = parts(path)
        val name = parts.lastOrNull() ?: return true
        return parts.any { it.equals("__MACOSX", ignoreCase = true) } || name.startsWith("._") || name.lowercase(Locale.ROOT) in JUNK_NAMES
    }

    /** The file name at the end of archive path [path]. */
    fun baseName(path: String): String = parts(path).lastOrNull().orEmpty()

    /**
     * Flat names for the files at archive [paths], to be named [names] (their own, or
     * with another extension): bare when no two match ignoring case; otherwise each one
     * under a folder takes the folder's path in front (`sub_001.jxl`, folders joined by
     * `_`), and a name still taken gets ` 2`, ` 3`... after its stem.
     */
    fun of(paths: List<String>, names: List<String>): List<String> {
        require(paths.size == names.size)
        if (names.map { it.lowercase(Locale.ROOT) }.toSet().size == names.size) return names
        val prefixed = paths.indices.map { i ->
            val folders = parts(paths[i]).dropLast(1)
            if (folders.isEmpty()) names[i] else folders.joinToString("_") + "_" + names[i]
        }
        val used = HashSet<String>()
        return prefixed.map { name ->
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
