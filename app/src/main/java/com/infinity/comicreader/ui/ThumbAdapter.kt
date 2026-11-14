package com.infinity.comicreader.ui

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.infinity.comicreader.R
import com.infinity.comicreader.reader.Fit
import com.infinity.comicreader.reader.PageSource
import com.infinity.comicreader.reader.Scaling
import com.infinity.comicreader.reader.Target
import com.infinity.comicreader.util.AppDispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Grid of page thumbnails used to jump to a page. Each is made at its view's exact
 * size, cropped and scaled through [scaling] to fill it, and drawn 1:1. Until it is ready the
 * page's preview stands in, stored or quickly decoded ([PageSource.loadPreview]) and
 * cropped the same way, so the exact one replaces it in place. Exact thumbnails are made
 * only once every page bound has its preview, or has none to get; one that fails shows
 * a placeholder only where nothing shows yet. [thumbHeight], when
 * above 0, replaces the layout's thumbnail height in pixels. [currentPage] is outlined,
 * pages [marked] outlined thicker and checked; [labelOf] numbers each page.
 */
class ThumbAdapter(
    private val source: PageSource,
    private val currentPage: Int,
    private val linear: Boolean = false,
    private val scaling: Scaling = Scaling.PLAIN,
    private val thumbHeight: Int = 0,
    private val marked: (Int) -> Boolean = { false },
    private val labelOf: (Int) -> String = { (it + 1).toString() },
    private val onPick: (Int) -> Unit
) : RecyclerView.Adapter<ThumbAdapter.Holder>() {

    private val scope = CoroutineScope(SupervisorJob() + AppDispatchers.main)
    private val cache = object : LruCache<Int, Bitmap>(100) {}
    private val previews = object : LruCache<Int, Bitmap>(PREVIEW_CACHE_BYTES) {
        override fun sizeOf(key: Int, value: Bitmap) = value.byteCount
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Archives are not safe for many parallel reads; a few at a time is plenty for thumbnails. */
    private val gate = Semaphore(2)

    /** Previews are small: more of them at a time, beside the exact thumbnails. */
    private val previewGate = Semaphore(4)

    /** Previews asked for and not yet shown or found missing; exact thumbnails start only while none are. */
    private val previewsDue = MutableStateFlow(0)

    /** Called on the main thread as page [Int] shows its preview (false) or exact thumbnail (true). */
    var onShown: ((Int, Boolean) -> Unit)? = null

    class Holder(
        val root: View,
        val image: com.google.android.material.imageview.ShapeableImageView,
        val label: TextView,
        val check: ImageView
    ) : RecyclerView.ViewHolder(root) {
        var job: Job? = null

        /** The page bound here, and the thumbnail size last requested for it (width in the high half). */
        var page = RecyclerView.NO_POSITION
        var requested = 0L
    }

    override fun getItemCount(): Int = source.pageCount

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_thumb, parent, false)
        val holder = Holder(
            view,
            view.findViewById(R.id.thumb_image),
            view.findViewById(R.id.thumb_label),
            view.findViewById(R.id.thumb_check)
        )
        if (thumbHeight > 0) holder.image.layoutParams.height = thumbHeight
        holder.image.addOnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
            if (r - l != oldR - oldL || b - t != oldB - oldT) requestThumb(holder)
        }
        return holder
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.label.text = labelOf(position)
        val context = holder.itemView.context
        val isMarked = marked(position)
        if (position == currentPage || isMarked) {
            holder.image.strokeWidth = context.resources.displayMetrics.density * (if (isMarked) 4f else 2f)
            holder.image.strokeColor = androidx.core.content.ContextCompat.getColorStateList(context, R.color.md_primary)
        } else {
            holder.image.strokeWidth = 0f
        }
        holder.check.visibility = if (isMarked) View.VISIBLE else View.GONE
        holder.root.setOnClickListener { onPick(holder.bindingAdapterPosition) }
        holder.job?.cancel()
        holder.page = position
        holder.requested = 0L
        holder.image.setImageDrawable(null)
        // At once when the view is already laid out at its size, else once it is.
        requestThumb(holder)
        mainHandler.post { requestThumb(holder) }
    }

    /** Shows the thumbnail of the page [holder] is bound to at the image view's size; a size already asked for is skipped. */
    private fun requestThumb(holder: Holder) {
        val position = holder.page
        val w = holder.image.width
        val h = holder.image.height
        if (position == RecyclerView.NO_POSITION || w <= 0 || h <= 0) return
        val size = (w.toLong() shl 32) or h.toLong()
        if (holder.requested == size) return
        holder.requested = size
        holder.job?.cancel()
        cache.get(position)?.takeIf { it.width == w && it.height == h }?.let {
            show(holder, it)
            return
        }
        holder.job = scope.launch {
            // The preview shows only while the exact thumbnail is not in.
            var exactIn = false
            val preview = if (holder.image.drawable == null) {
                previewsDue.update { it + 1 }
                launch { showPreview(holder, position) { !exactIn } }.also { job ->
                    job.invokeOnCompletion { previewsDue.update { it - 1 } }
                }
            } else {
                null
            }
            val bitmap = withContext(AppDispatchers.io) {
                gate.withPermit {
                    // Every page bound gets its preview before any exact thumbnail is made.
                    previewsDue.first { it == 0 }
                    try {
                        source.loadPage(position, Target(w, h, Fit.COVER, linear = linear, scaling = scaling))
                    } catch (t: Throwable) {
                        Log.w(TAG, "Failed to load thumbnail $position", t)
                        null
                    }
                }
            }
            if (bitmap != null) {
                exactIn = true
                preview?.cancel()
                cache.put(position, bitmap)
                if (holder.page == position) show(holder, bitmap)
                return@launch
            }
            preview?.join()
            // A failed thumbnail leaves what shows (its preview, or an earlier thumbnail) in place.
            if (holder.page == position && holder.image.drawable == null) showFailed(holder, position)
        }
    }

    private fun showFailed(holder: Holder, position: Int) {
        val res = holder.itemView.resources
        val name = source.pageName(position) ?: res.getString(R.string.page_number, position + 1)
        holder.image.scaleType = ImageView.ScaleType.FIT_CENTER
        holder.image.setImageDrawable(ErrorPageDrawable(res.getString(R.string.error_load), name, ""))
    }

    /**
     * Shows page [position]'s preview in [holder], cropped to fill it as the exact
     * thumbnail is, when one is quick to get and it is still [wanted].
     */
    private suspend fun showPreview(holder: Holder, position: Int, wanted: () -> Boolean) {
        val preview = previews.get(position) ?: withContext(AppDispatchers.io) {
            previewGate.withPermit {
                try {
                    source.loadPreview(position)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    null
                }
            }
        }?.also { previews.put(position, it) } ?: return
        if (holder.page != position || !wanted()) return
        holder.image.scaleType = ImageView.ScaleType.CENTER_CROP
        holder.image.setImageBitmap(preview)
        onShown?.invoke(position, false)
    }

    private fun show(holder: Holder, bitmap: Bitmap) {
        holder.image.scaleType = ImageView.ScaleType.CENTER
        holder.image.setImageBitmap(bitmap)
        onShown?.invoke(holder.page, true)
    }

    override fun onViewRecycled(holder: Holder) {
        super.onViewRecycled(holder)
        holder.job?.cancel()
        holder.job = null
        holder.page = RecyclerView.NO_POSITION
        holder.image.setImageDrawable(null)
    }

    fun release() {
        scope.cancel()
        cache.evictAll()
        previews.evictAll()
    }

    companion object {
        private const val TAG = "ThumbAdapter"
        private const val PREVIEW_CACHE_BYTES = 16 * 1024 * 1024
    }
}
