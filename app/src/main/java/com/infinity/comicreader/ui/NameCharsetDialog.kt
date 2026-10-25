package com.infinity.comicreader.ui

import android.content.Context
import androidx.appcompat.app.AlertDialog
import com.infinity.comicreader.R
import com.infinity.comicreader.data.BookFactsCache
import com.infinity.comicreader.data.Library
import com.infinity.comicreader.reader.ArchiveKind
import com.infinity.comicreader.reader.NameCharset
import com.infinity.comicreader.reader.PageMoves
import com.infinity.comicreader.reader.PageSources
import com.infinity.comicreader.util.Formats
import java.io.File
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
     * its own; [onPicked] hears a new choice (null: the setting), to keep with [switch].
     */
    fun builder(context: Context, library: Library, path: String, setting: String, onPicked: (String?) -> Unit): AlertDialog.Builder {
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
                if (picked != current) onPicked(picked)
            }
            .setNegativeButton(R.string.cancel, null)
    }

    /**
     * Makes [picked] the charset the book [file] reads its file names in (null: the
     * [setting]). Pages follow their names in order, so its hidden pages, history and
     * bookmarks move with them ([Library.movePages]), and facts read from the old names
     * are forgotten. Lists the book's entries twice where it has any of these. Blocking.
     */
    fun switch(library: Library, facts: BookFactsCache?, file: File, setting: String, picked: String?, cacheDir: File) {
        val path = file.absolutePath
        val before = NameCharset.forBook(path, setting)
        val after = picked ?: setting
        val placed = library.hiddenPages(path).isNotEmpty() || library.historyFor(path) != null || library.bookmarksFor(path).isNotEmpty()
        val keys = if (placed && before != after) {
            runCatching { PageSources.pageKeys(file, before, cacheDir) to PageSources.pageKeys(file, after, cacheDir) }.getOrNull()
        } else {
            null
        }
        library.setNameCharset(path, picked)
        val old = keys?.first
        val new = keys?.second
        if (old != null && new != null) {
            val moves = PageMoves.of(old, new)
            if (!PageMoves.none(moves, new.size)) library.movePages(path, moves, new.size)
        }
        facts?.remove(path)
    }
}
