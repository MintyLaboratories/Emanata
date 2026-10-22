package com.infinity.comicreader.net

import com.infinity.comicreader.util.Formats
import java.io.File
import java.io.FileFilter
import java.io.FilenameFilter

/**
 * A file or folder on a network share, at a `/net/id/…` path ([Remotes]), so the
 * library, covers and reader handle it as they handle a local [File]. Its kind,
 * size and times come from the listing that found it, else from [Remotes]; on the
 * main thread, where the network is never used, from what is remembered or a guess
 * by its name (a name a book could have is a file). It is read-only: renaming,
 * deleting and making folders fail.
 */
class RemoteFile(private val where: String, @Volatile private var known: RemoteStat? = null) : File(where) {

    /** Its stat as known now: found with it, remembered, or asked for off the main thread; null when unknown or gone. */
    fun stat(): RemoteStat? {
        known?.let { return it }
        Remotes.cachedStat(where)?.let { return it }
        if (asked || Remotes.onMainThread()) return null
        asked = true
        return runCatching { Remotes.stat(where) }.getOrNull()?.also { known = it }
    }

    /** True once [stat] asked the share, so a file found missing is not asked for again. */
    @Volatile
    private var asked = false

    /** Its stat asked for now, off the main thread; null when nothing is there. Throws when the share fails. */
    fun fresh(): RemoteStat? = Remotes.stat(where).also {
        known = it
        asked = true
    }

    /** The folder's entries; throws when the share fails, where [listFiles] gives null. */
    fun entries(): List<RemoteFile> = Remotes.list(where)

    /** True when the stat is unknown and only a guess can be made (on the main thread). */
    private fun guessing(): Boolean = known == null && Remotes.cachedStat(where) == null && Remotes.onMainThread()

    private fun guessDirectory(): Boolean = !Formats.isOpenable(name)

    override fun getPath(): String = where

    override fun getAbsolutePath(): String = where

    override fun getCanonicalPath(): String = where

    override fun getAbsoluteFile(): File = this

    override fun getCanonicalFile(): File = this

    override fun toString(): String = where

    override fun getName(): String = where.substringAfterLast('/')

    override fun getParent(): String? = where.substringBeforeLast('/', "").ifEmpty { null }

    override fun getParentFile(): File? = parent?.let { Remotes.file(it) }

    override fun isAbsolute(): Boolean = true

    override fun exists(): Boolean = guessing() || stat() != null

    override fun isDirectory(): Boolean = if (guessing()) guessDirectory() else stat()?.isDirectory == true

    override fun isFile(): Boolean = if (guessing()) !guessDirectory() else stat()?.isDirectory == false

    override fun isHidden(): Boolean = name.startsWith(".")

    override fun length(): Long = stat()?.takeIf { !it.isDirectory }?.size ?: 0L

    override fun lastModified(): Long = stat()?.modified ?: 0L

    /** When it was made, where the share tells (0 = unknown). */
    fun created(): Long = stat()?.created ?: 0L

    override fun canRead(): Boolean = exists()

    override fun canWrite(): Boolean = false

    override fun canExecute(): Boolean = false

    override fun list(): Array<String>? = listFiles()?.map { it.name }?.toTypedArray()

    override fun list(filter: FilenameFilter?): Array<String>? =
        listFiles()?.filter { filter == null || filter.accept(this, it.name) }?.map { it.name }?.toTypedArray()

    override fun listFiles(): Array<File>? = runCatching { entries() }.getOrNull()?.toTypedArray()

    override fun listFiles(filter: FilenameFilter?): Array<File>? =
        listFiles()?.filter { filter == null || filter.accept(this, it.name) }?.toTypedArray()

    override fun listFiles(filter: FileFilter?): Array<File>? =
        listFiles()?.filter { filter == null || filter.accept(it) }?.toTypedArray()

    override fun delete(): Boolean = false

    override fun renameTo(dest: File): Boolean = false

    override fun mkdir(): Boolean = false

    override fun mkdirs(): Boolean = false

    override fun createNewFile(): Boolean = false

    override fun setLastModified(time: Long): Boolean = false

    override fun setReadOnly(): Boolean = false

    override fun getTotalSpace(): Long = 0L

    override fun getFreeSpace(): Long = 0L

    override fun getUsableSpace(): Long = 0L
}
