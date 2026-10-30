package com.infinity.comicreader.compact

import com.infinity.comicreader.net.ChangeRefused
import com.infinity.comicreader.reader.ArchiveEntry
import com.infinity.comicreader.reader.ArchiveJunk
import com.infinity.comicreader.reader.ArchiveReader
import com.infinity.comicreader.reader.PageMoves
import com.infinity.comicreader.util.Formats
import com.infinity.comicreader.util.NaturalOrder
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Rebuilds a comic archive as a flat `.cbz` of stored (uncompressed) entries in natural
 * order: each page [convert]ed or kept as it is, names kept but for the extension of a
 * page made anew ([FlatNames]), every other file copied as it is, OS litter left out.
 * Pages convert on up to [maxWorkers] threads at once, fewer while [limit] says so, and
 * only as many as [memory] bytes hold ([Request.cost]); each file passes through
 * [scratch] on its way, checked by the CRC its archive stores, if any. The new archive is
 * written beside the book, every entry checked by its CRC, and only then put in place: as the book itself when
 * [Request.inPlace] (a non-ZIP book's file removed), else beside it.
 */
class Compactor(
    private val scratch: File,
    private val maxWorkers: Int,
    private val limit: () -> Int = { maxWorkers },
    private val threads: ThreadFactory = ThreadFactory { r -> Thread(r, "compact").apply { isDaemon = true } },
    private val memory: Long = Runtime.getRuntime().maxMemory() / 2
) {
    /** What to make of a book. */
    class Request(
        val book: File,
        /** Its page made anew, or null to keep it. */
        val convert: (ByteArray) -> PageWork.Made?,
        val inPlace: Boolean,
        /** The parenthesized word naming a new file beside the book whose name is taken: "compacted". */
        val suffix: String,
        /** Pages to leave out, by their place in the book (from 0). */
        val skip: Set<Int> = emptySet(),
        /** True to keep the new file only when smaller than the book. */
        val smallerOnly: Boolean = false,
        /** The memory making a page anew takes at most ([PageWork.memoryFor]). */
        val cost: (ByteArray) -> Long = { it.size * 3L }
    )

    /** What an operation's work hears from and tells its runner. */
    interface Progress {
        val alive: Boolean

        fun report(done: Long, total: Long)
    }

    sealed class Result {
        /**
         * The book rebuilt as [file], [before] and [after] bytes; book page `i` is now page
         * [moves]`[i]` of [pageCount] (-1: left out). [removed] is the book's file when it
         * went (in place, under another name); [reencoded] names the pages whose pixels
         * changed, [failed] those kept as they were because they could not be made anew.
         */
        class Rebuilt(
            val file: File,
            val before: Long,
            val after: Long,
            val moves: IntArray,
            val pageCount: Int,
            val removed: File?,
            val reencoded: List<String>,
            val failed: List<String> = emptyList()
        ) : Result()

        /** Left as it was, for [why]; [failed] names the pages that could not be made anew. */
        class Unchanged(val why: Why, val failed: List<String> = emptyList()) : Result()
    }

    enum class Why {
        /** No page to make anew, and no page left out. */
        NOTHING,

        /** It holds other archives, whose pages it would not reach. */
        NESTED,

        /** The new file came out no smaller. */
        NOT_SMALLER
    }

    /** A file of the new archive, waiting in [scratch]; [failed] for a page kept because it could not be made anew. */
    private class Piece(val file: File, val size: Long, val crc: Long, val made: PageWork.Made?, val failed: Boolean = false)

    /**
     * Rebuilds [request]'s book, read through the reader [open] gives, closed before the new
     * file takes its place; throws [CancellationException] once canceled, cleaning up.
     */
    fun run(request: Request, open: () -> ArchiveReader, progress: Progress): Result {
        var reader: ArchiveReader? = open()
        try {
            return run(request, reader!!, progress) { reader?.close(); reader = null }
        } finally {
            reader?.close()
        }
    }

    private fun run(request: Request, reader: ArchiveReader, progress: Progress, closeReader: () -> Unit): Result {
        val book = request.book
        // Taken now: in place, the book goes or is replaced.
        val time = book.lastModified()
        val all = reader.entries
        if (all.any { Formats.isArchiveName(it.path) && !FlatNames.isJunk(it.path) }) return Result.Unchanged(Why.NESTED)
        val kept = all.filter { !FlatNames.isJunk(it.path) }
        val pages = pagesOf(kept.map { it.path to it.size }).map { kept[it] }
        val dropped = request.skip.mapNotNullTo(HashSet()) { pages.getOrNull(it) }
        val files = kept.filter { it !in dropped }.sortedWith(compareBy(NaturalOrder) { it.path })
        val total = files.sumOf { it.size.coerceAtLeast(0) }.coerceAtLeast(1)
        val dir = book.parentFile ?: throw IOException("No folder")
        // Room for the pieces and the new file: no larger than the book when it must be smaller, else up to twice.
        val room = book.length() * (if (request.smallerOnly) 1 else 2) + SPARE_BYTES
        if (scratch.usableSpace < room || dir.usableSpace < room) throw ChangeRefused(ChangeRefused.Why.FULL)
        val work = File(scratch, "book-${System.nanoTime()}")
        if (!work.mkdirs()) throw IOException("Cannot make ${work.name}")
        var part: File? = null
        try {
            val pieces = convertAll(request, reader, files, work, progress, total)
            closeReader()
            val failedAt = files.indices.filter { pieces[it].failed }
            if (pieces.none { it.made != null } && dropped.isEmpty()) return Result.Unchanged(Why.NOTHING, failedAt.map { FlatNames.baseName(files[it].path) })
            val names = FlatNames.of(
                files.map { it.path },
                files.mapIndexed { i, e ->
                    val base = FlatNames.baseName(e.path)
                    pieces[i].made?.let { FlatNames.withExtension(base, it.ext) } ?: base
                }
            )
            val out = File(dir, ".${book.name}.${System.nanoTime()}.part")
            part = out
            pack(out, names, pieces, time, progress, total)
            verify(out, names, pieces)
            val before = book.length()
            val after = out.length()
            if (request.smallerOnly && after >= before) return Result.Unchanged(Why.NOT_SMALLER, failedAt.map { FlatNames.baseName(files[it].path) })
            check(progress)
            val (target, removed) = place(out, book, request)
            part = null
            if (time > 0) target.setLastModified(time)
            val oldKeys = pages.map { e -> files.indexOf(e).let { if (it < 0) null else names[it] } }
            val newPages = pagesOf(names.indices.map { names[it] to pieces[it].size }).map { names[it] }
            val moves = PageMoves.of(oldKeys.mapIndexed { i, n -> n ?: "\u0000$i" }, newPages)
            val reencoded = files.indices.filter { pieces[it].made?.reencoded == true }.map { names[it] }
            return Result.Rebuilt(target, before, after, moves, newPages.size, removed, reencoded, failedAt.map { names[it] })
        } finally {
            part?.delete()
            work.deleteRecursively()
        }
    }

    /**
     * Reads [files] in the order the archive stores them, converting pages on the worker
     * threads, each result in [work]; returns the pieces in the order of [files].
     */
    private fun convertAll(request: Request, reader: ArchiveReader, files: List<ArchiveEntry>, work: File, progress: Progress, total: Long): List<Piece> {
        val pieces = arrayOfNulls<Piece>(files.size)
        val pool = Executors.newFixedThreadPool(maxWorkers.coerceAtLeast(1), threads)
        // Pages read and not yet converted, and the memory they and their converting take.
        val room = Semaphore(maxWorkers.coerceAtLeast(1) * 2)
        val budget = Budget(memory.coerceAtLeast(1))
        val gate = Gate(limit)
        val futures = ArrayList<Future<*>>()
        var done = 0L
        val lock = Any()
        fun advance(bytes: Long) = synchronized(lock) {
            done += bytes
            progress.report(done * CONVERT_SHARE / 100, total)
        }
        try {
            for (i in files.indices.sortedBy { files[it].index }) {
                check(progress)
                val entry = files[i]
                val file = File(work, i.toString())
                if (!isPage(entry)) {
                    pieces[i] = reader.read(entry) { copy(it, file) }.also { checkRead(entry, it.size, it.crc) }
                    advance(entry.size.coerceAtLeast(0))
                    continue
                }
                while (!room.tryAcquire(WAIT_MS, TimeUnit.MILLISECONDS)) check(progress)
                // The page's bytes first, then what converting it takes beyond them; a page larger than the budget converts alone.
                var held = budget.take(entry.size, progress)
                val bytes = try {
                    reader.read(entry) { it.readBytes() }
                } catch (e: OutOfMemoryError) {
                    null
                } catch (t: Throwable) {
                    budget.give(held)
                    room.release()
                    throw t
                }
                if (bytes == null) {
                    budget.give(held)
                    room.release()
                    // Too large to hold now: copied as it is.
                    pieces[i] = reader.read(entry) { copy(it, file) }.let {
                        checkRead(entry, it.size, it.crc)
                        Piece(it.file, it.size, it.crc, null, failed = true)
                    }
                    advance(entry.size.coerceAtLeast(0))
                    continue
                }
                try {
                    checkRead(entry, bytes.size.toLong(), crcOf(bytes))
                    held += budget.take(request.cost(bytes).coerceIn(held, budget.total) - held, progress)
                } catch (t: Throwable) {
                    budget.give(held)
                    room.release()
                    throw t
                }
                val taken = held
                futures += pool.submit {
                    try {
                        var failed = false
                        val made = if (!progress.alive) null else gate.pass {
                            try {
                                request.convert(bytes)
                            } catch (e: Exception) {
                                failed = true
                                null
                            } catch (e: OutOfMemoryError) {
                                failed = true
                                null
                            }
                        }
                        val keep = made?.bytes ?: bytes
                        file.writeBytes(keep)
                        pieces[i] = Piece(file, keep.size.toLong(), crcOf(keep), made, failed)
                        advance(entry.size.coerceAtLeast(0))
                    } finally {
                        budget.give(taken)
                        room.release()
                    }
                }
            }
            for (f in futures) {
                try {
                    f.get()
                } catch (e: ExecutionException) {
                    throw e.cause ?: e
                }
            }
            check(progress)
        } finally {
            pool.shutdownNow()
            // libjxl cannot be stopped midway: the operation ends, and the next starts, once each page under way has.
            while (!pool.awaitTermination(WAIT_MS, TimeUnit.MILLISECONDS)) Unit
        }
        return pieces.map { it ?: throw IOException("A file was not read") }
    }

    /** Writes [pieces] as [names] into [out], a stored ZIP, in order, each dated [time]. */
    private fun pack(out: File, names: List<String>, pieces: List<Piece>, time: Long, progress: Progress, total: Long) {
        val buffer = ByteArray(COPY_BYTES)
        var done = 0L
        val packed = pieces.sumOf { it.size }.coerceAtLeast(1)
        FileOutputStream(out).use { fos ->
            val zip = ZipOutputStream(BufferedOutputStream(fos, COPY_BYTES))
            zip.setMethod(ZipOutputStream.STORED)
            for ((i, piece) in pieces.withIndex()) {
                check(progress)
                val entry = ZipEntry(names[i])
                entry.method = ZipEntry.STORED
                entry.size = piece.size
                entry.compressedSize = piece.size
                entry.crc = piece.crc
                if (time > 0) entry.time = time
                zip.putNextEntry(entry)
                piece.file.inputStream().use { input ->
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        zip.write(buffer, 0, n)
                    }
                }
                zip.closeEntry()
                done += piece.size
                progress.report(total * CONVERT_SHARE / 100 + (total * PACK_SHARE / 100) * done / packed, total)
            }
            zip.finish()
            zip.flush()
            fos.fd.sync()
            zip.close()
        }
    }

    /** Checks that [out] holds exactly [names], stored, each with its piece's size and CRC, read back. */
    private fun verify(out: File, names: List<String>, pieces: List<Piece>) {
        val buffer = ByteArray(COPY_BYTES)
        ZipFile(out).use { zip ->
            val entries = zip.entries().toList()
            if (entries.map { it.name } != names) throw IOException("Entries differ in ${out.name}")
            for ((i, entry) in entries.withIndex()) {
                val piece = pieces[i]
                if (entry.method != ZipEntry.STORED || entry.size != piece.size) throw IOException("Entry ${names[i]} differs")
                val crc = CRC32()
                var size = 0L
                zip.getInputStream(entry).use { input ->
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        crc.update(buffer, 0, n)
                        size += n
                    }
                }
                if (size != piece.size || crc.value != piece.crc) throw IOException("Entry ${names[i]} differs")
            }
        }
    }

    /**
     * Puts [out] in place: over [book] in place when it is a ZIP, else as a `.cbz` named after
     * it ([FlatNames.output]), the book removed when in place. Returns the file and what was removed.
     */
    private fun place(out: File, book: File, request: Request): Pair<File, File?> {
        val dir = out.parentFile!!
        if (request.inPlace && Formats.ext(book.name) in Formats.ZIP) {
            Files.move(out.toPath(), book.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            return book to null
        }
        val name = FlatNames.output(book.name, request.suffix) { n -> File(dir, n).exists() }
        val target = File(dir, name)
        // Never over a file there.
        Files.move(out.toPath(), target.toPath())
        if (!request.inPlace) return target to null
        if (!book.delete() && book.exists()) throw IOException("Cannot remove ${book.name}")
        return target to book
    }

    private fun check(progress: Progress) {
        if (!progress.alive) throw CancellationException("Canceled")
    }

    /** Throws unless [entry] read as [size] bytes of [crc] is what its archive says it holds. */
    private fun checkRead(entry: ArchiveEntry, size: Long, crc: Long) {
        if ((entry.size >= 0 && size != entry.size) || (entry.crc >= 0 && crc != entry.crc)) throw IOException("${entry.path} is damaged")
    }

    /** True for a file read whole to be converted: named as an image, of a size held in memory. */
    private fun isPage(entry: ArchiveEntry): Boolean =
        Formats.isImageName(entry.path) && entry.size in 1..MAX_PAGE_BYTES

    private fun copy(input: InputStream, file: File): Piece {
        val crc = CRC32()
        val buffer = ByteArray(COPY_BYTES)
        var size = 0L
        FileOutputStream(file).use { out ->
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                crc.update(buffer, 0, n)
                size += n
            }
        }
        return Piece(file, size, crc.value, null)
    }

    /** [total] bytes of memory to share out; what one asks beyond them it is given as the whole. */
    private inner class Budget(val total: Long) {
        private val lock = Object()
        private var used = 0L

        /** Takes [bytes] (at most [total]) once they are free, waiting on what others hold; throws once [progress] is canceled. Returns what it took. */
        fun take(bytes: Long, progress: Progress): Long {
            val n = bytes.coerceIn(0, total)
            synchronized(lock) {
                while (used + n > total) {
                    check(progress)
                    lock.wait(WAIT_MS)
                }
                used += n
            }
            return n
        }

        fun give(bytes: Long) = synchronized(lock) {
            used -= bytes
            lock.notifyAll()
        }
    }

    /** Lets at most [limit] pass at once, the limit read anew as each one comes. */
    private class Gate(private val limit: () -> Int) {
        private val lock = Object()
        private var inside = 0

        fun <T> pass(block: () -> T): T {
            synchronized(lock) {
                while (inside >= limit().coerceAtLeast(1)) lock.wait(WAIT_MS)
                inside++
            }
            try {
                return block()
            } finally {
                synchronized(lock) {
                    inside--
                    lock.notifyAll()
                }
            }
        }
    }

    companion object {
        /** Pages larger than this are copied as they are. */
        const val MAX_PAGE_BYTES = 64L * 1024 * 1024

        private const val COPY_BYTES = 256 * 1024

        /** Room left free beyond what a book needs. */
        private const val SPARE_BYTES = 64L * 1024 * 1024
        private const val WAIT_MS = 200L

        /** Shares of the progress: converting, then packing, then the check. */
        private const val CONVERT_SHARE = 85
        private const val PACK_SHARE = 10

        private fun crcOf(bytes: ByteArray): Long = CRC32().apply { update(bytes) }.value

        /**
         * The places among [files] (path, size) of the book's pages, in the book's order: as
         * the reader lists them, images by name in natural order, litter left out.
         */
        fun pagesOf(files: List<Pair<String, Long>>): List<Int> =
            files.indices.filter { !ArchiveJunk.isJunk(files[it].first, files[it].second) && Formats.isImageName(files[it].first) }
                .sortedWith(compareBy(NaturalOrder) { files[it].first })
    }
}
