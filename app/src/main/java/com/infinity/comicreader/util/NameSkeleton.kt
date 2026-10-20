package com.infinity.comicreader.util

import java.text.Normalizer
import java.util.Locale

/**
 * The shape of a file name with its numbers masked: the stem in NFC, lowercased,
 * every ASCII digit run replaced by `#` and whitespace collapsed, so
 * `Page 007.jpg` and `page 12.png` share the skeleton `page #`.
 */
object NameSkeleton {
    private val DIGITS = Regex("[0-9]+")
    private val SPACES = Regex("\\s+")

    fun of(name: String): String {
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        return Normalizer.normalize(stem, Normalizer.Form.NFC).lowercase(Locale.ROOT)
            .replace(DIGITS, "#")
            .replace(SPACES, " ")
            .trim()
    }

    /** True when the skeleton holds a masked number. */
    fun isNumbered(skeleton: String): Boolean = '#' in skeleton

    /**
     * The numbered skeleton most of [names] share, or null when no numbered skeleton
     * covers more than half of them.
     */
    fun dominant(names: Collection<String>): String? {
        if (names.isEmpty()) return null
        val (skeleton, count) = names.groupingBy { of(it) }.eachCount()
            .filterKeys { isNumbered(it) }
            .maxByOrNull { it.value }
            ?.toPair() ?: return null
        return if (count * 2 > names.size) skeleton else null
    }
}
