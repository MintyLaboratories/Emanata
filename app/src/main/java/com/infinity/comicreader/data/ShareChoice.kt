package com.infinity.comicreader.data

/** What sharing a book sends: any of its [title], its [cover] and the [file] itself. */
data class ShareChoice(val title: Boolean, val cover: Boolean, val file: Boolean) {
    val any: Boolean get() = title || cover || file
}
