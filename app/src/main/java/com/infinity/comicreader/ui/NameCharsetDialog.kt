package com.infinity.comicreader.ui

import android.content.Context
import androidx.appcompat.app.AlertDialog
import com.infinity.comicreader.R
import com.infinity.comicreader.data.Library
import com.infinity.comicreader.reader.ArchiveKind
import com.infinity.comicreader.reader.NameCharset
import com.infinity.comicreader.util.Formats
import java.nio.charset.Charset
import java.util.Locale

/** A book's own file name charset: the choice of it, and how Book info words it. */
object NameCharsetDialog {
    /** True for a book whose file names a charset decides: a zip or tar, by its extension. */
    fun applies(fileName: String): Boolean =
        BookInfo.promised(Formats.ext(fileName).lowercase(Locale.ROOT)).let { it == ArchiveKind.ZIP || it == ArchiveKind.TAR }

    /** The setting's name for charset [value] ("Auto", "Shift_JIS (Japanese)"), or [value] itself. */
    fun label(context: Context, value: String): String {
        val values = context.resources.getStringArray(R.array.charset_values)
        val i = values.indexOfFirst { it.equals(value, ignoreCase = true) }
        return if (i >= 0) context.resources.getStringArray(R.array.charset_entries)[i] else value
    }

    /**
     * What Book info says of the book at [path] whose names were read in [read]: its own
     * choice, or what the [setting] gave, with the charset Auto found.
     */
    fun describe(context: Context, library: Library, path: String, setting: String, read: Charset): String {
        val own = library.nameCharset(path)
        val chosen = own ?: setting
        val shown = if (chosen == NameCharset.AUTO) {
            context.getString(R.string.info_charset_auto, read.name())
        } else {
            label(context, chosen)
        }
        return if (own != null) context.getString(R.string.info_charset_book, shown) else shown
    }

    /**
     * Asks which charset the book at [path] reads its file names in: the [setting], or one of
     * its own; [onChanged] hears a new choice once kept.
     */
    fun builder(context: Context, library: Library, path: String, setting: String, onChanged: () -> Unit): AlertDialog.Builder {
        val values = listOf<String?>(null) + context.resources.getStringArray(R.array.charset_values)
        val labels = arrayOf<CharSequence>(context.getString(R.string.charset_follow_setting, label(context, setting))) +
            context.resources.getTextArray(R.array.charset_entries)
        val current = library.nameCharset(path)
        val checked = values.indexOf(current).coerceAtLeast(0)
        return AlertDialog.Builder(context)
            .setTitle(R.string.pref_charset)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                dialog.dismiss()
                val picked = values[which]
                if (picked == current) return@setSingleChoiceItems
                library.setNameCharset(path, picked)
                onChanged()
            }
            .setNegativeButton(R.string.cancel, null)
    }
}
