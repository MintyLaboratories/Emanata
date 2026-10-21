package com.infinity.comicreader.ui

import android.content.ComponentCallbacks2
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.FileProvider
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.sidesheet.SideSheetDialog
import com.google.android.material.slider.Slider
import com.infinity.comicreader.R
import com.infinity.comicreader.data.BookFactsCache
import com.infinity.comicreader.data.Bookmark
import com.infinity.comicreader.data.FolderLayout
import com.infinity.comicreader.data.HistoryItem
import com.infinity.comicreader.data.Library
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.ReaderLayout
import com.infinity.comicreader.data.ReaderSettings
import com.infinity.comicreader.data.RemovalNotices
import com.infinity.comicreader.data.SettingsRepository
import com.infinity.comicreader.data.SpreadDisplay
import com.infinity.comicreader.data.StorageRoot
import com.infinity.comicreader.data.StorageRoots
import com.infinity.comicreader.data.TwoPage
import com.infinity.comicreader.data.Thumbs
import com.infinity.comicreader.reader.Adjustments
import com.infinity.comicreader.reader.ArchiveKind
import com.infinity.comicreader.reader.AutoScroll
import com.infinity.comicreader.reader.AutoScrollClock
import com.infinity.comicreader.reader.BookDirection
import com.infinity.comicreader.reader.BookFactsReader
import com.infinity.comicreader.reader.Chapter
import com.infinity.comicreader.reader.Chapters
import com.infinity.comicreader.reader.ComicInfo
import com.infinity.comicreader.reader.DirectionDetection
import com.infinity.comicreader.reader.Filters
import com.infinity.comicreader.reader.Fit
import com.infinity.comicreader.reader.Half
import com.infinity.comicreader.reader.NestedArchives
import com.infinity.comicreader.reader.PageSource
import com.infinity.comicreader.reader.PageSizes
import com.infinity.comicreader.reader.PageSources
import com.infinity.comicreader.reader.PreviewStore
import com.infinity.comicreader.reader.Spreads
import com.infinity.comicreader.reader.TapZones
import com.infinity.comicreader.reader.Target
import com.infinity.comicreader.util.AppDispatchers
import com.infinity.comicreader.util.FileUtils
import com.infinity.comicreader.util.Formats
import com.infinity.comicreader.util.NaturalOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.BitSet
import kotlin.math.abs

/**
 * The reader.
 *
 * Page layout is one of three modes — paged, vertical or long strip — and every
 * reader option (mode, fit, filter, spacing, direction, two-page, night filter,
 * keep-on, fullscreen) is read from and written back to [Prefs], so the choices
 * survive chapter changes and app restarts. Color adjustments are per book, in
 * [Library], falling back to the defaults in [Prefs]. Changes made elsewhere,
 * such as in the settings screen, apply live through [SettingsRepository].
 */
class ViewerActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var library: Library
    private lateinit var settings: SettingsRepository

    /** The global settings last applied; changes are diffed against it. */
    private lateinit var applied: ReaderSettings

    private lateinit var pager: ViewPager2
    private lateinit var vertical: RecyclerView
    private lateinit var toolbar: Toolbar
    private lateinit var bottomBar: LinearLayout
    private lateinit var seek: SeekBar
    private lateinit var pageText: TextView
    private lateinit var chapterText: TextView
    private lateinit var nightOverlay: View
    private lateinit var autoProgress: View
    private lateinit var progress: android.widget.ProgressBar
    private lateinit var endCard: View
    private lateinit var endSubtitle: TextView
    private lateinit var endNext: View
    private lateinit var endPrev: View
    private lateinit var btnPrevChapter: ImageButton
    private lateinit var btnNextChapter: ImageButton
    private lateinit var hint: TextView
    private lateinit var seekScrim: View
    private lateinit var seekPanel: RecyclerView
    private lateinit var brightnessPill: View
    private lateinit var brightnessText: TextView
    private lateinit var brightnessBar: SignedBar

    /** Counts "previous page" actions on the first page, toward the previous chapter. */
    private val repeatedBack = RepeatedBack()

    /** The seek bar's preview strip of the open book, kept between openings ([openSeekPanel]); null until first opened. */
    private var seekPreviews: SeekPreviewAdapter? = null

    /** Tells a tap-and-hold on the seek bar from a drag. */
    private lateinit var seekHold: SeekHold

    /** True from a hold opening the strip until that touch lifts: the seek bar sees no more of it. */
    private var seekHeld = false

    /** A touch's down on the seek bar's thumb, kept from the seek bar until the touch moves or lifts before the hold. */
    private var seekDown: MotionEvent? = null

    /** Back closes the preview strip while it is open. */
    private val seekBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = closeSeekPanel()
    }

    /** The page [repeatedBack] is counting on; another page starts the count over. */
    private var backPage = -1

    private var source: PageSource? = null
    private var adapter: PageAdapter? = null
    private var file: File? = null

    /**
     * The next chapter, opened ahead so a vertical or long-strip reader rolls
     * straight into it instead of stopping at the chapter's end. While it is
     * pending it is simply appended to the strip ([stripAheadFile] tracks the
     * chapter it belongs to, since [file] still points at the one being read).
     */
    private var stripAhead: PageSource? = null
    private var stripAheadFile: File? = null
    private var stripAheadJob: Job? = null

    /** True between spotting a chapter boundary and running the posted roll-on. */
    private var stripRollPending = false

    /** Sibling chapters (openable files in the same folder), natural-ordered. */
    private var chapters: List<File> = emptyList()

    /** Chapters inside the open book (its folders or inner archives), for the chapter drawer. */
    private var bookChapters: List<Chapter> = emptyList()

    /** Current page index (always a real page, never a spread index). */
    private var currentIndex = 0

    /** The item on screen in paged reading: a page, a pair, or one half of a page. */
    private var currentItem = 0

    /** How items map to pages on screen ([buildLayout]). */
    private var layout = PageLayout(spread = false)

    /** The open book's page names and sizes, null where unknown; what the spread engine reads. */
    private var pageNames: List<String?> = emptyList()
    private var pageSizes: Array<IntArray?> = emptyArray()

    /** What the spread engine found in the open book's pages; null until the book is open. */
    private var analysis: Spreads.Analysis? = null

    /** What auto-crop found on the open book's pages, for pairs to share; kept across adapters. */
    private var pageBounds = PageBounds()

    /** Reads the open book's missing page sizes in the background ([scanSizes]). */
    private var sizesJob: Job? = null

    /** Lays the book out again with the sizes pages left as they loaded ([learnSize]); [learnRound] numbers its rounds. */
    private var learnJob: Job? = null
    private var learnRound = 0

    /** True when the open book's two-page pairing is shifted by one page. */
    private var shifted = false

    /** Holds the screen in landscape while a wide page shows whole ("Rotate for spreads"). */
    private val spreadRotation = SpreadRotation()
    private var mode = MODE_PAGED
    private var twoPage = TwoPage.OFF

    /** Whether the adapter on screen pairs pages; [useSpread] says whether it should. */
    private var spread = false
    private var readingDir = "ltr"
    private var filter = Filters.NONE
    private var adjustments = Adjustments.NEUTRAL

    /** True when the open book has adjustments of its own rather than the defaults. */
    private var ownAdjustments = false

    /** True when [adjustments] changed since they were last stored. */
    private var adjustmentsDirty = false
    private var spacingDp = 0
    private var nightFilter = false
    private var keepOn = false
    private var barsVisible = true
    private var fit = "screen"
    private var autoCrop = false
    private var spreadDisplay = SpreadDisplay.MERGE

    /** What the open book's folder keeps of the layout; null when it follows the defaults. */
    private var folderLayout: FolderLayout? = null

    /** The direction the open book's contents suggest, when "Detect reading direction" looked. */
    private var detection: DirectionDetection.Detected? = null

    /** True while [readingDir] is [detection]'s rather than a setting's. */
    private var detectedActive = false

    /** The folder and detection last noted on open, so each folder's note shows once. */
    private var notedDetection: Pair<String?, DirectionDetection.Detected>? = null

    /** The storage roots when the book was opened, to tell when its drive is pulled out. */
    internal var bookRoots: List<StorageRoot> = emptyList()

    private val storageWatcher = StorageWatcher(this) { checkStorage() }

    /** The reader settings sheet's content while it is open. */
    private var sheet: View? = null

    /** The reader settings sheet while it is open. */
    private var sheetDialog: BottomSheetDialog? = null

    /** True while [syncSheet] sets the sheet's controls, so their listeners stay quiet. */
    private var syncingSheet = false

    private var openJob: Job? = null

    /** Gathers Book info's facts; a tap while it runs is ignored. */
    private var bookInfoJob: Job? = null

    /** The open book's ComicInfo once Book info has read it, with the source it came from. */
    private var bookComicInfo: Pair<PageSource, ComicInfo?>? = null

    /** Finishes the open book's direction detection after it shows ([detectAfterOpen]). */
    private var lateDetectionJob: Job? = null

    /** Counts the reader's own direction choices, so a late cover finding never undoes one. */
    private var directionChoices = 0

    /** The library's facts of books, which already hold what their contents tell of direction. */
    private val factsCache by lazy { BookFactsCache(this) }
    private var endDismissed = false
    private var markedRead = false

    /** Whether the open comic and its folder are favorites, read once per file for the menu. */
    private var favoriteBook = false
    private var favoriteFolder = false

    /** True while the reader stays on the last page it was opened at from the next chapter: not yet read. */
    private var landedAtEnd = false

    // auto-scroll
    private val handler = Handler(Looper.getMainLooper())
    private var autoScrolling = false
    private val autoClock = AutoScrollClock()
    private var autoLastFrame = 0L

    /** The delay the next [autoTick] was posted with. */
    private var autoDelay = FRAME_MS

    /** The item whose time [autoClock] counts in paged reading; another one shown starts it over. */
    private var autoPage = -1

    /** Set when a chapter change should carry the running auto-scroll over. */
    private var keepAutoScroll = false

    // reading statistics for the current session
    private var sessionStart = 0L
    private val seenPages = BitSet()

    private var cropSource: File? = null

    private val cropLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                result.data?.getStringExtra(CropActivity.EXTRA_RESULT)?.let {
                    toast(getString(R.string.crop_saved, it))
                }
            }
            deleteCropSource()
        }

    private val pagesLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val page = result.data?.getIntExtra(PagesActivity.EXTRA_RESULT_PAGE, -1) ?: -1
                if (page >= 0) goTo(page)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        library = Library(this)
        settings = SettingsRepository.get(this)
        setContentView(R.layout.activity_viewer)
        bindViews()
        applied = settings.reader.value
        loadPrefs()
        // A recreated reader (theme or language change) continues where it was, turned as it was.
        spreadRotation.restore(savedInstanceState?.getIntArray(STATE_ROTATION))
        savedInstanceState?.getString(STATE_PATH)?.let {
            intent.putExtra(EXTRA_PATH, it).putExtra(EXTRA_PAGE, savedInstanceState.getInt(STATE_PAGE))
        }

        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        storageWatcher.start()

        // Page-change callbacks are registered once: they outlive adapter swaps.
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageScrollStateChanged(state: Int) {
                if (state == ViewPager2.SCROLL_STATE_DRAGGING) stopAutoScroll()
            }

            override fun onPageSelected(position: Int) {
                if (mode != MODE_PAGED) return
                currentItem = position
                currentIndex = itemToPage(position)
                seek.progress = position
                onPageChanged()
            }
        })
        vertical.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                // Touching the list takes over from auto-scroll.
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) stopAutoScroll()
            }

            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (mode == MODE_PAGED) return
                val lm = rv.layoutManager as? LinearLayoutManager ?: return
                // The page the reader counts as current: the first one fully on screen.
                val pos = lm.findFirstCompletelyVisibleItemPosition().takeIf { it >= 0 }
                    ?: lm.findFirstVisibleItemPosition()
                // The chapter behind the reader was appended to this strip, so the
                // pages on screen already belong to it: rolling over is bookkeeping.
                // RecyclerView warns against trimming items from inside a scroll
                // callback, so the roll is posted; until it runs the index is left
                // alone rather than being read against a strip it no longer matches.
                if (stripAhead != null && pos >= chapterPages()) {
                    if (!stripRollPending) {
                        stripRollPending = true
                        rv.post {
                            stripRollPending = false
                            commitStripChapter()
                        }
                    }
                    return
                }
                if (stripRollPending) return
                if (pos >= 0 && itemToPage(pos) != currentIndex) {
                    // Never count past this chapter: crossing into the next one is
                    // the roll-on's job, not the indicator's.
                    currentIndex = itemToPage(pos)
                        .coerceIn(0, (chapterPages() - 1).coerceAtLeast(0))
                    seek.progress = pos
                    onPageChanged()
                }
                if (mode == MODE_LONGSTRIP) updateEndCard()
            }
        })

        seekHold = SeekHold(ViewConfiguration.get(this).scaledTouchSlop.toFloat())
        seek.setOnTouchListener { _, e -> onSeekTouch(e) }
        onBackPressedDispatcher.addCallback(this, seekBack)

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, value: Int, fromUser: Boolean) {
                if (fromUser) goToItem(value)
            }

            override fun onStartTrackingTouch(sb: SeekBar) {
                // Intentionally empty: the page is updated in onProgressChanged only.
            }

            override fun onStopTrackingTouch(sb: SeekBar) {
                // Intentionally empty: the page is updated in onProgressChanged only.
            }
        })

        // Neither paged nor strip mode can scroll past their last page, so a swipe in
        // that direction is caught here: with auto next chapter on it rolls on.
        (pager.getChildAt(0) as? RecyclerView)?.addOnItemTouchListener(overSwipeListener)
        vertical.addOnItemTouchListener(overSwipeListener)

        btnPrevChapter = findViewById(R.id.btn_prev_chapter)
        btnNextChapter = findViewById(R.id.btn_next_chapter)
        btnPrevChapter.setOnClickListener { openPrevChapter() }
        btnNextChapter.setOnClickListener { openNextChapter() }
        findViewById<View>(R.id.btn_prev_page).setOnClickListener { turn(-1) }
        findViewById<View>(R.id.btn_next_page).setOnClickListener { turn(1) }
        findViewById<View>(R.id.btn_pages).setOnClickListener { openPages() }

        endPrev.setOnClickListener { openPrevChapter() }
        endNext.setOnClickListener { openNextChapter() }
        findViewById<View>(R.id.end_close).setOnClickListener {
            endDismissed = true
            hideEndCard()
        }

        // Changes made while the reader is stopped (the settings screen sits on top
        // of it) arrive when it starts again.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                settings.reader.collect { applySettings(it) }
            }
        }

        open()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        stopAutoScroll()
        flushStats()
        saveHistory()
        openJob?.cancel()
        closeSource()
        endDismissed = false
        hideEndCard()
        open()
    }

    /**
     * Opens the current chapter again: at the current page, or while it is still
     * opening, at the page it was asked for.
     */
    private fun reopen() {
        if (source != null) intent.putExtra(EXTRA_PAGE, currentIndex)
        stopAutoScroll()
        flushStats()
        saveHistory()
        openJob?.cancel()
        closeSource()
        endDismissed = false
        hideEndCard()
        open()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putIntArray(STATE_ROTATION, spreadRotation.save())
        val f = file ?: return
        if (source == null) return
        outState.putString(STATE_PATH, f.absolutePath)
        outState.putInt(STATE_PAGE, currentIndex)
    }

    override fun onStart() {
        super.onStart()
        sessionStart = SystemClock.elapsedRealtime()
        // The drive may have gone while the reader was away.
        checkStorage()
    }

    /** Closes the book, saying why, once the drive it is read from is gone from [now]. */
    internal fun checkStorage(now: List<StorageRoot> = StorageRoots.list(this)) {
        val path = file?.absolutePath ?: return
        val gone = StorageRoot.removed(path, bookRoots, now) ?: return
        if (isFinishing) return
        saveHistory()
        openJob?.cancel()
        closeSource()
        RemovalNotices.told(gone)
        toast(getString(R.string.book_storage_removed, gone.name))
        finish()
    }

    override fun onStop() {
        super.onStop()
        persistAdjustments()
        stopAutoScroll()
        saveHistory()
        flushStats()
    }

    /**
     * Gives up the zoom's full-detail pages, the largest memory the reader holds, once the
     * UI hides, and before API 34 also once memory runs low. From API 34 only the UI-hidden
     * and background levels arrive, so in the foreground the detail budget alone bounds them.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        @Suppress("DEPRECATION")
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) adapter?.trimMemory()
    }

    override fun onDestroy() {
        super.onDestroy()
        storageWatcher.stop()
        handler.removeCallbacks(hideHint)
        hint.animate().cancel()
        handler.removeCallbacks(hideBrightness)
        brightnessPill.animate().cancel()
        handler.removeCallbacks(seekHoldUp)
        stopAutoScroll()
        openJob?.cancel()
        closeSource()
        deleteCropSource()
    }

    /** Adds this session's reading time and newly seen pages to the statistics. */
    private fun flushStats() {
        if (sessionStart == 0L) return
        val seconds = ((SystemClock.elapsedRealtime() - sessionStart) / 1000L)
            .coerceIn(0L, MAX_SESSION_SECONDS)
        val pages = seenPages.cardinality()
        sessionStart = SystemClock.elapsedRealtime()
        seenPages.clear()
        if (source != null) library.addReading(seconds, pages)
    }

    private fun deleteCropSource() {
        cropSource?.let { runCatching { it.delete() } }
        cropSource = null
    }

    private fun bindViews() {
        pager = findViewById(R.id.viewer_pager)
        vertical = findViewById(R.id.viewer_vertical)
        toolbar = findViewById(R.id.viewer_toolbar)
        bottomBar = findViewById(R.id.viewer_bottom)
        seek = findViewById(R.id.viewer_seek)
        pageText = findViewById(R.id.viewer_page_text)
        chapterText = findViewById(R.id.viewer_chapter_text)
        nightOverlay = findViewById(R.id.night_overlay)
        autoProgress = findViewById(R.id.auto_progress)
        progress = findViewById(R.id.viewer_progress)
        endCard = findViewById(R.id.viewer_end)
        endSubtitle = findViewById(R.id.end_subtitle)
        endNext = findViewById(R.id.end_next)
        endPrev = findViewById(R.id.end_prev)
        hint = findViewById(R.id.viewer_hint)
        seekScrim = findViewById(R.id.seek_scrim)
        seekPanel = findViewById(R.id.seek_panel)
        seekPanel.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        seekPanel.addOnLayoutChangeListener { _, l, _, r, _, oldL, _, oldR, _ ->
            placeSeekPanel()
            // A new width (a turn of the screen) centers the item on screen again.
            if (r - l != oldR - oldL) seekPanel.post { if (seekPanelShown) centerSeekPanel() }
        }
        seekScrim.setOnClickListener { closeSeekPanel() }
        brightnessPill = findViewById(R.id.brightness_pill)
        brightnessText = findViewById(R.id.brightness_text)
        brightnessBar = findViewById(R.id.brightness_bar)
        vertical.layoutManager = LinearLayoutManager(this)
        // Any relayout (rotation, the card's buttons, the bars) places the card and the hint again.
        findViewById<View>(R.id.viewer_root).addOnLayoutChangeListener { root, _, _, _, _, _, _, _, _ ->
            placeEndCard(root.height)
            placeHint(root.height)
            placeSeekPanel()
        }
    }

    /** Puts the preview strip just above the bottom bar. */
    private fun placeSeekPanel() {
        if (seekPanel.visibility != View.VISIBLE) return
        val gap = resources.getDimensionPixelSize(R.dimen.space_2)
        val limit = if (bottomBar.visibility == View.VISIBLE) bottomBar.top else (seekPanel.parent as View).height
        seekPanel.translationY = (limit - gap - seekPanel.bottom).toFloat()
    }

    /** Puts the end card at [EndCard.top] in a root [rootHeight] tall, above the bottom bar while it shows. */
    private fun placeEndCard(rootHeight: Int) {
        if (endCard.visibility != View.VISIBLE) return
        val margin = resources.getDimensionPixelSize(R.dimen.end_card_margin)
        val limit = if (bottomBar.visibility == View.VISIBLE) bottomBar.top else rootHeight
        endCard.translationY = (EndCard.top(rootHeight, endCard.height, margin, limit) - endCard.top).toFloat()
    }

    /** Puts the hint just above the bottom bar while it shows, else above the bottom of a root [rootHeight] tall. */
    private fun placeHint(rootHeight: Int) {
        if (hint.visibility != View.VISIBLE) return
        val gap = resources.getDimensionPixelSize(R.dimen.reader_hint_gap)
        val limit = if (bottomBar.visibility == View.VISIBLE) bottomBar.top else rootHeight
        hint.translationY = (limit - gap - hint.bottom).toFloat()
    }

    private fun loadPrefs() {
        showLayout(ReaderLayout.of(prefs))
        filter = prefs.filter
        keepOn = prefs.keepScreenOn
        nightFilter = prefs.nightFilter
        applyKeepScreenOn()
        applyNight()
        if (prefs.fullscreen) applyFullscreen(true)
    }

    /**
     * Re-applies the global settings that changed since [applied]. The reader's own
     * writes arrive here as well and already match its state, so they do nothing.
     * Only changed keys are applied, so a folder's remembered layout is not replaced
     * by an unrelated change.
     */
    private fun applySettings(s: ReaderSettings) {
        val old = applied
        if (old == s) return
        applied = s
        var rebuild = false
        // Whether anything the menu or the end card reflects changed. Adjustments and
        // auto-scroll speed change neither.
        var shown = false
        if (s.filter != old.filter && s.filter != filter) {
            filter = s.filter
            adapter?.filter = filter
            shown = true
        }
        if (s.adjustments != old.adjustments && !ownAdjustments) showAdjustments(s.adjustments)

        if (s.nightFilter != old.nightFilter && s.nightFilter != nightFilter) {
            nightFilter = s.nightFilter
            applyNight()
            shown = true
        }
        if (s.keepScreenOn != old.keepScreenOn && s.keepScreenOn != keepOn) {
            keepOn = s.keepScreenOn
            // A running auto-scroll keeps the screen on by itself.
            if (!autoScrolling) applyKeepScreenOn()
            shown = true
        }
        if (s.rotateSpreads != old.rotateSpreads) {
            updateSpreadRotation()
            shown = true
        }
        if (s.fullscreen != old.fullscreen) {
            applyFullscreen(s.fullscreen)
            shown = true
        }

        // Built into the page adapter.
        if (s.doubleTapZoom != old.doubleTapZoom || s.brightnessGesture != old.brightnessGesture || s.fling != old.fling ||
            s.linearLight != old.linearLight ||
            s.preload != old.preload
        ) {
            rebuild = true
        }
        if (s.pageMode != old.pageMode || s.readingDir != old.readingDir || s.fit != old.fit ||
            s.twoPage != old.twoPage || s.autoCrop != old.autoCrop || s.pageSpacing != old.pageSpacing ||
            s.perSeries != old.perSeries || s.detectDirection != old.detectDirection || s.spreads != old.spreads
        ) {
            if (s.readingDir != old.readingDir) directionChoices++
            if (reloadLayout()) rebuild = true
            shown = true
        }
        if (s.charset != old.charset && (source != null || openJob?.isActive == true)) {
            // Entry names, and with them the page order, depend on the charset.
            reopen()
            invalidateOptionsMenu()
            return
        }
        if (s.coverAlone != old.coverAlone && spread) rebuild = true
        if (rebuild) attach()
        if (rebuild || s.autoNext != old.autoNext) {
            if (s.autoNext) prepareStripAhead() else dropStripAhead()
            shown = true
        }
        if (shown) {
            updateEndCard()
            invalidateOptionsMenu()
            syncSheet()
        }
    }

    /**
     * Resolves the layout again: the folder's over the global defaults, with the detected
     * direction on top while the folder has none. Returns true when the adapter must be
     * rebuilt; fit and page gap alone are applied in place.
     */
    private fun reloadLayout(): Boolean {
        val old = currentLayout()
        resolveLayout()
        if (mode != old.mode) {
            stopAutoScroll()
            // Only the strip modes lay the next chapter in behind this one.
            if (mode == MODE_PAGED) dropStripAhead()
        }
        if (fit != old.fit) adapter?.fitMode = effectiveFit()
        if (spacingDp != old.spacing) adapter?.spacingPx = spacingPx()
        return mode != old.mode || readingDir != old.dir || useSpread() != spread || autoCrop != old.autoCrop ||
            spreadDisplay != old.spreads
    }

    private fun resolveLayout() {
        folderLayout = layoutFolder()?.let { library.folderLayout(it) }
        val defaults = ReaderLayout.of(prefs)
        showLayout(folderLayout?.over(defaults) ?: defaults)
        applyDetection()
    }

    /** The layout the reader shows, its direction perhaps the detected one. */
    private fun currentLayout() = ReaderLayout(mode, readingDir, fit, twoPage, autoCrop, spacingDp, spreadDisplay)

    private fun showLayout(l: ReaderLayout) {
        mode = l.mode.takeIf { it in ReaderLayout.MODES } ?: MODE_PAGED
        readingDir = if (l.dir == "rtl") "rtl" else "ltr"
        fit = l.fit
        twoPage = l.twoPage
        autoCrop = l.autoCrop
        spacingDp = l.spacing
        spreadDisplay = l.spreads
    }

    /** The folder whose layout the open book follows; none with "Per-folder layout" off or for another app's document. */
    private fun layoutFolder(): String? = if (prefs.perSeries) favoriteFile()?.parent else null

    /** True while the folder decides the direction, so its books do not detect one. */
    private fun folderDirection() = folderLayout?.dirChosen == true

    /** Reads in the detected direction unless the folder has its own or detection is off. */
    private fun applyDetection() {
        val found = detection
        detectedActive = found != null && !folderDirection() && prefs.detectDirection
        if (detectedActive && found != null) readingDir = found.direction.key
    }

    /**
     * Says on open which direction was detected and why, when it is not the default; once
     * per folder. Only paged reading turns left or right, so the other modes say nothing.
     */
    private fun noteDetection() {
        val found = detection ?: return
        if (!detectedActive || found.direction.key == prefs.readingDir || mode != MODE_PAGED) return
        val noted = file?.parent to found
        if (notedDetection == noted) return
        notedDetection = noted
        showHint(getString(R.string.direction_detected, directionName(found.direction.key), detectedFrom(found)), DETECTION_NOTE_MS)
    }

    private fun directionName(dir: String) = getString(if (dir == "rtl") R.string.direction_rtl else R.string.direction_ltr)

    /** What [found] was read from: "ComicInfo", "the cover", or the publisher's name. */
    private fun detectedFrom(found: DirectionDetection.Detected): String = when (found.source) {
        DirectionDetection.Source.COMIC_INFO -> getString(R.string.direction_from_comic_info)
        DirectionDetection.Source.COVER -> getString(R.string.direction_from_cover)
        DirectionDetection.Source.PUBLISHER -> getString(R.string.direction_from_publisher, found.detail.orEmpty())
    }

    /** The direction on screen and where it comes from: the folder, the book's contents, or the default. */
    private fun directionInfo(): String {
        val found = detection
        val name = directionName(readingDir)
        return when {
            folderDirection() -> getString(R.string.direction_info, name, getString(R.string.direction_source_folder))
            detectedActive && found != null -> getString(R.string.direction_detected, name, detectedFrom(found))
            else -> getString(R.string.direction_info, name, getString(R.string.direction_source_default))
        }
    }

    /**
     * Stores a layout change: with the folder's layout while it is frozen, else as the
     * defaults. Directions are stored apart, by [setReadingDir].
     */
    private fun persistLayout(dirChosen: Boolean = folderDirection()) {
        val folder = layoutFolder()
        if (folderScope() && folder != null) {
            setFolder(folder, FolderLayout.Frozen(currentLayout(), dirChosen))
        } else {
            prefs.setLayoutDefaults(currentLayout())
        }
    }

    private fun setFolder(folder: String, layout: FolderLayout?) {
        folderLayout = layout
        library.setFolderLayout(folder, layout)
    }

    /** True while the folder keeps its whole layout ("Apply to: This folder"). */
    private fun folderScope() = folderLayout is FolderLayout.Frozen

    /**
     * "Apply to": [folder] freezes the layout on screen for the book's folder; all books
     * returns it to the defaults, though the folder keeps a direction it was given.
     */
    private fun setFolderScope(folder: Boolean) {
        val path = layoutFolder() ?: return
        val now = folderLayout
        if (folder == now is FolderLayout.Frozen) return
        setFolder(path, if (now is FolderLayout.Frozen) now.thawed() else FolderLayout.Frozen(currentLayout(), folderDirection()))
        val oldMode = mode
        if (reloadLayout()) attach()
        if (mode != oldMode) prepareStripAhead()
        invalidateOptionsMenu()
    }

    // ------------------------------------------------------------------ loading

    private fun open() {
        val path = intent.getStringExtra(EXTRA_PATH)
        if (path == null) {
            finish()
            return
        }
        val target = File(path)
        persistAdjustments()
        file = target
        bookRoots = StorageRoots.list(this)
        refreshFavorites()
        loadAdjustments()
        toolbar.title = chapterLabel(target)
        progress.visibility = View.VISIBLE
        markedRead = false
        landedAtEnd = false
        bookChapters = emptyList()
        repeatedBack.reset()
        hideHintNow()
        backPage = -1
        detection = null
        resolveLayout()

        lateDetectionJob?.cancel()
        openJob = lifecycleScope.launch {
            // A folder with its own direction keeps it: nothing to detect.
            val detect = prefs.detectDirection && !folderDirection()
            var known: KnownPages? = null
            val (opened, siblings, contents) = withContext(AppDispatchers.io) {
                val job = coroutineContext.job
                val s = runCatching { PageSources.open(target, prefs.charset, cacheDir, canceled = { !job.isActive }) }
                try {
                    ensureActive()
                    val siblings = siblingChapters(target)
                    val found = s.getOrNull()?.takeIf { detect }?.let { src -> runCatching { detectBeforeOpen(target, src, siblings) }.getOrNull() }
                    known = s.getOrNull()?.let { knownSizes(target, it) }
                    ensureActive()
                    Triple(s, siblings, found)
                } catch (e: CancellationException) {
                    // A newer open() replaced this one: do not leak the file handle.
                    runCatching { s.getOrNull()?.close() }
                    throw e
                }
            }
            progress.visibility = View.GONE
            chapters = siblings
            val src = opened.getOrNull()
            if (src == null) {
                val storage = opened.exceptionOrNull() is NestedArchives.StorageException
                toast(if (storage) R.string.error_open_storage else R.string.error_open)
                finish()
                return@launch
            }
            if (src.pageCount <= 0) {
                runCatching { src.close() }
                toast(R.string.error_open)
                finish()
                return@launch
            }
            detection = contents?.detected
            applyDetection()
            source = src
            known?.let { showPages(it) }
            setup(src)
            noteDetection()
            if (contents != null) detectLater(target, src, contents)
        }
    }

    /** Starts [detectAfterOpen] when [contents] left a costly ComicInfo or an unread cover. */
    private fun detectLater(book: File, src: PageSource, contents: BookDirection.Contents) {
        if (!BookDirection.hasCover(book)) return
        val coverPending = !Thumbs.frontKnown(book) && !DirectionDetection.outranksCover(contents.detected)
        if (!contents.complete || coverPending) detectAfterOpen(book, src, contents)
    }

    /**
     * What decides the direction before the first page shows, without decoding a page:
     * the library's facts of the book when it has them, else its cheap contents, then the
     * cover's front half when this session already found it. Blocking.
     */
    private fun detectBeforeOpen(target: File, src: PageSource, siblings: List<File>): BookDirection.Contents {
        if (!BookDirection.hasCover(target)) return BookDirection.fromContents(target, src, siblings)
        factsCache.load()
        val contents = BookDirection.fromContents(target, src, siblings, factsCache.get(target.absolutePath, target.length()))
        return contents.copy(detected = DirectionDetection.withCover(contents.detected, Thumbs.knownFront(target)))
    }

    /**
     * Once the book shows, finishes what [detectBeforeOpen] left: a ComicInfo too costly to
     * read first (read with the library's facts, through its own file handle, and kept for
     * them), then a wraparound cover's front half. The book turns to the direction found
     * while the reader is still where it opened and has not chosen one; otherwise the
     * finding waits for the next open.
     */
    private fun detectAfterOpen(book: File, src: PageSource, before: BookDirection.Contents) {
        val openedAt = currentIndex
        val choices = directionChoices
        val charset = prefs.charset
        lateDetectionJob = lifecycleScope.launch {
            val found = withContext(AppDispatchers.io) {
                var contents = before.detected
                if (!before.complete) {
                    val facts = runCatching { BookFactsReader.read(book, charset, cacheDir) }.getOrNull()
                    ensureActive()
                    if (facts != null) {
                        factsCache.put(book.absolutePath, book.length(), facts)
                        contents = facts.detected
                    }
                }
                if (DirectionDetection.outranksCover(contents)) return@withContext contents
                val front = if (Thumbs.frontKnown(book)) {
                    Thumbs.knownFront(book)
                } else {
                    runCatching { BookDirection.coverFront(src) }.getOrNull().also {
                        ensureActive()
                        Thumbs.rememberFront(book, it)
                    }
                }
                DirectionDetection.withCover(contents, front)
            }
            if (source !== src || file != book || choices != directionChoices || currentIndex != openedAt) return@launch
            if (!prefs.detectDirection || folderDirection() || found == detection) return@launch
            detection = found
            showDetection()
            noteDetection()
        }
    }

    private fun closeSource() {
        lateDetectionJob?.cancel()
        lateDetectionJob = null
        sizesJob?.cancel()
        sizesJob = null
        learnJob?.cancel()
        learnJob = null
        learnRound++
        bookInfoJob?.cancel()
        bookInfoJob = null
        bookComicInfo = null
        dropSeekPreviews()
        adapter?.release()
        adapter = null
        stripAheadJob?.cancel()
        stripAheadJob = null
        runCatching { stripAhead?.close() }
        stripAhead = null
        stripAheadFile = null
        runCatching { source?.close() }
        source = null
        pager.adapter = null
        vertical.adapter = null
    }

    private fun siblingChapters(f: File): List<File> {
        val dir = f.parentFile ?: return listOf(f)
        val files = dir.listFiles() ?: return listOf(f)
        val openable = files
            .filter { it.isFile && !it.name.startsWith(".") && Formats.isOpenable(it.name) }
            .sortedWith(compareBy(NaturalOrder) { it.name })
        return if (openable.any { it.absolutePath == f.absolutePath }) openable else openable + f
    }

    private fun setup(src: PageSource) {
        val requested = intent.getIntExtra(EXTRA_PAGE, -1)
        // Used once: a later reopen sets it again, and anything else starts from history.
        intent.removeExtra(EXTRA_PAGE)
        val path = file!!.absolutePath
        val saved = library.pageFor(path)
        val start = prefs.resumeMode.start(requested, saved, src.pageCount, finished = library.isRead(path))
        currentIndex = start.page.coerceIn(0, src.pageCount - 1)
        // Come back to from the next chapter: the end card waits for a move past the end,
        // and the chapter counts as read only once its last page is reached again.
        if (requested == LAST_PAGE) {
            endDismissed = true
            landedAtEnd = true
        }
        bookChapters = chaptersOf(src)
        seenPages.clear()
        attach()
        scanSizes(file!!, src)
        prepareStripAhead()

        // Carrying an auto-scroll run into the next chapter must survive the reload.
        if (keepAutoScroll) {
            keepAutoScroll = false
            startAutoScroll()
        }

        if (start.ask) askStartPage(saved, src.pageCount)
    }

    /**
     * The resume / first-page choice offered when a chapter has saved progress. With
     * "Don't ask again" ticked the answer becomes the "Resume where I left off" setting.
     */
    private fun askStartPage(saved: Int, count: Int) {
        val percent = ((saved + 1) * 100f / count).toInt().coerceIn(1, 100)
        val extra = layoutInflater.inflate(R.layout.dialog_resume, null)
        val dontAsk = extra.findViewById<CheckBox>(R.id.resume_dont_ask)
        fun answer(resume: Boolean) {
            prefs.resumeMode = prefs.resumeMode.afterAnswer(resume, dontAsk.isChecked)
            goTo(if (resume) saved else 0)
        }
        AlertDialog.Builder(this)
            .setTitle(chapterLabel(file!!))
            .setMessage(getString(R.string.resume_progress, saved + 1, count, percent))
            .setView(extra)
            .setPositiveButton(R.string.resume_page) { _, _ -> answer(resume = true) }
            .setNegativeButton(R.string.first_page) { _, _ -> answer(resume = false) }
            .show()
    }

    // ------------------------------------------------------------------ spreads

    /** A book's page [names], the [sizes] known of them (null where unknown) and what the spread engine finds in both. */
    private class KnownPages(val names: List<String?>, val sizes: Array<IntArray?>, val analysis: Spreads.Analysis)

    /** The page names of [src], the sizes stored for them and their [Spreads.classify]. Blocking. */
    private fun knownSizes(book: File, src: PageSource): KnownPages {
        val names = List(src.pageCount) { runCatching { src.pageName(it) }.getOrNull() }
        val sizes = PageSizes(PageSizes.dirIn(cacheDir)).read(PreviewStore.bookId(book), names) ?: arrayOfNulls(names.size)
        return KnownPages(names, sizes, classify(names, sizes))
    }

    /** What the spread engine finds in page [names] of [sizes]. Blocking on long books. */
    private fun classify(names: List<String?>, sizes: Array<IntArray?>): Spreads.Analysis =
        Spreads.classify(names.map { it.orEmpty() }, sizes.toList())

    /** Takes the [known] pages of the open book as what the spread engine reads. */
    private fun showPages(known: KnownPages) {
        pageBounds = PageBounds()
        pageNames = known.names
        pageSizes = known.sizes
        shifted = favoriteFile()?.let { library.isShifted(it.absolutePath) } == true
        analysis = known.analysis
    }

    /**
     * Reads the sizes [book]'s pages lack from their headers, in the background and from
     * the page on screen outward, stores them, and lays the book out again when they
     * change its spreads. Skipped where that would unpack the whole book
     * ([PageSource.cheapSizes]).
     */
    private fun scanSizes(book: File, src: PageSource) {
        sizesJob?.cancel()
        if (!src.cheapSizes || pageSizes.none { it == null }) return
        val names = pageNames
        val sizes = pageSizes.copyOf()
        val from = currentIndex
        sizesJob = lifecycleScope.launch {
            val found = withContext(AppDispatchers.io) {
                val job = coroutineContext.job
                if (!PageSizes.scan(src, sizes, from) { !job.isActive }) return@withContext null
                PageSizes(PageSizes.dirIn(cacheDir)).write(PreviewStore.bookId(book), names, sizes)
                classify(names, sizes)
            }
            if (found == null || source !== src || file != book) return@launch
            pageSizes = sizes
            analysis = found
            // The same layout may still show a page now known to be wide.
            if (buildLayout().sameAs(layout)) updateSpreadRotation() else attach()
        }
    }

    /**
     * Takes the size page [page] showed when it loaded, in a book whose sizes are not
     * scanned ([PageSource.cheapSizes]): after [LEARN_DELAY_MS], all sizes learned so far
     * are stored and the book is laid out again when they change its spreads.
     */
    private fun learnSize(page: Int) {
        val src = source ?: return
        val book = file ?: return
        if (mode != MODE_PAGED || src.cheapSizes || page !in pageSizes.indices || pageSizes[page] != null) return
        pageSizes[page] = src.loadedSize(page) ?: return
        if (learnJob != null) return
        learnJob = lifecycleScope.launch {
            delay(LEARN_DELAY_MS)
            // Sizes learned from here on start a round of their own.
            learnJob = null
            val round = ++learnRound
            val names = pageNames
            val sizes = pageSizes.copyOf()
            val found = withContext(AppDispatchers.io) {
                PageSizes(PageSizes.dirIn(cacheDir)).write(PreviewStore.bookId(book), names, sizes)
                classify(names, sizes)
            }
            if (round != learnRound || source !== src || file != book) return@launch
            analysis = found
            if (buildLayout().sameAs(layout)) updateSpreadRotation() else attach()
        }
    }

    /**
     * How items map to pages: in paged reading, the spread engine's pairs in two-page
     * mode, or each page with spreads in one file split in halves ([SpreadDisplay.SPLIT]);
     * otherwise a page an item.
     */
    private fun buildLayout(): PageLayout {
        val rtl = readingDir == "rtl"
        val a = analysis
        if (mode != MODE_PAGED || a == null) return PageLayout(spread, rtl)
        if (spread) return PageLayout.of(Spreads.pair(a, Spreads.Options(prefs.coverAlone, shifted)), spread = true, rtl = rtl)
        if (spreadDisplay == SpreadDisplay.SPLIT && (0 until a.size).any { a.splittable(it) }) {
            return PageLayout.split(a.size, rtl) { a.splittable(it) }
        }
        return PageLayout(false, rtl)
    }

    /** Shifts the open book's two-page pairing by one page, or back, and keeps the choice. */
    private fun setShifted(value: Boolean) {
        if (shifted == value) return
        shifted = value
        favoriteFile()?.let { library.setShifted(it.absolutePath, value) }
        if (spread) attach()
    }

    /** The wide page on screen, shown whole and alone in paged reading; -1 for anything else. */
    private fun widePageShown(): Int {
        val a = analysis ?: return -1
        val count = source?.pageCount ?: return -1
        if (mode != MODE_PAGED) return -1
        val part = layout.parts(shownItem(), count).filterNotNull().singleOrNull() ?: return -1
        return if (part.half == Half.WHOLE && a.wide(part.page)) part.page else -1
    }

    /** Turns the screen for the wide page on screen, or back, as [spreadRotation] says. */
    private fun updateSpreadRotation() {
        spreadRotation.show(widePageShown(), prefs.rotateSpreads, landscape())?.let { requestedOrientation = it }
    }

    /** True while items show numbers of their own: a page split in halves takes two. */
    private fun itemNumbers() = mode == MODE_PAGED && !spread && layout.grouped

    /**
     * Builds a fresh adapter for the current layout. A [RecyclerView.Adapter] has
     * a fixed item view type, so changing the mode, spread or reading direction
     * requires a new instance. These are only emitted from the UI thread.
     */
    private fun attach() {
        val src = source ?: return
        pager.adapter = null
        vertical.adapter = null
        // The new adapter reads the same chapters: they must stay open.
        adapter?.release(closeSources = false)

        spread = useSpread()
        layout = buildLayout()
        val a = PageAdapter(
            source = src,
            mode = adapterMode(),
            onTap = { x, y -> onTap(x, y) },
            doubleTapZoom = prefs.doubleTapZoom,
            twoPage = spread,
            rtl = readingDir == "rtl",
            verticalDrag = if (brightnessGestureEnabled()) brightnessDrag else null,
            autoCrop = autoCrop,
            linear = prefs.linearLight,
            preload = prefs.preload,
            fling = prefs.fling,
            onLongPress = { page -> showPageActions(if (page >= 0) page else currentIndex) },
            pageLayout = layout,
            bounds = pageBounds,
            onLoaded = { learnSize(it) }
        )
        a.fitMode = effectiveFit()
        a.filter = filter
        a.adjustments = adjustments
        a.spacingPx = spacingPx()
        adapter = a

        val item = pageToItem(currentIndex)
        currentItem = item
        // The roll-on chapter travels with the reader: adapters are rebuilt for
        // every layout change, so the already-opened source is laid in again.
        if (mode != MODE_PAGED) stripAhead?.let { a.append(it) }
        if (mode == MODE_PAGED) {
            pager.layoutDirection =
                if (readingDir == "rtl") View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
            vertical.visibility = View.GONE
            pager.visibility = View.VISIBLE
            pager.adapter = a
            pager.setCurrentItem(item, false)
        } else {
            pager.visibility = View.GONE
            vertical.visibility = View.VISIBLE
            vertical.adapter = a
            vertical.scrollToPosition(item)
        }
        // The slider stays scoped to this chapter even while the strip holds more:
        // seeking past its end would roll on, which the slider should not do by itself.
        seek.max = (layout.itemCount(src.pageCount) - 1).coerceAtLeast(0)
        seek.progress = item
        // An open preview strip follows the new layout.
        if (seekPanel.visibility == View.VISIBLE) seekPreviews?.show(seekCells(src), item)
        onPageChanged()
    }

    // ------------------------------------------------------- seamless chapter roll

    /** Pages in the chapter being read (the strip may already hold the next one too). */
    private fun chapterPages() = source?.pageCount ?: 0

    /**
     * Opens the chapter after this one so vertical and long-strip reading can be
     * rolled straight into it. Nothing is on screen yet: once its pages are open
     * they are laid in behind the current chapter's, and scrolling simply reaches
     * them, exactly like scrolling between two pages of one archive.
     */
    private fun prepareStripAhead() {
        if (mode == MODE_PAGED || !prefs.autoNext) {
            stripAheadJob?.cancel()
            stripAheadJob = null
            return
        }
        // Already open, or opening: the next chapter only changes with the file,
        // and a new file closes both first.
        if (stripAhead != null || stripAheadJob?.isActive == true) return
        val next = nextChapter() ?: return
        stripAheadJob = lifecycleScope.launch {
            val (src, ok) = withContext(AppDispatchers.io) {
                // Nested archives are not copied ahead: a book needing new copies opens when reached.
                val job = coroutineContext.job
                val s = runCatching {
                    PageSources.open(next, prefs.charset, cacheDir, canceled = { !job.isActive }, copyNested = false)
                }.getOrNull()
                try {
                    ensureActive()
                } catch (e: CancellationException) {
                    // A newer open replaced this one: do not leak the file handle.
                    runCatching { s?.close() }
                    throw e
                }
                s to (s != null && s.pageCount > 0)
            }
            if (!ok || src == null) {
                // Unreadable: the reader just falls back to stopping at the end.
                runCatching { src?.close() }
                return@launch
            }
            stripAhead = src
            stripAheadFile = next
            // The mode or the setting may have changed while the archive was opening.
            if (mode != MODE_PAGED && prefs.autoNext) adapter?.append(src)
        }
    }

    /**
     * Switches the reader over to the chapter that was appended to the strip. The
     * strip already shows its pages, so nothing is reloaded or re-decoded: the file,
     * source and page index simply move on, the pages already scrolled past are
     * trimmed away, and the chapter after that is opened for the next roll-on.
     */
    private fun commitStripChapter() {
        val a = adapter ?: return
        val finished = source ?: return
        val next = stripAhead ?: return
        val nextFile = stripAheadFile ?: return
        val boundary = finished.pageCount

        // The chapter just read is done: record it before moving on.
        currentIndex = (boundary - 1).coerceAtLeast(0)
        onPageChanged()
        saveHistory()
        flushStats()

        // Re-anchor on the boundary item at its current offset: it is the first page
        // of the new chapter that survives the trim, and its (usually negative) top
        // keeps the viewport exactly where it was.
        val lm = vertical.layoutManager as? LinearLayoutManager
        val first = lm?.findFirstVisibleItemPosition() ?: 0
        val anchor = first.coerceAtLeast(boundary)
        val top = lm?.findViewByPosition(anchor)?.top ?: 0

        dropSeekPreviews()
        source = next
        bookChapters = chaptersOf(next)
        persistAdjustments()
        file = nextFile
        refreshFavorites()
        loadAdjustments()
        // Reopening or recreating the reader must load the chapter now shown.
        intent.putExtra(EXTRA_PATH, nextFile.absolutePath)
        // Its spreads are read on the next paged layout.
        sizesJob?.cancel()
        analysis = null
        lifecycleScope.launch {
            val known = withContext(AppDispatchers.io) { knownSizes(nextFile, next) }
            if (source !== next) return@launch
            showPages(known)
            scanSizes(nextFile, next)
        }
        currentIndex = (first - boundary).coerceAtLeast(0)
        stripAhead = null
        stripAheadFile = null
        seenPages.clear()
        markedRead = false
        landedAtEnd = false
        toolbar.title = chapterLabel(nextFile)

        a.dropTo(boundary)
        lm?.scrollToPositionWithOffset((anchor - boundary).coerceAtLeast(0), top)

        seek.max = pageToItem((next.pageCount - 1).coerceAtLeast(0))
        seek.progress = pageToItem(currentIndex)
        onPageChanged()
        prepareStripAhead()
    }

    /**
     * Undoes the roll-on: closes the chapter opened ahead and trims it back out of
     * the strip, so turning "auto next chapter" off mid-chapter still ends it with
     * the usual prompt.
     */
    private fun dropStripAhead() {
        stripAheadJob?.cancel()
        stripAheadJob = null
        val ahead = stripAhead ?: return
        stripAhead = null
        stripAheadFile = null
        // The adapter owns its segments: trimming the chapter out of the strip
        // lets it close the source once nothing is decoding from it anymore.
        val a = adapter
        if (a != null) a.dropAfter(chapterPages()) else runCatching { ahead.close() }
        // Restores the end card, which the pending chapter may have suppressed.
        updateEndCard()
    }

    private fun adapterMode(): PageAdapter.Mode = when (mode) {
        MODE_VERTICAL -> PageAdapter.Mode.VERTICAL
        MODE_LONGSTRIP -> PageAdapter.Mode.LONG_STRIP
        else -> PageAdapter.Mode.PAGED
    }

    /** Two-page reading for the orientation pages are laid out for: while turned for a wide page, the one that comes back. */
    private fun useSpread() = mode == MODE_PAGED && twoPage.spread(spreadRotation.layoutLandscape(landscape()))

    private fun landscape() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    /** "Landscape only" pairs pages after a turn to landscape and parts them after a turn back. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (source != null && useSpread() != spread) {
            attach()
            syncSheet()
        }
        sheetDialog?.behavior?.peekHeight = sheetPeek()
    }

    private fun pageToItem(page: Int) = layout.itemOf(page)

    private fun itemToPage(item: Int) = layout.firstPage(item)

    /** The item on screen: tracked in paged reading, where a page can span two; the page's otherwise. */
    private fun shownItem() = if (mode == MODE_PAGED) currentItem else pageToItem(currentIndex)

    private fun brightnessGestureEnabled() = prefs.brightnessGesture && mode == MODE_PAGED

    private fun spacingPx(): Int =
        (spacingDp * resources.displayMetrics.density).toInt()

    /** The fit in effect: "Fit screen" is meaningless for a continuous column, so it fits the width there. */
    private fun effectiveFitName(): String = if (mode != MODE_PAGED && fit == "screen") "width" else fit

    private fun effectiveFit(): ZoomImageView.FitMode {
        return when (effectiveFitName()) {
            "width" -> ZoomImageView.FitMode.WIDTH
            "height" -> ZoomImageView.FitMode.HEIGHT
            "stretch" -> ZoomImageView.FitMode.STRETCH
            else -> ZoomImageView.FitMode.SCREEN
        }
    }

    // ------------------------------------------------------------------ paging

    private fun goTo(page: Int) {
        val count = source?.pageCount ?: return
        if (page < 0) {
            // Only a back made at the very start counts; one that scrolls a strip's
            // first page up to its top does not.
            val atStart = atChapterStart()
            goTo(0)
            if (atStart) backAtStart()
            return
        }
        if (page >= count) {
            handleAdvance()
            return
        }
        currentIndex = page
        val item = pageToItem(currentIndex)
        currentItem = item
        if (mode == MODE_PAGED) pager.setCurrentItem(item, false) else vertical.scrollToPosition(item)
        seek.progress = item
        onPageChanged()
    }

    /** Shows [item]; before the first or past the last it acts as [goTo] does there. */
    private fun goToItem(item: Int) {
        val count = source?.pageCount ?: return
        when {
            item < 0 -> goTo(-1)
            item >= layout.itemCount(count) -> goTo(count)
            else -> {
                currentItem = item
                currentIndex = itemToPage(item)
                if (mode == MODE_PAGED) pager.setCurrentItem(item, false) else vertical.scrollToPosition(item)
                seek.progress = item
                onPageChanged()
            }
        }
    }

    /** Moves [delta] items (pages, spreads, or halves of a page) from the current one. */
    private fun turn(delta: Int) = goToItem(shownItem() + delta)

    /** Called when the reader is told to move past the last page. */
    private fun handleAdvance() {
        val next = nextChapter()
        if (next == null) {
            toast(R.string.finished_last_chapter)
            return
        }
        if (!prefs.autoNext) {
            showEndCard()
            return
        }
        // A strip that already holds the next chapter needs no reload: rolling on
        // is pure bookkeeping, and the pages on screen keep rendering.
        if (mode != MODE_PAGED && stripAhead != null) {
            commitStripChapter()
            vertical.scrollToPosition(0)
        } else {
            openChapter(next, 0)
        }
    }

    /** A tap at ([x], [y]), as fractions of the page view: turns a page or spread, or toggles the UI. */
    private fun onTap(x: Float, y: Float) {
        val zones = if (mode == MODE_LONGSTRIP) TapZones.DISABLED else TapZones.of(prefs.tapZones)
        val rtl = readingDir == "rtl" && mode == MODE_PAGED
        when (zones.action(x, y, rtl, prefs.mirrorTapZones)) {
            TapZones.Action.PREVIOUS -> turn(-1)
            TapZones.Action.NEXT -> turn(1)
            TapZones.Action.MENU -> toggleBars()
        }
    }

    /**
     * A "previous page" action on the chapter's first page: counted toward "Previous
     * chapter on repeated back", which hints at the next one needed, or opens the
     * previous chapter at its last page (its last spread in two-page mode).
     */
    private fun backAtStart() {
        val prev = prevChapter() ?: return
        when (repeatedBack.back(SystemClock.uptimeMillis(), prefs.prevChapterBacks)) {
            RepeatedBack.Result.IGNORED -> Unit
            RepeatedBack.Result.HINT -> showHint(getString(R.string.hint_prev_chapter), RepeatedBack.WINDOW_MS)
            RepeatedBack.Result.OPEN -> {
                hideHintNow()
                openChapter(prev, LAST_PAGE)
            }
        }
    }

    /** Shows [text] above the bottom bar for [durationMs]: the repeated-back hint, or a note on open. */
    private fun showHint(text: CharSequence, durationMs: Long) {
        handler.removeCallbacks(hideHint)
        hint.animate().cancel()
        hint.text = text
        hint.alpha = 1f
        hint.visibility = View.VISIBLE
        handler.postDelayed(hideHint, durationMs)
    }

    private fun hideHintNow() {
        handler.removeCallbacks(hideHint)
        hideHint.run()
    }

    private val hideHint = Runnable {
        if (hint.visibility == View.VISIBLE) {
            hint.animate().alpha(0f).setDuration(150).withEndAction { hint.visibility = View.GONE }.start()
        }
    }

    private fun onPageChanged() {
        if (currentIndex != backPage) {
            repeatedBack.reset()
            hideHintNow()
            backPage = currentIndex
        }
        val count = source?.pageCount ?: 0
        pageText.text = if (itemNumbers()) getString(R.string.page_indicator, shownItem() + 1, layout.itemCount(count))
        else getString(R.string.page_indicator, currentIndex + 1, count)
        chapterText.text = modeLabel()
        updateChapterButtons()
        updateEndCard()
        if (currentIndex in 0 until count) {
            val item = shownItem()
            seenPages.set(layout.firstPage(item).coerceAtLeast(0), layout.lastPage(item, count) + 1)
        }
        updateSpreadRotation()
        if (seekPanel.visibility == View.VISIBLE) {
            seekPreviews?.highlight(shownItem())
            centerSeekPanel()
        }
        val onLast = count > 0 && layout.isLastItem(shownItem(), count)
        if (!onLast) landedAtEnd = false
        if (!markedRead && onLast && !landedAtEnd) {
            markedRead = true
            file?.absolutePath?.let { library.setRead(it, true) }
        }
        invalidateOptionsMenu()
    }

    private fun modeLabel(): String = when (mode) {
        MODE_VERTICAL -> getString(R.string.page_mode_vertical)
        MODE_LONGSTRIP -> getString(R.string.page_mode_longstrip)
        else -> getString(R.string.page_mode_paged)
    }

    private fun updateEndCard() {
        val src = source ?: return
        // "Auto next chapter" replaces the prompt entirely: the reader simply
        // rolls into the next chapter when the user moves past the last page.
        if (autoAdvance()) {
            hideEndCard()
            return
        }
        val atEnd = when (mode) {
            MODE_LONGSTRIP -> !vertical.canScrollVertically(1)
            else -> layout.isLastItem(shownItem(), src.pageCount)
        }
        if (!atEnd) {
            endDismissed = false
            hideEndCard()
        } else if (!endDismissed) {
            showEndCard()
        }
    }

    private fun showEndCard() {
        val f = file ?: return
        if (autoAdvance()) return
        endCard.visibility = View.VISIBLE
        endSubtitle.text = getString(R.string.chapter_end_msg, chapterLabel(f))
        endNext.visibility = if (nextChapter() != null) View.VISIBLE else View.GONE
        endPrev.visibility = if (prevChapter() != null) View.VISIBLE else View.GONE
    }

    private fun hideEndCard() {
        endCard.visibility = View.GONE
    }

    private fun updateChapterButtons() {
        val hasNext = nextChapter() != null
        val hasPrev = prevChapter() != null
        btnNextChapter.isEnabled = hasNext
        btnNextChapter.alpha = if (hasNext) 1f else 0.3f
        btnPrevChapter.isEnabled = hasPrev
        btnPrevChapter.alpha = if (hasPrev) 1f else 0.3f
    }

    private fun saveHistory() {
        val f = file ?: return
        val count = source?.pageCount ?: return
        library.addHistory(
            HistoryItem(f.absolutePath, f.name, currentIndex, count, System.currentTimeMillis())
        )
    }

    // ------------------------------------------------------------------ chapters

    private fun siblingIndex(): Int = chapters.indexOfFirst { it.absolutePath == file?.absolutePath }

    private fun nextChapter(): File? {
        val i = siblingIndex()
        return if (i >= 0 && i + 1 < chapters.size) chapters[i + 1] else null
    }

    private fun prevChapter(): File? {
        val i = siblingIndex()
        return if (i > 0) chapters[i - 1] else null
    }

    private fun openNextChapter() {
        val next = nextChapter()
        if (next == null) {
            toast(R.string.no_next_chapter)
            return
        }
        openChapter(next, 0)
    }

    private fun openPrevChapter() {
        val prev = prevChapter()
        if (prev == null) {
            toast(R.string.no_prev_chapter)
            return
        }
        openChapter(prev, 0)
    }

    private fun openChapter(target: File, page: Int) {
        saveHistory()
        val intent = Intent(this, ViewerActivity::class.java)
            .putExtra(EXTRA_PATH, target.absolutePath)
            .putExtra(EXTRA_PAGE, page)
        // singleTop + onNewIntent: the reader reloads in place, so the back stack
        // never grows one activity per chapter.
        startActivity(intent)
    }

    private fun chapterLabel(f: File): String {
        val name = f.name
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) else name
    }

    // ------------------------------------------------------------------ seek preview strip

    /**
     * Watches the seek bar's touches for a hold ([SeekHold]): one that stays still for
     * [SeekHold.HOLD_MS] opens the preview strip, and the rest of that touch is kept from
     * the seek bar. A touch that moves seeks as usual. A touch on the thumb reaches the
     * seek bar only once it moves or lifts first ([seekDown]), as the seek bar would
     * otherwise move to the finger at once.
     */
    private fun onSeekTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                seekHeld = false
                dropSeekDown()
                seekHold.down(e.x, e.y)
                handler.removeCallbacks(seekHoldUp)
                handler.postDelayed(seekHoldUp, SeekHold.HOLD_MS)
                val start = seek.paddingLeft
                val track = seek.width - start - seek.paddingRight
                val thumbHalf = (seek.thumb?.intrinsicWidth ?: 0) / 2
                val rtl = seek.layoutDirection == View.LAYOUT_DIRECTION_RTL
                if (SeekHold.onThumb(e.x, seek.progress, seek.max, start, track, thumbHalf, rtl)) {
                    seekDown = MotionEvent.obtain(e)
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> if (!seekHold.move(e.x, e.y)) {
                handler.removeCallbacks(seekHoldUp)
                releaseSeekDown()
            } else if (seekDown != null) {
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                seekHold.up()
                handler.removeCallbacks(seekHoldUp)
                if (seekHeld) {
                    seekHeld = false
                    return true
                }
                if (e.actionMasked == MotionEvent.ACTION_UP) releaseSeekDown() else dropSeekDown()
            }
        }
        return seekHeld
    }

    /** Hands the seek bar the kept [seekDown], ahead of the touch's event that follows it. */
    private fun releaseSeekDown() {
        val down = seekDown ?: return
        seekDown = null
        seek.onTouchEvent(down)
        down.recycle()
    }

    private fun dropSeekDown() {
        seekDown?.recycle()
        seekDown = null
    }

    /** The hold's time is up: the seek bar lets go of the touch (or never had it, on its thumb) and the strip opens. */
    private val seekHoldUp = Runnable {
        if (!seekHold.held() || source == null) return@Runnable
        seekHeld = true
        if (seekDown != null) {
            dropSeekDown()
        } else {
            val now = SystemClock.uptimeMillis()
            val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
            seek.onTouchEvent(cancel)
            cancel.recycle()
        }
        openSeekPanel()
    }

    /** The preview strip's cells for [src] as the reader lays its items out now. */
    private fun seekCells(src: PageSource): List<SeekStrip.Cell> =
        SeekStrip.cells(layout, src.pageCount, itemNumbers()) { pageSizes.getOrNull(it) ?: src.loadedSize(it) }

    /**
     * Opens the strip of page previews above the seek bar, the item on screen centered
     * and highlighted, in reading order, the page behind dimmed.
     */
    internal fun openSeekPanel() {
        val src = source ?: return
        stopAutoScroll()
        val a = seekPreviews ?: SeekPreviewAdapter(
            src,
            resources.getDimensionPixelSize(R.dimen.seek_thumb_h),
            prefs.linearLight,
            exact = src.cheapSizes
        ) { item ->
            closeSeekPanel()
            goToItem(item)
        }.also { seekPreviews = it }
        a.show(seekCells(src), shownItem())
        seekPanel.layoutDirection = if (readingDir == "rtl") View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        if (seekPanel.adapter !== a) seekPanel.adapter = a
        seekScrim.visibility = View.VISIBLE
        seekPanel.visibility = View.VISIBLE
        seekBack.isEnabled = true
        centerSeekPanel()
    }

    /** Scrolls the strip so the item on screen sits in its middle. */
    private fun centerSeekPanel() {
        val a = seekPreviews ?: return
        val item = shownItem()
        if (item !in 0 until a.itemCount) return
        val pad = resources.getDimensionPixelSize(R.dimen.seek_cell_pad) + resources.getDimensionPixelSize(R.dimen.seek_frame_pad)
        // Before its first layout the strip is as wide as the reader.
        val width = (seekPanel.width.takeIf { it > 0 } ?: (seekPanel.parent as View).width) - seekPanel.paddingLeft - seekPanel.paddingRight
        (seekPanel.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(item, SeekStrip.centerOffset(width, a.cellWidth(item, 2 * pad)))
    }

    /** True while the preview strip shows. */
    internal val seekPanelShown: Boolean get() = seekPanel.visibility == View.VISIBLE

    /** A touch on the bars, which sit above the strip's scrim, closes the strip and goes on to the bars. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && seekPanelShown && !hits(seekPanel, ev) &&
            (hits(toolbar, ev) || hits(bottomBar, ev))
        ) {
            closeSeekPanel()
        }
        return super.dispatchTouchEvent(ev)
    }

    /** True when [ev] lands on [view] as it shows on screen. */
    private fun hits(view: View, ev: MotionEvent): Boolean {
        if (view.visibility != View.VISIBLE) return false
        val at = IntArray(2)
        view.getLocationOnScreen(at)
        return ev.rawX >= at[0] && ev.rawX < at[0] + view.width && ev.rawY >= at[1] && ev.rawY < at[1] + view.height
    }

    /** Hides the strip; detaching its adapter recycles the cells, which stops their loads but keeps the caches. */
    private fun closeSeekPanel() {
        seekBack.isEnabled = false
        if (seekPanel.visibility != View.VISIBLE) return
        seekPanel.visibility = View.GONE
        seekScrim.visibility = View.GONE
        seekPanel.adapter = null
    }

    /** Closes the strip and forgets its previews, which belong to the book being closed. */
    private fun dropSeekPreviews() {
        closeSeekPanel()
        seekPreviews?.release()
        seekPreviews = null
    }

    // ------------------------------------------------------------------ gestures

    /**
     * Brightness by vertical drag: up is brighter. One screen height spans the whole range,
     * and crossing 0 holds there for [BRIGHTNESS_HOLD] of the screen height. The value
     * shows in a pill ([showBrightness]) while dragging, and for a moment after.
     */
    private val brightnessDrag = object : ZoomImageView.VerticalDrag {
        private var snap: SnapDrag? = null

        override fun start() {
            val h = resources.displayMetrics.heightPixels.toFloat()
            snap = SnapDrag(adjustments.brightness, h / (Adjustments.MAX - Adjustments.MIN), h * BRIGHTNESS_HOLD)
            showBrightness(adjustments.brightness, snapped = false)
        }

        override fun move(dy: Float) {
            val value = snap?.moveBy(-dy) ?: return
            val before = adjustments.brightness
            if (value != before) setAdjustments(adjustments.copy(brightness = value), persist = false)
            showBrightness(value, BrightnessPill.snapped(before, value))
        }

        override fun end() {
            snap = null
            persistAdjustments()
            handler.removeCallbacks(hideBrightness)
            handler.postDelayed(hideBrightness, BrightnessPill.LINGER_MS)
        }
    }

    /**
     * Shows [value] in the brightness pill near the top: "Brightness +12" over a bar from
     * −100 to +100, a 0 highlighted; [snapped] into the hold at 0 it also ticks.
     */
    private fun showBrightness(value: Int, snapped: Boolean) {
        handler.removeCallbacks(hideBrightness)
        brightnessPill.animate().cancel()
        val number = BrightnessPill.valueText(value)
        val text = SpannableStringBuilder(getString(R.string.brightness_indicator, number))
        if (value == 0) {
            val at = text.lastIndexOf(number)
            if (at >= 0) {
                text.setSpan(ForegroundColorSpan(getColor(R.color.reader_accent)), at, at + number.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                text.setSpan(StyleSpan(Typeface.BOLD), at, at + number.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        brightnessText.text = text
        brightnessBar.value = value
        val gap = resources.getDimensionPixelSize(R.dimen.brightness_pill_gap)
        brightnessPill.translationY = ((if (toolbar.visibility == View.VISIBLE) toolbar.bottom else 0) + gap).toFloat()
        brightnessPill.alpha = 1f
        brightnessPill.visibility = View.VISIBLE
        if (snapped) brightnessPill.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    private val hideBrightness = Runnable {
        if (brightnessPill.visibility == View.VISIBLE) {
            brightnessPill.animate().alpha(0f).setDuration(200).withEndAction { brightnessPill.visibility = View.GONE }.start()
        }
    }

    // ------------------------------------------------------------------ adjustments

    /** Takes the open book's adjustments: its own, or the defaults. */
    private fun loadAdjustments() {
        val own = file?.let { library.adjustmentsFor(it.absolutePath) }
        ownAdjustments = own != null
        adjustmentsDirty = false
        showAdjustments(own ?: prefs.defaultAdjustments)
    }

    private fun showAdjustments(value: Adjustments) {
        adjustments = value
        adapter?.adjustments = value
    }

    /**
     * Shows [value]: the book's own adjustments, or the defaults again when it equals them.
     * Stored now with [persist], else by [persistAdjustments].
     */
    private fun setAdjustments(value: Adjustments, persist: Boolean = true) {
        ownAdjustments = value.ownAgainst(prefs.defaultAdjustments)
        adjustmentsDirty = true
        showAdjustments(value.coerced())
        if (persist) persistAdjustments()
    }

    /** Stores the book's adjustments, or forgets them when it follows the defaults. */
    private fun persistAdjustments() {
        if (!adjustmentsDirty) return
        adjustmentsDirty = false
        val f = file ?: return
        library.setAdjustments(f.absolutePath, if (ownAdjustments) adjustments else null)
    }

    /** Returns the book to the default adjustments. */
    private fun resetAdjustments() {
        ownAdjustments = false
        adjustmentsDirty = true
        persistAdjustments()
        showAdjustments(prefs.defaultAdjustments)
    }

    /** One adjustment's slider: its ids and how it reads and changes [Adjustments]. */
    private class AdjustSlider(
        val slider: Int,
        val label: Int,
        val name: Int,
        val get: (Adjustments) -> Int,
        val with: (Adjustments, Int) -> Adjustments
    )

    private val adjustSliders = listOf(
        AdjustSlider(R.id.adjust_brightness, R.id.adjust_brightness_value, R.string.adjust_brightness, { it.brightness }) { a, v -> a.copy(brightness = v) },
        AdjustSlider(R.id.adjust_contrast, R.id.adjust_contrast_value, R.string.adjust_contrast, { it.contrast }) { a, v -> a.copy(contrast = v) },
        AdjustSlider(R.id.adjust_saturation, R.id.adjust_saturation_value, R.string.adjust_saturation, { it.saturation }) { a, v -> a.copy(saturation = v) }
    )

    /** Binds the adjustments panel ([R.layout.view_adjustments]) inside [root]: live while dragging, stored on release. */
    private fun bindAdjustments(root: View) {
        fun show() {
            for (s in adjustSliders) {
                val value = s.get(adjustments)
                root.findViewById<Slider>(s.slider).safeSet(value.toFloat())
                root.findViewById<TextView>(s.label).text = adjustLabel(s.name, value)
            }
        }
        for (s in adjustSliders) {
            val slider = root.findViewById<Slider>(s.slider)
            val label = root.findViewById<TextView>(s.label)
            slider.addOnChangeListener { _, value, fromUser ->
                if (!fromUser) return@addOnChangeListener
                setAdjustments(s.with(adjustments, value.toInt()), persist = false)
                label.text = adjustLabel(s.name, value.toInt())
            }
            slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) = Unit

                override fun onStopTrackingTouch(slider: Slider) = persistAdjustments()
            })
        }
        // Asks only when the book has its own adjustments to lose.
        root.findViewById<View>(R.id.adjust_reset).setOnClickListener {
            val reset = { resetAdjustments(); show() }
            if (ownAdjustments) {
                confirm(this, R.string.confirm_reset_adjust_title, R.string.confirm_reset_adjust_msg, R.string.reset) { reset() }
            } else {
                reset()
            }
        }
        show()
    }

    private fun adjustLabel(name: Int, value: Int): String =
        getString(name) + " · " + if (value > 0) "+$value" else "$value"

    private fun applyNight() {
        nightOverlay.visibility = if (nightFilter) View.VISIBLE else View.GONE
    }

    private fun setNight(enabled: Boolean) {
        if (nightFilter == enabled) return
        nightFilter = enabled
        prefs.nightFilter = enabled
        applyNight()
        invalidateOptionsMenu()
    }

    private fun applyKeepScreenOn() {
        if (keepOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun setKeepOn(enabled: Boolean) {
        if (keepOn == enabled) return
        keepOn = enabled
        prefs.keepScreenOn = enabled
        applyKeepScreenOn()
        invalidateOptionsMenu()
    }

    private fun setFullscreen(enabled: Boolean) {
        applyFullscreen(enabled)
        prefs.fullscreen = enabled
        invalidateOptionsMenu()
    }

    private fun applyFullscreen(enabled: Boolean) {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (enabled) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun toggleBars() {
        barsVisible = !barsVisible
        if (!barsVisible) closeSeekPanel()
        animateBar(toolbar, barsVisible)
        animateBar(bottomBar, barsVisible)
    }

    private fun animateBar(view: View, visible: Boolean) {
        view.animate().cancel()
        if (visible) {
            view.visibility = View.VISIBLE
            view.animate().alpha(1f).setDuration(150).start()
        } else {
            view.animate().alpha(0f).setDuration(150).withEndAction {
                view.visibility = View.GONE
            }.start()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (prefs.volumeKeys && isVolumeKey(keyCode)) {
            // Auto-repeat would skip pages while the key is held; one press, one page.
            if (event.repeatCount == 0) {
                stopAutoScroll()
                turn(if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) 1 else -1)
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /** Swallow the key-up too, otherwise the system still shows its volume UI. */
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (prefs.volumeKeys && isVolumeKey(keyCode)) return true
        return super.onKeyUp(keyCode, event)
    }

    private fun isVolumeKey(keyCode: Int) =
        keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN

    // ------------------------------------------------------------------ menu

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_viewer, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val marked = file?.let { library.isBookmarked(it.absolutePath, currentIndex) } == true
        menu.findItem(R.id.menu_bookmark)?.apply {
            setIcon(if (marked) R.drawable.ic_bookmark else R.drawable.ic_bookmark_border)
            setTitle(if (marked) R.string.menu_bookmark_remove else R.string.menu_bookmark_add)
        }
        menu.findItem(R.id.menu_auto_scroll)?.isChecked = autoScrolling
        prepareFavorites(menu)
        val drawer = ChapterDrawer.hasEntries(bookChapters, chapters)
        menu.findItem(R.id.menu_book_chapters)?.isVisible = drawer
        menu.findItem(R.id.menu_chapters)?.isVisible = drawer
        return super.onPrepareOptionsMenu(menu)
    }

    /**
     * Favorites ▸ this comic and this folder, each checked while it is a favorite; hidden
     * for a document another app handed over, which only has a copy in the cache.
     */
    private fun prepareFavorites(menu: Menu) {
        val f = favoriteFile()
        menu.findItem(R.id.menu_favorites)?.isVisible = f != null
        menu.findItem(R.id.menu_favorite)?.apply {
            isVisible = f != null
            isChecked = favoriteBook
        }
        menu.findItem(R.id.menu_favorite_folder)?.apply {
            isVisible = f?.parentFile != null
            isChecked = favoriteFolder
        }
    }

    /** The open comic, unless it is a copy in the cache of a document another app handed over. */
    private fun favoriteFile(): File? =
        file?.takeUnless { it.absolutePath.startsWith(cacheDir.absolutePath + File.separator) }

    /** Reads whether the open comic and its folder are favorites. */
    private fun refreshFavorites() {
        val f = favoriteFile()
        val favorites = if (f != null) library.favoriteSet() else emptySet()
        favoriteBook = f != null && f.absolutePath in favorites
        favoriteFolder = f?.parent?.let { it in favorites } == true
    }

    /** Adds [path] to the favorites, or removes it, and says which. */
    private fun toggleFavorite(path: String?, added: Int, removed: Int) {
        if (path == null) return
        toast(if (library.toggleFavorite(path)) added else removed)
        refreshFavorites()
        invalidateOptionsMenu()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_tune -> { showReaderSheet(); true }
            R.id.menu_chapters, R.id.menu_book_chapters -> { showChapterDrawer(); true }
            R.id.menu_auto_scroll -> { toggleAutoScroll(); true }
            R.id.menu_book_info -> { showBookInfo(); true }
            R.id.menu_settings -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
            R.id.menu_share_page -> { exportPage(share = true); true }
            R.id.menu_save_page -> { exportPage(share = false); true }
            R.id.menu_jump -> { showJumpDialog(); true }
            R.id.menu_rotate -> { cycleOrientation(); true }
            R.id.menu_bookmark -> { toggleBookmark(); true }
            R.id.menu_bookmark_list -> { showBookmarks(); true }
            R.id.menu_favorite -> {
                toggleFavorite(file?.absolutePath, R.string.favorite_added, R.string.favorite_removed)
                true
            }
            R.id.menu_favorite_folder -> {
                toggleFavorite(file?.parent, R.string.folder_favorite_added, R.string.folder_favorite_removed)
                true
            }
            R.id.menu_crop -> { startCrop(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ------------------------------------------------------------------ reader options

    private fun setFit(name: String) {
        if (fit == name) return
        fit = name
        persistLayout()
        adapter?.fitMode = effectiveFit()
        invalidateOptionsMenu()
    }

    private fun setFilter(name: String) {
        filter = name
        prefs.filter = name
        adapter?.filter = name
        invalidateOptionsMenu()
    }

    /**
     * The user's choice of direction, kept for the book's folder (with "Per-folder layout"
     * on): alone, so the folder's other options still follow the defaults, or with its
     * frozen layout. Its books no longer detect. Without a folder it is the default.
     */
    private fun setReadingDir(dir: String) {
        // The direction on screen may still be chosen, to keep it from detection and the defaults.
        val changed = readingDir != dir
        if (!changed && (layoutFolder() == null || folderDirection())) return
        readingDir = dir
        directionChoices++
        detectedActive = false
        detection = null
        val folder = layoutFolder()
        when {
            folder == null -> prefs.readingDir = dir
            folderScope() -> persistLayout(dirChosen = true)
            else -> {
                if (folderLayout == null) toast(R.string.direction_kept_for_folder)
                setFolder(folder, FolderLayout.Direction(dir))
            }
        }
        invalidateOptionsMenu()
        if (changed) attach()
    }

    /**
     * "Auto": the folder gives its direction back, keeping a frozen layout's other options,
     * so the book detects its direction again or reads the default.
     */
    private fun setAutoDirection() {
        val folder = layoutFolder() ?: return
        val now = folderLayout ?: return
        if (!now.dirChosen) return
        setFolder(folder, if (now is FolderLayout.Frozen) now.copy(dirChosen = false) else null)
        directionChoices++
        redetect()
    }

    /** Detects the open book's direction again, as on open, and shows what it finds. */
    private fun redetect() {
        lateDetectionJob?.cancel()
        val src = source
        val book = file
        if (src == null || book == null || !prefs.detectDirection) {
            detection = null
            showDetection()
            return
        }
        val choices = directionChoices
        val siblings = chapters
        lateDetectionJob = lifecycleScope.launch {
            val contents = withContext(AppDispatchers.io) { runCatching { detectBeforeOpen(book, src, siblings) }.getOrNull() }
            if (source !== src || choices != directionChoices || folderDirection()) return@launch
            detection = contents?.detected
            showDetection()
            noteDetection()
            if (contents != null) detectLater(book, src, contents)
        }
    }

    /** Lays the book out again with the direction detection gave it. */
    private fun showDetection() {
        if (reloadLayout()) attach()
        invalidateOptionsMenu()
        syncSheet()
    }

    private fun setPageMode(newMode: String) {
        if (newMode == mode) return
        stopAutoScroll()
        mode = newMode
        // Only the strip modes lay the next chapter in behind this one.
        if (mode == MODE_PAGED) dropStripAhead()
        persistLayout()
        attach()
        prepareStripAhead()
    }

    private fun setSpacingDp(dp: Int) {
        val value = dp.coerceIn(0, Prefs.PAGE_SPACING_MAX)
        if (value == spacingDp) return
        spacingDp = value
        persistLayout()
        adapter?.spacingPx = spacingPx()
    }

    private fun setTwoPage(value: TwoPage) {
        if (mode != MODE_PAGED) {
            toast(R.string.menu_spread)
            return
        }
        if (twoPage == value) return
        twoPage = value
        persistLayout()
        if (useSpread() != spread) attach()
    }

    private fun setSpreadDisplay(value: SpreadDisplay) {
        if (spreadDisplay == value) return
        spreadDisplay = value
        persistLayout()
        if (mode == MODE_PAGED && !spread) attach()
    }

    private fun setOrientationLock(value: Int) {
        requestedOrientation = spreadRotation.choose(value)
        invalidateOptionsMenu()
    }

    private fun cycleOrientation() {
        setOrientationLock(
            if (landscape()) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        )
    }

    /**
     * The reader settings sheet: Apply to, then Layout, Color and Behavior. It opens over
     * half the screen, so the page shows each change behind it, and stays in step with the
     * reader ([syncSheet]) while it is open.
     */
    private fun showReaderSheet() {
        val view = layoutInflater.inflate(R.layout.sheet_reader, null)
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(view)
        dialog.behavior.peekHeight = sheetPeek()
        // No scrim: the page behind is what the sheet's colors and layout are judged on.
        dialog.window?.setDimAmount(0f)
        bindSheet(view)
        bindAdjustments(view)
        view.findViewById<View>(R.id.sheet_more_settings).setOnClickListener {
            dialog.dismiss()
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        dialog.setOnDismissListener {
            if (sheet === view) {
                sheet = null
                sheetDialog = null
            }
            persistAdjustments()
        }
        sheet = view
        sheetDialog = dialog
        syncSheet()
        dialog.show()
    }

    /** The sheet opens over half the screen, in either orientation. */
    private fun sheetPeek() = resources.displayMetrics.heightPixels / 2

    /** One single choice in the sheet: its button [group], each button's value, the value now and how to pick one. */
    private class Segments<T>(
        val group: Int,
        val buttons: List<Pair<Int, T>>,
        val current: () -> T,
        val pick: (T) -> Unit
    )

    private val segments: List<Segments<*>> by lazy {
        listOf(
            Segments(R.id.sheet_scope, listOf(R.id.btn_scope_folder to true, R.id.btn_scope_all to false), { folderScope() }) { setFolderScope(it) },
            Segments(
                R.id.sheet_page_mode,
                listOf(R.id.btn_mode_paged to MODE_PAGED, R.id.btn_mode_vertical to MODE_VERTICAL, R.id.btn_mode_longstrip to MODE_LONGSTRIP),
                { mode }
            ) { setPageMode(it) },
            Segments(
                R.id.sheet_dir,
                listOf(R.id.btn_dir_ltr to "ltr", R.id.btn_dir_rtl to "rtl", R.id.btn_dir_auto to DIR_AUTO),
                { if (layoutFolder() != null && !folderDirection()) DIR_AUTO else readingDir }
            ) { if (it == DIR_AUTO) setAutoDirection() else setReadingDir(it) },
            Segments(
                R.id.sheet_fit,
                listOf(R.id.btn_fit_screen to "screen", R.id.btn_fit_width to "width", R.id.btn_fit_height to "height", R.id.btn_fit_stretch to "stretch"),
                { effectiveFitName() }
            ) { setFit(it) },
            Segments(
                R.id.sheet_two_page,
                listOf(R.id.btn_two_off to TwoPage.OFF, R.id.btn_two_on to TwoPage.ON, R.id.btn_two_landscape to TwoPage.LANDSCAPE),
                { twoPage }
            ) { setTwoPage(it) },
            Segments(
                R.id.sheet_spreads,
                listOf(R.id.btn_spreads_split to SpreadDisplay.SPLIT, R.id.btn_spreads_merge to SpreadDisplay.MERGE),
                { spreadDisplay }
            ) { setSpreadDisplay(it) },
            Segments(
                R.id.sheet_orientation,
                listOf(
                    R.id.btn_orient_default to ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
                    R.id.btn_orient_portrait to ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                    R.id.btn_orient_landscape to ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                ),
                { spreadRotation.user }
            ) { setOrientationLock(it) }
        )
    }

    private val filterChips = listOf(
        R.id.chip_filter_none to Filters.NONE,
        R.id.chip_filter_invert to Filters.INVERT,
        R.id.chip_filter_grayscale to Filters.GRAYSCALE,
        R.id.chip_filter_contrast to Filters.CONTRAST,
        R.id.chip_filter_sepia to Filters.SEPIA
    )

    /** Binds the sheet's controls once; [syncSheet] then shows the reader's state on them. */
    private fun bindSheet(view: View) {
        for (s in segments) bindSegments(view, s)
        view.findViewById<ChipGroup>(R.id.sheet_filter).setOnCheckedStateChangeListener { _, checked ->
            if (syncingSheet) return@setOnCheckedStateChangeListener
            filterChips.firstOrNull { it.first == checked.firstOrNull() }?.let { setFilter(it.second) }
        }
        fun switch(id: Int, set: (Boolean) -> Unit) =
            view.findViewById<MaterialSwitch>(id).setOnCheckedChangeListener { _, checked -> if (!syncingSheet) set(checked) }
        switch(R.id.sheet_auto_crop) { setAutoCrop(it) }
        switch(R.id.sheet_shift) { setShifted(it) }
        switch(R.id.sheet_rotate_spreads) {
            prefs.rotateSpreads = it
            updateSpreadRotation()
        }
        switch(R.id.sheet_night) { setNight(it) }
        switch(R.id.sheet_auto_next) { setAutoNext(it) }
        switch(R.id.sheet_keep_on) { setKeepOn(it) }
        switch(R.id.sheet_fullscreen) { setFullscreen(it) }
        view.findViewById<Slider>(R.id.sheet_spacing).addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            setSpacingDp(value.toInt())
            syncSheet()
        }
        view.findViewById<Slider>(R.id.sheet_auto_speed).apply {
            valueTo = (AutoScroll.SPEEDS.size - 1).toFloat()
            setLabelFormatter { AutoScroll.format(speedAt(it)) }
            addOnChangeListener { _, value, fromUser ->
                if (!fromUser) return@addOnChangeListener
                prefs.autoSpeed = speedAt(value)
                syncSheet()
            }
        }
    }

    private fun <T> bindSegments(view: View, s: Segments<T>) {
        view.findViewById<MaterialButtonToggleGroup>(s.group).addOnButtonCheckedListener { _, id, checked ->
            if (!checked || syncingSheet) return@addOnButtonCheckedListener
            s.buttons.firstOrNull { it.first == id }?.let { s.pick(it.second) }
            syncSheet()
        }
    }

    /** Shows the reader's state on the open sheet, its listeners quiet meanwhile. */
    private fun syncSheet() {
        val view = sheet ?: return
        syncingSheet = true
        try {
            for (s in segments) syncSegments(view, s)
            val chip = filterChips.firstOrNull { it.second == filter }?.first ?: R.id.chip_filter_none
            view.findViewById<ChipGroup>(R.id.sheet_filter).check(chip)
            val scoped = layoutFolder() != null
            view.findViewById<View>(R.id.sheet_scope_row).visibility = if (scoped) View.VISIBLE else View.GONE
            // Auto hands a folder's direction back; without a folder there is none to hand back.
            view.findViewById<View>(R.id.btn_dir_auto).visibility = if (scoped) View.VISIBLE else View.GONE
            view.findViewById<TextView>(R.id.sheet_dir_hint).text = directionInfo()
            view.findViewById<TextView>(R.id.sheet_scope_hint)
                .setText(if (folderScope()) R.string.scope_folder_hint else R.string.scope_all_hint)
            // "Fit screen" reads as fit width in a strip; spreads are paged only, split or merged one page a screen;
            // a long strip neither crops nor spaces.
            view.findViewById<View>(R.id.btn_fit_screen).isEnabled = mode == MODE_PAGED
            setGroupEnabled(view.findViewById(R.id.sheet_two_page), mode == MODE_PAGED)
            setGroupEnabled(view.findViewById(R.id.sheet_spreads), mode == MODE_PAGED && !spread)
            view.findViewById<MaterialSwitch>(R.id.sheet_shift).apply {
                isChecked = shifted
                isEnabled = spread && favoriteFile() != null
            }
            view.findViewById<MaterialSwitch>(R.id.sheet_rotate_spreads).isChecked = prefs.rotateSpreads
            view.findViewById<MaterialSwitch>(R.id.sheet_auto_crop).apply {
                isChecked = autoCrop
                isEnabled = mode != MODE_LONGSTRIP
            }
            view.findViewById<TextView>(R.id.sheet_spacing_value).text = spacingLabel()
            view.findViewById<Slider>(R.id.sheet_spacing).apply {
                safeSet(spacingDp.toFloat())
                isEnabled = mode != MODE_LONGSTRIP
            }
            view.findViewById<MaterialSwitch>(R.id.sheet_night).isChecked = nightFilter
            view.findViewById<MaterialSwitch>(R.id.sheet_auto_next).isChecked = prefs.autoNext
            view.findViewById<TextView>(R.id.sheet_auto_speed_value).text = autoSpeedLabel()
            view.findViewById<Slider>(R.id.sheet_auto_speed).safeSet(AutoScroll.indexOf(prefs.autoSpeed).toFloat())
            view.findViewById<MaterialSwitch>(R.id.sheet_keep_on).isChecked = keepOn
            view.findViewById<MaterialSwitch>(R.id.sheet_fullscreen).isChecked = prefs.fullscreen
        } finally {
            syncingSheet = false
        }
    }

    private fun <T> syncSegments(view: View, s: Segments<T>) {
        val now = s.current()
        val id = s.buttons.firstOrNull { it.second == now }?.first ?: s.buttons.first().first
        view.findViewById<MaterialButtonToggleGroup>(s.group).check(id)
    }

    private fun setGroupEnabled(group: ViewGroup, enabled: Boolean) {
        group.isEnabled = enabled
        for (i in 0 until group.childCount) group.getChildAt(i).isEnabled = enabled
    }

    private fun spacingLabel(): String =
        getString(R.string.menu_page_spacing) + " · $spacingDp dp"

    private fun autoSpeedLabel(): String =
        getString(R.string.pref_auto_speed) + " · " + AutoScrollText.of(resources, prefs.autoSpeed)

    /** The speed at a position of the sheet's slider, which steps through [AutoScroll.SPEEDS]. */
    private fun speedAt(position: Float): Float = AutoScroll.SPEEDS[position.toInt().coerceIn(0, AutoScroll.SPEEDS.size - 1)]

    /** Snaps a value onto the slider's step grid before assigning it. */
    private fun Slider.safeSet(value: Float) {
        val step = stepSize
        val snapped = if (step > 0f) valueFrom + Math.round((value - valueFrom) / step) * step else value
        this.value = snapped.coerceIn(valueFrom, valueTo)
    }

    // ------------------------------------------------------------------ auto-scroll

    private fun toggleAutoScroll() {
        setAutoScroll(!autoScrolling)
    }

    /** One place that turns auto-scroll on or off, so the sheet and the menu agree. */
    private fun setAutoScroll(enabled: Boolean) {
        if (enabled) startAutoScroll() else stopAutoScroll()
    }

    private fun startAutoScroll() {
        if (source == null || autoScrolling) return
        autoScrolling = true
        autoClock.reset()
        autoPage = shownItem()
        autoLastFrame = SystemClock.uptimeMillis()
        autoDelay = FRAME_MS
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (barsVisible) toggleBars()
        handler.post(autoTick)
        invalidateOptionsMenu()
    }

    private fun stopAutoScroll() {
        if (!autoScrolling) return
        autoScrolling = false
        handler.removeCallbacks(autoTick)
        autoProgress.visibility = View.GONE
        applyKeepScreenOn()
        invalidateOptionsMenu()
    }

    /**
     * One tick of auto-scroll at [Prefs.autoSpeed]: a strip scrolls on every frame; a page
     * turns once its time is up, a thin bar along the bottom edge filling meanwhile, a few
     * times a second ([AutoScrollClock.nextTickMs]) so the still page is not redrawn every
     * frame. A page turned by hand gets its full time.
     */
    private val autoTick = object : Runnable {
        override fun run() {
            if (!autoScrolling) return
            val now = SystemClock.uptimeMillis()
            // A stalled main thread does not jump the run ahead.
            val dt = (now - autoLastFrame).coerceAtMost(autoDelay + MAX_LATE_MS)
            autoLastFrame = now
            val speed = prefs.autoSpeed
            if (mode == MODE_PAGED) {
                val count = source?.pageCount ?: return
                if (shownItem() != autoPage) {
                    autoClock.reset()
                    autoPage = shownItem()
                }
                if (autoClock.turn(dt, speed)) {
                    val next = shownItem() + 1
                    if (next >= layout.itemCount(count)) {
                        finishAutoScroll()
                        return
                    }
                    goToItem(next)
                    autoPage = shownItem()
                }
                showAutoProgress(autoClock.progress(speed))
                autoDelay = autoClock.nextTickMs(speed, autoProgress.width)
            } else {
                autoProgress.visibility = View.GONE
                val px = autoClock.scroll(dt, speed, vertical.height)
                if (px > 0) vertical.scrollBy(0, px)
                if (!vertical.canScrollVertically(1)) {
                    finishAutoScroll()
                    return
                }
                autoDelay = FRAME_MS
            }
            handler.postDelayed(this, autoDelay)
        }
    }

    /** Fills [fraction] of the bottom edge, from where the reading direction starts. */
    private fun showAutoProgress(fraction: Float) {
        autoProgress.pivotX = if (readingDir == "rtl") autoProgress.width.toFloat() else 0f
        autoProgress.scaleX = fraction
        autoProgress.visibility = View.VISIBLE
    }

    /**
     * Ends an auto-scroll run at the end of a chapter: with "auto next chapter" on
     * the reading keeps going in the next one ([setup] restarts it there), otherwise
     * it simply stops.
     */
    private fun finishAutoScroll() {
        val next = nextChapter()
        if (prefs.autoNext && next != null) {
            keepAutoScroll = true
            openChapter(next, 0)
        } else {
            stopAutoScroll()
        }
    }

    private fun setAutoCrop(enabled: Boolean) {
        if (autoCrop == enabled) return
        autoCrop = enabled
        persistLayout()
        invalidateOptionsMenu()
        attach()
    }

    /**
     * When on, reaching the end of a chapter opens the next one directly instead of
     * offering the end-of-chapter prompt. In the strip modes the next chapter is
     * also opened right away and laid in behind this one, so reading never stops at
     * the boundary in the first place.
     */
    private fun setAutoNext(enabled: Boolean) {
        if (prefs.autoNext == enabled) return
        prefs.autoNext = enabled
        if (enabled) prepareStripAhead() else dropStripAhead()
        invalidateOptionsMenu()
        updateEndCard()
    }

    /** True while the reader should skip the end card and roll into the next chapter. */
    private fun autoAdvance() = prefs.autoNext && nextChapter() != null

    /**
     * Watches for a swipe aimed past the end of the chapter. Paged reading clamps
     * at the last page, so without this the "auto next chapter" setting would have
     * nothing to act on; swiping there now just rolls into the next chapter. Strips
     * read downward, so their version of the gesture is a flick upward — and it only
     * has to catch the gap before the next chapter's pages have been laid in.
     */
    private val overSwipeListener = object : RecyclerView.OnItemTouchListener {
        private var startX = 0f
        private var startY = 0f
        private var tracking = false

        /** True when the swipe began on the chapter's first page, where swiping back counts. */
        private var fromStart = false

        override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = e.x
                    startY = e.y
                    tracking = true
                    fromStart = atChapterStart()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val dx = e.x - startX
                    val dy = e.y - startY
                    val strip = mode != MODE_PAGED
                    // "Forward" is leftward in a paged book (rightward in RTL) and
                    // downward in the finger, i.e. a flick up, for a strip.
                    val along = if (strip) -dy else if (readingDir == "rtl") dx else -dx
                    val across = if (strip) abs(dx) else abs(dy)
                    if (tracking && along > across * 2 && along > swipeMinPx() &&
                        autoAdvance() && atChapterEnd()
                    ) {
                        openNextChapter()
                    }
                    // Backward: rightward in a paged book (leftward in RTL), a flick down for a strip.
                    // A zoomed page panned on past its edge hands the drag back, pan included.
                    if (tracking && e.actionMasked == MotionEvent.ACTION_UP && -along > across * 2 &&
                        -along > swipeMinPx() && fromStart && atChapterStart() && !pannedAt(rv, startX, startY)
                    ) {
                        backAtStart()
                    }
                    tracking = false
                }
            }
            // Observing only: never intercept, or the page itself could not be paged.
            return false
        }

        override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) = Unit

        override fun onRequestDisallowInterceptTouchEvent(disallow: Boolean) = Unit
    }

    /** True when the page under ([x], [y]) in [rv] was panned during the current gesture. */
    private fun pannedAt(rv: RecyclerView, x: Float, y: Float): Boolean =
        rv.findChildViewUnder(x, y)?.let { anyPanned(it) } == true

    private fun anyPanned(v: View): Boolean = when (v) {
        is ZoomImageView -> v.panned
        is ViewGroup -> (0 until v.childCount).any { anyPanned(v.getChildAt(it)) }
        else -> false
    }

    /** True when the reader sits on the last page or at the bottom of a strip. */
    private fun atChapterEnd(): Boolean {
        val src = source ?: return false
        return when (mode) {
            MODE_PAGED -> layout.isLastItem(shownItem(), src.pageCount)
            else -> !vertical.canScrollVertically(1)
        }
    }

    /** True when the reader sits on the first page or at the top of a strip. */
    private fun atChapterStart(): Boolean {
        if (source == null) return false
        return when (mode) {
            MODE_PAGED -> shownItem() == 0
            else -> !vertical.canScrollVertically(-1)
        }
    }

    private fun swipeMinPx(): Int = (resources.displayMetrics.widthPixels / 8f).toInt()

    // ------------------------------------------------------------------ chapter drawer

    /** The chapters of [src]: [Chapters.of] its page paths. */
    private fun chaptersOf(src: PageSource): List<Chapter> =
        Chapters.of(List(src.pageCount) { src.pageName(it) })

    /**
     * The chapter drawer from the start edge: the open book's chapters, then the files in
     * its folder, the current ones highlighted. A chapter goes to its first page, a file
     * opens. Opened from the toolbar or the menu, as an edge swipe would clash with the
     * system back gesture.
     */
    private fun showChapterDrawer() {
        val rows = ChapterDrawer.rows(bookChapters, currentIndex, chapters, file?.absolutePath, library.readSet())
        if (rows.isEmpty()) return
        val view = layoutInflater.inflate(R.layout.sheet_chapters, null)
        val dialog = SideSheetDialog(this)
        val width = minOf(
            resources.getDimensionPixelSize(R.dimen.chapter_drawer_width),
            (resources.displayMetrics.widthPixels * 0.85f).toInt()
        )
        dialog.setContentView(view, ViewGroup.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT))
        dialog.setSheetEdge(Gravity.START)
        view.findViewById<TextView>(R.id.chapters_title).text = file?.let { chapterLabel(it) }
        val current = ChapterDrawer.currentRow(rows)
        val rv = view.findViewById<RecyclerView>(R.id.chapters_list)
        val lm = LinearLayoutManager(this)
        rv.layoutManager = lm
        rv.adapter = ChapterAdapter(
            rows,
            onChapter = { chapter ->
                dialog.dismiss()
                goTo(chapter.first)
            },
            onFile = { f ->
                dialog.dismiss()
                if (f.absolutePath != file?.absolutePath) openChapter(f, 0)
            }
        )
        // Two rows of context above the current chapter.
        if (current > 2) lm.scrollToPositionWithOffset(current - 2, 0)
        dialog.show()
    }

    // ------------------------------------------------------------------ book info

    /**
     * Book info: the file, its format (from its leading bytes), size and pages, the
     * direction on screen with where it comes from, and every ComicInfo field.
     */
    private fun showBookInfo() {
        if (bookInfoJob?.isActive == true) return
        val f = file ?: return
        val src = source ?: return
        val pages = src.pageCount
        val direction = directionInfo()
        val known = bookComicInfo?.takeIf { it.first === src }
        progress.visibility = View.VISIBLE
        bookInfoJob = lifecycleScope.launch {
            val facts = try {
                withContext(AppDispatchers.io) {
                    val kind = runCatching { ArchiveKind.detect(f) }.getOrNull()
                    // Through the open book: no second handle, and a solid archive's ComicInfo is read once.
                    val info = if (known != null) known.second else runCatching { src.comicInfo() }.getOrNull()
                    Triple(kind, info, f.length())
                }
            } finally {
                if (source === src) progress.visibility = View.GONE
            }
            val (kind, info, size) = facts
            if (isFinishing || source !== src) return@launch
            bookComicInfo = src to info
            val text = SpannableStringBuilder()
            fun row(label: Int, value: String) {
                if (text.isNotEmpty()) text.append("\n\n")
                val start = text.length
                text.append(getString(label))
                text.setSpan(StyleSpan(Typeface.BOLD), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                text.append('\n').append(value)
            }
            row(R.string.info_file, f.name)
            f.parent?.let { row(R.string.info_folder, it) }
            row(R.string.info_format, BookInfo.format(f.name, kind, getString(R.string.info_format_mismatch)))
            row(R.string.info_size, FileUtils.formatSize(this@ViewerActivity, size))
            row(R.string.info_pages, pages.toString())
            row(R.string.menu_reading_dir, direction)
            val fields = info?.fields.orEmpty()
            if (fields.isNotEmpty()) row(R.string.info_comic_info, fields.joinToString("\n") { (k, v) -> "$k: $v" })
            AlertDialog.Builder(this@ViewerActivity)
                .setTitle(chapterLabel(f))
                .setMessage(text)
                .setPositiveButton(R.string.close, null)
                .show()
        }
    }

    // ------------------------------------------------------------------ share / save page

    private fun fileSafeName(page: Int): String {
        val base = file?.let { chapterLabel(it) } ?: "page"
        return base.replace(Regex("[^A-Za-z0-9._-]"), "_").take(60) + "_p" + (page + 1)
    }

    /**
     * Page ▸ actions for [page], from a long-press on it: share, save, crop, and rotating
     * the screen.
     */
    private fun showPageActions(page: Int) {
        if (source == null) return
        val actions = listOf<Pair<Int, () -> Unit>>(
            R.string.menu_share_page to { exportPage(share = true, page = page) },
            R.string.menu_save_page to { exportPage(share = false, page = page) },
            R.string.menu_crop to { startCrop(page) },
            R.string.menu_rotate to { cycleOrientation() }
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.page_actions_title, page + 1))
            .setItems(actions.map { getString(it.first) }.toTypedArray()) { _, which -> actions[which].second() }
            .show()
    }

    /** Renders [page] at full resolution and either shares or saves it. */
    private fun exportPage(share: Boolean, page: Int = currentIndex) {
        val src = source ?: return
        val name = fileSafeName(page)
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val outcome = withContext(AppDispatchers.io) {
                runCatching {
                    val bitmap = src.loadPage(page, Target(EXPORT_W, EXPORT_H, Fit.SHRINK))
                    if (share) shareUri(bitmap, name) else {
                        savePicture(bitmap, name)
                        null
                    }
                }
            }
            progress.visibility = View.GONE
            val uri = outcome.getOrNull()
            when {
                outcome.isFailure -> toast(R.string.error_load)
                share && uri != null -> startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND)
                            .setType("image/png")
                            .putExtra(Intent.EXTRA_STREAM, uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                        getString(R.string.menu_share_page)
                    )
                )
                else -> toast(R.string.page_saved)
            }
        }
    }

    /** Writes [bitmap] under cache/shared (exposed through the FileProvider) and returns its URI. */
    private fun shareUri(bitmap: Bitmap, name: String): android.net.Uri {
        val dir = File(cacheDir, SHARE_DIR)
        dir.mkdirs()
        dir.listFiles()?.forEach { runCatching { it.delete() } } // keep only the latest shared page
        val out = File(dir, "$name.png")
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return FileProvider.getUriForFile(this, "$packageName.files", out)
    }

    private fun savePicture(bitmap: Bitmap, name: String) {
        val stamp = System.currentTimeMillis()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "${name}_$stamp.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Emanata")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw java.io.IOException("Could not create the picture")
            try {
                resolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            } catch (t: Throwable) {
                resolver.delete(uri, null, null)
                throw t
            }
        } else {
            val dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: File(cacheDir, "pictures")
            dir.mkdirs()
            File(dir, "${name}_$stamp.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    // ------------------------------------------------------------------ bookmarks

    private fun toggleBookmark() {
        val f = file ?: return
        val path = f.absolutePath
        if (library.isBookmarked(path, currentIndex)) {
            library.removeBookmark(path, currentIndex)
            toast(R.string.bookmark_removed)
        } else {
            library.addBookmark(Bookmark(path, f.name, currentIndex, "", System.currentTimeMillis()))
            toast(R.string.bookmark_saved)
        }
        invalidateOptionsMenu()
    }

    private fun showBookmarks() {
        val f = file ?: return
        val marks = library.bookmarksFor(f.absolutePath)
        if (marks.isEmpty()) {
            toast(R.string.no_bookmarks_in_book)
            return
        }
        val count = source?.pageCount ?: 0
        val labels = marks.map { getString(R.string.page_indicator, it.page + 1, count) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_bookmark_list)
            .setItems(labels) { _, which -> goTo(marks[which].page) }
            .setNeutralButton(R.string.clear) { _, _ ->
                val message = getString(R.string.confirm_clear_book_bookmarks_msg, f.name)
                confirm(this, R.string.confirm_clear_book_bookmarks_title, message, R.string.clear) {
                    marks.forEach { library.removeBookmark(f.absolutePath, it.page) }
                    invalidateOptionsMenu()
                }
            }
            .show()
    }

    private fun showJumpDialog() {
        val count = source?.pageCount ?: return
        val input = EditText(this)
        input.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        input.hint = getString(R.string.jump_hint)
        val container = LinearLayout(this).apply {
            setPadding(48, 24, 48, 0)
            addView(
                input,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_jump)
            .setView(container)
            .setPositiveButton(R.string.ok) { _, _ ->
                val n = input.text.toString().toIntOrNull() ?: return@setPositiveButton
                if (itemNumbers()) goToItem((n - 1).coerceIn(0, layout.itemCount(count) - 1))
                else goTo((n - 1).coerceIn(0, count - 1))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------------ crop / pages

    private fun openPages() {
        val f = file ?: return
        pagesLauncher.launch(
            Intent(this, PagesActivity::class.java)
                .putExtra(PagesActivity.EXTRA_PATH, f.absolutePath)
                .putExtra(PagesActivity.EXTRA_PAGE, currentIndex)
        )
    }

    private fun startCrop(page: Int = currentIndex) {
        val src = source ?: return
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val bitmap = withContext(AppDispatchers.io) {
                try {
                    src.loadPage(page, Target(EXPORT_W, EXPORT_H, Fit.SHRINK))
                } catch (t: Throwable) {
                    null
                }
            }
            val outFile = if (bitmap == null) null else withContext(AppDispatchers.io) {
                runCatching {
                    val out = File(cacheDir, "crop_src.png")
                    out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    out
                }.getOrNull()
            }
            progress.visibility = View.GONE
            if (outFile == null) {
                toast(R.string.error_load)
                return@launch
            }
            cropSource = outFile
            cropLauncher.launch(
                Intent(this@ViewerActivity, CropActivity::class.java)
                    .putExtra(CropActivity.EXTRA_PATH, outFile.absolutePath)
            )
        }
    }

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    companion object {
        const val EXTRA_PATH = "extra_path"
        const val EXTRA_PAGE = "extra_page"

        private const val STATE_PATH = "state_path"
        private const val STATE_PAGE = "state_page"
        private const val STATE_ROTATION = "state_rotation"

        /** How long [learnSize] gathers sizes before laying the book out again. */
        private const val LEARN_DELAY_MS = 300L

        /** A start page past any book's end: opens it at its last page. */
        private const val LAST_PAGE = Int.MAX_VALUE

        /** How long the detected-direction note shows on open. */
        private const val DETECTION_NOTE_MS = 3_000L

        /** The sheet's "Auto" direction: none of the folder's own. */
        private const val DIR_AUTO = "auto"

        private const val SHARE_DIR = "shared"
        private const val EXPORT_W = 2400
        private const val EXPORT_H = 3200
        private const val MAX_SESSION_SECONDS = 4L * 3600
        private const val FRAME_MS = 16L

        /** How much later than posted an auto-scroll tick may count. */
        private const val MAX_LATE_MS = 100L

        /** How long, as a share of the screen height, a brightness drag holds at 0. */
        private const val BRIGHTNESS_HOLD = 0.15f

        private const val MODE_PAGED = ReaderLayout.MODE_PAGED
        private const val MODE_VERTICAL = ReaderLayout.MODE_VERTICAL
        private const val MODE_LONGSTRIP = ReaderLayout.MODE_LONGSTRIP
    }
}
