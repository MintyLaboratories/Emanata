package com.infinity.comicreader.ui

/** The series name every item of a set starts with, free of Android types. */
object TitlePrefix {
    private const val SEPARATORS = " _-.–—"

    /**
     * The longest start all [names] share that ends just after a separator (space,
     * `_`, `-`, `.`, dash) that follows no digit and holds a letter, so it is a name and
     * never cuts into a word or number: `Lumen - 001.cbz`, `Lumen - 002.cbz` share
     * `Lumen - `; `Lumen 2019-01.cbz`, `Lumen 2019-02.cbz` share `Lumen `. Null for
     * fewer than two names, when none is shared, or when a name would be left empty.
     */
    fun common(names: List<String>): String? {
        if (names.size < 2) return null
        var end = names.minOf { it.length }
        val first = names[0]
        for (name in names) {
            var i = 0
            while (i < end && name[i] == first[i]) i++
            end = i
        }
        // Back to just after the last separator inside the shared part that ends no number.
        while (end > 0) {
            while (end > 0 && first[end - 1] !in SEPARATORS) end--
            if (end >= 2 && first[end - 2].isDigit()) end-- else break
        }
        if (end == 0) return null
        val prefix = first.substring(0, end)
        if (prefix.none { it.isLetter() }) return null
        if (names.any { name -> name.substring(end).none { it.isLetterOrDigit() } }) return null
        return prefix
    }
}
