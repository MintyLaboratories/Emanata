package com.infinity.comicreader.reader

import android.graphics.Bitmap
import java.io.File

/** A single image file is a one-page book. */
class ImageSource(private val file: File) : PageSource {
    override val pageCount: Int get() = 1

    override fun loadPage(index: Int, target: Target): Bitmap =
        Decoders.decode(file, target)

    override fun pageSize(index: Int): IntArray? = Decoders.size(file)

    override fun pageName(index: Int): String = file.name

    override fun close() {
        // Intentionally empty: pages are decoded on demand and no file handle is kept open.
    }
}
