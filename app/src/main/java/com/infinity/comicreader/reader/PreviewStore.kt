package com.infinity.comicreader.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import com.infinity.comicreader.util.Hashes
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Page previews ([Previews]) on disk under [dir], one JPEG per page, named by a hash of
 * the book's identity (path, size, modification time), the page's index and entry
 * name, and the preview size, so a changed book or size never reads a stale one.
 * Writes run on [writer], off the decoding thread; a file appears whole or not at all.
 */
class PreviewStore(val dir: File, private val writer: Executor = WRITER) {

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

    private fun write(file: File, preview: Bitmap) {
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

    private fun fileOf(key: String) = File(dir, Hashes.sha256Hex(key) + ".jpg")

    companion object {
        private const val DIR = "previews"
        private const val PART_SUFFIX = ".part"
        private const val QUALITY = 85
        private const val STALE_PART_MS = 10L * 60 * 1000

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

        /** What identifies [file]'s contents: a change of path, size or time makes it another book. */
        fun bookId(file: File): String = "${file.absolutePath}|${file.length()}|${file.lastModified()}"

        /** Key of the preview of page [index], stored as [entry] (null when unnamed), of book [bookId]. */
        fun keyOf(bookId: String, index: Int, entry: String?): String = "$bookId|$index|${entry.orEmpty()}|${Previews.BOX}"

        /**
         * Keeps the store under [maxBytes] by deleting the least recently used previews
         * first; partial writes left by an earlier run go too.
         */
        fun trim(dir: File, maxBytes: Long = MAX_BYTES, now: Long = System.currentTimeMillis()) {
            val files = dir.listFiles() ?: return
            files.filter { it.name.endsWith(PART_SUFFIX) && it.lastModified() < now - STALE_PART_MS }.forEach { it.delete() }
            val kept = files.filter { it.name.endsWith(".jpg") && it.isFile }
            var total = kept.sumOf { it.length() }
            if (total <= maxBytes) return
            for (f in kept.sortedBy { it.lastModified() }) {
                if (total <= maxBytes) break
                val size = f.length()
                if (f.delete()) total -= size
            }
        }

        fun clear(dir: File) {
            runCatching { dir.deleteRecursively() }
        }
    }
}
