package com.infinity.comicreader.data

/** What spreading two fingers on a comic or folder in the library does. */
enum class PeekMode(val key: String) {
    OFF("off"),

    /** Grows a grid of its pages (a folder's: of the covers inside); it stays open however far the fingers spread. */
    PEEK("peek"),

    /** As [PEEK], and spreading all the way opens the book or folder. */
    PEEK_OPEN("peek_open");

    companion object {
        fun of(key: String?): PeekMode = entries.firstOrNull { it.key == key } ?: PEEK_OPEN
    }
}
