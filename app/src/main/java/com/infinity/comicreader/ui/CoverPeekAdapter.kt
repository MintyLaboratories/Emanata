package com.infinity.comicreader.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.infinity.comicreader.R
import com.infinity.comicreader.data.BookEntry
import com.infinity.comicreader.data.CoverStyle
import com.infinity.comicreader.data.Thumbs
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
 * The covers of a folder's [entries], for peeking into the folder. Each is exactly
 * [coverWidth] x [coverHeight] in [style], as the library draws it, so covers the
 * library made come straight from [Thumbs]' caches. Missing ones are made
 * [GENERATORS] at a time, those nearest the middle of the view first ([NearestFirst]).
 */
class CoverPeekAdapter(
    context: Context,
    private val entries: List<BookEntry>,
    private val coverWidth: Int,
    private val coverHeight: Int,
    private val style: CoverStyle,
    private val charset: String,
    private val onPick: (BookEntry) -> Unit
) : RecyclerView.Adapter<CoverPeekAdapter.Holder>() {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + AppDispatchers.main)

    /** Cache lookups are cheap: several at a time. */
    private val lookups = Semaphore(LOOKUPS)

    /** Positions whose cover is not made yet, and those that have none; main thread only. */
    private val missing = LinkedHashSet<Int>()
    private val coverless = HashSet<Int>()
    private var generators = 0
    private var recycler: RecyclerView? = null

    /** Called on the main thread as cell [Int] shows its cover or settles without one; the flag is always true. */
    var onShown: ((Int, Boolean) -> Unit)? = null

    class Holder(root: View, val cover: ImageView, val icon: ImageView, val title: TextView) : RecyclerView.ViewHolder(root) {
        var job: Job? = null

        /** The position bound here. */
        var bound = RecyclerView.NO_POSITION
    }

    override fun getItemCount(): Int = entries.size

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        recycler = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        recycler = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_peek_cover, parent, false)
        view.findViewById<View>(R.id.peek_box).layoutParams.apply {
            width = coverWidth
            height = coverHeight
        }
        return Holder(view, view.findViewById(R.id.peek_cover), view.findViewById(R.id.peek_icon), view.findViewById(R.id.peek_title))
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val entry = entries[position]
        holder.job?.cancel()
        holder.bound = position
        holder.title.text = entry.name
        holder.cover.setImageDrawable(null)
        holder.icon.setImageResource(EntryIcons.iconFor(entry))
        holder.icon.imageTintList = ColorStateList.valueOf(EntryIcons.tintFor(holder.itemView.context, entry))
        holder.icon.visibility = View.VISIBLE
        holder.itemView.setOnClickListener {
            entries.getOrNull(holder.bindingAdapterPosition)?.let(onPick)
        }
        if (position in coverless) {
            onShown?.invoke(position, true)
            return
        }
        holder.job = scope.launch {
            val cached = withContext(AppDispatchers.io) {
                lookups.withPermit { runCatching { Thumbs.cached(appContext, entry, coverWidth, coverHeight, style) }.getOrNull() }
            }
            if (cached != null) {
                if (holder.bound == position) show(holder, position, cached)
            } else {
                missing += position
                generate()
            }
        }
    }

    /** Starts makers for missing covers, up to [GENERATORS] running. */
    private fun generate() {
        while (generators < GENERATORS && missing.isNotEmpty()) {
            generators++
            scope.launch {
                try {
                    while (true) {
                        val position = NearestFirst.pick(missing, middle()) ?: break
                        missing -= position
                        val entry = entries[position]
                        val made = withContext(AppDispatchers.io) {
                            runCatching { Thumbs.load(appContext, entry, charset, coverWidth, coverHeight, style) }.getOrNull()
                        }
                        if (made == null) coverless += position
                        val holder = recycler?.findViewHolderForAdapterPosition(position) as? Holder
                        if (holder != null && holder.bound == position) show(holder, position, made)
                    }
                } finally {
                    generators--
                }
            }
        }
    }

    /** The middle cell in view. */
    private fun middle(): Int {
        val lm = recycler?.layoutManager as? GridLayoutManager ?: return 0
        return NearestFirst.middle(lm.findFirstVisibleItemPosition(), lm.findLastVisibleItemPosition())
    }

    private fun show(holder: Holder, position: Int, cover: Bitmap?) {
        if (cover != null) {
            holder.cover.setImageBitmap(cover)
            holder.icon.visibility = View.GONE
        }
        onShown?.invoke(position, true)
    }

    override fun onViewRecycled(holder: Holder) {
        super.onViewRecycled(holder)
        holder.job?.cancel()
        holder.job = null
        holder.bound = RecyclerView.NO_POSITION
        holder.cover.setImageDrawable(null)
    }

    fun release() {
        scope.cancel()
    }

    private companion object {
        const val LOOKUPS = 4

        /** Covers made at once: each reads a book and decodes its first page whole. */
        const val GENERATORS = 2
    }
}
