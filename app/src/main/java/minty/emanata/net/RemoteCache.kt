package minty.emanata.net

import minty.emanata.data.BookId
import minty.emanata.data.BookIds
import minty.emanata.util.Formats
import minty.emanata.util.Hashes
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException

/**
 * Local copies of files on shares, for what cannot be read in place: PDFs, loose images,
 * and books on servers that send files only whole. Kept under `cacheDir/network` by
 * the file's Book ID ([nameOf]), so a changed file is fetched again and a moved or copied
 * one is not. Pages unpacked
 * from solid archives read in place are kept beside them, a folder per book under
 * `cacheDir/network-pages` ([pagesOf]). The least recently used copies and folders go
 * once together they pass [limitBytes] (the "Network cache" setting).
 */
object RemoteCache {
    const val DIR = "network"
    const val PAGES_DIR = "network-pages"
    private const val PART_SUFFIX = ".part"
    private const val CHUNK_BYTES = 1 shl 20
    const val DEFAULT_LIMIT_BYTES = 2L * 1024 * 1024 * 1024

    /** How much the copies may take; set from the setting at startup and when it changes. */
    @Volatile
    var limitBytes: Long = DEFAULT_LIMIT_BYTES

    /** Copies being written and page folders in use now, by path, each with its users, so trimming leaves them be. */
    private val writing = HashMap<String, Int>()

    private fun hold(path: String) = synchronized(writing) { writing[path] = (writing[path] ?: 0) + 1 }

    private fun letGo(path: String) = synchronized(writing) {
        val left = (writing[path] ?: 0) - 1
        if (left > 0) writing[path] = left else writing -= path
    }

    fun dirIn(cacheDir: File) = File(cacheDir, DIR)

    fun pagesIn(cacheDir: File) = File(cacheDir, PAGES_DIR)

    /**
     * The folder pages unpacked from [file] are kept in, by its Book ID ([nameOf]), marked
     * as just used and held from trimming until [release]d as often as asked for. Blocking:
     * an unseen file's ends are read.
     */
    fun pagesOf(file: RemoteFile, cacheDir: File): File {
        val stat = file.stat()
        val dir = File(pagesIn(cacheDir), stat?.let { nameOf(file, it, read = true) } ?: Hashes.sha256Hex(file.path))
        hold(dir.path)
        if (dir.isDirectory) touch(dir)
        return dir
    }

    /** Lets trimming take [pages], a folder from [pagesOf], again, then trims what [cacheDir] keeps. */
    fun release(pages: File, cacheDir: File) {
        letGo(pages.path)
        trim(dirIn(cacheDir))
    }

    /**
     * The local copy of [file] when one is complete; null otherwise. Touches no network,
     * so only a file whose Book ID is known finds its copy. Copies are stored only once
     * whole, so one of a file whose size is not listed counts.
     */
    fun cached(file: RemoteFile, cacheDir: File): File? {
        val stat = file.stat() ?: return null
        return copyOf(file, stat, dirIn(cacheDir), read = false).takeIf { whole(it, stat) }?.also { touch(it) }
    }

    private fun whole(copy: File, stat: RemoteStat): Boolean = copy.isFile && (stat.size < 0 || copy.length() == stat.size)

    /**
     * The local copy of [file], fetched first when there is none: written aside and
     * renamed when whole. [progress] hears bytes done and the total; fetching stops,
     * throwing, once [canceled] turns true. Blocking.
     */
    fun fetch(
        file: RemoteFile,
        cacheDir: File,
        canceled: () -> Boolean = { false },
        progress: (Long, Long) -> Unit = { _, _ -> }
    ): File {
        val stat = file.fresh() ?: throw IOException("Not found: ${file.name}")
        val dir = dirIn(cacheDir)
        val target = copyOf(file, stat, dir, read = true)
        if (whole(target, stat)) {
            touch(target)
            return target
        }
        if (stat.size > limitBytes) throw TooLargeException(stat.size, limitBytes)
        dir.mkdirs()
        val part = File.createTempFile(target.name, PART_SUFFIX, dir)
        hold(part.path)
        try {
            // Read front to back in growing runs, the next fetched while one is written.
            RemoteChannel(Remotes.open(file.path), minBlock = CHUNK_BYTES, toEnd = true).use { input ->
                // A size the listing left out comes with the file.
                val size = if (stat.size >= 0) stat.size else input.size()
                if (size > limitBytes) throw TooLargeException(size, limitBytes)
                trim(dir, limitBytes - size)
                part.outputStream().use { out ->
                    val buf = ByteArray(CHUNK_BYTES)
                    var done = 0L
                    while (done < size) {
                        if (canceled()) throw InterruptedIOException("Canceled")
                        val n = input.read(buf, 0, minOf(buf.size.toLong(), size - done).toInt())
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        progress(done, size)
                    }
                    if (done != size) throw IOException("Short read: ${file.name}")
                }
            }
            val named = idNamed(file, stat, part, target)
            if (named != target && whole(named, stat)) {
                // The same book, copied before from elsewhere.
                touch(named)
                return named
            }
            if (!part.renameTo(named)) {
                named.delete()
                if (!part.renameTo(named)) throw IOException("Could not store ${file.name}")
            }
            return named
        } finally {
            part.delete()
            letGo(part.path)
        }
    }

    /**
     * Deletes the least recently used copies in [dir], and folders of kept pages beside
     * it, until those left take at most [maxBytes]; ones being written or read are left be.
     */
    fun trim(dir: File, maxBytes: Long = limitBytes) {
        val busy = synchronized(writing) { writing.keys.toSet() }
        val copies = dir.listFiles()?.filter { it.isFile && it.path !in busy }.orEmpty().map { it to it.length() }
        val pages = File(dir.parentFile, PAGES_DIR).listFiles()?.filter { it.isDirectory && it.path !in busy }.orEmpty()
            .map { d -> d to d.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
        var total = copies.sumOf { it.second } + pages.sumOf { it.second }
        for ((f, size) in (copies + pages).sortedBy { it.first.lastModified() }) {
            if (total <= maxBytes) break
            if (f.deleteRecursively()) total -= size
        }
    }

    /**
     * The name [file]'s copy or kept pages go by: a hash of its Book ID, found by reading its
     * ends when unknown and [read] says so; else of its path, size and time.
     */
    private fun nameOf(file: RemoteFile, stat: RemoteStat, read: Boolean): String {
        BookIds.load()
        val id = BookIds.known(file.path, stat.size, stat.modified) ?: if (read) BookIds.find(file) else null
        return id?.let { nameOf(it) } ?: Hashes.sha256Hex("${file.path}|${stat.size}|${stat.modified}")
    }

    /**
     * Where [part], the whole copy of [file] just fetched, is kept: [target], unless that
     * is named by path because the ID was unknown (a share sending files only whole is
     * never read for it), in which case it is found from the copy and named by it.
     */
    private fun idNamed(file: RemoteFile, stat: RemoteStat, part: File, target: File): File {
        val id = BookIds.known(file.path, stat.size, stat.modified) ?: try {
            BookIds.ofCopy(file, stat.size, stat.modified, part)
        } catch (e: IOException) {
            return target
        }
        return File(target.parentFile, nameOf(id) + "." + target.name.substringAfterLast('.'))
    }

    /** The name the copy and kept pages of book [id] go by. */
    private fun nameOf(id: BookId): String = Hashes.sha256Hex("id:${id.hex}")

    /** Deletes the copy and kept pages of book [id] in [cacheDir], as when it is deleted; ones in use are left be. */
    fun forget(id: BookId, cacheDir: File) {
        val name = nameOf(id)
        val busy = synchronized(writing) { writing.keys.toSet() }
        dirIn(cacheDir).listFiles()?.filter { it.name.substringBefore('.') == name && it.path !in busy }?.forEach { it.delete() }
        File(pagesIn(cacheDir), name).takeIf { it.path !in busy }?.deleteRecursively()
    }

    private fun copyOf(file: RemoteFile, stat: RemoteStat, dir: File, read: Boolean): File {
        val ext = Formats.ext(file.name).ifEmpty { "bin" }
        return File(dir, nameOf(file, stat, read) + "." + ext)
    }

    /** Marks [copy] as just used, for trimming. */
    private fun touch(copy: File) {
        copy.setLastModified(System.currentTimeMillis())
    }

    /** A file larger than the whole cache may hold. */
    class TooLargeException(val size: Long, val limit: Long) : IOException("File of $size bytes exceeds the network cache ($limit bytes)")
}
