package minty.emanata.data

import minty.emanata.reader.TapZones
import org.json.JSONObject

/**
 * Backups made by upstream ComicReader, read into today's settings and folder layouts.
 * Upstream kept books by path; [Library.importAll] keeps only their folders.
 */
object UpstreamBackup {
    /** The app name upstream's backups carry. */
    const val APP = "ComicReader"

    /**
     * Sets [prefs] from upstream's settings in [values] that today's keys do not take as they
     * are ([Prefs.importAll] takes those and ignores the rest).
     */
    fun restore(values: Map<String, Any>, prefs: Prefs) {
        (values["pref_tap_zones"] as? Boolean)?.let {
            prefs.tapZones = (if (it) TapZones.LEFT_RIGHT else TapZones.DISABLED).value
        }
        // A black overlay, 20..100 with 100 = none.
        (values["pref_brightness"] as? Int)?.let {
            prefs.defaultAdjustments = prefs.defaultAdjustments.copy(brightness = it.coerceIn(20, 100) - 100)
        }
        if ("pref_resume" in values || "pref_ask_resume" in values) {
            val resume = values["pref_resume"] as? Boolean ?: true
            val ask = values["pref_ask_resume"] as? Boolean ?: true
            prefs.resumeMode = if (!resume) ResumeMode.NO else if (ask) ResumeMode.ASK else ResumeMode.YES
        }
        // One grid and one sort, for Browse and Favorites; a missing grid switch was off.
        if ("pref_grid" in values || "pref_grid_columns" in values) {
            val grid = values["pref_grid"] as? Boolean ?: false
            val columns = (values["pref_grid_columns"] as? String)?.toIntOrNull()
            for (tab in TABS) {
                prefs.setGrid(tab, grid)
                if (columns != null) prefs.setGridColumns(tab, columns)
            }
        }
        if ("pref_sort" in values || "pref_sort_dir" in values) {
            val level = sortLevel(values["pref_sort"] as? String, values["pref_sort_dir"] == "desc")
            if (level != SortLevel(SortKey.NAME)) for (tab in TABS) prefs.setSortBy(tab, level)
        }
        if (values["pref_show_filenames"] == false) {
            prefs.titleDisplay = TitleDisplay.HIDDEN
            for (tab in Prefs.TABS) prefs.setDetail(tab, DetailDisplay.HIDDEN)
        }
        // Its default, UTF-8, reads names as Auto does.
        (values["pref_charset"] as? String)?.takeIf { it != "UTF-8" }?.let { prefs.charset = it }
        (values["pref_two_page"] as? Boolean)?.let { prefs.twoPage = if (it) TwoPage.ON else TwoPage.OFF }
        when (values["pref_page_mode"]) {
            VERTICAL -> {
                prefs.pageMode = ReaderLayout.MODE_PAGED
                prefs.readingDir = ReaderLayout.DIR_TTB
            }
            LONGSTRIP -> {
                prefs.pageMode = ReaderLayout.MODE_CONTINUOUS
                prefs.readingDir = ReaderLayout.DIR_TTB
                // The long strip never cropped.
                prefs.autoCrop = false
            }
        }
        (values["pref_locale"] as? String)?.let { tag ->
            prefs.localeTag = if (tag == "en") "en-US" else tag.takeIf { it in Prefs.LOCALES } ?: "system"
        }
    }

    /**
     * [library] with upstream's folder layouts (mode, direction, fit and two-page, all kept
     * whenever a direction was chosen) in today's form: one whose mode, fit and two-page
     * match [defaults] keeps only its direction; one that differs keeps its whole layout.
     */
    fun library(library: JSONObject, defaults: ReaderLayout): JSONObject {
        val series = library.optJSONObject(SERIES) ?: return library
        val out = JSONObject()
        for (k in series.keys()) {
            val o = series.optJSONObject(k) ?: continue
            out.put(k, folderLayout(o, defaults).encode())
        }
        return JSONObject(library.toString()).put(SERIES, out)
    }

    /** Upstream's layout [o] for a folder, as [library] reads it. */
    internal fun folderLayout(o: JSONObject, defaults: ReaderLayout): FolderLayout {
        val old = o.optString("mode")
        val (mode, dir) = when (old) {
            VERTICAL -> ReaderLayout.MODE_PAGED to ReaderLayout.DIR_TTB
            LONGSTRIP -> ReaderLayout.MODE_CONTINUOUS to ReaderLayout.DIR_TTB
            else -> ReaderLayout.modeOf(old) to ReaderLayout.dirOf(o.optString("dir"))
        }
        val layout = defaults.copy(
            mode = mode,
            dir = dir,
            fit = o.optString("fit", "screen").ifEmpty { "screen" },
            twoPage = if (o.optBoolean("two")) TwoPage.ON else TwoPage.OFF,
            autoCrop = old != LONGSTRIP && defaults.autoCrop
        )
        val same = layout.mode == defaults.mode && layout.fit == defaults.fit && layout.twoPage == defaults.twoPage
        return if (same) FolderLayout.Direction(dir) else FolderLayout.Frozen(layout)
    }

    /**
     * Upstream's sort ("name", "size", "date" or "type"), ascending unless [descending]:
     * dates are when files were added, and "type" ascending put images before archives.
     */
    private fun sortLevel(old: String?, descending: Boolean): SortLevel = when (old) {
        "size" -> SortLevel(SortKey.SIZE, flipped = !descending)
        "date" -> SortLevel(SortKey.ADDED, flipped = !descending)
        "type" -> SortLevel(SortKey.TYPE, flipped = !descending)
        else -> SortLevel(SortKey.NAME, flipped = descending)
    }

    /** The tabs upstream's one grid and one sort go to; the others start at their defaults. */
    private val TABS = listOf(Prefs.TAB_BROWSE, Prefs.TAB_FAVORITES)

    /** Upstream's modes that read top to bottom: one page a screen, and one column. */
    private const val VERTICAL = "vertical"
    private const val LONGSTRIP = "longstrip"

    /** Where upstream's backup keeps folder layouts, by folder path. */
    private const val SERIES = "series"
}
