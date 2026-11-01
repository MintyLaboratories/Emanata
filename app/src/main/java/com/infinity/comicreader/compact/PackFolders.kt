package com.infinity.comicreader.compact

import com.infinity.comicreader.reader.ArchiveEntry
import com.infinity.comicreader.reader.ArchiveReader
import com.infinity.comicreader.util.Formats
import com.infinity.comicreader.util.NaturalOrder
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.Locale

/**
 * Which folders Pack takes: those holding images, and a `ComicInfo.xml` beside them, only;
 * OS litter aside. Unusual files (dot-files, dot-folders, empty files) are packed only when
 * the user says so, and never as pages.
 */
object PackFolders {
    /** Why a folder cannot be packed. */
    enum class Why { SUBFOLDERS, OTHER_FILES, NO_IMAGES, UNREADABLE }

    /** One of a folder's children as listed: its [name], whether a [dir], and a file's [size]. */
    data class Child(val name: String, val dir: Boolean = false, val size: Long = 1)

    /**
     * What a folder holds: the [images] to pack, in natural order, its [extras] (a
     * `ComicInfo.xml`) and its [unusual] files and dot-folders, which go in only when wanted;
     * else [why] not.
     */
    class Contents(val images: List<File>, val why: Why?, val extras: List<File> = emptyList(), val unusual: List<File> = emptyList()) {
        /** What goes into the CBZ, in natural order of their paths: [images], [extras], and with [unusualToo] every file of [unusual], dot-folders' too. */
        fun packed(dir: File, unusualToo: Boolean): List<File> {
            val more = if (unusualToo) unusualFiles() else emptyList()
            return (images + extras + more).sortedWith(compareBy(NaturalOrder) { pathIn(dir, it) })
        }

        /** Every file of [unusual] as it is now, dot-folders' too. Blocking. */
        fun unusualFiles(): List<File> = unusual.flatMap { if (it.isDirectory) it.walkTopDown().filter { f -> f.isFile }.toList() else listOf(it) }
    }

    /** OS litter beside the images, left out and removed with the folder. */
    fun isJunk(name: String): Boolean = FlatNames.isJunk(name) || name.lowercase(Locale.ROOT) in MORE_JUNK

    /** An OS litter folder (`__MACOSX/`): removed, whole, with the folder. */
    fun isJunkFolder(name: String): Boolean = !isDotName(name) && FlatNames.isJunk("$name/-")

    /** A file Pack packs beside the images: `ComicInfo.xml`. */
    fun isExtra(name: String): Boolean = name.equals(COMIC_INFO, ignoreCase = true)

    /** A child asked about: a dot-folder, or a file (not litter) that is a dot-file or empty. */
    fun isUnusual(child: Child): Boolean =
        if (child.dir) isDotName(child.name) else !isJunk(child.name) && (isDotName(child.name) || child.size == 0L)

    /** Why a folder holding [children] cannot be packed; null when it can. */
    fun why(children: List<Child>): Why? {
        if (children.any { it.dir && !isJunkFolder(it.name) && !isUnusual(it) }) return Why.SUBFOLDERS
        val files = children.filter { !it.dir && !isJunk(it.name) && !isUnusual(it) }
        if (files.any { !Formats.isImageName(it.name) && !isExtra(it.name) }) return Why.OTHER_FILES
        return if (files.none { Formats.isImageName(it.name) }) Why.NO_IMAGES else null
    }

    /** What [dir] holds now. Blocking. */
    fun of(dir: File): Contents {
        val listed = dir.listFiles() ?: return Contents(emptyList(), Why.UNREADABLE)
        val children = listed.map { it to Child(it.name, !it.isFile, if (it.isFile) it.length() else 0) }
        val why = why(children.map { it.second })
        if (why != null) return Contents(emptyList(), why)
        val order = compareBy<File, String>(NaturalOrder) { it.name }
        val unusual = children.filter { isUnusual(it.second) }.map { it.first }.sortedWith(order)
        val usual = children.filter { !it.second.dir && !isJunk(it.second.name) && !isUnusual(it.second) }.map { it.first }
        val (extras, images) = usual.partition { isExtra(it.name) }
        return Contents(images.sortedWith(order), null, extras.sortedWith(order), unusual)
    }

    /** The first of [dirs] that cannot be packed, with why; null when all can. Blocking. */
    fun firstBlocked(dirs: List<File>): Pair<File, Why>? {
        for (dir in dirs) of(dir).why?.let { return dir to it }
        return null
    }

    /** [file]'s path in [dir], `/`-separated. */
    fun pathIn(dir: File, file: File): String = file.relativeTo(dir).invariantSeparatorsPath

    /** A dot-file or dot-folder name; `...Extras` and other names opening with an ellipsis are not. */
    private fun isDotName(name: String) = name.startsWith('.') && !name.startsWith("..")

    private const val COMIC_INFO = "ComicInfo.xml"

    /** `.nomedia` and the thumbnail caches some systems leave. */
    private val MORE_JUNK = setOf(".nomedia", "ehthumbs.db", "ehthumbs_vista.db")
}

/** Reads [files], a folder's, as the entries of an archive named by their paths in [dir] (by file names without it). */
class FolderReader(private val files: List<File>, dir: File? = null) : ArchiveReader {
    override val entries: List<ArchiveEntry> =
        files.mapIndexed { i, f -> ArchiveEntry(if (dir == null) f.name else PackFolders.pathIn(dir, f), f.length(), i) }

    override fun <T> read(entry: ArchiveEntry, block: (InputStream) -> T): T = FileInputStream(looseFile(entry)).use(block)

    /** The file [entry] is. */
    override fun looseFile(entry: ArchiveEntry): File = files[entry.index]

    override fun close() = Unit
}
