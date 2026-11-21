package com.infinity.comicreader.data

/** What paged reading in portrait does for a wide page shown whole. */
enum class WidePages(val key: String) {
    /** Shown as it is, across the portrait screen. */
    NOTHING("nothing"),

    /** The screen turns to landscape while it shows. */
    ORIENTATION("orientation"),

    /** The picture is drawn turned a quarter to fill the screen, which stays as it is. */
    IMAGE("image");

    companion object {
        /** The choice stored as [key]; rotate image when unknown. */
        fun of(key: String?): WidePages = entries.firstOrNull { it.key == key } ?: IMAGE
    }
}
