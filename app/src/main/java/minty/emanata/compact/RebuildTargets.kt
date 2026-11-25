package minty.emanata.compact

import minty.emanata.util.Formats
import minty.emanata.util.NaturalOrder
import java.io.File
import java.util.concurrent.CancellationException

/** The books Compact and Make compatible rebuild for a selection of books and folders on the device. */
object RebuildTargets {
    /**
     * The paths of [books], then of every archive inside [folders] and their subfolders,
     * each folder's in natural order, hidden files and folders (`.name`) and links left out;
     * each once. Blocking I/O; throws [CancellationException] once [stopped].
     */
    fun of(books: List<File>, folders: List<File>, stopped: () -> Boolean = { false }): List<String> {
        val out = LinkedHashSet<String>()
        books.mapTo(out) { it.absolutePath }
        for (dir in folders) walk(dir, out, HashSet(), stopped)
        return out.toList()
    }

    private fun walk(dir: File, out: MutableSet<String>, seen: MutableSet<String>, stopped: () -> Boolean) {
        if (stopped()) throw CancellationException("Stopped")
        // A folder reached twice (through a link) is walked once.
        if (!seen.add(runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath))) return
        val children = dir.listFiles()?.filter { !it.name.startsWith(".") }?.sortedWith(compareBy(NaturalOrder) { it.name }) ?: return
        for (f in children) {
            if (isLink(f)) continue
            if (f.isDirectory) walk(f, out, seen, stopped) else if (f.isFile && Formats.isArchiveName(f.name)) out += f.absolutePath
        }
    }

    private fun isLink(f: File): Boolean = runCatching { java.nio.file.Files.isSymbolicLink(f.toPath()) }.getOrDefault(false)
}
