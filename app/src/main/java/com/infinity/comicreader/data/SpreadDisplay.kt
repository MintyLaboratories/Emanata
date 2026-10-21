package com.infinity.comicreader.data

/** How paged reading shows a spread in one file: as its two halves, or whole. */
enum class SpreadDisplay(val key: String) {
    /** Each half on its own screen, a page number apiece. */
    SPLIT("split"),

    /** The whole spread on one screen. */
    MERGE("merge");

    companion object {
        /** The display stored as [key]; merge when unknown. */
        fun of(key: String?): SpreadDisplay = entries.firstOrNull { it.key == key } ?: MERGE
    }
}
