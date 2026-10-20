package com.infinity.comicreader.ui

import android.content.Context
import androidx.appcompat.app.AlertDialog
import com.infinity.comicreader.R
import com.infinity.comicreader.data.Crumbs
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.StorageRoot
import com.infinity.comicreader.data.StorageRoots
import com.infinity.comicreader.util.NaturalOrder
import java.io.File

/**
 * Picks a folder: the favorite folders first (labeled as in the Favorites tab),
 * then the storage roots, then "Other folder…" to browse for one.
 */
class FolderPicker(private val context: Context, private val onPick: (String) -> Unit) {
    private val roots: List<StorageRoot> = StorageRoots.list(context)

    fun show() {
        val favorites = LibraryOrder.favorites(context, Prefs(context), roots).filter { it.isDir }
        val labels = ArrayList<CharSequence>()
        val actions = ArrayList<() -> Unit>()
        for (f in favorites) {
            labels.add(f.label?.let { LibraryOrder.labelText(context, it) } ?: f.name)
            actions.add { onPick(f.path) }
        }
        for (root in roots) {
            labels.add(root.name)
            actions.add { onPick(root.path) }
        }
        labels.add(context.getString(R.string.picker_other_folder))
        actions.add { browse(File(roots.first().path)) }
        AlertDialog.Builder(context)
            .setTitle(R.string.pref_startup_custom)
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Lists the folders inside [dir]; tapping one goes into it, "Choose" picks [dir]. */
    private fun browse(dir: File) {
        val path = dir.absolutePath
        val folders = dir.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.sortedWith(compareBy(NaturalOrder) { it.name })
            .orEmpty()
        val root = StorageRoot.of(path, roots)
        val parent = dir.parentFile?.takeIf { root != null && path != root.path }
        AlertDialog.Builder(context)
            .setTitle(Crumbs.of(path, roots).joinToString(" / ") { it.label })
            .setItems(folders.map { it.name }.toTypedArray()) { _, which -> browse(folders[which]) }
            .setPositiveButton(R.string.picker_choose) { _, _ -> onPick(path) }
            .apply { if (parent != null) setNeutralButton(R.string.picker_up) { _, _ -> browse(parent) } }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    companion object {
        /** How [path] reads in a summary: its favorite label, else its crumbs. */
        fun describe(context: Context, path: String): CharSequence {
            val roots = StorageRoots.list(context)
            val favorite = LibraryOrder.favorites(context, Prefs(context), roots).firstOrNull { it.path == path }
            favorite?.label?.let { return LibraryOrder.labelText(context, it) }
            return Crumbs.of(path, roots).joinToString(" / ") { it.label }
        }
    }
}
