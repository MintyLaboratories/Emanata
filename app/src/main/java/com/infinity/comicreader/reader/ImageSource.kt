package com.infinity.comicreader.reader

import android.graphics.Bitmap
import java.io.File

/** A single image file is a one-page book. */
class ImageSource(private val file: File) : PageSource {
    override val pageCount: Int get() = 1

    override fun loadPage(index: Int, reqWidth: Int, reqHeight: Int): Bitmap =
        Decoders.decode(file, reqWidth, reqHeight)

    override fun pageSize(index: Int): IntArray? = Decoders.size(file)

    override fun close() {
        // Intentionally empty: pages are decoded on demand and no file handle is kept open.
    }
}
