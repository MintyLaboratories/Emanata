package com.infinity.comicreader.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.tabs.TabLayout
import com.infinity.comicreader.R
import com.infinity.comicreader.data.BookEntry
import com.infinity.comicreader.data.Crumbs
import com.infinity.comicreader.data.EntryType
import com.infinity.comicreader.data.FavoriteClassifier
import com.infinity.comicreader.data.FavoriteKind
import com.infinity.comicreader.data.FavoriteKindCache
import com.infinity.comicreader.data.FavoriteLabels
import com.infinity.comicreader.data.HistoryItem
import com.infinity.comicreader.data.Library
import com.infinity.comicreader.data.ListSettings
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.SettingsRepository
import com.infinity.comicreader.data.StartupLocation
import com.infinity.comicreader.data.StorageRoot
import com.infinity.comicreader.data.StorageRoots
import com.infinity.comicreader.util.AppDispatchers
import com.infinity.comicreader.util.FileUtils
import com.infinity.comicreader.util.Formats
import com.infinity.comicreader.util.NaturalOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ListActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var library: Library
    private lateinit var settings: SettingsRepository

    /** The settings the screen currently shows; changes are diffed against it. */
    private lateinit var applied: ListSettings
    private lateinit var adapter: BookAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var toolbar: MaterialToolbar
    private lateinit var tabs: TabLayout
    private lateinit var pathbar: CrumbBar
    private lateinit var fab: FloatingActionButton

    /** A detached "Recent" row, measured to size grid rows around the real one. */
    private var headerProbe: View? = null

    /** A detached section header, measured to size grid rows around the real ones. */
    private var sectionProbe: View? = null

    /** Cells in each Favorites section shown, 0 for a collapsed one; empty when not grouped. */
    private var sectionItems: List<Int> = emptyList()
    private lateinit var progress: ProgressBar
    private lateinit var emptyView: LinearLayout
    private lateinit var emptyIcon: ImageView
    private lateinit var emptyText: TextView
    private lateinit var emptySubtitle: TextView

    private var currentDir: File? = null

    /** Mounted storage volumes, refreshed whenever the screen resumes. */
    private var roots: List<StorageRoot> = emptyList()
    private var mode = TAB_BROWSE
    private var query = ""
    private var resumedOnce = false
    private var started = false

    /** The state to come back to after a recreate, until [startUp] uses it. */
    private var restored: Bundle? = null

    /** Overrides the startup tab setting for the first [startUp]. */
    private var startTab: Int? = null

    /** A folder handed in by another app, browsed by the first [startUp] instead of the startup location. */
    private var incomingDir: File? = null
    private var deepJob: Job? = null
    private var deepResults: List<BookEntry>? = null
    private val allEntries = ArrayList<BookEntry>()

    /** Known sections of the favorites shown; a missing path is still being classified. */
    private var favoriteKinds: Map<String, FavoriteKind> = emptyMap()
    private var classifyJob: Job? = null

    /** The listing being loaded; a newer one cancels it, so only the latest is shown. */
    private var listJob: Job? = null
    private lateinit var kindCache: FavoriteKindCache
    private val shown = ArrayList<BookEntry>()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            // Only read access is required to browse; write access (old Android) is a bonus.
            if (result[Manifest.permission.READ_EXTERNAL_STORAGE] == true) startUp()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        library = Library(this)
        settings = SettingsRepository.get(this)
        kindCache = FavoriteKindCache(this)
        applied = settings.list.value
        setContentView(R.layout.activity_list)

        toolbar = findViewById(R.id.toolbar)
        tabs = findViewById(R.id.tabs)
        pathbar = findViewById(R.id.pathbar)
        recycler = findViewById(R.id.recycler)
        fab = findViewById(R.id.fab)
        progress = findViewById(R.id.progress)
        emptyView = findViewById(R.id.empty_view)
        emptyIcon = findViewById(R.id.empty_icon)
        emptyText = findViewById(R.id.empty_text)
        emptySubtitle = findViewById(R.id.empty_subtitle)

        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(true)
        toolbar.setTitle(R.string.app_name)

        adapter = BookAdapter(
            grid = prefs.listGrid,
            prefs = prefs,
            onClick = { onEntryClick(it) },
            onLongClick = { onEntryLongClick(it) },
            onStarClick = { toggleFavorite(it.file.absolutePath) }
        )
        adapter.onSectionClick = { toggleSection(it.kind) }
        recycler.adapter = adapter
        applyLayoutManager()
        recycler.setHasFixedSize(false)
        recycler.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val widthChanged = right - left != oldRight - oldLeft
            // The keyboard shrinks the list while searching: cells keep their size.
            val heightChanged = bottom - top != oldBottom - oldTop && !keyboardVisible()
            // Not from inside a layout pass: resizing cells rebinds them.
            if (widthChanged || heightChanged) recycler.post { updateCellSize() }
        }

        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = onTabChanged(tab.position)

            override fun onTabUnselected(tab: TabLayout.Tab) {
                // Intentionally empty: only the newly selected tab needs handling.
            }

            override fun onTabReselected(tab: TabLayout.Tab) {
                recycler.scrollToPosition(0)
            }
        })

        fab.setOnClickListener { toggleGrid() }
        updateFabIcon()
        fab.visibility = View.GONE
        pathbar.visibility = if (prefs.listPathView) View.VISIBLE else View.GONE
        pathbar.onCrumbClick = { loadDirectory(File(it.path)) }
        pathbar.onRootMenuClick = { showRootChooser() }
        roots = StorageRoots.list(this)

        // Settings changed here, in the settings screen or anywhere else apply live;
        // changes made while this screen was stopped arrive when it starts again.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                settings.list.collect { applySettings(it) }
            }
        }

        restored = savedInstanceState
        // What another app hands in is shown on Browse, not on the startup tab; a
        // recreate restores the screen instead of handling it again.
        if (savedInstanceState == null) {
            incomingDir = handleIncoming(intent)
            if (intent.data != null) startTab = TAB_BROWSE
        }

        if (!hasStorageAccess()) {
            requestStorageAccess()
        } else {
            startUp()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_MODE, mode)
        currentDir?.let { outState.putString(STATE_DIR, it.absolutePath) }
    }

    override fun onResume() {
        super.onResume()
        applyStatusBarSetting()
        roots = StorageRoots.list(this)
        if (!hasStorageAccess()) return
        if (!resumedOnce) {
            // Right after onCreate's listing, or after access was granted to start up with.
            resumedOnce = true
            if (!started) startUp()
            return
        }
        // Refresh so files added/removed outside the app (or from the viewer)
        // appear immediately.
        if (mode == TAB_BROWSE) currentDir?.let { loadDirectory(it) }
    }

    override fun onDestroy() {
        super.onDestroy()
        adapter.release()
    }

    /** Re-applies whatever changed since the settings last shown. */
    private fun applySettings(s: ListSettings) {
        val old = applied
        if (old == s) return
        applied = s
        if (s.grid != old.grid || s.gridColumns != old.gridColumns || s.gridRows != old.gridRows) {
            adapter.grid = s.grid
            applyLayoutManager()
            updateCellSize()
            updateFabIcon()
            invalidateOptionsMenu()
        }
        if (s.showHidden != old.showHidden) {
            invalidateOptionsMenu()
            // Coming back to the browse tab, onResume reloads the folder anyway.
            if (mode != TAB_BROWSE || lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) refreshCurrent()
        }
        if (s.sort != old.sort || s.sortDir != old.sortDir) {
            applyFilterAndSort()
        } else if (s.thumbnails != old.thumbnails || s.showFilenames != old.showFilenames || s.charset != old.charset ||
            s.linearLight != old.linearLight
        ) {
            adapter.notifyDataSetChanged()
        }
        if (s.groupFavorites != old.groupFavorites && mode == TAB_FAVORITES) {
            applyFilterAndSort()
            classifyPending(allEntries.toList())
        }
        if (s.pathView != old.pathView && mode == TAB_BROWSE) {
            pathbar.visibility = if (s.pathView) View.VISIBLE else View.GONE
        }
        if (s.statusBar != old.statusBar) applyStatusBarSetting()
        if (s.quickFab != old.quickFab) {
            fab.visibility = if (shown.isNotEmpty() && s.quickFab) View.VISIBLE else View.GONE
        }
    }

    private fun applyStatusBarSetting() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (prefs.listStatusBar) {
            controller.show(WindowInsetsCompat.Type.statusBars())
        } else {
            controller.hide(WindowInsetsCompat.Type.statusBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // ------------------------------------------------------------------ storage

    private fun hasStorageAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }

    private fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            AlertDialog.Builder(this)
                .setMessage(R.string.all_files_access)
                .setPositiveButton(R.string.grant_access) { _, _ ->
                    runCatching {
                        startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:$packageName")
                            )
                        )
                    }.onFailure {
                        startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } else {
            val wanted = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            } else {
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            permissionLauncher.launch(wanted)
        }
    }

    /**
     * Opens the screen as it was before a recreate, else on the startup tab with
     * Browse behind it at the folder handed in, or else the startup location.
     */
    private fun startUp() {
        val state = restored
        restored = null
        val savedDir = state?.getString(STATE_DIR)?.let { File(it) }?.takeIf { it.isDirectory }
        val tab = state?.getInt(STATE_MODE, TAB_BROWSE) ?: startTab ?: tabOf(prefs.startupTab)
        startTab = null
        started = true
        val dir = savedDir ?: incomingDir
        incomingDir = null
        if (tab == TAB_BROWSE) {
            if (dir != null) loadDirectory(dir) else openStartDirectory()
            return
        }
        currentDir = dir
        if (dir == null) {
            lifecycleScope.launch {
                val start = withContext(AppDispatchers.io) { startDirectory() }
                if (currentDir == null) currentDir = start
            }
        }
        when (tab) {
            TAB_FAVORITES -> showFavorites()
            TAB_HISTORY -> showHistory()
            else -> showBookmarks()
        }
    }

    private fun tabOf(value: String): Int = when (value) {
        Prefs.TAB_FAVORITES -> TAB_FAVORITES
        Prefs.TAB_HISTORY -> TAB_HISTORY
        Prefs.TAB_BOOKMARKS -> TAB_BOOKMARKS
        else -> TAB_BROWSE
    }

    /** The startup location; checks folders, so not on the main thread. */
    private fun startDirectory(): File {
        val path = StartupLocation.resolve(
            choice = prefs.startupLocation,
            custom = prefs.startupPath,
            last = prefs.lastPath,
            shown = { LibraryOrder.favorites(this, prefs, roots).map { it.path } },
            added = { library.favorites() },
            history = { library.history().map { it.path } },
            isDir = { File(it).isDirectory }
        )
        return path?.let { File(it) } ?: Environment.getExternalStorageDirectory()
    }

    /** Browses the startup location, unless another listing starts while it is worked out. */
    private fun openStartDirectory() {
        showProgress(true)
        launchListing {
            val dir = withContext(AppDispatchers.io) { startDirectory() }
            loadDirectory(dir)
        }
    }

    // ------------------------------------------------------------------ tabs

    private fun selectTab(index: Int) {
        if (tabs.selectedTabPosition != index) tabs.getTabAt(index)?.select()
    }

    private fun onTabChanged(index: Int) {
        if (index == mode) return
        when (index) {
            TAB_FAVORITES -> showFavorites()
            TAB_HISTORY -> showHistory()
            TAB_BOOKMARKS -> showBookmarks()
            else -> {
                val dir = currentDir
                if (dir != null) loadDirectory(dir) else openStartDirectory()
            }
        }
    }

    // ------------------------------------------------------------------ listing

    private fun loadDirectory(dir: File, highlight: String? = null) {
        mode = TAB_BROWSE
        currentDir = dir
        deepJob?.cancel()
        deepResults = null
        prefs.lastPath = dir.absolutePath
        adapter.highlightPath = highlight
        selectTab(TAB_BROWSE)

        val atRoot = roots.any { it.path == dir.absolutePath }
        supportActionBar?.title = if (atRoot || dir.name.isBlank()) getString(R.string.app_name) else dir.name
        pathbar.show(Crumbs.of(dir.absolutePath, roots), rootMenu = roots.size > 1)
        pathbar.visibility = if (prefs.listPathView) View.VISIBLE else View.GONE
        invalidateOptionsMenu()

        showProgress(true)
        launchListing {
            val list = withContext(AppDispatchers.io) { buildDirectoryEntries(dir) }
            progress.visibility = View.GONE
            displayEntries(list)
            if (highlight != null) scrollToHighlight(highlight)
        }
    }

    /** Runs [load] as the only listing: an older one, and favorites still being classified, stop. */
    private fun launchListing(load: suspend CoroutineScope.() -> Unit) {
        listJob?.cancel()
        classifyJob?.cancel()
        listJob = lifecycleScope.launch(block = load)
    }

    /** Favorites, read marks and history, loaded once per listing instead of once per row. */
    private class Snapshot(
        val favorites: Set<String>,
        val read: Set<String>,
        val history: Map<String, HistoryItem>
    )

    private fun snapshot() = Snapshot(library.favoriteSet(), library.readSet(), library.historyMap())

    /** A folder as subtitles show it, e.g. "Comics/Series" or "SD card/Comics"; empty for the device's root. */
    private fun relativePath(dir: File): String = StorageRoot.describe(dir.absolutePath, roots)

    /** Items a user would see inside [dir]: sub-folders and openable files, minus hidden ones. */
    private fun visibleChildren(dir: File): List<File> {
        val showHidden = prefs.showHidden
        return dir.listFiles()?.filter {
            (showHidden || !it.name.startsWith(".")) && (it.isDirectory || Formats.isOpenable(it.name))
        } ?: emptyList()
    }

    private fun buildDirectoryEntries(dir: File): List<BookEntry> {
        val files = dir.listFiles() ?: return emptyList()
        val showHidden = prefs.showHidden
        val snap = snapshot()
        val out = ArrayList<BookEntry>(files.size)
        for (f in files) {
            if (!showHidden && f.name.startsWith(".")) continue
            val isDir = f.isDirectory
            if (!isDir && Formats.typeOf(f.name) == EntryType.OTHER) continue
            out.add(entryFor(f, snap))
        }
        return out
    }

    private fun entryFor(file: File, snap: Snapshot): BookEntry {
        val isDir = file.isDirectory
        val history = if (isDir) null else snap.history[file.absolutePath]
        val children = if (isDir) visibleChildren(file) else null
        return BookEntry(
            file = file,
            name = file.name,
            isDir = isDir,
            type = if (isDir) EntryType.FOLDER else Formats.typeOf(file.name),
            size = if (isDir) 0 else file.length(),
            lastModified = file.lastModified(),
            childCount = children?.size ?: -1,
            readCount = children?.count { it.absolutePath in snap.read } ?: -1,
            pages = history?.total ?: -1,
            progressPage = history?.page ?: -1,
            favorite = file.absolutePath in snap.favorites,
            read = file.absolutePath in snap.read
        )
    }

    private fun showFavorites() {
        mode = TAB_FAVORITES
        adapter.highlightPath = null
        selectTab(TAB_FAVORITES)
        supportActionBar?.title = getString(R.string.menu_favorites)
        pathbar.visibility = View.GONE
        invalidateOptionsMenu()
        showProgress(true)
        launchListing {
            val (list, kinds) = withContext(AppDispatchers.io) { favoriteEntries() }
            progress.visibility = View.GONE
            favoriteKinds = kinds
            displayEntries(list)
            classifyPending(list)
        }
    }

    /** The favorites with their labels, and the sections already known for them. */
    private fun favoriteEntries(): Pair<List<BookEntry>, Map<String, FavoriteKind>> {
        val snap = snapshot()
        val paths = library.favorites()
        val labels = FavoriteLabels.of(paths, roots)
        val kinds = HashMap<String, FavoriteKind>()
        val list = paths.map { path ->
            val label = labels[path]
            val entry = entryFor(File(path), snap).let { if (label != null) it.copy(name = label.name, label = label) else it }
            val kind = if (!entry.isDir) FavoriteKind.COMIC else kindCache.get(path, entry.lastModified)
            if (kind != null) kinds[path] = kind
            entry
        }
        kindCache.retain(paths)
        return list to kinds
    }

    /**
     * Classifies the favorites not classified yet when they are grouped, moving them
     * into their sections as they are found, at most every [CLASSIFY_PUBLISH_MS].
     */
    private fun classifyPending(list: List<BookEntry>) {
        if (!prefs.groupFavorites) return
        val pending = list.filter { it.path !in favoriteKinds }
        if (pending.isEmpty()) return
        classifyJob?.cancel()
        classifyJob = lifecycleScope.launch {
            val found = HashMap<String, FavoriteKind>()
            var shownAt = SystemClock.uptimeMillis()
            for ((i, e) in pending.withIndex()) {
                // Leaving the tab or listing again stops here, between favorites.
                found[e.path] = withContext(AppDispatchers.io) {
                    FavoriteClassifier.classify(e.file, isRoot = roots.any { it.path == e.path })
                        .also { kindCache.put(e.path, e.lastModified, it) }
                }
                val now = SystemClock.uptimeMillis()
                if (i < pending.lastIndex && now - shownAt < CLASSIFY_PUBLISH_MS) continue
                favoriteKinds = favoriteKinds + found
                found.clear()
                shownAt = now
                if (mode == TAB_FAVORITES) applyFilterAndSort()
            }
        }
    }

    private fun toggleSection(kind: FavoriteKind) {
        val collapsed = prefs.collapsedSections
        prefs.collapsedSections = if (kind in collapsed) collapsed - kind else collapsed + kind
        applyFilterAndSort()
    }

    private fun sectionTitle(kind: FavoriteKind): String = getString(
        when (kind) {
            FavoriteKind.LIBRARY -> R.string.section_libraries
            FavoriteKind.SET -> R.string.section_sets
            FavoriteKind.COMIC -> R.string.section_comics
        }
    )

    private fun showHistory() {
        mode = TAB_HISTORY
        adapter.highlightPath = null
        selectTab(TAB_HISTORY)
        supportActionBar?.title = getString(R.string.menu_history)
        pathbar.visibility = View.GONE
        invalidateOptionsMenu()
        showProgress(true)
        launchListing {
            val list = withContext(AppDispatchers.io) {
                val snap = snapshot()
                library.history().map { entryForHistory(it, snap) }
            }
            progress.visibility = View.GONE
            displayEntries(list)
        }
    }

    private fun showBookmarks() {
        mode = TAB_BOOKMARKS
        adapter.highlightPath = null
        selectTab(TAB_BOOKMARKS)
        supportActionBar?.title = getString(R.string.menu_bookmarks)
        pathbar.visibility = View.GONE
        invalidateOptionsMenu()
        showProgress(true)
        launchListing {
            val list = withContext(AppDispatchers.io) {
                val snap = snapshot()
                library.bookmarks().map { entryForBookmark(it, snap) }
            }
            progress.visibility = View.GONE
            displayEntries(list)
        }
    }

    private fun entryForHistory(item: HistoryItem, snap: Snapshot): BookEntry {
        val file = File(item.path)
        val exists = file.exists()
        // Chapters are usually just "Chapter 12.cbz", so the folder below the name is
        // what tells two chapters from different comics apart.
        val where = file.parentFile?.let { relativePath(it) }
        return BookEntry(
            file = file,
            name = item.name,
            isDir = false,
            type = Formats.typeOf(item.name),
            size = if (exists) file.length() else 0,
            lastModified = item.time,
            pages = item.total,
            progressPage = item.page,
            favorite = item.path in snap.favorites,
            read = item.path in snap.read,
            subtitle = when {
                !exists -> getString(R.string.missing_file)
                where != null && where.isNotEmpty() -> where
                else -> null
            }
        )
    }

    private fun entryForBookmark(bookmark: com.infinity.comicreader.data.Bookmark, snap: Snapshot): BookEntry {
        val file = File(bookmark.path)
        return BookEntry(
            file = file,
            name = file.name.ifEmpty { bookmark.name },
            isDir = false,
            type = Formats.typeOf(file.name),
            size = 0,
            lastModified = bookmark.time,
            pages = snap.history[bookmark.path]?.total ?: -1,
            progressPage = bookmark.page,
            favorite = bookmark.path in snap.favorites,
            read = bookmark.path in snap.read,
            subtitle = getString(R.string.page_number, bookmark.page + 1)
        )
    }

    private fun displayEntries(list: List<BookEntry>) {
        allEntries.clear()
        allEntries.addAll(list)
        applyFilterAndSort()
        if (query.isNotBlank()) scheduleDeepSearch()
    }

    private fun applyFilterAndSort() {
        val deep = deepResults
        val filtered = when {
            query.isBlank() -> allEntries
            deep != null -> deep
            else -> allEntries.filter { it.name.contains(query, ignoreCase = true) }
        }

        val comparator = LibraryOrder.comparator(prefs.listSort, prefs.listSortDir)

        shown.clear()
        shown.addAll(filtered.sortedWith(comparator))
        if (mode == TAB_FAVORITES && prefs.groupFavorites) {
            val groups = FavoriteSections.arrange(shown) { favoriteKinds[it.path] }
            val collapsed = prefs.collapsedSections
            sectionItems = groups.map { if (it.kind in collapsed) 0 else it.entries.size }
            adapter.submit(FavoriteSections.rows(groups, collapsed) { sectionTitle(it) })
        } else {
            sectionItems = emptyList()
            adapter.submit(shown)
        }
        updateRecentHeader()
        updateEmptyState()
        updateFabIcon()
    }

    /**
     * Keeps a "Recent" row at the top of the browse list, pointing at the folder of
     * the last chapter that was read. Chapters are almost always just numbered, so
     * the folder name is what identifies the series — and tapping the row jumps
     * straight back to it.
     */
    private fun updateRecentHeader() {
        val show = mode == TAB_BROWSE && query.isBlank() && shown.isNotEmpty()
        val dir = if (show) library.history().firstOrNull()?.let { File(it.path).parentFile } else null
        adapter.header = dir?.let {
            BookAdapter.Header(
                title = getString(R.string.recent),
                subtitle = relativePath(it).ifEmpty { StorageRoot.of(it.absolutePath, roots)?.name ?: it.absolutePath },
                iconRes = R.drawable.ic_history,
                onClick = { loadDirectory(it) }
            )
        }
        // Grid rows share the height the header leaves.
        if (prefs.listGrid && prefs.gridRows > 0) updateCellSize()
    }

    private fun updateEmptyState() {
        val visible = shown.isEmpty()
        emptyView.visibility = if (visible) View.VISIBLE else View.GONE
        fab.visibility = if (!visible && prefs.listQuickFab) View.VISIBLE else View.GONE
        if (!visible) return

        val (icon, title, sub) = when {
            query.isNotBlank() -> Triple(R.drawable.ic_search, getString(R.string.empty_search), null)
            mode == TAB_FAVORITES -> Triple(
                R.drawable.ic_star_border, getString(R.string.no_favorites),
                getString(R.string.menu_add_favorite)
            )
            mode == TAB_HISTORY -> Triple(R.drawable.ic_history, getString(R.string.no_history), null)
            mode == TAB_BOOKMARKS -> Triple(
                R.drawable.ic_bookmark_border, getString(R.string.no_bookmarks),
                getString(R.string.menu_bookmark_add)
            )
            else -> Triple(R.drawable.ic_folder_open, getString(R.string.empty_folder), null)
        }
        emptyIcon.setImageResource(icon)
        emptyText.text = title
        emptySubtitle.text = sub.orEmpty()
        emptySubtitle.visibility = if (sub == null) View.GONE else View.VISIBLE
    }

    private fun scrollToHighlight(path: String) {
        val position = adapter.positionOf(path)
        if (position >= 0) recycler.scrollToPosition(position)
    }

    private fun showProgress(visible: Boolean) {
        progress.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) emptyView.visibility = View.GONE
    }

    // ------------------------------------------------------------------ actions

    private fun onEntryClick(entry: BookEntry) {
        when {
            entry.isDir -> loadDirectory(entry.file)
            mode == TAB_HISTORY -> {
                // History entries open the containing folder first, so the reader
                // never resumes from a stale/missing path.
                if (entry.file.exists()) openLocation(entry.file) else toast(R.string.open_location_failed)
            }
            mode == TAB_BOOKMARKS -> {
                if (entry.file.exists()) openViewer(entry.file, entry.progressPage.coerceAtLeast(0))
                else toast(R.string.open_location_failed)
            }
            Formats.isOpenable(entry.name) -> {
                // The reader decides where to start (ResumeMode.start).
                openViewer(entry.file, -1)
            }
            else -> toast(R.string.error_open)
        }
    }

    private fun onEntryLongClick(entry: BookEntry) {
        val path = entry.path
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        fun add(label: String, action: () -> Unit) {
            labels.add(label)
            actions.add(action)
        }

        if (mode == TAB_HISTORY && entry.file.exists()) {
            add(getString(R.string.menu_continue)) {
                openViewer(entry.file, library.pageFor(path))
            }
        }
        if (mode == TAB_BOOKMARKS) {
            add(getString(R.string.menu_remove_favorite)) {
                library.removeBookmark(path, entry.progressPage)
                showBookmarks()
            }
        } else {
            val favorite = library.isFavorite(path)
            add(if (favorite) getString(R.string.menu_remove_favorite) else getString(R.string.menu_add_favorite)) {
                toggleFavorite(path)
            }
            if (entry.isDir) {
                add(getString(R.string.menu_continue)) { continueReading(entry.file) }
                add(getString(R.string.menu_mark_all_read)) { markFolder(entry.file, true) }
                add(getString(R.string.menu_mark_all_unread)) { markFolder(entry.file, false) }
            } else {
                val read = library.isRead(path)
                add(if (read) getString(R.string.menu_mark_unread) else getString(R.string.menu_mark_read)) {
                    library.setRead(path, !read)
                    refreshCurrent()
                }
            }
            add(getString(R.string.menu_open_location)) { openLocation(entry.file) }
            if (entry.isDir) {
                add(getString(R.string.menu_set_startup)) {
                    prefs.setStartupFolder(path)
                    toast(R.string.startup_folder_set)
                }
            }
            if (entry.file.exists()) {
                add(getString(R.string.menu_rename)) { renameEntry(entry.file) }
                add(getString(R.string.delete)) { confirmDelete(entry.file) }
            }
        }
        if (mode == TAB_HISTORY) {
            add(getString(R.string.pref_clear_history)) {
                confirm(this, R.string.confirm_clear_history_title, R.string.confirm_clear_history_msg, R.string.clear) {
                    library.clearHistory()
                    refreshCurrent()
                }
            }
        }

        AlertDialog.Builder(this)
            .setTitle(entry.name)
            .setItems(labels.toTypedArray()) { _, which -> actions[which].invoke() }
            .show()
    }

    /** Only writes the setting: [applySettings] re-lays the list, as for any other change. */
    private fun toggleGrid() {
        prefs.listGrid = !prefs.listGrid
    }

    private fun updateFabIcon() {
        fab.setImageResource(if (prefs.listGrid) R.drawable.ic_view_list else R.drawable.ic_grid_view)
        fab.contentDescription = getString(if (prefs.listGrid) R.string.menu_list_view else R.string.menu_grid)
    }

    private fun applyLayoutManager() {
        val grid = prefs.listGrid
        val current = recycler.layoutManager
        if (grid && current is GridLayoutManager) {
            current.spanCount = columnCount()
            return
        }
        if (!grid && current != null && current !is GridLayoutManager) return
        // Keep the reader's place when switching between list and grid.
        val first = (current as? LinearLayoutManager)?.findFirstVisibleItemPosition() ?: RecyclerView.NO_POSITION
        recycler.layoutManager = if (grid) {
            GridLayoutManager(this, columnCount()).apply {
                // The "Recent" row and section headers are not grid cells: they span the whole width.
                spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int): Int =
                        if (adapter.isFullWidth(position)) spanCount else 1
                }
            }
        } else {
            LinearLayoutManager(this)
        }
        if (first > 0) recycler.scrollToPosition(first)
    }

    private fun columnCount(): Int {
        val widthDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        return GridSizing.columns(prefs.gridColumns, widthDp)
    }

    private fun keyboardVisible(): Boolean =
        ViewCompat.getRootWindowInsets(recycler)?.isVisible(WindowInsetsCompat.Type.ime()) == true

    /** Sizes grid cells: one column's width, and a height that fits the chosen rows on screen. */
    private fun updateCellSize() {
        if (!prefs.listGrid) {
            adapter.setCellSize(0, 0)
            return
        }
        val inner = recycler.width - recycler.paddingLeft - recycler.paddingRight
        val width = inner.coerceAtLeast(0) / columnCount()
        val rows = prefs.gridRows
        val header = if (rows > 0) headerHeight(inner) + sectionHeadersHeight(inner, rows) else 0
        val available = GridSizing.rowSpace(recycler.height, recycler.paddingTop, recycler.paddingBottom, header)
        val minPx = (MIN_CELL_DP * resources.displayMetrics.density).toInt()
        adapter.setCellSize(width, GridSizing.cellHeight(available, rows, minPx))
    }

    /** Height of the "Recent" row with its margins at list width [width], measured from its layout; 0 without one. */
    private fun headerHeight(width: Int): Int {
        val h = adapter.header ?: return 0
        if (width <= 0) return 0
        val view = headerProbe ?: layoutInflater.inflate(R.layout.item_book_header, recycler, false).also { headerProbe = it }
        view.findViewById<TextView>(R.id.title).text = h.title
        view.findViewById<TextView>(R.id.subtitle).apply {
            text = h.subtitle
            visibility = if (h.subtitle.isEmpty()) View.GONE else View.VISIBLE
        }
        return measuredHeight(view, width)
    }

    /** Height of the section headers shown with the first [rows] grid rows at list width [width]. */
    private fun sectionHeadersHeight(width: Int, rows: Int): Int {
        val count = GridSizing.headersWithin(rows, sectionItems, columnCount())
        if (count == 0 || width <= 0) return 0
        val view = sectionProbe ?: layoutInflater.inflate(R.layout.item_section, recycler, false).also { sectionProbe = it }
        view.findViewById<TextView>(R.id.title).text = sectionTitle(FavoriteKind.LIBRARY)
        return count * measuredHeight(view, width)
    }

    /** Height of [view] with its margins at width [width]. */
    private fun measuredHeight(view: View, width: Int): Int {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val margins = view.layoutParams as? ViewGroup.MarginLayoutParams
        return view.measuredHeight + (margins?.topMargin ?: 0) + (margins?.bottomMargin ?: 0)
    }

    private fun toggleFavorite(path: String?) {
        if (path == null) return
        val added = library.toggleFavorite(path)
        toast(if (added) R.string.menu_add_favorite else R.string.menu_remove_favorite)
        refreshCurrent()
    }

    private fun openLocation(file: File) {
        val dir = file.parentFile
        if (dir == null || !dir.isDirectory) {
            toast(R.string.open_location_failed)
            return
        }
        loadDirectory(dir, highlight = file.absolutePath)
        toast(R.string.menu_open_location)
    }

    private fun renameEntry(file: File) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(file.name)
            selectAll()
        }
        val container = LinearLayout(this).apply {
            setPadding(48, 24, 48, 0)
            addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_rename)
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty() || name == file.name) return@setPositiveButton
                if (!FileUtils.isSafeName(name)) {
                    toast(R.string.invalid_name)
                    return@setPositiveButton
                }
                val target = File(file.parentFile, name)
                // renameTo silently replaces an existing file, so refuse to.
                val ok = !target.exists() && runCatching { file.renameTo(target) }.getOrDefault(false)
                if (ok) library.replacePath(file.absolutePath, target.absolutePath)
                toast(if (ok) R.string.renamed else R.string.rename_failed)
                refreshCurrent()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(file: File) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_confirm_title)
            .setMessage(R.string.delete_confirm_msg)
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    val ok = withContext(AppDispatchers.io) {
                        runCatching { FileUtils.deleteTree(file) }.getOrDefault(false)
                    }
                    // Even a partial failure may have removed some files: forget what is gone.
                    if (!file.exists()) library.removePathsUnder(file.absolutePath)
                    toast(if (ok) getString(R.string.deleted_format, 1) else getString(R.string.op_failed))
                    refreshCurrent()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun newFolder() {
        val dir = currentDir ?: return
        val input = EditText(this).apply { inputType = InputType.TYPE_CLASS_TEXT; hint = getString(R.string.new_folder_hint) }
        val container = LinearLayout(this).apply {
            setPadding(48, 24, 48, 0)
            addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_new_folder)
            .setView(container)
            .setPositiveButton(R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                if (!FileUtils.isSafeName(name)) {
                    toast(R.string.invalid_name)
                    return@setPositiveButton
                }
                val created = File(dir, name).mkdir()
                toast(if (created) R.string.folder_created else R.string.error_open)
                refreshCurrent()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun refreshCurrent() {
        when (mode) {
            TAB_FAVORITES -> showFavorites()
            TAB_HISTORY -> showHistory()
            TAB_BOOKMARKS -> showBookmarks()
            else -> currentDir?.let { loadDirectory(it) } ?: openStartDirectory()
        }
    }

    private fun openViewer(file: File, page: Int) {
        val intent = Intent(this, ViewerActivity::class.java)
        intent.putExtra(ViewerActivity.EXTRA_PATH, file.absolutePath)
        intent.putExtra(ViewerActivity.EXTRA_PAGE, page)
        startActivity(intent)
    }

    /** Opens a book at [file]; returns a folder there for the caller to browse. Both by canonical path. */
    private fun handleIncomingFile(file: File): File? {
        val real = runCatching { file.canonicalFile }.getOrNull() ?: return null
        if (!isUnderStorage(real.path)) return null
        if (real.isFile && Formats.isOpenable(real.name)) openViewer(real, 0)
        return real.takeIf { it.isDirectory }
    }

    /** Only shared storage may be opened by path: never the app's own or other private folders. */
    private fun isUnderStorage(canonical: String): Boolean =
        StorageRoot.of(canonical, roots) != null || canonical.startsWith("/storage/")

    /** Opens a book handed in by another app; returns a folder handed in for the caller to browse. */
    private fun handleIncoming(intent: Intent): File? {
        val uri = intent.data ?: return null
        when (uri.scheme) {
            "file" -> return uri.path?.let { handleIncomingFile(File(it)) }
            "content" -> openSharedContent(uri)
        }
        return null
    }

    /** Another app handed us a content:// URI: copy it into the cache and open the copy. */
    private fun openSharedContent(uri: Uri) {
        showProgress(true)
        lifecycleScope.launch {
            val copy = withContext(AppDispatchers.io) { copyToCache(uri) }
            progress.visibility = View.GONE
            if (copy != null) openViewer(copy, 0) else toast(R.string.error_open)
        }
    }

    private fun copyToCache(uri: Uri): File? {
        val name = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment
        val safe = name?.let { File(it).name }
        if (safe == null || !FileUtils.isSafeName(safe) || !Formats.isOpenable(safe)) return null

        val dir = File(cacheDir, INCOMING_DIR)
        FileUtils.deleteTree(dir) // keep only the file being read
        if (!dir.mkdirs()) return null
        val out = File(dir, safe)
        return try {
            val input = contentResolver.openInputStream(uri) ?: return null
            input.use { source ->
                out.outputStream().use { sink ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = source.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > MAX_INCOMING_BYTES) throw java.io.IOException("File is too large")
                        sink.write(buffer, 0, n)
                    }
                }
            }
            out
        } catch (e: java.io.IOException) {
            out.delete()
            null
        }
    }

    // ------------------------------------------------------------------ continue / mark

    private fun continueReading(dir: File) {
        lifecycleScope.launch {
            val target = withContext(AppDispatchers.io) { pickContinue(dir) }
            if (target == null) toast(R.string.continue_none) else openViewer(target.first, target.second)
        }
    }

    /**
     * What to read next in [dir]: the most recently read book if it is unfinished, else the
     * first unread book after it, else the first unread book.
     */
    private fun pickContinue(dir: File): Pair<File, Int>? {
        val books = dir.listFiles()
            ?.filter { it.isFile && !it.name.startsWith(".") && Formats.isOpenable(it.name) }
            ?.sortedWith(compareBy(NaturalOrder) { it.name })
            ?: return null
        if (books.isEmpty()) return null
        val history = library.historyMap()
        val read = library.readSet()
        val last = books.filter { it.absolutePath in history }
            .maxByOrNull { history.getValue(it.absolutePath).time }
        if (last != null) {
            val page = history.getValue(last.absolutePath).page
            if (page > 0 && last.absolutePath !in read) return last to page
            books.drop(books.indexOf(last) + 1).firstOrNull { it.absolutePath !in read }
                ?.let { return it to 0 }
        }
        return books.firstOrNull { it.absolutePath !in read }?.let { it to 0 }
    }

    /** Marks the books directly in [dir] read, or unread once confirmed. */
    private fun markFolder(dir: File, read: Boolean) {
        if (read) return markFolderNow(dir, true)
        val message = android.text.TextUtils.expandTemplate(
            getText(R.string.confirm_mark_unread_msg), FolderPicker.describe(this, dir.absolutePath)
        )
        confirm(this, R.string.confirm_mark_unread_title, message, R.string.menu_mark_all_unread) {
            markFolderNow(dir, false)
        }
    }

    private fun markFolderNow(dir: File, read: Boolean) {
        lifecycleScope.launch {
            withContext(AppDispatchers.io) {
                val paths = dir.listFiles()
                    ?.filter { it.isFile && Formats.isOpenable(it.name) }
                    ?.map { it.absolutePath }
                    .orEmpty()
                library.setReadMany(paths, read)
            }
            toast(if (read) R.string.marked_read else R.string.marked_unread)
            refreshCurrent()
        }
    }

    private fun showStats() {
        val st = library.stats()
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_stats)
            .setMessage(
                getString(
                    R.string.stats_body, st.chapters, st.pages, st.seconds / 3600,
                    (st.seconds % 3600) / 60, st.daysRead, st.streak, st.bestStreak
                )
            )
            .setPositiveButton(R.string.ok, null)
            .setNeutralButton(R.string.reset_stats) { _, _ ->
                confirm(this, R.string.confirm_reset_stats_title, R.string.confirm_reset_stats_msg, R.string.reset) {
                    library.clearStats()
                }
            }
            .show()
    }

    private fun openCleanup(cleanupMode: String) {
        val root = currentDir ?: Environment.getExternalStorageDirectory()
        startActivity(
            Intent(this, CleanupActivity::class.java)
                .putExtra(CleanupActivity.EXTRA_MODE, cleanupMode)
                .putExtra(CleanupActivity.EXTRA_ROOT, root.absolutePath)
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncoming(intent)?.let { loadDirectory(it) }
    }

    // ------------------------------------------------------------------ menu

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_list, menu)
        val search = menu.findItem(R.id.menu_search)
        val searchView = search.actionView as? SearchView ?: return true
        searchView.queryHint = getString(R.string.search_hint)
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(q: String?): Boolean {
                applyQuery(q)
                searchView.clearFocus() // collapse the keyboard once the search is committed
                return true
            }

            override fun onQueryTextChange(q: String?): Boolean = applyQuery(q)
        })
        return true
    }

    /** Filters and re-sorts the list for [q], live as the user types and on submit. */
    private fun applyQuery(q: String?): Boolean {
        query = q.orEmpty()
        deepJob?.cancel()
        deepResults = null
        applyFilterAndSort()
        scheduleDeepSearch()
        return true
    }

    /** With "search subfolders" on, looks below the current folder once typing pauses. */
    private fun scheduleDeepSearch() {
        val dir = currentDir
        val text = query.trim()
        if (!prefs.deepSearch || mode != TAB_BROWSE || dir == null || text.length < 2) return
        deepJob?.cancel()
        deepJob = lifecycleScope.launch {
            delay(DEEP_SEARCH_DELAY_MS)
            val found = withContext(AppDispatchers.io) { deepSearch(dir, text) { isActive } }
            deepResults = found
            applyFilterAndSort()
        }
    }

    private fun deepSearch(root: File, text: String, alive: () -> Boolean): List<BookEntry> {
        val showHidden = prefs.showHidden
        val snap = snapshot()
        val rootPath = root.absolutePath
        return root.walkTopDown()
            .maxDepth(DEEP_SEARCH_DEPTH)
            .onEnter { showHidden || !it.name.startsWith(".") }
            .takeWhile { alive() }
            .filter { it != root && it.name.contains(text, ignoreCase = true) }
            .filter { it.isDirectory || Formats.isOpenable(it.name) }
            .take(DEEP_SEARCH_LIMIT)
            .map { f ->
                val where = f.parentFile?.absolutePath?.removePrefix(rootPath)?.trimStart('/').orEmpty()
                entryFor(f, snap).copy(subtitle = where.ifEmpty { f.parentFile?.name.orEmpty() })
            }
            .toList()
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val browsing = mode == TAB_BROWSE
        menu.findItem(R.id.menu_grid)?.title =
            getString(if (prefs.listGrid) R.string.menu_list_view else R.string.menu_grid)
        menu.findItem(R.id.menu_columns)?.isVisible = prefs.listGrid
        menu.findItem(R.id.menu_rows)?.isVisible = prefs.listGrid
        menu.findItem(R.id.menu_show_hidden)?.apply {
            isChecked = prefs.showHidden
            title = getString(if (prefs.showHidden) R.string.menu_hide_hidden else R.string.menu_show_hidden)
        }
        menu.findItem(R.id.menu_add_favorite)?.isVisible = browsing
        menu.findItem(R.id.menu_add_favorite)?.title =
            getString(
                if (currentDir?.let { library.isFavorite(it.absolutePath) } == true)
                    R.string.menu_remove_favorite else R.string.menu_add_favorite
            )
        menu.findItem(R.id.menu_new_folder)?.isVisible = browsing
        menu.findItem(R.id.menu_continue)?.isVisible = browsing
        menu.findItem(R.id.menu_mark_all_read)?.isVisible = browsing
        menu.findItem(R.id.menu_mark_all_unread)?.isVisible = browsing
        menu.findItem(R.id.menu_dupes)?.isVisible = browsing
        menu.findItem(R.id.menu_large)?.isVisible = browsing
        menu.findItem(R.id.menu_search_deep)?.apply {
            isVisible = browsing
            isChecked = prefs.deepSearch
        }
        menu.findItem(R.id.menu_sort_dir)?.isVisible =
            mode == TAB_BROWSE || mode == TAB_FAVORITES
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_grid -> toggleGrid()
            R.id.menu_sort -> showSortDialog()
            R.id.menu_sort_dir -> toggleSortDirection()
            R.id.menu_columns -> showColumnsDialog()
            R.id.menu_rows -> showRowsDialog()
            R.id.menu_show_hidden -> prefs.showHidden = !prefs.showHidden
            R.id.menu_new_folder -> newFolder()
            R.id.menu_continue -> currentDir?.let { continueReading(it) }
            R.id.menu_mark_all_read -> currentDir?.let { markFolder(it, true) }
            R.id.menu_mark_all_unread -> currentDir?.let { markFolder(it, false) }
            R.id.menu_search_deep -> {
                prefs.deepSearch = !prefs.deepSearch
                deepJob?.cancel()
                deepResults = null
                invalidateOptionsMenu()
                applyFilterAndSort()
                scheduleDeepSearch()
            }
            R.id.menu_stats -> showStats()
            R.id.menu_dupes -> openCleanup(CleanupActivity.MODE_DUPES)
            R.id.menu_large -> openCleanup(CleanupActivity.MODE_LARGE)
            R.id.menu_add_favorite -> toggleFavorite(currentDir?.absolutePath)
            R.id.menu_refresh -> refreshCurrent()
            R.id.menu_settings -> startActivity(Intent(this, SettingsActivity::class.java))
            R.id.menu_about -> startActivity(Intent(this, AboutActivity::class.java))
            R.id.menu_exit -> finish()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun showSortDialog() {
        val values = resources.getStringArray(R.array.sort_values)
        val labels = resources.getStringArray(R.array.sort_entries)
        val checked = values.indexOf(prefs.listSort).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_sort)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                prefs.listSort = values[which]
                dialog.dismiss()
            }
            .show()
    }

    private fun toggleSortDirection() {
        prefs.listSortDir = if (prefs.listSortDir == "asc") "desc" else "asc"
        toast(if (prefs.listSortDir == "asc") R.string.menu_ascending else R.string.menu_descending)
    }

    private fun showColumnsDialog() {
        val values = resources.getStringArray(R.array.columns_values)
        val labels = resources.getStringArray(R.array.columns_entries)
        val checked = values.indexOf(prefs.gridColumns.toString()).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_columns)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                prefs.gridColumns = values[which].toIntOrNull() ?: 0
                dialog.dismiss()
            }
            .show()
    }

    private fun showRowsDialog() {
        val values = resources.getStringArray(R.array.rows_values)
        val labels = resources.getStringArray(R.array.rows_entries)
        val checked = values.indexOf(prefs.gridRows.toString()).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.pref_grid_rows)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                prefs.gridRows = values[which].toIntOrNull() ?: 0
                dialog.dismiss()
            }
            .show()
    }

    // ------------------------------------------------------------------ back

    /** Returns true when the back press was handled by navigating somewhere. */
    private fun navigateUp(): Boolean {
        if (mode != TAB_BROWSE) {
            val dir = currentDir
            if (dir != null) loadDirectory(dir) else selectTab(TAB_BROWSE)
            return true
        }
        val dir = currentDir ?: return false
        val parent = parentWithinBounds(dir) ?: return false
        loadDirectory(parent)
        return true
    }

    /**
     * The parent directory, unless that would climb above the [StorageRoot.bound] of
     * [dir] (`/storage/emulated/0`, an SD card's top). Browsing must never leave the
     * user's files.
     */
    private fun parentWithinBounds(dir: File): File? {
        val path = dir.absolutePath
        val root = StorageRoot.bound(path, roots) ?: return null
        return if (path == root.path) null else dir.parentFile
    }

    /** Lists the mounted storage roots; picking one opens its top folder. */
    private fun showRootChooser() {
        val list = roots
        val current = currentDir?.let { StorageRoot.of(it.absolutePath, list) }
        AlertDialog.Builder(this)
            .setTitle(R.string.choose_root)
            .setSingleChoiceItems(list.map { it.name }.toTypedArray(), list.indexOf(current)) { dialog, which ->
                dialog.dismiss()
                loadDirectory(File(list[which].path))
            }
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!navigateUp()) {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object {
        private const val TAB_BROWSE = 0
        private const val TAB_FAVORITES = 1
        private const val TAB_HISTORY = 2
        private const val TAB_BOOKMARKS = 3
        private const val STATE_MODE = "mode"
        private const val STATE_DIR = "dir"
        private const val INCOMING_DIR = "incoming"
        private const val MAX_INCOMING_BYTES = 2L * 1024 * 1024 * 1024
        private const val DEEP_SEARCH_DELAY_MS = 300L
        private const val CLASSIFY_PUBLISH_MS = 250L
        private const val DEEP_SEARCH_DEPTH = 8
        private const val DEEP_SEARCH_LIMIT = 500

        /** Room for the name and details below a cover that is still recognizable. */
        private const val MIN_CELL_DP = 120
    }
}
