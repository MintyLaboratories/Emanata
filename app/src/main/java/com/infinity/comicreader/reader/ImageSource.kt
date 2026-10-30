package com.infinity.comicreader.reader

import android.graphics.Bitmap
import com.infinity.comicreader.net.RemoteFile
import com.infinity.comicreader.util.Formats
import java.io.File

/** A single image file is a one-page book. */
class ImageSource(private val file: File) : PageSource {
    override val pageCount: Int get() = 1

    override fun loadPage(index: Int, target: Target): Bitmap =
        Decoders.decode(file, target)

    /** Read a rectangle at a time where the image is large ([Decoders.regionDetail]), else decoded whole. */
    override fun loadDetail(index: Int, target: Target, maxBytes: Long): PageDetail {
        if (file !is RemoteFile && Decoders.regionNamed(file.name)) {
            val bytes = file.inputStream().use { BitmapUtils.readCapped(it) }
            Decoders.regionDetail(bytes, target)?.let { return it }
            return BitmapDetail(Decoders.decode(bytes, target.detail(Decoders.size(bytes), maxBytes)))
        }
        return super.loadDetail(index, target, maxBytes)
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

    override fun close() {
        // Intentionally empty: pages are decoded on demand and no file handle is kept open.
    }
}
