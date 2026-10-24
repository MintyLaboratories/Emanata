package com.infinity.comicreader.files

import com.infinity.comicreader.net.Remotes
import com.infinity.comicreader.util.FileUtils
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/** A file or folder as a [FileSpace] finds it: a folder's size is 0, a file's -1 when unknown. */
data class Node(val name: String, val isDirectory: Boolean, val size: Long, val modified: Long = 0)

/**
 * Where files live, by the app's paths: the device's storage, or one share. Every call
 * may be blocking I/O; failures throw.
 */
interface FileSpace {
    /** True when [other] is the same place, where [rename] can move between them. */
    fun sameAs(other: FileSpace): Boolean

    /** What is at [path]; null when nothing is. */
    fun stat(path: String): Node?

    /** The entries of folder [path]. */
    fun list(path: String): List<Node>

    /** Makes folder [path]; fails when anything is there. */
    fun mkdir(path: String)

    /** Reads file [path] from its start. */
    fun open(path: String): InputStream

    /**
     * Writes [length] bytes from [input] as the new file [path], all of them stored once
     * it returns; fails when anything is there. A failure may leave part of it behind.
     */
    fun create(path: String, input: InputStream, length: Long, modified: Long)

    /**
     * Renames or moves [from] to [to] within this space; fails when anything is at [to].
     * False when this space cannot move between the two (another drive of the device).
     */
    fun rename(from: String, to: String): Boolean

    /** Deletes the file or folder [path] with everything inside. */
    fun delete(path: String)

    /** Copies file [from] to the new file [to] without passing through the device; false when it cannot. */
    fun copy(from: String, to: String, progress: (Long) -> Unit): Boolean = false
}

/** The device's storage, through [File]. */
object DeviceSpace : FileSpace {
    override fun sameAs(other: FileSpace) = other === this

    override fun stat(path: String): Node? {
        val f = File(path)
        if (!f.exists()) return null
        val dir = f.isDirectory
        return Node(f.name, dir, if (dir) 0 else f.length(), f.lastModified())
    }

    override fun list(path: String): List<Node> =
        (File(path).listFiles() ?: throw IOException("Not a folder: $path")).map { f ->
            val dir = f.isDirectory
            Node(f.name, dir, if (dir) 0 else f.length(), f.lastModified())
        }

    override fun mkdir(path: String) {
        if (!File(path).mkdir()) throw IOException("Could not make $path")
    }

    override fun open(path: String): InputStream = File(path).inputStream()

    /** Synced to storage before returning, so a move may then delete its source. */
    override fun create(path: String, input: InputStream, length: Long, modified: Long) {
        val file = File(path)
        if (!file.createNewFile()) throw IOException("Already there: $path")
        FileOutputStream(file).use { out ->
            input.copyTo(out, BUFFER)
            out.fd.sync()
        }
        if (modified > 0) file.setLastModified(modified)
    }

    override fun rename(from: String, to: String): Boolean {
        val target = File(to)
        if (target.exists() && !sameFileOtherCase(from, to)) throw IOException("Already there: $to")
        if (File(from).renameTo(target)) return true
        // Across drives renameTo fails: copied instead. Elsewhere a failure is one.
        if (File(from).exists() && !target.exists() && driveOf(from) != driveOf(to)) return false
        throw IOException("Could not rename $from")
    }

    override fun delete(path: String) {
        val file = File(path)
        if (!FileUtils.deleteTree(file) && file.exists()) throw IOException("Could not delete $path")
    }

    /** True when [a] and [b] differ only in case and name one file (on storage that ignores case). */
    private fun sameFileOtherCase(a: String, b: String): Boolean =
        a != b && a.equals(b, ignoreCase = true) &&
            runCatching { File(a).canonicalPath == File(b).canonicalPath }.getOrDefault(false)

    /** The mount [path] is on, as far as paths tell: `/storage/<volume>`, else its first two names. */
    private fun driveOf(path: String): String =
        path.replace(File.separatorChar, '/').split('/').filter { it.isNotEmpty() }.take(2).joinToString("/")

    private const val BUFFER = 1 shl 20
}

/** Share [id], through [Remotes]. */
class ShareSpace(val id: String) : FileSpace {
    override fun sameAs(other: FileSpace) = other is ShareSpace && other.id == id

    override fun stat(path: String): Node? = Remotes.stat(path)?.let { Node(it.name, it.isDirectory, it.size, it.modified) }

    override fun list(path: String): List<Node> = Remotes.list(path).map { f ->
        val s = f.stat()
        Node(f.name, s?.isDirectory == true, s?.size ?: -1, s?.modified ?: 0)
    }

    override fun mkdir(path: String) = Remotes.mkdir(path)

    override fun open(path: String): InputStream = Remotes.inputStream(path)

    /** Shares set their own modification times. */
    override fun create(path: String, input: InputStream, length: Long, modified: Long) = Remotes.create(path, input, length)

    override fun rename(from: String, to: String): Boolean {
        Remotes.rename(from, to)
        return true
    }

    override fun delete(path: String) = Remotes.delete(path)

    override fun copy(from: String, to: String, progress: (Long) -> Unit): Boolean = Remotes.copy(from, to, progress)
}

object FileSpaces {
    /** The space [path] is in. */
    fun of(path: String): FileSpace = Remotes.idOf(path)?.let { ShareSpace(it) } ?: DeviceSpace
}
