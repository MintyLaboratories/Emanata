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
 * Decoding happens off the main thread, at most [DECODE_THREADS] pages at a time.
 * Results live in a byte-budgeted cache, a page that is requested twice shares one
 * decode, a decode nobody is waiting for any more is dropped before it starts, and
 * the next [preload] pages are decoded ahead of time. A page whose decode yields a
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
    private val twoPage: Boolean = false,
    private val rtl: Boolean = false,
    private val verticalDrag: ZoomImageView.VerticalDrag? = null,
    val autoCrop: Boolean = false,
    private val linear: Boolean = false,
    private val pageScaling: Scaling = Scaling.PLAIN,
    private val zoomScaling: Scaling = Scaling.PLAIN,
    private val preload: Int = 2,
    private val fling: Boolean = false,
    private val onLongPress: ((Int) -> Unit)? = null,
    pageLayout: PageLayout? = null,
    private val bounds: PageBounds = PageBounds(),
    private val onLoaded: ((Int) -> Unit)? = null
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

    /** A page of [tallPages] that shows from its bottom, as one turned back to; -1 for none. */
    private var fromBottom = NO_PAGE

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
    }

    /** A page decoded for one exact [target]; the same page at another size is another entry. */
    internal data class PageKey(val page: Int, val target: Target) {
        /** The part of the layout it shows. */
        val part: PageLayout.Part get() = PageLayout.Part(page, target.half)

        /** Which part this is, as [PageHolder.shown] keeps it. */
        val code: Int get() = codeOf(part)
    }

    private val scope = CoroutineScope(SupervisorJob() + AppDispatchers.main)
    private val gate = Semaphore(DECODE_THREADS)
    private val cache = object : LruCache<PageKey, Bitmap>(cacheBytes()) {
        override fun sizeOf(key: PageKey, value: Bitmap): Int = value.byteCount
    }
    private val pending = HashMap<PageKey, Pending>()

    /** True while any decode has not finished and been put away; for tests. */
    internal val decoding: Boolean get() = pending.isNotEmpty()

    /**
     * The pages decoded from [source] this adapter holds, for one that replaces it ([adopt]);
     * none unless paged, where pages are numbered from the book's first.
     */
    internal fun decoded(source: PageSource): Map<PageKey, Bitmap> =
        if (released || mode != Mode.PAGED || strip.chapters.firstOrNull()?.value?.source !== source) emptyMap() else cache.snapshot()

    /** Takes the [pages] an adapter of the same pages decoded, so those it showed show at once here. */
    internal fun adopt(pages: Map<PageKey, Bitmap>) {
        for ((key, bitmap) in pages) if (!bitmap.isRecycled) cache.put(key, bitmap)
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
        recyclerView.context.getSystemService(ActivityManager::class.java)?.let { am ->
            detailBudget = DetailStore.budget(ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem)
        }
        val listener = View.OnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            val w = right - left
            val h = bottom - top
            if (w > 0 && h > 0 && (w != sizedW || h != sizedH)) {
                sizedW = w
                sizedH = h
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
        val image = tallImage(position) ?: return
        image.startAtBottom = atBottom
        image.fit()
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
    private fun tapped(image: View, x: Float, y: Float) {
        val list = recycler
        if (list == null || list.width <= 0 || list.height <= 0) {
            onTap(x, y)
            return
        }
        image.getLocationOnScreen(tapAt)
        list.getLocationOnScreen(listAt)
        onTap(
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
        val entry = request(key)
        entry.waiters++
        holder.decodes[slot] = entry
        holder.waiters[slot] = scope.launch {
            // A smaller version of the page, when its decode yields one first, shows until it is done.
            launch {
                val standIn = entry.standIn.await() ?: return@launch
                if (entry.deferred.isActive && holder.shown[slot] != key.code) applyBitmap(holder, slot, key, standIn, exact = false)
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
        val (w, h) = itemSize()
        sizedW = w
        sizedH = h
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
        scope.launch {
            try {
                withContext(AppDispatchers.io) {
                    gate.withPermit {
                        runCatching {
                            Decoders.measuring({ box, w, h -> bounds[page] = PageBounds.Found(box, w, h) }) {
                                seg.source.loadPage(page - chapter.first, MEASURE).recycle()
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
        ReadProbes.current?.shown(image, pageOf[image] ?: NO_PAGE, exact = true)
    }

    // ------------------------------------------------------------------ decoding

    private fun cacheBytes(): Int =
        (Runtime.getRuntime().maxMemory() / 6L).toInt().coerceAtLeast(MIN_CACHE_BYTES)

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
        // Only a running decode is joined: a finished one is merely not forgotten yet, and
        // a transient failure must run again.
        pending[key]?.takeIf { it.deferred.isActive }?.let { return it }
        val pageIndex = key.page
        val chapter = strip.chapterOf(pageIndex)
        val seg = chapter.value
        // Keep the chapter open while its page is being decoded, even if the strip
        // rolls on past it in the meantime.
        seg.loads.incrementAndGet()
        val entry = Pending(key, CompletableDeferred())
        val standIn = entry.standIn
        val queued = Traces.queued("page $pageIndex queued")
        val deferred = scope.async(AppDispatchers.io) {
            gate.withPermit {
                queued.end()
                val job = coroutineContext.job
                val bitmap = Traces.section({ "page $pageIndex ${key.target.width}x${key.target.height}" }) {
                    learning(key) {
                        Decoders.standingIn({ standIn.complete(frozen(it)) }, { entry.waiters > 0 }) {
                            decode(seg.source, pageIndex - chapter.first, pageIndex, key.target) { job.isCancelled }
                        }
                    }?.let { frozen(it) }
                }
                if (bitmap != null && bitmap.width > 0) {
                    cache.put(key, bitmap)
                    aspects[pageIndex] = bitmap.height.toFloat() / bitmap.width.toFloat()
                }
                bitmap
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
            // Runs on the decoder thread; hop back before touching [pending].
            scope.launch {
                if (pending[key] === entry) pending.remove(key)
                if (error == null) {
                    onLoaded?.invoke(pageIndex)
                    ReadProbes.current?.decoded(pageIndex)
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

    /** Decodes the [preload] parts on the items after [position] into the cache. */
    private fun prefetchAfter(position: Int) {
        prefetchFrom = position
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
                if (cache.get(key) != null || pending[key] != null || permanentFailure(key) != null) continue
                request(key).prefetch = true
            }
            item++
        }
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

        /** Item view types: one page, a two-slot row, or two pages shown as one picture. */
        private const val SINGLE = 0
        private const val ROW = 1
        private const val JOINED = 2
        private const val DECODE_THREADS = 2
        private const val MIN_CACHE_BYTES = 24 * 1024 * 1024
        private const val MAX_STRIP_PRELOAD = 2

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
