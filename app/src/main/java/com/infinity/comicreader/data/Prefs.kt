package com.infinity.comicreader.data

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.PreferenceManager
import com.infinity.comicreader.net.Remotes
import com.infinity.comicreader.reader.Adjustments
import com.infinity.comicreader.reader.AutoScroll
import com.infinity.comicreader.reader.NameCharset
import com.infinity.comicreader.reader.ScaleFilter
import com.infinity.comicreader.reader.Scaling
import com.infinity.comicreader.reader.TapZones

/**
 * Central settings access. Everything that must survive across chapters, screens
 * and app launches lives here: the settings screen writes the `pref_*` keys and
 * the reader writes back the values the user changes while reading, so reader
 * state is global and persistent rather than per-activity.
 *
 * [ui] holds small transient browsing state (last folder, active tab).
 */
class Prefs(context: Context) {
    private val app = context.applicationContext
    private val sp: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(app)
    private val ui: SharedPreferences = app.getSharedPreferences("ui", Context.MODE_PRIVATE)

    private fun str(key: String, def: String): String = sp.getString(key, def) ?: def
    private fun bool(key: String, def: Boolean): Boolean = sp.getBoolean(key, def)
    private fun integer(key: String, def: Int): Int = sp.getInt(key, def)
    private fun put(key: String, value: Any?) {
        val e = sp.edit()
        when (value) {
            is String -> e.putString(key, value)
            is Boolean -> e.putBoolean(key, value)
            is Int -> e.putInt(key, value)
            else -> e.remove(key)
        }
        e.apply()
    }

    // ---------------------------------------------------------------- general

    /** Settings marked advanced stay out of sight. */
    var keepSimple: Boolean
        get() = bool(KEY_KEEP_SIMPLE, true)
        set(v) = put(KEY_KEEP_SIMPLE, v)

    var theme: String
        get() = str(KEY_THEME, "system")
        set(v) = put(KEY_THEME, v)

    var localeTag: String
        get() = str(KEY_LOCALE, "system")
        set(v) = put(KEY_LOCALE, v)

    /** What archive file names are read in: [NameCharset.AUTO] or a charset's name. */
    var charset: String
        get() = str(KEY_CHARSET, NameCharset.AUTO)
        set(v) = put(KEY_CHARSET, v)

    // ---------------------------------------------------------------- reader

    var fullscreen: Boolean
        get() = bool(KEY_FULLSCREEN, true)
        set(v) = put(KEY_FULLSCREEN, v)

    var keepScreenOn: Boolean
        get() = bool(KEY_KEEP_ON, false)
        set(v) = put(KEY_KEEP_ON, v)

    /** ltr | rtl | ttb ([ReaderLayout.DIRS]) */
    var readingDir: String
        get() = ReaderLayout.dirOf(sp.getString(KEY_READING_DIR, null))
        set(v) = put(KEY_READING_DIR, ReaderLayout.dirOf(v))

    /** The layout: paged | continuous ([ReaderLayout.MODES]); continuous reads top to bottom only. */
    var pageMode: String
        get() = ReaderLayout.modeOf(sp.getString(KEY_PAGE_MODE, null))
        set(v) = put(KEY_PAGE_MODE, ReaderLayout.modeOf(v))

    /** screen | width | height | stretch */
    var fit: String
        get() = str(KEY_FIT, "screen")
        set(v) = put(KEY_FIT, v)

    /** none | invert | grayscale | contrast | sepia */
    var filter: String
        get() = str(KEY_FILTER, "none")
        set(v) = put(KEY_FILTER, v)

    /** The adjustments every book starts with; a book's own replace them. */
    var defaultAdjustments: Adjustments
        get() = Adjustments(
            integer(KEY_ADJUST_BRIGHTNESS, 0),
            integer(KEY_ADJUST_CONTRAST, 0),
            integer(KEY_ADJUST_SATURATION, 0)
        ).coerced()
        set(v) {
            val a = v.coerced()
            sp.edit()
                .putInt(KEY_ADJUST_BRIGHTNESS, a.brightness)
                .putInt(KEY_ADJUST_CONTRAST, a.contrast)
                .putInt(KEY_ADJUST_SATURATION, a.saturation)
                .apply()
        }

    var pageSpacing: Int
        get() = integer(KEY_SPACING, 0).coerceIn(0, PAGE_SPACING_MAX)
        set(v) = put(KEY_SPACING, v.coerceIn(0, PAGE_SPACING_MAX))

    /** Stores [layout] as the defaults, all but its direction, in one write. */
    fun setLayoutDefaults(layout: ReaderLayout) {
        sp.edit()
            .putString(KEY_PAGE_MODE, layout.mode)
            .putString(KEY_FIT, layout.fit)
            .putString(KEY_TWO_PAGE, layout.twoPage.key)
            .putBoolean(KEY_AUTO_CROP, layout.autoCrop)
            .putInt(KEY_SPACING, layout.spacing.coerceIn(0, PAGE_SPACING_MAX))
            .putString(KEY_SPREADS, layout.spreads.key)
            .apply()
    }

    /** How paged reading shows a spread in one file. */
    var spreads: SpreadDisplay
        get() = SpreadDisplay.of(sp.getString(KEY_SPREADS, null))
        set(v) = put(KEY_SPREADS, v.key)

    /** Two-page reading shows the first page, or a cover, alone. */
    var coverAlone: Boolean
        get() = bool(KEY_COVER_ALONE, true)
        set(v) = put(KEY_COVER_ALONE, v)

    /** Two pages whose facing edges run on show, or pair, as one picture ([com.infinity.comicreader.reader.SpreadSeams]). */
    var joinSpreads: Boolean
        get() = bool(KEY_JOIN_SPREADS, true)
        set(v) = put(KEY_JOIN_SPREADS, v)

    /** Paged reading in portrait turns the screen to landscape while a wide page shows whole. */
    var rotateSpreads: Boolean
        get() = bool(KEY_ROTATE_SPREADS, true)
        set(v) = put(KEY_ROTATE_SPREADS, v)

    var autoNext: Boolean
        get() = bool(KEY_AUTO_NEXT, true)
        set(v) = put(KEY_AUTO_NEXT, v)

    /**
     * "Previous page" actions on a chapter's first page that open the previous chapter:
     * 0 (off), 2 or 3. Stored as a string by the list preference.
     */
    var prevChapterBacks: Int
        get() = str(KEY_PREV_CHAPTER_BACKS, "").toIntOrNull()?.takeIf { it in PREV_CHAPTER_BACKS } ?: PREV_CHAPTER_BACKS_DEFAULT
        set(v) = put(KEY_PREV_CHAPTER_BACKS, (v.takeIf { it in PREV_CHAPTER_BACKS } ?: PREV_CHAPTER_BACKS_DEFAULT).toString())

    /** Whether a book with a saved position opens there. */
    var resumeMode: ResumeMode
        get() = ResumeMode.of(sp.getString(KEY_RESUME_MODE, null))
        set(v) = put(KEY_RESUME_MODE, v.value)

    /** When paged reading pairs pages into spreads. */
    var twoPage: TwoPage
        get() = TwoPage.of(sp.getString(KEY_TWO_PAGE, null))
        set(v) = put(KEY_TWO_PAGE, v.key)

    var nightFilter: Boolean
        get() = bool(KEY_NIGHT, false)
        set(v) = put(KEY_NIGHT, v)

    /** Trim blank borders from pages. */
    var autoCrop: Boolean
        get() = bool(KEY_AUTO_CROP, false)
        set(v) = put(KEY_AUTO_CROP, v)

    /** Scale images in linear light rather than in sRGB values. */
    var linearLight: Boolean
        get() = bool(KEY_LINEAR_LIGHT, false)
        set(v) = put(KEY_LINEAR_LIGHT, v)

    /** The filter pages are scaled with to show at rest. */
    var scalePages: ScaleFilter
        get() = ScaleFilter.of(sp.getString(KEY_SCALE_PAGES, null))
        set(v) = put(KEY_SCALE_PAGES, v.key)

    /** The filter zooming scales pages with, while moving (GPU) and once settled. */
    var scaleZoom: ScaleFilter
        get() = ScaleFilter.of(sp.getString(KEY_SCALE_ZOOM, null))
        set(v) = put(KEY_SCALE_ZOOM, v.key)

    /** The filter thumbnails and covers are made with. */
    var scaleThumbs: ScaleFilter
        get() = ScaleFilter.of(sp.getString(KEY_SCALE_THUMBS, null))
        set(v) = put(KEY_SCALE_THUMBS, v.key)

    /** Anti-ringing, in percent, where an image is enlarged. */
    var ringUp: Int
        get() = integer(KEY_RING_UP, 0).coerceIn(0, 100)
        set(v) = put(KEY_RING_UP, v.coerceIn(0, 100))

    /** Anti-ringing, in percent, where an image is shrunk. */
    var ringDown: Int
        get() = integer(KEY_RING_DOWN, Scaling.DEFAULT_DOWN_RING).coerceIn(0, 100)
        set(v) = put(KEY_RING_DOWN, v.coerceIn(0, 100))

    val pageScaling: Scaling get() = Scaling(scalePages, ringUp, ringDown)
    val zoomScaling: Scaling get() = Scaling(scaleZoom, ringUp, ringDown)
    val thumbScaling: Scaling get() = Scaling(scaleThumbs, ringUp, ringDown)

    /** How many pages ahead to decode (0 = off). Stored as a string by the list preference. */
    var preload: Int
        get() = str(KEY_PRELOAD, "2").toIntOrNull()?.coerceIn(0, 6) ?: 2
        set(v) = put(KEY_PRELOAD, v.coerceIn(0, 6).toString())

    /** Auto-scroll speed in screen heights per minute, one of [AutoScroll.SPEEDS]. */
    var autoSpeed: Float
        get() = AutoScroll.parse(sp.getString(KEY_AUTO_SPEED, null))
        set(v) = put(KEY_AUTO_SPEED, AutoScroll.format(AutoScroll.nearest(v)))

    /** Read a book right to left (or left to right) when its contents say so, unless its folder has a direction set. */
    var detectDirection: Boolean
        get() = bool(KEY_DETECT_DIRECTION, true)
        set(v) = put(KEY_DETECT_DIRECTION, v)

    /** Let the reader remember mode / direction / fit / spread per folder. */
    var perSeries: Boolean
        get() = bool(KEY_PER_SERIES, true)
        set(v) = put(KEY_PER_SERIES, v)

    // ---------------------------------------------------------------- gestures

    /** Which edges turn pages; one of [TapZones]' values. */
    var tapZones: String
        get() = TapZones.of(sp.getString(KEY_TAP_ZONE_LAYOUT, null)).value
        set(v) = put(KEY_TAP_ZONE_LAYOUT, TapZones.of(v).value)

    /** The tap zones a book that reads [topToBottom] or not uses ([TapZones.forBook]). */
    fun tapZonesFor(topToBottom: Boolean): TapZones = TapZones.forBook(sp.getString(KEY_TAP_ZONE_LAYOUT, null), topToBottom)

    /** Swap the left and right tap zones in right-to-left books. */
    var mirrorTapZones: Boolean
        get() = bool(KEY_TAP_ZONES_MIRROR, true)
        set(v) = put(KEY_TAP_ZONES_MIRROR, v)

    var volumeKeys: Boolean
        get() = bool(KEY_VOLUME_KEYS, false)
        set(v) = put(KEY_VOLUME_KEYS, v)

    var brightnessGesture: Boolean
        get() = bool(KEY_BRIGHTNESS_GESTURE, true)
        set(v) = put(KEY_BRIGHTNESS_GESTURE, v)

    var doubleTapZoom: Boolean
        get() = bool(KEY_DOUBLE_TAP, true)
        set(v) = put(KEY_DOUBLE_TAP, v)

    /** "Momentum scrolling": a zoomed page keeps gliding after the finger lifts. */
    var fling: Boolean
        get() = bool(KEY_FLING, true)
        set(v) = put(KEY_FLING, v)

    // ---------------------------------------------------------------- library

    var showHidden: Boolean
        get() = bool(KEY_SHOW_HIDDEN, false)
        set(v) = put(KEY_SHOW_HIDDEN, v)

    var thumbnails: Boolean
        get() = bool(KEY_THUMBS, true)
        set(v) = put(KEY_THUMBS, v)

    /** How much the local copies of books on network shares may take, in bytes. */
    val networkCacheBytes: Long
        get() = (str(KEY_NETWORK_CACHE, DEFAULT_NETWORK_CACHE_MB).toLongOrNull() ?: DEFAULT_NETWORK_CACHE_MB.toLong()).coerceAtLeast(1) shl 20

    /** How long a request to a network share waits for an answer ([Remotes.answerMs]), in milliseconds. */
    var networkTimeoutMs: Long
        get() = timeoutMsOf(str(KEY_NETWORK_TIMEOUT, ""))
        set(v) = put(KEY_NETWORK_TIMEOUT, (v / 1000).toString())

    /** How [tab] (one of the `TAB_*` names) shows its entries. */
    fun view(tab: String): TabView {
        val def = TabView.defaultFor(tab)
        return TabView(
            grid = bool(viewKey(tab, VIEW_GRID), def.grid),
            columns = integer(viewKey(tab, VIEW_COLUMNS), def.columns).coerceIn(0, TabView.MAX_CELLS),
            rows = integer(viewKey(tab, VIEW_ROWS), def.rows).coerceIn(0, TabView.MAX_CELLS),
            density = ListDensity.of(sp.getString(viewKey(tab, VIEW_DENSITY), null)),
            detail = DetailDisplay.of(sp.getString(viewKey(tab, VIEW_DETAIL), null))
        )
    }

    fun setDetail(tab: String, detail: DetailDisplay) = put(viewKey(tab, VIEW_DETAIL), detail.key)

    fun setDensity(tab: String, density: ListDensity) = put(viewKey(tab, VIEW_DENSITY), density.key)

    var thumbFit: ThumbFit
        get() = ThumbFit.of(sp.getString(KEY_THUMB_FIT, null))
        set(v) = put(KEY_THUMB_FIT, v.key)

    var cropAnchor: CropAnchor
        get() = CropAnchor.of(sp.getString(KEY_CROP_ANCHOR, null))
        set(v) = put(KEY_CROP_ANCHOR, v.key)

    /** How covers are made from their images. */
    val coverStyle: CoverStyle get() = CoverStyle(thumbFit, cropAnchor, linearLight, thumbScaling)

    /** The library toolbar's buttons, in order. */
    var toolbarButtons: ToolbarButtons
        get() = ToolbarButtons.decode(sp.getString(KEY_TOOLBAR_BUTTONS, null))
        set(v) = put(KEY_TOOLBAR_BUTTONS, v.encode())

    /** The selection's ⋮ menu items, in order. */
    var moreActions: MoreActions
        get() = MoreActions.decode(sp.getString(KEY_MORE_ACTIONS, null))
        set(v) = put(KEY_MORE_ACTIONS, v.encode())

    /** What the floating button does with one item selected. */
    var fabOne: FabAction
        get() = FabAction.of(sp.getString(KEY_FAB_ONE, null), FabAction.OPEN)
        set(v) = put(KEY_FAB_ONE, v.key)

    /** What the floating button does with several items selected; never Open. */
    var fabMany: FabAction
        get() = FabAction.of(sp.getString(KEY_FAB_MANY, null), FabAction.MOVE).takeIf { it in FabAction.FOR_MANY } ?: FabAction.MOVE
        set(v) = put(KEY_FAB_MANY, v.key)

    /** Space between grid covers, in dp. */
    var coverSpacing: Int
        get() = integer(KEY_COVER_SPACING, COVER_SPACING_DEFAULT).coerceIn(0, COVER_SPACING_MAX)
        set(v) = put(KEY_COVER_SPACING, v.coerceIn(0, COVER_SPACING_MAX))

    /** The box covers are drawn in, in every tab. */
    var coverShape: CoverShape
        get() = CoverShape.of(sp.getString(KEY_COVER_SHAPE, null))
        set(v) = put(KEY_COVER_SHAPE, v.key)

    fun setGrid(tab: String, grid: Boolean) = put(viewKey(tab, VIEW_GRID), grid)

    /** Grid [columns] across and [rows] per screen height; 0 is auto. */
    fun setGridSize(tab: String, columns: Int, rows: Int) {
        sp.edit()
            .putInt(viewKey(tab, VIEW_COLUMNS), columns.coerceIn(0, TabView.MAX_CELLS))
            .putInt(viewKey(tab, VIEW_ROWS), rows.coerceIn(0, TabView.MAX_CELLS))
            .apply()
    }

    /** How [tab] orders its entries. */
    fun sort(tab: String): TabSort {
        val def = TabSort.defaultFor(tab)
        return TabSort(
            by = SortLevel.decode(sp.getString(sortKey(tab, SORT_BY), null)) ?: def.by,
            then = SortLevel.decode(sp.getString(sortKey(tab, SORT_THEN), null)),
            foldersFirst = bool(sortKey(tab, SORT_FOLDERS_FIRST), def.foldersFirst)
        )
    }

    fun setSort(tab: String, sort: TabSort) {
        val e = sp.edit()
            .putString(sortKey(tab, SORT_BY), sort.by.encode())
            .putBoolean(sortKey(tab, SORT_FOLDERS_FIRST), sort.foldersFirst)
        val then = sort.then
        if (then == null) e.remove(sortKey(tab, SORT_THEN)) else e.putString(sortKey(tab, SORT_THEN), then.encode())
        e.apply()
    }

    /** What [tab] hides. */
    fun filter(tab: String): LibraryFilter = LibraryFilter.decode(sp.getString(filterKey(tab), null))

    fun setFilter(tab: String, filter: LibraryFilter) = put(filterKey(tab), filter.encode().ifEmpty { null })

    /** What spreading two fingers on a comic in the library does. */
    var pinchPeek: PeekMode
        get() = PeekMode.of(sp.getString(KEY_PINCH_PEEK, null))
        set(v) = put(KEY_PINCH_PEEK, v.key)

    var titleDisplay: TitleDisplay
        get() = TitleDisplay.of(sp.getString(KEY_TITLE_DISPLAY, null))
        set(v) = put(KEY_TITLE_DISPLAY, v.key)

    /** Inside a Set, leave out the series name every item's title starts with. */
    var shortenPrefixes: Boolean
        get() = bool(KEY_SHORTEN_PREFIXES, true)
        set(v) = put(KEY_SHORTEN_PREFIXES, v)

    var listPathView: Boolean
        get() = bool(KEY_PATHVIEW, true)
        set(v) = put(KEY_PATHVIEW, v)

    var listStatusBar: Boolean
        get() = bool(KEY_LIST_STATUSBAR, true)
        set(v) = put(KEY_LIST_STATUSBAR, v)

    var listQuickFab: Boolean
        get() = bool(KEY_QUICK_FAB, true)
        set(v) = put(KEY_QUICK_FAB, v)

    /**
     * The folder a location ([StorageRoot], by its [root] path) opens at when picked: one
     * set below it ([setLocationStart]), else the root itself.
     */
    fun locationStart(root: String): String = LocationStarts.decode(sp.getString(KEY_LOCATION_STARTS, null))[root] ?: root

    /** Makes [folder] where the location at [root] opens; the root itself, null, or a folder elsewhere clears it. */
    fun setLocationStart(root: String, folder: String?) {
        val starts = LocationStarts.decode(sp.getString(KEY_LOCATION_STARTS, null)).toMutableMap()
        if (LocationStarts.isBelow(folder, root)) starts[root] = folder!! else starts.remove(root)
        put(KEY_LOCATION_STARTS, LocationStarts.encode(starts))
    }

    /** Re-points the startup folder, the locations' start folders and the last folder browsed after [old] moved to [new]. */
    fun followPath(old: String, new: String) {
        fun moved(p: String?) = p != null && (p == old || p.startsWith("$old/"))
        fun remap(p: String) = new + p.removePrefix(old)
        val e = sp.edit()
        sp.getString(KEY_STARTUP_PATH, null)?.takeIf { moved(it) }?.let { e.putString(KEY_STARTUP_PATH, remap(it)) }
        val starts = LocationStarts.decode(sp.getString(KEY_LOCATION_STARTS, null))
        if (starts.values.any { moved(it) }) {
            e.putString(KEY_LOCATION_STARTS, LocationStarts.encode(starts.mapValues { (_, v) -> if (moved(v)) remap(v) else v }))
        }
        e.apply()
        lastPath?.takeIf { moved(it) }?.let { lastPath = remap(it) }
    }

    /** Whether the app, as it starts, reopens the book read last: asks (the default), always, or never. */
    var startupResume: ResumeMode
        get() = ResumeMode.of(sp.getString(KEY_STARTUP_RESUME, null))
        set(v) = put(KEY_STARTUP_RESUME, v.value)

    /** The tab the library opens on: one of [TAB_BROWSE], [TAB_FAVORITES], [TAB_HISTORY], [TAB_BOOKMARKS]. */
    var startupTab: String
        get() = str(KEY_STARTUP_TAB, TAB_BROWSE)
        set(v) = put(KEY_STARTUP_TAB, v)

    /** Split the Favorites tab into Libraries, Sets and Comics. */
    var groupFavorites: Boolean
        get() = bool(KEY_GROUP_FAVORITES, true)
        set(v) = put(KEY_GROUP_FAVORITES, v)

    // ---------------------------------------------------------------- battery

    /** While battery saver is on, covers of layered images stay at their first layer ([Thrift]). */
    var saverCovers: Boolean
        get() = bool(KEY_SAVER_COVERS, true)
        set(v) = put(KEY_SAVER_COVERS, v)

    /** While battery saver is on, fewer pages are decoded ahead ([Thrift]). */
    var saverReadAhead: Boolean
        get() = bool(KEY_SAVER_READ_AHEAD, true)
        set(v) = put(KEY_SAVER_READ_AHEAD, v)

    /** While battery saver is on, background scans wait ([Thrift]). */
    var saverScans: Boolean
        get() = bool(KEY_SAVER_SCANS, true)
        set(v) = put(KEY_SAVER_SCANS, v)

    // ---------------------------------------------------------------- ui state

    var lastPath: String?
        get() = ui.getString("last_path", null)
        set(v) = ui.edit().putString("last_path", v).apply()

    /** `[width, height]` in pixels of the covers the library's grid last drew in [landscape] or portrait; null before any. */
    fun gridCover(landscape: Boolean): IntArray? =
        ui.getString(gridCoverKey(landscape), null)?.split('x')?.mapNotNull { it.toIntOrNull() }
            ?.takeIf { it.size == 2 && it[0] > 0 && it[1] > 0 }?.toIntArray()

    /** Keeps the grid's cover size for [gridCover]; writes only a change. */
    fun setGridCover(landscape: Boolean, width: Int, height: Int) {
        val key = gridCoverKey(landscape)
        val value = "${width}x$height"
        if (ui.getString(key, null) != value) ui.edit().putString(key, value).apply()
    }

    private fun gridCoverKey(landscape: Boolean) = if (landscape) "grid_cover_landscape" else "grid_cover_portrait"

    /** One of [StartupLocation]'s choices; a home folder set by an older version counts as custom. */
    var startupLocation: String
        get() = sp.getString(KEY_STARTUP_LOCATION, null)
            ?: if (legacyHome != null) StartupLocation.CUSTOM else StartupLocation.LAST
        set(v) {
            dropLegacyHome()
            put(KEY_STARTUP_LOCATION, v)
        }

    /** The folder of the custom startup location. */
    var startupPath: String?
        get() = sp.getString(KEY_STARTUP_PATH, null) ?: legacyHome
        set(v) {
            dropLegacyHome()
            put(KEY_STARTUP_PATH, v)
        }

    /** Makes [path] the custom startup location. */
    fun setStartupFolder(path: String) {
        dropLegacyHome()
        sp.edit().putString(KEY_STARTUP_PATH, path).putString(KEY_STARTUP_LOCATION, StartupLocation.CUSTOM).apply()
    }

    /** Moves an older version's home folder into the startup settings before they are written. */
    private fun dropLegacyHome() {
        val home = legacyHome ?: return
        val e = sp.edit()
        if (!sp.contains(KEY_STARTUP_PATH)) e.putString(KEY_STARTUP_PATH, home)
        if (!sp.contains(KEY_STARTUP_LOCATION)) e.putString(KEY_STARTUP_LOCATION, StartupLocation.CUSTOM)
        e.apply()
        ui.edit().remove("home_path").apply()
    }

    /** The home folder older versions kept with the browsing state. */
    private val legacyHome: String? get() = ui.getString("home_path", null)

    /** Settings sections the user folded away, by key. */
    var collapsedSettings: Set<String>
        get() = ui.getStringSet("collapsed_settings", null).orEmpty().toSet()
        set(v) = ui.edit().putStringSet("collapsed_settings", v).apply()

    /** Favorites tab sections the user folded away, by [FavoriteKind.key]. */
    var collapsedSections: Set<FavoriteKind>
        get() = ui.getStringSet("collapsed_sections", null).orEmpty().mapNotNull { FavoriteKind.of(it) }.toSet()
        set(v) = ui.edit().putStringSet("collapsed_sections", v.map { it.key }.toSet()).apply()

    /** Search below the current folder, not only in it. */
    var deepSearch: Boolean
        get() = ui.getBoolean("deep_search", false)
        set(v) = ui.edit().putBoolean("deep_search", v).apply()

    /** What sharing a book sent last time, for the next to start from: title and cover at first. */
    var shareChoice: ShareChoice
        get() = ShareChoice(ui.getBoolean("share_title", true), ui.getBoolean("share_cover", true), ui.getBoolean("share_file", false))
        set(v) = ui.edit().putBoolean("share_title", v.title).putBoolean("share_cover", v.cover).putBoolean("share_file", v.file).apply()

    /** True once the first file operation asked to show notifications (Android 13+). */
    var askedNotifications: Boolean
        get() = ui.getBoolean("asked_notifications", false)
        set(v) = ui.edit().putBoolean("asked_notifications", v).apply()

    val nightMode: Int
        get() = when (theme) {
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            "dark" -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }

    /** The caller must hold [listener] strongly: SharedPreferences keeps only a weak reference. */
    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.registerOnSharedPreferenceChangeListener(listener)

    fun reset() {
        val e = sp.edit()
        sp.all.keys.filter { it.startsWith("pref_") }.forEach { e.remove(it) }
        e.apply()
    }

    /** Moves values stored under replaced keys to the settings that replaced them. */
    fun upgrade() {
        val changes = upgrade(sp.all)
        if (changes.isEmpty()) return
        val e = sp.edit()
        write(e, changes)
        e.apply()
    }

    /** Every stored `pref_*` value of a simple type, for backup. */
    fun exportAll(): Map<String, Any> =
        sp.all.filter { (k, v) -> k in KNOWN_KEYS && (v is String || v is Boolean || v is Int) }
            .mapValues { it.value as Any }

    /**
     * Restores values written by [exportAll], an older version's brought up to date;
     * unknown keys and odd types are ignored.
     */
    fun importAll(values: Map<String, Any>) {
        val known = values.filterKeys { it in KNOWN_KEYS }
        val e = sp.edit()
        write(e, known + upgrade(known))
        e.apply()
    }

    companion object {
        const val TAB_BROWSE = "browse"
        const val TAB_FAVORITES = "favorites"
        const val TAB_HISTORY = "history"
        const val TAB_BOOKMARKS = "bookmarks"
        val TABS = listOf(TAB_BROWSE, TAB_FAVORITES, TAB_HISTORY, TAB_BOOKMARKS)

        /** The widest page gap, in dp. */
        const val PAGE_SPACING_MAX = 48

        /** Space between grid covers, in dp: the default and the most. */
        const val COVER_SPACING_DEFAULT = 8
        const val COVER_SPACING_MAX = 32

        private const val VIEW_GRID = "grid"
        private const val VIEW_COLUMNS = "columns"
        private const val VIEW_ROWS = "rows"
        private const val VIEW_DENSITY = "density"
        private const val VIEW_DETAIL = "detail"
        private val VIEW_FIELDS = listOf(VIEW_GRID, VIEW_COLUMNS, VIEW_ROWS, VIEW_DENSITY, VIEW_DETAIL)

        /** The key of one [field] of [tab]'s view, e.g. `pref_view_browse_grid`. */
        private fun viewKey(tab: String, field: String) = "pref_view_${tab}_$field"

        private const val SORT_BY = "by"
        private const val SORT_THEN = "then"
        private const val SORT_FOLDERS_FIRST = "folders_first"
        private val SORT_FIELDS = listOf(SORT_BY, SORT_THEN, SORT_FOLDERS_FIRST)

        /** The key of one [field] of [tab]'s sort, e.g. `pref_sort_browse_by`. */
        private fun sortKey(tab: String, field: String) = "pref_sort_${tab}_$field"

        /** The key of [tab]'s filter; kept out of backups like other browsing state. */
        private fun filterKey(tab: String) = "pref_library_filter_$tab"

        private const val KEY_KEEP_SIMPLE = "pref_keep_simple"
        private const val KEY_THEME = "pref_theme"
        private const val KEY_LOCALE = "pref_locale"
        private const val KEY_CHARSET = "pref_name_charset"
        private const val KEY_FULLSCREEN = "pref_fullscreen"
        private const val KEY_KEEP_ON = "pref_keep_screen_on"
        private const val KEY_READING_DIR = "pref_reading_dir"
        private const val KEY_PAGE_MODE = "pref_page_mode"
        private const val KEY_FIT = "pref_fit"
        private const val KEY_FILTER = "pref_filter"
        private const val KEY_ADJUST_BRIGHTNESS = "pref_adjust_brightness"
        private const val KEY_ADJUST_CONTRAST = "pref_adjust_contrast"
        private const val KEY_ADJUST_SATURATION = "pref_adjust_saturation"
        private const val KEY_SPACING = "pref_page_spacing"
        private const val KEY_AUTO_NEXT = "pref_auto_next"
        private const val KEY_RESUME_MODE = "pref_resume_mode"
        private const val KEY_PREV_CHAPTER_BACKS = "pref_prev_chapter_backs"
        private val PREV_CHAPTER_BACKS = setOf(0, 2, 3)
        private const val PREV_CHAPTER_BACKS_DEFAULT = 2
        private const val KEY_TWO_PAGE = "pref_two_page_mode"
        private const val KEY_NIGHT = "pref_night"
        private const val KEY_TAP_ZONE_LAYOUT = "pref_tap_zone_layout"
        private const val KEY_TAP_ZONES_MIRROR = "pref_tap_zones_mirror"
        private const val KEY_VOLUME_KEYS = "pref_volume_keys"
        private const val KEY_BRIGHTNESS_GESTURE = "pref_brightness_gesture"
        private const val KEY_DOUBLE_TAP = "pref_double_tap"
        private const val KEY_FLING = "pref_fling"
        private const val KEY_SHOW_HIDDEN = "pref_show_hidden"
        private const val KEY_THUMBS = "pref_thumbs"
        const val KEY_NETWORK_CACHE = "pref_network_cache"
        private const val DEFAULT_NETWORK_CACHE_MB = "2048"
        const val KEY_NETWORK_TIMEOUT = "pref_network_timeout"
        private const val MIN_NETWORK_TIMEOUT_S = 5L
        private const val MAX_NETWORK_TIMEOUT_S = 600L

        /** A stored network timeout in seconds as milliseconds; the default for none or no number. */
        fun timeoutMsOf(seconds: String): Long =
            seconds.toLongOrNull()?.coerceIn(MIN_NETWORK_TIMEOUT_S, MAX_NETWORK_TIMEOUT_S)?.times(1000)
                ?: Remotes.DEFAULT_ANSWER_MS
        private const val KEY_COVER_SHAPE = "pref_cover_shape"
        private const val KEY_COVER_SPACING = "pref_cover_spacing"
        private const val KEY_TOOLBAR_BUTTONS = "pref_toolbar_buttons"
        private const val KEY_MORE_ACTIONS = "pref_more_actions"
        const val KEY_FAB_ONE = "pref_fab_one"
        const val KEY_FAB_MANY = "pref_fab_many"
        private const val KEY_THUMB_FIT = "pref_thumb_fit"
        private const val KEY_CROP_ANCHOR = "pref_crop_anchor"
        private const val KEY_PINCH_PEEK = "pref_pinch_peek"
        private const val KEY_TITLE_DISPLAY = "pref_title_display"
        private const val KEY_SHORTEN_PREFIXES = "pref_shorten_prefixes"
        private const val KEY_PATHVIEW = "pref_list_pathview"
        private const val KEY_LIST_STATUSBAR = "pref_list_statusbar"
        private const val KEY_QUICK_FAB = "pref_list_quickfab"
        private const val KEY_GROUP_FAVORITES = "pref_group_favorites"
        private const val KEY_STARTUP_TAB = "pref_startup_tab"
        private const val KEY_STARTUP_LOCATION = "pref_startup_location"
        private const val KEY_STARTUP_PATH = "pref_startup_path"
        private const val KEY_STARTUP_RESUME = "pref_startup_resume"
        private const val KEY_LOCATION_STARTS = "pref_location_starts"
        private const val KEY_AUTO_CROP = "pref_auto_crop"
        private const val KEY_PRELOAD = "pref_preload"
        private const val KEY_AUTO_SPEED = "pref_auto_scroll_speed"
        private const val KEY_PER_SERIES = "pref_per_series"
        private const val KEY_DETECT_DIRECTION = "pref_detect_direction"
        private const val KEY_LINEAR_LIGHT = "pref_linear_light"
        private const val KEY_SCALE_PAGES = "pref_scale_pages"
        private const val KEY_SCALE_ZOOM = "pref_scale_zoom"
        private const val KEY_SCALE_THUMBS = "pref_scale_thumbs"
        private const val KEY_RING_UP = "pref_ring_up"
        private const val KEY_RING_DOWN = "pref_ring_down"
        private const val KEY_SPREADS = "pref_spreads"
        private const val KEY_COVER_ALONE = "pref_cover_alone"
        private const val KEY_ROTATE_SPREADS = "pref_rotate_spreads"
        private const val KEY_JOIN_SPREADS = "pref_join_spreads"
        private const val KEY_SAVER_COVERS = "pref_saver_covers"
        private const val KEY_SAVER_READ_AHEAD = "pref_saver_read_ahead"
        private const val KEY_SAVER_SCANS = "pref_saver_scans"

        private val KNOWN_KEYS = setOf(
            KEY_KEEP_SIMPLE, KEY_THEME, KEY_LOCALE, KEY_CHARSET, OLD_CHARSET, KEY_FULLSCREEN, KEY_KEEP_ON, KEY_READING_DIR,
            KEY_PAGE_MODE, KEY_FIT, KEY_FILTER, KEY_SPACING, KEY_AUTO_NEXT,
            KEY_RESUME_MODE, OLD_RESUME, OLD_ASK_RESUME, KEY_PREV_CHAPTER_BACKS, KEY_TWO_PAGE, OLD_TWO_PAGE, KEY_NIGHT, KEY_TAP_ZONE_LAYOUT, KEY_VOLUME_KEYS,
            KEY_BRIGHTNESS_GESTURE, KEY_DOUBLE_TAP, KEY_FLING, KEY_SHOW_HIDDEN, KEY_THUMBS, KEY_COVER_SHAPE, KEY_THUMB_FIT, KEY_CROP_ANCHOR, OLD_GRID,
            OLD_COLUMNS, OLD_SORT, OLD_SORT_DIR, KEY_PINCH_PEEK, OLD_FILENAMES, KEY_TITLE_DISPLAY, KEY_SHORTEN_PREFIXES, KEY_PATHVIEW, KEY_LIST_STATUSBAR,
            KEY_QUICK_FAB, KEY_AUTO_CROP, KEY_PRELOAD, KEY_AUTO_SPEED, OLD_AUTO_SPEED, KEY_PER_SERIES, OLD_ROWS,
            KEY_LINEAR_LIGHT, KEY_SCALE_PAGES, KEY_SCALE_ZOOM, KEY_SCALE_THUMBS, KEY_RING_UP, KEY_RING_DOWN, KEY_TAP_ZONES_MIRROR, OLD_TAP_ZONES, KEY_ADJUST_BRIGHTNESS,
            KEY_ADJUST_CONTRAST, KEY_ADJUST_SATURATION, OLD_BRIGHTNESS, KEY_GROUP_FAVORITES,
            KEY_STARTUP_TAB, KEY_STARTUP_LOCATION, KEY_STARTUP_PATH, KEY_STARTUP_RESUME, KEY_LOCATION_STARTS, KEY_DETECT_DIRECTION, KEY_COVER_SPACING,
            KEY_TOOLBAR_BUTTONS, KEY_MORE_ACTIONS, KEY_FAB_ONE, KEY_FAB_MANY, KEY_SPREADS, KEY_COVER_ALONE, KEY_ROTATE_SPREADS, KEY_JOIN_SPREADS,
            KEY_SAVER_COVERS, KEY_SAVER_READ_AHEAD, KEY_SAVER_SCANS
        ) + TABS.flatMap { tab -> VIEW_FIELDS.map { viewKey(tab, it) } + SORT_FIELDS.map { sortKey(tab, it) } }

        /** With [OLD_SORT_DIR] ("asc" / "desc"), replaced by Browse's and Favorites' own sorts. */
        private const val OLD_SORT = "pref_sort"
        private const val OLD_SORT_DIR = "pref_sort_dir"

        /** With [OLD_COLUMNS] and [OLD_ROWS], replaced by Browse's and Favorites' own views; defaulted off. */
        private const val OLD_GRID = "pref_grid"

        /** Stored as strings by the list preferences they came from. */
        private const val OLD_COLUMNS = "pref_grid_columns"
        private const val OLD_ROWS = "pref_grid_rows"

        /** Replaced by [KEY_TITLE_DISPLAY] and each tab's detail display: off hid titles and details. */
        private const val OLD_FILENAMES = "pref_show_filenames"

        /**
         * The old single sort ("name", "size", "date", "type"), ascending unless
         * [descending], as a sort level: dates are when files were added, and
         * "type" ascending put images before archives.
         */
        private fun sortLevelOf(old: String?, descending: Boolean): SortLevel = when (old) {
            "size" -> SortLevel(SortKey.SIZE, flipped = !descending)
            "date" -> SortLevel(SortKey.ADDED, flipped = !descending)
            "type" -> SortLevel(SortKey.TYPE, flipped = !descending)
            else -> SortLevel(SortKey.NAME, flipped = descending)
        }

        /** The tabs that take over the single grid and sort settings; the others start at their defaults. */
        private val GRID_HEIRS = listOf(TAB_BROWSE, TAB_FAVORITES)

        /** The Language setting's values (`locale_values`); other stored tags move per [OLD_LOCALES] or fall back to the system's. */
        internal val LOCALES = listOf("system", "en-US")

        /** Retired language tags and the [LOCALES] value that replaced each. */
        private val OLD_LOCALES = mapOf("en" to "en-US")

        /** Replaced by [KEY_CHARSET], whose default reads UTF-8 names as UTF-8 did; other charsets move. */
        private const val OLD_CHARSET = "pref_charset"

        /** Replaced by [KEY_TWO_PAGE]: on meant [TwoPage.ON]. */
        private const val OLD_TWO_PAGE = "pref_two_page"

        /** Replaced by [KEY_AUTO_SPEED]: a 1..10 scale, not converted; the speed starts over at [AutoScroll.DEFAULT]. */
        private const val OLD_AUTO_SPEED = "pref_auto_speed"

        /** Replaced by [KEY_TAP_ZONE_LAYOUT]: on meant left/right zones. */
        private const val OLD_TAP_ZONES = "pref_tap_zones"

        /** Replaced by [KEY_ADJUST_BRIGHTNESS]: a black overlay, 20..100 with 100 = none. */
        private const val OLD_BRIGHTNESS = "pref_brightness"

        /** With [OLD_ASK_RESUME], replaced by [KEY_RESUME_MODE] ([ResumeMode.fromSwitches]); both default on. */
        private const val OLD_RESUME = "pref_resume"
        private const val OLD_ASK_RESUME = "pref_ask_resume"

        /** Puts each of [values] into [e]: a string, boolean or int is written, null removes its key, other types are ignored. */
        internal fun write(e: SharedPreferences.Editor, values: Map<String, Any?>) {
            for ((k, v) in values) {
                when (v) {
                    is String -> e.putString(k, v)
                    is Boolean -> e.putBoolean(k, v)
                    is Int -> e.putInt(k, v)
                    null -> e.remove(k)
                }
            }
        }

        /**
         * The changes that bring [stored] settings up to date: a value to write per key,
         * or null for a key to remove. A replaced key's value moves to its successor
         * unless the successor is already set.
         */
        internal fun upgrade(stored: Map<String, Any?>): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            if (OLD_TAP_ZONES in stored) {
                val on = stored[OLD_TAP_ZONES] as? Boolean ?: true
                if (KEY_TAP_ZONE_LAYOUT !in stored) {
                    out[KEY_TAP_ZONE_LAYOUT] = (if (on) TapZones.LEFT_RIGHT else TapZones.DISABLED).value
                }
                out[OLD_TAP_ZONES] = null
            }
            if (OLD_BRIGHTNESS in stored) {
                val old = stored[OLD_BRIGHTNESS] as? Int
                if (KEY_ADJUST_BRIGHTNESS !in stored && old != null) {
                    out[KEY_ADJUST_BRIGHTNESS] = old.coerceIn(20, 100) - 100
                }
                out[OLD_BRIGHTNESS] = null
            }
            if (OLD_RESUME in stored || OLD_ASK_RESUME in stored) {
                if (KEY_RESUME_MODE !in stored) {
                    val resume = stored[OLD_RESUME] as? Boolean ?: true
                    val ask = stored[OLD_ASK_RESUME] as? Boolean ?: true
                    out[KEY_RESUME_MODE] = ResumeMode.fromSwitches(resume, ask).value
                }
                out[OLD_RESUME] = null
                out[OLD_ASK_RESUME] = null
            }
            if (OLD_GRID in stored || OLD_COLUMNS in stored || OLD_ROWS in stored) {
                // A missing grid switch was off, its old default.
                val grid = stored[OLD_GRID] as? Boolean ?: false
                val columns = (stored[OLD_COLUMNS] as? String)?.toIntOrNull()?.coerceIn(0, TabView.MAX_CELLS)
                val rows = (stored[OLD_ROWS] as? String)?.toIntOrNull()?.coerceIn(0, TabView.MAX_CELLS)
                for (tab in GRID_HEIRS) {
                    for ((field, value) in listOf(VIEW_GRID to grid, VIEW_COLUMNS to columns, VIEW_ROWS to rows)) {
                        val key = viewKey(tab, field)
                        if (value != null && key !in stored) out[key] = value
                    }
                }
                out[OLD_GRID] = null
                out[OLD_COLUMNS] = null
                out[OLD_ROWS] = null
            }
            if (OLD_SORT in stored || OLD_SORT_DIR in stored) {
                val level = sortLevelOf(stored[OLD_SORT] as? String, stored[OLD_SORT_DIR] == "desc")
                if (level != SortLevel(SortKey.NAME)) {
                    for (tab in GRID_HEIRS) {
                        val key = sortKey(tab, SORT_BY)
                        if (key !in stored) out[key] = level.encode()
                    }
                }
                out[OLD_SORT] = null
                out[OLD_SORT_DIR] = null
            }
            if (OLD_FILENAMES in stored) {
                if (stored[OLD_FILENAMES] == false) {
                    if (KEY_TITLE_DISPLAY !in stored) out[KEY_TITLE_DISPLAY] = TitleDisplay.HIDDEN.key
                    for (tab in TABS) {
                        val key = viewKey(tab, VIEW_DETAIL)
                        if (key !in stored) out[key] = DetailDisplay.HIDDEN.key
                    }
                }
                out[OLD_FILENAMES] = null
            }
            if (OLD_AUTO_SPEED in stored) out[OLD_AUTO_SPEED] = null
            if (OLD_CHARSET in stored) {
                val old = stored[OLD_CHARSET] as? String
                if (KEY_CHARSET !in stored && old != null && old != "UTF-8") out[KEY_CHARSET] = old
                out[OLD_CHARSET] = null
            }
            if (OLD_TWO_PAGE in stored) {
                val on = stored[OLD_TWO_PAGE] as? Boolean
                if (KEY_TWO_PAGE !in stored && on != null) out[KEY_TWO_PAGE] = TwoPage.of(on).key
                out[OLD_TWO_PAGE] = null
            }
            val mode = stored[KEY_PAGE_MODE]
            if (mode == ReaderLayout.OLD_VERTICAL || mode == ReaderLayout.OLD_LONGSTRIP) {
                // Both read top to bottom: vertical pages, the long strip is continuous.
                val (layout, dir) = ReaderLayout.migrated(mode as String, null)
                out[KEY_PAGE_MODE] = layout
                out[KEY_READING_DIR] = dir
                // The long strip never cropped; continuous would.
                if (mode == ReaderLayout.OLD_LONGSTRIP) out[KEY_AUTO_CROP] = false
            }
            val locale = stored[KEY_LOCALE]
            if (locale != null && locale !in LOCALES) out[KEY_LOCALE] = OLD_LOCALES[locale]
            return out
        }
    }
}
