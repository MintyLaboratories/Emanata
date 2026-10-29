package com.infinity.comicreader.files

/** The items Move or Copy picked up, waiting to be pasted: one pick at a time, kept while the app runs. */
object FileClipboard {
    /** An item picked up, at [path]. */
    data class Item(val path: String, val isDir: Boolean) {
        val name: String get() = java.io.File(path).name
    }

    /** [items] picked up together, from the device and shares alike: [move] deletes each from where it was once pasted. */
    data class Clip(val items: List<Item>, val move: Boolean) {
        /** The name of the first item, the only one when [count] is 1. */
        val name: String get() = items.first().name

        val count: Int get() = items.size
    }

    @Volatile
    var clip: Clip? = null

    /** [items] once each, without those inside a folder among them: the folder takes them along. */
    fun outermost(items: List<Item>): List<Item> {
        val folders = items.filter { it.isDir }.map { it.path }
        return items.distinctBy { it.path }.filter { item -> folders.none { it != item.path && Transfer.isWithin(item.path, it) } }
    }
}
