package com.infinity.comicreader.util

import android.content.Context
import com.infinity.comicreader.data.BookId
import com.infinity.comicreader.data.Library
import com.infinity.comicreader.data.Likeness
import com.infinity.comicreader.data.LikenessStore
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.ReaderLayout
import com.infinity.comicreader.data.UpstreamBackup
import com.infinity.comicreader.net.ShareBackup
import com.infinity.comicreader.net.ShareStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * Exports favorites, history, bookmarks, read markers, per-series settings,
 * statistics, preferences, network shares ([ShareBackup]) and the likeness of each
 * book with data kept ([LikenessStore], so a re-encoded copy can still take its data
 * over) to a single JSON document, and restores them.
 *
 * A backup is only ever read or written through a file the user picked, and a
 * restore accepts nothing but the known keys, with the expected JSON types.
 */
object Backup {
    private const val APP = "Emanata"

    /** Names a restorable backup may carry: this app's and its upstream's ([UpstreamBackup]). */
    private val APPS = setOf(APP, UpstreamBackup.APP)
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
        val library = Library(context)
        root.put("library", library.exportAll())
        val likeness = exportLikeness(LikenessStore(context).rows(library.kept().keys))
        if (likeness.length() > 0) root.put("likeness", likeness)
        val p = JSONObject()
        for ((k, v) in Prefs(context).exportAll()) p.put(k, v)
        root.put("prefs", p)
        val shares = ShareStore(context).all()
        if (shares.isNotEmpty()) root.put("shares", ShareBackup.export(shares, deviceId, withPasswords))
        return root.toString(2)
    }

    /**
     * Restores a backup, share passwords opened for [deviceId]; shares are added to those
     * there, replacing any with the same id but keeping its working password for the same
     * server ([ShareBackup.merged]). Returns false when [json] is not a backup of
     * this app or its upstream. Blocking: opening passwords takes a moment.
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
            val prefs = Prefs(context)
            prefs.importAll(values)
            if (root.optString("app") == UpstreamBackup.APP) UpstreamBackup.restore(values, prefs)
        }
        root.optJSONArray("likeness")?.let { importLikeness(LikenessStore(context), it) }
        root.optJSONObject("shares")?.let { s ->
            val store = ShareStore(context)
            val here = store.all().associateBy { it.id }
            // A share whose password cannot be sealed here is skipped; the rest still restore.
            for (share in ShareBackup.import(s, deviceId)) runCatching { store.put(ShareBackup.merged(share, here[share.id])) }
        }
        // Folder layouts of older versions, judged against the defaults just restored.
        library.migrateFolderLayouts(ReaderLayout.of(Prefs(context)))
        return true
    }

    private fun exportLikeness(rows: List<LikenessStore.Row>): JSONArray {
        val out = JSONArray()
        for (r in rows.sortedBy { it.id.hex }) {
            out.put(
                JSONObject()
                    .put("id", r.id.hex)
                    .put("print", r.likeness.print)
                    .put("strong", r.likeness.strong)
                    .put("stems", JSONArray(r.likeness.stems))
                    .put("name", r.fileName)
                    .put("time", r.time)
            )
        }
        return out
    }

    /** Stores each well-formed likeness in [rows]; others are skipped. */
    private fun importLikeness(store: LikenessStore, rows: JSONArray) {
        for (i in 0 until rows.length()) {
            val o = rows.optJSONObject(i) ?: continue
            val id = BookId.parse(o.optString("id")) ?: continue
            val print = o.opt("print") as? String ?: continue
            val strong = o.opt("strong") as? Boolean ?: continue
            val name = o.opt("name") as? String ?: continue
            val time = (o.opt("time") as? Number)?.toLong() ?: continue
            val arr = o.optJSONArray("stems") ?: continue
            val stems = (0 until arr.length()).map { arr.opt(it) as? String }
            if (print.isEmpty() || stems.any { it == null || !LikenessStore.storable(it) }) continue
            runCatching { store.put(id, Likeness(strong, print, stems.map { it!! }), name, time) }
        }
    }
}
