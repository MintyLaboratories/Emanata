package com.infinity.comicreader.compact

import com.infinity.comicreader.data.BookIds
import com.infinity.comicreader.data.Xxh3
import com.infinity.comicreader.net.ChangeRefused
import com.infinity.comicreader.reader.ArchiveEntry
import com.infinity.comicreader.reader.ArchiveJunk
import com.infinity.comicreader.reader.ArchiveKind
import com.infinity.comicreader.reader.ArchiveReader
import com.infinity.comicreader.reader.PageMoves
import com.infinity.comicreader.util.Formats
import com.infinity.comicreader.util.LimitGate
import com.infinity.comicreader.util.NaturalOrder
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.FileAlreadyExistsException
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
 * [scratch] on its way, checked by the CRC its archive stores, if any; a folder's file kept
 * as it is is read from where it is. The new archive is written beside the book, every entry
 * checked by its CRC, and only then put in place: as the book itself when [Request.inPlace]
 * (a non-ZIP book's file removed), as [Request.target] when given (over a file there only
 * when [Request.replace]), else beside it. With [Request.marks], its archive comment says
 * what was done ([OutputMarks]), appended to a ZIP book's own comment and chain.
 */
class Compactor(
    private val scratch: File,
    private val maxWorkers: Int,
    private val limit: () -> Int = { maxWorkers },
    private val threads: ThreadFactory = ThreadFactory { r -> Thread(r, "compact").apply { isDaemon = true } },
    private val memory: Long = Runtime.getRuntime().maxMemory() / 2,
    /** The bytes free for files where a file is; tests stand in their own. */
    private val free: (File) -> Long = { it.usableSpace },
    /** Pages larger than this are copied as they are; tests stand in a smaller one. */
    private val maxPage: Long = MAX_PAGE_BYTES,
    /** The hash of output marks ([OutputMarks]). */
    private val hash: () -> Xxh3 = { BookIds.xxh3 }
) {
    /** What to make of a book. */
    class Request(
        val book: File,
        /** Its page made anew, or null to keep it; null to keep every page, each streamed for its CRC alone. */
        val convert: ((ByteArray) -> PageWork.Made?)?,
        val inPlace: Boolean,
        /** The parenthesized word naming a new file beside the book whose name is taken: "compacted". */
        val suffix: String,
        /** Pages to leave out, by their place in the book (from 0). */
        val skip: Set<Int> = emptySet(),
        /** True to keep the new file only when smaller than the book. */
        val smallerOnly: Boolean = false,
        /** The memory making a page anew takes at most ([PageWork.memoryFor]). */
        val cost: (ByteArray) -> Long = { it.size * 3L },
        /** What puts the new file in place, and what goes with it. */
        val placer: Placer = Placer { _, _, swap -> swap() },
        /** Where the new file goes; null to name it from the book. */
        val target: File? = null,
        /** True to put the new file over one at [target]; else a file found there fails it. */
        val replace: Boolean = false,
        /** True to write the new file even when no page is made anew (a folder packed). */
        val always: Boolean = false,
        /** True for a page, by its first bytes ([HEAD_BYTES]), that [convert] must make anew: one too large to read whole is listed as failed. */
        val mustConvert: (ByteArray) -> Boolean = { false },
        /** True to keep what is in dot-folders too, under its own path, never as pages (a folder packed with its unusual files). */
        val dotFolders: Boolean = false,
        /** Where the new file goes instead when a file takes [target]'s name meanwhile ("Keep both"); null to fail then. */
        val keepBoth: ((File) -> File)? = null,
        /** What the new file's comment says was done ([OutputMarks]), in order; compacting with pages left out says so. Empty for no mark. */
        val marks: List<OutputMarks.Operation> = emptyList()
    )

    /** Puts a rebuilt book in place. */
    fun interface Placer {
        /**
         * Runs [swap], which puts the new file [part] where [result] says ([Result.Rebuilt.file])
         * and removes what it says, with what goes before and after it; throws to leave the book as it is.
         */
        fun place(result: Result.Rebuilt, part: File, swap: () -> Unit)
    }

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
            file: File,
            val before: Long,
            val after: Long,
            val moves: IntArray,
            val pageCount: Int,
            val removed: File?,
            val reencoded: List<String>,
            val failed: List<String> = emptyList()
        ) : Result() {
            /** Where the new file is: the target, or the free name it took when that one was taken meanwhile ([Request.keepBoth]). */
            var file: File = file
                internal set
        }

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
        val all = reader.entries
        // Taken now: in place, the book goes or is replaced. A folder's is its newest file's.
        val time = if (book.isDirectory) all.maxOfOrNull { e -> reader.looseFile(e)?.lastModified() ?: 0L } ?: 0L else book.lastModified()
        if (all.any { Formats.isArchiveName(it.path) && !FlatNames.isJunk(it.path) }) return Result.Unchanged(Why.NESTED)
        val kept = all.filter { !FlatNames.isJunk(it.path) || (request.dotFolders && FlatNames.inDotFolder(it.path)) }
        val pages = pagesOf(kept.map { it.path to it.size }).map { kept[it] }
        val dropped = request.skip.mapNotNullTo(HashSet()) { pages.getOrNull(it) }
        val files = kept.filter { it !in dropped }.sortedWith(compareBy(NaturalOrder) { it.path })
        val total = files.sumOf { it.size.coerceAtLeast(0) }.coerceAtLeast(1)
        val dir = book.parentFile ?: throw IOException("No folder")
        sweep(dir)
        // Room for the pieces and for the new file, each as large as the files unpacked when kept or made smaller, else twice.
        val room = total * (if (request.smallerOnly || request.convert == null) 1 else GROWTH)
        // Every file kept and read from where it is: no pieces, only the new file.
        val noPieces = request.convert == null && files.all { reader.looseFile(it) != null }
        val enough = when {
            noPieces -> free(dir) >= room + SPARE_BYTES
            sameVolume(scratch, dir) -> free(dir) >= 2 * room + SPARE_BYTES
            else -> free(scratch) >= room + SPARE_BYTES && free(dir) >= room + SPARE_BYTES
        }
        if (!enough) throw ChangeRefused(ChangeRefused.Why.FULL)
        val work = File(scratch, "book-${System.nanoTime()}")
        if (!work.mkdirs()) throw IOException("Cannot make ${work.name}")
        var part: File? = null
        try {
            val pieces = convertAll(request, reader, files, work, progress, total)
            closeReader()
            val failedAt = files.indices.filter { pieces[it].failed }
            if (!request.always && pieces.none { it.made != null } && dropped.isEmpty()) return Result.Unchanged(Why.NOTHING, failedAt.map { FlatNames.baseName(files[it].path) })
            // What is kept of dot-folders keeps its path: never a page, never flattened.
            val flat = files.indices.filter { !FlatNames.inDotFolder(files[it].path) }
            val flatNames = FlatNames.of(
                flat.map { files[it].path },
                flat.map { i ->
                    val base = FlatNames.baseName(files[i].path)
                    pieces[i].made?.let { FlatNames.withExtension(base, it.ext) } ?: base
                },
                pagesOf(flat.map { files[it].path to files[it].size })
            )
            val names = files.map { it.path }.toMutableList()
            flat.forEachIndexed { k, i -> names[i] = flatNames[k] }
            val out = File(dir, ".${book.name}.${System.nanoTime()}.part")
            part = out
            pack(out, names, pieces, time, progress, total)
            if (request.marks.isNotEmpty()) {
                val partial = dropped.isNotEmpty()
                val ops = request.marks.map { if (partial && it == OutputMarks.Operation.COMPACT) OutputMarks.Operation.COMPACT_PARTIAL else it }
                OutputMarks.write(out, sourceComment(book), ops, hash())
            }
            verify(out, names, pieces)
            val before = if (book.isDirectory) all.sumOf { it.size.coerceAtLeast(0) } else book.length()
            val after = out.length()
            if (request.smallerOnly && after >= before) return Result.Unchanged(Why.NOT_SMALLER, failedAt.map { FlatNames.baseName(files[it].path) })
            check(progress)
            // In place, a ZIP is replaced under its name; any other book becomes a .cbz and goes.
            val over = request.inPlace && Formats.ext(book.name) in Formats.ZIP
            val target = request.target ?: if (over) book else File(dir, FlatNames.output(book.name, request.suffix) { n -> File(dir, n).exists() })
            val removed = if (request.inPlace && !over) book else null
            val oldKeys = pages.map { e -> files.indexOf(e).let { if (it < 0) null else names[it] } }
            val newPages = pagesOf(names.indices.map { names[it] to pieces[it].size }).map { names[it] }
            val moves = PageMoves.of(oldKeys.mapIndexed { i, n -> n ?: "\u0000$i" }, newPages)
            val reencoded = files.indices.filter { pieces[it].made?.reencoded == true }.map { names[it] }
            val result = Result.Rebuilt(target, before, after, moves, newPages.size, removed, reencoded, failedAt.map { names[it] })
            request.placer.place(result, out) {
                result.file = place(out, book, target, removed != null, request.replace, request.keepBoth)
                part = null
                if (time > 0) result.file.setLastModified(time)
            }
            return result
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
        val gate = LimitGate(limit)
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
                if (request.convert == null || !isPage(entry)) {
                    val piece = asIs(reader, entry, file)
                    // A page too large to convert that had to be: listed as failed.
                    val unconverted = request.convert != null && isImage(entry) && request.mustConvert(head(piece.file))
                    pieces[i] = if (unconverted) Piece(piece.file, piece.size, piece.crc, null, failed = true) else piece
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
                    pieces[i] = asIs(reader, entry, file).let { Piece(it.file, it.size, it.crc, null, failed = true) }
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
                                request.convert?.invoke(bytes)
                            } catch (e: Exception) {
                                failed = true
                                null
                            } catch (e: OutOfMemoryError) {
                                failed = true
                                null
                            }
                        }
                        val keep = made?.bytes ?: bytes
                        // A folder's file kept as it is goes into the new archive from where it is.
                        val own = if (made == null) reader.looseFile(entry) else null
                        if (own == null) {
                            ensureRoom(work, keep.size.toLong())
                            file.writeBytes(keep)
                        }
                        pieces[i] = Piece(own ?: file, keep.size.toLong(), crcOf(keep), made, failed)
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

    /**
     * [entry] as it is: a folder's file where it is, its size and CRC read; else copied to
     * [file]; either checked by the size and CRC its archive lists.
     */
    private fun asIs(reader: ArchiveReader, entry: ArchiveEntry, file: File): Piece {
        reader.looseFile(entry)?.let { own -> return reader.read(entry) { sum(it, own) }.also { checkRead(entry, it.size, it.crc) } }
        ensureRoom(file.parentFile!!, entry.size)
        return reader.read(entry) { copy(it, file) }.also { checkRead(entry, it.size, it.crc) }
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
                ensureRoom(out.parentFile!!, piece.size)
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
     * Puts [out] in place as [target], returning where it went: over [book] when it is the
     * book, or over a file there when [replace], else never over one: where [keepBoth] says
     * when given, else throws [java.nio.file.FileAlreadyExistsException]; [book] removed
     * when [remove].
     */
    private fun place(out: File, book: File, target: File, remove: Boolean, replace: Boolean, keepBoth: ((File) -> File)?): File {
        if (target == book || replace) {
            Files.move(out.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            if (remove && target != book && !book.delete() && book.exists()) throw IOException("Cannot remove ${book.name}")
            return target
        }
        val placed = try {
            Files.move(out.toPath(), target.toPath())
            target
        } catch (e: FileAlreadyExistsException) {
            val other = keepBoth?.invoke(target) ?: throw e
            Files.move(out.toPath(), other.toPath())
            other
        }
        if (remove && !book.delete() && book.exists()) throw IOException("Cannot remove ${book.name}")
        return placed
    }

    /** The archive comment of [book] as stored, kept in its new file: a ZIP's; other readers expose none. */
    private fun sourceComment(book: File): ByteArray =
        if (book.isFile && ArchiveKind.sniff(book) == ArchiveKind.ZIP) OutputMarks.commentOf(book) else ByteArray(0)

    private fun check(progress: Progress) {
        if (!progress.alive) throw CancellationException("Canceled")
    }

    /** Throws [ChangeRefused] (full) unless [bytes] more leave [SPARE_BYTES] free where [dir] is. */
    private fun ensureRoom(dir: File, bytes: Long) {
        if (free(dir) - bytes.coerceAtLeast(0) < SPARE_BYTES) throw ChangeRefused(ChangeRefused.Why.FULL)
    }

    /** Throws unless [entry] read as [size] bytes of [crc] is what its archive says it holds. */
    private fun checkRead(entry: ArchiveEntry, size: Long, crc: Long) {
        if ((entry.size >= 0 && size != entry.size) || (entry.crc >= 0 && crc != entry.crc)) throw IOException("${entry.path} is damaged")
    }

    /** True for a file read whole to be converted: a page ([isImage]) of a size held in memory. */
    private fun isPage(entry: ArchiveEntry): Boolean = isImage(entry) && entry.size in 1..maxPage

    /** True for a file named as an image that the reader shows: not hidden, in a dot-folder or empty (each kept as it is). */
    private fun isImage(entry: ArchiveEntry): Boolean = Formats.isImageName(entry.path) && !ArchiveJunk.isJunk(entry.path, entry.size)

    /** The first [HEAD_BYTES] of [file], fewer when it is shorter. */
    private fun head(file: File): ByteArray = file.inputStream().use { input ->
        val out = ByteArray(HEAD_BYTES)
        var n = 0
        while (n < out.size) {
            val r = input.read(out, n, out.size - n)
            if (r < 0) break
            n += r
        }
        out.copyOf(n)
    }

    /** [file] read through [input] for its size and CRC. */
    private fun sum(input: InputStream, file: File): Piece {
        val crc = CRC32()
        val buffer = ByteArray(COPY_BYTES)
        var size = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            crc.update(buffer, 0, n)
            size += n
        }
        return Piece(file, size, crc.value, null)
    }

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

    companion object {
        /** Pages larger than this are copied as they are. */
        const val MAX_PAGE_BYTES = 64L * 1024 * 1024

        private const val COPY_BYTES = 256 * 1024

        /** Bytes enough to tell an image's format ([Request.mustConvert]). */
        const val HEAD_BYTES = 64

        /** Room left free beyond what a book needs, for the system and other apps. */
        const val SPARE_BYTES = 256L * 1024 * 1024

        /** How much larger than its files the pieces of a book made compatible may grow: lossless JPEG XL becomes WebP or PNG. */
        private const val GROWTH = 2

        /** How old a new file left in a book's folder ([PART]) must be to be taken for one a run that ended early left. */
        const val STALE_PART_MS = 6L * 60 * 60 * 1000

        /** The new file of a book, written in its folder before it takes its place: `.<book>.<nanos>.part`. */
        private val PART = Regex("""^\..+\.\d+\.part$""")

        /** Deletes from [dir] new files ([PART]) that runs ending early (the app stopped) left there [STALE_PART_MS] ago or more. */
        fun sweep(dir: File, now: Long = System.currentTimeMillis()) {
            dir.listFiles { f -> f.isFile && PART.matches(f.name) && now - f.lastModified() >= STALE_PART_MS }?.forEach { it.delete() }
        }

        /** True when [a] and [b] seem on one volume: of the same size, as the shared storage and the app's own files are; or one store. */
        fun sameVolume(a: File, b: File): Boolean =
            a.totalSpace == b.totalSpace || runCatching { Files.getFileStore(a.toPath()) == Files.getFileStore(b.toPath()) }.getOrDefault(false)
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
