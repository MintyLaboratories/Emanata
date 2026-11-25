package minty.emanata.ui

import minty.emanata.data.HistoryItem
import minty.emanata.data.StorageRoot
import minty.emanata.net.Remotes

/** What the "Recent" row of Browse points at: the folder of the book read last. */
object RecentRow {
    /**
     * The folder holding the most recent book of [history] (newest first) that is not on
     * a share removed from the app ([isOrphan]); null when there is none.
     */
    fun folderOf(history: List<HistoryItem>, isOrphan: (String) -> Boolean = Remotes::isOrphan): String? =
        history.asSequence()
            .filter { it.path.isNotEmpty() && !isOrphan(it.path) }
            .mapNotNull { h -> h.path.lastIndexOfAny(SEPARATORS).takeIf { it > 0 }?.let { h.path.substring(0, it) } }
            .firstOrNull()

    /**
     * Folder [dir] as the row's subtitle names it: as list subtitles do ([StorageRoot.describe]),
     * a root by its name, and a folder on a share [roots] do not list (yet) after the share's
     * name ([shareName]), never as a raw `/net/<id>` path.
     */
    fun subtitle(dir: String, roots: List<StorageRoot>, shareName: (String) -> String?): String {
        val root = StorageRoot.of(dir, roots)
        if (root == null && Remotes.isRemote(dir)) {
            val id = Remotes.idOf(dir) ?: return ""
            val name = shareName(id) ?: return Remotes.relOf(dir)
            return listOf(name, Remotes.relOf(dir)).filter { it.isNotEmpty() }.joinToString("/")
        }
        return StorageRoot.describe(dir, roots).ifEmpty { root?.name ?: dir }
    }

    /** Either path separator: tests on a desktop JVM may see `\`. */
    private val SEPARATORS = charArrayOf('/', '\\')
}
