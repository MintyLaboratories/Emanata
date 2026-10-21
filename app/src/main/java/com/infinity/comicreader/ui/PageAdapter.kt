package com.infinity.comicreader.ui

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import com.infinity.comicreader.R
import com.infinity.comicreader.reader.Adjustments
import com.infinity.comicreader.reader.Cancellation
import com.infinity.comicreader.reader.DecodeException
import com.infinity.comicreader.reader.Filters
import com.infinity.comicreader.reader.Look
import com.infinity.comicreader.reader.PageDetail
import com.infinity.comicreader.reader.PageSource
import com.infinity.comicreader.reader.Region
import com.infinity.comicreader.reader.Target
import com.infinity.comicreader.util.AppDispatchers
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
 * A new fit, page gap or item size shows at once: each page keeps its bitmap, scaled
 * to the new size on the GPU (Lanczos3 when enlarged), while the bitmap for the new
 * size is made in the background, from the page's full detail when a zoom already
 * holds it, and then takes over in place ([ZoomImageView.swapBitmap]). Bitmaps for
 * other sizes stay cached, so going back to one is instant too.
 *
 * A [RecyclerView.Adapter] has a fixed item view type, so callers build a fresh
 * instance whenever the mode, spread, reading direction, [autoCrop] or [linear]
 * (linear-light scaling) changes.
 *
 * Vertical and long-strip layouts may hold more than one chapter ([append]), with
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
    private val autoCrop: Boolean = false,
    private val linear: Boolean = false,
    private val preload: Int = 2,
    private val fling: Boolean = false,
    private val onLongPress: ((Int) -> Unit)? = null
) : RecyclerView.Adapter<PageAdapter.PageHolder>() {

    enum class Mode { PAGED, VERTICAL, LONG_STRIP }

    /** Margin between pages; ignored for long strips, which must be contiguous. */
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

    /**
     * Shows every page at the size the layout now asks for, in place: decodes for an
     * old size are dropped and the items rebind on their own holders ([RESIZE]), which
     * keep their bitmaps until the new ones arrive.
     */
    private fun resize() {
        // Canceling can complete handlers inline, which edit [pending]: iterate a copy.
        for (entry in pending.values.toList()) {
            if (entry.key != keyFor(entry.key.page)) {
                entry.deferred.cancel()
                if (pending[entry.key] === entry) pending.remove(entry.key)
            }
        }
        notifyItemRangeChanged(0, itemCount, RESIZE)
    }

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

    internal class Pending(val key: PageKey, val deferred: Deferred<Bitmap?>) {
        var waiters = 0
        var prefetch = false
    }

    /** A page decoded for one exact [target]; the same page at another size is another entry. */
    internal data class PageKey(val page: Int, val target: Target)

    private val scope = CoroutineScope(SupervisorJob() + AppDispatchers.main)
    private val gate = Semaphore(DECODE_THREADS)
    private val cache = object : LruCache<PageKey, Bitmap>(cacheBytes()) {
        override fun sizeOf(key: PageKey, value: Bitmap): Int = value.byteCount
    }
    private val pending = HashMap<PageKey, Pending>()

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
        /** The decode each image is waiting for, and the coroutine waiting for it. */
        internal val decodes = arrayOfNulls<Pending>(images.size)
        val waiters = arrayOfNulls<Job>(images.size)

        /** The page each image shows, at whatever size, or [NO_PAGE]. */
        val shown = IntArray(images.size) { NO_PAGE }
    }

    override fun getItemCount(): Int = strip.itemCount

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        recycler = recyclerView
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
        if (mode == Mode.LONG_STRIP) learnAspect()
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        layoutListener?.let { recyclerView.removeOnLayoutChangeListener(it) }
        layoutListener = null
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
        flingEnabled = fling
        onTap = { x, y -> tapped(this, x, y) }
        if (this@PageAdapter.onLongPress != null) onLongPress = { pressed(this) }
        verticalDrag = this@PageAdapter.verticalDrag
        look = this@PageAdapter.look
        images += this
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

    /** The pages [holder] shows at full detail, or could. */
    private fun detailKeys(holder: PageHolder): List<PageKey> =
        holder.images.mapNotNull { (it.detailSource as? SlotDetail)?.key }

    /** The detail of [key]'s page, shown in [holder], for its image's settled zooms. */
    private inner class SlotDetail(private val holder: PageHolder, val key: PageKey) : ZoomImageView.DetailSource {
        override fun prepare() {
            if (!released) details.prepare(key, detailKeys(holder), loader())
        }

        override fun render(region: Region, width: Int, height: Int, done: (Bitmap) -> Unit): () -> Unit {
            if (released) return {}
            val job = details.render(key, detailKeys(holder), loader(), { frozen(it.render(region, width, height, linear)) }, done)
            return { job.cancel() }
        }

        /** Loads the page at full detail within its share of [detailBudget]: the item's, split between the pages shown. */
        fun loader(): () -> PageDetail {
            val chapter = strip.chapterOf(key.page)
            val source = chapter.value.source
            val index = key.page - chapter.first
            val maxBytes = detailBudget / holder.images.count { it.visibility != View.GONE }.coerceAtLeast(1)
            return { source.loadDetail(index, key.target, maxBytes) }
        }
    }

    /** Frees the full-detail pages; zooms that settle later load them again. */
    fun trimMemory() = details.clear()

    // ------------------------------------------------------------------ binding

    private fun bindImage(holder: PageHolder, slot: Int, pageIndex: Int) {
        val image = holder.images[slot]
        stopWaiting(holder, slot)
        pageOf[image] = pageIndex
        if (pageIndex < 0 || pageIndex >= end) {
            clearImage(holder, slot)
            return
        }
        image.look = look
        prepareContainer(holder, pageIndex)

        val key = keyFor(pageIndex)
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
        if (holder.shown[slot] == pageIndex) {
            image.setFitMode(fitMode)
        } else {
            val standIn = cache.snapshot().entries.lastOrNull { it.key.page == pageIndex }
            if (standIn != null) applyBitmap(holder, slot, standIn.key, standIn.value) else clearImage(holder, slot)
        }
        val held = (image.detailSource as? SlotDetail)?.key?.takeIf { holder.shown[slot] == pageIndex && details.loaded(it) }
        if (held != null) {
            renderFromDetail(holder, slot, held, key)
            return
        }

        val entry = request(key)
        entry.waiters++
        holder.decodes[slot] = entry
        holder.waiters[slot] = scope.launch {
            val bitmap = entry.deferred.await()
            if (bitmap == null) {
                failures[pageIndex]?.let { applyFailure(holder, slot, it) }
                return@launch
            }
            showBitmap(holder, slot, key, bitmap)
            updateLongStripAspect(holder, pageIndex, bitmap)
        }
    }

    /**
     * Makes [key]'s bitmap from the full detail [held] keeps of the page shown in [slot],
     * as a settled zoom would, instead of decoding the page again.
     */
    private fun renderFromDetail(holder: PageHolder, slot: Int, held: PageKey, key: PageKey) {
        val shown = (holder.images[slot].drawable as? BitmapDrawable)?.bitmap ?: return
        val plan = key.target.plan(shown.width.toDouble(), shown.height.toDouble())
        val load = SlotDetail(holder, held).loader()
        holder.waiters[slot] = details.render(held, detailKeys(holder), load, {
            frozen(it.render(WHOLE_PAGE, plan.width, plan.height, linear))
        }) { bitmap ->
            holder.waiters[slot] = null
            cache.put(key, bitmap)
            if (holder.shown[slot] == key.page) showBitmap(holder, slot, key, bitmap)
        }
    }

    /**
     * The decode [page] needs now: its exact on-screen size for the current item size,
     * fit mode and place in a spread, so it is drawn at 1:1.
     */
    private fun keyFor(page: Int): PageKey {
        val (w, h) = itemSize()
        sizedW = w
        sizedH = h
        val slot = when {
            !spread || layout.shownAlone(page, end) -> PageTargets.Slot.ALONE
            layout.slotOf(page, end) == 0 -> PageTargets.Slot.LEFT
            else -> PageTargets.Slot.RIGHT
        }
        val view = PageTargets.viewSize(mode, spread, w, h, spacingPx, slot)
        return PageKey(page, PageTargets.target(mode, fitMode, view[0], view[1], autoCrop, linear))
    }

    /** Forgets the decode [slot] was waiting for; abandons it if nobody else wants it. */
    private fun stopWaiting(holder: PageHolder, slot: Int) {
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

    /** Applies the per-mode container geometry (long-strip height, or page spacing). */
    private fun prepareContainer(holder: PageHolder, pageIndex: Int) {
        if (mode == Mode.LONG_STRIP) {
            applyHeight(holder.container, longStripHeight(pageIndex))
            holder.container.setPadding(0, 0, 0, 0)
        } else if (holder.container is FrameLayout) {
            holder.container.setPadding(spacingPx, spacingPx, spacingPx, spacingPx)
        }
    }

    /**
     * Shows [bitmap], made for [key]: in place of the same page at another size, where
     * and as large as that one shows; otherwise fitted.
     */
    private fun showBitmap(holder: PageHolder, slot: Int, key: PageKey, bitmap: Bitmap) {
        val image = holder.images[slot]
        when {
            holder.shown[slot] != key.page -> applyBitmap(holder, slot, key, bitmap)
            (image.drawable as? BitmapDrawable)?.bitmap === bitmap -> image.setFitMode(fitMode)
            else -> {
                // A cached bitmap can arrive at once, before the stand-in took the new fit.
                if (image.fitMode != fitMode) image.setFitMode(fitMode)
                image.swapBitmap(bitmap, SlotDetail(holder, key))
            }
        }
    }

    /** Shows [bitmap], decoded for [key]; settled zooms re-render it from the page's full detail. */
    private fun applyBitmap(holder: PageHolder, slot: Int, key: PageKey, bitmap: Bitmap) {
        val image = holder.images[slot]
        image.setImageBitmap(bitmap)
        image.setFitMode(fitMode)
        image.detailSource = SlotDetail(holder, key)
        holder.shown[slot] = key.page
    }

    private fun clearImage(holder: PageHolder, slot: Int) {
        val image = holder.images[slot]
        image.detailSource = null
        image.setImageDrawable(null)
        holder.shown[slot] = NO_PAGE
    }

    private fun applyFailure(holder: PageHolder, slot: Int, failure: Failure) {
        val image = holder.images[slot]
        image.detailSource = null
        val res = image.resources
        val name = failure.name ?: res.getString(R.string.page_number, failure.index + 1)
        image.setImageDrawable(ErrorPageDrawable(res.getString(R.string.error_load), name, failure.reason))
        image.setFitMode(fitMode)
        holder.shown[slot] = NO_PAGE
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
        val deferred = scope.async(AppDispatchers.io) {
            gate.withPermit {
                val job = coroutineContext.job
                val bitmap = decode(seg.source, pageIndex - chapter.first, pageIndex, key.target) { job.isCancelled }
                    ?.let { frozen(it) }
                if (bitmap != null && bitmap.width > 0) {
                    cache.put(key, bitmap)
                    aspects[pageIndex] = bitmap.height.toFloat() / bitmap.width.toFloat()
                }
                bitmap
            }
        }
        deferred.invokeOnCompletion { releaseLoad(seg) }
        val entry = Pending(key, deferred)
        pending[key] = entry
        deferred.invokeOnCompletion {
            // Runs on the decoder thread; hop back before touching [pending].
            scope.launch { if (pending[key] === entry) pending.remove(key) }
        }
        return entry
    }

    /** Decodes the [preload] pages after [lastShown] into the cache. */
    private fun prefetchAfter(lastShown: Int) {
        val ahead = if (mode == Mode.LONG_STRIP) minOf(preload, MAX_STRIP_PRELOAD) else preload
        for (k in 1..ahead) {
            val p = lastShown + k
            if (p >= end) break
            val key = keyFor(p)
            if (cache.get(key) != null || pending[key] != null || permanentFailure(key) != null) continue
            request(key).prefetch = true
        }
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
        val width = itemSize()[0]
        return PageTargets.stripHeight(width, aspects[page] ?: learnedAspect)
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
        private const val DECODE_THREADS = 2
        private const val MIN_CACHE_BYTES = 24 * 1024 * 1024
        private const val MAX_STRIP_PRELOAD = 2

        /** Rebinds an item on its own holder, which keeps its bitmaps meanwhile. */
        private val RESIZE = Any()
        private val WHOLE_PAGE = Region(0.0, 0.0, 1.0, 1.0)

        /**
         * [bitmap] as an immutable bitmap, copied if it is mutable (which it then recycles):
         * the GPU keeps an immutable bitmap's texture between frames, where a mutable one
         * drawn through a [PageShader] is uploaded again for every frame.
         */
        internal fun frozen(bitmap: Bitmap): Bitmap {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !bitmap.isMutable) return bitmap
            val copy = bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false) ?: return bitmap
            bitmap.recycle()
            return copy
        }
    }
}
