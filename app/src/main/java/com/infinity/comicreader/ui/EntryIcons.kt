package com.infinity.comicreader.ui

import android.content.Context
import androidx.core.content.ContextCompat
import com.infinity.comicreader.R
import com.infinity.comicreader.data.BookEntry
import com.infinity.comicreader.data.EntryType

/** The type icon a library entry shows while it has no cover, and its tint. */
object EntryIcons {
    fun iconFor(item: BookEntry): Int = when {
        item.isDir -> R.drawable.ic_folder
        item.type == EntryType.PDF -> R.drawable.ic_picture_as_pdf
        item.type == EntryType.ARCHIVE -> R.drawable.ic_auto_stories
        item.type == EntryType.IMAGE -> R.drawable.ic_image
        else -> R.drawable.ic_file
    }

    fun tintFor(context: Context, item: BookEntry): Int = ContextCompat.getColor(
        context,
        when {
            item.isDir -> R.color.md_primary
            item.type == EntryType.PDF -> R.color.md_error
            item.type == EntryType.ARCHIVE -> R.color.md_secondary
            item.type == EntryType.IMAGE -> R.color.md_tertiary
            else -> R.color.md_on_surface_variant
        }
    )
}
