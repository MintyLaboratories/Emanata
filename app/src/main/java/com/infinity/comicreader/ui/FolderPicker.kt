package com.infinity.comicreader.ui

import android.content.Context
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.infinity.comicreader.R
import com.infinity.comicreader.net.RemoteException
import com.infinity.comicreader.net.RemoteFile
import com.infinity.comicreader.net.Remotes
import com.infinity.comicreader.data.Crumbs
import com.infinity.comicreader.data.FavoriteLabels
import com.infinity.comicreader.data.Library
import com.infinity.comicreader.data.MissingFolder
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.StorageRoot
import com.infinity.comicreader.data.StorageRoots
import com.infinity.comicreader.util.AppDispatchers
import com.infinity.comicreader.util.NaturalOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Picks a folder: the favorite folders first (labeled as in the Favorites tab),
 * then the storage roots, then "Other folder…" to browse for one. Folders are
 * read in [scope] off the main thread.
 */
class FolderPicker(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onPick: (String) -> Unit
) {
    private val roots: List<StorageRoot> = StorageRoots.list(context)

    fun show() {
        scope.launch {
            val favorites = withContext(AppDispatchers.io) {
                LibraryOrder.favorites(context, Prefs(context), roots).filter { it.isDir }
            }
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
            actions.add { browse(Remotes.file(roots.first().path)) }
            AlertDialog.Builder(context)
                .setTitle(R.string.pref_startup_custom)
                .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    /** Browses for a folder from [path] ([browse]), without the favorites and roots first. */
    fun browseFrom(path: String) = browse(Remotes.file(path))

    /**
     * Lists the folders inside [dir]; tapping one goes into it, "Choose" picks [dir]. A
     * [dir] gone by then lists its nearest existing parent instead, saying so, or the
     * first storage root when its whole root is gone. A share that fails to list says why
     * and lists nothing, so nothing on it is taken for gone.
     */
    private fun browse(dir: File) {
        scope.launch {
            val listed = withContext(AppDispatchers.io) { runCatching { foldersIn(dir) } }
            val failure = listed.exceptionOrNull()
            if (failure != null && dir is RemoteFile) {
                val name = StorageRoot.bound(dir.absolutePath, roots)?.name.orEmpty()
                Toast.makeText(context, context.getString(LocationSheet.failureTextNamed(failure), name), Toast.LENGTH_SHORT).show()
                return@launch
            }
            val folders = listed.getOrNull()
            if (folders == null) {
                val found = withContext(AppDispatchers.io) {
                    MissingFolder.nearest(dir.absolutePath, roots) { Remotes.file(it).isDirectory }?.let { Remotes.file(it) }
                        ?: Remotes.file(roots.first().path).takeIf { it.isDirectory }
                }
                Toast.makeText(context, R.string.folder_gone, Toast.LENGTH_SHORT).show()
                if (found != null && found != dir) browse(found)
                return@launch
            }
            val path = dir.absolutePath
            val root = StorageRoot.bound(path, roots)
            val parent = dir.parentFile?.takeIf { root != null && path != root.path }
            AlertDialog.Builder(context)
                .setTitle(Crumbs.of(path, roots).joinToString(" / ") { it.label })
                .setItems(folders.map { it.name }.toTypedArray()) { _, which -> browse(folders[which]) }
                .setPositiveButton(R.string.picker_choose) { _, _ -> onPick(path) }
                .apply { if (parent != null) setNeutralButton(R.string.picker_up) { _, _ -> browse(parent) } }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    /** The folders in [dir] by name; null when [dir] is gone. Throws when its share fails. Blocking. */
    private fun foldersIn(dir: File): List<File>? {
        val all: List<File> = if (dir is RemoteFile) {
            try {
                dir.entries()
            } catch (e: RemoteException) {
                if (e.reason == RemoteException.Reason.MISSING) return null
                throw e
            }
        } else {
            if (!dir.isDirectory) return null
            dir.listFiles()?.toList().orEmpty()
        }
        return all.filter { it.isDirectory && !it.name.startsWith(".") }.sortedWith(compareBy(NaturalOrder) { it.name })
    }

    companion object {
        /** How [path] reads in a summary: its favorite label, else its crumbs. Touches no files. */
        fun describe(context: Context, path: String): CharSequence {
            val roots = StorageRoots.list(context)
            val favorites = Library(context).favorites()
            if (path in favorites) {
                FavoriteLabels.of(favorites, roots)[path]?.let { return LibraryOrder.labelText(context, it) }
            }
            return Crumbs.of(path, roots).joinToString(" / ") { it.label }
        }
    }
}
