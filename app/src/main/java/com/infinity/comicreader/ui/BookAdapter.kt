package com.infinity.comicreader.ui

import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.infinity.comicreader.R
import com.infinity.comicreader.data.BookEntry
import com.infinity.comicreader.data.CoverShape
import com.infinity.comicreader.data.DetailDisplay
import com.infinity.comicreader.data.ListDensity
import com.infinity.comicreader.data.TitleDisplay
import com.infinity.comicreader.data.EntryType
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.ReadingDirection
import com.infinity.comicreader.data.Thumbs
import com.infinity.comicreader.net.Remotes
import com.infinity.comicreader.reader.NameCharset
import com.infinity.comicreader.util.AppDispatchers
import com.infinity.comicreader.util.FileUtils
import com.infinity.comicreader.util.Timing
import com.google.android.material.card.MaterialCardView
import com.google.android.material.imageview.ShapeableImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

/** How rows look beyond grid or list: list thumbnail size, cover shape, titles and details. */
data class RowOptions(
    val density: ListDensity = ListDensity.NORMAL,
    val shape: CoverShape = CoverShape.BOOK,
    val title: TitleDisplay = TitleDisplay.DOUBLE,
    val detail: DetailDisplay = DetailDisplay.COMFORTABLE,
    /** Space between grid cells, in dp. */
    val spacing: Int = Prefs.COVER_SPACING_DEFAULT
)

/**
 * Library rows. Each entry shows a cover thumbnail (page 1 for books, first image
 * for folders), its name, a size/pages subtitle, a progress badge and — only for
 * favorites — a star. Tapping a visible star removes the favorite; adding one
 * is done from the long-press menu.
 */
class BookAdapter(
    grid: Boolean,
    private val prefs: Prefs,
    private val onClick: (BookEntry) -> Unit,
    private val onLongClick: (BookEntry) -> Unit,
    private val onStarClick: (BookEntry) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    /** Grid cells or list rows; switching rebinds every row with the other layout. */
    var grid: Boolean = grid
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    /** Called with the cover size (width, height) each grid cell asks a cover at. */
    var onGridCover: ((Int, Int) -> Unit)? = null

    /** Rebinds every row when it changes. */
    var options = RowOptions()
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    /** Grid cell width; 0 until the list is measured. */
    var cellWidth: Int = 0
        private set

    /** Grid cell height from the "Grid rows" setting; 0 keeps the fixed cover height. */
    var cellHeight: Int = 0
        private set

    /** Sets the grid cell size; a change rebinds the grid, whose covers follow the new view size. */
    fun setCellSize(width: Int, height: Int) {
        if (width == cellWidth && height == cellHeight) return
        cellWidth = width
        cellHeight = height
        if (grid) notifyDataSetChanged()
    }

    /** [BookEntry] and [SectionRow] rows, after the header. */
    private val items = ArrayList<Any>()

    /** Tapped a section header. */
    var onSectionClick: ((SectionRow) -> Unit)? = null
    private val scope = CoroutineScope(SupervisorJob() + AppDispatchers.main)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Moves every scrolling title in sync; made once the list is known. */
    private var clock: TitleClock? = null

    /** Titles wait while the screen is hidden. */
    var stopped = false
        set(value) {
            field = value
            clock?.stopped = value
        }

    private val scrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
            clock?.scrolling = newState != RecyclerView.SCROLL_STATE_IDLE
        }
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        val speed = TitleMotion.SPEED_DP_PER_S * recyclerView.resources.displayMetrics.density / 1000f
        if (clock == null) clock = TitleClock(speed).also { it.stopped = stopped }
        recyclerView.addOnScrollListener(scrollListener)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        recyclerView.removeOnScrollListener(scrollListener)
        super.onDetachedFromRecyclerView(recyclerView)
    }

    /** Caps concurrent covers being made; a row scrolled away before its turn is never made. Cached covers skip it. */
    private val gate = Semaphore(THUMB_THREADS)

    /** Where covers are made ([gated]), apart from the loads asking for them. */
    private val makers = CoroutineScope(SupervisorJob() + AppDispatchers.io)

    /**
     * What [make] gives, made with one of the [gate]'s places. Canceled, the load gives its
     * place back at once, as one stuck on a slow share would hold it, while [make], told so
     * by its argument, stops at its next step.
     */
    private suspend fun <T> gated(make: (canceled: () -> Boolean) -> T): T {
        val asker = coroutineContext.job
        gate.acquire()
        try {
            return makers.async {
                // A load canceled while it waited for its place makes nothing.
                asker.ensureActive()
                make { !asker.isActive }
            }.await()
        } finally {
            // A canceled load's place is given back once the others are canceled too ([cancelCovers]), so none takes it.
            if (asker.isActive) gate.release() else mainHandler.post { gate.release() }
        }
    }

    /** Where covers come from; tests stand in their own. */
    var covers: CoverSource = CoverSource.THUMBS

    /** Cover loads running or waiting, with the row each is for. */
    private val loads = HashMap<Job, BookHolder>()

    /**
     * Set by [cancelCovers] until the next [submit]: rows still shown ask for no cover (the
     * new tab's view may redraw them meanwhile), and the next list redraws every row, so
     * each asks anew.
     */
    private var redrawAll = false

    /**
     * Drops every cover load running or waiting: the screen moves to another tab or folder,
     * whose covers go first. One being made stops at its next step. Until the next list
     * ([submit]) no cover is asked for; then rows ask anew, and covers already drawn stay.
     */
    fun cancelCovers() {
        // A load not started yet ends, and leaves [loads], within cancel().
        for ((job, holder) in loads.toList()) {
            job.cancel()
            if (holder.job === job) holder.requested = 0L
        }
        loads.clear()
        redrawAll = true
    }

    /** Left out of the titles that start with it: the series name inside a Set. A change redraws every row. */
    var titlePrefix: String? = null
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    /** Path to draw a focus ring around, used after "open file location". A change redraws every row. */
    var highlightPath: String? = null
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    /**
     * Optional first row of the list — currently only the "Recent" shortcut. Two are equal
     * when they look the same: what a tap does is not compared.
     */
    class Header(
        val title: String,
        val subtitle: String,
        val iconRes: Int,
        val onClick: () -> Unit
    ) {
        override fun equals(other: Any?): Boolean =
            other is Header && other.title == title && other.subtitle == subtitle && other.iconRes == iconRes

        override fun hashCode(): Int = (title.hashCode() * 31 + subtitle.hashCode()) * 31 + iconRes
    }

    /**
     * A header occupies position 0, so every entry shifts down by this much. Only a header
     * that looks different redraws its row; a tap always runs the latest one's action.
     */
    var header: Header? = null
        set(value) {
            val old = field
            field = value
            when {
                old == value -> Unit
                old == null -> notifyItemInserted(0)
                value == null -> notifyItemRemoved(0)
                else -> notifyItemChanged(0)
            }
        }

    val itemOffset: Int get() = if (header != null) 1 else 0

    /**
     * Shows [list]: [BookEntry] rows, optionally between [SectionRow] headers. When the rows
     * are the same as shown, in the same order, only those that changed are redrawn, and
     * of those whose cover stays ([coverId]) only the text: facts found later (page counts,
     * read marks) never reload or blank a cover. A book whose cover changes is redrawn in the
     * same view, its old cover shown until the new one is drawn.
     */
    fun submit(list: List<Any>) {
        val old = ArrayList(items)
        items.clear()
        items.addAll(list)
        if (redrawAll || !RowChanges.sameRows(old, list)) {
            redrawAll = false
            notifyDataSetChanged()
            return
        }
        for (i in list.indices) {
            val before = old[i]
            val now = list[i]
            if (before == now) continue
            val payload = when {
                before !is BookEntry || now !is BookEntry -> null
                coverId(before) == coverId(now) -> DETAILS
                else -> COVER
            }
            notifyItemChanged(i + itemOffset, payload)
        }
    }

    /** Pages hidden in each book, by path, as covers skip them ([Thumbs.hiddenTag]). */
    var hiddenPages: () -> Map<String, Set<Int>> = { emptyMap() }

    /**
     * Everything the cover of [item] is made from: two entries with the same id show the same
     * cover. The direction counts only for a cover not known to look the same either way.
     */
    private fun coverId(item: BookEntry): String =
        "${item.path}|${item.size}|${item.lastModified}|${item.isDir}|${item.type}|" +
            "${item.direction == ReadingDirection.RTL && covers.directionMatters(item)}|" +
            "${item.bookmarkPage}${Thumbs.hiddenTag(item, hiddenPages())}|${prefs.coverStyle.key}|${NameCharset.forBook(item.path, prefs.charset)}|${prefs.thumbnails}|${Remotes.thumbnails(item.path)}"

    init {
        setHasStableIds(true)
    }

    /** The header, each section and each entry ([RowChanges.keyOf]) keep their id, so rows keep their views through a refresh. */
    override fun getItemId(position: Int): Long {
        if (position == 0 && header != null) return HEADER_ID
        return when (val row = items[position - itemOffset]) {
            is SectionRow -> SECTION_ID - row.kind.ordinal
            is BookEntry -> RowChanges.idOf(row)
            else -> RecyclerView.NO_ID
        }
    }

    /** True for rows that span the whole grid width: the header and section headers. */
    fun isFullWidth(position: Int): Boolean =
        (position == 0 && header != null) || items.getOrNull(position - itemOffset) is SectionRow

    /** The entry at adapter [position], or null for a header or section row. */
    fun entryAt(position: Int): BookEntry? = items.getOrNull(position - itemOffset) as? BookEntry

    /** Adapter position of the entry at [path], or -1. */
    fun positionOf(path: String): Int {
        val i = items.indexOfFirst { it is BookEntry && it.path == path }
        return if (i < 0) -1 else i + itemOffset
    }

    class BookHolder(view: View) : RecyclerView.ViewHolder(view) {
        val card: MaterialCardView? = view as? MaterialCardView
        val cover: CoverFrame? = view.findViewById(R.id.cover)
        val box: CoverBox = view.findViewById(R.id.box)
        val thumb: ShapeableImageView = view.findViewById(R.id.thumb)
        val placeholder: ImageView = view.findViewById(R.id.placeholder)
        val badge: TextView = view.findViewById(R.id.badge)
        val title: TitleView = view.findViewById(R.id.title)
        val subtitle: TextView = view.findViewById(R.id.subtitle)
        val star: ImageView = view.findViewById(R.id.star)
        var job: Job? = null

        /** The entry bound here, and the cover size last requested for it (width in the high half). */
        var entry: BookEntry? = null
        var requested = 0L

        /** The [coverId] of the entry bound here, of the cover drawn, if any, and of a quick cover drawn until it is. */
        var wanted: String? = null
        var drawn: String? = null
        var quick: String? = null
    }

    class SectionHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.title)
        val count: TextView = view.findViewById(R.id.count)
        val spinner: View = view.findViewById(R.id.spinner)
        val chevron: ImageView = view.findViewById(R.id.chevron)
    }

    class HeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.title)
        val subtitle: TextView = view.findViewById(R.id.subtitle)
        val icon: ImageView = view.findViewById(R.id.placeholder)
        val chevron: ImageView = view.findViewById(R.id.chevron)
    }

    override fun getItemViewType(position: Int): Int = when {
        position == 0 && header != null -> VIEW_HEADER
        items[position - itemOffset] is SectionRow -> VIEW_SECTION
        grid -> VIEW_GRID
        else -> VIEW_LIST
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val context = parent.context
        val layout = when (viewType) {
            VIEW_HEADER -> R.layout.item_book_header
            VIEW_SECTION -> R.layout.item_section
            VIEW_GRID -> R.layout.item_book_grid
            else -> R.layout.item_book_list
        }
        val view = LayoutInflater.from(context).inflate(layout, parent, false)
        if (viewType == VIEW_HEADER) return HeaderHolder(view)
        if (viewType == VIEW_SECTION) return SectionHolder(view)
        val holder = BookHolder(view)
        holder.thumb.addOnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
            if (r - l != oldR - oldL || b - t != oldB - oldT) requestCover(holder)
        }
        return holder
    }

    override fun getItemCount(): Int = items.size + itemOffset

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        val row = items.getOrNull(position - itemOffset)
        if (holder is BookHolder && row is BookEntry && payloads.isNotEmpty() && payloads.all { it == DETAILS || it == COVER }) {
            if (COVER in payloads) bindBook(holder, row) else bindDetails(holder, row)
            return
        }
        onBindViewHolder(holder, position)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is HeaderHolder) {
            val h = header ?: return
            holder.title.text = h.title
            holder.subtitle.text = h.subtitle
            holder.subtitle.visibility = if (h.subtitle.isEmpty()) View.GONE else View.VISIBLE
            holder.icon.setImageResource(h.iconRes)
            holder.itemView.setOnClickListener { header?.onClick?.invoke() }
            return
        }
        val row = items[position - itemOffset]
        if (holder is SectionHolder && row is SectionRow) {
            holder.title.text = row.title
            holder.count.text = row.count.toString()
            holder.spinner.visibility = if (row.loading) View.VISIBLE else View.GONE
            holder.chevron.rotation = if (row.collapsed) 0f else 90f
            holder.itemView.contentDescription = row.title
            holder.itemView.setOnClickListener { onSectionClick?.invoke(row) }
            return
        }
        bindBook(holder as BookHolder, row as BookEntry)
    }

    /**
     * Binds [item] to [holder]. A cover already drawn there for the same [coverId] stays as it
     * is (a refresh, a re-sort); one of the same book stays until its new cover replaces it (a
     * direction found, another cover style); else the type icon stands in until the cover is loaded.
     */
    private fun bindBook(holder: BookHolder, item: BookEntry) {
        val context = holder.itemView.context
        val before = holder.entry?.path
        val id = coverId(item)
        val keep = holder.wanted == id
        if (!keep) holder.job?.cancel()
        holder.wanted = id

        bindDetails(holder, item)
        if (grid) sizeCell(holder) else sizeThumb(holder)

        val icon = EntryIcons.iconFor(item)
        holder.placeholder.setImageResource(icon)
        holder.placeholder.imageTintList = ColorStateList.valueOf(EntryIcons.tintFor(context, item))
        if (holder.drawn != id && holder.quick != id) {
            holder.drawn = null
            holder.quick = null
            if (before != item.path || !prefs.thumbnails) clearCover(holder)
        }
        // The cover is requested at its view's exact size, once that is laid out.
        if (!keep) holder.requested = 0L
        // At once when nothing about the cell changed, else after its next layout.
        if (!holder.itemView.isLayoutRequested) requestCover(holder)
        mainHandler.post { requestCover(holder) }
    }

    /** Everything of [item] in [holder] but its cover: title, details, star, badge, highlight and taps. */
    private fun bindDetails(holder: BookHolder, item: BookEntry) {
        val context = holder.itemView.context
        holder.entry = item
        val mode = TitleMotion.effective(options.title, ValueAnimator.areAnimatorsEnabled())
        holder.title.mode = mode
        holder.title.reserve = grid
        holder.title.clock = clock
        holder.title.text = item.label?.let { LibraryOrder.labelText(context, it) } ?: shortName(item)
        // Empty while unknown: grid cells still hold its line so covers stay even.
        val dense = if (options.detail == DetailDisplay.DENSE) {
            DetailText.dense(item) { FileUtils.formatSize(context, it) }.ifEmpty { if (grid) "" else null }
        } else {
            null
        }
        holder.title.detail = dense
        holder.title.visibility = if (mode == TitleDisplay.HIDDEN && dense == null) View.GONE else View.VISIBLE
        val comfortable = options.detail == DetailDisplay.COMFORTABLE
        val subtitle = if (comfortable) item.subtitle ?: subtitleFor(context, item) else ""
        holder.subtitle.text = subtitle
        holder.subtitle.visibility = when {
            subtitle.isNotEmpty() -> View.VISIBLE
            // The same text height in every cell keeps the covers of a row equal (titles reserve their lines).
            grid && cellHeight > 0 && comfortable -> View.INVISIBLE
            else -> View.GONE
        }

        holder.star.visibility = if (item.favorite) View.VISIBLE else View.GONE
        holder.star.setOnClickListener { onStarClick(item) }

        bindBadge(holder.badge, item)
        applyHighlight(holder, item)

        holder.itemView.setOnClickListener { onClick(item) }
        holder.itemView.setOnLongClickListener { onLongClick(item); true }
    }

    /**
     * Loads the cover of the entry [holder] shows at the cover view's size, so it is
     * drawn 1:1. Runs after each bind and again whenever the view changes size; a
     * size already requested, or a view not laid out yet, asks for nothing. A cover
     * drawn meanwhile stays until the new one replaces it. One in memory shows at once,
     * one on disk without waiting for covers being made ([gate]).
     *
     * Covers are made in two passes: a layered cover page first gives a quick cover from its
     * first layer ([CoverSource.loadFirst]); the full one replaces it once every cover asked
     * for has had its first pass ([finishCover]). On a share set not to load thumbnails
     * ([Remotes.thumbnails]), only covers already made show.
     */
    private fun requestCover(holder: BookHolder) {
        // A row left over from the list before (a request posted before it changed) asks nothing.
        if (redrawAll || holder.bindingAdapterPosition == RecyclerView.NO_POSITION) return
        val item = holder.entry ?: return
        val id = holder.wanted ?: return
        val w = holder.thumb.width
        val h = holder.thumb.height
        if (!prefs.thumbnails || w <= 0 || h <= 0) return
        val size = (w.toLong() shl 32) or h.toLong()
        if (holder.requested == size) return
        holder.requested = size
        if (grid) onGridCover?.invoke(w, h)
        holder.job?.cancel()
        val appContext = holder.itemView.context.applicationContext
        val charset = prefs.charset
        val style = prefs.coverStyle
        val source = covers
        runCatching { source.inMemory(appContext, item, w, h, style) }.getOrNull()?.let {
            draw(holder, it, id)
            return
        }
        // A share set not to load thumbnails shows only covers made before.
        val makes = Remotes.thumbnails(item.path)
        firstPasses.value++
        val job = scope.launch {
            val got = withContext(AppDispatchers.io) {
                runCatching { source.cached(appContext, item, w, h, style) }.getOrNull()?.let { Thumbs.Cover(it, full = true) }
            } ?: if (!makes) null else gated { canceled -> Timing.span("cover first") { runCatching { source.loadFirst(appContext, item, charset, w, h, style, canceled) }.getOrNull() } }
            if (holder.wanted != id) return@launch
            if (got == null) {
                // The same book's earlier cover stood in for one there is none of now.
                if (holder.drawn == null && holder.quick == null) clearCover(holder)
                return@launch
            }
            if (got.full) {
                draw(holder, got.bitmap, id)
                return@launch
            }
            holder.thumb.setImageBitmap(got.bitmap)
            holder.quick = id
            holder.placeholder.visibility = View.GONE
            finishCover(holder, item, id, w, h)
        }
        track(holder, job)
        job.invokeOnCompletion { firstPasses.value-- }
    }

    /** First passes ([requestCover]) not done yet; full covers wait for none to be left. */
    private val firstPasses = MutableStateFlow(0)

    /** Replaces the quick cover of [item] in [holder] by its full [w] x [h] one, once no first pass is left. */
    private fun finishCover(holder: BookHolder, item: BookEntry, id: String, w: Int, h: Int) {
        val appContext = holder.itemView.context.applicationContext
        val charset = prefs.charset
        val style = prefs.coverStyle
        val source = covers
        val job = scope.launch {
            firstPasses.first { it == 0 }
            val bitmap = gated { canceled -> Timing.span("cover full") { runCatching { source.load(appContext, item, charset, w, h, style, canceled) }.getOrNull() } }
            if (bitmap != null && holder.wanted == id) draw(holder, bitmap, id)
        }
        track(holder, job)
    }

    /** Makes [job] the cover load of [holder], dropped by [cancelCovers]. */
    private fun track(holder: BookHolder, job: Job) {
        holder.job = job
        loads[job] = holder
        job.invokeOnCompletion { loads.remove(job) }
    }

    /** Shows the type icon in [holder] instead of a cover. */
    private fun clearCover(holder: BookHolder) {
        holder.thumb.setImageDrawable(null)
        holder.placeholder.visibility = View.VISIBLE
    }

    /** Shows [bitmap], the cover with [coverId] [id], in [holder]. */
    private fun draw(holder: BookHolder, bitmap: Bitmap, id: String) {
        Timing.mark("cover drawn at ${holder.bindingAdapterPosition}")
        holder.thumb.setImageBitmap(bitmap)
        holder.drawn = id
        holder.quick = null
        holder.placeholder.visibility = View.GONE
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        if (holder !is BookHolder) return
        holder.job?.cancel()
        holder.job = null
        holder.entry = null
        holder.wanted = null
        holder.drawn = null
        holder.quick = null
        holder.thumb.setImageDrawable(null)
    }

    fun release() {
        scope.cancel()
        makers.cancel()
    }

    // ------------------------------------------------------------------ helpers

    /**
     * With a row count set, the card takes the cell height and the cover gets what
     * the text below leaves; the text keeps its size. Otherwise the cover is as tall
     * as its shape asks for the cell width (a fixed height for the cell's shape) and
     * the card wraps it. Either way the box inside is the cover shape's largest fit.
     */
    private fun sizeCell(holder: BookHolder) {
        val cover = holder.cover ?: return
        val ratio = options.shape.ratio
        cover.ratio = ratio
        cover.freeHeight = cover.resources.getDimensionPixelSize(R.dimen.grid_thumb_h)
        holder.box.ratio = ratio
        val card = holder.itemView
        val cardParams = card.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val coverParams = cover.layoutParams as LinearLayout.LayoutParams
        val body = cover.parent as View
        // Neighbors' halves add up to the spacing; the list's edges get a half.
        val half = (options.spacing * card.resources.displayMetrics.density / 2).roundToInt()
        cardParams.setMargins(half, half, half, half)
        val rows = cellHeight > 0
        cardParams.height = if (rows) {
            cellHeight - cardParams.topMargin - cardParams.bottomMargin
        } else {
            ViewGroup.LayoutParams.WRAP_CONTENT
        }
        body.layoutParams.height =
            if (rows) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
        coverParams.height = if (rows) 0 else ViewGroup.LayoutParams.WRAP_CONTENT
        coverParams.weight = if (rows) 1f else 0f
        card.layoutParams = cardParams
    }

    /** A list row's thumbnail: the density's height, the cover shape's width. */
    private fun sizeThumb(holder: BookHolder) {
        val box = holder.box
        val (w, h) = CoverGeometry.listBoxDp(options.density, options.shape)
        val dp = box.resources.displayMetrics.density
        val params = box.layoutParams
        val width = (w * dp).toInt()
        val height = (h * dp).toInt()
        if (params.width == width && params.height == height) return
        params.width = width
        params.height = height
        box.layoutParams = params
        // The type icon shrinks to fit the tiniest thumbnails.
        val icon = holder.placeholder.layoutParams
        icon.width = minOf((PLACEHOLDER_DP * dp).toInt(), width - (PLACEHOLDER_MARGIN_DP * dp).toInt())
        icon.height = icon.width
        holder.placeholder.layoutParams = icon
    }

    private fun shortName(item: BookEntry): String {
        val prefix = titlePrefix ?: return item.name
        return if (item.type != EntryType.IMAGE && item.name.startsWith(prefix)) item.name.substring(prefix.length) else item.name
    }

    private fun applyHighlight(holder: BookHolder, item: BookEntry) {
        val card = holder.card ?: return
        val highlighted = item.path == highlightPath
        if (highlighted) {
            card.strokeWidth = (holder.itemView.resources.displayMetrics.density * 2).toInt()
            card.strokeColor = ContextCompat.getColor(holder.itemView.context, R.color.md_primary)
        } else {
            card.strokeWidth = 0
        }
    }

    /**
     * The read or progress pill on the cover; none on bookmarks, whose page is drawn
     * there. A list thumbnail too narrow for the text gets the percentage, or a bare dot.
     */
    private fun bindBadge(badge: TextView, item: BookEntry) {
        val context = badge.context
        val hasProgress = item.pages > 0 && item.progressPage > 0
        val folderDone = item.isDir && item.childCount > 0 && item.readCount >= item.childCount
        val forms = when {
            item.bookmarkPage >= 0 -> emptyList()
            item.read || folderDone -> {
                setPill(badge, R.color.md_secondary, R.color.md_on_secondary)
                listOf(context.getString(R.string.read_badge))
            }
            hasProgress -> {
                setPill(badge, R.color.md_primary, R.color.md_on_primary)
                listOf(
                    context.getString(R.string.read_progress, item.progressPage + 1, item.pages),
                    context.getString(R.string.read_percent, BadgeFit.percent(item.progressPage, item.pages))
                )
            }
            else -> emptyList()
        }
        if (forms.isEmpty()) {
            badge.visibility = View.GONE
            return
        }
        badge.visibility = View.VISIBLE
        badge.text = if (grid) forms[0] else {
            val dp = context.resources.displayMetrics.density
            val margins = (badge.layoutParams as? ViewGroup.MarginLayoutParams)?.let { it.leftMargin + it.rightMargin } ?: 0
            val available = CoverGeometry.listBoxDp(options.density, options.shape)[0] * dp - margins
            val padding = badge.paddingLeft + badge.paddingRight
            BadgeFit.choose(forms, available) { badge.paint.measureText(it) + padding }
        }
    }

    private fun setPill(view: TextView, colorRes: Int, textColorRes: Int) {
        val context = view.context
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 40f * context.resources.displayMetrics.density
            setColor(ContextCompat.getColor(context, colorRes))
        }
        view.setTextColor(ContextCompat.getColor(context, textColorRes))
    }

    private fun subtitleFor(context: android.content.Context, item: BookEntry): String {
        if (item.isDir) {
            return when {
                item.childCount < 0 -> ""
                item.readCount > 0 -> context.getString(R.string.folder_read_summary, item.childCount, item.readCount)
                else -> context.getString(R.string.books_count, item.childCount)
            }
        }
        val size = FileUtils.formatSize(context, item.size)
        return if (item.pages > 0) {
            "$size · ${context.getString(R.string.pages_count, item.pages)}"
        } else {
            size
        }
    }

    private companion object {
        /** Redraws a row's text only: its cover stays. */
        const val DETAILS = "details"

        /** Redraws a book's row in the view it is in, which keeps its cover until the new one is drawn. */
        const val COVER = "cover"
        const val HEADER_ID = Long.MIN_VALUE
        const val SECTION_ID = Long.MIN_VALUE + 16
        const val THUMB_THREADS = 3
        const val VIEW_LIST = 0
        const val VIEW_GRID = 1
        const val VIEW_HEADER = 2
        const val VIEW_SECTION = 3

        /** The type icon's size on a placeholder thumbnail, and the least room left beside it. */
        const val PLACEHOLDER_DP = 26
        const val PLACEHOLDER_MARGIN_DP = 4
    }
}
