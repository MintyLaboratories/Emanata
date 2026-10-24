package com.infinity.comicreader.files

/** The item Move or Copy picked up, waiting to be pasted: one at a time, kept while the app runs. */
object FileClipboard {
    /** An item picked up: [move] deletes it from [path] once pasted. */
    data class Clip(val path: String, val isDir: Boolean, val move: Boolean) {
        val name: String get() = java.io.File(path).name
    }

    @Volatile
    var clip: Clip? = null
}
