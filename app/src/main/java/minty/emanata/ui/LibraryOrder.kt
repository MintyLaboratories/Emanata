package minty.emanata.ui

import android.content.Context
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import com.google.android.material.color.MaterialColors
import minty.emanata.data.BookEntry
import minty.emanata.data.EntryValues
import minty.emanata.data.FavoriteKind
import minty.emanata.data.FavoriteKindCache
import minty.emanata.data.FavoriteLabel
import minty.emanata.data.FavoriteLabels
import minty.emanata.data.Library
import minty.emanata.data.LibrarySnapshot
import minty.emanata.data.LibrarySort
import minty.emanata.data.Prefs
import minty.emanata.data.StorageRoot
import minty.emanata.net.Remotes

object LibraryOrder {
    /**
     * The favorites, labeled, in Favorites tab order: sections by their cached kind
     * when grouped (unknown ones under Sets), sorted inside by the tab's sort with
     * the values their entries and folder contents hold.
     */
    fun favorites(context: Context, prefs: Prefs, roots: List<StorageRoot>): List<BookEntry> {
        val library = Library(context)
        val items = library.favoriteItems()
        val labels = FavoriteLabels.of(items.map { it.path }, roots)
        val cache = FavoriteKindCache(context)
        val snap = LibrarySnapshot.load(library, prefs)
        val entries = items.map { (path, id) ->
            val label = labels[path]
            // Favorites on shares as known, without the network.
            snap.entry(Remotes.file(path, askNetwork = false), prefs.showHidden)
                .let { if (id != null) snap.withId(it, id) else it }
                .let { it.copy(name = label?.name ?: it.name, label = label) }
        }
        val sorted = entries.sortedWith(LibrarySort.comparator(prefs.sort(Prefs.TAB_FAVORITES), EntryValues()))
        if (!prefs.groupFavorites) return sorted
        return FavoriteSections.arrange(sorted) {
            when {
                !it.isDir -> FavoriteKind.COMIC
                Remotes.isRemote(it.path) -> cache.peek(it.path)
                else -> cache.get(it.path, it.lastModified)
            }
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
