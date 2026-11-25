package minty.emanata.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import minty.emanata.data.BookId
import minty.emanata.data.BookIds
import minty.emanata.util.CacheFiles
import minty.emanata.util.Traces
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Page previews ([Previews]) on disk under [dir], one JPEG per page, named after the book
 * ([CacheFiles.nameOf]), so [forget] finds all of one book's, and a hash of
 * the book's identity ([bookId]), the page's index and entry name, and the preview size,
 * so a changed book or size never reads a stale one, and a book moved or copied keeps its.
 * Writes run on [writer], off the decoding thread; a file appears whole or not at all.
 */
class PreviewStore(
    val dir: File,
    private val writer: Executor = WRITER,
    private val maxBytes: Long = MAX_BYTES,
    private val trimEvery: Int = TRIM_EVERY
) {

    /** Keys handed to [put] whose file is not written yet. */
    private val pending: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())

    /** True when page [key] has a preview, written or on its way. */
    fun has(key: String): Boolean = key in pending || fileOf(key).isFile

    /** The preview stored for [key]; null when there is none or it does not decode. */
    fun get(key: String): Bitmap? {
        val file = fileOf(key)
        if (!file.isFile) return null
        val bitmap = runCatching { BitmapFactory.decodeFile(file.path, DECODE) }.getOrNull() ?: return null
        // Recently read previews outlive others when the store is trimmed.
        writer.execute { file.setLastModified(System.currentTimeMillis()) }
        return bitmap
    }

    /** Stores [preview] for [key] in the background; [preview] is only read, never recycled. */
    fun put(key: String, preview: Bitmap) {
        if (!pending.add(key)) return
        writer.execute {
            try {
                write(fileOf(key), preview)
            } finally {
                pending.remove(key)
            }
        }
    }

    /** True when [key] has no preview yet and the writer has room for one more ([MAX_PENDING]). */
    fun wants(key: String): Boolean = pending.size < MAX_PENDING && !has(key)

    /**
     * Makes [key]'s preview with [make] on the writer and stores it, unless [wants] says
     * no; false then, and [make] never runs. What [make] returns is recycled once written.
     */
    fun putMade(key: String, make: () -> Bitmap?): Boolean {
        if (!wants(key) || !pending.add(key)) return false
        writer.execute {
            try {
                val preview = runCatching { make() }.getOrNull()
                if (preview != null) {
                    write(fileOf(key), preview)
                    preview.recycle()
                }
            } finally {
                pending.remove(key)
            }
        }
        return true
    }

    /** Writes [preview] to [file], trimming the store every [trimEvery] writes, so a long-running app stays under [maxBytes]. */
    private fun write(file: File, preview: Bitmap) {
        Traces.section("preview write") { store(file, preview) }
        if (WRITES.incrementAndGet() % trimEvery == 0) trim(dir, maxBytes)
    }

    private fun store(file: File, preview: Bitmap) {
        val opaque = if (!preview.hasAlpha()) preview else {
            Bitmap.createBitmap(preview.width, preview.height, Bitmap.Config.ARGB_8888).also {
                Canvas(it).apply { drawColor(Color.WHITE) }.drawBitmap(preview, 0f, 0f, null)
            }
        }
        val part = File(file.path + PART_SUFFIX)
        runCatching {
            file.parentFile?.mkdirs()
            part.outputStream().use { opaque.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
            if (!part.renameTo(file)) part.delete()
        }.onFailure { part.delete() }
        if (opaque !== preview) opaque.recycle()
    }

    private fun fileOf(key: String) = fileIn(dir, key)

    companion object {
        private const val DIR = "page-previews"
        private const val PART_SUFFIX = ".part"
        private const val QUALITY = 85
        private const val STALE_PART_MS = 10L * 60 * 1000

        /** Previews made on the writer that may wait at once; each holds a half-size page until made. */
        const val MAX_PENDING = 4

        /** Writes between trims of a store ([trim]) while the app runs; it is trimmed at startup too. */
        const val TRIM_EVERY = 256

        /** Previews written since the app started, by every store. */
        private val WRITES = AtomicInteger()

        /** Upper bound of the store on disk: some ten thousand pages. */
        const val MAX_BYTES = 128L * 1024 * 1024

        private val DECODE = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }

        /** One background thread writes every store's previews. */
        private val WRITER: Executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "preview-writer").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }
        }

        fun dirIn(cacheDir: File) = File(cacheDir, DIR)

        /** What identifies [file]'s contents: its Book ID ([BookIds.keyOf]). Blocking: an unseen file is read. */
        fun bookId(file: File): String = BookIds.keyOf(file)

        /** Key of the preview of page [index], stored as [entry] (null when unnamed), of book [bookId]. */
        fun keyOf(bookId: String, index: Int, entry: String?): String = "$bookId|$index|${entry.orEmpty()}|${Previews.BOX}"

        /**
         * Keeps the store under [maxBytes] by deleting the least recently used previews
         * first; partial writes left by an earlier run go too.
         */
        fun trim(dir: File, maxBytes: Long = MAX_BYTES, now: Long = System.currentTimeMillis()) {
            val files = dir.listFiles() ?: return
            files.filter { it.name.endsWith(PART_SUFFIX) && it.lastModified() < now - STALE_PART_MS }.forEach { it.delete() }
            // Each file's size and time read once: one touched meanwhile cannot upset the order.
            val kept = files.filter { it.name.endsWith(".jpg") && it.isFile }.map { Triple(it, it.length(), it.lastModified()) }
            var total = kept.sumOf { it.second }
            if (total <= maxBytes) return
            for ((f, size, _) in kept.sortedBy { it.third }) {
                if (total <= maxBytes) break
                if (f.delete()) total -= size
            }
        }

        /** Deletes the previews of book [id] in [dir]. */
        fun forget(dir: File, id: BookId) = CacheFiles.forget(dir, id.hex)

        /** The file in [dir] of the preview keyed [key] ([keyOf]). */
        private fun fileIn(dir: File, key: String) = File(dir, CacheFiles.nameOf(key, key, ends = '|') + ".jpg")

        /**
         * Gives book [to] with pages [toNames] the previews in [dir] of book [from] with pages
         * [fromNames]: page `i`'s as page [map]`[i]`'s, none where -1, over any there. With
         * [move] they are moved, else copied; each file appears whole or not at all.
         */
        fun carry(dir: File, from: String, fromNames: List<String?>, to: String, toNames: List<String?>, map: IntArray, move: Boolean) {
            for (i in map.indices) {
                val j = map[i]
                if (j !in toNames.indices) continue
                val source = fileIn(dir, keyOf(from, i, fromNames.getOrNull(i)))
                if (!source.isFile) continue
                val target = fileIn(dir, keyOf(to, j, toNames[j])).toPath()
                runCatching {
                    if (move) {
                        Files.move(source.toPath(), target, StandardCopyOption.REPLACE_EXISTING)
                    } else {
                        val part = File(target.toString() + PART_SUFFIX).toPath()
                        Files.copy(source.toPath(), part, StandardCopyOption.REPLACE_EXISTING)
                        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            }
        }

        fun clear(dir: File) {
            runCatching { dir.deleteRecursively() }
        }
    }
}
