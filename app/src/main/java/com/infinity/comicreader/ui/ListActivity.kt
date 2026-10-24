package com.infinity.comicreader.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.provider.OpenableColumns
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.util.LruCache
import android.provider.Settings
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
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
import com.google.android.material.chip.Chip
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import com.infinity.comicreader.R
import com.infinity.comicreader.data.BookEntry
import com.infinity.comicreader.data.BookFactsCache
import com.infinity.comicreader.data.Bookmark
import com.infinity.comicreader.data.Crumb
import com.infinity.comicreader.data.EntryType
import com.infinity.comicreader.data.EntryValues
import com.infinity.comicreader.data.FavoriteClassifier
import com.infinity.comicreader.data.FavoriteKind
import com.infinity.comicreader.data.FavoriteKindCache
import com.infinity.comicreader.data.FavoriteLabels
import com.infinity.comicreader.data.FileStat
import com.infinity.comicreader.data.FolderSize
import com.infinity.comicreader.data.ForwardCrumbs
import com.infinity.comicreader.data.HistoryItem
import com.infinity.comicreader.data.Library
import com.infinity.comicreader.data.LibraryFilter
import com.infinity.comicreader.data.LibrarySnapshot
import com.infinity.comicreader.data.LibrarySort
import com.infinity.comicreader.data.ListSettings
import com.infinity.comicreader.data.MissingFolder
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.RemovalNotices
import com.infinity.comicreader.data.ResumeMode
import com.infinity.comicreader.data.SettingsRepository
import com.infinity.comicreader.data.SortKey
import com.infinity.comicreader.data.SortValues
import com.infinity.comicreader.data.StartupLocation
import com.infinity.comicreader.data.StorageRoot
import com.infinity.comicreader.data.StorageRoots
import com.infinity.comicreader.data.TabView
import com.infinity.comicreader.data.ToolbarButton
import com.infinity.comicreader.data.Years
import com.infinity.comicreader.net.RemoteException
import com.infinity.comicreader.net.RemoteFile
import com.infinity.comicreader.net.Remotes
import com.infinity.comicreader.reader.BookFactsReader
import com.infinity.comicreader.util.AppDispatchers
import com.infinity.comicreader.util.FileUtils
import com.infinity.comicreader.util.Formats
import com.infinity.comicreader.util.NaturalOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

class ListActivity : AppCompatActivity(), LocationSheet.Host {

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
    private lateinit var filterChip: Chip
    private lateinit var peek: PeekOverlay
    private lateinit var pinch: PinchPeek

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
    private lateinit var retryButton: View

    /** Why the folder browsed could not be listed (its share is unreachable); null when it was. */
    private var listError: RemoteException? = null

    private var currentDir: File? = null

    /** The deepest folder of the branch browsed, shown as forward crumbs past [currentDir]. */
    private var trail: String? = null

    /** Mounted storage volumes, refreshed whenever the screen resumes. */
    private var roots: List<StorageRoot> = emptyList()

    /** Lists the storage roots; tests stand in drives. */
    internal var listRoots: () -> List<StorageRoot> = { StorageRoots.list(this) }

    /** A drive plugged in that the location picker has not shown yet. */
    private var pluggedDrive: String? = null
    private var mode = TAB_BROWSE
    private var query = ""
    private var resumedOnce = false
    private var started = false

    /** The state to come back to after a recreate, until [startUp] uses it. */
    private var restored: Bundle? = null

    /** Overrides the startup tab setting for the first [startUp]. */
    private var startTab: Int? = null

    /** True when the app is starting afresh: the first [startUp] offers the book read last ([offerResume]). */
    private var appStart = false

    /** A folder handed in by another app, browsed by the first [startUp] instead of the startup location. */
    private var incomingDir: File? = null
    /** The options menu shown, so searching can hide the other toolbar buttons without rebuilding it. */
    private var menu: Menu? = null

    /** The search field is open. */
    private var searching = false
    private var deepJob: Job? = null
    private var deepResults: List<BookEntry>? = null
    private val allEntries = ArrayList<BookEntry>()

    /** Known sections of the favorites shown; a missing path is still being classified. */
    private var favoriteKinds: Map<String, FavoriteKind> = emptyMap()
    private var classifyJob: Job? = null

    private lateinit var factsCache: BookFactsCache

    /** Folder totals found so far, by path. */
    private val folderSizes = HashMap<String, Long>()

    /** Books that could not be read, by path, with the size they failed at; retried next launch. */
    private val factsFailed = HashMap<String, Long>()

    /** Finds values the sort needs in the background ([scheduleFacts]). */
    private var factsJob: Job? = null

    /** What [factsJob] covers while it runs. */
    private var factsWork: FactsWork? = null

    /** Counts listings shown, so background work knows which one it serves. */
    private var listing = 0

    /** The values the list was last sorted by. */
    private var sortValues: SortValues? = null

    /** The listing being loaded; a newer one cancels it, so only the latest is shown. */
    private var listJob: Job? = null

    /** Keeps the "Recent" row on the book opened last, as the reader records it. */
    private var historyWatch: AutoCloseable? = null
    private lateinit var kindCache: FavoriteKindCache

    /** Follows drives as they are plugged in and pulled out, while started. */
    private lateinit var storageWatcher: StorageWatcher
    private val shown = ArrayList<BookEntry>()

    /** The series name left out of titles in the Set browsed, or null. */
    private var setPrefix: String? = null

    /** Kinds of folders browsed that their listing did not settle, with the modification time found at. */
    private val folderKinds = LruCache<String, Pair<Long, FavoriteKind>>(256)

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
        factsCache = BookFactsCache(this)
        historyWatch = library.watchHistory { if (started && mode == TAB_BROWSE) updateRecentHeader() }
        lifecycleScope.launch {
            withContext(AppDispatchers.io) { factsCache.load() }
            // A list shown before the facts were in sorts again with them.
            if (allEntries.isNotEmpty()) applyFilterAndSort()
        }
        applied = settings.list.value
        setContentView(R.layout.activity_list)

        toolbar = findViewById(R.id.toolbar)
        tabs = findViewById(R.id.tabs)
        pathbar = findViewById(R.id.pathbar)
        recycler = findViewById(R.id.recycler)
        fab = findViewById(R.id.fab)
        filterChip = findViewById(R.id.filter_chip)
        filterChip.setOnClickListener { TabDialogFragment.show(this, TabDialogFragment.Kind.FILTER, tabName()) }
        filterChip.setOnCloseIconClickListener { prefs.setFilter(tabName(), LibraryFilter.NONE) }
        filterChip.closeIconContentDescription = getString(R.string.filter_clear)
        progress = findViewById(R.id.progress)
        emptyView = findViewById(R.id.empty_view)
        emptyIcon = findViewById(R.id.empty_icon)
        emptyText = findViewById(R.id.empty_text)
        emptySubtitle = findViewById(R.id.empty_subtitle)
        retryButton = findViewById(R.id.empty_retry)
        retryButton.setOnClickListener { retryListing() }

        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(true)
        toolbar.setTitle(R.string.app_name)

        adapter = BookAdapter(
            grid = view.grid,
            prefs = prefs,
            onClick = { onEntryClick(it) },
            onLongClick = { onEntryLongClick(it) },
            onStarClick = { toggleFavorite(it.file.absolutePath) }
        )
        adapter.onSectionClick = { toggleSection(it.kind) }
        adapter.hiddenPages = library::hiddenAll
        // Folder peeks from list rows reuse the grid's covers at this size.
        adapter.onGridCover = { w, h -> prefs.setGridCover(resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE, w, h) }
        recycler.adapter = adapter
        applyLayoutManager()
        setUpPeek()
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
        pathbar.onCrumbClick = { openCrumb(it) }
        pathbar.onLocationsClick = { showRootChooser() }
        roots = listRoots()
        storageWatcher = StorageWatcher(this) { refreshRoots() }

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
            appStart = intent.data == null
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
        trail?.let { outState.putString(STATE_TRAIL, it) }
    }

    override fun onResume() {
        super.onResume()
        applyStatusBarSetting()
        // A drive may have come or gone while stopped.
        val left = refreshRoots()
        showPluggedDrive()
        if (left) return
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

    override fun onStart() {
        super.onStart()
        adapter.stopped = false
        storageWatcher.start()
    }

    override fun onStop() {
        adapter.stopped = true
        storageWatcher.stop()
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        historyWatch?.close()
        peek.hide()
        adapter.release()
    }

    /**
     * Lists the storage roots again ([now]). When the drive of the folder being browsed is
     * gone, says so and goes to the device's storage; returns true then. Otherwise
     * the crumbs and an open location picker follow the new list. A drive plugged in
     * opens the location picker with it highlighted, at once in the foreground, else
     * on the next return ([showPluggedDrive]).
     */
    internal fun refreshRoots(now: List<StorageRoot> = listRoots()): Boolean {
        val before = roots
        roots = now
        LocationSheet.find(this)?.update(roots)
        if (roots == before) return false
        StorageRoot.added(before, roots)?.let { pluggedDrive = it.path }
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) showPluggedDrive()
        val dir = currentDir ?: return false
        val gone = StorageRoot.removed(dir.absolutePath, before, roots)
        if (gone != null) {
            // The reader closing a book on this drive has just said so.
            if (!RemovalNotices.alreadyTold(gone)) toast(getString(R.string.location_removed, gone.name))
            trail = null
            currentDir = null
            if (mode == TAB_BROWSE && hasStorageAccess()) {
                loadDirectory(File(roots.first().path))
                return true
            }
            return false
        }
        if (mode == TAB_BROWSE) showCrumbs(dir)
        return false
    }

    /** Re-applies whatever changed since the settings last shown. */
    private fun applySettings(s: ListSettings) {
        val old = applied
        if (old == s) return
        applied = s
        if (s.views[tabName()] != old.views[tabName()]) applyView()
        if (s.coverShape != old.coverShape || s.titleDisplay != old.titleDisplay || s.coverSpacing != old.coverSpacing) {
            adapter.options = rowOptions()
        }
        if (s.shortenPrefixes != old.shortenPrefixes && mode == TAB_BROWSE) refreshCurrent()
        if (s.showHidden != old.showHidden) {
            invalidateOptionsMenu()
            // Coming back to the browse tab, onResume reloads the folder anyway.
            if (mode != TAB_BROWSE || lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) refreshCurrent()
        }
        if (s.sorts[tabName()] != old.sorts[tabName()] || s.filters[tabName()] != old.filters[tabName()] ||
            s.detectDirection != old.detectDirection
        ) {
            applyFilterAndSort()
        } else if (s.thumbnails != old.thumbnails || s.charset != old.charset ||
            s.linearLight != old.linearLight || s.thumbFit != old.thumbFit || s.cropAnchor != old.cropAnchor
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
        if (s.toolbarButtons != old.toolbarButtons) invalidateOptionsMenu()
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
        val savedDir = state?.getString(STATE_DIR)?.let { Remotes.file(it) }?.takeIf { it.isDirectory }
        trail = state?.getString(STATE_TRAIL)
        val tab = state?.getInt(STATE_MODE, TAB_BROWSE) ?: startTab ?: tabOf(prefs.startupTab)
        startTab = null
        started = true
        val dir = savedDir ?: incomingDir
        incomingDir = null
        if (appStart) {
            appStart = false
            offerResume()
        }
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

    /**
     * As the app starts, reopens the book read last where it was left, asking first unless
     * the setting says always: Yes / No, with "Don't ask again" making the answer the
     * setting ([ResumeMode.afterAnswer]). Nothing is offered for a finished book, nor for
     * one that is gone.
     */
    private fun offerResume() {
        val mode = prefs.startupResume
        if (mode == ResumeMode.NO) return
        val last = library.history().firstOrNull { it.path.isNotEmpty() } ?: return
        if (library.isRead(last.path) || Remotes.isOrphan(last.path)) return
        val file = Remotes.file(last.path, askNetwork = false)
        if (!file.exists()) return
        val page = last.page.coerceAtLeast(0)
        if (mode == ResumeMode.YES) {
            openViewer(file, page)
            return
        }
        val extra = layoutInflater.inflate(R.layout.dialog_resume, null)
        val dontAsk = extra.findViewById<CheckBox>(R.id.resume_dont_ask)
        fun answer(resume: Boolean) {
            prefs.startupResume = prefs.startupResume.afterAnswer(resume, dontAsk.isChecked)
            if (resume) openViewer(file, page)
        }
        val message = if (last.total > 0) {
            val percent = ((page + 1) * 100f / last.total).toInt().coerceIn(1, 100)
            last.name + "\n" + getString(R.string.resume_progress, page + 1, last.total, percent)
        } else {
            last.name
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.startup_resume_title)
            .setMessage(message)
            .setView(extra)
            .setPositiveButton(R.string.resume_yes) { _, _ -> answer(resume = true) }
            .setNegativeButton(R.string.resume_no) { _, _ -> answer(resume = false) }
            .show()
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
            // A share's folder is taken as it is; browsing it says when it cannot be reached.
            isDir = { Remotes.file(it, askNetwork = false).isDirectory },
            reachable = { nearestFolder(it) != null }
        )
        return path?.let { Remotes.file(it) } ?: deviceStart()
    }

    /** Where the device's storage opens ([Prefs.locationStart]). */
    private fun deviceStart(): File = File(prefs.locationStart(Environment.getExternalStorageDirectory().absolutePath))

    /** Browses the startup location, unless another listing starts while it is worked out. */
    private fun openStartDirectory() {
        showProgress(true)
        launchListing {
            val dir = withContext(AppDispatchers.io) { startDirectory() }
            if (dir is RemoteFile && !answersSoon(dir)) {
                startOnDevice(dir)
                return@launchListing
            }
            loadDirectory(dir)
        }
    }

    /**
     * True when the share of [dir] answers listing it within [SHARE_WAIT_MS]: lists it, or
     * says why not (a refused sign-in, a folder gone), which browsing it then shows. Otherwise,
     * also when it cannot be reached at all, it is asked again in the background ([keepTrying]).
     */
    private suspend fun answersSoon(dir: RemoteFile): Boolean {
        val attempt = lifecycleScope.async(AppDispatchers.io) { listingAnswer(dir) }
        val answer = withTimeoutOrNull(SHARE_WAIT_MS) { attempt.await() }
        if (answer != null && answer != RemoteException.Reason.UNREACHABLE) return true
        keepTrying(dir, attempt)
        return false
    }

    /** Lists [dir]: null when that works, else why not. Blocking. */
    private fun listingAnswer(dir: RemoteFile): RemoteException.Reason? = try {
        dir.entries()
        null
    } catch (e: RemoteException) {
        e.reason
    } catch (e: Exception) {
        RemoteException.Reason.UNREACHABLE
    }

    /** Opens the device's start folder instead of [dir], on a share slow to answer, saying so. */
    private fun startOnDevice(dir: File) {
        val share = StorageRoot.of(dir.absolutePath, roots)?.name ?: Remotes.idOf(dir.absolutePath)?.let { Remotes.share(it)?.name }.orEmpty()
        toast(getString(R.string.share_slow_start, share))
        loadDirectory(deviceStart())
    }

    /**
     * Waits for [attempt] at listing [dir], then asks again a few times further apart while
     * it fails. Once the share answers, offers to open [dir], unless another folder or tab
     * was opened meanwhile.
     */
    private fun keepTrying(dir: RemoteFile, attempt: Deferred<RemoteException.Reason?>) {
        val fallback = deviceStart().absolutePath
        lifecycleScope.launch {
            var answered = attempt.await() == null
            var wait = SHARE_RETRY_MS
            repeat(SHARE_RETRIES) {
                if (answered) return@repeat
                delay(wait)
                wait *= 2
                Remotes.idOf(dir.absolutePath)?.let { Remotes.retry(it) }
                answered = withContext(AppDispatchers.io) { listingAnswer(dir) } == null
            }
            if (!answered || mode != TAB_BROWSE || currentDir?.absolutePath != fallback) return@launch
            val share = StorageRoot.of(dir.absolutePath, roots)?.name.orEmpty()
            Snackbar.make(recycler, getString(R.string.share_answering, share), Snackbar.LENGTH_LONG)
                .setAction(R.string.share_open) { loadDirectory(dir) }
                .show()
        }
    }

    // ------------------------------------------------------------------ tabs

    private fun selectTab(index: Int) {
        if (tabs.selectedTabPosition != index) tabs.getTabAt(index)?.select()
    }

    /** The settings name of tab [index]. */
    private fun tabName(index: Int = mode): String = when (index) {
        TAB_FAVORITES -> Prefs.TAB_FAVORITES
        TAB_HISTORY -> Prefs.TAB_HISTORY
        TAB_BOOKMARKS -> Prefs.TAB_BOOKMARKS
        else -> Prefs.TAB_BROWSE
    }

    private fun rowOptions() =
        RowOptions(
            density = view.density, shape = prefs.coverShape, title = prefs.titleDisplay, detail = view.detail,
            spacing = prefs.coverSpacing
        )

    /** The current tab's view settings. */
    private val view: TabView get() = prefs.view(tabName())

    /** Lays the list out as the current tab's view says. */
    private fun applyView() {
        adapter.grid = view.grid
        adapter.options = rowOptions()
        applyLayoutManager()
        updateCellSize()
        updateFabIcon()
        invalidateOptionsMenu()
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

    /** [path] when it is a folder, else its nearest existing parent within its root; null when none is left. */
    private fun nearestFolder(path: String): String? =
        MissingFolder.nearest(path, roots) { Remotes.file(it, askNetwork = false).isDirectory }

    /**
     * Browses [dir], or when it no longer exists its nearest existing parent (the device's
     * storage when none is left), saying so (unless [noted] already) and dropping the
     * forward crumbs through it. A folder on a share is found gone only by listing it.
     */
    private fun loadDirectory(dir: File, highlight: String? = null, noted: Boolean = false) {
        if (!dir.isDirectory) {
            val missing = dir.absolutePath
            if (!noted) toast(R.string.folder_gone)
            trail = MissingFolder.trailAfter(trail, missing)
            val instead = nearestFolder(missing)?.let { Remotes.file(it) } ?: Environment.getExternalStorageDirectory()
            if (instead.absolutePath != missing && instead.isDirectory) return loadDirectory(instead, noted = true)
        }
        switchingTo(TAB_BROWSE, dir)
        mode = TAB_BROWSE
        applyView()
        currentDir = dir
        trail = ForwardCrumbs.next(trail, dir.absolutePath)
        deepJob?.cancel()
        deepResults = null
        prefs.lastPath = dir.absolutePath
        adapter.highlightPath = highlight
        selectTab(TAB_BROWSE)

        val atRoot = roots.any { it.path == dir.absolutePath }
        supportActionBar?.title = if (atRoot || dir.name.isBlank()) getString(R.string.app_name) else dir.name
        showCrumbs(dir)
        pathbar.visibility = if (prefs.listPathView) View.VISIBLE else View.GONE
        invalidateOptionsMenu()

        showProgress(true)
        launchListing {
            val listed = withContext(AppDispatchers.io) {
                runCatching {
                    val entries = buildDirectoryEntries(dir)
                    val prefix = sharedPrefixOf(entries)
                    Triple(entries, prefix, if (prefix == null) null else knownKindOf(dir, entries))
                }
            }
            val failure = listed.exceptionOrNull()?.let {
                // Anything that stops a share's listing shows as that share failing, with Retry.
                if (it is RemoteException || dir !is RemoteFile) it
                else RemoteException(RemoteException.Reason.UNREACHABLE, it.message, it)
            }
            if (failure != null) {
                if (failure !is RemoteException) throw failure
                if (failure.reason == RemoteException.Reason.MISSING) {
                    shareFolderGone(dir, noted)
                    return@launchListing
                }
                progress.visibility = View.GONE
                listError = failure
                setPrefix = null
                displayEntries(emptyList())
                return@launchListing
            }
            val (list, prefix, kind) = listed.getOrThrow()
            setPrefix = prefix.takeIf { kind == FavoriteKind.SET }
            progress.visibility = View.GONE
            displayEntries(list)
            if (highlight != null) scrollToHighlight(highlight)
            // A folder its listing does not settle is looked into after it shows.
            if (prefix != null && kind == null) {
                val found = withContext(AppDispatchers.io) {
                    val time = dir.lastModified()
                    FavoriteClassifier.classify(dir, isRoot = atRoot).also { folderKinds.put(dir.absolutePath, time to it) }
                }
                if (found == FavoriteKind.SET) {
                    setPrefix = prefix
                    applyFilterAndSort()
                }
            }
        }
    }

    /** Goes to the nearest parent of [dir], a share's folder its listing found gone, saying so unless [noted]. */
    private fun shareFolderGone(dir: File, noted: Boolean) {
        if (!noted) toast(R.string.folder_gone)
        val missing = dir.absolutePath
        trail = MissingFolder.trailAfter(trail, missing)
        val parent = dir.parent?.takeIf { Remotes.isRemote(it) }
        loadDirectory(parent?.let { Remotes.file(it) } ?: Environment.getExternalStorageDirectory(), noted = true)
    }

    /**
     * The series name every item of a folder listed as [entries] starts with, to
     * leave out of their titles inside a Set, when the setting asks for it. Loose
     * images are left out.
     */
    private fun sharedPrefixOf(entries: List<BookEntry>): String? {
        if (!prefs.shortenPrefixes) return null
        return TitlePrefix.common(entries.filter { it.type != EntryType.IMAGE }.map { it.name })
    }

    /** The kind of [dir] listed as [entries] when known without looking into its subfolders. */
    private fun knownKindOf(dir: File, entries: List<BookEntry>): FavoriteKind? {
        val path = dir.absolutePath
        folderKinds.get(path)?.let { (time, kind) -> if (time == dir.lastModified()) return kind }
        val visible = entries.filter { !it.name.startsWith(".") }
        return FavoriteClassifier.quickKind(
            books = visible.count { it.type == EntryType.ARCHIVE || it.type == EntryType.PDF },
            images = visible.count { it.type == EntryType.IMAGE },
            folders = visible.count { it.isDir },
            isRoot = roots.any { it.path == path }
        )
    }

    /**
     * Before showing tab [tab] (Browse at [dir]): covers still loading for another tab or
     * folder are dropped, so those of the view about to show come first.
     */
    private fun switchingTo(tab: Int, dir: File? = null) {
        if (mode != tab || (tab == TAB_BROWSE && currentDir?.absolutePath != dir?.absolutePath)) adapter.cancelCovers()
    }

    /** Runs [load] as the only listing: an older one, and favorites still being classified, stop. */
    private fun launchListing(load: suspend CoroutineScope.() -> Unit) {
        if (peek.isShowing) peek.hide()
        listError = null
        listJob?.cancel()
        classifyJob?.cancel()
        factsJob?.cancel()
        listJob = lifecycleScope.launch(block = load)
    }

    private fun snapshot() = LibrarySnapshot.load(library, prefs)

    /** A folder as subtitles show it, e.g. "Comics/Series" or "SD card/Comics"; empty for the device's root. */
    private fun relativePath(dir: File): String = StorageRoot.describe(dir.absolutePath, roots)

    /** The entries of [dir]; throws [RemoteException] when its share cannot be reached. */
    private fun buildDirectoryEntries(dir: File): List<BookEntry> {
        val files = (if (dir is RemoteFile) dir.entries().toTypedArray<File>() else dir.listFiles()) ?: return emptyList()
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

    private fun entryFor(file: File, snap: LibrarySnapshot): BookEntry = snap.entry(file, prefs.showHidden)

    private fun showFavorites() {
        switchingTo(TAB_FAVORITES)
        mode = TAB_FAVORITES
        applyView()
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
            // Favorites on shares show from what is known, without waiting for the network.
            val entry = entryFor(Remotes.file(path, askNetwork = false), snap)
                .let { if (label != null) it.copy(name = label.name, label = label) else it }
            val kind = when {
                !entry.isDir -> FavoriteKind.COMIC
                Remotes.isRemote(path) -> kindCache.peek(path)
                else -> kindCache.get(path, entry.lastModified)
            }
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
        // Folders on shares are checked too: their kind is shown as cached until their time is known.
        val pending = list.filter { it.path !in favoriteKinds || (it.isDir && it.file is RemoteFile) }
        if (pending.isEmpty()) return
        classifyJob?.cancel()
        classifyJob = lifecycleScope.launch {
            val found = HashMap<String, FavoriteKind>()
            var shownAt = SystemClock.uptimeMillis()
            for ((i, e) in pending.withIndex()) {
                // Leaving the tab or listing again stops here, between favorites.
                val kind = withContext(AppDispatchers.io) { classify(e) }
                if (kind != null) found[e.path] = kind
                val now = SystemClock.uptimeMillis()
                if (i < pending.lastIndex && now - shownAt < CLASSIFY_PUBLISH_MS) continue
                favoriteKinds = favoriteKinds + found
                found.clear()
                shownAt = now
                // Sections fill in without re-sorting rows by values found since.
                if (mode == TAB_FAVORITES) applyFilterAndSort(resort = false)
            }
        }
    }

    /**
     * The kind of favorite [e], kept with its modification time; a folder on a share
     * is looked into only when its time changed since, and is left as it is (null)
     * while the share cannot be reached.
     */
    private fun classify(e: BookEntry): FavoriteKind? {
        val isRoot = roots.any { it.path == e.path }
        val file = e.file
        if (file !is RemoteFile) {
            return FavoriteClassifier.classify(file, isRoot).also { kindCache.put(e.path, e.lastModified, it) }
        }
        val time = if (isRoot) 0L else runCatching { file.fresh() }.getOrNull()?.modified ?: return null
        kindCache.get(e.path, time)?.let { return it }
        val kind = runCatching { FavoriteClassifier.classify(Remotes.file(e.path), isRoot) }.getOrNull() ?: return null
        kindCache.put(e.path, time, kind)
        return kind
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
        switchingTo(TAB_HISTORY)
        mode = TAB_HISTORY
        applyView()
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
        switchingTo(TAB_BOOKMARKS)
        mode = TAB_BOOKMARKS
        applyView()
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

    private fun entryForHistory(item: HistoryItem, snap: LibrarySnapshot): BookEntry {
        val file = Remotes.file(item.path, askNetwork = false)
        val exists = file.exists()
        val stat = if (exists) FileStat.of(file) else null
        // Chapters are usually just "Chapter 12.cbz", so the folder below the name is
        // what tells two chapters from different comics apart.
        val where = file.parentFile?.let { relativePath(it) }
        return BookEntry(
            file = file,
            name = item.name,
            isDir = false,
            type = Formats.typeOf(item.name),
            size = stat?.size ?: 0,
            lastModified = item.time,
            pages = item.total,
            progressPage = item.page,
            favorite = item.path in snap.favorites,
            read = item.path in snap.read,
            begun = true,
            added = stat?.created ?: 0,
            accessed = snap.access[item.path] ?: item.time,
            year = Years.fromName(item.name),
            direction = snap.directionOf(item.path, isDir = false),
            directionSet = snap.isDirectionSet(item.path, isDir = false),
            subtitle = when {
                !exists -> getString(R.string.missing_file)
                where != null && where.isNotEmpty() -> where
                else -> null
            }
        )
    }

    private fun entryForBookmark(bookmark: Bookmark, snap: LibrarySnapshot): BookEntry {
        val file = Remotes.file(bookmark.path, askNetwork = false)
        val stat = if (file.exists()) FileStat.of(file) else null
        return BookEntry(
            file = file,
            name = file.name.ifEmpty { bookmark.name },
            isDir = false,
            type = Formats.typeOf(file.name),
            size = stat?.size ?: 0,
            lastModified = bookmark.time,
            pages = snap.history[bookmark.path]?.total ?: -1,
            progressPage = bookmark.page,
            bookmarkPage = bookmark.page,
            favorite = bookmark.path in snap.favorites,
            read = bookmark.path in snap.read,
            begun = bookmark.path in snap.history,
            added = stat?.created ?: 0,
            // A bookmark counts as accessed when added, or later when its book was read.
            accessed = maxOf(bookmark.time, snap.history[bookmark.path]?.time ?: 0),
            year = Years.fromName(file.name),
            direction = snap.directionOf(bookmark.path, isDir = false),
            directionSet = snap.isDirectionSet(bookmark.path, isDir = false),
            subtitle = getString(R.string.page_number, bookmark.page + 1)
        )
    }

    private fun displayEntries(list: List<BookEntry>) {
        listing++
        allEntries.clear()
        allEntries.addAll(list)
        applyFilterAndSort()
        if (query.isNotBlank()) scheduleDeepSearch()
    }

    /** Sort and filter values as found so far: folder totals and what books' contents told. */
    private fun values(hidden: Map<String, Int>) =
        EntryValues(folderSizes, prefs.detectDirection, hidden) { e -> if (e.isDir) null else factsCache.get(e.path, e.size) }

    /**
     * Shows the entries matching the search in the tab's order. Unless [resort], the
     * order uses the values the list was last sorted by, so values found since fill
     * in without moving rows.
     */
    private fun applyFilterAndSort(resort: Boolean = true) {
        val deep = deepResults
        val searched = when {
            query.isBlank() -> allEntries
            deep != null -> deep
            else -> allEntries.filter { it.name.contains(query, ignoreCase = true) }
        }

        val sort = prefs.sort(tabName())
        val filter = prefs.filter(tabName())
        val hidden = library.hiddenCounts()
        val live = values(hidden)
        val order = sortValues.takeIf { !resort } ?: live.frozen(searched, sort.keys).also { sortValues = it }
        val browsing = mode == TAB_BROWSE
        val passing = if (filter.isActive) searched.filter { filter.accepts(it, live, browsing) } else searched

        shown.clear()
        shown.addAll(passing.sortedWith(LibrarySort.comparator(sort, order)).map { withFacts(it, hidden) })
        filterChip.visibility = if (filter.isActive) View.VISIBLE else View.GONE
        if (resort) scheduleFacts(sort.keys + filter.keys)
        adapter.titlePrefix = setPrefix.takeIf { mode == TAB_BROWSE && query.isBlank() }
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
     * [entry] with the page count read from its book when it has none of its own, less
     * the pages it hides ([hidden] counts by path), and the direction detected in it when
     * its folder has none set, so a wraparound cover without a barcode shows the half the
     * book starts on.
     */
    private fun withFacts(entry: BookEntry, hidden: Map<String, Int>): BookEntry {
        if (entry.isDir) return entry
        val facts = factsCache.get(entry.path, entry.size) ?: return entry
        var out = entry
        if (entry.pages <= 0 && facts.pages > 0) out = out.copy(pages = facts.shownPages(hidden[entry.path] ?: 0))
        val detected = facts.detected?.direction
        if (detected != null && !entry.directionSet && prefs.detectDirection && detected != entry.direction) out = out.copy(direction = detected)
        return out
    }

    /**
     * Finds the values [keys] need that are not known yet, in the background: page
     * counts, ComicInfo years and detected directions of books, and folder totals. Books fill in as they
     * are read, at most every [FACTS_PUBLISH_MS], without moving rows; folder totals
     * reorder the list once all are in. Work already running for the same listing
     * goes on undisturbed.
     */
    private fun scheduleFacts(keys: Set<SortKey>) {
        val needFacts = SortKey.LENGTH in keys || SortKey.YEAR in keys || (SortKey.DIRECTION in keys && prefs.detectDirection)
        val needSizes = SortKey.SIZE in keys
        if (!needFacts && !needSizes) return
        val work = FactsWork(listing, needFacts, needSizes)
        if (factsJob?.isActive == true && factsWork?.covers(work) == true) return
        val entries = allEntries.toList()
        fun unknown(e: BookEntry) = factsCache.get(e.path, e.size) == null && factsFailed[e.path] != e.size
        val candidates = if (!needFacts) emptyList() else entries.filter {
            !it.isDir && it.type != EntryType.IMAGE && it.size > 0 && (!factsCache.isLoaded || unknown(it))
        }
        val folders = if (!needSizes) emptyList() else entries.filter { it.isDir && it.path !in folderSizes }
        if (candidates.isEmpty() && folders.isEmpty()) return
        factsJob?.cancel()
        factsWork = work
        factsJob = lifecycleScope.launch {
            if (!factsCache.isLoaded) withContext(AppDispatchers.io) { factsCache.load() }
            val books = candidates.filter { unknown(it) }
            val charset = prefs.charset
            var shownAt = SystemClock.uptimeMillis()
            for ((i, e) in books.withIndex()) {
                val read = withContext(AppDispatchers.io) {
                    runCatching { factsCache.put(e.path, e.size, BookFactsReader.read(e.file, charset, cacheDir)) }.isSuccess
                }
                if (!read) factsFailed[e.path] = e.size
                val now = SystemClock.uptimeMillis()
                if (i < books.lastIndex && now - shownAt < FACTS_PUBLISH_MS) continue
                shownAt = now
                applyFilterAndSort(resort = false)
            }
            if (folders.isEmpty()) return@launch
            for (f in folders) {
                folderSizes[f.path] = withContext(AppDispatchers.io) { FolderSize.total(f.file) { isActive } }
            }
            applyFilterAndSort()
        }
    }

    /**
     * Keeps a "Recent" row at the top of the browse list, pointing at the folder of
     * the last chapter that was read. Chapters are almost always just numbered, so
     * the folder name is what identifies the series — and tapping the row jumps
     * straight back to it.
     */
    private fun updateRecentHeader() {
        val show = mode == TAB_BROWSE && query.isBlank()
        val dir = if (show) RecentRow.folderOf(library.history()) else null
        adapter.header = dir?.let {
            BookAdapter.Header(
                title = getString(R.string.recent),
                subtitle = RecentRow.subtitle(it, roots) { id -> Remotes.share(id)?.name },
                iconRes = R.drawable.ic_history,
                onClick = { loadDirectory(Remotes.file(it)) }
            )
        }
        // Grid rows share the height the header and section headers leave.
        if (view.grid && view.rows > 0) updateCellSize()
    }

    private fun updateEmptyState() {
        val visible = shown.isEmpty()
        emptyView.visibility = if (visible) View.VISIBLE else View.GONE
        fab.visibility = if (!visible && prefs.listQuickFab) View.VISIBLE else View.GONE
        val error = listError?.takeIf { mode == TAB_BROWSE }
        retryButton.visibility = if (error != null) View.VISIBLE else View.GONE
        if (!visible) return

        val (icon, title, sub) = when {
            error != null -> Triple(R.drawable.ic_cloud_off, unreachableText(error), null)
            query.isNotBlank() -> Triple(R.drawable.ic_search, getString(R.string.empty_search), null)
            prefs.filter(tabName()).isActive && allEntries.isNotEmpty() ->
                Triple(R.drawable.ic_filter_list, getString(R.string.empty_filtered), null)
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

    /** What stopped the share browsed from listing, by its name. */
    private fun unreachableText(error: RemoteException): String {
        val name = currentDir?.let { StorageRoot.of(it.absolutePath, roots)?.name }.orEmpty()
        return getString(
            when (error.reason) {
                RemoteException.Reason.DENIED -> R.string.share_denied_named
                RemoteException.Reason.NO_SHARE -> R.string.share_missing_named
                else -> R.string.share_unreachable_named
            },
            name
        )
    }

    /** Lists the share browsed again, asking the network at once. */
    private fun retryListing() {
        val dir = currentDir ?: return
        Remotes.idOf(dir.absolutePath)?.let { Remotes.retry(it) }
        loadDirectory(dir)
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
            // A favorite folder that is gone: browse what is left of it.
            mode == TAB_FAVORITES && !Formats.isOpenable(entry.name) && !entry.file.exists() -> loadDirectory(entry.file)
            mode == TAB_HISTORY -> {
                // The reader decides where to start (ResumeMode.start).
                if (entry.file.exists()) openViewer(entry.file, -1) else toast(R.string.open_location_failed)
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

        if (mode == TAB_HISTORY) {
            if (entry.file.exists()) add(getString(R.string.menu_continue)) { openViewer(entry.file, library.pageFor(path)) }
            add(getString(R.string.menu_open_location)) { openLocation(entry.file) }
        }
        if (canPeek(entry)) add(getString(R.string.menu_peek)) { peekFromMenu(entry) }
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
            if (mode != TAB_HISTORY && !isBrowsedHere(entry)) add(getString(R.string.menu_open_location)) { openLocation(entry.file) }
            if (entry.isDir) {
                add(getString(R.string.menu_set_startup)) {
                    prefs.setStartupFolder(path)
                    toast(R.string.startup_folder_set)
                }
            }
            if (entry.file !is RemoteFile && entry.file.exists()) {
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

    /** True when [entry] is listed in the folder Browse shows: "Show in folder" would lead nowhere new. */
    private fun isBrowsedHere(entry: BookEntry): Boolean =
        mode == TAB_BROWSE && currentDir?.absolutePath?.let { it == entry.file.parent } == true

    // ------------------------------------------------------------------ peek

    /**
     * Spreading two fingers on a comic grows a grid of its pages out of it, on a folder
     * a grid of the covers inside ([PinchPeek]).
     */
    private fun setUpPeek() {
        pinch = PinchPeek(this, recycler, { applied.pinchPeek }, ::peekTarget, ::peekEntries) { entry, page ->
            if (entry.isDir) loadDirectory(entry.file) else openViewer(entry.file, page)
        }
        peek = pinch.overlay
    }

    /**
     * What [dir] lists when browsed: the browse filter applied, in the browse order, with
     * the facts found in its books ([withFacts]), so covers match the list's.
     */
    private suspend fun peekEntries(dir: File): List<BookEntry> {
        val entries = withContext(AppDispatchers.io) { buildDirectoryEntries(dir) }
        val sort = prefs.sort(Prefs.TAB_BROWSE)
        val filter = prefs.filter(Prefs.TAB_BROWSE)
        val hidden = library.hiddenCounts()
        val live = values(hidden)
        val passing = if (filter.isActive) entries.filter { filter.accepts(it, live, true) } else entries
        return passing.sortedWith(LibrarySort.comparator(sort, live.frozen(passing, sort.keys))).map { withFacts(it, hidden) }
    }

    /** The comic or folder under list point ([x], [y]) and the view its cover is drawn in, or null. */
    private fun peekTarget(x: Float, y: Float): Pair<BookEntry, View>? {
        val child = recycler.findChildViewUnder(x, y) ?: return null
        val entry = adapter.entryAt(recycler.getChildAdapterPosition(child)) ?: return null
        if (!canPeek(entry)) return null
        return entry to (child.findViewById<View>(R.id.box) ?: child)
    }

    private fun canPeek(entry: BookEntry) =
        if (entry.isDir) entry.file.isDirectory else Formats.isOpenable(entry.name) && entry.file.isFile

    /** Peeks into [entry] from the long-press menu: the grid grows out of its tile and stays open. */
    private fun peekFromMenu(entry: BookEntry) {
        val position = adapter.positionOf(entry.path)
        val tile = recycler.findViewHolderForAdapterPosition(position)?.itemView?.let { it.findViewById<View>(R.id.box) ?: it }
        pinch.open(entry, tile)
    }

    /** Only writes the setting: [applySettings] re-lays the list, as for any other change. */
    private fun toggleGrid() {
        prefs.setGrid(tabName(), !view.grid)
    }

    private fun updateFabIcon() {
        val grid = view.grid
        fab.setImageResource(if (grid) R.drawable.ic_view_list else R.drawable.ic_grid_view)
        fab.contentDescription = getString(if (grid) R.string.menu_list_view else R.string.menu_grid)
    }

    private fun applyLayoutManager() {
        val grid = view.grid
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
        return GridSizing.columns(view.columns, widthDp)
    }

    private fun keyboardVisible(): Boolean =
        ViewCompat.getRootWindowInsets(recycler)?.isVisible(WindowInsetsCompat.Type.ime()) == true

    /** Sizes grid cells: one column's width, and a height that fits the chosen rows on screen. */
    private fun updateCellSize() {
        val view = view
        if (!view.grid) {
            adapter.setCellSize(0, 0)
            return
        }
        val inner = recycler.width - recycler.paddingLeft - recycler.paddingRight
        val width = inner.coerceAtLeast(0) / columnCount()
        val rows = view.rows
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

    /** Browses the folder holding [file], highlighting it; [loadDirectory] climbs out of a folder that is gone. */
    private fun openLocation(file: File) {
        val dir = file.parentFile
        if (dir == null) {
            toast(R.string.open_location_failed)
            return
        }
        val found = dir.isDirectory
        loadDirectory(dir, highlight = file.absolutePath)
        if (found) toast(R.string.menu_open_location)
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

    /** Marks the books directly in [dir] read or unread once confirmed; either replaces their read marks. */
    private fun markFolder(dir: File, read: Boolean) {
        val message = android.text.TextUtils.expandTemplate(
            getText(if (read) R.string.confirm_mark_read_msg else R.string.confirm_mark_unread_msg),
            FolderPicker.describe(this, dir.absolutePath)
        )
        confirm(
            this,
            if (read) R.string.confirm_mark_read_title else R.string.confirm_mark_unread_title,
            message,
            if (read) R.string.menu_mark_all_read else R.string.menu_mark_all_unread
        ) {
            markFolderNow(dir, read)
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
        this.menu = menu
        searching = false
        for ((order, button) in prefs.toolbarButtons.visible.withIndex()) addToolbarButton(menu, button, order)
        return true
    }

    /** Adds [button] to the toolbar at [order] among the buttons. */
    private fun addToolbarButton(menu: Menu, button: ToolbarButton, order: Int) {
        val (id, icon) = when (button) {
            ToolbarButton.SORT -> R.id.menu_bar_sort to R.drawable.ic_sort
            ToolbarButton.FILTER -> R.id.menu_bar_filter to R.drawable.ic_filter_list
            ToolbarButton.FAVORITE -> R.id.menu_bar_favorite to R.drawable.ic_star_border
            ToolbarButton.SEARCH -> R.id.menu_search to R.drawable.ic_search
        }
        val item = menu.add(Menu.NONE, id, order, ToolbarButtonsDialog.label(this, button)).setIcon(icon)
        if (button != ToolbarButton.SEARCH) {
            item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            return
        }
        item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS or MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW)
        val searchView = SearchView(toolbar.context)
        item.actionView = searchView
        // The other buttons make room for the search field while it is open.
        item.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem): Boolean {
                searching = true
                updateToolbarButtons()
                return true
            }

            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                searching = false
                updateToolbarButtons()
                return true
            }
        })
        searchView.queryHint = getString(R.string.search_hint)
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(q: String?): Boolean {
                applyQuery(q)
                searchView.clearFocus() // collapse the keyboard once the search is committed
                return true
            }

            override fun onQueryTextChange(q: String?): Boolean = applyQuery(q)
        })
    }

    /** Shows the toolbar buttons that apply here, hidden while searching; the star tells whether the folder is a favorite. */
    private fun updateToolbarButtons() {
        val menu = menu ?: return
        menu.findItem(R.id.menu_bar_sort)?.isVisible = !searching
        menu.findItem(R.id.menu_bar_filter)?.isVisible = !searching
        menu.findItem(R.id.menu_bar_favorite)?.apply {
            val dir = currentDir
            val favorite = dir != null && library.isFavorite(dir.absolutePath)
            isVisible = !searching && mode == TAB_BROWSE && dir != null
            setIcon(if (favorite) R.drawable.ic_star else R.drawable.ic_star_border)
            title = getString(if (favorite) R.string.menu_remove_favorite else R.string.menu_add_favorite)
        }
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
        val grid = view.grid
        menu.findItem(R.id.menu_grid)?.title = getString(if (grid) R.string.menu_list_view else R.string.menu_grid)
        menu.findItem(R.id.menu_grid_size)?.isVisible = grid
        menu.findItem(R.id.menu_density)?.isVisible = !grid
        menu.findItem(R.id.menu_show_hidden)?.apply {
            isChecked = prefs.showHidden
            title = getString(if (prefs.showHidden) R.string.menu_hide_hidden else R.string.menu_show_hidden)
        }
        val buttons = prefs.toolbarButtons.visible
        // The menu keeps what the toolbar does not show.
        menu.findItem(R.id.menu_sort)?.isVisible = ToolbarButton.SORT !in buttons
        menu.findItem(R.id.menu_filter)?.isVisible = ToolbarButton.FILTER !in buttons
        menu.findItem(R.id.menu_add_favorite)?.isVisible = browsing && ToolbarButton.FAVORITE !in buttons
        menu.findItem(R.id.menu_add_favorite)?.title =
            getString(
                if (currentDir?.let { library.isFavorite(it.absolutePath) } == true)
                    R.string.menu_remove_favorite else R.string.menu_add_favorite
            )
        // Shares are read-only, and too slow to search for duplicates.
        val local = currentDir !is RemoteFile
        menu.findItem(R.id.menu_new_folder)?.isVisible = browsing && local
        menu.findItem(R.id.menu_continue)?.isVisible = browsing
        menu.findItem(R.id.menu_mark_all_read)?.isVisible = browsing
        menu.findItem(R.id.menu_mark_all_unread)?.isVisible = browsing
        menu.findItem(R.id.menu_dupes)?.isVisible = browsing && local
        menu.findItem(R.id.menu_large)?.isVisible = browsing && local
        menu.findItem(R.id.menu_search_deep)?.apply {
            isVisible = browsing
            isChecked = prefs.deepSearch
        }
        updateToolbarButtons()
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_grid -> toggleGrid()
            R.id.menu_sort, R.id.menu_bar_sort -> showSortDialog()
            R.id.menu_filter, R.id.menu_bar_filter -> TabDialogFragment.show(this, TabDialogFragment.Kind.FILTER, tabName())
            R.id.menu_grid_size -> TabViewDialogs.showGridSize(this, prefs, tabName())
            R.id.menu_density -> TabViewDialogs.showDensity(this, prefs, tabName())
            R.id.menu_detail -> TabViewDialogs.showDetail(this, prefs, tabName())
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
            R.id.menu_add_favorite, R.id.menu_bar_favorite -> toggleFavorite(currentDir?.absolutePath)
            R.id.menu_refresh -> refreshCurrent()
            R.id.menu_settings -> startActivity(Intent(this, SettingsActivity::class.java))
            R.id.menu_about -> startActivity(Intent(this, AboutActivity::class.java))
            R.id.menu_exit -> finish()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun showSortDialog() {
        TabDialogFragment.show(this, TabDialogFragment.Kind.SORT, tabName())
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

    /** Opens a crumb's folder; [loadDirectory] climbs out of one that is gone. */
    private fun openCrumb(crumb: Crumb) = loadDirectory(Remotes.file(crumb.path))

    /** The crumbs of [dir], with the forward crumbs browsed past it. */
    private fun showCrumbs(dir: File) {
        val (crumbs, current) = ForwardCrumbs.crumbs(dir.absolutePath, trail, roots)
        pathbar.show(crumbs, current = current)
    }

    /** Opens the location picker with the drive plugged in last highlighted, if it is still mounted. */
    private fun showPluggedDrive() {
        val path = pluggedDrive ?: return
        val drive = roots.firstOrNull { it.path == path }
        if (drive == null || LocationSheet.show(this, drive)) pluggedDrive = null
    }

    /** Opens the location picker from the app icon crumb; picking a root opens its top folder ([onLocationPicked]). */
    private fun showRootChooser() {
        LocationSheet.show(this, currentDir?.let { StorageRoot.of(it.absolutePath, roots) })
    }

    override val locationRoots: List<StorageRoot> get() = roots

    /** Opens [root] at its start folder ([Prefs.locationStart]); [loadDirectory] climbs out of one that is gone. */
    override fun onLocationPicked(root: StorageRoot) {
        Remotes.idOf(root.path)?.let { Remotes.retry(it) }
        loadDirectory(Remotes.file(prefs.locationStart(root.path)))
    }

    override fun onSharesChanged() {
        refreshRoots()
    }

    /** The open location picker; for tests. */
    internal val locations: LocationSheet? get() = LocationSheet.find(this)

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (peek.isShowing) {
            peek.close()
            return
        }
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
        private const val STATE_TRAIL = "trail"
        private const val INCOMING_DIR = "incoming"
        private const val MAX_INCOMING_BYTES = 2L * 1024 * 1024 * 1024
        private const val DEEP_SEARCH_DELAY_MS = 300L

        /** How long the startup folder's share has to answer before the device's start folder opens instead. */
        const val SHARE_WAIT_MS = 3_000L

        /** Then the share is asked again this much later, twice as late each time, this many times. */
        private const val SHARE_RETRY_MS = 10_000L
        private const val SHARE_RETRIES = 3
        private const val CLASSIFY_PUBLISH_MS = 250L
        private const val FACTS_PUBLISH_MS = 250L
        private const val DEEP_SEARCH_DEPTH = 8
        private const val DEEP_SEARCH_LIMIT = 500

        /** Room for the name and details below a cover that is still recognizable. */
        private const val MIN_CELL_DP = 120
    }
}
