package com.infinity.comicreader.net

import com.infinity.comicreader.util.Formats
import com.infinity.comicreader.util.Hashes
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException

/**
 * Local copies of files on shares, for what cannot be read in place: rar, 7z and tar
 * books, PDFs and loose images. Kept under `cacheDir/network` by path, size and
 * modification time, so a changed file is fetched again; the least recently used
 * go once the copies pass [limitBytes] (the "Network cache" setting).
 */
object RemoteCache {
    const val DIR = "network"
    private const val PART_SUFFIX = ".part"
    private const val CHUNK_BYTES = 1 shl 20
    const val DEFAULT_LIMIT_BYTES = 2L * 1024 * 1024 * 1024

    /** How much the copies may take; set from the setting at startup and when it changes. */
    @Volatile
    var limitBytes: Long = DEFAULT_LIMIT_BYTES

    /** Copies being written now, by path, so trimming leaves them be. */
    private val writing = HashSet<String>()

    fun dirIn(cacheDir: File) = File(cacheDir, DIR)

    /** The local copy of [file] when one is complete; null otherwise. Touches no network. */
    fun cached(file: RemoteFile, cacheDir: File): File? {
        val stat = file.stat() ?: return null
        return copyOf(file, stat, dirIn(cacheDir)).takeIf { it.isFile && it.length() == stat.size }?.also { touch(it) }
    }

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
        val target = copyOf(file, stat, dir)
        if (target.isFile && target.length() == stat.size) {
            touch(target)
            return target
        }
        if (stat.size > limitBytes) throw TooLargeException(stat.size, limitBytes)
        dir.mkdirs()
        val part = File.createTempFile(target.name, PART_SUFFIX, dir)
        synchronized(writing) { writing += part.path }
        try {
            trim(dir, limitBytes - stat.size)
            Remotes.open(file.path).use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(CHUNK_BYTES)
                    var done = 0L
                    while (done < stat.size) {
                        if (canceled()) throw InterruptedIOException("Canceled")
                        val n = input.read(done, buf, 0, minOf(buf.size.toLong(), stat.size - done).toInt())
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        progress(done, stat.size)
                    }
                    if (done != stat.size) throw IOException("Short read: ${file.name}")
                }
            }
            if (!part.renameTo(target)) {
                target.delete()
                if (!part.renameTo(target)) throw IOException("Could not store ${file.name}")
            }
            return target
        } finally {
            part.delete()
            synchronized(writing) { writing -= part.path }
        }
    }

    /** Deletes the least recently used copies until those left take at most [maxBytes]. */
    fun trim(dir: File, maxBytes: Long = limitBytes) {
        val files = dir.listFiles()?.filter { it.isFile && synchronized(writing) { it.path !in writing } } ?: return
        var total = files.sumOf { it.length() }
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= maxBytes) break
            val size = f.length()
            if (f.delete()) total -= size
        }
    }

    private fun copyOf(file: RemoteFile, stat: RemoteStat, dir: File): File {
        val ext = Formats.ext(file.name).ifEmpty { "bin" }
        return File(dir, Hashes.sha256Hex("${file.path}\n${stat.size}\n${stat.modified}") + "." + ext)
    }

    /** Marks [copy] as just used, for trimming. */
    private fun touch(copy: File) {
        copy.setLastModified(System.currentTimeMillis())
    }

    /** A file larger than the whole cache may hold. */
    class TooLargeException(val size: Long, val limit: Long) : IOException("File of $size bytes exceeds the network cache ($limit bytes)")
}
