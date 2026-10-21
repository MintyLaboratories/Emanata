package com.infinity.comicreader.data

/** When paged reading shows two pages side by side. */
enum class TwoPage(val key: String) {
    OFF("off"),
    ON("on"),
    LANDSCAPE("landscape");

    /** True when pages pair up on a screen that is [landscape] or not. */
    fun spread(landscape: Boolean): Boolean = this == ON || this == LANDSCAPE && landscape

    companion object {
        /** The mode stored as [key]; off when unknown. */
        fun of(key: String?): TwoPage = entries.firstOrNull { it.key == key } ?: OFF

        /** The mode an older on/off switch meant. */
        fun of(on: Boolean): TwoPage = if (on) ON else OFF
    }
}
