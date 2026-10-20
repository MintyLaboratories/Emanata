package com.infinity.comicreader.reader

import java.util.Locale

/**
 * Archive entries that are never pages: macOS resource forks (`__MACOSX/`, `._*`),
 * hidden dotfiles and folders (`.DS_Store`), Windows shell files and empty files.
 */
object ArchiveJunk {
    private val SHELL_FILES = setOf("thumbs.db", "ehthumbs.db", "desktop.ini")

    fun isJunk(path: String, size: Long): Boolean {
        if (size == 0L) return true
        val parts = path.split('/', '\\').filter { it.isNotEmpty() && it != "." && it != ".." }
        if (parts.any { isHidden(it) || it.equals("__MACOSX", ignoreCase = true) }) return true
        return parts.lastOrNull()?.lowercase(Locale.ROOT) in SHELL_FILES
    }

    /** A dotfile name; `...Extras` and other names opening with an ellipsis are not hidden. */
    private fun isHidden(name: String) = name.startsWith('.') && !name.startsWith("..")
}
