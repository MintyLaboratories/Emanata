package com.infinity.comicreader.ai

/**
 * Which of the two default chains a book runs ([AiPicks.of]): [MANGA] for black-and-white
 * pages with dot patterns, [OTHER] for the rest (color, and black-and-white without dots).
 * Stored by [key].
 */
enum class ChainKind(val key: String) {
    MANGA("manga"),
    OTHER("other");

    companion object {
        /** The kind stored as [key]; null for none or one no kind has. */
        fun of(key: String?): ChainKind? = entries.firstOrNull { it.key == key }

        /**
         * The kind a book runs: its [own] choice, else its [folder]'s, else [MANGA] for a book
         * read right to left and [OTHER] for the rest.
         */
        fun resolve(own: ChainKind?, folder: ChainKind?, rightToLeft: Boolean): ChainKind =
            own ?: folder ?: if (rightToLeft) MANGA else OTHER
    }
}
