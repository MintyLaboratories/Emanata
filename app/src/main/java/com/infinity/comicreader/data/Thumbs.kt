package com.infinity.comicreader.data

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.LruCache
import com.infinity.comicreader.reader.Decoders
import com.infinity.comicreader.reader.Fit
import com.infinity.comicreader.reader.BookDirection
import com.infinity.comicreader.reader.PageSources
import com.infinity.comicreader.reader.PdfSource
import com.infinity.comicreader.reader.Target
import com.infinity.comicreader.reader.Wraparound
import com.infinity.comicreader.util.Formats
import com.infinity.comicreader.util.Hashes
import com.infinity.comicreader.util.NaturalOrder
import java.io.File

/**
 * Cover thumbnails for library rows. Images decode directly; archives and PDFs
 * render their first page; folders fall back to the first image/openable file
 * found a couple of levels deep. Covers are made at exactly the size of the view
 * showing them: decoded at full size (up to [THUMB_CAP_PIXELS]; a PDF rendered
 * just covering the box), then made into the box by [CoverArt] as the [CoverStyle]
 * says, so the view draws them 1:1. They
 * are cached per size and style in memory (bounded by heap) and losslessly on disk
 * under `cacheDir/covers`. A bookmark's cover has its page in front ([CoverArt.withPage]).
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

    private fun key(entry: BookEntry): String = key(entry.path, entry.size, entry.lastModified, entry.isDir)

    private fun key(file: File): String = key(file.absolutePath, file.length(), file.lastModified(), false)

    private fun key(path: String, size: Long, lastModified: Long, isDir: Boolean): String = "$path|$size|$lastModified|$isDir"

    /**
     * The cover of [entry], exactly [width] x [height]. Blocking — call from a background
     * dispatcher. Returns null when no cover exists. A wraparound without a barcode shows
     * the half its [BookEntry.direction] starts on, so the direction is part of the key.
     */
    fun load(context: Context, entry: BookEntry, charset: String, width: Int, height: Int, style: CoverStyle = CoverStyle()): Bitmap? {
        cached(context, entry, width, height, style)?.let { return it }
        val page = entry.bookmarkPage
        val rightToLeft = entry.direction == ReadingDirection.RTL
        val k = coverKey(entry, width, height, style)
        val disk = diskFile(context, k)

        val (full, innerRatio) = generate(entry, charset, context.cacheDir, width, height) ?: return null
        val cover = try {
            CoverArt.cover(full, width, height, style, innerRatio, rightToLeft) { src -> frontOf(entry, src) }
        } catch (t: Throwable) {
            return null
        } finally {
            full.recycle()
        }
        val bitmap = if (page < 0) cover else {
            val (w, h) = CoverArt.pageBox(width, height)
            val front = pageOf(entry.file, page, charset, context.cacheDir, Target(w, h, Fit.INSIDE, linear = style.linear))
            if (front == null) cover else CoverArt.withPage(cover, front).also { front.recycle() }
        }
        memory.put(k, bitmap)
        runCatching {
            disk.parentFile?.mkdirs()
            disk.outputStream().use { bitmap.compress(LOSSLESS, LOSSLESS_EFFORT, it) }
        }
        return bitmap
    }

    /**
     * The cover of [entry] as [load] makes it, when one was made before: from memory or
     * disk, never generated. Blocking; null when there is none yet.
     */
    fun cached(context: Context, entry: BookEntry, width: Int, height: Int, style: CoverStyle = CoverStyle()): Bitmap? {
        val k = coverKey(entry, width, height, style)
        memory.get(k)?.let { return it }
        val disk = diskFile(context, k)
        if (!disk.isFile) return null
        // Stored at the box size: the decode is the file as it is.
        val cached = runCatching { Decoders.decode(disk, Target(width, height, Fit.STRETCH)) }.getOrNull() ?: return null
        memory.put(k, cached)
        return cached
    }

    /** What a cover is cached by: its file, box size, style, the direction it starts on, and a bookmark's page. */
    private fun coverKey(entry: BookEntry, width: Int, height: Int, style: CoverStyle): String {
        val page = entry.bookmarkPage
        val rightToLeft = entry.direction == ReadingDirection.RTL
        return "${key(entry)}|${width}x$height|${style.key}|${if (rightToLeft) "rtl" else "ltr"}" + if (page >= 0) "|p$page" else ""
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
        fronts.evictAll()
        runCatching { File(context.cacheDir, DISK_DIR).deleteRecursively() }
    }

    // ------------------------------------------------------------------ internals

    /**
     * The cover image of [entry] at full size, up to [THUMB_CAP_PIXELS] (a PDF's
     * rendered just large enough for a [width] x [height] box), with its book's
     * typical inner page shape when the cover is wide enough to be a wraparound.
     */
    private fun generate(entry: BookEntry, charset: String, cacheDir: File, width: Int, height: Int): Pair<Bitmap, Double?>? = try {
        val target = Target(capPixels = THUMB_CAP_PIXELS)
        when {
            entry.isDir -> findCover(entry.file, 2)?.let { coverOf(it, charset, cacheDir, width, height) }
            entry.type == EntryType.IMAGE -> Decoders.decode(entry.file, target) to null
            else -> coverOf(entry.file, charset, cacheDir, width, height)
        }
    } catch (t: Throwable) {
        null
    }

    private fun coverOf(file: File, charset: String, cacheDir: File, width: Int, height: Int): Pair<Bitmap, Double?>? {
        if (!file.isFile || !Formats.isOpenable(file.name)) return null
        val full = Target(capPixels = THUMB_CAP_PIXELS)
        if (Formats.typeOf(file.name) == EntryType.IMAGE) {
            return Decoders.decode(file, full) to null
        }
        val source = runCatching { PageSources.openCover(file, charset, cacheDir) }.getOrNull() ?: return null
        val cover = try {
            val target = if (!source.isVector) full else {
                source.pageSize(0)?.let { PdfSource.coverTarget(it[0], it[1], width, height, THUMB_CAP_PIXELS) } ?: full
            }
            if (source.pageCount <= 0) null else source.loadPage(0, target)
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { source.close() }
        } ?: return null
        val inner = if (Wraparound.isCandidate(cover.width, cover.height)) innerRatioOf(file, charset, cacheDir) else null
        return cover to inner
    }

    /** The typical inner page shape of the book [file] ([Wraparound.innerRatio]); null when it has none or cannot be read. */
    private fun innerRatioOf(file: File, charset: String, cacheDir: File): Double? {
        val source = runCatching { PageSources.open(file, charset, cacheDir, copyNested = false) }.getOrNull() ?: return null
        return try {
            BookDirection.innerRatio(source)
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { source.close() }
        }
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
