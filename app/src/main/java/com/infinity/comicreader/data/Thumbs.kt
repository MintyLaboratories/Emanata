package com.infinity.comicreader.data

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import com.infinity.comicreader.reader.Decoders
import com.infinity.comicreader.reader.Fit
import com.infinity.comicreader.reader.PageSources
import com.infinity.comicreader.reader.Target
import com.infinity.comicreader.util.Formats
import com.infinity.comicreader.util.NaturalOrder
import java.io.File
import java.security.MessageDigest

/**
 * Cover thumbnails for library rows. Images decode directly; archives and PDFs
 * render their first page; folders fall back to the first image/openable file
 * found a couple of levels deep. Covers are cropped and scaled to fill the requested
 * box, rounded up to a [ThumbSizing] tier, and cached per tier in memory (bounded
 * by heap) and on disk under `cacheDir/thumbnails`.
 */
object Thumbs {
    private const val DISK_DIR = "thumbnails"
    private const val THUMB_CAP_PIXELS = 16_000_000L
    private const val MAX_DISK_BYTES = 120L * 1024 * 1024
    private const val HEX_DIGITS = "0123456789abcdef"

    private val memory = object : LruCache<String, Bitmap>(maxBytes()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private fun maxBytes(): Int {
        val max = Runtime.getRuntime().maxMemory()
        return (max / 8L).toInt().coerceIn(8 * 1024 * 1024, 96 * 1024 * 1024)
    }

    private fun key(entry: BookEntry): String =
        "${entry.path}|${entry.size}|${entry.lastModified}|${entry.isDir}"

    /** Blocking — call from a background dispatcher. Returns null when no cover exists. */
    fun load(context: Context, entry: BookEntry, charset: String, reqW: Int, reqH: Int): Bitmap? {
        val boxW = ThumbSizing.tier(reqW)
        val boxH = ThumbSizing.tier(reqH)
        val k = "${key(entry)}|${boxW}x$boxH"
        memory.get(k)?.let { return it }

        val disk = diskFile(context, k)
        if (disk.isFile) {
            val cached = decodeFile(disk, boxW, boxH)
            if (cached != null) {
                memory.put(k, cached)
                return cached
            }
        }

        val bitmap = generate(entry, charset, context.cacheDir, Target(boxW, boxH, Fit.COVER, capPixels = THUMB_CAP_PIXELS))
            ?: return null
        memory.put(k, bitmap)
        runCatching {
            disk.parentFile?.mkdirs()
            disk.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        }
        return bitmap
    }

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
        runCatching { File(context.cacheDir, DISK_DIR).deleteRecursively() }
    }

    // ------------------------------------------------------------------ internals

    private fun generate(entry: BookEntry, charset: String, cacheDir: File, target: Target): Bitmap? = try {
        when {
            entry.isDir -> findCover(entry.file, 2)?.let { coverOf(it, charset, cacheDir, target) }
            entry.type == EntryType.IMAGE -> Decoders.decode(entry.file, target)
            else -> coverOf(entry.file, charset, cacheDir, target)
        }
    } catch (t: Throwable) {
        null
    }

    private fun coverOf(file: File, charset: String, cacheDir: File, target: Target): Bitmap? {
        if (!file.isFile || !Formats.isOpenable(file.name)) return null
        if (Formats.typeOf(file.name) == EntryType.IMAGE) {
            return Decoders.decode(file, target)
        }
        val source = runCatching { PageSources.openCover(file, charset, cacheDir) }.getOrNull() ?: return null
        return try {
            if (source.pageCount <= 0) null else source.loadPage(0, target)
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

    private fun decodeFile(file: File, boxW: Int, boxH: Int): Bitmap? =
        runCatching { Decoders.decode(file, Target(boxW, boxH, Fit.COVER, capPixels = THUMB_CAP_PIXELS)) }.getOrNull()

    private fun diskFile(context: Context, key: String): File =
        File(File(context.cacheDir, DISK_DIR), sha256(key) + ".jpg")

    /**
     * Cache-file name for [value]. This is only used to build a collision-resistant
     * file name, but SHA-256 is used so the code does not rely on a weak hash
     * (Sonar S4790) and the hex is built without a locale-sensitive formatter.
     */
    private fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        val hex = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val b = bytes[i].toInt() and 0xFF
            hex[i * 2] = HEX_DIGITS[b ushr 4]
            hex[i * 2 + 1] = HEX_DIGITS[b and 0x0F]
        }
        return String(hex)
    }
}
