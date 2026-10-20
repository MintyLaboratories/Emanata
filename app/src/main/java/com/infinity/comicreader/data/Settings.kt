package com.infinity.comicreader.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Library screen settings as one value, so a screen can diff what changed. */
data class ListSettings(
    val showHidden: Boolean,
    val thumbnails: Boolean,
    val grid: Boolean,
    val gridColumns: Int,
    val gridRows: Int,
    val sort: String,
    val sortDir: String,
    val showFilenames: Boolean,
    val pathView: Boolean,
    val statusBar: Boolean,
    val quickFab: Boolean,
    val charset: String
) {
    companion object {
        fun from(p: Prefs) = ListSettings(
            showHidden = p.showHidden,
            thumbnails = p.thumbnails,
            grid = p.listGrid,
            gridColumns = p.gridColumns,
            gridRows = p.gridRows,
            sort = p.listSort,
            sortDir = p.listSortDir,
            showFilenames = p.showFilenames,
            pathView = p.listPathView,
            statusBar = p.listStatusBar,
            quickFab = p.listQuickFab,
            charset = p.charset
        )
    }
}

/** Reader settings (global defaults; a folder's remembered layout is layered on top). */
data class ReaderSettings(
    val fullscreen: Boolean,
    val keepScreenOn: Boolean,
    val readingDir: String,
    val pageMode: String,
    val fit: String,
    val filter: String,
    val brightness: Int,
    val pageSpacing: Int,
    val autoNext: Boolean,
    val resume: Boolean,
    val askResume: Boolean,
    val twoPage: Boolean,
    val nightFilter: Boolean,
    val autoCrop: Boolean,
    val preload: Int,
    val autoSpeed: Int,
    val perSeries: Boolean,
    val tapZones: Boolean,
    val volumeKeys: Boolean,
    val brightnessGesture: Boolean,
    val doubleTapZoom: Boolean,
    val charset: String
) {
    companion object {
        fun from(p: Prefs) = ReaderSettings(
            fullscreen = p.fullscreen,
            keepScreenOn = p.keepScreenOn,
            readingDir = p.readingDir,
            pageMode = p.pageMode,
            fit = p.fit,
            filter = p.filter,
            brightness = p.brightness,
            pageSpacing = p.pageSpacing,
            autoNext = p.autoNext,
            resume = p.resume,
            askResume = p.askResume,
            twoPage = p.twoPage,
            nightFilter = p.nightFilter,
            autoCrop = p.autoCrop,
            preload = p.preload,
            autoSpeed = p.autoSpeed,
            perSeries = p.perSeries,
            tapZones = p.tapZones,
            volumeKeys = p.volumeKeys,
            brightnessGesture = p.brightnessGesture,
            doubleTapZoom = p.doubleTapZoom,
            charset = p.charset
        )
    }
}

/**
 * App-wide change feed for [Prefs]. Reads and writes still go through [Prefs];
 * this republishes a fresh snapshot whenever any setting changes, from whichever
 * screen wrote it, so consumers re-apply what changed live or when they start
 * again. Theme and language are not here: they apply by recreating activities.
 */
class SettingsRepository internal constructor(context: Context) {
    private val prefs = Prefs(context)

    private val _list = MutableStateFlow(ListSettings.from(prefs))
    val list: StateFlow<ListSettings> = _list.asStateFlow()

    private val _reader = MutableStateFlow(ReaderSettings.from(prefs))
    val reader: StateFlow<ReaderSettings> = _reader.asStateFlow()

    // Held in a field: SharedPreferences references its listeners only weakly.
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        _list.value = ListSettings.from(prefs)
        _reader.value = ReaderSettings.from(prefs)
    }

    init {
        prefs.registerListener(listener)
    }

    companion object {
        @Volatile
        private var instance: SettingsRepository? = null

        fun get(context: Context): SettingsRepository =
            instance ?: synchronized(this) {
                instance ?: SettingsRepository(context.applicationContext).also { instance = it }
            }
    }
}
