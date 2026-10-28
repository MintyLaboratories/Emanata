package com.infinity.comicreader.data

import com.infinity.comicreader.reader.ComicInfo
import com.infinity.comicreader.util.Hashes
import java.text.Normalizer
import java.util.Locale

/**
 * What tells a re-encoded copy of a book from another book, when its Book ID differs:
 * [strong] when its ComicInfo identifies the book (series and number, or a title), from
 * that ComicInfo without `<Pages>` (whose per-page sizes change on re-encode) and the
 * page count; else weak, from the page names without extensions, the page count and the
 * book's file name without extension. [stems] are the page names as compared, so data
 * kept by page can follow its page into the copy ([moves]).
 */
data class Likeness(val strong: Boolean, val print: String, val stems: List<String>) {

    companion object {
        /**
         * The likeness of a book of pages [pageNames] (stored names, null where unnamed),
         * with [info] its ComicInfo (null when it has none) and [fileName] its file name.
         */
        fun of(info: ComicInfo?, pageNames: List<String?>, fileName: String): Likeness {
            val stems = pageNames.map { stemOf(it.orEmpty().substringAfterLast('/').substringAfterLast('\\')) }
            val identity = info?.takeIf { identifies(it) }
            val text = if (identity != null) {
                val fields = identity.fields
                    .filterNot { it.first.equals("Pages", ignoreCase = true) }
                    .map { (name, value) -> name.lowercase(Locale.ROOT) to normal(value) }
                    .sortedWith(compareBy({ it.first }, { it.second }))
                "strong\u0000${pageNames.size}\u0000" + fields.joinToString("\u0000") { "${it.first}=${it.second}" }
            } else {
                "weak\u0000${pageNames.size}\u0000${stemOf(fileName)}\u0000" + stems.joinToString("\u0000")
            }
            return Likeness(identity != null, Hashes.sha256Hex(text, bytes = 16), stems)
        }

        /** True when [info] says which book it is: a series and its number, or a title. */
        fun identifies(info: ComicInfo): Boolean {
            fun has(name: String) = info.fields.any { it.first.equals(name, ignoreCase = true) && it.second.isNotBlank() }
            return (has("Series") && has("Number")) || has("Title")
        }

        /** [name] without its extension, compared without regard to case, accents composed alike and spaces collapsed. */
        fun stemOf(name: String): String {
            val dot = name.lastIndexOf('.')
            val stem = if (dot > 0) name.substring(0, dot) else name
            return normal(stem).lowercase(Locale.ROOT)
        }

        private val SPACES = Regex("\\s+")

        private fun normal(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFC).trim().replace(SPACES, " ")

        /**
         * Where each page of a book of page stems [from] went in its copy of stems [to]: the
         * page of the same name where there is one, else the page at the same place, else -1.
         */
        fun moves(from: List<String>, to: List<String>): IntArray {
            val byName = HashMap<String, ArrayDeque<Int>>()
            to.forEachIndexed { i, s -> byName.getOrPut(s) { ArrayDeque() }.addLast(i) }
            val taken = BooleanArray(to.size)
            val out = IntArray(from.size) { -1 }
            from.forEachIndexed { i, s ->
                val j = byName[s]?.removeFirstOrNull() ?: return@forEachIndexed
                out[i] = j
                taken[j] = true
            }
            for (i in from.indices) if (out[i] < 0 && i < to.size && !taken[i]) {
                out[i] = i
                taken[i] = true
            }
            return out
        }
    }
}
