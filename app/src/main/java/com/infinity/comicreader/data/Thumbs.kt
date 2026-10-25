package com.infinity.comicreader.data

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.LruCache
import com.infinity.comicreader.reader.Decoders
import com.infinity.comicreader.reader.Fit
import com.infinity.comicreader.reader.HiddenPages
import com.infinity.comicreader.reader.NameCharset
import com.infinity.comicreader.reader.ImageSource
import com.infinity.comicreader.reader.BookDirection
import com.infinity.comicreader.reader.PageSources
import com.infinity.comicreader.reader.PdfSource
import com.infinity.comicreader.reader.Target
import com.infinity.comicreader.reader.Wraparound
import com.infinity.comicreader.net.RemoteFile
import com.infinity.comicreader.util.Formats
import com.infinity.comicreader.util.Hashes
import com.infinity.comicreader.util.NaturalOrder
import java.io.File

/**
 * Cover thumbnails for library rows. Images decode directly; archives and PDFs
 * render their first page not hidden ([coverPage]); folders fall back to the first image/openable file
 * found a couple of levels deep. Covers are made at exactly the size of the view
 * showing them: decoded at full size (up to [THUMB_CAP_PIXELS]; a PDF rendered
 * just covering the box), then made into the box by [CoverArt] as the [CoverStyle]
 * says, so the view draws them 1:1. They
 * are cached per size and style in memory (bounded by heap) and losslessly on disk
 * under `cacheDir/covers`. A bookmark's cover has its page in front ([CoverArt.withPage]).
 *
 * A cover page that is a layered image (a layered AVIF) can first give a quick cover made
 * from its first layer alone ([loadFirst]), kept in memory only, until [load] makes the
 * full one.
 */
object Thumbs {
    private const val DISK_DIR = "covers"

    /** Covers decode at full size below this; larger sources take the decoder's coarse reduction first. */
    private const val THUMB_CAP_PIXELS = 16_000_000L
    private const val MAX_DISK_BYTES = 160L * 1024 * 1024

    private val memory = object : LruCache<String, Bitmap>(maxBytes()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private fun maxBytes(): Int {
        val max = Runtime.getRuntime().maxMemory()
        return (max / 8L).toInt().coerceIn(8 * 1024 * 1024, 96 * 1024 * 1024)
    }

    /** Each wraparound's front half, by [key]; [NO_FRONT] when its barcode tells nothing. */
    private val fronts = LruCache<String, Int>(1024)
    private const val NO_FRONT = -1

    /** The front half of wraparound cover [src] of [entry], found once per cover file. */
    private fun frontOf(entry: BookEntry, src: Bitmap): Wraparound.Half? {
        val k = key(entry)
        val known = fronts.get(k) ?: (CoverArt.frontOf(src)?.ordinal ?: NO_FRONT).also { fronts.put(k, it) }
        return Wraparound.Half.entries.getOrNull(known)
    }

    /** True once the front half of [file]'s cover was looked for this session, whatever was found. */
    fun frontKnown(file: File): Boolean = fronts.get(key(file)) != null

    /** The front half of [file]'s wraparound cover as found this session; null when unknown or none. */
    fun knownFront(file: File): Wraparound.Half? = fronts.get(key(file))?.let { Wraparound.Half.entries.getOrNull(it) }

    /** Remembers [front] as what [file]'s cover shows, so neither covers nor the reader scan it again. */
    fun rememberFront(file: File, front: Wraparound.Half?) {
        fronts.put(key(file), front?.ordinal ?: NO_FRONT)
    }

    /** Quick covers ([loadFirst]) by their cover's key; a few screens' worth. */
    private val quicks = object : LruCache<String, Bitmap>(QUICK_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private const val QUICK_BYTES = 24 * 1024 * 1024

    /**
     * Whether each cover made this session depends on its book's direction, by [key]: true for a
     * wraparound without a barcode ([CoverArt.cover]). One that does not is found under either
     * direction's key ([directionMatters]).
     */
    private val directional = LruCache<String, Boolean>(4096)

    /** False once [entry]'s cover was made this session and shows the same whichever way the book reads. */
    fun directionMatters(entry: BookEntry): Boolean = directional.get(key(entry)) ?: true

    /** Each book's typical inner page shape ([Wraparound.innerRatio]) by [key]; NaN when it has none. */
    private val inners = LruCache<String, Double>(1024)

    private fun key(entry: BookEntry): String = key(entry.path, entry.size, entry.lastModified, entry.isDir)

    private fun key(file: File): String = key(file.absolutePath, file.length(), file.lastModified(), false)

    private fun key(path: String, size: Long, lastModified: Long, isDir: Boolean): String = "$path|$size|$lastModified|$isDir"

    /**
     * The cover of [entry], exactly [width] x [height]. Blocking — call from a background
     * dispatcher. Returns null when no cover exists, or once [canceled] turns true between
     * its steps (nothing is kept then). A wraparound without a barcode shows the half its
     * [BookEntry.direction] starts on, so the direction is part of the key.
     */
    fun load(
        context: Context,
        entry: BookEntry,
        charset: String,
        width: Int,
        height: Int,
        style: CoverStyle = CoverStyle(),
        canceled: () -> Boolean = { false }
    ): Bitmap? = make(context, entry, charset, width, height, style, canceled, quickFirst = false)?.bitmap

    /** A cover, and whether it is the [full] one or a quick one standing in for it. */
    class Cover(val bitmap: Bitmap, val full: Boolean)

    /**
     * The cover of [entry] as [load] makes it, or when its cover page is a layered image
     * ([PageSource.loadFirstLayer]) and no full cover was made yet, a quick one made the same
     * way from its first layer alone ([Cover.full] false), for [load] to replace. Blocking.
     */
    fun loadFirst(
        context: Context,
        entry: BookEntry,
        charset: String,
        width: Int,
        height: Int,
        style: CoverStyle = CoverStyle(),
        canceled: () -> Boolean = { false }
    ): Cover? {
        cached(context, entry, width, height, style)?.let { return Cover(it, full = true) }
        either(context, entry, width, height, style) { quicks.get(it) }?.let { return Cover(it, full = false) }
        return make(context, entry, charset, width, height, style, canceled, quickFirst = entry.bookmarkPage < 0)
    }

    /** The cover of [entry] ([load]); with [quickFirst], a quick one when its cover page has a first layer ([loadFirst]). */
    private fun make(
        context: Context,
        entry: BookEntry,
        charset: String,
        width: Int,
        height: Int,
        style: CoverStyle,
        canceled: () -> Boolean,
        quickFirst: Boolean
    ): Cover? {
        cached(context, entry, width, height, style)?.let { return Cover(it, full = true) }
        val page = entry.bookmarkPage
        val rightToLeft = entry.direction == ReadingDirection.RTL
        val k = coverKey(context, entry, width, height, style)
        val disk = diskFile(context, k)

        val hidden = Library(context).hiddenAll()
        if (canceled()) return null
        val made = generate(entry, charset, context.cacheDir, width, height, hidden, canceled, quickFirst) ?: return null
        val full = made.bitmap
        if (canceled()) {
            full.recycle()
            return null
        }
        var sided = false
        val cover = try {
            CoverArt.cover(full, width, height, style, made.innerRatio, rightToLeft) { src ->
                frontOf(entry, src).also { if (it == null) sided = true }
            }
        } catch (t: Throwable) {
            return null
        } finally {
            full.recycle()
        }
        if (!made.full) {
            quicks.put(k, cover)
            return Cover(cover, full = false)
        }
        quicks.remove(k)
        directional.put(key(entry), sided)
        val bitmap = if (page < 0) cover else {
            val (w, h) = CoverArt.pageBox(width, height)
            val front = pageOf(entry.file, bookPage(context, entry), charset, context.cacheDir, Target(w, h, Fit.INSIDE, linear = style.linear, scaling = style.scaling))
            if (front == null) cover else CoverArt.withPage(cover, front).also { front.recycle() }
        }
        memory.put(k, bitmap)
        runCatching {
            disk.parentFile?.mkdirs()
            disk.outputStream().use { bitmap.compress(LOSSLESS, LOSSLESS_EFFORT, it) }
        }
        return Cover(bitmap, full = true)
    }

    /**
     * The cover page of book [entry] whole, made to [target] as a page is, for sharing:
     * never cropped, framed or cached. Blocking; null when it has none or cannot be read.
     */
    fun shareCover(context: Context, entry: BookEntry, charset: String, target: Target): Bitmap? = try {
        if (entry.isDir) null else {
            val page = coverPage(Library(context).hiddenPages(entry.path))
            coverOf(entry.file, charset, context.cacheDir, target.width, target.height, page, full = target, inner = false)?.bitmap
        }
    } catch (t: Throwable) {
        null
    }

    /** The cover of [entry] as [load] makes it, when it is held in memory; null otherwise. Quick enough for the main thread. */
    fun inMemory(context: Context, entry: BookEntry, width: Int, height: Int, style: CoverStyle = CoverStyle()): Bitmap? =
        either(context, entry, width, height, style) { memory.get(it) }

    /**
     * The cover of [entry] as [load] makes it, when one was made before: from memory or
     * disk, never generated. Blocking; null when there is none yet.
     */
    fun cached(context: Context, entry: BookEntry, width: Int, height: Int, style: CoverStyle = CoverStyle()): Bitmap? {
        inMemory(context, entry, width, height, style)?.let { return it }
        return either(context, entry, width, height, style) { k ->
            val disk = diskFile(context, k)
            if (!disk.isFile) return@either null
            // Stored at the box size: the decode is the file as it is.
            runCatching { Decoders.decode(disk, Target(width, height, Fit.STRETCH)) }.getOrNull()?.also { memory.put(k, it) }
        }
    }

    /**
     * What [find] gives for the key of [entry]'s cover, else for the other direction's key when
     * the cover shows the same either way ([directionMatters]).
     */
    private fun <T> either(context: Context, entry: BookEntry, width: Int, height: Int, style: CoverStyle, find: (String) -> T?): T? {
        val rightToLeft = entry.direction == ReadingDirection.RTL
        find(coverKey(context, entry, width, height, style, rightToLeft))?.let { return it }
        if (directionMatters(entry)) return null
        return find(coverKey(context, entry, width, height, style, !rightToLeft))
    }

    /** The book index of [entry]'s bookmarked page, which counts pages as shown ([Library.hiddenPages]). */
    private fun bookPage(context: Context, entry: BookEntry): Int =
        HiddenPages.toBook(entry.bookmarkPage, Library(context).hiddenPages(entry.path))

    /**
     * What a cover is cached by: its file, box size, style, the direction it starts on, the
     * hidden pages that move it ([hiddenTag]), a bookmark's page in the book, and the book's
     * own file name charset, which may reorder its pages ([NameCharset.overrides]).
     */
    private fun coverKey(
        context: Context,
        entry: BookEntry,
        width: Int,
        height: Int,
        style: CoverStyle,
        rightToLeft: Boolean = entry.direction == ReadingDirection.RTL
    ): String {
        val page = if (entry.bookmarkPage >= 0) bookPage(context, entry) else -1
        val hidden = hiddenTag(entry, Library(context).hiddenAll())
        val names = NameCharset.overrides[entry.path]?.let { "|n$it" }.orEmpty()
        return "${key(entry)}|${width}x$height|${style.key}|${if (rightToLeft) "rtl" else "ltr"}$hidden$names" + if (page >= 0) "|p$page" else ""
    }

    /** The book index of the cover of a book hiding [hidden]: its first page not hidden. */
    fun coverPage(hidden: Set<Int>): Int {
        var page = 0
        while (page in hidden) page++
        return page
    }

    /**
     * What [hidden] (pages hidden by book path) adds to the key of [entry]'s cover: a book's
     * cover page when it is not the first, and for a folder, which books below it start later.
     * Empty when no hidden page moves the cover, so such keys stay as they were.
     */
    internal fun hiddenTag(entry: BookEntry, hidden: Map<String, Set<Int>>): String {
        if (hidden.isEmpty() || (!entry.isDir && entry.type == EntryType.IMAGE)) return ""
        if (!entry.isDir) return coverPage(hidden[entry.path].orEmpty()).takeIf { it > 0 }?.let { "|c$it" }.orEmpty()
        val dir = entry.path
        val moved = hidden.entries
            .filter { (path, pages) -> path.length > dir.length && path.startsWith(dir) && isSeparator(path[dir.length]) && coverPage(pages) > 0 }
            .sortedBy { it.key }
        if (moved.isEmpty()) return ""
        return "|h" + Hashes.sha256Hex(moved.joinToString("\u0000") { (path, pages) -> path + ":" + coverPage(pages) }).take(16)
    }

    /** WebP lossless where the platform writes it, PNG before that. */
    private val LOSSLESS =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.PNG

    /** For lossless formats the quality is encoder effort: low keeps writing fast. */
    private const val LOSSLESS_EFFORT = 25

    /**
     * Keeps the on-disk cover cache under [maxBytes] by deleting the least recently written
     * files first. Without this the cache only ever grows.
     */
    fun trim(context: Context, maxBytes: Long = MAX_DISK_BYTES) {
        val files = File(context.cacheDir, DISK_DIR).listFiles() ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= maxBytes) break
            val size = f.length()
            if (f.delete()) total -= size
        }
    }

    fun clear(context: Context) {
        memory.evictAll()
        quicks.evictAll()
        inners.evictAll()
        fronts.evictAll()
        directional.evictAll()
        runCatching { File(context.cacheDir, DISK_DIR).deleteRecursively() }
    }

    // ------------------------------------------------------------------ internals

    /** A cover image as decoded, with its book's typical inner page shape; not [full] when it is a first layer. */
    private class Made(val bitmap: Bitmap, val innerRatio: Double?, val full: Boolean = true)

    /**
     * The cover image of [entry] at full size, up to [THUMB_CAP_PIXELS] (a PDF's
     * rendered just large enough for a [width] x [height] box), with its book's
     * typical inner page shape when the cover is wide enough to be a wraparound.
     * With [quickFirst], the first layer of a layered cover page instead.
     */
    private fun generate(
        entry: BookEntry,
        charset: String,
        cacheDir: File,
        width: Int,
        height: Int,
        hidden: Map<String, Set<Int>>,
        canceled: () -> Boolean = { false },
        quickFirst: Boolean = false
    ): Made? = try {
        fun pageOf(book: File) = coverPage(hidden[book.absolutePath].orEmpty())
        when {
            entry.isDir -> findCover(entry.file, 2)?.takeIf { !canceled() }?.let {
                coverOf(it, charset, cacheDir, width, height, pageOf(it), canceled, quickFirst)
            }
            entry.type == EntryType.IMAGE -> imageCover(entry.file, quickFirst)
            else -> coverOf(entry.file, charset, cacheDir, width, height, pageOf(entry.file), canceled, quickFirst)
        }
    } catch (t: Throwable) {
        null
    }

    /** Image [file] as its own cover; with [quickFirst], the first layer of a layered one stands in. */
    private fun imageCover(file: File, quickFirst: Boolean): Made {
        if (quickFirst && file !is RemoteFile) ImageSource(file).loadFirstLayer(0)?.let { return Made(it, null, full = false) }
        return Made(Decoders.decode(file, Target(capPixels = THUMB_CAP_PIXELS)), null)
    }

    /**
     * The cover of book [file]: its page [page] (book index), or its first page when it has
     * fewer, made to [full]; with [inner], the book's inner page shape when it could be a
     * wraparound.
     */
    private fun coverOf(
        file: File,
        charset: String,
        cacheDir: File,
        width: Int,
        height: Int,
        page: Int = 0,
        canceled: () -> Boolean = { false },
        quickFirst: Boolean = false,
        full: Target = Target(capPixels = THUMB_CAP_PIXELS),
        inner: Boolean = true
    ): Made? {
        if (!file.isFile || !Formats.isOpenable(file.name)) return null
        if (Formats.typeOf(file.name) == EntryType.IMAGE) return if (full.width > 0) Made(Decoders.decode(file, full), null) else imageCover(file, quickFirst)
        val source = runCatching { PageSources.openCover(file, charset, cacheDir, page) }.getOrNull() ?: return null
        var layered = false
        val cover = try {
            if (canceled()) return null
            val index = if (page < source.pageCount) page else 0
            val first = if (quickFirst && source.pageCount > 0) runCatching { source.loadFirstLayer(index) }.getOrNull() else null
            layered = first != null
            val target = if (!source.isVector) full else {
                source.pageSize(index)?.let { PdfSource.coverTarget(it[0], it[1], width, height, THUMB_CAP_PIXELS) } ?: full
            }
            first ?: if (source.pageCount <= 0) null else source.loadPage(index, target)
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { source.close() }
        } ?: return null
        if (canceled()) {
            cover.recycle()
            return null
        }
        val ratio = if (inner && Wraparound.isCandidate(cover.width, cover.height)) innerRatioOf(file, charset, cacheDir) else null
        return Made(cover, ratio, full = !layered)
    }

    /**
     * The typical inner page shape of the book [file] ([Wraparound.innerRatio]), found once a
     * session; null when it has none or cannot be read.
     */
    private fun innerRatioOf(file: File, charset: String, cacheDir: File): Double? {
        val k = key(file)
        inners.get(k)?.let { return it.takeIf { !it.isNaN() } }
        val source = runCatching { PageSources.open(file, charset, cacheDir, copyNested = false) }.getOrNull() ?: return null
        val found = try {
            BookDirection.innerRatio(source)
        } catch (t: Throwable) {
            return null
        } finally {
            runCatching { source.close() }
        }
        inners.put(k, found ?: Double.NaN)
        return found
    }

    /** Page [index] of the book [file] (its last page if it has fewer) made to [target]; null when it cannot be read. */
    private fun pageOf(file: File, index: Int, charset: String, cacheDir: File, target: Target): Bitmap? {
        if (!file.isFile) return null
        val source = runCatching { PageSources.open(file, charset, cacheDir) }.getOrNull() ?: return null
        return try {
            if (source.pageCount <= 0) null else source.loadPage(index.coerceIn(0, source.pageCount - 1), target)
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { source.close() }
        }
    }

    private fun findCover(dir: File, depth: Int): File? {
        val files = dir.listFiles()?.filterNot { it.name.startsWith(".") } ?: return null
        files.firstOrNull { it.isFile && Formats.typeOf(it.name) == EntryType.IMAGE }?.let { return it }
        files.filter { it.isFile && Formats.isOpenable(it.name) }
            .minWithOrNull(compareBy(NaturalOrder) { it.name })
            ?.let { return it }
        if (depth <= 0) return null
        val subs = files.filter { it.isDirectory }
            .sortedWith(compareBy(NaturalOrder) { it.name })
            .take(8)
        for (sub in subs) findCover(sub, depth - 1)?.let { return it }
        return null
    }

    private fun diskFile(context: Context, key: String): File =
        File(File(context.cacheDir, DISK_DIR), Hashes.sha256Hex(key) + if (LOSSLESS == Bitmap.CompressFormat.PNG) ".png" else ".webp")
}
