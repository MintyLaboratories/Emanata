package minty.emanata.data

import android.content.Context
import minty.emanata.util.Formats
import minty.emanata.util.NameSkeleton
import java.io.File

/** The Favorites tab section a favorite belongs to, in display order. */
enum class FavoriteKind(val key: String) {
    LIBRARY("library"),
    SET("set"),
    COMIC("comic");

    companion object {
        fun of(key: String?): FavoriteKind? = entries.firstOrNull { it.key == key }
    }
}

/** A folder as [FavoriteClassifier] sees it: its visible files and subfolders. */
interface FolderView {
    fun fileNames(): List<String>
    fun folders(): List<FolderView>
}

/** A folder on disk, listed once; hidden entries are left out. */
class FileFolder(private val dir: File) : FolderView {
    private val children: List<File> by lazy {
        dir.listFiles()?.filter { !it.name.startsWith(".") }.orEmpty()
    }

    override fun fileNames(): List<String> = children.filter { it.isFile }.map { it.name }

    override fun folders(): List<FolderView> = children.filter { it.isDirectory }.map { FileFolder(it) }
}

/**
 * Sorts favorites into Libraries, Sets and Comics by what they hold, not by where
 * they sit:
 * - Comic: a file (archive, PDF, image), or a folder of loose pages: at least
 *   [MIN_PAGES] images, images most of its files, and most image names sharing
 *   one numbered skeleton (`cover.jpg` + `promotional.jpg` is not a comic);
 * - Set: a folder whose children are mostly comics; stray images are ignored;
 * - Library: a storage root, or a folder whose comics are mostly two or more
 *   levels down.
 * Mixed folders go by majority; a tie, or nothing to count, is a Set.
 */
object FavoriteClassifier {
    const val MIN_PAGES = 5

    /** How far below a child folder comics are looked for. */
    const val MAX_DEPTH = 4

    fun classify(file: File, isRoot: Boolean): FavoriteKind =
        if (file.isDirectory) classifyFolder(FileFolder(file), isRoot) else FavoriteKind.COMIC

    fun classifyFolder(folder: FolderView, isRoot: Boolean): FavoriteKind {
        if (isRoot) return FavoriteKind.LIBRARY
        val files = folder.fileNames()
        if (isLooseComic(files)) return FavoriteKind.COMIC
        var comics = files.count { isBook(it) }
        var deeper = 0
        for (child in folder.folders()) {
            when {
                isLooseComic(child.fileNames()) -> comics++
                holdsComics(child, MAX_DEPTH) -> deeper++
            }
        }
        return if (deeper > comics) FavoriteKind.LIBRARY else FavoriteKind.SET
    }

    /**
     * The kind of a folder with [books] books, [images] images and [folders]
     * subfolders when that alone settles it, without looking inside: a root, or too
     * few images for loose pages and no more subfolders than books (a Set). Else null.
     */
    fun quickKind(books: Int, images: Int, folders: Int, isRoot: Boolean): FavoriteKind? = when {
        isRoot -> FavoriteKind.LIBRARY
        images < MIN_PAGES && folders <= books -> FavoriteKind.SET
        else -> null
    }

    /** True when [files] are the pages of one comic. */
    fun isLooseComic(files: List<String>): Boolean {
        val images = files.filter { Formats.isImageName(it) }
        return images.size >= MIN_PAGES && images.size * 2 > files.size && NameSkeleton.dominant(images) != null
    }

    private fun isBook(name: String): Boolean = Formats.isArchiveName(name) || name.endsWith(".pdf", ignoreCase = true)

    private fun holdsComics(folder: FolderView, depth: Int): Boolean {
        val files = folder.fileNames()
        if (files.any { isBook(it) } || isLooseComic(files)) return true
        if (depth <= 0) return false
        return folder.folders().any { holdsComics(it, depth - 1) }
    }
}

/**
 * Remembers each favorite's [FavoriteKind] with the modification time it was
 * worked out at; a different time means the folder changed and the kind is stale.
 */
class FavoriteKindCache(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** The kind stored for [path] at [lastModified], or null when unknown or stale. */
    fun get(path: String, lastModified: Long): FavoriteKind? {
        val stored = sp.getString(path, null) ?: return null
        val time = stored.substringBefore(':').toLongOrNull() ?: return null
        return if (time == lastModified) FavoriteKind.of(stored.substringAfter(':')) else null
    }

    /** The kind stored for [path] at any time: what a folder on a share shows until its time is known. */
    fun peek(path: String): FavoriteKind? = sp.getString(path, null)?.let { FavoriteKind.of(it.substringAfter(':')) }

    fun put(path: String, lastModified: Long, kind: FavoriteKind) {
        sp.edit().putString(path, "$lastModified:${kind.key}").apply()
    }

    /** Forgets every path not in [paths]. */
    fun retain(paths: Collection<String>) {
        val keep = paths.toHashSet()
        val gone = sp.all.keys.filter { it !in keep }
        if (gone.isEmpty()) return
        val e = sp.edit()
        gone.forEach { e.remove(it) }
        e.apply()
    }

    private companion object {
        const val FILE = "favorite_kinds"
    }
}
