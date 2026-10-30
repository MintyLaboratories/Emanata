package com.infinity.comicreader.compact

import com.infinity.comicreader.util.Formats
import com.infinity.comicreader.util.NaturalOrder
import java.io.File

/** The books Compact and Make compatible rebuild for a selection of books and folders on the device. */
object RebuildTargets {
    /**
     * The paths of [books], then of every archive inside [folders] and their subfolders,
     * each folder's in natural order, hidden files and folders (`.name`) and links left out;
     * each once. Blocking I/O.
     */
    fun of(books: List<File>, folders: List<File>): List<String> {
        val out = LinkedHashSet<String>()
        books.mapTo(out) { it.absolutePath }
        for (dir in folders) walk(dir, out, HashSet())
        return out.toList()
    }

    private fun walk(dir: File, out: MutableSet<String>, seen: MutableSet<String>) {
        // A folder reached twice (through a link) is walked once.
        if (!seen.add(runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath))) return
        val children = dir.listFiles()?.filter { !it.name.startsWith(".") }?.sortedWith(compareBy(NaturalOrder) { it.name }) ?: return
        for (f in children) {
            if (isLink(f)) continue
            if (f.isDirectory) walk(f, out, seen) else if (f.isFile && Formats.isArchiveName(f.name)) out += f.absolutePath
        }
    }

    private fun isLink(f: File): Boolean = runCatching { java.nio.file.Files.isSymbolicLink(f.toPath()) }.getOrDefault(false)
}
