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
import android.view.Choreographer
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.sidesheet.SideSheetDialog
import com.google.android.material.slider.Slider
import com.infinity.comicreader.R
import com.infinity.comicreader.ai.AiChain
import com.infinity.comicreader.ai.AiPicks
import com.infinity.comicreader.ai.AiStep
import com.infinity.comicreader.ai.DownloadState
import com.infinity.comicreader.ai.Downloads
import com.infinity.comicreader.ai.ModelCatalog
import com.infinity.comicreader.ai.ModelDownloads
import com.infinity.comicreader.ai.ModelStore
import com.infinity.comicreader.ai.ReaderAi
import com.infinity.comicreader.data.BookFactsCache
import com.infinity.comicreader.data.BookId
import com.infinity.comicreader.data.BookIds
import com.infinity.comicreader.data.BookRef
import com.infinity.comicreader.data.Bookmark
import com.infinity.comicreader.data.CopyMatch
import com.infinity.comicreader.data.EntryType
import com.infinity.comicreader.data.Crumbs
import com.infinity.comicreader.data.FolderLayout
import com.infinity.comicreader.data.FolderReads
import com.infinity.comicreader.data.HistoryItem
import com.infinity.comicreader.data.Library
import com.infinity.comicreader.data.Likeness
import com.infinity.comicreader.data.LikenessStore
import com.infinity.comicreader.data.OpenBooks
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.ResumeMode
import com.infinity.comicreader.data.ReaderLayout
import com.infinity.comicreader.data.ReaderSettings
import com.infinity.comicreader.data.RemovalNotices
import com.infinity.comicreader.data.SettingsRepository
import com.infinity.comicreader.data.Thrift
import com.infinity.comicreader.data.SpreadDisplay
import com.infinity.comicreader.data.StorageRoot
import com.infinity.comicreader.data.StorageRoots
import com.infinity.comicreader.net.RemoteCache
import com.infinity.comicreader.net.RemoteException
import com.infinity.comicreader.net.RemoteFile
import com.infinity.comicreader.net.Remotes
import com.infinity.comicreader.data.TwoPage
import com.infinity.comicreader.data.Thumbs
import com.infinity.comicreader.reader.Adjustments
import com.infinity.comicreader.reader.ArchiveKind
import com.infinity.comicreader.reader.AutoScroll
import com.infinity.comicreader.reader.AutoScrollClock
import com.infinity.comicreader.reader.BookDirection
import com.infinity.comicreader.reader.BookFactsReader
import com.infinity.comicreader.reader.Calibrator
import com.infinity.comicreader.reader.Chapter
import com.infinity.comicreader.reader.Chapters
import com.infinity.comicreader.reader.ComicInfo
import com.infinity.comicreader.reader.Decoders
import com.infinity.comicreader.reader.DirectionDetection
import com.infinity.comicreader.reader.Filters
import com.infinity.comicreader.reader.Fit
import com.infinity.comicreader.reader.Half
import com.infinity.comicreader.reader.HiddenPages
import com.infinity.comicreader.reader.HidingSource
import com.infinity.comicreader.reader.NestedArchives
import com.infinity.comicreader.reader.PageSeams
import com.infinity.comicreader.reader.PageSource
import com.infinity.comicreader.reader.PageSizes
import com.infinity.comicreader.reader.PageSources
import com.infinity.comicreader.reader.PreviewStore
import com.infinity.comicreader.reader.Slices
import com.infinity.comicreader.reader.SolidArchives
import com.infinity.comicreader.reader.Spreads
import com.infinity.comicreader.reader.StoredPages
import com.infinity.comicreader.reader.TallPages
import com.infinity.comicreader.reader.TapZones
import com.infinity.comicreader.reader.Target
import com.infinity.comicreader.util.AppDispatchers
import com.infinity.comicreader.util.FileUtils
import com.infinity.comicreader.util.Formats
import com.infinity.comicreader.util.NaturalOrder
import com.infinity.comicreader.util.ReadProbes
import com.infinity.comicreader.util.Traces
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.BitSet
import java.util.concurrent.Executors
import java.util.stream.Collectors
import kotlin.math.abs

/**
 * The reader.
 *
 * Pages are laid out paged (swiped sideways, or up when reading top to bottom) or in
 * one continuous column (top to bottom only), and every
 * reader option (layout, fit, filter, spacing, direction, two-page, night filter,
 * keep-on, fullscreen) is read from and written back to [Prefs], so the choices
 * survive chapter changes and app restarts. Color adjustments are per book, in
 * [Library], falling back to the defaults in [Prefs]. Changes made elsewhere,
 * such as in the settings screen, apply live through [SettingsRepository].
 */
class ViewerActivity : AppCompatActivity(), Calibrator.Reading {

    private lateinit var prefs: Prefs
    private lateinit var library: Library
    private lateinit var settings: SettingsRepository

    /** AI processing of the pages shown ([PageAdapter.reading]); its models stay open until the reader closes. */
    private lateinit var readerAi: ReaderAi

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

    /** How much of a book on a share is fetched, below [progress]. */
    private lateinit var fetched: TextView

    /** "Waiting for network location…", while a book on a share or its page on screen shows nothing ([updateShareWait]). */
    private lateinit var shareWait: View

    /** [shareWait]'s spinner, hidden while [progress] spins. */
    private lateinit var shareWaitSpinner: View

    /**
     * The reader's own requests to shares, opening books and reading their pages; [shareWait]
     * heeds only these. Scans of the open book count too: a page shown reads through the same
     * channel, which waits for a scan's read to finish.
     */
    private val asker = Remotes.Asker()
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

    /** True once the open preview strip is scrolled by hand: it is no longer centered for the reader. */
    private var seekPanelScrolled = false

    /** Back closes the preview strip while it is open. */
    private val seekBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = closeSeekPanel()
    }

    /** The page [repeatedBack] is counting on; another page starts the count over. */
    private var backPage = -1

    private var source: PageSource? = null
    private var adapter: PageAdapter? = null

    /** The book open; a rebuild leaves it alone meanwhile ([OpenBooks]). */
    private var file: File? = null
        set(value) {
            val was = field?.absolutePath
            val now = value?.absolutePath
            if (now != was) {
                was?.let(OpenBooks::closed)
                now?.let(OpenBooks::opened)
            }
            field = value
        }

    /** The open book's [BookId], as known when it began to open, then as found; null while unknown or unreadable. */
    private var bookId: BookId? = null

    /** Takes the open book's likeness and offers a book it may be a copy of ([noteLikeness]). */
    private var likenessJob: Job? = null

    /** The open book as the library keeps it; null while its ID is unknown. */
    private fun book(): BookRef? = bookId?.let { id -> file?.let { BookRef(id, it.absolutePath) } }

    /** [book], or null, saying nothing can be kept for it, while its ID is unknown. */
    private fun keptBook(): BookRef? = book() ?: run {
        if (file != null) toast(R.string.book_id_unknown)
        null
    }

    /**
     * The next chapter, opened ahead so a continuous reader rolls
     * straight into it instead of stopping at the chapter's end. While it is
     * pending it is simply appended to the strip ([stripAheadFile] tracks the
     * chapter it belongs to, since [file] still points at the one being read).
     */
    private var stripAhead: PageSource? = null
    private var stripAheadFile: File? = null

    /** [stripAheadFile]'s [BookId], found as it opened; null when unreadable. */
    private var stripAheadId: BookId? = null
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

    /** Where the open book's page sizes and seams are stored, as [pageNames] counts its pages. */
    private var pageStore: StoredPages? = null
    private var pageSizes: Array<IntArray?> = emptyArray()

    /** What the spread engine found in the open book's pages; null until the book is open. */
    private var analysis: Spreads.Analysis? = null

    /** What auto-crop found on the open book's pages, for pairs to share; kept across adapters. */
    private var pageBounds = PageBounds()

    /** Reads the open book's missing page sizes in the background ([scanSizes]). */
    private var sizesJob: Job? = null

    /** The sizes the last [scanSizes] read, and of which source: kept when it stops early. */
    private var sizesFound: Pair<PageSource, Array<IntArray?>>? = null

    /** Lays the book out again with the sizes pages left as they loaded ([learnSize]); [learnRound] numbers its rounds. */
    private var learnJob: Job? = null
    private var learnRound = 0

    /** True when the open book's two-page pairing is shifted by one page; [shiftedByNames] from before seams. */
    private var shifted = false
    private var shiftedByNames = false

    /** True when the open book's seams are never checked, nor pair, merge or join its pages. */
    private var ignoreSeams = false

    /** The seams between the open book's pages checked so far, and the job checking more ([checkSeams]). */
    private var pageSeams = PageSeams.Found(0)
    private var seamsJob: Job? = null

    /** The seams the layout reads: [pageSeams] as they were when last shown ([relayout]). */
    private var shownSeams = PageSeams.Found(0)

    /** True until the reader turns a page of the book just opened: until then seams may re-pair the page on screen. */
    private var seamsSettling = true

    /**
     * Pages of the open book the reader has had on screen, in this and earlier sessions
     * ([StoredPages.readSeen]): a lone seam match near one may settle its run.
     */
    private val visitedPages = BitSet()

    /** [visitedPages] counted when last stored, and the job storing them a while after the last new one ([visit]). */
    private var storedVisits = 0
    private var visitsJob: Job? = null

    /** True once the open book's seams count ([seamsUsed]), or it has seams or pages seen stored: only then are pages seen stored. */
    private var keepVisits = false

    /** [visitedPages] counted when the layout was last made from them. */
    private var shownVisits = 0

    /** True when page sizes changed since the layout was made ([relayout]). */
    private var layoutStale = false

    /** The page the open book first showed, -1 until it shows. */
    private var openedPage = -1

    /** Holds the screen in landscape while a wide page shows whole ("Rotate for spreads"). */
    private val spreadRotation = SpreadRotation()

    /** Tells [spreadRotation] when the sensor knows the way up; made when first needed ([upright]). */
    private var uprightWatch: UprightWatch? = null

    /** True between [onStart] and [onStop]. */
    private var started = false

    /** The layout on screen: [chosenMode], unless the direction pages or detected slices run on ([shownMode]). */
    private var mode = MODE_PAGED

    /** The layout the settings or the folder chose, kept as the default while the direction overrides it. */
    private var chosenMode = MODE_PAGED

    /** True once a layout is picked for the open book, so detected slices no longer make it continuous. */
    private var layoutPicked = false
    private var twoPage = TwoPage.OFF

    /** Whether the adapter on screen pairs pages; [useSpread] says whether it should. */
    private var spread = false

    /** Whether the adapter on screen auto-crops; [cropOn] says whether it should. */
    private var shownCrop = false
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

    /** What the open book's folder keeps of the layout, or the book itself ([layoutOwn]); null when it follows the defaults. */
    private var folderLayout: FolderLayout? = null

    /** True while [folderLayout] is the open book's own, from a folder packed into it: kept for the book, not its folder. */
    private var layoutOwn = false

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

    /**
     * Top to bottom when the open book's pages are slices of one strip ([Slices]), found on
     * open or after it ([detectSlicesLater]). It outranks [detection], and shows them in one
     * continuous column, also in a folder set to top to bottom.
     */
    private var slices: DirectionDetection.Detected? = null

    /** True once the open book's pages were checked for slices, or are being. */
    private var slicesChecked = false

    /**
     * True while the open book reads as one continuous strip top to bottom because its pages
     * are mostly tall and the reader said so ([askTallStrip]), unless its folder keeps a
     * layout or direction.
     */
    private var tallStrip = false

    /** True once the open book was judged for tall pages, or its answer was kept: it is asked at most once. */
    private var tallJudged = false

    /** The open book's tall pages ([TallPages]), by index, as its known sizes tell; paged reading shows them at fit width. */
    private var tallPages: Set<Int> = emptySet()

    /** Reads the sizes of images beside a loose image, to judge its folder ([judgeTall]). */
    private var tallJob: Job? = null

    /** The question whether to read the open book as a strip, while it shows. */
    private var tallAsk: AlertDialog? = null

    /** Checks whether the open book is a solid archive worth repacking ([offerRepack]). */
    private var repackJob: Job? = null

    /** The offer to repack the open book as CBZ, while it shows. */
    private var repackAsk: AlertDialog? = null

    /** Checks the open book's seams, or sizes not known on open, for slices after it shows. */
    private var slicesJob: Job? = null

    /**
     * True once nothing opening the book started still runs: the open, what it left for
     * later, and page decodes. Tests wait for it so no late result lands mid-run.
     */
    internal fun openSettled(): Boolean =
        listOf(openJob, lateDetectionJob, likenessJob, tallJob, repackJob, slicesJob, sizesJob, seamsJob, learnJob, bookInfoJob).none { it?.isActive == true } &&
            adapter?.decoding != true

    /** True while the open book's scans (sizes, seams, slices) or a page decode run; for benchmarks. */
    internal val busy: Boolean
        get() = sizesJob?.isActive == true || seamsJob?.isActive == true || slicesJob?.isActive == true || adapter?.decoding == true

    /**
     * True once a page of the open book showed: scans of its sizes and seams wait until then
     * ([startScans]), so they never compete with the first page's read and decode.
     */
    private var scansReleased = false

    /** Starts the open book's scans should no page show within [SCAN_HOLD_MS]. */
    private val releaseScans = Runnable { startScans() }

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
        readerAi = ReaderAi.of(this)
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
                if (state == ViewPager2.SCROLL_STATE_DRAGGING) {
                    stopAutoScroll()
                    // A swipe lands on a tall page as taps and keys do, back at its bottom: set before it slides in.
                    if (mode == MODE_PAGED) adapter?.swipingFrom(pager.currentItem)
                }
                if (state == ViewPager2.SCROLL_STATE_IDLE && mode == MODE_PAGED) {
                    adapter?.settled()
                    // Seams held back while the reader was on their pages may show now.
                    pager.post { relayout() }
                }
            }

            override fun onPageSelected(position: Int) {
                if (mode != MODE_PAGED) return
                adapter?.turnedTo(position, back = position < currentItem)
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
                if (mode == MODE_CONTINUOUS) updateEndCard()
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

    /**
     * Reads the open book's file names in [picked] (null: the setting) from now on, and
     * opens it again at the page on screen, wherever the new names put it ([NameCharsetDialog.switch]).
     */
    private fun switchCharset(picked: String?) {
        val book = favoriteFile() ?: return
        val opened = source != null
        stopAutoScroll()
        flushStats()
        saveHistory()
        openJob?.cancel()
        closeSource()
        endDismissed = false
        hideEndCard()
        progress.visibility = View.VISIBLE
        openJob = lifecycleScope.launch {
            withContext(AppDispatchers.io) { NameCharsetDialog.switch(library, factsCache, book, prefs.charset, picked, cacheDir) }
            // The history holds the page that was on screen, moved with the pages.
            if (opened) intent.putExtra(EXTRA_PAGE, bookId?.let { library.pageFor(it) } ?: 0)
            open()
        }
    }

    /**
     * Leaves the book pages [hidden] out of the open book and opens it again at the page
     * on screen, or the next one shown when that one is hidden. Its history and
     * bookmarks follow the new numbering ([Library.setHiddenPages]).
     */
    private fun applyHidden(hidden: Set<Int>) {
        val ref = book() ?: return
        val src = source ?: return
        val count = HiddenPages.bookCount(src)
        val page = HiddenPages.bookIndex(src, currentIndex.coerceIn(0, src.pageCount - 1))
        stopAutoScroll()
        flushStats()
        saveHistory()
        library.setHiddenPages(ref, hidden, count)
        val now = library.hiddenPages(ref.id)
        val shown = HiddenPages.shown(count, now).size
        intent.putExtra(EXTRA_PAGE, HiddenPages.toShown(page, now).coerceIn(0, shown - 1))
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
        updateShareWait()
        started = true
        watchUpright()
        adapter?.resumeAi()
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
        storeVisits()
        handler.removeCallbacks(checkShareWait)
        started = false
        watchUpright()
        // Out of sight, pages are neither processed nor shown.
        adapter?.pauseAi()
    }

    /**
     * Gives up the zoom's full-detail pages, the largest memory the reader holds, once the
     * UI hides, and before API 34 also once memory runs low. From API 34 only the UI-hidden
     * and background levels arrive, so in the foreground the detail budget alone bounds them.
     * Hidden, the AI models close too, freeing their native and GPU memory.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        @Suppress("DEPRECATION")
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) adapter?.trimMemory()
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) readerAi.closeModels()
    }

    override fun onDestroy() {
        super.onDestroy()
        storageWatcher.stop()
        handler.removeCallbacks(hideHint)
        hint.animate().cancel()
        handler.removeCallbacks(hideBrightness)
        brightnessPill.animate().cancel()
        handler.removeCallbacks(seekHoldUp)
        handler.removeCallbacks(checkShareWait)
        handler.removeCallbacks(releaseScans)
        handler.removeCallbacks(followDownloads)
        stopAutoScroll()
        openJob?.cancel()
        closeSource()
        deleteCropSource()
        readerAi.close()
        file = null
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
        fetched = findViewById(R.id.viewer_fetched)
        shareWait = findViewById(R.id.viewer_share_wait)
        shareWaitSpinner = findViewById(R.id.viewer_share_wait_spinner)
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
        seekPanel.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) seekPanelScrolled = true
            }
        })
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
        if (s.joinSpreads != old.joinSpreads) {
            applyJoinSpreads()
            shown = true
        }
        if (s.tallThreshold != old.tallThreshold && source != null) {
            updateTallPages()
            relayout()
        }
        if (s.fullscreen != old.fullscreen) {
            applyFullscreen(s.fullscreen)
            shown = true
        }

        // Built into the page adapter.
        if (s.doubleTapZoom != old.doubleTapZoom || s.brightnessGesture != old.brightnessGesture || s.fling != old.fling ||
            s.linearLight != old.linearLight || s.pageScaling != old.pageScaling || s.zoomScaling != old.zoomScaling ||
            s.thrift.readAhead(s.preload, pagesPerScreen()) != old.thrift.readAhead(old.preload, pagesPerScreen())
        ) {
            rebuild = true
        }
        if (s.ai != old.ai) aiChanged()
        // Pages put off for battery saver go on at once once it lets them.
        if (old.thrift.pausedAi && !s.thrift.pausedAi) adapter?.aiWake()
        if (s.thrift.pausedScans != old.thrift.pausedScans) {
            // Paused, the scans under way stop; resumed, they go on from the page on screen.
            if (s.thrift.pausedScans) {
                sizesJob?.cancel()
                seamsJob?.cancel()
                seamsJob = null
            } else {
                val src = source
                val book = file
                if (src != null && book != null) scanSizes(book, src)
            }
            checkSeams()
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
        // Built into the seek strip's previews.
        if (s.thumbScaling != old.thumbScaling || s.linearLight != old.linearLight) dropSeekPreviews()
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
            // Only a continuous column lays the next chapter in behind this one.
            if (mode == MODE_PAGED) dropStripAhead()
        }
        if (fit != old.fit) adapter?.fitMode = effectiveFit()
        if (spacingDp != old.spacing) adapter?.spacingPx = spacingPx()
        return mode != old.mode || readingDir != old.dir || useSpread() != spread || cropOn() != shownCrop ||
            spreadDisplay != old.spreads
    }

    private fun resolveLayout() {
        val own = if (layoutFolder() != null) bookId?.let { library.bookLayout(it) } else null
        layoutOwn = own != null
        folderLayout = own ?: layoutFolder()?.let { library.folderLayout(it) }
        val defaults = ReaderLayout.of(prefs)
        showLayout(folderLayout?.over(defaults) ?: defaults)
        applyDetection()
    }

    /** The layout the reader shows, its direction perhaps the detected one. */
    private fun currentLayout() = ReaderLayout(mode, readingDir, fit, twoPage, autoCrop, spacingDp, spreadDisplay)

    private fun showLayout(l: ReaderLayout) {
        chosenMode = ReaderLayout.modeOf(l.mode)
        readingDir = ReaderLayout.dirOf(l.dir)
        mode = shownMode()
        fit = l.fit
        twoPage = l.twoPage
        autoCrop = l.autoCrop
        spacingDp = l.spacing
        spreadDisplay = l.spreads
    }

    /**
     * The layout for [chosenMode] and the direction on screen: paged unless reading top to
     * bottom, and continuous for detected slices of one strip, unless the folder keeps a
     * whole layout or a layout was picked for the book.
     */
    private fun shownMode(): String = ReaderLayout.shownMode(chosenMode, readingDir, sliced() || tallStripOn())

    /** True while the open book reads as a strip for its tall pages: the folder keeps no layout or direction of its own. */
    private fun tallStripOn() = tallStrip && !folderScope() && !folderDirection()

    /** True while detected slices may lay the book out in one column: no frozen folder layout, no layout picked. */
    private fun sliced() = slices != null && prefs.detectDirection && !folderScope() && !layoutPicked

    /** True while the column on screen shows detected slices, which join only uncropped. */
    private fun slicesShown() = mode == MODE_CONTINUOUS && readingDir == DIR_TTB && sliced()

    /** Auto-crop as shown: off for detected slices, whose blank bands and widths belong to the strip. */
    private fun cropOn() = autoCrop && !slicesShown()

    /** What decides the detected direction: slices of one strip first, then the book's contents. */
    private fun found(): DirectionDetection.Detected? = slices ?: detection

    /**
     * True when the open book's pages may be checked for slices: detection is on, and the
     * folder keeps neither a whole layout nor a direction other than top to bottom.
     */
    private fun maySlice() = prefs.detectDirection && !folderScope() && (!folderDirection() || folderLayout?.dir == DIR_TTB)

    /** True while pages read top to bottom, paged or continuous. */
    private fun topToBottom() = readingDir == DIR_TTB

    /** The folder whose layout the open book follows; none with "Per-folder layout" off or for another app's document. */
    private fun layoutFolder(): String? = if (prefs.perSeries) favoriteFile()?.parent else null

    /** True while the folder decides the direction, so its books do not detect one. */
    private fun folderDirection() = folderLayout?.dirChosen == true

    /** Reads in the detected direction unless the folder has its own or detection is off. */
    private fun applyDetection() {
        val found = found()
        detectedActive = found != null && !folderDirection() && prefs.detectDirection
        if (detectedActive && found != null) readingDir = found.direction.key
        if (tallStripOn()) readingDir = DIR_TTB
        mode = shownMode()
    }

    /** Says on open which direction was detected and why, when it or the layout it brings is not the default; once per folder. */
    private fun noteDetection() {
        val found = found() ?: return
        if (!detectedActive || (found.direction.key == prefs.readingDir && mode == chosenMode)) return
        val noted = file?.parent to found
        if (notedDetection == noted) return
        notedDetection = noted
        showHint(getString(R.string.direction_detected, directionName(found.direction.key), detectedFrom(found)), DETECTION_NOTE_MS)
    }

    private fun directionName(dir: String) = getString(
        when (dir) {
            "rtl" -> R.string.direction_rtl
            "ttb" -> R.string.direction_ttb
            else -> R.string.direction_ltr
        }
    )

    /** What [found] was read from: "ComicInfo", "the cover", or the publisher's name. */
    private fun detectedFrom(found: DirectionDetection.Detected): String = when (found.source) {
        DirectionDetection.Source.COMIC_INFO -> getString(R.string.direction_from_comic_info)
        DirectionDetection.Source.COVER -> getString(R.string.direction_from_cover)
        DirectionDetection.Source.PUBLISHER -> getString(R.string.direction_from_publisher, found.detail.orEmpty())
        DirectionDetection.Source.SHAPE -> getString(R.string.direction_from_shape)
        DirectionDetection.Source.SEAMS -> getString(R.string.direction_from_seams)
    }

    /** The direction on screen and where it comes from: the folder, the book's contents, or the default. */
    private fun directionInfo(): String {
        val found = found()
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
            setFolder(folder, FolderLayout.Frozen(keptLayout(), dirChosen))
        } else {
            // The defaults keep the layout chosen, not one detected slices show.
            prefs.setLayoutDefaults(currentLayout().copy(mode = chosenMode))
        }
    }

    /** What a folder freezes: the layout on screen, the chosen one where a left or right direction pages. */
    private fun keptLayout() = currentLayout().copy(mode = if (mode == MODE_CONTINUOUS) mode else chosenMode)

    private fun setFolder(folder: String, layout: FolderLayout?) {
        folderLayout = layout
        val own = if (layoutOwn) book() else null
        if (own != null) library.setBookLayout(own, layout) else library.setFolderLayout(folder, layout)
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
        setFolder(path, if (now is FolderLayout.Frozen) now.thawed() else FolderLayout.Frozen(keptLayout(), folderDirection()))
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
        val target = Remotes.file(path)
        ReadProbes.current?.opening(path)
        persistAdjustments()
        file = target
        // Found as the book opens, off the main thread.
        bookId = null
        bookRoots = StorageRoots.list(this)
        refreshFavorites()
        loadAdjustments()
        toolbar.title = chapterLabel(target)
        progress.visibility = View.VISIBLE
        markedRead = false
        fetchedPercent = -1
        timeShareWait(OPENING)
        landedAtEnd = false
        bookChapters = emptyList()
        repeatedBack.reset()
        hideHintNow()
        backPage = -1
        detection = null
        slices = null
        slicesChecked = false
        layoutPicked = false
        tallStrip = false
        tallJudged = false
        tallPages = emptySet()
        tallJob?.cancel()
        resolveLayout()

        lateDetectionJob?.cancel()
        slicesJob?.cancel()
        openJob = lifecycleScope.launch {
            val folderDecides = folderDirection()
            val perFolder = layoutFolder() != null
            val slice = maySlice()
            var known: KnownPages? = null
            var sliceSizes: List<IntArray?> = emptyList()
            var sliced: DirectionDetection.Detected? = null
            var sliceSettled = false
            var found: BookId? = null
            var fresh = false
            val (opened, siblings, contents) = withContext(AppDispatchers.io + Remotes.askingAs(asker)) {
                val job = coroutineContext.job
                val fetch = PageSources.Fetch(canceled = { !job.isActive }) { done, total -> showFetched(done, total) }
                // The ID first: on a share, the reads of the book's ends start opening it too.
                // A share sending files only whole has it from the copy fetched to open the book.
                found = Traces.section("open: book id") { BookIds.find(target) }
                val s = runCatching {
                    val raw = PageSources.open(target, prefs.charset, cacheDir, canceled = { !job.isActive }, fetch = fetch)
                    hiding((found ?: BookIds.known(target)).also { found = it }, raw)
                }
                // Before opening keeps anything for it.
                fresh = found?.let { !library.hasData(it) } == true
                val id = found
                try {
                    ensureActive()
                    val siblings = siblingChapters(target)
                    // A book or folder with its own direction keeps it: nothing to detect. The book's own layout, known by its ID, decides over its folder's.
                    val own = if (perFolder) id?.let { library.bookLayout(it) } else null
                    val detect = prefs.detectDirection && !(own?.dirChosen ?: folderDecides)
                    val found = s.getOrNull()?.takeIf { detect }?.let { src ->
                        Traces.section("open: direction") { runCatching { detectBeforeOpen(target, src, siblings) }.getOrNull() }
                    }
                    known = s.getOrNull()?.let { src ->
                        knownSizes(target, src, opening = startOf(src.pageCount, id).page)
                    }
                    // Slices found before, or whose shape alone tells, read as one column from the first page; seams wait.
                    s.getOrNull()?.takeIf { slice }?.let { src ->
                        val checked = keptSlices(target) ?: run {
                            sliceSizes = Traces.section("open: slice sizes") { BookDirection.sliceSizes(src, known?.sizes, read = src.cheapSizes) }
                            Slices.settled(sliceSizes)?.also { keepSlices(target, it) }
                        }
                        sliced = checked?.found
                        sliceSettled = checked != null
                    }
                    ensureActive()
                    Triple(s, siblings, found)
                } catch (e: CancellationException) {
                    // A newer open() replaced this one: do not leak the file handle.
                    runCatching { s.getOrNull()?.close() }
                    throw e
                }
            }
            progress.visibility = View.GONE
            fetched.visibility = View.GONE
            if (found != bookId) {
                bookId = found
                refreshFavorites()
                loadAdjustments()
                // A book keeping its own layout shows it rather than its folder's.
                if (layoutOwn || (layoutFolder() != null && found?.let { library.bookLayout(it) } != null)) resolveLayout()
            }
            book()?.let { library.seen(it) }
            chapters = siblings
            val src = opened.getOrNull()
            if (src == null) {
                toast(openFailure(opened.exceptionOrNull(), target))
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
            slices = sliced
            slicesChecked = slice
            keptTallStrip(target)?.let { strip ->
                // An answer kept counts as a layout picked for the book.
                tallStrip = strip
                tallJudged = true
                layoutPicked = true
            }
            applyDetection()
            source = src
            aiBooks()
            known?.let { showPages(it) }
            setup(src)
            updateShareWait()
            noteDetection()
            if (contents != null) detectLater(target, src, contents)
            if (slice && !sliceSettled) detectSlicesLater(target, src, sliceSizes)
            noteLikeness(target, src, found, fresh)
            offerRepack(target, src, found)
        }
    }

    /**
     * Offers to repack [book], opened as [src], of ID [id], as CBZ when it is a solid archive
     * ([SolidArchives]) named as a RAR or 7z, on the device in a folder that can be written, unless "Suggest repacking
     * solid archives" is off or the book was answered "Not now" before.
     */
    private fun offerRepack(book: File, src: PageSource, id: BookId?) {
        repackJob?.cancel()
        // Named as a RAR or 7z: other books are never read for it.
        val named = Formats.ext(book.name).let { it in Formats.RAR || it in Formats.SEVEN_ZIP }
        if (id == null || !named || !prefs.suggestRepack || book is RemoteFile || Remotes.isRemote(book.path) || library.repackDeclined(id)) return
        repackJob = lifecycleScope.launch {
            val solid = withContext(AppDispatchers.io) { !ownCopy(book) && book.parentFile?.canWrite() == true && SolidArchives.isSolid(book) }
            // Another question showing first: asked again on a later open.
            if (!solid || source !== src || file != book || bookId != id || tallAsk != null || isFinishing) return@launch
            askRepack(BookRef(id, book.absolutePath))
        }
    }

    /** True for [book] kept in the app's own folders (a copy of a file another app shared): not the user's to repack. Blocking. */
    private fun ownCopy(book: File): Boolean {
        val path = runCatching { book.canonicalPath }.getOrDefault(book.absolutePath)
        val own = listOfNotNull(dataDir, externalCacheDir?.parentFile).map { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) }
        return own.any { path.startsWith(it + File.separator) }
    }

    /**
     * "Repack as CBZ" or "Not now" for solid archive [book], with In place as Compact shares
     * it. Not now, or the note dismissed, is kept for the book: not offered again.
     */
    private fun askRepack(book: BookRef) {
        var answered = false
        val view = FileActions.compactOptions(this, prefs, skipHidden = false)
        val inPlace = view.findViewById<MaterialSwitch>(R.id.compact_in_place)
        repackAsk = AlertDialog.Builder(this)
            .setTitle(getString(R.string.repack_title_one, File(book.path).name))
            .setMessage(R.string.repack_note)
            .setView(view)
            .setPositiveButton(R.string.more_repack) { _, _ ->
                answered = true
                repack(File(book.path), inPlace.isChecked)
            }
            .setNegativeButton(R.string.repack_not_now, null)
            .setOnDismissListener {
                repackAsk = null
                if (!answered) library.setRepackDeclined(book)
            }
            .showKeepingFullscreen()
    }

    /**
     * Queues repacking [book] as CBZ, beside it or [inPlace], and leaves the reader for the
     * library, where the operation shows: the book is let go first, as a rebuild leaves
     * books open in the reader alone; its place is kept, and follows the new file.
     */
    private fun repack(book: File, inPlace: Boolean) {
        if (file != book) return
        prefs.compactInPlace = inPlace
        stopAutoScroll()
        flushStats()
        saveHistory()
        openJob?.cancel()
        closeSource()
        file = null
        FileActions.startRebuild(FileJobs(this, library, prefs), listOf(book.absolutePath), FileJobs.Rebuild.REPACK, inPlace)
        toast(R.string.repack_started)
        finish()
    }

    /**
     * Keeps the likeness of [book], opened as [src], of ID [id] ([CopyMatch.note]); when
     * nothing was kept for it before it opened ([fresh]) and it is like a book with data
     * kept, asks once whether to carry that data over. A ComicInfo that only unpacking
     * pages would reach is read through its own handle at the lowest priority, unless
     * battery saver pauses scans ([Thrift.pausedScans]) or the book is solid on a share:
     * then the book is noted on a later open.
     */
    private fun noteLikeness(book: File, src: PageSource, id: BookId?, fresh: Boolean) {
        likenessJob?.cancel()
        if (id == null) return
        val app = applicationContext
        val paused = applied.thrift.pausedScans
        val charset = prefs.charset
        likenessJob = lifecycleScope.launch {
            val found = withContext(AppDispatchers.io) {
                val store = LikenessStore(app)
                if (!CopyMatch.needed(store, id, book.name, fresh)) return@withContext null
                val raw = (src as? HidingSource)?.inner ?: src
                val info = if (raw.comicInfoCostly()) {
                    if (paused) return@withContext null
                    withContext(AppDispatchers.background) { BookFactsReader.comicInfo(book, charset, cacheDir) } ?: return@withContext null
                } else {
                    runCatching { raw.comicInfo(cheapOnly = true) }.getOrNull()
                }
                ensureActive()
                val likeness = runCatching {
                    Likeness.of(info, (0 until raw.pageCount).map { raw.pageName(it) }, book.name)
                }.getOrNull() ?: return@withContext null
                ensureActive()
                val match = CopyMatch.note(store, library, id, likeness, book.name, fresh) ?: return@withContext null
                // A book on this device still where it was may be another of the same name; shares are not asked.
                val stillThere = library.pathOf(match.id)?.let { !Remotes.isRemote(it) && File(it).isFile } == true
                Triple(store, match, Triple(likeness, raw.pageCount, stillThere))
            } ?: return@launch
            if (source !== src || file != book || bookId != id) return@launch
            val (store, match, mine) = found
            askCarryOver(store, match, BookRef(id, book.absolutePath), mine.first, mine.second, mine.third)
        }
    }

    /**
     * Asks whether book [to] takes over what is kept for [match], the book it is like,
     * saying so when that book is [stillThere]; carries it over and opens [to] again with it.
     */
    private fun askCarryOver(store: LikenessStore, match: LikenessStore.Row, to: BookRef, likeness: Likeness, pageCount: Int, stillThere: Boolean) {
        val from = library.pathOf(match.id) ?: return
        val where = Crumbs.of(File(from).parent.orEmpty(), bookRoots).joinToString(" / ") { it.label }
        AlertDialog.Builder(this)
            .setTitle(R.string.carry_over_title)
            .setMessage(getString(R.string.carry_over_msg, File(from).name, where) + if (stillThere) "\n\n" + getString(R.string.carry_over_still_there) else "")
            .setPositiveButton(R.string.carry_over) { _, _ ->
                if (book() != to) return@setPositiveButton
                stopAutoScroll()
                flushStats()
                saveHistory()
                lifecycleScope.launch {
                    withContext(AppDispatchers.io) { CopyMatch.carryOver(store, library, match, to, likeness, pageCount) }
                    if (book() != to) return@launch
                    // Opened again at the page its history now holds.
                    openJob?.cancel()
                    closeSource()
                    endDismissed = false
                    hideEndCard()
                    open()
                }
            }
            .setNegativeButton(R.string.carry_over_no, null)
            .showKeepingFullscreen()
    }

    /**
     * Checks [book]'s pages for slices once it shows: the seams of a shape that alone does
     * not settle it, or the sizes not known on open. The finding is kept for the book
     * ([keepSlices]); slices turn it to one column top to bottom at the page on screen,
     * unless a direction was chosen meanwhile. [sizes] null reads them all now.
     */
    private fun detectSlicesLater(book: File, src: PageSource, sizes: List<IntArray?>?) {
        slicesJob?.cancel()
        slicesChecked = true
        val choices = directionChoices
        val known = pageSizes
        slicesJob = lifecycleScope.launch {
            val found = withContext(AppDispatchers.io) {
                val job = coroutineContext.job
                val checked = keptSlices(book) ?: runCatching {
                    BookDirection.slices(src, sizes ?: BookDirection.sliceSizes(src, known, read = true)) { !job.isActive }
                }.getOrNull()?.also { if (job.isActive) keepSlices(book, it) }
                checked?.found
            }
            if (source !== src || file != book) return@launch
            if (found == null || choices != directionChoices || !maySlice()) {
                // Not slices of one strip: its tall pages may still be asked about.
                judgeTall(slicesDone = true)
                return@launch
            }
            tallAsk?.dismiss()
            slices = found
            showDetection()
            noteDetection()
        }
    }

    /** What [book]'s pages were found to be when checked for slices before, here or by the library; null when never. Blocking. */
    private fun keptSlices(book: File): Slices.Checked? {
        factsCache.load()
        val id = BookIds.known(book)
        // Found this session: the library's facts, read since, learn it too.
        BookDirection.checkedSlices(book)?.let { checked ->
            id?.let { factsCache.keepSlices(it, checked) }
            return checked
        }
        return factsCache.get(id)?.slices
    }

    /** Keeps what [book]'s pages were found to be, also with the library's facts of it, so the next open needs no check. Blocking. */
    private fun keepSlices(book: File, checked: Slices.Checked) {
        BookDirection.rememberSlices(book, checked)
        BookIds.known(book)?.let { factsCache.keepSlices(it, checked) }
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
        val contents = BookDirection.fromContents(target, src, siblings, factsCache.get(BookIds.known(target)))
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
                        // Seams checked this session settle what headers alone left open.
                        BookIds.find(book)?.let { id ->
                            factsCache.put(id, facts)
                            // Checked after the put: a check kept meanwhile ([keepSlices]) found these facts.
                            if (facts.slices == null) BookDirection.checkedSlices(book)?.let { factsCache.keepSlices(id, it) }
                        }
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
        repackJob?.cancel()
        repackJob = null
        // Closed with the book, unanswered: offered again on a later open.
        repackAsk?.let {
            repackAsk = null
            it.setOnDismissListener(null)
            it.dismiss()
        }
        likenessJob?.cancel()
        likenessJob = null
        slicesJob?.cancel()
        slicesJob = null
        sizesJob?.cancel()
        sizesJob = null
        sizesFound = null
        learnJob?.cancel()
        learnJob = null
        learnRound++
        seamsJob?.cancel()
        seamsJob = null
        storeVisits()
        pageSeams = PageSeams.Found(0)
        pageStore = null
        shownSeams = pageSeams
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
        stripAheadId = null
        runCatching { source?.close() }
        source = null
        pager.adapter = null
        vertical.adapter = null
    }

    /** Shows how much of a book on a share is fetched, from any thread, as whole percents. */
    private fun showFetched(done: Long, total: Long) {
        val percent = if (total <= 0) 0 else (done * 100 / total).toInt()
        if (percent == fetchedPercent) return
        fetchedPercent = percent
        runOnUiThread {
            fetched.text = getString(R.string.share_fetching, percent)
            fetched.visibility = if (percent < 100) View.VISIBLE else View.GONE
            shareAnswered()
        }
    }

    @Volatile
    private var fetchedPercent = -1

    /** When the wait on the share now timed began ([timeShareWait]), and for which item ([OPENING] while the book opens). */
    private var shareWaitFrom = 0L
    private var shareWaitItem = OPENING

    private val checkShareWait = Runnable { updateShareWait() }

    /** True while a book on a share opens with nothing fetched yet, or the item on screen shows nothing of a page it holds. */
    private fun blank(): Boolean {
        if (file !is RemoteFile || isFinishing) return false
        if (progress.visibility == View.VISIBLE) return fetched.visibility != View.VISIBLE
        return source != null && adapter?.blankAt(shownItem()) == true
    }

    /**
     * Times a wait on the share from now, as a book on one starts to open ([item] is
     * [OPENING]) or another [item] comes on screen; the book's first item waits on from
     * when it began to open. [shareWait] shows once the wait lasts [SHARE_WAIT_NOTE_MS].
     */
    private fun timeShareWait(item: Int) {
        if (item == shareWaitItem && item != OPENING) return
        if (item == OPENING || shareWaitItem != OPENING) shareWaitFrom = SystemClock.uptimeMillis()
        shareWaitItem = item
        updateShareWait()
    }

    /**
     * Shows [shareWait] while the screen has been [blank] for [SHARE_WAIT_NOTE_MS] since
     * the wait began ([timeShareWait]) and a request of the reader's own ([asker]) has had
     * no answer for [SHARE_SILENT_MS]; checks again every [SHARE_WAIT_CHECK_MS] while blank,
     * so it comes when the share stalls and goes as soon as data arrives.
     */
    private fun updateShareWait() {
        handler.removeCallbacks(checkShareWait)
        if (file !is RemoteFile) {
            shareWait.visibility = View.GONE
            return
        }
        val left = shareWaitFrom + SHARE_WAIT_NOTE_MS - SystemClock.uptimeMillis()
        if (left > 0 && shareWait.visibility != View.VISIBLE) {
            handler.postDelayed(checkShareWait, left)
            return
        }
        val blank = blank()
        val waiting = blank && Remotes.silentFor(asker) >= SHARE_SILENT_MS
        shareWaitSpinner.visibility = if (progress.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        shareWait.visibility = if (waiting) View.VISIBLE else View.GONE
        if (blank) handler.postDelayed(checkShareWait, SHARE_WAIT_CHECK_MS)
    }

    /** Hides [shareWait] at once when what it waited for arrived. */
    private fun shareAnswered() {
        if (shareWait.visibility == View.VISIBLE) updateShareWait()
    }

    /** Why [book] could not be opened, as the user can act on it. */
    private fun openFailure(error: Throwable?, book: File): String = when (error) {
        is NestedArchives.StorageException -> getString(R.string.error_open_storage)
        is RemoteCache.TooLargeException -> getString(R.string.error_open_too_large)
        is RemoteException -> if (error.reason == RemoteException.Reason.MISSING || error.reason == RemoteException.Reason.UNKNOWN_SHARE) {
            getString(R.string.open_location_failed)
        } else {
            val name = StorageRoot.of(book.absolutePath, bookRoots)?.name.orEmpty()
            getString(
                when (error.reason) {
                    RemoteException.Reason.DENIED -> R.string.share_denied_named
                    RemoteException.Reason.NO_SHARE -> R.string.share_missing_named
                    else -> R.string.share_unreachable_named
                },
                name
            )
        }
        else -> getString(R.string.error_open)
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
        val id = bookId
        val saved = id?.let { library.pageFor(it) } ?: 0
        val start = startOf(src.pageCount, id)
        // Used once: a later reopen sets it again, and anything else starts from history.
        intent.removeExtra(EXTRA_PAGE)
        currentIndex = start.page.coerceIn(0, src.pageCount - 1)
        // Come back to from the next chapter: the end card waits for a move past the end,
        // and the chapter counts as read only once its last page is reached again.
        if (requested == LAST_PAGE) {
            endDismissed = true
            landedAtEnd = true
        }
        bookChapters = chaptersOf(src)
        seenPages.clear()
        scansReleased = false
        handler.removeCallbacks(releaseScans)
        handler.postDelayed(releaseScans, SCAN_HOLD_MS)
        attach()
        prepareStripAhead()
        judgeTall()

        // Carrying an auto-scroll run into the next chapter must survive the reload.
        if (keepAutoScroll) {
            keepAutoScroll = false
            startAutoScroll()
        }

        // Recorded on opening, so the library's "Recent" row follows at once.
        saveHistory()
        if (start.ask) askStartPage(saved, src.pageCount)
    }

    /** Where book [id] of [count] pages opens ([ResumeMode.start]): the page asked for, else as its history says. */
    private fun startOf(count: Int, id: BookId?): ResumeMode.Start {
        val saved = id?.let { library.pageFor(it) } ?: 0
        return prefs.resumeMode.start(intent.getIntExtra(EXTRA_PAGE, -1), saved, count, finished = id != null && library.isRead(id))
    }

    /**
     * Reads the sizes [sizes] lacks of the pages a book opens on, the [Spreads.LANDSCAPE_SAMPLE]
     * pages nearest page [at], from their headers where that is cheap ([PageSource.cheapSizes]),
     * so a tall page or spread among them is laid out as one from the start and enough sizes
     * are known to tell a landscape book. Blocking.
     */
    private fun sizeOpening(src: PageSource, sizes: Array<IntArray?>, at: Int) {
        if (!src.cheapSizes) return
        val from = (at - Spreads.LANDSCAPE_SAMPLE / 2).coerceAtMost(sizes.size - Spreads.LANDSCAPE_SAMPLE).coerceAtLeast(0)
        for (i in from until minOf(sizes.size, from + Spreads.LANDSCAPE_SAMPLE)) {
            if (sizes[i] == null) sizes[i] = runCatching { src.pageSize(i) }.getOrNull()
        }
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
            .showKeepingFullscreen()
    }

    // ------------------------------------------------------------------ spreads

    /**
     * A book's pages as [store]d, their [names], the [sizes] known of them (null where unknown),
     * what the spread engine finds in both, the [seams] between its pages checked before, and
     * the pages [seen] before.
     */
    private class KnownPages(
        val store: StoredPages,
        val sizes: Array<IntArray?>,
        val analysis: Spreads.Analysis,
        val seams: PageSeams.Found,
        val seen: BitSet
    ) {
        val names: List<String?> get() = store.names
    }

    /** [src], book [id] opened, without the pages hidden in it. */
    private fun hiding(id: BookId?, src: PageSource): PageSource = HiddenPages.wrap(src, library.hiddenPages(id))

    /**
     * The page names of [src], the sizes, seams and pages seen stored for them and their
     * [Spreads.classify], with the sizes of the pages the book opens on at [opening] read
     * first ([sizeOpening]), so a spread there is laid out as one from the start. Blocking.
     */
    private suspend fun knownSizes(book: File, src: PageSource, opening: Int = -1): KnownPages {
        val store = StoredPages(PageSizes(PageSizes.dirIn(cacheDir)), PageSeams(PageSeams.dirIn(cacheDir)), PreviewStore.bookId(book), src)
        val sizes = store.readSizes()
        if (opening >= 0) Traces.section("open: sizes of the first pages") { sizeOpening(src, sizes, opening) }
        return KnownPages(store, sizes, classify(store.names, sizes), store.readSeams(), withContext(seenIo) { store.readSeen() })
    }

    /** What the spread engine finds in page [names] of [sizes]. Blocking on long books. */
    private fun classify(names: List<String?>, sizes: Array<IntArray?>): Spreads.Analysis =
        Spreads.classify(names.map { it.orEmpty() }, sizes.toList())

    /** Takes the [known] pages of the open book as what the spread engine reads. */
    private fun showPages(known: KnownPages) {
        pageBounds = PageBounds()
        pageNames = known.names
        pageStore = known.store
        pageSizes = known.sizes
        val id = favoriteFile()?.let { bookId }
        shifted = id != null && library.isShifted(id)
        shiftedByNames = id != null && library.isShiftedByNames(id)
        ignoreSeams = id != null && library.ignoresSeams(id)
        analysis = known.analysis
        pageSeams = known.seams
        shownSeams = pageSeams
        seamsSettling = true
        openedPage = -1
        visitedPages.clear()
        visitedPages.or(known.seen)
        storedVisits = visitedPages.cardinality()
        keepVisits = !known.seen.isEmpty || known.seams.anyChecked
        shownVisits = 0
        tallPages = findTallPages()
    }

    /**
     * Reads the sizes [book]'s pages lack from their headers, in the background and from
     * the page on screen outward, stores them, and lays the book out again when they
     * change its spreads. Skipped where that would unpack the whole book
     * ([PageSource.cheapSizes]), and while battery saver pauses scans ([Thrift.pausedScans]).
     */
    private fun scanSizes(book: File, src: PageSource) {
        val previous = sizesJob
        previous?.cancel()
        if (!src.cheapSizes || pageSizes.none { it == null } || applied.thrift.pausedScans) return
        val names = pageNames
        val store = pageStore ?: return
        val sizes = pageSizes.copyOf()
        val from = currentIndex
        sizesJob = lifecycleScope.launch {
            // A scan stopped before keeps what it found ([sizesFound]): none of it is read again.
            previous?.join()
            sizesFound?.takeIf { it.first === src }?.second?.forEachIndexed { i, s -> if (s != null && i < sizes.size && sizes[i] == null) sizes[i] = s }
            sizesFound = src to sizes
            val known = sizes.count { it != null }
            val found = withContext(AppDispatchers.io) {
                val job = coroutineContext.job
                if (!PageSizes.scan(src, sizes, from) { !job.isActive }) {
                    if (sizes.count { it != null } > known) store.writeSizes(sizes)
                    return@withContext null
                }
                store.writeSizes(sizes)
                classify(names, sizes)
            }
            if (found == null || source !== src || file != book) return@launch
            pageSizes = sizes
            analysis = found
            layoutStale = true
            updateTallPages()
            relayout()
            judgeTall()
        }
    }

    /** Scans the open book's page sizes and seams once, after its first page shows ([scansReleased]). */
    private fun startScans() {
        if (scansReleased) return
        scansReleased = true
        handler.removeCallbacks(releaseScans)
        val src = source ?: return
        val book = file ?: return
        scanSizes(book, src)
        checkSeams()
    }

    /** Runs [block] once the next frame is drawn, should [src] still be the book open. */
    private fun afterNextDraw(src: PageSource, block: () -> Unit) {
        Choreographer.getInstance().postFrameCallback { handler.post { if (source === src) block() } }
    }

    /**
     * Takes the size page [page] showed when it loaded, in a book whose sizes are not
     * scanned ([PageSource.cheapSizes]): after [LEARN_DELAY_MS], all sizes learned so far
     * are stored and the book is laid out again when they change its spreads.
     */
    private fun learnSize(page: Int) {
        val src = source ?: return
        val book = file ?: return
        val store = pageStore ?: return
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
                store.writeSizes(sizes)
                classify(names, sizes)
            }
            if (round != learnRound || source !== src || file != book) return@launch
            analysis = found
            layoutStale = true
            updateTallPages()
            relayout()
            judgeTall()
        }
    }

    // ------------------------------------------------------------------ tall pages

    /** The tall pages among the open book's known sizes, for the threshold set and this screen's shape. */
    private fun findTallPages(): Set<Int> = TallPages.of(pageSizes.toList(), prefs.tallThreshold, ScreenShape.portraitAspect(this))

    /** Takes the open book's tall pages anew: paged reading shows them alone, at fit width, from their top. */
    private fun updateTallPages() {
        val found = findTallPages()
        if (found == tallPages) return
        tallPages = found
        layoutStale = true
        adapter?.tallPages = if (mode == MODE_PAGED) found else emptySet()
    }

    /** True when [book] is a loose image, judged and answered for with the images of its folder. */
    private fun looseImage(book: File) = Formats.typeOf(book.name) == EntryType.IMAGE

    /** Whether [book] reads as a strip, as answered before for it, or for its folder when it is a loose image; null when never asked. */
    private fun keptTallStrip(book: File): Boolean? =
        if (looseImage(book)) book.parent?.let { library.folderTallStrip(it) } else bookId?.let { library.tallStrip(it) }

    /** Keeps [strip] as the answer for the open book, or for its folder when it is a loose image. */
    private fun keepTallStrip(strip: Boolean) {
        val book = favoriteFile() ?: return
        if (looseImage(book)) book.parent?.let { library.setFolderTallStrip(it, strip) } else book()?.let { library.setTallStrip(it, strip) }
    }

    /**
     * Asks once whether the open book reads as a continuous strip when at least half its
     * pages of known size are tall ([TallPages.mostlyTall]): a book once the sizes of
     * [TallPages.FOLDER_SAMPLE] pages, or all, are known; a loose image by the images nearest
     * it in its folder. Never asked where the folder keeps a layout or direction, a layout
     * was picked, slices read as one already, or the book shows as a strip anyway; nor
     * before its pages are checked for slices, unless [slicesDone].
     */
    private fun judgeTall(slicesDone: Boolean = false) {
        val src = source ?: return
        val book = file ?: return
        if (!mayAskTall(slicesDone)) return
        if (looseImage(book)) {
            if (tallJob?.isActive == true) return
            val images = chapters.filter { looseImage(it) }
            val at = images.indexOfFirst { it.absolutePath == book.absolutePath }
            if (at < 0) return
            val threshold = prefs.tallThreshold
            val aspect = ScreenShape.portraitAspect(this)
            tallJob = lifecycleScope.launch {
                val verdict = withContext(AppDispatchers.io) {
                    val sizes = TallPages.nearest(images.size, at).map { i ->
                        ensureActive()
                        runCatching { headerSize(images[i]) }.getOrNull()
                    }
                    TallPages.mostlyTall(sizes, threshold, aspect)
                } ?: return@launch
                // What was found while the sizes were read may settle it first.
                if (source !== src || file != book || !mayAskTall(slicesDone)) return@launch
                tallJudged = true
                if (verdict) askTallStrip()
            }
            return
        }
        if (pageSizes.count { it != null } < minOf(src.pageCount, TallPages.FOLDER_SAMPLE)) return
        val verdict = TallPages.mostlyTall(pageSizes.toList(), prefs.tallThreshold, ScreenShape.portraitAspect(this)) ?: return
        tallJudged = true
        if (verdict) askTallStrip()
    }

    /**
     * True while the open book may still be asked whether it reads as a strip: not asked
     * yet, no folder layout or direction, no layout picked, no slices reading as one, not
     * shown continuous top to bottom already, and its slices checked unless [slicesDone].
     */
    private fun mayAskTall(slicesDone: Boolean): Boolean {
        if (tallJudged || folderLayout != null || layoutPicked || slices != null) return false
        if (!slicesDone && slicesJob?.isActive == true) return false
        return !(mode == MODE_CONTINUOUS && readingDir == DIR_TTB)
    }

    /** `[width, height]` of image [file] from its header alone. Blocking. */
    private fun headerSize(file: File): IntArray? =
        if (file is RemoteFile) Remotes.inputStream(file.path).use { Decoders.headerSize(it) } else Decoders.size(file)

    /** "Tall pages: read as a continuous strip?" Yes reads the book continuous top to bottom; either answer is kept. */
    private fun askTallStrip() {
        val book = file
        var answered = false
        fun answer(strip: Boolean) {
            if (answered || file != book) return
            answered = true
            keepTallStrip(strip)
            layoutPicked = true
            if (!strip) return
            tallStrip = true
            showDetection()
        }
        tallAsk = AlertDialog.Builder(this)
            .setMessage(R.string.tall_strip_ask)
            .setPositiveButton(R.string.tall_strip_yes) { _, _ -> answer(true) }
            .setNegativeButton(R.string.tall_strip_no) { _, _ -> answer(false) }
            .setOnCancelListener { answer(false) }
            .setOnDismissListener { tallAsk = null }
            .showKeepingFullscreen()
    }

    /** Lets the open book follow the layout the reader picks instead of reading as a strip, and keeps that. */
    private fun leaveTallStrip() {
        if (!tallStrip) return
        tallStrip = false
        keepTallStrip(false)
    }

    /**
     * How items map to pages: in paged reading, the spread engine's pairs in two-page
     * mode, or each page with spreads in one file split in halves ([SpreadDisplay.SPLIT]),
     * left or right, or with spreads in two files shown as one ([SpreadDisplay.MERGE]);
     * otherwise a page an item. Seams shown so far ([shownSeams]) settle pairs and merges.
     */
    private fun buildLayout(): PageLayout {
        val rtl = readingDir == "rtl"
        val a = analysis
        if (mode != MODE_PAGED || a == null) return PageLayout(spread, rtl)
        visit(currentIndex)
        shownVisits = visitedPages.cardinality()
        val visited = visitedPages.stream().boxed().collect(Collectors.toSet())
        val options = Spreads.Options(prefs.coverAlone, shifted, if (seamsRead()) shownSeams.of(rtl) else emptyList(), shiftedByNames, visited, tallPages)
        if (spread) {
            val views = Spreads.pair(a, options)
            return PageLayout.of(views, spread = true, rtl = rtl, joined = Spreads.joined(views, options))
        }
        if (topToBottom()) return PageLayout(false, rtl)
        if (spreadDisplay == SpreadDisplay.SPLIT && (0 until a.size).any { a.splittable(it) }) {
            return PageLayout.split(a.size, rtl) { a.splittable(it) }
        }
        if (spreadDisplay == SpreadDisplay.MERGE && !ignoreSeams) {
            val views = Spreads.merge(a, options)
            if (views.size < a.size) return PageLayout.of(views, spread = true, rtl = rtl, joined = Spreads.joined(views, options))
        }
        return PageLayout(false, rtl)
    }

    /** True when pages may pair or join by their seams ([Prefs.joinSpreads]) read left or right, in a book not ignoring them. */
    private fun seamsRead() = prefs.joinSpreads && !topToBottom() && !ignoreSeams

    /** True when the open book's layout reads its seams: paged left or right, two pages a screen or spreads merged. */
    private fun seamsUsed() = mode == MODE_PAGED && seamsRead() && (spread || spreadDisplay == SpreadDisplay.MERGE)

    /** Lays the open book out again with seams read or not, as [Prefs.joinSpreads] and [ignoreSeams] now say. */
    private fun applyJoinSpreads() {
        if (!seamsRead()) seamsJob?.cancel()
        shownSeams = pageSeams
        if (buildLayout().sameAs(layout)) updateSpreadRotation() else attach()
        checkSeams()
    }

    /**
     * Checks the seams between the open book's pages that its layout may read, one at a
     * time in the background ([AppDispatchers.background]), nearest the page on screen
     * first: those within [SEAM_WINDOW] pages, again as the reader moves on, and beyond
     * them those of pages read alone ([PageSource.cheapSizes]) as JPEG ([Decoders.cheapSidesNamed]).
     * They are kept for the book ([PageSeams]), and the book is laid out again whenever they
     * change it. While battery saver pauses scans ([Thrift.pausedScans]), only those within
     * [SEAM_WINDOW] pages are checked: the screens being read need them.
     * A page's sides come from its decode for the reader ([PageSource.decodedSides]), checked at
     * once and first; only a JPEG page read alone is read for them before that, in rounds that
     * start once no page decodes for the reader ([PageAdapter.awaitIdle]). Other pages, and every
     * page of a solid archive, wait for the reader to decode them ([onLoaded][PageAdapter]): never
     * a second decode of a page. A book whose JPEG pages decode whole for their sides
     * ([PageSource.dearSides]: progressive) stops being read for them after the first round.
     */
    private fun checkSeams() {
        val src = source ?: return
        val book = file ?: return
        val a = analysis ?: return
        val store = pageStore ?: return
        if (!scansReleased || seamsJob?.isActive == true || !seamsUsed() || src.isVector) return
        val names = pageNames
        val far = !applied.thrift.pausedScans
        // Pages are read for their sides until one decodes whole for them: the job then ends and the next waits for decodes.
        val strips = !src.dearSides
        val readable = { i: Int -> strips && src.cheapSizes && Decoders.cheapSidesNamed(names.getOrNull(i)) }
        val cheap = { i: Int -> far && readable(i) }
        val ready = { i: Int -> readable(i) || src.decodedSides(i) != null }
        val read = { i: Int -> src.decodedSides(i) == null || src.decodedSides(i + 1) == null }
        // Those with both sides decoded first (the sort keeps the order within each).
        val pages = PageSeams.unchecked(a, pageSeams, currentIndex, SEAM_WINDOW, cheap).filter { ready(it) && ready(it + 1) }.sortedBy(read)
        if (pages.isEmpty()) return
        val found = pageSeams.copy()
        val checked = object : PageSource by src {
            override fun sides(index: Int) = src.decodedSides(index) ?: if (readable(index)) src.sides(index) else null
        }
        seamsJob = lifecycleScope.launch {
            var at = 0
            var batch = SEAM_FIRST
            while (at < pages.size) {
                val next = pages.subList(at, minOf(pages.size, at + batch))
                if (next.any(read)) {
                    // A replaced adapter, released, no longer waits: the one after it is asked too.
                    while (true) adapter?.takeIf { it.decoding }?.awaitIdle() ?: break
                }
                if (source !== src) return@launch
                val done = withContext(AppDispatchers.background) {
                    val job = coroutineContext.job
                    // A page found to decode whole for its sides ends the batch: the rest wait for decodes.
                    val dear = { strips && src.dearSides }
                    Traces.section({ "seam check ${next.size} pages" }) { PageSeams.check(checked, next, found, dear) { !job.isActive } }.also { ok ->
                        if (ok) store.writeSeams(found)
                    }
                }
                if (!done || source !== src || file != book) return@launch
                pageSeams = found.copy()
                relayout()
                at += next.size
                batch = SEAM_BATCH
                if (strips && src.dearSides) break
            }
            seamsJob = null
            // Where only nearby seams are checked, the reader may have moved on meanwhile.
            checkSeams()
        }
    }

    /**
     * Lays the book out with what changed on its own since the layout was made: seams
     * found, pages the reader reached ([visitedPages]), sizes learned ([layoutStale]).
     * Once the reader turned a page ([seamsSettling]) in a layout that reads seams, the
     * screen shown and those before it keep their pages ([PageLayout.keeping]), but for
     * one pairing a page now known to be wide; only the pages after it re-pair, and not
     * during a swipe or a zoom, which waits for the next call. A layout the reader asks
     * for takes them all ([attach]).
     */
    private fun relayout() {
        if (!layoutStale && shownSeams === pageSeams && shownVisits == visitedPages.cardinality()) return
        val shown = shownSeams
        val visits = shownVisits
        shownSeams = pageSeams
        layoutStale = false
        val next = buildLayout()
        if (next.sameAs(layout)) {
            updateSpreadRotation()
            return
        }
        if (seamsSettling || !seamsUsed()) return attach(fresh = false)
        val count = source?.pageCount ?: return
        val a = analysis ?: return
        if (pager.scrollState != ViewPager2.SCROLL_STATE_IDLE || adapter?.zoomed() == true) {
            shownSeams = shown
            shownVisits = visits
            layoutStale = true
            return
        }
        val item = layout.itemOf(currentIndex)
        val paired = layout.firstPairing(item, count) { a.wide(it) }
        val kept = layout.keeping(if (paired >= 0) paired - 1 else item, next, count)
        if (kept.sameItems(layout, count)) return updateSpreadRotation()
        attach(fresh = false, laid = if (kept.sameItems(next, count)) next else kept)
    }

    /** Shifts the open book's two-page pairing by one page, or back, and keeps the choice. */
    private fun setShifted(value: Boolean) {
        if (shifted == value) return
        shifted = value
        shiftedByNames = false
        if (favoriteFile() != null) book()?.let { library.setShifted(it, value) }
        if (spread) attach()
    }

    /**
     * Adds [page] to [visitedPages]; true when it is new there. New pages are stored
     * [VISITS_DELAY_MS] after the last of them ([storeVisits]).
     */
    private fun visit(page: Int): Boolean {
        if (page < 0 || visitedPages.get(page)) return false
        visitedPages.set(page)
        visitsJob?.cancel()
        visitsJob = lifecycleScope.launch {
            delay(VISITS_DELAY_MS)
            storeVisits()
        }
        return true
    }

    /** Stores [visitedPages] for the open book now, in the background, when any is new since last stored and they count ([keepVisits]). */
    private fun storeVisits() {
        visitsJob?.cancel()
        visitsJob = null
        val store = pageStore ?: return
        if (visitedPages.cardinality() == storedVisits) return
        if (!keepVisits && !seamsUsed()) return
        keepVisits = true
        storedVisits = visitedPages.cardinality()
        val seen = visitedPages.clone() as BitSet
        visitWrites.launch { store.writeSeen(seen) }
    }

    /** Ignores the open book's seams, or reads them again, and keeps the choice. */
    private fun setIgnoreSeams(value: Boolean) {
        if (ignoreSeams == value) return
        ignoreSeams = value
        if (favoriteFile() != null) book()?.let { library.setIgnoreSeams(it, value) }
        applyJoinSpreads()
    }

    /**
     * The wide page on screen, shown whole and alone in paged reading, or the first page
     * of a spread in two files merged into one view; -1 for anything else.
     */
    private fun widePageShown(): Int = widePageOf(shownItem())

    /** The wide page item [item] shows whole, as [widePageShown] tells; -1 for none. */
    private fun widePageOf(item: Int): Int {
        val a = analysis ?: return -1
        val count = source?.pageCount ?: return -1
        if (mode != MODE_PAGED) return -1
        if (!spread && layout.joined(item)) return layout.firstPage(item)
        val part = layout.parts(item, count).filterNotNull().singleOrNull() ?: return -1
        return if (part.half == Half.WHOLE && a.wide(part.page)) part.page else -1
    }

    /** Turns the screen for the wide page on screen, or back, as [spreadRotation] says. */
    private fun updateSpreadRotation() {
        // With auto-rotate off, the system turns back to the reader's lock whatever the sensor.
        val unsure = spreadRotation.held >= 0 && autoRotate() && upright().unsure()
        spreadRotation.show(widePageShown(), prefs.rotateSpreads, landscape(), screenOrientation(), unsure)?.let { requestedOrientation = it }
        adapter?.turned = spreadRotation.held >= 0
        watchUpright()
    }

    private fun upright(): UprightWatch = uprightWatch ?: UprightWatch(this) {
        spreadRotation.settled()?.let { requestedOrientation = it }
        watchUpright()
    }.also { uprightWatch = it }

    /**
     * Reads the sensor only while the reader shows, [spreadRotation] needs it and auto-rotate
     * is on; with auto-rotate off, a kept orientation is let go at once.
     */
    private fun watchUpright() {
        val needed = spreadRotation.watching && started
        val auto = needed && autoRotate()
        if (needed && !auto && spreadRotation.keeping) spreadRotation.settled()?.let { requestedOrientation = it }
        if (auto) upright().enabled = true
        else uprightWatch?.enabled = false
    }

    /** True when the system turns the screen by the sensor: auto-rotate is on. */
    private fun autoRotate() =
        android.provider.Settings.System.getInt(contentResolver, android.provider.Settings.System.ACCELEROMETER_ROTATION, 1) != 0

    /** The fixed orientation the display is in now, from its rotation and shape (not the window's, which may be a part of it). */
    private fun screenOrientation(): Int {
        @Suppress("DEPRECATION")
        val display = window.decorView.display ?: windowManager.defaultDisplay
        return SpreadRotation.fixedOrientation(display?.rotation ?: -1, displayLandscape(display), reverseDefaultRotation)
    }

    /** True when [display] is wider than tall, whatever part of it the reader's window takes. */
    private fun displayLandscape(display: android.view.Display?): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            if (!bounds.isEmpty) return bounds.width() > bounds.height()
        }
        val size = android.graphics.Point()
        @Suppress("DEPRECATION")
        display?.getRealSize(size) ?: return landscape()
        return if (size.x == size.y) landscape() else size.x > size.y
    }

    /** The platform's `config_reverseDefaultRotation`: which quarter turn its landscape (or portrait) is. */
    private val reverseDefaultRotation by lazy {
        val res = android.content.res.Resources.getSystem()
        @android.annotation.SuppressLint("DiscouragedApi")
        val id = res.getIdentifier("config_reverseDefaultRotation", "bool", "android")
        id != 0 && runCatching { res.getBoolean(id) }.getOrDefault(false)
    }

    /** True while items show numbers of their own: a page split in halves takes two. */
    private fun itemNumbers() = mode == MODE_PAGED && !layout.spread && layout.grouped

    /**
     * Builds a fresh adapter for the current layout. A [RecyclerView.Adapter] has
     * a fixed item view type, so changing the mode, spread or reading direction
     * requires a new instance. Paged reading top to bottom turns the pager upright.
     * A [fresh] layout, one the reader asked for, takes every seam found so far
     * ([relayout]). The pages the old adapter decoded show at once in the new one.
     * These are only emitted from the UI thread.
     */
    private fun attach(fresh: Boolean = true, laid: PageLayout? = null) {
        val src = source ?: return
        if (fresh) shownSeams = pageSeams
        layoutStale = false
        val decoded = adapter?.decoded(src).orEmpty()
        val processed = adapter?.processedOf(decoded).orEmpty()
        pager.adapter = null
        vertical.adapter = null
        // The new adapter reads the same chapters: they must stay open.
        adapter?.release(closeSources = false)

        spread = useSpread()
        shownCrop = cropOn()
        layout = laid ?: buildLayout()
        val a = PageAdapter(
            source = src,
            mode = adapterMode(),
            onTap = { x, y -> onTap(x, y) },
            doubleTapZoom = prefs.doubleTapZoom,
            tapsAtOnce = { x, y -> tapAction(x, y) != TapZones.Action.MENU },
            twoPage = spread,
            rtl = readingDir == "rtl",
            verticalDrag = if (brightnessGestureEnabled()) brightnessDrag else null,
            autoCrop = shownCrop,
            linear = prefs.linearLight,
            pageScaling = prefs.pageScaling,
            zoomScaling = prefs.zoomScaling,
            preload = applied.thrift.readAhead(prefs.preload, pagesPerScreen()),
            behind = PageAdapter.screenBehind && prefs.preload > 0 && !applied.thrift.lessReadAhead,
            fling = prefs.fling,
            onLongPress = { page -> showPageActions(if (page >= 0) page else currentIndex) },
            pageLayout = layout,
            bounds = pageBounds,
            enhancer = readerAi,
            onLoaded = {
                learnSize(it)
                if (!scansReleased) afterNextDraw(src) { startScans() }
                // Seams waiting for this page's decode are checked now.
                else handler.post { checkSeams() }
                // After the slot waiting for the page shows it.
                handler.post { shareAnswered() }
            }
        )
        a.fitMode = effectiveFit()
        a.tallPages = if (mode == MODE_PAGED) tallPages else emptySet()
        // Each page is decoded at the size it shows at once the screen turned for a wide page, or back.
        if (mode == MODE_PAGED) a.turnsFor = { item -> spreadRotation.turnsFor(widePageOf(item), prefs.rotateSpreads) }
        a.turned = spreadRotation.held >= 0
        a.filter = filter
        a.adjustments = adjustments
        a.spacingPx = spacingPx()
        a.adopt(decoded, processed)
        if (!started) a.pauseAi()
        adapter = a

        val item = pageToItem(currentIndex)
        currentItem = item
        // The roll-on chapter travels with the reader: adapters are rebuilt for
        // every layout change, so the already-opened source is laid in again.
        if (mode != MODE_PAGED) stripAhead?.let { a.append(it) }
        if (mode == MODE_PAGED) {
            pager.orientation = if (topToBottom()) ViewPager2.ORIENTATION_VERTICAL else ViewPager2.ORIENTATION_HORIZONTAL
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
     * Opens the chapter after this one so continuous reading can be
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
            // The reader's own: once reached, its pages show from what this opens.
            var id: BookId? = null
            val (src, ok) = withContext(AppDispatchers.io + Remotes.askingAs(asker)) {
                // Nested archives are not copied ahead: a book needing new copies opens when reached.
                val job = coroutineContext.job
                id = BookIds.find(next)
                val s = runCatching {
                    val raw = PageSources.open(next, prefs.charset, cacheDir, canceled = { !job.isActive }, copyNested = false)
                    hiding((id ?: BookIds.known(next)).also { id = it }, raw)
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
            stripAheadId = id
            aiBooks()
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
        bookId = stripAheadId
        aiBooks()
        val fresh = bookId?.let { !library.mayHaveData(it) } == true
        noteLikeness(nextFile, next, bookId, fresh)
        book()?.let { library.seen(it) }
        refreshFavorites()
        loadAdjustments()
        // Reopening or recreating the reader must load the chapter now shown.
        intent.putExtra(EXTRA_PATH, nextFile.absolutePath)
        // Its spreads are read on the next paged layout.
        sizesJob?.cancel()
        analysis = null
        storeVisits()
        pageStore = null
        lifecycleScope.launch {
            val known = withContext(AppDispatchers.io) { knownSizes(nextFile, next) }
            if (source !== next) return@launch
            showPages(known)
            scanSizes(nextFile, next)
        }
        currentIndex = (first - boundary).coerceAtLeast(0)
        stripAhead = null
        stripAheadFile = null
        stripAheadId = null
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
        stripAheadId = null
        // The adapter owns its segments: trimming the chapter out of the strip
        // lets it close the source once nothing is decoding from it anymore.
        val a = adapter
        if (a != null) a.dropAfter(chapterPages()) else runCatching { ahead.close() }
        // Restores the end card, which the pending chapter may have suppressed.
        updateEndCard()
    }

    private fun adapterMode(): PageAdapter.Mode =
        if (mode == MODE_CONTINUOUS) PageAdapter.Mode.CONTINUOUS else PageAdapter.Mode.PAGED

    /**
     * Two-page reading, paged left or right only, for the orientation pages are laid out
     * for: while turned for a wide page, the one that comes back.
     */
    private fun useSpread() = mode == MODE_PAGED && !topToBottom() && twoPage.spread(spreadRotation.layoutLandscape(landscape()))

    /** Pages a screen shows at most in the layout built last: two in spreads. */
    private fun pagesPerScreen() = if (spread) 2 else 1

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

    /** Brightness by vertical drag, in pages swiped sideways: swiping up turns the page top to bottom. */
    private fun brightnessGestureEnabled() = prefs.brightnessGesture && mode == MODE_PAGED && !topToBottom()

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
        if (mode == MODE_PAGED) showItem(item) else vertical.scrollToPosition(item)
        seek.progress = item
        onPageChanged()
    }

    /** Shows [item] in the pager; a tall page there from its top, or its bottom when turned [back] to. */
    private fun showItem(item: Int, back: Boolean = false) {
        pager.setCurrentItem(item, false)
        adapter?.landTall(item, back)
    }

    /** Shows [item]; before the first or past the last it acts as [goTo] does there. [back]: turned back to. */
    private fun goToItem(item: Int, back: Boolean = false) {
        val count = source?.pageCount ?: return
        when {
            item < 0 -> goTo(-1)
            item >= layout.itemCount(count) -> goTo(count)
            else -> {
                currentItem = item
                currentIndex = itemToPage(item)
                if (mode == MODE_PAGED) showItem(item, back) else vertical.scrollToPosition(item)
                seek.progress = item
                onPageChanged()
            }
        }
    }

    /**
     * Moves [delta] items (pages, spreads, or halves of a page) from the current one; a tall
     * page shown alone scrolls by most of a screen first, until its bottom (top going back).
     */
    private fun turn(delta: Int) {
        if (mode == MODE_PAGED && delta != 0 && adapter?.scrollTall(shownItem(), delta > 0) == true) return
        goToItem(shownItem() + delta, back = delta < 0)
    }

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

    /**
     * A tap at ([x], [y]), as fractions of the page view: turns a page or spread, or toggles
     * the UI. A continuous column only toggles it; top to bottom takes top / bottom zones
     * unless the setting names others ([TapZones.forBook]).
     */
    private fun onTap(x: Float, y: Float) {
        when (tapAction(x, y)) {
            TapZones.Action.PREVIOUS -> turn(-1)
            TapZones.Action.NEXT -> turn(1)
            TapZones.Action.MENU -> toggleBars()
        }
    }

    /** What a tap at ([x], [y]), fractions of the page view, does ([onTap]). */
    private fun tapAction(x: Float, y: Float): TapZones.Action {
        val zones = if (mode == MODE_CONTINUOUS) TapZones.DISABLED else prefs.tapZonesFor(topToBottom())
        val rtl = readingDir == "rtl" && mode == MODE_PAGED
        return zones.action(x, y, rtl, prefs.mirrorTapZones)
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
        timeShareWait(shownItem())
        ReadProbes.current?.moved(shownItem())
        adapter?.reading(shownItem())
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
        if (openedPage < 0) openedPage = currentIndex else if (currentIndex != openedPage) seamsSettling = false
        // A page reached anew may bring a lone seam match near enough to settle what follows.
        if (visit(currentIndex) && seamsUsed()) pager.post { relayout() }
        checkSeams()
        if (seekPanel.visibility == View.VISIBLE) {
            seekPreviews?.highlight(shownItem())
            centerSeekPanel()
        }
        val onLast = count > 0 && layout.isLastItem(shownItem(), count)
        if (!onLast) landedAtEnd = false
        if (!markedRead && onLast && !landedAtEnd) {
            markedRead = true
            book()?.let { library.setRead(it, true) }
            // A loose image read may make its folder one more book read.
            val image = file?.takeIf { looseImage(it) }
            val dir = image?.parentFile
            if (dir != null) {
                val images = chapters.filter { looseImage(it) }
                lifecycleScope.launch(AppDispatchers.io) { FolderReads.update(library, dir, images) }
            }
        }
        invalidateOptionsMenu()
    }

    private fun modeLabel(): String =
        getString(if (mode == MODE_CONTINUOUS) R.string.page_mode_continuous else R.string.page_mode_paged)

    private fun updateEndCard() {
        val src = source ?: return
        // "Auto next chapter" replaces the prompt entirely: the reader simply
        // rolls into the next chapter when the user moves past the last page.
        if (autoAdvance()) {
            hideEndCard()
            return
        }
        val atEnd = when (mode) {
            MODE_CONTINUOUS -> !vertical.canScrollVertically(1)
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
        val id = bookId ?: return
        val count = source?.pageCount ?: return
        library.addHistory(
            HistoryItem(f.absolutePath, f.name, currentIndex, count, System.currentTimeMillis(), id)
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
            prefs.thumbScaling,
            exact = src.cheapSizes
        ) { item ->
            closeSeekPanel()
            goToItem(item)
        }.also { strip ->
            seekPreviews = strip
            // Cells reshaped by their previews push the item on screen aside, until the strip is scrolled.
            strip.onResized = { if (!seekPanelScrolled) seekPanel.post { if (seekPanelShown && !seekPanelScrolled) centerSeekPanel() } }
        }
        seekPanelScrolled = false
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

    /**
     * A touch on the bars, which sit above the strip's scrim, closes the strip and goes on to
     * the bars. Every touch holds AI work back while it lasts ([PageAdapter.touched]).
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        adapter?.touched(ev.actionMasked)
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
        val own = bookId?.let { library.adjustmentsFor(it) }
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
        val ref = book() ?: return
        library.setAdjustments(ref, if (ownAdjustments) adjustments else null)
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

    private fun applyFullscreen(enabled: Boolean) = Immersive.apply(window, enabled)

    /** Back from a dialog, sheet or menu, the bars hide again in fullscreen. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && prefs.fullscreen) applyFullscreen(true)
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
        val marked = bookId?.let { library.isBookmarked(it, currentIndex) } == true
        menu.findItem(R.id.menu_bookmark)?.apply {
            setIcon(if (marked) R.drawable.ic_bookmark else R.drawable.ic_bookmark_border)
            setTitle(if (marked) R.string.menu_bookmark_remove else R.string.menu_bookmark_add)
        }
        menu.findItem(R.id.menu_auto_scroll)?.isChecked = autoScrolling
        prepareFavorites(menu)
        val drawer = ChapterDrawer.hasEntries(bookChapters, chapters)
        menu.findItem(R.id.menu_book_chapters)?.isVisible = drawer
        menu.findItem(R.id.menu_chapters)?.isVisible = drawer
        menu.findItem(R.id.menu_unhide_pages)?.isEnabled = hiddenCount() > 0
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
        favoriteBook = f != null && bookId?.let { library.isFavorite(it) } == true
        favoriteFolder = f?.parent?.let { library.isFavoriteFolder(it) } == true
    }

    /** Adds the open book to the favorites, or removes it, and says which. */
    private fun toggleFavoriteBook() {
        val ref = keptBook() ?: return
        toast(if (library.toggleFavorite(ref)) R.string.favorite_added else R.string.favorite_removed)
        refreshFavorites()
        invalidateOptionsMenu()
    }

    /** Adds the open book's folder to the favorites, or removes it, and says which. */
    private fun toggleFavoriteFolder() {
        val folder = file?.parent ?: return
        toast(if (library.toggleFavoriteFolder(folder)) R.string.folder_favorite_added else R.string.folder_favorite_removed)
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
                toggleFavoriteBook()
                true
            }
            R.id.menu_favorite_folder -> {
                toggleFavoriteFolder()
                true
            }
            R.id.menu_crop -> { startCrop(); true }
            R.id.menu_hide_page -> { hidePageOnScreen(); true }
            R.id.menu_unhide_pages -> { showHiddenPages(); true }
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
     * frozen layout. Its books no longer detect. Without a folder it is the default. Top to
     * bottom shows the chosen layout; left and right page.
     */
    private fun setReadingDir(dir: String) {
        // The direction on screen may still be chosen, to keep it from detection and the defaults.
        if (dir != DIR_TTB) leaveTallStrip()
        val changed = readingDir != dir
        if (!changed && (layoutFolder() == null || folderDirection())) return
        val oldMode = mode
        readingDir = dir
        directionChoices++
        detectedActive = false
        detection = null
        val folder = layoutFolder()
        // Without a folder to hold the choice, slices would take the direction back on the next layout.
        if (folder == null && dir != DIR_TTB) slices = null
        mode = shownMode()
        if (mode != oldMode) {
            stopAutoScroll()
            if (mode == MODE_PAGED) dropStripAhead()
        }
        when {
            folder == null -> prefs.readingDir = dir
            folderScope() -> persistLayout(dirChosen = true)
            else -> {
                // Slices are still found in a folder read top to bottom ([maySlice]).
                if (folderLayout == null) toast(if (dir == DIR_TTB) R.string.direction_kept_for_folder_ttb else R.string.direction_kept_for_folder)
                setFolder(folder, FolderLayout.Direction(dir))
            }
        }
        invalidateOptionsMenu()
        if (changed || mode != oldMode) attach()
        if (mode != oldMode) prepareStripAhead()
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
        // A folder that kept a left or right direction never checked its pages for slices.
        if (!slicesChecked && maySlice()) detectSlicesLater(book, src, null)
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
        val oldMode = mode
        if (reloadLayout()) attach()
        if (mode != oldMode) prepareStripAhead()
        invalidateOptionsMenu()
        syncSheet()
    }

    /** The layout picked in the sheet: kept, and shown while the direction allows it (continuous reads top to bottom only). */
    private fun setPageMode(newMode: String) {
        if (newMode == chosenMode && newMode == mode) return
        if (newMode != MODE_CONTINUOUS) leaveTallStrip()
        chosenMode = newMode
        layoutPicked = true
        val oldMode = mode
        mode = shownMode()
        persistLayout()
        if (mode == oldMode) return
        stopAutoScroll()
        // Only a continuous column lays the next chapter in behind this one.
        if (mode == MODE_PAGED) dropStripAhead()
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
        if (mode != MODE_PAGED || topToBottom()) {
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
        adapter?.let {
            it.turned = false
            // The page the screen was turned for is declined: it shows at the size the reader picked.
            it.rekey()
        }
        watchUpright()
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
        SheetExit.passTouchesWhileLeaving(dialog)
        bindSheet(view)
        bindAdjustments(view)
        view.findViewById<View>(R.id.sheet_more_settings).setOnClickListener {
            dialog.dismiss()
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        dialog.setOnDismissListener {
            handler.removeCallbacks(followDownloads)
            if (sheet === view) {
                sheet = null
                sheetDialog = null
            }
            persistAdjustments()
        }
        sheet = view
        sheetDialog = dialog
        syncSheet()
        followDownloads.run()
        Immersive.follow(dialog).show()
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
                listOf(R.id.btn_mode_paged to MODE_PAGED, R.id.btn_mode_continuous to MODE_CONTINUOUS),
                { mode }
            ) { setPageMode(it) },
            Segments(
                R.id.sheet_dir,
                listOf(R.id.btn_dir_ltr to "ltr", R.id.btn_dir_rtl to "rtl", R.id.btn_dir_ttb to DIR_TTB, R.id.btn_dir_auto to DIR_AUTO),
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
            ) { setOrientationLock(it) },
            Segments(R.id.sheet_ai_scope, listOf(R.id.btn_ai_book to true, R.id.btn_ai_default to false), { aiOwn() != null }) { setAiOwn(it) }
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
        switch(R.id.sheet_ignore_seams) { setIgnoreSeams(it) }
        switch(R.id.sheet_join_spreads) {
            prefs.joinSpreads = it
            applyJoinSpreads()
        }
        switch(R.id.sheet_rotate_spreads) {
            prefs.rotateSpreads = it
            updateSpreadRotation()
        }
        switch(R.id.sheet_night) { setNight(it) }
        switch(R.id.sheet_auto_next) { setAutoNext(it) }
        switch(R.id.sheet_keep_on) { setKeepOn(it) }
        switch(R.id.sheet_fullscreen) { setFullscreen(it) }
        for ((step, id) in aiPickers) view.findViewById<MaterialButton>(id).setOnClickListener { showAiChoices(step) }
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
                .setText(if (!folderScope()) R.string.scope_all_hint else if (layoutOwn) R.string.scope_book_hint else R.string.scope_folder_hint)
            // "Fit screen" reads as fit width in a column, which reads top to bottom only; two-page and
            // spreads in one file are paged left or right only, split or merged one page a screen.
            view.findViewById<View>(R.id.btn_fit_screen).isEnabled = mode == MODE_PAGED
            view.findViewById<View>(R.id.btn_mode_continuous).isEnabled = topToBottom()
            val sideways = mode == MODE_PAGED && !topToBottom()
            setGroupEnabled(view.findViewById(R.id.sheet_two_page), sideways)
            setGroupEnabled(view.findViewById(R.id.sheet_spreads), sideways && !spread)
            view.findViewById<MaterialSwitch>(R.id.sheet_shift).apply {
                isChecked = shifted
                isEnabled = spread && favoriteFile() != null
            }
            view.findViewById<MaterialSwitch>(R.id.sheet_join_spreads).apply {
                isChecked = prefs.joinSpreads
                isEnabled = sideways
            }
            // Seams play a part only two pages a screen or with spreads merged; split ones never do.
            view.findViewById<MaterialSwitch>(R.id.sheet_ignore_seams).apply {
                isChecked = ignoreSeams
                isEnabled = sideways && prefs.joinSpreads && favoriteFile() != null && (spread || spreadDisplay == SpreadDisplay.MERGE)
            }
            view.findViewById<MaterialSwitch>(R.id.sheet_rotate_spreads).isChecked = prefs.rotateSpreads
            view.findViewById<MaterialSwitch>(R.id.sheet_auto_crop).apply {
                isChecked = autoCrop
                isEnabled = !slicesShown()
            }
            view.findViewById<TextView>(R.id.sheet_spacing_value).text = spacingLabel()
            view.findViewById<Slider>(R.id.sheet_spacing).safeSet(spacingDp.toFloat())
            view.findViewById<MaterialSwitch>(R.id.sheet_night).isChecked = nightFilter
            view.findViewById<MaterialSwitch>(R.id.sheet_auto_next).isChecked = prefs.autoNext
            view.findViewById<TextView>(R.id.sheet_auto_speed_value).text = autoSpeedLabel()
            view.findViewById<Slider>(R.id.sheet_auto_speed).safeSet(AutoScroll.indexOf(prefs.autoSpeed).toFloat())
            view.findViewById<MaterialSwitch>(R.id.sheet_keep_on).isChecked = keepOn
            view.findViewById<MaterialSwitch>(R.id.sheet_fullscreen).isChecked = prefs.fullscreen
            syncAi(view)
        } finally {
            syncingSheet = false
        }
    }

    // ------------------------------------------------------- AI processing

    /** The sheet's model picker of each AI step. */
    private val aiPickers = listOf(
        AiStep.ARTIFACTS to R.id.sheet_ai_artifacts,
        AiStep.DESCREEN to R.id.sheet_ai_descreen,
        AiStep.UPSCALE to R.id.sheet_ai_upscale
    )

    private val aiModels by lazy { ModelStore.of(this) }

    /** The model downloads last shown in the sheet. */
    private var shownDownloads: Downloads? = null

    /**
     * While the sheet is open and a model downloads, shows its progress; asks every
     * [DOWNLOAD_POLL_MS] rather than redrawing at every update.
     */
    private val followDownloads = object : Runnable {
        override fun run() {
            handler.removeCallbacks(this)
            if (sheet == null) return
            val now = ModelDownloads.state.value
            if (now != shownDownloads) {
                shownDownloads = now
                syncSheet()
            }
            if (now.states.values.any { it is DownloadState.Running }) handler.postDelayed(this, DOWNLOAD_POLL_MS)
        }
    }

    /** This book's own AI picks; null when it follows the default. */
    private fun aiOwn(): AiPicks? = bookId?.let { library.aiPicks(it) }

    /** The picks the sheet shows: this book's own, else those chosen in Settings, which run while AI processing is on. */
    private fun aiShown(): AiPicks = aiOwn() ?: AiPicks.of(prefs)

    /**
     * Shows the AI picks this book runs, each model's download state, whose picks they are and
     * whether they run slow. With Keep it simple on, the advanced steps show only when a model
     * is picked there, as in Settings' summary.
     */
    private fun syncAi(view: View) {
        val own = aiOwn()
        val picks = aiShown()
        val states = ModelDownloads.state.value.states
        for ((step, id) in aiPickers) {
            val m = ModelCatalog.byId(picks.id(step))?.takeIf { it.step == step }
            val button = view.findViewById<MaterialButton>(id)
            button.text = ModelText.choice(this, m, m != null && aiModels.installed(m), m?.let { states[it.id] })
            val shown = step == AiStep.UPSCALE || !prefs.keepSimple || m != null
            button.visibility = if (shown) View.VISIBLE else View.GONE
            aiLabels[step]?.let { view.findViewById<View>(it).visibility = button.visibility }
        }
        view.findViewById<View>(R.id.btn_ai_book).isEnabled = book() != null
        val hint = getString(
            when {
                own != null -> R.string.ai_scope_book_hint
                prefs.aiEnabled -> R.string.ai_scope_default_hint
                else -> R.string.ai_scope_default_off_hint
            }
        )
        view.findViewById<TextView>(R.id.sheet_ai_hint).text =
            if ((own != null || prefs.aiEnabled) && AiChain.slow(picks.models)) hint + "\n" + getString(R.string.ai_chain_slow) else hint
    }

    /** The label above each advanced step's picker. */
    private val aiLabels = mapOf(AiStep.ARTIFACTS to R.id.sheet_ai_artifacts_label, AiStep.DESCREEN to R.id.sheet_ai_descreen_label)

    /** Gives this book its own AI picks, starting from the default ones, or hands it back to the default. */
    private fun setAiOwn(own: Boolean) {
        val book = book() ?: return
        if (own == (aiOwn() != null)) return
        library.setAiPicks(book, if (own) AiChain.startingPicks(prefs) else null)
        aiChanged()
    }

    /**
     * The models [step] can run, with what they do, the one this book runs checked; slow ones
     * only while Keep it simple is off, or when running ([ModelCatalog.offered]).
     */
    private fun showAiChoices(step: AiStep) {
        val current = aiShown().id(step)
        val (entries, ids) = ModelText.choices(this, step, prefs.keepSimple, current)
        AlertDialog.Builder(this)
            .setTitle(ModelText.step(this, step))
            .setSingleChoiceItems(ModelText.adapter(this, entries), ids.indexOf(current ?: Prefs.AI_NONE).coerceAtLeast(0)) { dialog, which ->
                dialog.dismiss()
                pickAi(step, ids[which].takeIf { it != Prefs.AI_NONE })
            }
            .setNegativeButton(R.string.cancel, null)
            .showKeepingFullscreen()
    }

    /**
     * Runs model [id] (null: none) at [step]: for this book when it has its own picks, else as
     * the default. Picking a model while AI processing is off turns it on with the models the
     * sheet shows, the other steps' picks kept; leaving no model turns it off. A model not
     * downloaded starts downloading.
     */
    private fun pickAi(step: AiStep, id: String?) {
        val own = aiOwn()
        val book = book()
        if (own != null && book != null) {
            library.setAiPicks(book, own.with(step, id))
        } else {
            prefs.setAiModel(step, id)
            if (id != null) {
                prefs.aiEnabled = true
            } else if (prefs.aiEnabled && AiPicks.of(prefs).models.isEmpty()) {
                prefs.aiEnabled = false
            }
        }
        ModelCatalog.byId(id)?.let { m -> if (!aiModels.installed(m)) ModelDownloads.start(aiModels, m) }
        aiChanged()
        followDownloads.run()
    }

    /**
     * Reads the books' AI chains again; the pages shown go through the new chain when this
     * book's or the next chapter's changed.
     */
    private fun aiChanged() {
        val books = listOfNotNull(source, stripAhead).map { it.bookKey }
        val before = books.map { readerAi.chainFor(it) }
        readerAi.refresh()
        if (books.map { readerAi.chainFor(it) } != before) adapter?.aiChanged()
        aiBooks()
        syncSheet()
    }

    /**
     * Tells the reader's AI the IDs found for the open books (their own picks apply even when
     * their keys hold none), and closes the models none of them runs.
     */
    private fun aiBooks() {
        readerAi.know(source?.bookKey, bookId)
        readerAi.know(stripAhead?.bookKey, stripAheadId)
        val keep = listOfNotNull(source, stripAhead).flatMapTo(HashSet()) { s -> readerAi.chainFor(s.bookKey).map { it.id } }
        readerAi.closeModels(keep)
        followModels(keep.isNotEmpty())
    }

    /** Wakes pages waiting for a model ([followModels]); null while no open book runs one. */
    private var modelsJob: Job? = null

    /**
     * While [on] (an open book runs models), lets pages waiting for a model go on once it is in,
     * whether or not the sheet is open, and once one failed, when the queue stops asking for it.
     */
    private fun followModels(on: Boolean) {
        if (!on) {
            modelsJob?.cancel()
            modelsJob = null
            return
        }
        if (modelsJob != null) return
        modelsJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ModelDownloads.state.map { d -> d.generation to d.states.filterValues { it is DownloadState.Failed }.keys }
                    .distinctUntilChanged()
                    .collect { adapter?.aiWake() }
            }
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
     * offering the end-of-chapter prompt. In a continuous column the next chapter is
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
                    val upright = mode != MODE_PAGED || topToBottom()
                    // "Forward" is leftward in a paged book (rightward in RTL) and a
                    // flick up for a book read top to bottom, paged or continuous.
                    val along = if (upright) -dy else if (readingDir == "rtl") dx else -dx
                    val across = if (upright) abs(dx) else abs(dy)
                    if (tracking && along > across * 2 && along > swipeMinPx() &&
                        autoAdvance() && atChapterEnd()
                    ) {
                        openNextChapter()
                    }
                    // Backward: rightward in a paged book (leftward in RTL), a flick down top to bottom.
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
        val read = library.readSet()
        // By path alone: no stat per chapter on the main thread.
        val readChapters = if (read.isEmpty()) emptySet() else chapters.filter { f -> BookIds.last(f.absolutePath)?.let { it in read } == true }.mapTo(HashSet()) { it.absolutePath }
        val rows = ChapterDrawer.rows(bookChapters, currentIndex, chapters, file?.absolutePath, readChapters)
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
        Immersive.follow(dialog).show()
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
        val hidden = hiddenCount()
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
            row(R.string.info_pages, if (hidden > 0) getString(R.string.info_pages_hidden, pages, hidden) else pages.toString())
            row(R.string.menu_reading_dir, direction)
            val read = src.nameCharset
            val book = favoriteFile()?.absolutePath
            if (read != null && book != null) row(R.string.info_name_charset, NameCharsetDialog.describe(this@ViewerActivity, library, bookId, prefs.charset, read))
            val fields = info?.fields.orEmpty()
            if (fields.isNotEmpty()) row(R.string.info_comic_info, fields.joinToString("\n") { (k, v) -> "$k: $v" })
            val dialog = AlertDialog.Builder(this@ViewerActivity)
                .setTitle(chapterLabel(f))
                .setMessage(text)
                .setPositiveButton(R.string.close, null)
            // An advanced setting: offered once Keep it simple is off.
            if (read != null && book != null && !prefs.keepSimple) {
                dialog.setNeutralButton(R.string.menu_name_charset) { _, _ ->
                    NameCharsetDialog.builder(this@ViewerActivity, library, bookId, prefs.charset) { switchCharset(it) }.showKeepingFullscreen()
                }
            }
            dialog.showKeepingFullscreen()
        }
    }

    // ------------------------------------------------------------------ share / save page

    private fun fileSafeName(page: Int): String {
        val base = file?.let { chapterLabel(it) } ?: "page"
        return base.replace(Regex("[^A-Za-z0-9._-]"), "_").take(60) + "_p" + (page + 1)
    }

    /**
     * Page ▸ actions for [page], from a long-press on it: share, save, crop, hide, and
     * rotating the screen.
     */
    private fun showPageActions(page: Int) {
        if (source == null) return
        val actions = listOf<Pair<Int, () -> Unit>>(
            R.string.menu_share_page to { exportPage(share = true, page = page) },
            R.string.menu_save_page to { exportPage(share = false, page = page) },
            R.string.menu_crop to { startCrop(page) },
            R.string.menu_hide_page to { hidePage(page) },
            R.string.menu_rotate to { cycleOrientation() }
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.page_actions_title, page + 1))
            .setItems(actions.map { getString(it.first) }.toTypedArray()) { _, which -> actions[which].second() }
            .showKeepingFullscreen()
    }

    /** Hides the page on screen, or the one picked when two pages share it. */
    private fun hidePageOnScreen() {
        val src = source ?: return
        val pages = layout.slots(layout.itemOf(currentIndex), src.pageCount).filter { it != PageLayout.NONE }.distinct()
        if (pages.size < 2) return hidePage(currentIndex)
        AlertDialog.Builder(this)
            .setTitle(R.string.hide_which_page)
            .setItems(pages.map { getString(R.string.page_number, it + 1) }.toTypedArray()) { _, which -> hidePage(pages[which]) }
            .showKeepingFullscreen()
    }

    /** Pages hidden in the open book. */
    private fun hiddenCount(): Int = source?.let { HiddenPages.bookCount(it) - it.pageCount } ?: 0

    /** Leaves [page] out of the open book; the last page shown stays. */
    private fun hidePage(page: Int) {
        val f = file ?: return
        val src = source ?: return
        if (page !in 0 until src.pageCount) return
        if (src.pageCount <= 1) {
            toast(R.string.hide_last_page)
            return
        }
        keptBook() ?: return
        applyHidden(library.hiddenPages(bookId) + HiddenPages.bookIndex(src, page))
        toast(R.string.page_hidden)
    }

    /**
     * The pages hidden in the open book as a grid, numbered as in the book: tapping
     * marks pages to show again.
     */
    private fun showHiddenPages() {
        val f = file ?: return
        val src = source as? HidingSource ?: return
        val hidden = library.hiddenPages(bookId).filter { it < src.inner.pageCount }.sorted()
        if (hidden.isEmpty()) return
        val picked = HashSet<Int>()
        val grid = RecyclerView(this)
        grid.id = R.id.hidden_pages_grid
        val pad = (12 * resources.displayMetrics.density).toInt()
        grid.setPadding(pad, pad, pad, 0)
        grid.layoutManager = GridLayoutManager(this, HIDDEN_COLUMNS)
        lateinit var dialog: AlertDialog
        // Never closed here: it reads through the open book, which the reader closes.
        val pages = HidingSource(src.inner, hidden.toIntArray())
        val thumbs = ThumbAdapter(
            pages, -1, prefs.linearLight, prefs.thumbScaling,
            marked = { it in picked },
            labelOf = { (hidden[it] + 1).toString() }
        ) { position ->
            if (position < 0) return@ThumbAdapter
            if (!picked.add(position)) picked.remove(position)
            grid.adapter?.notifyItemChanged(position)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = picked.isNotEmpty()
        }
        grid.adapter = thumbs
        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.unhide_pages_title)
            .setView(grid)
            .setPositiveButton(R.string.unhide) { _, _ ->
                applyHidden(hidden.filterIndexed { i, _ -> i !in picked }.toSet())
            }
            .setNeutralButton(R.string.unhide_all) { _, _ -> applyHidden(emptySet()) }
            .setNegativeButton(R.string.cancel, null)
            .setOnDismissListener { thumbs.release() }
            .showKeepingFullscreen()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = false
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
        val id = keptBook()?.id ?: return
        if (library.isBookmarked(id, currentIndex)) {
            library.removeBookmark(id, currentIndex)
            toast(R.string.bookmark_removed)
        } else {
            library.addBookmark(Bookmark(f.absolutePath, f.name, currentIndex, "", System.currentTimeMillis(), id))
            toast(R.string.bookmark_saved)
        }
        invalidateOptionsMenu()
    }

    private fun showBookmarks() {
        val f = file ?: return
        val marks = bookId?.let { library.bookmarksFor(it) }.orEmpty()
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
                    marks.forEach { library.removeBookmark(it.id, it.page) }
                    invalidateOptionsMenu()
                }
            }
            .showKeepingFullscreen()
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
            .showKeepingFullscreen()
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

        /** How long the open book's scans wait at most for its first page to show ([startScans]). */
        private const val SCAN_HOLD_MS = 2_000L

        /** [timeShareWait]'s item while a book opens. */
        private const val OPENING = -2

        /** How long the reader waits on a share before saying so ([updateShareWait]). */
        private const val SHARE_WAIT_NOTE_MS = 2_000L

        /** How long a share keeps a request unanswered before the reader counts it as waiting on it ([updateShareWait]). */
        private const val SHARE_SILENT_MS = 1_000L

        /** How often a blank screen on a share checks whether the share stalled or answered. */
        private const val SHARE_WAIT_CHECK_MS = 500L

        /** How long after the last page newly on screen the pages seen are stored. */
        private const val VISITS_DELAY_MS = 5_000L

        /** Reads and writes pages seen one at a time, in order, so a book opened again reads what was just stored. */
        private val seenIo = Executors.newSingleThreadExecutor { r -> Thread(r, "pages-seen").apply { isDaemon = true } }.asCoroutineDispatcher()

        /** Stores pages seen, outliving the reader that saw them. */
        private val visitWrites = CoroutineScope(SupervisorJob() + seenIo)

        /** Pages either way of the one on screen whose seams are checked where pages are costly to read. */
        private const val SEAM_WINDOW = 6

        /** Seams checked before the first layout with them, then between layouts. */
        private const val SEAM_FIRST = 4
        private const val SEAM_BATCH = 24

        /** A start page past any book's end: opens it at its last page. */
        private const val LAST_PAGE = Int.MAX_VALUE

        /** How long the detected-direction note shows on open. */
        private const val DETECTION_NOTE_MS = 3_000L

        /** The sheet's "Auto" direction: none of the folder's own. */
        private const val DIR_AUTO = "auto"

        private const val SHARE_DIR = "shared"

        /** Columns of the hidden pages grid. */
        private const val HIDDEN_COLUMNS = 4
        private const val EXPORT_W = 2400
        private const val EXPORT_H = 3200
        private const val MAX_SESSION_SECONDS = 4L * 3600
        private const val FRAME_MS = 16L

        /** How much later than posted an auto-scroll tick may count. */
        private const val MAX_LATE_MS = 100L

        /** How long, as a share of the screen height, a brightness drag holds at 0. */
        private const val BRIGHTNESS_HOLD = 0.15f

        private const val MODE_PAGED = ReaderLayout.MODE_PAGED
        private const val MODE_CONTINUOUS = ReaderLayout.MODE_CONTINUOUS
        private const val DIR_TTB = ReaderLayout.DIR_TTB

        /** How often the open sheet asks how a model download goes ([followDownloads]). */
        private const val DOWNLOAD_POLL_MS = 500L
    }
}
