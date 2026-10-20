package com.infinity.comicreader.ui

import android.content.Context
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import com.google.android.material.color.MaterialColors
import com.infinity.comicreader.data.BookEntry
import com.infinity.comicreader.data.EntryType
import com.infinity.comicreader.data.FavoriteKind
import com.infinity.comicreader.data.FavoriteKindCache
import com.infinity.comicreader.data.FavoriteLabel
import com.infinity.comicreader.data.FavoriteLabels
import com.infinity.comicreader.data.Library
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.StorageRoot
import com.infinity.comicreader.util.Formats
import com.infinity.comicreader.util.NaturalOrder
import java.io.File

object LibraryOrder {
    /** Folders first, then by [sort] ("name", "size", "date", "type") in [dir] ("asc", "desc"), then by name. */
    fun comparator(sort: String, dir: String): Comparator<BookEntry> {
        var byField: Comparator<BookEntry> = when (sort) {
            "size" -> compareBy { it.size }
            "date" -> compareBy { it.lastModified }
            "type" -> compareBy { it.type.ordinal }
            else -> compareBy(NaturalOrder) { it.name }
        }
        if (dir == "desc") byField = byField.reversed()
        return compareByDescending<BookEntry> { it.isDir }
            .then(byField)
            .thenBy(NaturalOrder) { it.name }
    }

    /**
     * The favorites, labeled, in Favorites tab order: sections by their cached kind
     * when grouped (unknown ones under Sets), sorted inside by the list sort.
     */
    fun favorites(context: Context, prefs: Prefs, roots: List<StorageRoot>): List<BookEntry> {
        val paths = Library(context).favorites()
        val labels = FavoriteLabels.of(paths, roots)
        val cache = FavoriteKindCache(context)
        val entries = paths.map { path ->
            val file = File(path)
            val isDir = file.isDirectory
            val label = labels[path]
            BookEntry(
                file = file,
                name = label?.name ?: file.name,
                isDir = isDir,
                type = if (isDir) EntryType.FOLDER else Formats.typeOf(file.name),
                size = if (isDir) 0 else file.length(),
                lastModified = file.lastModified(),
                label = label
            )
        }
        val sorted = entries.sortedWith(comparator(prefs.listSort, prefs.listSortDir))
        if (!prefs.groupFavorites) return sorted
        return FavoriteSections.arrange(sorted) {
            if (!it.isDir) FavoriteKind.COMIC else cache.get(it.path, it.lastModified)
        }.flatMap { it.entries }
    }

    /** [label] with the name in the text color and its prefix and suffix grayed. */
    fun labelText(context: Context, label: FavoriteLabel): CharSequence {
        if (label.prefix.isEmpty() && label.suffix.isEmpty()) return label.name
        val gray = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, 0)
        val text = SpannableString(label.text)
        val nameEnd = label.prefix.length + label.name.length
        if (label.prefix.isNotEmpty()) {
            text.setSpan(ForegroundColorSpan(gray), 0, label.prefix.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (label.suffix.isNotEmpty()) {
            text.setSpan(ForegroundColorSpan(gray), nameEnd, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return text
    }
}
