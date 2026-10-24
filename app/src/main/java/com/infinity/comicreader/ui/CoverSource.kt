package com.infinity.comicreader.ui

import android.content.Context
import android.graphics.Bitmap
import com.infinity.comicreader.data.BookEntry
import com.infinity.comicreader.data.CoverStyle
import com.infinity.comicreader.data.Thumbs

/** Where library rows get their covers ([Thumbs]); tests stand in their own. */
interface CoverSource {
    /** The cover held in memory, at once; null when there is none. Main thread. */
    fun inMemory(context: Context, entry: BookEntry, width: Int, height: Int, style: CoverStyle): Bitmap?

    /** The cover made before, from memory or disk, never made now; null when there is none. Blocking, but short. */
    fun cached(context: Context, entry: BookEntry, width: Int, height: Int, style: CoverStyle): Bitmap?

    /** The cover made now; null when there is none, or once [canceled] turns true. Blocking. */
    fun load(context: Context, entry: BookEntry, charset: String, width: Int, height: Int, style: CoverStyle, canceled: () -> Boolean): Bitmap?

    /** Like [load], but a quick cover from a layered cover page's first layer when it has one ([Thumbs.loadFirst]). Blocking. */
    fun loadFirst(
        context: Context,
        entry: BookEntry,
        charset: String,
        width: Int,
        height: Int,
        style: CoverStyle,
        canceled: () -> Boolean
    ): Thumbs.Cover?

    /** False when the cover of [entry] is known to show the same whichever way the book reads ([Thumbs.directionMatters]). */
    fun directionMatters(entry: BookEntry): Boolean = true

    companion object {
        val THUMBS: CoverSource = object : CoverSource {
            override fun inMemory(context: Context, entry: BookEntry, width: Int, height: Int, style: CoverStyle) =
                Thumbs.inMemory(context, entry, width, height, style)

            override fun cached(context: Context, entry: BookEntry, width: Int, height: Int, style: CoverStyle) =
                Thumbs.cached(context, entry, width, height, style)

            override fun load(
                context: Context,
                entry: BookEntry,
                charset: String,
                width: Int,
                height: Int,
                style: CoverStyle,
                canceled: () -> Boolean
            ) = Thumbs.load(context, entry, charset, width, height, style, canceled)

            override fun loadFirst(
                context: Context,
                entry: BookEntry,
                charset: String,
                width: Int,
                height: Int,
                style: CoverStyle,
                canceled: () -> Boolean
            ) = Thumbs.loadFirst(context, entry, charset, width, height, style, canceled)

            override fun directionMatters(entry: BookEntry) = Thumbs.directionMatters(entry)
        }
    }
}
