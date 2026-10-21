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
import com.infinity.comicreader.reader.Target
import com.infinity.comicreader.util.AppDispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Grid of page thumbnails used to jump to a page. Each is made at its view's exact
 * size, cropped and Lanczos3-scaled to fill it, and drawn 1:1. [thumbHeight], when
 * above 0, replaces the layout's thumbnail height in pixels.
 */
class ThumbAdapter(
    private val source: PageSource,
    private val currentPage: Int,
    private val linear: Boolean = false,
    private val thumbHeight: Int = 0,
    private val onPick: (Int) -> Unit
) : RecyclerView.Adapter<ThumbAdapter.Holder>() {

    private val scope = CoroutineScope(SupervisorJob() + AppDispatchers.main)
    private val cache = object : LruCache<Int, Bitmap>(100) {}
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Archives are not safe for many parallel reads; a few at a time is plenty for thumbnails. */
    private val gate = Semaphore(2)

    class Holder(val root: View, val image: com.google.android.material.imageview.ShapeableImageView, val label: TextView) :
        RecyclerView.ViewHolder(root) {
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
            view.findViewById(R.id.thumb_label)
        )
        if (thumbHeight > 0) holder.image.layoutParams.height = thumbHeight
        holder.image.addOnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
            if (r - l != oldR - oldL || b - t != oldB - oldT) requestThumb(holder)
        }
        return holder
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.label.text = (position + 1).toString()
        val context = holder.itemView.context
        if (position == currentPage) {
            holder.image.strokeWidth = context.resources.displayMetrics.density * 2f
            holder.image.strokeColor = androidx.core.content.ContextCompat.getColorStateList(context, R.color.md_primary)
        } else {
            holder.image.strokeWidth = 0f
        }
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
            val bitmap = withContext(AppDispatchers.io) {
                gate.withPermit {
                    try {
                        source.loadPage(position, Target(w, h, Fit.COVER, linear = linear))
                    } catch (t: Throwable) {
                        Log.w(TAG, "Failed to load thumbnail $position", t)
                        null
                    }
                }
            }
            if (bitmap != null) {
                cache.put(position, bitmap)
                if (holder.page == position) show(holder, bitmap)
            } else if (holder.page == position) {
                val res = holder.itemView.resources
                val name = source.pageName(position) ?: res.getString(R.string.page_number, position + 1)
                holder.image.scaleType = ImageView.ScaleType.FIT_CENTER
                holder.image.setImageDrawable(ErrorPageDrawable(res.getString(R.string.error_load), name, ""))
            }
        }
    }

    private fun show(holder: Holder, bitmap: Bitmap) {
        holder.image.scaleType = ImageView.ScaleType.CENTER
        holder.image.setImageBitmap(bitmap)
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
    }

    companion object {
        private const val TAG = "ThumbAdapter"
    }
}
