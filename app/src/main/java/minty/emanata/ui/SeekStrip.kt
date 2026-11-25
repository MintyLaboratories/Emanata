package minty.emanata.ui

import minty.emanata.reader.Half
import kotlin.math.roundToInt

/**
 * The seek bar's page-preview strip: one cell per reader item, as [PageLayout] lays
 * items out, so a pair shows both pages and a split spread its two halves.
 */
object SeekStrip {

    /** Width over height of a page whose size is unknown. */
    const val DEFAULT_ASPECT = 2f / 3f

    /** Narrowest and widest a single part may be, over its height. */
    const val MIN_ASPECT = 0.4f
    const val MAX_ASPECT = 2f

    /**
     * One cell: the [parts] it shows left to right, each [aspects] wide over its height,
     * and its [label]. A part [guessed] true has [DEFAULT_ASPECT], its page's size unknown.
     */
    data class Cell(
        val parts: List<PageLayout.Part>,
        val label: String,
        val aspects: List<Float>,
        val guessed: List<Boolean> = parts.map { false }
    ) {
        /** The cell's width over its height. */
        val aspect: Float get() = aspects.sum()
    }

    /**
     * The cells of [layout]'s items for [pageCount] pages. With [numbered] (items count
     * on their own, as split halves do) a cell is labeled with its item number, else
     * with the pages it shows ("6", "6–7"). [sizeOf] gives a page's `[width, height]`,
     * null when unknown.
     */
    fun cells(layout: PageLayout, pageCount: Int, numbered: Boolean, sizeOf: (Int) -> IntArray?): List<Cell> =
        List(layout.itemCount(pageCount)) { item ->
            val parts = layout.parts(item, pageCount).filterNotNull()
            val label = if (numbered) "${item + 1}" else pagesLabel(parts)
            val sizes = parts.map { sizeOf(it.page)?.takeIf(::known) }
            Cell(parts, label, parts.zip(sizes) { part, size -> partAspect(part, size) }, sizes.map { it == null })
        }

    /**
     * [cell] with the aspect of each [guessed][Cell.guessed] part whose page [sizeOf] now
     * knows; [cell] itself when there is none.
     */
    fun refined(cell: Cell, sizeOf: (Int) -> IntArray?): Cell {
        var aspects: MutableList<Float>? = null
        var guessed: MutableList<Boolean>? = null
        for ((i, part) in cell.parts.withIndex()) {
            if (!cell.guessed.getOrElse(i) { false }) continue
            val size = sizeOf(part.page)?.takeIf(::known) ?: continue
            if (aspects == null) aspects = cell.aspects.toMutableList()
            if (guessed == null) guessed = cell.guessed.toMutableList()
            aspects[i] = partAspect(part, size)
            guessed[i] = false
        }
        return if (aspects == null || guessed == null) cell else cell.copy(aspects = aspects, guessed = guessed)
    }

    /** True for a `[width, height]` with both above 0. */
    private fun known(size: IntArray): Boolean = size.size >= 2 && size[0] > 0 && size[1] > 0

    /** "6" for one page, "6–7" for two: the 1-based pages [parts] show, lowest first. */
    fun pagesLabel(parts: List<PageLayout.Part>): String {
        val pages = parts.map { it.page + 1 }.distinct().sorted()
        return when {
            pages.isEmpty() -> ""
            pages.size == 1 -> pages[0].toString()
            else -> "${pages.first()}–${pages.last()}"
        }
    }

    /** Width over height of [part] of a page [size] (`[width, height]`, null when unknown): a half is half as wide. */
    fun partAspect(part: PageLayout.Part, size: IntArray?): Float {
        val whole = if (size != null && known(size)) size[0].toFloat() / size[1] else null
        val aspect = when {
            whole == null -> DEFAULT_ASPECT
            part.half == Half.WHOLE -> whole
            else -> whole / 2f
        }
        return aspect.coerceIn(MIN_ASPECT, MAX_ASPECT)
    }

    /** Width in pixels of a part or cell [aspect] wide at [height] pixels tall. */
    fun widthOf(aspect: Float, height: Int): Int = (aspect * height).roundToInt().coerceAtLeast(1)

    /** Offset from a strip's start edge that centers a cell [cellWidth] wide in a strip [stripWidth] wide. */
    fun centerOffset(stripWidth: Int, cellWidth: Int): Int = (stripWidth - cellWidth) / 2
}
