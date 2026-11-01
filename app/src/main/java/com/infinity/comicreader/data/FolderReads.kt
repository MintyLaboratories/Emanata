package com.infinity.comicreader.data

import com.infinity.comicreader.net.Remotes
import com.infinity.comicreader.util.Formats
import java.io.File

/**
 * "Books read" for folders of loose images: the folder counts as one book read once every
 * image the reader shows in it is read ([Library.setFolderRead]), and no longer once one is
 * not. Blocking: it lists the folder and finds its images' IDs (known ones, mostly).
 */
object FolderReads {
    /** True when [path] is a loose image: counted with its folder, never alone. */
    fun isLooseImage(path: String): Boolean = Formats.typeOf(File(path).name) == EntryType.IMAGE

    /** The images the reader shows in [dir], as it lists them beside one opened there; null when it can't be listed. */
    fun images(dir: File): List<File>? =
        dir.listFiles()?.filter { it.isFile && !it.name.startsWith(".") && isLooseImage(it.name) }

    /**
     * Counts folder [dir] as read, or no longer, by its [images] (listed when null); unchanged
     * when it can't be listed. An image whose last ID ([BookIds.last]) is not read settles it
     * at once, no file looked at; else those never found are found, up to the first not read,
     * and only a folder then all read is checked file by file.
     */
    fun update(library: Library, dir: File, images: List<File>? = null) {
        val all = images ?: images(dir) ?: return
        val read = library.readSet()
        val last = all.map { BookIds.last(it.absolutePath) }
        fun isRead(f: File) = BookIds.find(f)?.let { it in read } == true
        val done = all.isNotEmpty() && last.none { it != null && it !in read } &&
            all.indices.filter { last[it] == null }.all { isRead(all[it]) } && all.all(::isRead)
        library.setFolderRead(dir.absolutePath, done)
    }

    /** Updates the folders of the loose images among [books]. */
    fun updateFor(library: Library, books: Collection<BookRef>) {
        val folders = books.filter { isLooseImage(it.path) }.mapNotNull { File(it.path).parent }.distinct()
        for (folder in folders) update(library, Remotes.file(folder))
    }
}
