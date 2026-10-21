package com.infinity.comicreader.ui

import android.content.Context
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import com.google.android.material.color.MaterialColors
import com.infinity.comicreader.data.BookEntry
import com.infinity.comicreader.data.EntryValues
import com.infinity.comicreader.data.FavoriteKind
import com.infinity.comicreader.data.FavoriteKindCache
import com.infinity.comicreader.data.FavoriteLabel
import com.infinity.comicreader.data.FavoriteLabels
import com.infinity.comicreader.data.Library
import com.infinity.comicreader.data.LibrarySnapshot
import com.infinity.comicreader.data.LibrarySort
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.StorageRoot
import java.io.File

object LibraryOrder {
    /**
     * The favorites, labeled, in Favorites tab order: sections by their cached kind
     * when grouped (unknown ones under Sets), sorted inside by the tab's sort with
     * the values their entries and folder contents hold.
     */
    fun favorites(context: Context, prefs: Prefs, roots: List<StorageRoot>): List<BookEntry> {
        val library = Library(context)
        val paths = library.favorites()
        val labels = FavoriteLabels.of(paths, roots)
        val cache = FavoriteKindCache(context)
        val snap = LibrarySnapshot.load(library, prefs)
        val entries = paths.map { path ->
            val label = labels[path]
            snap.entry(File(path), prefs.showHidden).let { it.copy(name = label?.name ?: it.name, label = label) }
        }
        val sorted = entries.sortedWith(LibrarySort.comparator(prefs.sort(Prefs.TAB_FAVORITES), EntryValues()))
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
