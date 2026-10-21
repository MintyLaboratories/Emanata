package com.infinity.comicreader.ui

import android.graphics.Bitmap
import android.graphics.Typeface
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.infinity.comicreader.R
import com.infinity.comicreader.data.CoverArt
import com.infinity.comicreader.reader.Cancellation
import com.infinity.comicreader.reader.Fit
import com.infinity.comicreader.reader.Half
import com.infinity.comicreader.reader.PageSource
import com.infinity.comicreader.reader.Target
import com.infinity.comicreader.util.AppDispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * The seek bar's strip of page previews ([SeekStrip.Cell] each), [thumbHeight] pixels
 * tall, the [current] cell highlighted. A part shows its page's stored or quick preview
 * ([PageSource.loadPreview]) at once, then its Lanczos3 rendering at the part's exact
 * size: with [exact] from the page, without it (a book whose pages are costly to reach)
 * from the preview, the page read only when it has none. Both are cached while the
 * adapter lives, so the strip opens again at once.
 */
class SeekPreviewAdapter(
    private val source: PageSource,
    private val thumbHeight: Int,
    private val linear: Boolean,
    private val exact: Boolean,
    private val onPick: (Int) -> Unit
) : RecyclerView.Adapter<SeekPreviewAdapter.Holder>() {

    private val scope = CoroutineScope(SupervisorJob() + AppDispatchers.main)

    /** Exact renderings by part and size. */
    private val renders = object : LruCache<PartKey, Bitmap>(RENDER_CACHE_BYTES) {
        override fun sizeOf(key: PartKey, value: Bitmap) = value.byteCount
    }

    /** Previews by page and half; a half is cut from its page's preview. */
    private val previews = object : LruCache<PartKey, Bitmap>(PREVIEW_CACHE_BYTES) {
        override fun sizeOf(key: PartKey, value: Bitmap) = value.byteCount
    }

    /** One rendering at a time: the reader's own pages come first. */
    private val renderGate = Semaphore(1)
    private val previewGate = Semaphore(2)

    /** Scaling previews reads no pages, so it runs beside the reader. */
    private val scaleGate = Semaphore(2)

    /** What a part shows: [page]'s [half] at [width] x [height] (0 x 0 for a preview). */
    private data class PartKey(val page: Int, val half: Half, val width: Int = 0, val height: Int = 0)

    /** One image of a cell, and what it is loading. */
    class Slot internal constructor(val image: ImageView) {
        internal var key: Any? = null
        var job: Job? = null
    }

    class Holder(val root: View, val frame: View, val slots: List<Slot>, val label: TextView) : RecyclerView.ViewHolder(root)

    var cells: List<SeekStrip.Cell> = emptyList()
        private set

    /** The highlighted cell. */
    var current = -1
        private set

    /** Shows [cells] with [current] highlighted. */
    fun show(cells: List<SeekStrip.Cell>, current: Int) {
        this.cells = cells
        this.current = current
        @Suppress("NotifyDataSetChanged")
        notifyDataSetChanged()
    }

    /** Highlights cell [item] instead of the one before. */
    fun highlight(item: Int) {
        if (item == current) return
        val old = current
        current = item
        if (old in cells.indices) notifyItemChanged(old, HIGHLIGHT)
        if (item in cells.indices) notifyItemChanged(item, HIGHLIGHT)
    }

    /** Pixel width of cell [item] as laid out, with its frame and padding. */
    fun cellWidth(item: Int, extra: Int): Int =
        cells.getOrNull(item)?.let { c -> c.aspects.sumOf { SeekStrip.widthOf(it, thumbHeight) } + extra } ?: 0

    override fun getItemCount(): Int = cells.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_seek_thumb, parent, false)
        val slots = listOf(R.id.seek_thumb_0, R.id.seek_thumb_1).map { id ->
            Slot(view.findViewById<ImageView>(id).also { it.layoutParams.height = thumbHeight })
        }
        return Holder(view, view.findViewById(R.id.seek_thumb_frame), slots, view.findViewById(R.id.seek_thumb_label))
    }

    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && payloads.all { it == HIGHLIGHT }) {
            mark(holder, position == current)
            return
        }
        onBindViewHolder(holder, position)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val cell = cells[position]
        holder.label.text = cell.label
        mark(holder, position == current)
        holder.root.setOnClickListener { holder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let(onPick) }
        for ((i, slot) in holder.slots.withIndex()) {
            val part = cell.parts.getOrNull(i)
            if (part == null) {
                clear(slot)
                slot.image.visibility = View.GONE
                continue
            }
            val width = SeekStrip.widthOf(cell.aspects[i], thumbHeight)
            slot.image.visibility = View.VISIBLE
            if (slot.image.layoutParams.width != width) {
                slot.image.layoutParams.width = width
                slot.image.requestLayout()
            }
            load(slot, PartKey(part.page, part.half, width, thumbHeight))
        }
    }

    /** Highlights [holder] as the current cell, or not. */
    private fun mark(holder: Holder, on: Boolean) {
        val context = holder.root.context
        holder.frame.background = if (on) ContextCompat.getDrawable(context, R.drawable.bg_seek_current) else null
        holder.label.setTextColor(ContextCompat.getColor(context, if (on) R.color.reader_accent else R.color.reader_on_surface_dim))
        holder.label.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
    }

    /** Shows [key] in [slot]: its rendering when cached, else its preview, then the rendering. */
    private fun load(slot: Slot, key: PartKey) {
        if (slot.key == key) return
        clear(slot)
        slot.key = key
        renders.get(key)?.let { return showRender(slot, it) }
        slot.job = scope.launch {
            val preview = previewOf(key)
            if (slot.key != key) return@launch
            if (preview != null) {
                slot.image.scaleType = ImageView.ScaleType.CENTER_CROP
                slot.image.setImageBitmap(preview)
            }
            val render = withContext(AppDispatchers.io) {
                try {
                    // Without [exact], the preview itself is scaled: no page is read.
                    if (preview != null && !exact) scaleGate.withPermit { scaled(preview, key) }
                    else renderGate.withPermit {
                        source.loadPage(key.page, Target(key.width, key.height, Fit.COVER, linear = linear, half = key.half))
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    null
                }
            } ?: return@launch
            renders.put(key, render)
            if (slot.key == key) showRender(slot, render)
        }
    }

    /** [preview] cropped and Lanczos3-scaled to fill [key]'s size; stops early once this coroutine is canceled. */
    private suspend fun scaled(preview: Bitmap, key: PartKey): Bitmap {
        val job = currentCoroutineContext().job
        val plan = Target(key.width, key.height, Fit.COVER).plan(preview.width.toDouble(), preview.height.toDouble())
        return Cancellation.during({ !job.isActive }) { CoverArt.render(preview, plan, linear) }
    }

    private fun showRender(slot: Slot, bitmap: Bitmap) {
        slot.image.scaleType = ImageView.ScaleType.CENTER
        slot.image.setImageBitmap(bitmap)
    }

    /** The preview of [key]'s part: cached, or the page's, cut to its half. */
    private suspend fun previewOf(key: PartKey): Bitmap? {
        val partKey = PartKey(key.page, key.half)
        previews.get(partKey)?.let { return it }
        val whole = previews.get(PartKey(key.page, Half.WHOLE)) ?: withContext(AppDispatchers.io) {
            previewGate.withPermit {
                try {
                    source.loadPreview(key.page)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    null
                }
            }
        }?.also { previews.put(PartKey(key.page, Half.WHOLE), it) } ?: return null
        if (key.half == Half.WHOLE) return whole
        val w = (whole.width / 2).coerceAtLeast(1)
        val x = if (key.half == Half.LEFT) 0 else whole.width - w
        return runCatching { Bitmap.createBitmap(whole, x, 0, w, whole.height) }.getOrNull()?.also { previews.put(partKey, it) }
    }

    private fun clear(slot: Slot) {
        slot.job?.cancel()
        slot.job = null
        slot.key = null
        slot.image.setImageDrawable(null)
    }

    override fun onViewRecycled(holder: Holder) {
        super.onViewRecycled(holder)
        for (slot in holder.slots) clear(slot)
    }

    fun release() {
        scope.cancel()
        renders.evictAll()
        previews.evictAll()
    }

    companion object {
        private const val HIGHLIGHT = "highlight"
        private const val RENDER_CACHE_BYTES = 48 * 1024 * 1024
        private const val PREVIEW_CACHE_BYTES = 16 * 1024 * 1024
    }
}
