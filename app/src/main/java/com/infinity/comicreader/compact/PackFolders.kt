package com.infinity.comicreader.compact

import com.infinity.comicreader.reader.ArchiveEntry
import com.infinity.comicreader.reader.ArchiveReader
import com.infinity.comicreader.util.Formats
import com.infinity.comicreader.util.NaturalOrder
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.Locale

/** Which folders Pack takes: those holding images only, OS litter aside. */
object PackFolders {
    /** Why a folder cannot be packed. */
    enum class Why { SUBFOLDERS, OTHER_FILES, NO_IMAGES, UNREADABLE }

    /** What a folder holds: the [images] to pack in natural order, else [why] not. */
    class Contents(val images: List<File>, val why: Why?)

    /** OS litter beside the images, left out and removed with the folder. */
    fun isJunk(name: String): Boolean = FlatNames.isJunk(name) || name.lowercase(Locale.ROOT) in MORE_JUNK

    /** An OS litter folder (`__MACOSX/`) or a dot-folder (`.thumbnails/`), which the reader never shows: removed, whole, with the folder. */
    fun isJunkFolder(name: String): Boolean = FlatNames.isJunk("$name/-")

    /** Why a folder listing [names] (each with true for a folder) cannot be packed; null when it can. */
    fun why(names: List<Pair<String, Boolean>>): Why? {
        if (names.any { it.second && !isJunkFolder(it.first) }) return Why.SUBFOLDERS
        val files = names.filter { !it.second }.map { it.first }.filterNot(::isJunk)
        if (files.any { !Formats.isImageName(it) }) return Why.OTHER_FILES
        return if (files.isEmpty()) Why.NO_IMAGES else null
    }

    /** What [dir] holds now. Blocking. */
    fun of(dir: File): Contents {
        val children = dir.listFiles() ?: return Contents(emptyList(), Why.UNREADABLE)
        val why = why(children.map { it.name to !it.isFile })
        if (why != null) return Contents(emptyList(), why)
        val images = children.filter { it.isFile && !isJunk(it.name) }.sortedWith(compareBy(NaturalOrder) { it.name })
        return Contents(images, null)
    }

    /** The first of [dirs] that cannot be packed, with why; null when all can. Blocking. */
    fun firstBlocked(dirs: List<File>): Pair<File, Why>? {
        for (dir in dirs) of(dir).why?.let { return dir to it }
        return null
    }

    /** `.nomedia` and the thumbnail caches some systems leave. */
    private val MORE_JUNK = setOf(".nomedia", "ehthumbs.db", "ehthumbs_vista.db")
}

/** Reads [files], a folder's images, as the entries of an archive named by their file names. */
class FolderReader(private val files: List<File>) : ArchiveReader {
    override val entries: List<ArchiveEntry> = files.mapIndexed { i, f -> ArchiveEntry(f.name, f.length(), i) }

    override fun <T> read(entry: ArchiveEntry, block: (InputStream) -> T): T = FileInputStream(looseFile(entry)).use(block)

    /** The file [entry] is. */
    override fun looseFile(entry: ArchiveEntry): File = files[entry.index]

    override fun close() = Unit
}
