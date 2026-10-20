package com.infinity.comicreader.ui

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.util.LruCache
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import com.infinity.comicreader.R
import com.infinity.comicreader.reader.BitmapUtils
import com.infinity.comicreader.reader.DecodeException
import com.infinity.comicreader.reader.Filters
import com.infinity.comicreader.reader.PageSource
import com.infinity.comicreader.util.AppDispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Renders [PageSource] pages into zoomable images.
 *
 * Three layouts share one adapter:
 *  - [Mode.PAGED]     horizontal [androidx.viewpager2.widget.ViewPager2], one page
 *                     (or a two-page spread) at a time;
 *  - [Mode.VERTICAL]  vertical list, one full-height page per screen;
 *  - [Mode.LONG_STRIP] continuous vertical strip sized from each page's aspect
 *                     ratio with no gaps, as used by webtoons/manhwa.
 *
 * Decoding happens off the main thread, at most [DECODE_THREADS] pages at a time.
 * Results live in a byte-budgeted cache, a page that is requested twice shares one
 * decode, a decode nobody is waiting for any more is dropped before it starts, and
 * the next [preload] pages are decoded ahead of time. With [autoCrop] blank borders
 * are trimmed (never in long-strip mode, where the gaps belong to the art). A page
 * that fails to decode shows an [ErrorPageDrawable] naming it and why, and is not
 * retried until the decode size changes; one that failed for lack of memory or a
 * read error is tried again the next time it is shown.
 *
 * A [RecyclerView.Adapter] has a fixed item view type, so callers build a fresh
 * instance whenever the mode, spread, reading direction or [autoCrop] changes.
 *
 * Vertical and long-strip layouts may hold more than one chapter ([append]), with
 * [dropTo]/[dropAfter] trimming chapters off either end; pages are addressed by
 * their absolute number in the strip, so the cache survives trims untouched.
 */
class PageAdapter(
    source: PageSource,
    private val mode: Mode,
    private val onTap: (Float) -> Unit,
    private val doubleTapZoom: Boolean,
    private val twoPage: Boolean = false,
    private val rtl: Boolean = false,
    private val onVerticalDrag: ((Float) -> Unit)? = null,
    private val autoCrop: Boolean = false,
    private val preload: Int = 2
) : RecyclerView.Adapter<PageAdapter.PageHolder>() {

    enum class Mode { PAGED, VERTICAL, LONG_STRIP }

    /** Margin between pages; ignored for long strips, which must be contiguous. */
    var spacingPx: Int = 0
        set(value) {
            if (field != value) {
                field = value
                notifyItemRangeChanged(0, itemCount)
            }
        }

    var fitMode: ZoomImageView.FitMode = ZoomImageView.FitMode.SCREEN
        set(value) {
            if (field == value) return
            field = value
            // Paged pages are decoded for the active fit mode, so they must be redone.
            if (mode == Mode.PAGED) dropDecoded()
            notifyItemRangeChanged(0, itemCount)
        }

    var filter: String = Filters.NONE
        set(value) {
            field = value
            notifyItemRangeChanged(0, itemCount)
        }

    private class Pending(val deferred: Deferred<Bitmap?>) {
        var waiters = 0
        var prefetch = false
    }

    private val scope = CoroutineScope(SupervisorJob() + AppDispatchers.main)
    private val gate = Semaphore(DECODE_THREADS)
    private val cache = object : LruCache<Int, Bitmap>(cacheBytes()) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount
    }
    private val pending = HashMap<Int, Pending>()

    /**
     * Why a page could not be shown; [name] is null when its source has no page names.
     * Only a [permanent] failure is shown again without another decode.
     */
    private class Failure(val index: Int, val name: String?, val reason: String, val permanent: Boolean)

    private val failures = ConcurrentHashMap<Int, Failure>()
    private val aspects = ConcurrentHashMap<Int, Float>()
    private var learnedAspect = 1.45f
    private var recyclerWidth = 0
    private var fallbackWidth = 1080
    private var lastItemW = 1080
    private var lastItemH = 1920
    private var layoutListener: View.OnLayoutChangeListener? = null

    private val spread = twoPage && mode == Mode.PAGED
    private val layout = PageLayout(spread, rtl)

    /** A chapter's source, open while its pages can be addressed or are decoding. */
    private class Segment(
        val source: PageSource,
        var loads: AtomicInteger = AtomicInteger(0),
        @Volatile var wanted: Boolean = true,
        val closed: AtomicBoolean = AtomicBoolean(false)
    )

    /**
     * The chapters currently in the strip, in reading order. A vertical or long-strip
     * reader with "auto next chapter" on appends the following chapter, so scrolling
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

    inner class PageHolder(val container: View, val images: List<ZoomImageView>) :
        RecyclerView.ViewHolder(container) {
        /** The page each image is showing or waiting for, and the coroutine waiting for it. */
        val pages = IntArray(images.size) { NO_PAGE }
        val waiters = arrayOfNulls<Job>(images.size)
    }

    override fun getItemCount(): Int = strip.itemCount

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        recyclerWidth = recyclerView.width
        val listener = View.OnLayoutChangeListener { v, left, _, right, _, _, _, _, _ ->
            val width = right - left
            if (width > 0 && width != recyclerWidth) {
                recyclerWidth = width
                if (mode == Mode.LONG_STRIP) notifyItemRangeChanged(0, itemCount)
            }
            if (v.width > 0) fallbackWidth = v.width
        }
        layoutListener = listener
        recyclerView.addOnLayoutChangeListener(listener)
        if (mode == Mode.LONG_STRIP) learnAspect()
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        layoutListener?.let { recyclerView.removeOnLayoutChangeListener(it) }
        layoutListener = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
        val context = parent.context
        if (parent.width > 0) fallbackWidth = parent.width
        if (spread) {
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
        onTap = { fraction -> this@PageAdapter.onTap(fraction) }
        onVerticalDrag = this@PageAdapter.onVerticalDrag
        colorFilter = Filters.colorFilter(this@PageAdapter.filter)
    }

    override fun onBindViewHolder(holder: PageHolder, position: Int) {
        val pages = strip.slots(position)
        for (slot in pages.indices) {
            val page = pages[slot]
            // The lone last page of a spread takes the whole row.
            if (spread) holder.images[slot].visibility = if (page == NO_PAGE) View.GONE else View.VISIBLE
            bindImage(holder, slot, page)
        }
        prefetchAfter(strip.lastPage(position))
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
        for (i in holder.images.indices) {
            stopWaiting(holder, i)
            holder.images[i].setImageDrawable(null)
        }
    }

    // ------------------------------------------------------------------ binding

    private fun bindImage(holder: PageHolder, slot: Int, pageIndex: Int) {
        val image = holder.images[slot]
        stopWaiting(holder, slot)
        if (pageIndex < 0 || pageIndex >= end) {
            image.setImageDrawable(null)
            return
        }
        image.colorFilter = Filters.colorFilter(filter)
        prepareContainer(holder, pageIndex)

        val cached = cache.get(pageIndex)
        if (cached != null) {
            applyBitmap(image, cached)
            return
        }
        failures[pageIndex]?.takeIf { it.permanent }?.let {
            applyFailure(image, it)
            return
        }
        image.setImageDrawable(null)

        // A spread row's slots may still have their old widths before layout: size
        // from the row, which is the same for paired and lone pages.
        val sized = if (spread) holder.container else image
        lastItemW = if (sized.width > 0) sized.width else fallbackWidth
        lastItemH = if (image.height > 0) image.height else 1920
        val (reqW, reqH) = requestSize(pageIndex, lastItemW, lastItemH)
        val entry = request(pageIndex, reqW, reqH)
        entry.waiters++
        holder.pages[slot] = pageIndex
        holder.waiters[slot] = scope.launch {
            val bitmap = entry.deferred.await()
            if (bitmap == null) {
                failures[pageIndex]?.let { applyFailure(image, it) }
                return@launch
            }
            applyBitmap(image, bitmap)
            updateLongStripAspect(holder, pageIndex, bitmap)
        }
    }

    /** Forgets the page [slot] was waiting for; abandons its decode if nobody else wants it. */
    private fun stopWaiting(holder: PageHolder, slot: Int) {
        holder.waiters[slot]?.cancel()
        holder.waiters[slot] = null
        val page = holder.pages[slot]
        holder.pages[slot] = NO_PAGE
        if (page == NO_PAGE) return
        val entry = pending[page] ?: return
        entry.waiters--
        if (entry.waiters <= 0 && !entry.prefetch && entry.deferred.isActive) {
            entry.deferred.cancel()
            pending.remove(page)
        }
    }

    /** Applies the per-mode container geometry (long-strip height, or page spacing). */
    private fun prepareContainer(holder: PageHolder, pageIndex: Int) {
        if (mode == Mode.LONG_STRIP) {
            applyHeight(holder.container, longStripHeight(pageIndex))
            holder.container.setPadding(0, 0, 0, 0)
        } else if (holder.container is FrameLayout) {
            holder.container.setPadding(spacingPx, spacingPx, spacingPx, spacingPx)
        }
    }

    private fun applyBitmap(image: ZoomImageView, bitmap: Bitmap) {
        image.setImageBitmap(bitmap)
        image.setFitMode(fitMode)
    }

    private fun applyFailure(image: ZoomImageView, failure: Failure) {
        val res = image.resources
        val name = failure.name ?: res.getString(R.string.page_number, failure.index + 1)
        image.setImageDrawable(ErrorPageDrawable(res.getString(R.string.error_load), name, failure.reason))
        image.setFitMode(fitMode)
    }

    // ------------------------------------------------------------------ decoding

    private fun cacheBytes(): Int =
        (Runtime.getRuntime().maxMemory() / 6L).toInt().coerceAtLeast(MIN_CACHE_BYTES)

    /** Page [index] of [source], shown as [pageIndex]; null after recording why in [failures]. */
    private fun decode(source: PageSource, index: Int, pageIndex: Int, reqW: Int, reqH: Int): Bitmap? = try {
        val raw = source.loadPage(index, reqW, reqH)
        if (autoCrop && mode != Mode.LONG_STRIP) BitmapUtils.autoCrop(raw) else raw
    } catch (t: Throwable) {
        Log.w(TAG, "Failed to load page $index", t)
        val name = runCatching { source.pageName(index) }.getOrNull()
        failures[pageIndex] = Failure(index, name, t.message ?: t.javaClass.simpleName, isPermanent(t))
        null
    }

    /** Out-of-memory and read errors may pass on a later try; a page that does not decode will not. */
    private fun isPermanent(t: Throwable): Boolean = when (t) {
        is DecodeException -> !t.transient
        is IOException, is OutOfMemoryError -> false
        else -> true
    }

    /** Starts (or joins) the background decode of [pageIndex]. Main thread only. */
    private fun request(pageIndex: Int, reqW: Int, reqH: Int): Pending {
        pending[pageIndex]?.let { return it }
        val chapter = strip.chapterOf(pageIndex)
        val seg = chapter.value
        // Keep the chapter open while its page is being decoded, even if the strip
        // rolls on past it in the meantime.
        seg.loads.incrementAndGet()
        val deferred = scope.async(AppDispatchers.io) {
            gate.withPermit {
                val bitmap = decode(seg.source, pageIndex - chapter.first, pageIndex, reqW, reqH)
                if (bitmap != null && bitmap.width > 0) {
                    cache.put(pageIndex, bitmap)
                    aspects[pageIndex] = bitmap.height.toFloat() / bitmap.width.toFloat()
                }
                bitmap
            }
        }
        deferred.invokeOnCompletion { releaseLoad(seg) }
        val entry = Pending(deferred)
        pending[pageIndex] = entry
        deferred.invokeOnCompletion {
            // Runs on the decoder thread; hop back before touching [pending].
            scope.launch { if (pending[pageIndex] === entry) pending.remove(pageIndex) }
        }
        return entry
    }

    /** Decodes the [preload] pages after [lastShown] into the cache. */
    private fun prefetchAfter(lastShown: Int) {
        val ahead = if (mode == Mode.LONG_STRIP) minOf(preload, MAX_STRIP_PRELOAD) else preload
        for (k in 1..ahead) {
            val p = lastShown + k
            if (p >= end) break
            if (cache.get(p) != null || pending[p] != null || failures[p]?.permanent == true) continue
            val (reqW, reqH) = requestSize(p, lastItemW, lastItemH)
            request(p, reqW, reqH).prefetch = true
        }
    }

    /** Throws away everything decoded so far (the requested size changed). */
    private fun dropDecoded() {
        // Cancelling can complete handlers inline, which edit [pending]: iterate a copy.
        pending.values.toList().forEach { it.deferred.cancel() }
        pending.clear()
        cache.evictAll()
        failures.clear()
    }

    // ------------------------------------------------------------------ long strip

    /** Learns each page's real aspect ratio once its bitmap arrives, for long strips. */
    private fun updateLongStripAspect(holder: PageHolder, pageIndex: Int, bitmap: Bitmap) {
        if (mode != Mode.LONG_STRIP || bitmap.width <= 0) return
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
        val width = if (recyclerWidth > 0) recyclerWidth else fallbackWidth
        val aspect = aspects[page] ?: learnedAspect
        return (width * aspect).toInt().coerceAtLeast(1)
    }

    /**
     * Decode size for [page] on an item [itemW] x [itemH]: a page paired in a spread
     * gets half the row, a lone one the whole row.
     */
    private fun requestSize(page: Int, itemW: Int, itemH: Int): Pair<Int, Int> {
        val pageW = if (layout.shownAlone(page, end)) itemW else itemW / 2
        val viewW = pageW.coerceAtLeast(360)
        val viewH = itemH.coerceAtLeast(360)
        return when {
            mode == Mode.LONG_STRIP || mode == Mode.VERTICAL -> viewW to 1
            fitMode == ZoomImageView.FitMode.WIDTH -> viewW to 1
            fitMode == ZoomImageView.FitMode.HEIGHT -> 1 to viewH
            else -> viewW to viewH
        }
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
        private const val DECODE_THREADS = 2
        private const val MIN_CACHE_BYTES = 24 * 1024 * 1024
        private const val MAX_STRIP_PRELOAD = 2
    }
}
