package com.infinity.comicreader.ui

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import com.infinity.comicreader.R
import com.infinity.comicreader.ai.AiOutcome
import com.infinity.comicreader.ai.AiQueue
import com.infinity.comicreader.ai.Fallback
import com.infinity.comicreader.ai.PageEnhancer
import com.infinity.comicreader.reader.Adjustments
import com.infinity.comicreader.reader.Box
import com.infinity.comicreader.reader.Cancellation
import com.infinity.comicreader.reader.DecodeException
import com.infinity.comicreader.reader.Decoders
import com.infinity.comicreader.reader.Filters
import com.infinity.comicreader.reader.Half
import com.infinity.comicreader.reader.Look
import com.infinity.comicreader.reader.PageDetail
import com.infinity.comicreader.reader.PageSource
import com.infinity.comicreader.reader.PairCrop
import com.infinity.comicreader.reader.Region
import com.infinity.comicreader.reader.Scaling
import com.infinity.comicreader.reader.Target
import com.infinity.comicreader.reader.Urgency
import com.infinity.comicreader.util.AppDispatchers
import com.infinity.comicreader.util.ReadProbes
import com.infinity.comicreader.util.Traces
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Renders [PageSource] pages into zoomable images.
 *
 * Two layouts share one adapter:
 *  - [Mode.PAGED]      a [androidx.viewpager2.widget.ViewPager2], one page (or a
 *                      two-page spread) at a time, swiped sideways or up;
 *  - [Mode.CONTINUOUS] one column read top to bottom, each item sized from its page's
 *                      aspect ratio with the page gap below it (none for webtoons).
 *
 * Decoding happens off the main thread, at most [decodeThreads] pages at a time.
 * Results live in a byte-budgeted cache, a page that is requested twice shares one
 * decode, a decode nobody is waiting for any more is dropped before it starts, and
 * the next [preload] pages are decoded ahead of time, and with [behind] the screen before the
 * one shown, the cache sized to hold them all ([fitCache]). A page whose decode yields a
 * smaller version first (a layered AVIF's first layer, [Decoders.standingIn]) shows it
 * scaled until the page takes over in place. With [autoCrop] blank borders
 * are trimmed. A page
 * that fails to decode shows an [ErrorPageDrawable] naming it and why, and is not
 * retried until the decode size changes; one that failed for lack of memory or a
 * read error is tried again the next time it is shown.
 *
 * A new fit, page gap or item size shows at once: each page keeps its bitmap, scaled
 * to the new size on the GPU (the zoom filter when enlarged), while the bitmap for the new
 * size is made in the background, from the page's full detail when a zoom already
 * holds it, and then takes over in place ([ZoomImageView.swapBitmap]). The shown pages'
 * bitmaps for other sizes stay cached while room allows, so going back to a recent size
 * is instant too.
 *
 * With an [enhancer], pages of a book with an AI chain go through it ([AiQueue]) once their
 * plain page shows: those of the item being read ([reading]), then the next item's, one at a
 * time; each processed page takes the plain one's place in the cache and in its image, at the
 * same size, so it swaps in without a jump ([ZoomImageView.swapBitmap]). Turning pages faster
 * than they are processed, the work skips ahead to pages the reader reaches once they are done
 * ([AiLead]).
 *
 * A [RecyclerView.Adapter] has a fixed item view type, so callers build a fresh
 * instance whenever the mode, spread, reading direction, [autoCrop], [linear]
 * (linear-light scaling) or the scaling of pages at rest ([pageScaling]) or zoomed
 * ([zoomScaling]) changes. Paged items follow [pageLayout] when one is given (the
 * spread engine's pairs, or pages split in halves), else pages pair by position with
 * [twoPage]. With [autoCrop], the two pages of a pair share one top and bottom crop and
 * show at one height ([PairCrop]), from what auto-crop found on each, kept in [bounds];
 * a page not measured yet is measured first; the halves of one picture ([PageLayout.joined])
 * keep their gutter edges. Paired pages meet at the gutter; the halves of one picture
 * show as one, zooming and panning together ([ZoomImageView.joinWith]).
 *
 * A page larger than one bitmap should hold at its size ([Target.bandsAt]) shows a smaller
 * stand-in, over which its image draws it in bands at its full size ([ZoomImageView.updateBands]),
 * rendered from its full detail; in a continuous column, around what the list shows.
 * In paged reading, [tallPages] show at fit width, from their top.
 *
 * A continuous column may hold more than one chapter ([append]), with
 * [dropTo]/[dropAfter] trimming chapters off either end; pages are addressed by
 * their absolute number in the strip, so the cache survives trims untouched.
 */
class PageAdapter(
    source: PageSource,
    private val mode: Mode,
    private val onTap: (Float, Float) -> Unit,
    private val doubleTapZoom: Boolean,
    /** Where, as fractions of the list's area, a tap acts at once despite [doubleTapZoom] ([ZoomImageView.tapsAtOnce]). */
    private val tapsAtOnce: ((Float, Float) -> Boolean)? = null,
    private val twoPage: Boolean = false,
    private val rtl: Boolean = false,
    private val verticalDrag: ZoomImageView.VerticalDrag? = null,
    val autoCrop: Boolean = false,
    private val linear: Boolean = false,
    private val pageScaling: Scaling = Scaling.PLAIN,
    private val zoomScaling: Scaling = Scaling.PLAIN,
    private val preload: Int = 2,
    /** In paged reading, also decode the screen before the one shown, after those ahead, so a back turn is ready. */
    private val behind: Boolean = false,
    private val fling: Boolean = false,
    private val onLongPress: ((Int) -> Unit)? = null,
    pageLayout: PageLayout? = null,
    private val bounds: PageBounds = PageBounds(),
    private val onLoaded: ((Int) -> Unit)? = null,
    /** Processes pages by AI; null for none. */
    private val enhancer: PageEnhancer? = null
) : RecyclerView.Adapter<PageAdapter.PageHolder>() {

    enum class Mode { PAGED, CONTINUOUS }

    /** Margin around each paged page, or the gap below each page of a continuous column. */
    var spacingPx: Int = 0
        set(value) {
            if (field != value) {
                field = value
                resize()
            }
        }

    var fitMode: ZoomImageView.FitMode = ZoomImageView.FitMode.SCREEN
        set(value) {
            if (field == value) return
            field = value
            resize()
        }

    /** Pages, by number in the strip, that paged reading shows at fit width whatever [fitMode] is. */
    var tallPages: Set<Int> = emptySet()
        set(value) {
            if (field == value) return
            // Before it shows any page, an adapter just takes them.
            val changed = mode == Mode.PAGED && fitMode != ZoomImageView.FitMode.WIDTH && recycler != null
            field = value
            if (changed) resize()
        }

    /**
     * Items the screen turns to landscape for while they show ("Rotate for spreads"), as the
     * reader decides; null when it turns for none. In a portrait layout ([home]), each page is
     * decoded at the size it shows at once the screen settled: those items at [home] turned a
     * quarter, the others at [home], so turning the screen and back re-decodes neither.
     */
    var turnsFor: ((Int) -> Boolean)? = null
        set(value) {
            field = value
            rekey()
        }

    /** The portrait item size the screen comes back to from a turn; null when none is known, or it is not portrait. */
    private var home: IntArray? = null

    /** True from when the screen turns for an item ([turned]) until it is laid out at its size again. */
    private var away = false

    /**
     * True while the screen is turned for an item [turnsFor] names, or turning to it; false once
     * it turns back, when [home] holds until the layout after, or [HOME_WAIT_MS] at most.
     */
    var turned = false
        set(value) {
            if (field == value) return
            field = value
            if (value) {
                away = true
                mainHandler.removeCallbacks(backHome)
            } else {
                mainHandler.removeCallbacks(backHome)
                mainHandler.postDelayed(backHome, HOME_WAIT_MS)
            }
        }

    /** Takes the size laid out now as the one to show at, should the screen stay as it was after a turn. */
    private val backHome = Runnable {
        if (released || turned || !away) return@Runnable
        away = false
        val (w, h) = itemSize()
        val was = home
        home = if (w < h) intArrayOf(w, h) else null
        if (!was.contentEquals(home)) rekey()
    }

    /** Learns the item size [w] x [h] laid out: the [home] size unless the screen is away on a turn. */
    private fun laidOut(w: Int, h: Int) {
        if (turned) return
        if (away) {
            mainHandler.removeCallbacks(backHome)
            away = false
        }
        home = if (w < h) intArrayOf(w, h) else null
    }

    /** The item size [part] is decoded for: as [turnsFor] says once the screen settles, else the size now. */
    private fun sizeFor(part: PageLayout.Part): IntArray {
        val now = itemSize()
        val turns = turnsFor ?: return now
        val h = home ?: return now
        val across = intArrayOf(h[1], h[0])
        // Laid out at neither size (split screen, bars shown): as it is.
        if (!now.contentEquals(h) && !now.contentEquals(across)) return now
        return if (turns(layout.itemOf(part))) across else h
    }

    /** Shows the pages at the sizes [sizeFor] now gives, when the adapter shows any: after [turnsFor] answers otherwise. */
    fun rekey() {
        if (recycler != null && !released) resize()
    }

    /** A page of [tallPages] that shows from its bottom, as one turned back to; -1 for none. */
    private var fromBottom = NO_PAGE

    /**
     * Pages of [tallPages] to land on ([landTall]) from their bottom (true) or top once their
     * item is attached again: one kept aside off screen comes back as it was left, unbound.
     */
    private val landings = HashMap<Int, Boolean>()

    /** The fit page [page] shows at: [fitMode], or fit width for one of [tallPages] in paged reading. */
    private fun fitOf(page: Int): ZoomImageView.FitMode =
        if (mode == Mode.PAGED && page in tallPages) ZoomImageView.FitMode.WIDTH else fitMode

    /**
     * Shows every page at the size the layout now asks for, in place: decodes for an
     * old size are dropped, as are its cached bitmaps of pages not shown, and the items
     * rebind on their own holders ([RESIZE]), which keep their bitmaps until the new ones
     * arrive. Meanwhile [ZoomImageView] draws a kept bitmap enlarged through the zoom filter and
     * shrunk bilinear, which aliases less than the shader at source size. Timed by [timing].
     */
    private fun resize() {
        timing.begin()
        // Runs once the frame showing the stand-ins is drawn.
        Choreographer.getInstance().postFrameCallback { mainHandler.post { if (!released) timing.drawn() } }
        // Canceling can complete handlers inline, which edit [pending]: iterate a copy.
        for (entry in pending.values.toList()) {
            if (entry.key != keyFor(entry.key.part)) {
                entry.deferred.cancel()
                if (pending[entry.key] === entry) pending.remove(entry.key)
            }
        }
        // Old sizes of pages off screen make room for preloading at the new one.
        val shown = images.filter { it.drawable != null }.mapNotNullTo(HashSet()) { pageOf[it] }
        for (key in cache.snapshot().keys) {
            if (key.page !in shown && key != keyFor(key.part)) cache.remove(key)
        }
        notifyItemRangeChanged(0, itemCount, RESIZE)
    }

    /** How long the last [resize] took to show, and to show exactly. */
    private val timing = ResizeTiming()

    /** The page filter, one of [Filters]. */
    var filter: String = Filters.NONE
        set(value) {
            if (field == value) return
            field = value
            applyLook()
        }

    var adjustments: Adjustments = Adjustments.NEUTRAL
        set(value) {
            if (field == value) return
            field = value
            applyLook()
        }

    private var look = Look.NONE

    /** Every image this adapter made that is still alive, shown or waiting in the recycler's caches. */
    private val images: MutableSet<ZoomImageView> = Collections.newSetFromMap(WeakHashMap())

    /** Shows the current [filter] and [adjustments] on every image at once, without binding again. */
    private fun applyLook() {
        look = Look.of(filter, adjustments)
        images.forEach { it.look = look }
    }

    internal class Pending(val key: PageKey, val standIn: CompletableDeferred<Bitmap?>) {
        lateinit var deferred: Deferred<Bitmap?>

        /** Slots waiting for it; the decode reads it to make a stand-in only for a page shown. */
        @Volatile
        var waiters = 0
        var prefetch = false

        /** True once the decode has a decoder ([decodeThreads]) and runs. */
        @Volatile
        var started = false

        /** True once the decode made a bitmap and put it in the cache; set before [deferred] completes. */
        @Volatile
        var decoded = false
    }

    /** A page decoded for one exact [target]; the same page at another size is another entry. */
    internal data class PageKey(val page: Int, val target: Target) {
        /** The part of the layout it shows. */
        val part: PageLayout.Part get() = PageLayout.Part(page, target.half)

        /** Which part this is, as [PageHolder.shown] keeps it. */
        val code: Int get() = codeOf(part)
    }

    private val scope = CoroutineScope(SupervisorJob() + AppDispatchers.main)
    private val gate = Semaphore(decodeThreads.coerceIn(1, MAX_DECODE_THREADS))

    /** Where [gate]'s decoders are taken, one at a time: decodes get them in the order they are asked for. */
    private val inOrder = AppDispatchers.io.limitedParallelism(1)

    /** The heap limit the page cache is sized by ([cacheBytes], [fitCache]); tests set it. */
    internal var heapBytes: Long = Runtime.getRuntime().maxMemory()

    private val cache = object : LruCache<PageKey, Bitmap>(cacheBytes()) {
        override fun sizeOf(key: PageKey, value: Bitmap): Int = value.byteCount
    }

    /** True while no decode is under way ([pending] is empty). */
    private val idle = MutableStateFlow(true)

    /** The decodes under way, by page and size; [idle] follows whether there are any. */
    private val pending = object : HashMap<PageKey, Pending>() {
        override fun put(key: PageKey, value: Pending): Pending? = super.put(key, value).also { idle.value = false }

        override fun remove(key: PageKey): Pending? = super.remove(key).also { if (isEmpty()) idle.value = true }

        override fun clear() {
            super.clear()
            idle.value = true
        }
    }

    /** True while any decode has not finished and been put away. */
    internal val decoding: Boolean get() = pending.isNotEmpty()

    /** How many decodes have not completed yet; [decoding] also counts those not put away. */
    internal val running: Int get() = pending.values.count { it.deferred.isActive }

    /** Returns once no decode is under way ([decoding]): at once when none is, and once released. */
    internal suspend fun awaitIdle() {
        idle.first { it }
    }

    /**
     * The pages decoded from [source] this adapter holds, for one that replaces it ([adopt]);
     * none unless paged, where pages are numbered from the book's first.
     */
    internal fun decoded(source: PageSource): Map<PageKey, Bitmap> =
        if (released || mode != Mode.PAGED || strip.chapters.firstOrNull()?.value?.source !== source) emptyMap() else cache.snapshot()

    /** The pages of [decoded] whose bitmap is processed by AI, for one that replaces this adapter ([adopt]). */
    internal fun processedOf(decoded: Map<PageKey, Bitmap>): Set<PageKey> = processed.filterTo(HashSet()) { it in decoded }

    /**
     * Takes the [pages] an adapter of the same pages decoded, so those it showed show at once
     * here; those [processed] by AI count as such, so they are not processed again and go
     * back to plain should the AI chain change.
     */
    internal fun adopt(pages: Map<PageKey, Bitmap>, processed: Set<PageKey> = emptySet()) {
        for ((key, bitmap) in pages) {
            if (bitmap.isRecycled) continue
            cache.put(key, bitmap)
            if (key in processed) this.processed += key
        }
    }

    /** True when a page on screen is zoomed in. */
    fun zoomed(): Boolean = images.any { it.isAttachedToWindow && it.isZoomed }

    /**
     * True while item [position] shows nothing of a page it holds, not even why it failed:
     * the page is being measured, decoded or bound again, or the item is not bound yet.
     */
    fun blankAt(position: Int): Boolean {
        val holder = recycler?.findViewHolderForAdapterPosition(position) as? PageHolder ?: return true
        return holder.images.any { (pageOf[it] ?: NO_PAGE) in 0 until end && it.drawable == null }
    }

    /**
     * Why a page could not be shown at [target]; [name] is null when its source has no
     * page names. Only a [permanent] failure is shown again without another decode, and
     * only at the size it failed at.
     */
    private class Failure(val index: Int, val name: String?, val reason: String, val permanent: Boolean, val target: Target)

    private val failures = ConcurrentHashMap<Int, Failure>()

    /** The permanent failure of [key]'s page at its size, if any. */
    private fun permanentFailure(key: PageKey): Failure? =
        failures[key.page]?.takeIf { it.permanent && it.target == key.target }
    private val aspects = ConcurrentHashMap<Int, Float>()
    private var learnedAspect = 1.45f
    private var recycler: RecyclerView? = null
    private var fallbackW = 1080
    private var fallbackH = 1920

    /** Item size the bound pages were last sized for; a layout at another size rebinds them. */
    private var sizedW = 0
    private var sizedH = 0
    private var layoutListener: View.OnLayoutChangeListener? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val layout = pageLayout?.takeIf { mode == Mode.PAGED } ?: PageLayout(twoPage && mode == Mode.PAGED, rtl)
    private val spread = layout.spread

    /**
     * True for one page a screen with two-file spreads merged ([PageLayout.joined]): only
     * those items are two-slot rows; a page alone shows as in any one-page layout.
     */
    private val merged = spread && !twoPage

    override fun getItemViewType(position: Int): Int = when {
        !spread || (merged && strip.parts(position).count { it != null } <= 1) -> SINGLE
        strip.joined(position) -> JOINED
        else -> ROW
    }

    /** A chapter's source, open while its pages can be addressed or are decoding. */
    private class Segment(
        val source: PageSource,
        var loads: AtomicInteger = AtomicInteger(0),
        @Volatile var wanted: Boolean = true,
        val closed: AtomicBoolean = AtomicBoolean(false)
    )

    /**
     * The chapters currently in the strip, in reading order. A continuous reader with
     * "auto next chapter" on appends the following chapter, so scrolling
     * simply continues into it instead of stopping at the chapter's end. Chapters
     * already read past are trimmed away without renumbering the decoded-page cache.
     *
     * The strip owns its sources: a chapter stays open while its pages can still be
     * addressed by absolute index, and is closed once it is trimmed off the strip or
     * the adapter is released, whichever comes last. Closing a chapter the moment it
     * is scrolled past would race with decodes that are still in flight for it.
     */
    private val strip = PageStrip(Segment(source), source.pageCount, layout)
    private val end get() = strip.end
    private var released = false

    /** An item's [images], by slot; [joined] when they show one picture, laid over each other. */
    inner class PageHolder(val container: View, val images: List<ZoomImageView>, val joined: Boolean = false) :
        RecyclerView.ViewHolder(container) {
        /** The decode each image is waiting for, and the coroutine waiting for it. */
        internal val decodes = arrayOfNulls<Pending>(images.size)
        val waiters = arrayOfNulls<Job>(images.size)

        /** The part each image shows ([codeOf]), at whatever size, or [NO_PAGE]. */
        val shown = IntArray(images.size) { NO_PAGE }

        /** True where an image shows its page's small preview ([previewOf]), until anything better. */
        val rough = BooleanArray(images.size)
    }

    override fun getItemCount(): Int = strip.itemCount

    /** Lets the images of a continuous column render their bands around what shows as it scrolls. */
    private val bandScroll = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            for (image in images) if (image.isAttachedToWindow) image.updateBands()
        }
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        recycler = recyclerView
        if (mode == Mode.CONTINUOUS) recyclerView.addOnScrollListener(bandScroll)
        recyclerView.addOnScrollListener(aiScroll)
        recyclerView.context.getSystemService(ActivityManager::class.java)?.let { am ->
            detailBudget = DetailStore.budget(ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem)
        }
        val listener = View.OnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            val w = right - left
            val h = bottom - top
            if (w > 0 && h > 0 && (w != sizedW || h != sizedH)) {
                sizedW = w
                sizedH = h
                laidOut(w, h)
                // Pages are decoded for the item size: show them again at the new one,
                // outside this layout pass.
                mainHandler.post { if (!released) resize() }
            }
        }
        layoutListener = listener
        recyclerView.addOnLayoutChangeListener(listener)
        if (mode == Mode.CONTINUOUS) learnAspect()
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        layoutListener?.let { recyclerView.removeOnLayoutChangeListener(it) }
        layoutListener = null
        recyclerView.removeOnScrollListener(bandScroll)
        recyclerView.removeOnScrollListener(aiScroll)
        if (recycler === recyclerView) recycler = null
    }

    /** `[width, height]` of every item: the recycler's size, which items match, or a guess before it is measured. */
    private fun itemSize(): IntArray {
        val rv = recycler
        val w = rv?.width?.takeIf { it > 0 } ?: fallbackW
        val h = rv?.height?.takeIf { it > 0 } ?: fallbackH
        return intArrayOf(w, h)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
        val context = parent.context
        if (parent.width > 0 && parent.height > 0) {
            fallbackW = parent.width
            fallbackH = parent.height
        }
        if (viewType == ROW) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                // Slots are screen order, already flipped for RTL: do not inherit the
                // pager's RTL direction, which would mirror the row a second time.
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            val left = makeImage(context)
            val right = makeImage(context)
            row.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            row.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            return PageHolder(row, listOf(left, right))
        }
        if (viewType == JOINED) {
            // Both pages over the whole item: the left one lays out both and takes the gestures.
            val frame = FrameLayout(context).apply {
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            val left = makeImage(context)
            val right = makeImage(context)
            val whole = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            frame.addView(right, whole)
            frame.addView(left, FrameLayout.LayoutParams(whole))
            left.joinWith(right)
            return PageHolder(frame, listOf(left, right), joined = true)
        }
        val container = FrameLayout(context).apply {
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val image = makeImage(context)
        container.addView(
            image,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        return PageHolder(container, listOf(image))
    }

    private fun makeImage(context: Context): ZoomImageView = ZoomImageView(context).apply {
        doubleTapEnabled = doubleTapZoom
        flingEnabled = fling
        zoomScaling = this@PageAdapter.zoomScaling
        onTap = { x, y -> tapped(this, x, y) }
        this@PageAdapter.tapsAtOnce?.let { at -> tapsAtOnce = { x, y -> inList(this, x, y) { lx, ly -> at(lx, ly) } } }
        if (this@PageAdapter.onLongPress != null) onLongPress = { pressed(this) }
        verticalDrag = this@PageAdapter.verticalDrag
        look = this@PageAdapter.look
        if (mode == Mode.CONTINUOUS) viewport = { out -> listArea(this, out) }
        images += this
    }

    /** Puts the list's area into [out] in [image]'s coordinates; false while it is not in the list. */
    private fun listArea(image: View, out: Rect): Boolean {
        val list = recycler ?: return false
        if (!image.isAttachedToWindow) return false
        out.set(0, 0, list.width, list.height)
        return try {
            list.offsetRectIntoDescendantCoords(image, out)
            true
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    /**
     * Scrolls the page of [tallPages] item [position] shows alone by most of a screen,
     * [forward] or back, gliding; false when it shows none or is already at that end.
     */
    fun scrollTall(position: Int, forward: Boolean): Boolean {
        val image = tallImage(position) ?: return false
        return image.scrollPage(if (forward) TALL_STEP else -TALL_STEP)
    }

    /**
     * Shows the page of [tallPages] item [position] shows alone from its top, or from its
     * bottom when [atBottom] (turned back to), now if it is bound, else once it is.
     */
    fun landTall(position: Int, atBottom: Boolean) {
        if (mode != Mode.PAGED) return
        val part = strip.parts(position).filterNotNull().singleOrNull()
        fromBottom = if (atBottom && part != null && part.page in tallPages) part.page else NO_PAGE
        land(position, atBottom)
    }

    /**
     * Readies the items beside [position] as a swipe starts, as turning to them does: a page
     * of [tallPages] before it shows from its bottom, one after it from its top. Where the
     * swipe ends says which shows bound again ([turnedTo]).
     */
    fun swipingFrom(position: Int) {
        if (mode != Mode.PAGED) return
        land(position + 1, atBottom = false)
        land(position - 1, atBottom = true)
    }

    /**
     * The pager shows item [position] now, turned [back] to or on: its page of [tallPages],
     * bound again, shows from its bottom or its top, as it landed.
     */
    fun turnedTo(position: Int, back: Boolean) {
        if (mode != Mode.PAGED) return
        val part = strip.parts(position).filterNotNull().singleOrNull()
        fromBottom = if (back && part != null && part.page in tallPages) part.page else NO_PAGE
    }

    /** The pager came to rest: landings readied for a swipe that went elsewhere are dropped. */
    fun settled() = landings.clear()

    /** Shows the page of [tallPages] item [position] shows alone from its top or [atBottom]: now if it is attached, else once it is ([landings]). */
    private fun land(position: Int, atBottom: Boolean) {
        if (position !in 0 until itemCount) return
        val page = strip.parts(position).filterNotNull().singleOrNull()?.page?.takeIf { it in tallPages } ?: return
        val image = tallImage(position)
        if (image == null) {
            landings[page] = atBottom
            return
        }
        landings.remove(page)
        image.startAtBottom = atBottom
        image.fit()
    }

    /** An item attached again lands its tall page as [landings] says, before it shows. */
    override fun onViewAttachedToWindow(holder: PageHolder) {
        super.onViewAttachedToWindow(holder)
        if (landings.isEmpty()) return
        for (image in holder.images) {
            if (image.visibility == View.GONE) continue
            val atBottom = landings.remove(pageOf[image] ?: continue) ?: continue
            image.startAtBottom = atBottom
            image.fit()
        }
    }

    /** The image of the page of [tallPages] item [position] shows alone, when it is bound. */
    private fun tallImage(position: Int): ZoomImageView? {
        if (mode != Mode.PAGED) return null
        val part = strip.parts(position).filterNotNull().singleOrNull() ?: return null
        if (part.page !in tallPages) return null
        val holder = recycler?.findViewHolderForAdapterPosition(position) as? PageHolder ?: return null
        return holder.images.firstOrNull { it.visibility != View.GONE && pageOf[it] == part.page }
    }

    /** The page each image last showed, by its number in the strip. */
    private val pageOf = WeakHashMap<ZoomImageView, Int>()

    /**
     * Passes on a long-press of [image] as its page in the first chapter of the strip,
     * the one being read; -1 for a page of a chapter laid in behind it, or none.
     */
    private fun pressed(image: ZoomImageView) {
        val page = pageOf[image] ?: -1
        val first = strip.chapters.firstOrNull()
        onLongPress?.invoke(if (first != null && page in first.first until first.first + first.count) page - first.first else -1)
    }

    private val tapAt = IntArray(2)
    private val listAt = IntArray(2)

    /**
     * Passes on a tap at ([x], [y]), fractions of [image], as fractions of the list's
     * area, so the zones span the screen even across a spread's two pages.
     */
    private fun tapped(image: View, x: Float, y: Float) = inList(image, x, y, onTap)

    /** Calls [to] with ([x], [y]), fractions of [image], as fractions of the list's area. */
    private inline fun <T> inList(image: View, x: Float, y: Float, to: (Float, Float) -> T): T {
        val list = recycler
        if (list == null || list.width <= 0 || list.height <= 0) return to(x, y)
        image.getLocationOnScreen(tapAt)
        list.getLocationOnScreen(listAt)
        return to(
            (tapAt[0] - listAt[0] + x * image.width) / list.width,
            (tapAt[1] - listAt[1] + y * image.height) / list.height
        )
    }

    override fun onBindViewHolder(holder: PageHolder, position: Int) {
        // A one-page item of a merged layout has one image for its row's first slot.
        val parts = strip.parts(position).let { if (holder.images.size < it.size) arrayOf(it.firstOrNull { p -> p != null }) else it }
        for (slot in parts.indices) {
            val part = parts[slot]
            // A lone page of a spread takes the whole row.
            if (holder.images.size > 1) holder.images[slot].visibility = if (part == null) View.GONE else View.VISIBLE
            bindImage(holder, slot, part)
        }
        // The first pages shown go first: reading ahead waits until they are measured and decoded.
        if (!shownOnce && (holder.decodes.any { it != null && it.deferred.isActive } || parts.any { it != null && it.page in measuring })) {
            prefetchFrom = position
            preloadWaits = true
        } else {
            preloadWaits = false
            prefetchAfter(position)
        }
    }

    /**
     * Lays the pages of [next] in behind the ones already shown, so a strip reader
     * rolls from this chapter's last page straight into the next one. Refused in
     * paged mode, whose pages are laid out two at a time and never rolled over.
     * Main thread only.
     */
    fun append(next: PageSource): Boolean {
        if (released) return false
        val added = strip.append(Segment(next), next.pageCount) ?: return false
        notifyItemRangeInserted(added.first, added.count())
        return true
    }

    /**
     * Forgets every strip item before item [position] (a chapter that was read
     * past), leaving item 0 to show that page. Keeps the decoded-page cache valid
     * because pages are addressed by their absolute number, not by item index.
     * Main thread only.
     */
    fun dropTo(position: Int) = forget(strip.dropTo(position))

    /**
     * Undoes an append: throws away every chapter starting at item [position] (used
     * when "auto next chapter" is switched off again mid-chapter). Main thread only.
     */
    fun dropAfter(position: Int) = forget(strip.dropAfter(position))

    /**
     * Applies a trim, closing the chapters leaving the strip. A chapter stays open
     * while its pages can still be addressed by absolute index, so only the item
     * range changes while it is merely trimmed from the layout.
     */
    private fun forget(removal: PageStrip.Removal<Segment>?) {
        removal ?: return
        removal.chapters.forEach { retire(it) }
        notifyItemRangeRemoved(removal.position, removal.count)
    }

    /** Releases a chapter once it is no longer wanted and no decodes are in flight. */
    private fun retire(seg: Segment) {
        seg.wanted = false
        if (seg.loads.get() == 0) closeOnce(seg)
    }

    private fun closeOnce(seg: Segment) {
        if (seg.closed.compareAndSet(false, true)) {
            runCatching { seg.source.close() }
        }
    }

    /** Decrements the load count; closes the source if no more wanted and no more loads. */
    private fun releaseLoad(seg: Segment) {
        if (seg.loads.decrementAndGet() == 0 && !seg.wanted) closeOnce(seg)
    }

    override fun onViewRecycled(holder: PageHolder) {
        super.onViewRecycled(holder)
        details.drop(detailKeys(holder))
        for (i in holder.images.indices) {
            stopWaiting(holder, i)
            clearImage(holder, i)
        }
    }

    /** An item scrolled off screen gives up its pages' full detail. */
    override fun onViewDetachedFromWindow(holder: PageHolder) {
        super.onViewDetachedFromWindow(holder)
        details.drop(detailKeys(holder))
    }

    // ------------------------------------------------------------------ detail

    /** Full-detail pages for the item being zoomed. */
    private val details = DetailStore<PageKey>(scope, AppDispatchers.io)

    /** Memory one item's full-detail pages may take; set from the device's RAM once attached. */
    private var detailBudget = DetailStore.MAX_BUDGET

    /** The pages [holder] shows at full detail, or could, by [detailKey]. */
    private fun detailKeys(holder: PageHolder): List<PageKey> =
        holder.images.mapNotNull { (it.detailSource as? SlotDetail)?.detailKey }

    /**
     * The full-detail pages [holder] keeps: its own, and those of every image drawing bands,
     * which a column may show two of at once.
     */
    private fun keptKeys(holder: PageHolder): List<PageKey> = detailKeys(holder) + bandKeys()

    /** The full-detail pages of every image drawing bands. */
    private fun bandKeys(): List<PageKey> =
        images.filter { it.isAttachedToWindow && it.hasBands }.mapNotNull { (it.detailSource as? SlotDetail)?.detailKey }

    /**
     * What [key]'s full detail is held by: its page and how it is cropped and scaled, not
     * its size or fit ([Target.detail]), so a zoom after a new fit finds it still loaded.
     */
    private fun detailKey(key: PageKey) =
        PageKey(
            key.page,
            Target(
                autoCrop = key.target.autoCrop, linear = key.target.linear, capPixels = key.target.capPixels, half = key.target.half,
                box = key.target.box, scaling = key.target.scaling
            )
        )

    /**
     * The detail of [key]'s page, shown in [holder], for its image's settled zooms, and for
     * its [bands] when its bitmap is a stand-in for a page larger than one bitmap.
     */
    private inner class SlotDetail(private val holder: PageHolder, val key: PageKey, override val bands: Boolean = false) :
        ZoomImageView.DetailSource {
        val detailKey = detailKey(key)

        override fun prepare() {
            if (!released) details.prepare(detailKey, keptKeys(holder), loader())
        }

        override fun render(region: Region, width: Int, height: Int, done: (Bitmap) -> Unit): () -> Unit {
            if (released) return {}
            val job = details.render(detailKey, keptKeys(holder), loader(), { frozen(it.render(region, width, height, linear, zoomScaling)) }, done = done)
            return { job.cancel() }
        }

        override fun renderBand(region: Region, width: Int, height: Int, dropped: () -> Unit, done: (Bitmap?) -> Unit): () -> Unit {
            if (released) return {}
            val job = details.render(
                detailKey, keptKeys(holder), loader(), { frozen(it.render(region, width, height, linear, pageScaling)) },
                failed = { done(null) }, dropped = dropped, done = done
            )
            return { job.cancel() }
        }

        /**
         * Loads the page at full detail within its share of [detailBudget], split between the
         * pages shown and those kept beside them ([keptKeys]); a page drawn in [bands] may be
         * kept beside another's, so it takes half at most.
         */
        fun loader(): () -> PageDetail {
            val chapter = strip.chapterOf(key.page)
            val source = chapter.value.source
            val index = key.page - chapter.first
            val shown = holder.images.filter { it.visibility != View.GONE }
            val kept = (shown.mapNotNull { (it.detailSource as? SlotDetail)?.detailKey } + bandKeys() + detailKey).distinct().size
            val maxBytes = detailBudget / maxOf(shown.size, kept, if (bands) BANDED_SHARES else 1)
            return { source.loadDetail(index, key.target, maxBytes) }
        }
    }

    /** Frees the full-detail pages; zooms that settle later load them again. */
    fun trimMemory() = details.clear()

    // ------------------------------------------------------------------ binding

    private fun bindImage(holder: PageHolder, slot: Int, part: PageLayout.Part?) {
        val image = holder.images[slot]
        stopWaiting(holder, slot)
        val pageIndex = part?.page ?: NO_PAGE
        pageOf[image] = pageIndex
        ReadProbes.current?.bound(image, if (part == null || pageIndex >= end) NO_PAGE else pageIndex)
        // A view used again starts a page from its top unless that page was turned back to.
        image.startAtBottom = pageIndex >= 0 && pageIndex == fromBottom
        if (part == null || pageIndex < 0 || pageIndex >= end) {
            clearImage(holder, slot)
            return
        }
        image.look = look
        prepareContainer(holder, pageIndex)

        val code = codeOf(part)
        val slotType = slotOf(part)
        val key = keyFor(part)
        if (key == null) {
            // The pair's shared crop waits for what auto-crop finds on both pages.
            measureItem(part)
            image.setFitGeometry(null, if (holder.joined) 0.5f else alignOf(slotType))
            if (holder.shown[slot] != code) {
                val standIn = cache.snapshot().entries.lastOrNull { it.key.code == code }
                if (standIn != null) applyBitmap(holder, slot, standIn.key, standIn.value, exact = false) else clearImage(holder, slot)
            }
            return
        }
        if (holder.joined) image.setFitGeometry(joinedBoxOf(key), 0.5f) else image.setFitGeometry(fitBoxOf(key, slotType), alignOf(slotType))
        val cached = cache.get(key)
        if (cached != null) {
            showBitmap(holder, slot, key, cached)
            return
        }
        permanentFailure(key)?.let {
            applyFailure(holder, slot, it)
            return
        }
        // The page at another size stands in, scaled to the new one, until that arrives.
        if (holder.shown[slot] == code) {
            image.setFitMode(fitOf(pageIndex))
            ReadProbes.current?.shown(image, pageIndex, exact = false)
        } else {
            val standIn = cache.snapshot().entries.lastOrNull { it.key.code == code }
            if (standIn != null) applyBitmap(holder, slot, standIn.key, standIn.value, exact = false) else clearImage(holder, slot)
        }
        if (holder.shown[slot] == code) timing.standIn(image)
        if (holder.shown[slot] == code && details.loaded(detailKey(key))) {
            renderFromDetail(holder, slot, key)
            return
        }
        decodeInto(holder, slot, key)
    }

    /** Decodes [key]'s page and shows it in [slot], or why it failed. */
    private fun decodeInto(holder: PageHolder, slot: Int, key: PageKey) {
        // A quick preview read from the book races the page's own read and decode: worth it on
        // opening, or while the decode waits for a decoder; never beside a decode under way.
        val quick = !shownOnce || pending[key]?.let { !it.started } ?: (gate.availablePermits == 0)
        val entry = request(key)
        entry.waiters++
        holder.decodes[slot] = entry
        holder.waiters[slot] = scope.launch {
            // Its small preview, read beside the decodes and cut as the page shows, stands in until anything better does.
            if (holder.shown[slot] != key.code && previewShows(key.target)) launch {
                val chapter = strip.chapterOf(key.page)
                val preview = withContext(AppDispatchers.io) {
                    previewOf(chapter.value.source, key.page - chapter.first, quick)?.let { runCatching { previewPart(it, key.target) }.getOrNull() }
                } ?: return@launch
                if (entry.deferred.isActive && holder.shown[slot] != key.code) {
                    applyBitmap(holder, slot, key, preview, exact = false)
                    holder.rough[slot] = true
                }
            }
            // A smaller version of the page, when its decode yields one first, shows until it is done.
            launch {
                val standIn = entry.standIn.await() ?: return@launch
                if (entry.deferred.isActive && (holder.shown[slot] != key.code || holder.rough[slot])) applyBitmap(holder, slot, key, standIn, exact = false)
            }
            val bitmap = entry.deferred.await()
            if (bitmap == null) {
                failures[key.page]?.let { applyFailure(holder, slot, it) }
                return@launch
            }
            showBitmap(holder, slot, key, bitmap)
            updateLongStripAspect(holder, key.page, bitmap)
        }
    }

    /**
     * Makes [key]'s bitmap from the full detail held for the page shown in [slot], as a
     * settled zoom would, instead of decoding the page again; decodes it when that render
     * fails or the detail is dropped first.
     */
    private fun renderFromDetail(holder: PageHolder, slot: Int, key: PageKey) {
        val shown = (holder.images[slot].drawable as? BitmapDrawable)?.bitmap
        if (shown == null) {
            decodeInto(holder, slot, key)
            return
        }
        val plan = key.target.plan(shown.width.toDouble(), shown.height.toDouble())
        val detail = SlotDetail(holder, key, key.target.bandsAt(shown.width.toDouble(), shown.height.toDouble()))
        // [failed] can run before [DetailStore.render] returns, the decode then waited on.
        var decoding = false
        val job = details.render(detail.detailKey, keptKeys(holder), detail.loader(), {
            frozen(it.render(WHOLE_PAGE, plan.width, plan.height, linear, pageScaling))
        }, failed = {
            decoding = true
            decodeInto(holder, slot, key)
        }) { bitmap ->
            holder.waiters[slot] = null
            cache.put(key, bitmap)
            if (holder.shown[slot] == key.code) showBitmap(holder, slot, key, bitmap)
        }
        if (!decoding) holder.waiters[slot] = job
    }

    /** Where [part] sits in its item: alone, or on the left or right of a pair. */
    private fun slotOf(part: PageLayout.Part): PageTargets.Slot {
        if (!spread) return PageTargets.Slot.ALONE
        val parts = layout.parts(layout.itemOf(part), end)
        return when {
            parts.count { it != null } <= 1 -> PageTargets.Slot.ALONE
            parts[0] == part -> PageTargets.Slot.LEFT
            else -> PageTargets.Slot.RIGHT
        }
    }

    /** Paired pages meet at the gutter; a page alone is centered. */
    private fun alignOf(slot: PageTargets.Slot): Float = when (slot) {
        PageTargets.Slot.LEFT -> 1f
        PageTargets.Slot.RIGHT -> 0f
        PageTargets.Slot.ALONE -> 0.5f
    }

    /** The box a paired page with a shared crop fits into at fit screen: its target, of the pair's one height. */
    private fun fitBoxOf(key: PageKey, slot: PageTargets.Slot): IntArray? {
        val t = key.target
        if (slot == PageTargets.Slot.ALONE || fitOf(key.page) != ZoomImageView.FitMode.SCREEN || t.box == null) return null
        return if (t.width > 0 && t.height > 0) intArrayOf(t.width, t.height) else null
    }

    /**
     * The box two pages shown as one ([ZoomImageView.joinWith]) fit into at fit screen: the
     * item's width and the one height of [key]'s pair, when it has one ([fitBoxOf]).
     */
    private fun joinedBoxOf(key: PageKey): IntArray? {
        val t = key.target
        if (fitOf(key.page) != ZoomImageView.FitMode.SCREEN || t.box == null || t.height <= 0) return null
        return intArrayOf(itemSize()[0], t.height)
    }

    /**
     * The decode [part] needs now: its exact on-screen size for the current item size,
     * fit mode and place in a spread, so it is drawn at 1:1. With auto-crop, a paired page
     * takes the crop it shares with its partner ([PairCrop]) and, at fit screen, the pair's
     * one height; null until both pages are measured ([measureItem]).
     */
    private fun keyFor(part: PageLayout.Part): PageKey? {
        itemSize().let { (w, h) ->
            if (w != sizedW || h != sizedH) laidOut(w, h)
            sizedW = w
            sizedH = h
        }
        val (w, h) = sizeFor(part)
        val slot = slotOf(part)
        val view = PageTargets.viewSize(mode, spread && !merged, w, h, spacingPx, slot)
        val fit = fitOf(part.page)
        var target = PageTargets.target(mode, fit, view[0], view[1], autoCrop, linear, pageScaling)
        if (part.half != Half.WHOLE) target = target.copy(half = part.half)
        if (slot == PageTargets.Slot.ALONE || !autoCrop || fit == ZoomImageView.FitMode.STRETCH) return PageKey(part.page, target)
        val parts = layout.parts(layout.itemOf(part), end)
        val left = parts[0] ?: return PageKey(part.page, target)
        val right = parts[1] ?: return PageKey(part.page, target)
        val foundLeft = bounds[left.page] ?: return null
        val foundRight = bounds[right.page] ?: return null
        // The halves of one picture keep their gutter edges whole.
        val (boxLeft, boxRight) = PairCrop.boxes(foundLeft.box, foundRight.box, spread = layout.joined(layout.itemOf(part)))
        if (fit == ZoomImageView.FitMode.SCREEN) {
            val la = foundLeft.aspect(boxLeft)
            val ra = foundRight.aspect(boxRight)
            if (la > 0 && ra > 0) target = target.copy(height = PageTargets.pairHeight(w, h, la, ra))
        }
        return PageKey(part.page, target.copy(box = if (slot == PageTargets.Slot.LEFT) boxLeft else boxRight))
    }

    /** Pages whose crop [measure] is finding. */
    private val measuring = HashSet<Int>()

    /** Measures the crop of each page of [part]'s item that has none yet. */
    private fun measureItem(part: PageLayout.Part) {
        for (p in layout.parts(layout.itemOf(part), end)) if (p != null && bounds[p.page] == null) measure(p.page)
    }

    /**
     * Finds what auto-crop takes off [page], with a decode sampled down to about
     * [MEASURE_PIXELS] and then to a tiny bitmap that is thrown away; then binds its item
     * again, or preloads it when it is ahead ([prefetchAfter]). A page with no blank
     * border, or one that does not decode, crops alone.
     */
    private fun measure(page: Int) {
        if (released || !measuring.add(page)) return
        val chapter = strip.chapterOf(page)
        val seg = chapter.value
        seg.loads.incrementAndGet()
        // Starts at once: it asks for a decoder in call order, and its finally runs even when a
        // release comes next. It resumes from the IO pool, so posted outside any layout pass.
        scope.launch {
            try {
                withContext(inOrder) {
                    gate.withPermit {
                        withContext(AppDispatchers.io) {
                            runCatching {
                                Decoders.measuring({ box, w, h -> bounds[page] = PageBounds.Found(box, w, h) }) {
                                    seg.source.loadPage(page - chapter.first, MEASURE).recycle()
                                }
                            }
                        }
                    }
                }
            } finally {
                releaseLoad(seg)
                measuring.remove(page)
            }
            if (bounds[page] == null) bounds[page] = PageBounds.Found(Box.FULL, 0, 0)
            notifyItemChanged(layout.itemOf(PageLayout.Part(page)), RESIZE)
            // An item not bound yet waits for no bind: its pages are asked for here, unless the
            // first pages shown are still coming (their item binds again with this crop).
            if (!released && prefetchFrom >= 0 && !preloadWaits) prefetchAfter(prefetchFrom)
        }
    }

    /** Runs a decode of [key], keeping what its auto-crop finds for pairs to share. */
    private fun <T> learning(key: PageKey, decode: () -> T): T =
        if (mode == Mode.PAGED && key.target.autoCrop && key.target.box == null) {
            Decoders.measuring({ box, w, h -> bounds[key.page] = PageBounds.Found(box, w, h) }, decode)
        } else {
            decode()
        }

    /** Forgets the decode [slot] was waiting for; abandons it if nobody else wants it. */
    private fun stopWaiting(holder: PageHolder, slot: Int) {
        timing.done(holder.images[slot])
        holder.waiters[slot]?.cancel()
        holder.waiters[slot] = null
        val entry = holder.decodes[slot] ?: return
        holder.decodes[slot] = null
        entry.waiters--
        if (entry.waiters <= 0 && !entry.prefetch && entry.deferred.isActive) {
            entry.deferred.cancel()
            if (pending[entry.key] === entry) pending.remove(entry.key)
        }
    }

    /** Applies the per-mode container geometry: a column item's height with its gap below, or page spacing. */
    private fun prepareContainer(holder: PageHolder, pageIndex: Int) {
        if (mode == Mode.CONTINUOUS) {
            applyHeight(holder.container, longStripHeight(pageIndex))
            holder.container.setPadding(0, 0, 0, spacingPx)
        } else if (holder.container is FrameLayout && !holder.joined) {
            holder.container.setPadding(spacingPx, spacingPx, spacingPx, spacingPx)
        }
    }

    /**
     * Shows [bitmap], made for [key]: in place of the same page at another size, where
     * and as large as that one shows; otherwise fitted.
     */
    private fun showBitmap(holder: PageHolder, slot: Int, key: PageKey, bitmap: Bitmap) {
        val image = holder.images[slot]
        val fit = fitOf(key.page)
        when {
            holder.shown[slot] != key.code -> applyBitmap(holder, slot, key, bitmap, exact = true)
            (image.drawable as? BitmapDrawable)?.bitmap === bitmap -> image.setFitMode(fit)
            else -> {
                // A cached bitmap can arrive at once, before the stand-in took the new fit.
                if (image.fitMode != fit) image.setFitMode(fit)
                image.swapBitmap(bitmap, slotDetail(holder, key, bitmap))
            }
        }
        ReadProbes.current?.shown(image, key.page, exact = true)
        timing.done(image)
    }

    /**
     * Shows [bitmap], decoded for [key], the page's final bitmap when [exact] rather than a
     * stand-in; settled zooms re-render it from the page's full detail.
     */
    private fun applyBitmap(holder: PageHolder, slot: Int, key: PageKey, bitmap: Bitmap, exact: Boolean) {
        val image = holder.images[slot]
        image.setImageBitmap(bitmap)
        image.setFitMode(fitOf(key.page))
        image.detailSource = slotDetail(holder, key, bitmap)
        holder.shown[slot] = key.code
        holder.rough[slot] = false
        if (!exact) ReadProbes.current?.shown(image, key.page, exact = false)
    }

    /** The detail of [key]'s page shown as [bitmap]: drawn in bands when [bitmap] stands in for a page larger than one bitmap. */
    private fun slotDetail(holder: PageHolder, key: PageKey, bitmap: Bitmap) =
        SlotDetail(holder, key, key.target.bandsAt(bitmap.width.toDouble(), bitmap.height.toDouble()))

    private fun clearImage(holder: PageHolder, slot: Int) {
        val image = holder.images[slot]
        timing.done(image)
        image.detailSource = null
        image.setImageDrawable(null)
        holder.shown[slot] = NO_PAGE
        holder.rough[slot] = false
        ReadProbes.current?.shown(image, NO_PAGE, exact = false)
    }

    private fun applyFailure(holder: PageHolder, slot: Int, failure: Failure) {
        val image = holder.images[slot]
        timing.done(image)
        image.detailSource = null
        val res = image.resources
        val name = failure.name ?: res.getString(R.string.page_number, failure.index + 1)
        image.setImageDrawable(ErrorPageDrawable(res.getString(R.string.error_load), name, failure.reason))
        image.setFitMode(fitOf(pageOf[image] ?: NO_PAGE))
        holder.shown[slot] = NO_PAGE
        holder.rough[slot] = false
        ReadProbes.current?.shown(image, pageOf[image] ?: NO_PAGE, exact = true)
    }

    // ------------------------------------------------------------------ decoding

    /**
     * Page [index] of [source]'s small preview: the one kept from an earlier read, else with
     * [quick], where pages are read in any order ([PageSource.cheapSizes]), a quick one
     * ([PageSource.loadPreview]); null when none comes cheap. Blocking.
     */
    private fun previewOf(source: PageSource, index: Int, quick: Boolean): Bitmap? =
        runCatching { source.keptPreview(index) ?: if (quick && source.cheapSizes) source.loadPreview(index) else null }.getOrNull()

    /** The cache's least size: a sixth of the heap limit. */
    private fun cacheBytes(): Int =
        (heapBytes / 6L).coerceIn(MIN_CACHE_BYTES.toLong(), Int.MAX_VALUE.toLong()).toInt()

    /**
     * Page [index] of [source], shown as [pageIndex]; null after recording why in [failures].
     * Throws [CancellationException] when [canceled] stops the decode first.
     */
    private fun decode(
        source: PageSource,
        index: Int,
        pageIndex: Int,
        target: Target,
        canceled: () -> Boolean
    ): Bitmap? = try {
        Cancellation.during(canceled) { source.loadPage(index, target) }
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        Log.w(TAG, "Failed to load page $index", t)
        val name = runCatching { source.pageName(index) }.getOrNull()
        failures[pageIndex] = Failure(index, name, t.message ?: t.javaClass.simpleName, isPermanent(t), target)
        null
    }

    /** Out-of-memory and read errors may pass on a later try; a page that does not decode will not. */
    private fun isPermanent(t: Throwable): Boolean = when (t) {
        is DecodeException -> !t.transient
        is IOException, is OutOfMemoryError -> false
        else -> true
    }

    /** Starts (or joins) the background decode for [key]. Main thread only. */
    private fun request(key: PageKey): Pending {
        // Joins a decode still running, or one whose bitmap landed after the caller missed it
        // in the cache; a failed or canceled decode runs again.
        pending[key]?.takeIf { it.deferred.isActive || (it.decoded && !it.deferred.isCancelled) }?.let { return it }
        val pageIndex = key.page
        val chapter = strip.chapterOf(pageIndex)
        val seg = chapter.value
        // Keep the chapter open while its page is being decoded, even if the strip
        // rolls on past it in the meantime.
        seg.loads.incrementAndGet()
        val entry = Pending(key, CompletableDeferred())
        val standIn = entry.standIn
        val queued = Traces.queued("page $pageIndex queued")
        // Decoders are taken in the order pages are asked for; each decode then runs on the IO pool.
        val deferred = scope.async(inOrder) {
            gate.withPermit {
                withContext(AppDispatchers.io) {
                    entry.started = true
                    queued.end()
                    val job = coroutineContext.job
                    val bitmap = Traces.section({ "page $pageIndex ${key.target.width}x${key.target.height}" }) {
                        learning(key) {
                            Decoders.standingIn({ standIn.complete(frozen(it)) }, { entry.waiters > 0 }) {
                                // A page read ahead resamples on the later budget until it is shown.
                                Urgency.during({ entry.waiters > 0 }) {
                                    decode(seg.source, pageIndex - chapter.first, pageIndex, key.target) { job.isCancelled }
                                }
                            }
                        }?.let { frozen(it) }
                    }
                    if (bitmap != null && bitmap.width > 0) {
                        cache.put(key, bitmap)
                        aspects[pageIndex] = bitmap.height.toFloat() / bitmap.width.toFloat()
                        entry.decoded = true
                    }
                    bitmap
                }
            }
        }
        deferred.invokeOnCompletion {
            queued.end()
            standIn.complete(null)
            releaseLoad(seg)
        }
        entry.deferred = deferred
        pending[key] = entry
        deferred.invokeOnCompletion { error ->
            // Runs on the decoder thread, or inline here when the decode already ended: queued on
            // the main thread, after the bind asking for it has asked for the rest of its item.
            scope.launch(AppDispatchers.mainQueued) {
                if (pending[key] === entry) pending.remove(key)
                if (error == null) {
                    onLoaded?.invoke(pageIndex)
                    ReadProbes.current?.decoded(pageIndex)
                    // A page decoded afresh shows plain: it is processed again.
                    processed -= key
                    ai?.forget(key)
                    wantAi()
                }
                if (error == null && entry.waiters > 0) shownOnce = true
                if (preloadWaits && !released && pending.values.none { it.waiters > 0 && it.deferred.isActive } && !firstMeasuring()) {
                    preloadWaits = false
                    prefetchAfter(prefetchFrom)
                }
            }
        }
        return entry
    }

    /** The item [prefetchAfter] last preloaded after, or -1. */
    private var prefetchFrom = -1

    /** True while reading ahead of [prefetchFrom] waits for the first pages shown to decode. */
    private var preloadWaits = false

    /** True once a page shown has decoded; from then on reading ahead runs beside the pages shown. */
    private var shownOnce = false

    /** True while auto-crop measures a page of item [prefetchFrom], the one reading ahead waits behind. */
    private fun firstMeasuring(): Boolean =
        prefetchFrom in 0 until itemCount && strip.parts(prefetchFrom).any { it != null && it.page in measuring }

    /** True while the cache holds the screen behind beside those shown and ahead ([fitCache]). */
    private var behindFits = false

    /**
     * Sizes the cache in paged reading for the pages read at once ([cacheFor]), so reading
     * ahead and behind never evicts a page about to show; the screen behind is decoded only
     * where the cache can grow to hold it too ([behindFits]).
     */
    private fun fitCache() {
        if (mode != Mode.PAGED) return
        val size = itemSize()
        val screen = size[0].toLong() * size[1] * 4
        val perScreen = if (twoPage) 2 else 1
        val base = cacheBytes().toLong()
        val cap = heapBytes / MAX_CACHE_SHARE
        val bytes = cacheFor(base, cap, screen, perScreen, preload, behind)
        if (bytes.toInt() != cache.maxSize()) cache.resize(bytes.toInt())
        behindFits = behind && cacheFor(0, Long.MAX_VALUE, screen, perScreen, preload, behind = true) <= bytes
    }

    /**
     * Decodes the [preload] parts on the items after [position] into the cache, then with
     * [behind] those of the item before it.
     */
    private fun prefetchAfter(position: Int) {
        prefetchFrom = position
        fitCache()
        var ahead = if (mode == Mode.CONTINUOUS) minOf(preload, MAX_STRIP_PRELOAD) else preload
        var item = position + 1
        while (ahead > 0 && item < itemCount) {
            for (part in strip.parts(item)) {
                if (part == null || ahead <= 0) continue
                ahead--
                val key = keyFor(part)
                if (key == null) {
                    measureItem(part)
                    continue
                }
                prefetch(key)
            }
            item++
        }
        if (behindFits && position > 0) {
            for (part in strip.parts(position - 1)) {
                if (part == null) continue
                // A page not measured yet waits for a turn back: measuring decodes too.
                keyFor(part)?.let { prefetch(it) }
            }
        }
    }

    /** Decodes [key] into the cache unless it is there, on its way, or known to fail. */
    private fun prefetch(key: PageKey) {
        if (cache.get(key) != null || pending[key] != null || permanentFailure(key) != null) return
        request(key).prefetch = true
    }

    // ------------------------------------------------------------------ AI processing

    /** What processing a page needs that only the main thread may read: its chapter's source, its index there, its plain page's size. */
    private class AiPage(val source: PageSource, val index: Int, val width: Int, val height: Int)

    /** The reader's pace of turns, which AI work keeps ahead of ([AiLead]). */
    private val pace = TurnPace()

    /** Asks for AI work again once the reader has stayed on a page past its pace. */
    private val rethinkAi = Runnable { wantAi() }

    private val aiPages = ConcurrentHashMap<PageKey, AiPage>()

    /** The AI work queue, made once a page of a book with an AI chain shows. */
    private var ai: AiQueue<PageKey>? = null

    private fun queueOf(e: PageEnhancer): AiQueue<PageKey> = ai ?: AiQueue<PageKey>(scope, e.worker, { key, canceled ->
        val p = aiPages[key] ?: return@AiQueue AiOutcome.Skipped(Fallback.UNNEEDED)
        e.enhance(p.source, p.index, key.target, p.width, p.height, { motion.moving }, canceled)
    }, ::enhanced).also {
        ai = it
        if (aiPaused) it.pause()
    }

    /** The reader's touches and scrolling, which AI work waits out between tiles. */
    private val motion = ReaderMotion()

    /** Follows the reader's touches, so AI work waits while it is touched; call with every touch event its window gets. */
    fun touched(action: Int) = motion.touched(action)

    private val aiScroll = object : RecyclerView.OnScrollListener() {
        override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) = motion.scrolled(newState)
    }

    /** True while the reader is out of sight ([pauseAi]). */
    private var aiPaused = false

    /** Stops AI work, the page under way dropped, while the reader is out of sight. */
    fun pauseAi() {
        aiPaused = true
        ai?.pause()
    }

    /** Goes on with AI work from [pauseAi], from the page read. */
    fun resumeAi() {
        aiPaused = false
        ai?.resume()
    }

    /** The item read now, whose pages go through AI first; [NO_PAGE] before [reading] says. */
    private var readingAt = NO_PAGE

    /** Pages whose cached bitmap is the processed one. Main thread only. */
    private val processed = HashSet<PageKey>()

    /** Processes item [position]'s pages, then the next item's, as each shows plain ([enhancer]). Call as the reader moves. */
    fun reading(position: Int) {
        if (readingAt == position) return
        readingAt = position
        pace.turned(position)
        wantAi()
    }

    /**
     * Shows every page plain again and processes it anew: after a book's AI chain changed.
     * Each image keeps its bitmap until the new one arrives.
     */
    fun aiChanged() {
        if (enhancer == null || released) return
        for (key in processed) cache.remove(key)
        processed.clear()
        ai?.reset()
        notifyItemRangeChanged(0, itemCount, RESIZE)
        // Pages cached plain go through the new chain at once; those that were processed once decoded again.
        wantAi()
    }

    /** Ends a wait for AI ([AiOutcome.Later]): a model downloaded, battery saver turned off. */
    fun aiWake() {
        ai?.wake()
    }

    /**
     * Asks for AI work on the pages of the item [reading] and the next one that show plain in
     * the cache; none until every page of the item read is there (or failed), so the page
     * read goes first and AI work never competes with its decode. While the reader turns
     * faster than a page is processed ([AiLead]), the work starts at the first item it reaches
     * once a page is done instead; of the items before, only pages kept on disk are read back
     * (at once), and the page under way goes on.
     */
    private fun wantAi() {
        val e = enhancer ?: return
        if (released || readingAt < 0) return
        mainHandler.removeCallbacks(rethinkAi)
        val read = aiCandidates(e, readingAt)
        val keys = ArrayList<PageKey>()
        if (read != null) {
            val pageMs = readSource()?.let { e.pageMs(it) } ?: 0L
            val lead = AiLead.of(pageMs, pace.paceMs, pace.dwellMs)
            if (lead == 0) {
                keys += read
                keys += aiCandidates(e, readingAt + 1).orEmpty()
            } else {
                val passed = read + (readingAt + 1 until readingAt + lead).flatMap { aiCandidates(e, it).orEmpty() }
                for (key in passed) {
                    val p = aiPages[key] ?: continue
                    if (e.stored(p.source, p.index, key.target, p.width, p.height)) keys += key
                }
                for (item in readingAt + lead..readingAt + lead + 1) keys += aiCandidates(e, item).orEmpty()
                // The page under way is not wasted while the reader may still see it.
                ai?.current?.let { if (it in passed && it !in keys) keys += it }
                pace.paceMs?.let { mainHandler.postDelayed(rethinkAi, (it - pace.dwellMs).coerceAtLeast(0) + RETHINK_MS) }
            }
        }
        if (keys.isNotEmpty() || ai != null) queueOf(e).want(keys)
        aiPages.keys.retainAll(keys.toSet())
    }

    /** The source of the item read. */
    private fun readSource(): PageSource? =
        strip.parts(readingAt).firstOrNull { it != null && it.page < end }?.let { strip.chapterOf(it.page).value.source }

    /**
     * The pages of item [item] that show plain in the cache and may be processed, noted in
     * [aiPages]; null while one of the item read is not there yet (nor failed).
     */
    private fun aiCandidates(e: PageEnhancer, item: Int): List<PageKey>? {
        if (item < 0 || item >= itemCount) return emptyList()
        val keys = ArrayList<PageKey>()
        for (part in strip.parts(item)) {
            if (part == null || part.page >= end) continue
            val chapter = strip.chapterOf(part.page)
            val source = chapter.value.source
            if (!e.enhances(source)) continue
            val key = keyFor(part)
            // A page shown processed already needs nothing.
            if (key != null && key in processed) continue
            val bitmap = key?.let { cache.get(it) }
            if (key == null || bitmap == null) {
                if (item == readingAt && (key == null || permanentFailure(key) == null)) return null
                continue
            }
            // A stand-in for a page drawn in bands is never processed.
            if (key.target.bandsAt(bitmap.width.toDouble(), bitmap.height.toDouble())) continue
            aiPages.putIfAbsent(key, AiPage(source, key.page - chapter.first, bitmap.width, bitmap.height))
            keys += key
        }
        return keys
    }

    /** Shows [done]'s page in place of [key]'s plain one, in the cache and wherever it shows at that size. */
    private fun enhanced(key: PageKey, done: AiOutcome.Done) {
        if (released || key != keyFor(key.part)) return
        val bitmap = frozen(done.bitmap)
        cache.put(key, bitmap)
        processed += key
        ReadProbes.current?.enhanced(key.page, done.ms, done.stored)
        recycler?.let { list ->
            for (i in 0 until list.childCount) {
                val holder = list.getChildViewHolder(list.getChildAt(i)) as? PageHolder ?: continue
                for (slot in holder.images.indices) {
                    if (holder.shown[slot] != key.code || (holder.images[slot].detailSource as? SlotDetail)?.key != key) continue
                    showBitmap(holder, slot, key, bitmap)
                }
            }
        }
        // Where to go on depends on how long the reader has stayed since.
        wantAi()
    }

    // ------------------------------------------------------------------ continuous

    /** Learns each page's real aspect ratio once its bitmap arrives, for a continuous column. */
    private fun updateLongStripAspect(holder: PageHolder, pageIndex: Int, bitmap: Bitmap) {
        if (mode != Mode.CONTINUOUS || bitmap.width <= 0) return
        if (pageIndex == 0) learnedAspect = bitmap.height.toFloat() / bitmap.width.toFloat()
        applyHeight(holder.container, longStripHeight(pageIndex))
    }

    private fun applyHeight(container: View, height: Int) {
        val lp = container.layoutParams
        if (lp == null) {
            container.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
        } else if (lp.height != height) {
            lp.height = height
            container.layoutParams = lp
        }
    }

    private fun longStripHeight(page: Int): Int {
        val width = itemSize()[0]
        return PageTargets.stripHeight(width, aspects[page] ?: learnedAspect, spacingPx)
    }

    private fun learnAspect() {
        val seg = strip.chapters.firstOrNull()?.value ?: return
        // Reading the page size touches the archive, so hold the chapter open
        // while the query is in flight.
        seg.loads.incrementAndGet()
        scope.launch {
            val size = withContext(AppDispatchers.io) {
                runCatching { seg.source.pageSize(0) }.getOrNull()
            }
            releaseLoad(seg)
            if (size != null && size[0] > 0) {
                learnedAspect = size[1].toFloat() / size[0].toFloat()
                aspects.putIfAbsent(0, learnedAspect)
                notifyItemRangeChanged(0, itemCount)
            }
        }
    }

    /**
     * Stops all work. With [closeSources] false the chapters stay open for the
     * adapter that replaces this one, as when a layout change rebuilds it.
     */
    fun release(closeSources: Boolean = true) {
        released = true
        mainHandler.removeCallbacks(rethinkAi)
        ai?.release()
        aiPages.clear()
        details.clear()
        scope.cancel()
        pending.clear()
        cache.evictAll()
        failures.clear()
        val chapters = strip.clear()
        // Every chapter's handle is released here rather than earlier, so a decode
        // still in flight cannot meet a closed archive.
        if (closeSources) chapters.forEach { retire(it) }
    }

    companion object {
        private const val TAG = "PageAdapter"
        private const val NO_PAGE = PageLayout.NONE

        /** Margin past the reader's pace before AI work is asked for again ([rethinkAi]). */
        private const val RETHINK_MS = 50L

        /** Item view types: one page, a two-slot row, or two pages shown as one picture. */
        private const val SINGLE = 0
        private const val ROW = 1
        private const val JOINED = 2
        private const val MAX_DECODE_THREADS = 8

        /** Pages decoded at once by an adapter made from now on. */
        @Volatile
        var decodeThreads: Int = 2

        /** False turns off the screen behind for readers opened from now on (bench A/Bs). */
        @Volatile
        var screenBehind: Boolean = true
        private const val MIN_CACHE_BYTES = 24 * 1024 * 1024

        /** The page cache grows to at most this share of the heap limit: half ([fitCache]). */
        private const val MAX_CACHE_SHARE = 2L

        /**
         * Cache bytes holding the pages read at once, [perScreen] pages to a screen of
         * [screenBytes]: a screen shown, [preload] pages ahead, with [behind] a screen behind,
         * and one page spare (a page at another size, a stand-in); never under [base], nor over
         * [cap] unless [base] is.
         */
        internal fun cacheFor(base: Long, cap: Long, screenBytes: Long, perScreen: Int, preload: Int, behind: Boolean): Long {
            val per = perScreen.coerceAtLeast(1)
            val pages = per + preload.coerceAtLeast(0) + (if (behind) per else 0) + 1
            val need = pages * (screenBytes / per)
            return need.coerceAtMost(cap).coerceAtLeast(base).coerceAtMost(Int.MAX_VALUE.toLong())
        }

        private const val MAX_STRIP_PRELOAD = 2

        /** How long [PageAdapter.home] holds after the screen turns back, should no layout at it follow. */
        private const val HOME_WAIT_MS = 1_500L

        /** Share of a screen a tall page scrolls by at a time ([scrollTall]). */
        const val TALL_STEP = 0.9f

        /** Rebinds an item on its own holder, which keeps its bitmaps meanwhile. */
        private val RESIZE = Any()
        private val WHOLE_PAGE = Region(0.0, 0.0, 1.0, 1.0)

        /**
         * Pixels a decode that only measures a page's crop works on, sampled down by a power
         * of two: about half the size each way for most pages.
         */
        private const val MEASURE_PIXELS = 4_000_000L

        /** Pages drawn in bands whose detail a column may keep at once: one ending, the next starting. */
        private const val BANDED_SHARES = 2

        /** A decode that only measures a page's crop. */
        private val MEASURE = Target(16, 16, autoCrop = true, capPixels = MEASURE_PIXELS)

        /** True when a whole page's preview can show as [target] shows the page: not while auto-crop is still to find its box. */
        internal fun previewShows(target: Target): Boolean = !target.autoCrop || target.box != null

        /** [preview], a whole page made small, cut to what [target] shows of it ([Target.area]): its box, then its half. */
        internal fun previewPart(preview: Bitmap, target: Target): Bitmap {
            if (target.box == null && target.half == Half.WHOLE) return preview
            val a = target.area(preview.width, preview.height)
            return Bitmap.createBitmap(preview, a[0], a[1], a[2] - a[0], a[3] - a[1])
        }

        /** [part] as one number: its page and half. */
        internal fun codeOf(part: PageLayout.Part): Int = part.page * 3 + part.half.ordinal

        /**
         * [bitmap] as an immutable bitmap, copied if it is mutable (which it then recycles):
         * the GPU keeps an immutable bitmap's texture between frames, where a mutable one
         * drawn through a [PageShader] is uploaded again for every frame.
         */
        internal fun frozen(bitmap: Bitmap): Bitmap {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !bitmap.isMutable) return bitmap
            val copy = Traces.section("freeze copy") { bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false) } ?: return bitmap
            bitmap.recycle()
            return copy
        }
    }
}
