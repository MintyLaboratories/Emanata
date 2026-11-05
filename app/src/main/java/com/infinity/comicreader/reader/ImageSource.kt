package com.infinity.comicreader.reader

import android.graphics.Bitmap
import com.infinity.comicreader.net.RemoteFile
import com.infinity.comicreader.net.Remotes
import com.infinity.comicreader.util.Formats
import java.io.File

/** A single image file is a one-page book. */
class ImageSource(private val file: File) : PageSource {
    override val pageCount: Int get() = 1

    override fun loadPage(index: Int, target: Target): Bitmap =
        Decoders.decode(file, target)

    /** Read a rectangle at a time where the image is large, else decoded whole ([Decoders.detail]); one on a share is read whole first. */
    override fun loadDetail(index: Int, target: Target, maxBytes: Long): PageDetail {
        val bytes = if (file is RemoteFile) Remotes.readBytes(file.path) else file.inputStream().use { BitmapUtils.readCapped(it) }
        return Decoders.detail(bytes, target, maxBytes)
    }

    override fun loadPreview(index: Int): Bitmap? {
        if (!QuickPreviews().worthReading(file.name)) return null
        return file.inputStream().use { Decoders.quickBytes(it) }?.let { Decoders.preview(it) }
    }

    override fun loadFirstLayer(index: Int): Bitmap? {
        if (!Formats.ext(file.name).equals("avif", ignoreCase = true)) return null
        return file.inputStream().use { Decoders.firstLayer(it) }
    }

    override fun pageSize(index: Int): IntArray? = Decoders.size(file)

    override fun edge(index: Int, bottom: Boolean): Seams.Profile? =
        runCatching { Decoders.edge(file.inputStream().use { BitmapUtils.readCapped(it) }, bottom) }.getOrNull()

    override fun sides(index: Int): SpreadSeams.Sides? =
        runCatching { Decoders.sides(file.inputStream().use { BitmapUtils.readCapped(it) }) }.getOrNull()

    override fun pageName(index: Int): String = file.name

    override fun pageBytes(index: Int): ByteArray =
        if (file is RemoteFile) Remotes.readBytes(file.path) else file.inputStream().use { BitmapUtils.readCapped(it) }

    override fun close() {
        // Intentionally empty: pages are decoded on demand and no file handle is kept open.
    }
}
