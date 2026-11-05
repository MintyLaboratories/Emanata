package com.infinity.comicreader.ui

import android.content.Context
import com.infinity.comicreader.R
import com.infinity.comicreader.reader.ImageKind
import com.infinity.comicreader.reader.PageFormat

/** Page info's words for a page's format, e.g. "AVIF, lossy, layered, 3 layers". */
object PageInfoText {

    fun format(context: Context, format: PageFormat?): String {
        val kind = format?.kind ?: return context.getString(R.string.page_format_unknown)
        val parts = ArrayList<String>()
        parts += name(kind)
        when (format.lossless) {
            true -> parts += context.getString(R.string.page_format_lossless)
            false -> parts += context.getString(R.string.page_format_lossy)
            null -> Unit
        }
        if (format.progressive) parts += context.getString(R.string.page_format_progressive)
        if (format.animated) parts += context.getString(R.string.page_format_animated)
        if (format.layers > 0) parts += context.resources.getQuantityString(R.plurals.page_format_layers, format.layers, format.layers)
        return parts.joinToString(", ")
    }

    /** The format's usual name. */
    fun name(kind: ImageKind): String = when (kind) {
        ImageKind.JPEG -> "JPEG"
        ImageKind.PNG -> "PNG"
        ImageKind.GIF -> "GIF"
        ImageKind.BMP -> "BMP"
        ImageKind.WEBP -> "WebP"
        ImageKind.AVIF -> "AVIF"
        ImageKind.HEIF -> "HEIF"
        ImageKind.JXL -> "JPEG XL"
        ImageKind.TIFF -> "TIFF"
    }
}
