package com.infinity.comicreader.ui

import android.animation.ValueAnimator
import android.content.res.ColorStateList
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
import com.infinity.comicreader.data.Thumbs
import com.infinity.comicreader.util.AppDispatchers
import com.infinity.comicreader.util.FileUtils
import com.google.android.material.card.MaterialCardView
import com.google.android.material.imageview.ShapeableImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
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

    /** Caps concurrent cover decodes; a row scrolled away before its turn is never decoded. */
    private val gate = Semaphore(THUMB_THREADS)

    /** Left out of the titles that start with it: the series name inside a Set. Set before [submit]. */
    var titlePrefix: String? = null

    /** Path to draw a focus ring around, used after "open file location". */
    var highlightPath: String? = null

    /** Optional first row of the list — currently only the "Recent" shortcut. */
    data class Header(
        val title: String,
        val subtitle: String,
        val iconRes: Int,
        val onClick: () -> Unit
    )

    /** A header occupies position 0, so every entry shifts down by this much. */
    var header: Header? = null
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    val itemOffset: Int get() = if (header != null) 1 else 0

    /** Shows [list]: [BookEntry] rows, optionally between [SectionRow] headers. */
    fun submit(list: List<Any>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
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

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is HeaderHolder) {
            val h = header ?: return
            holder.title.text = h.title
            holder.subtitle.text = h.subtitle
            holder.subtitle.visibility = if (h.subtitle.isEmpty()) View.GONE else View.VISIBLE
            holder.icon.setImageResource(h.iconRes)
            holder.itemView.setOnClickListener { h.onClick() }
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

    private fun bindBook(holder: BookHolder, item: BookEntry) {
        val context = holder.itemView.context
        holder.job?.cancel()

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
        holder.subtitle.visibility = if (subtitle.isNotEmpty()) View.VISIBLE else View.GONE
        if (grid) sizeCell(holder, comfortable) else sizeThumb(holder)

        holder.star.visibility = if (item.favorite) View.VISIBLE else View.GONE
        holder.star.setOnClickListener { onStarClick(item) }

        bindBadge(holder.badge, item)
        applyHighlight(holder, item)

        val icon = iconFor(item)
        holder.placeholder.setImageResource(icon)
        holder.placeholder.imageTintList = ColorStateList.valueOf(tintFor(context, item))
        holder.thumb.setImageDrawable(null)
        holder.placeholder.visibility = View.VISIBLE

        // The cover is requested at its view's exact size, once that is laid out.
        holder.entry = item
        holder.requested = 0L
        // At once when nothing about the cell changed, else after its next layout.
        if (!holder.itemView.isLayoutRequested) requestCover(holder)
        mainHandler.post { requestCover(holder) }

        holder.itemView.setOnClickListener { onClick(item) }
        holder.itemView.setOnLongClickListener { onLongClick(item); true }
    }

    /**
     * Loads the cover of the entry [holder] shows at the cover view's size, so it is
     * drawn 1:1. Runs after each bind and again whenever the view changes size; a
     * size already requested, or a view not laid out yet, asks for nothing.
     */
    private fun requestCover(holder: BookHolder) {
        val item = holder.entry ?: return
        val w = holder.thumb.width
        val h = holder.thumb.height
        if (!prefs.thumbnails || w <= 0 || h <= 0) return
        val size = (w.toLong() shl 32) or h.toLong()
        if (holder.requested == size) return
        holder.requested = size
        holder.job?.cancel()
        val appContext = holder.itemView.context.applicationContext
        val charset = prefs.charset
        val style = prefs.coverStyle
        holder.job = scope.launch {
            val bitmap = withContext(AppDispatchers.io) {
                gate.withPermit {
                    runCatching { Thumbs.load(appContext, item, charset, w, h, style) }.getOrNull()
                }
            }
            if (bitmap != null && holder.entry === item) {
                holder.thumb.setImageBitmap(bitmap)
                holder.placeholder.visibility = View.GONE
            }
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        if (holder !is BookHolder) return
        holder.job?.cancel()
        holder.job = null
        holder.entry = null
        holder.thumb.setImageDrawable(null)
    }

    fun release() {
        scope.cancel()
    }

    // ------------------------------------------------------------------ helpers

    /**
     * With a row count set, the card takes the cell height and the cover gets what
     * the text below leaves; the text keeps its size. Otherwise the cover is as tall
     * as its shape asks for the cell width (a fixed height for the cell's shape) and
     * the card wraps it. Either way the box inside is the cover shape's largest fit.
     */
    private fun sizeCell(holder: BookHolder, showDetails: Boolean) {
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
        // The same text height in every cell keeps the covers of a row equal (titles reserve their lines).
        if (rows && showDetails && holder.subtitle.visibility == View.GONE) {
            holder.subtitle.visibility = View.INVISIBLE
        }
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

    private fun iconFor(item: BookEntry): Int = when {
        item.isDir -> R.drawable.ic_folder
        item.type == EntryType.PDF -> R.drawable.ic_picture_as_pdf
        item.type == EntryType.ARCHIVE -> R.drawable.ic_auto_stories
        item.type == EntryType.IMAGE -> R.drawable.ic_image
        else -> R.drawable.ic_file
    }

    private fun tintFor(context: android.content.Context, item: BookEntry): Int = ContextCompat.getColor(
        context,
        when {
            item.isDir -> R.color.md_primary
            item.type == EntryType.PDF -> R.color.md_error
            item.type == EntryType.ARCHIVE -> R.color.md_secondary
            item.type == EntryType.IMAGE -> R.color.md_tertiary
            else -> R.color.md_on_surface_variant
        }
    )

    private companion object {
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
