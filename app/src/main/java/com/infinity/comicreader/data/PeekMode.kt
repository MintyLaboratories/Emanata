package com.infinity.comicreader.data

/** What spreading two fingers on a comic in the library does. */
enum class PeekMode(val key: String) {
    OFF("off"),

    /** Grows a grid of its pages; it stays open however far the fingers spread. */
    PEEK("peek"),

    /** As [PEEK], and spreading all the way opens the book. */
    PEEK_OPEN("peek_open");

    companion object {
        fun of(key: String?): PeekMode = entries.firstOrNull { it.key == key } ?: PEEK_OPEN
    }
}
