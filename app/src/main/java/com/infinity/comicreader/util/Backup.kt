package com.infinity.comicreader.util

import android.content.Context
import com.infinity.comicreader.data.Library
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.ReaderLayout
import com.infinity.comicreader.net.ShareBackup
import com.infinity.comicreader.net.ShareStore
import org.json.JSONObject

/**
 * Exports favorites, history, bookmarks, read markers, per-series settings,
 * statistics, preferences and network shares ([ShareBackup]) to a single JSON
 * document, and restores them.
 *
 * A backup is only ever read or written through a file the user picked, and a
 * restore accepts nothing but the known keys, with the expected JSON types.
 */
object Backup {
    private const val APP = "Emanata"

    /** Names a restorable backup may carry: this app's and its upstream's. */
    private val APPS = setOf(APP, "ComicReader")
    private const val VERSION = 1

    /** Largest backup file that will be read. Real backups are a few hundred KB. */
    const val MAX_BYTES = 8L * 1024 * 1024

    /**
     * The backup of [context]'s app; share passwords sealed for [deviceId] unless not
     * [withPasswords]. Blocking: sealing takes a moment.
     */
    fun export(context: Context, withPasswords: Boolean = true, deviceId: String? = ShareBackup.deviceId(context)): String {
        val root = JSONObject()
        root.put("app", APP)
        root.put("version", VERSION)
        root.put("time", System.currentTimeMillis())
        root.put("library", Library(context).exportAll())
        val p = JSONObject()
        for ((k, v) in Prefs(context).exportAll()) p.put(k, v)
        root.put("prefs", p)
        val shares = ShareStore(context).all()
        if (shares.isNotEmpty()) root.put("shares", ShareBackup.export(shares, deviceId, withPasswords))
        return root.toString(2)
    }

    /**
     * Restores a backup, share passwords opened for [deviceId]; shares are added to those
     * there, replacing any with the same id. Returns false when [json] is not a backup of
     * this app or its upstream.
     */
    fun import(context: Context, json: String, deviceId: String? = ShareBackup.deviceId(context)): Boolean {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return false
        if (root.optString("app") !in APPS || root.optInt("version", 0) !in 1..VERSION) return false
        val lib = root.optJSONObject("library") ?: return false

        val library = Library(context)
        library.importAll(lib)

        root.optJSONObject("prefs")?.let { p ->
            val values = HashMap<String, Any>()
            p.keys().forEach { k ->
                when (val v = p.get(k)) {
                    is Boolean, is Int, is String -> values[k] = v
                }
            }
            Prefs(context).importAll(values)
        }
        root.optJSONObject("shares")?.let { s ->
            val store = ShareStore(context)
            // A share whose password cannot be sealed here is skipped; the rest still restore.
            for (share in ShareBackup.import(s, deviceId)) runCatching { store.put(share) }
        }
        // Folder layouts of older versions, judged against the defaults just restored.
        library.migrateFolderLayouts(ReaderLayout.of(Prefs(context)))
        return true
    }
}
