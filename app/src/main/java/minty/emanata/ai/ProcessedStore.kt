package minty.emanata.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import minty.emanata.compact.LibJxl
import minty.emanata.data.BookId
import minty.emanata.reader.JxlPages
import minty.emanata.reader.Target
import minty.emanata.util.CacheFiles
import minty.emanata.util.Traces
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** How processed pages are kept on disk: losslessly, as files ending [ext]. */
interface PageCodec {
    val ext: String

    /** [page], opaque, as a file's bytes; null when it cannot be written. */
    fun encode(page: Bitmap): ByteArray?

    /** The [width] x [height] page in [bytes]; null when they do not hold one that size. */
    fun decode(bytes: ByteArray, width: Int, height: Int): Bitmap?

    companion object {
        /** Lossless JPEG XL where libjxl loads, else PNG. */
        val best: PageCodec by lazy { if (JxlPageCodec.available) JxlPageCodec else PngPageCodec }
    }
}

/**
 * Lossless JPEG XL at effort 1, libjxl's fast lossless path: written in a fraction of PNG's
 * time, read back with the reader's own JPEG XL decoder ([JxlPages]).
 */
object JxlPageCodec : PageCodec {
    override val ext = ".jxl"

    /** libjxl's fastest effort: its dedicated lossless path. */
    private const val EFFORT = 1

    /** True when libjxl loads as the release our binding was built against, and the reader decodes JPEG XL natively. */
    val available: Boolean by lazy { runCatching { LibJxl.available && JxlPages.enabled }.getOrDefault(false) }

    override fun encode(page: Bitmap): ByteArray? = LibJxl.encode(rgbOf(page), page.width, page.height, 3, 8, null, EFFORT)

    /** Rows of [page] read at a time by [rgbOf]. */
    private const val ROWS = 64

    /**
     * [page]'s pixels as packed 8-bit RGB, read [ROWS] rows at a time, so the only large
     * buffer is the result (3 bytes a pixel, not another 4 for a whole RGBA copy).
     */
    internal fun rgbOf(page: Bitmap): ByteArray {
        val w = page.width
        val h = page.height
        val rgb = ByteArray(w * h * 3)
        val rows = IntArray(w * minOf(ROWS, h))
        var o = 0
        var y = 0
        while (y < h) {
            val n = minOf(ROWS, h - y)
            page.getPixels(rows, 0, w, 0, y, w, n)
            for (i in 0 until w * n) {
                val c = rows[i]
                rgb[o] = (c shr 16).toByte()
                rgb[o + 1] = (c shr 8).toByte()
                rgb[o + 2] = c.toByte()
                o += 3
            }
            y += n
        }
        return rgb
    }

    override fun decode(bytes: ByteArray, width: Int, height: Int): Bitmap? = JxlPages.decode(bytes, width, height, urgent = false)
}

/** PNG through the platform: slower, but everywhere. */
object PngPageCodec : PageCodec {
    override val ext = ".png"

    override fun encode(page: Bitmap): ByteArray? =
        ByteArrayOutputStream().use { out -> if (page.compress(Bitmap.CompressFormat.PNG, 100, out)) out.toByteArray() else null }

    override fun decode(bytes: ByteArray, width: Int, height: Int): Bitmap? =
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, DECODE)?.takeIf { it.width == width && it.height == height }

    private val DECODE = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
}

/**
 * Pages processed by AI ([AiPages.chain]) on disk under [dir], one file per page at the size
 * it shows, named after the book ([CacheFiles.nameOf]) so [forget] finds all of one book's,
 * and a hash of its key ([keyOf]): the book, page, size and crop shown, the chain with its
 * models' versions, and which of its models ran, so nothing stale is ever read. Kept under [maxBytes], and a tenth of the
 * space it and the free space take together ([limit]), by dropping the least recently read
 * first, every [trimEvery] writes and at startup. Writes run on [writer], off the
 * AI thread; a file appears whole or not at all.
 */
class ProcessedStore(
    val dir: File,
    private val codec: PageCodec = PageCodec.best,
    private val writer: Executor = WRITER,
    private val maxBytes: Long = MAX_BYTES,
    private val trimEvery: Int = TRIM_EVERY
) {
    /** Keys handed to [put] whose file is not written yet. */
    private val pending: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())

    /** True when [key] has a page, written or on its way. */
    fun has(key: String): Boolean = key in pending || fileOf(key).isFile

    /** The [width] x [height] page stored for [key]; null when there is none or it does not read. Blocking. */
    fun get(key: String, width: Int, height: Int): Bitmap? {
        val file = fileOf(key)
        if (!file.isFile) return null
        val bitmap = Traces.section("processed read") {
            runCatching { codec.decode(file.readBytes(), width, height) }.getOrNull()
        }
        if (bitmap == null) {
            file.delete()
            return null
        }
        bitmap.setHasAlpha(false)
        writer.execute { CacheFiles.touch(file) }
        return bitmap
    }

    /** Stores [page] for [key] in the background; [page] is only read, never recycled, so it may show meanwhile. */
    fun put(key: String, page: Bitmap) {
        if (!pending.add(key)) return
        writer.execute {
            try {
                write(fileOf(key), page)
            } finally {
                pending.remove(key)
            }
        }
    }

    private fun write(file: File, page: Bitmap) {
        if (page.isRecycled) return
        val bytes = Traces.section("processed write") { runCatching { codec.encode(page) }.getOrNull() } ?: return
        val part = File(file.path + PART_SUFFIX)
        runCatching {
            file.parentFile?.mkdirs()
            part.writeBytes(bytes)
            if (!part.renameTo(file)) part.delete()
        }.onFailure { part.delete() }
        if (WRITES.incrementAndGet() % trimEvery == 0) trim(dir, maxBytes)
    }

    private fun fileOf(key: String) = File(dir, CacheFiles.nameOf(key, key, ends = '|') + codec.ext)

    companion object {
        private const val DIR = "ai-pages"
        private const val PART_SUFFIX = ".part"
        private const val STALE_PART_MS = 10L * 60 * 1000

        /** Upper bound of the store on disk: some two hundred pages at a large tablet's size, a few books' worth. */
        const val MAX_BYTES = 1024L * 1024 * 1024

        /** The most of the space it and the free space take together the store keeps. */
        const val FREE_SHARE = 0.1

        /** The store's limit holding [held] bytes with [free] bytes free on its disk: [maxBytes] at most, else [FREE_SHARE] of both. */
        fun limit(held: Long, free: Long, maxBytes: Long = MAX_BYTES): Long = minOf(maxBytes, ((held + free) * FREE_SHARE).toLong())

        /** Writes between trims of a store while the app runs; it is trimmed at startup too. */
        const val TRIM_EVERY = 16

        private val WRITES = AtomicInteger()

        /** One background thread writes every store's pages. */
        private val WRITER: Executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "ai-page-writer").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }
        }

        fun dirIn(cacheDir: File) = File(cacheDir, DIR)

        /**
         * Key of page [index], stored as [entry] (null when unnamed), of book [book] (its Book
         * ID key), made for [target] at [width] x [height] by the chain [signature] names
         * running [run]: the models that ran ([AiPages.runKey]), or where they are not known
         * before the page's decode, the thresholds that decide them ([AiPages.handles]).
         */
        fun keyOf(book: String, index: Int, entry: String?, target: Target, width: Int, height: Int, signature: String, run: String): String =
            "$book|$index|${entry.orEmpty()}|${width}x$height|$target|$signature|$run"

        /**
         * Keeps [dir] under [maxBytes] and its [limit] with [free] bytes free, least recently
         * read first; partial writes left by an earlier run go too.
         */
        fun trim(dir: File, maxBytes: Long = MAX_BYTES, now: Long = System.currentTimeMillis(), free: Long = freeOn(dir)) {
            val files = dir.listFiles() ?: return
            files.filter { it.name.endsWith(PART_SUFFIX) && it.lastModified() < now - STALE_PART_MS }.forEach { it.delete() }
            val held = files.sumOf { if (it.isFile) it.length() else 0L }
            CacheFiles.trim(dir, limit(held, free, maxBytes)) { it.name.endsWith(PART_SUFFIX) }
        }

        /** Bytes free to the app on [dir]'s disk; unknown counts as plenty. */
        private fun freeOn(dir: File): Long = runCatching { dir.usableSpace }.getOrDefault(0L).takeIf { it > 0 } ?: Long.MAX_VALUE / 4

        /** Deletes the processed pages of book [id] in [dir]. */
        fun forget(dir: File, id: BookId) = CacheFiles.forget(dir, id.hex)

        fun clear(dir: File) {
            runCatching { dir.deleteRecursively() }
        }
    }
}
