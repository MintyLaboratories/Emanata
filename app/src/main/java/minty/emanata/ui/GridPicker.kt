package minty.emanata.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import minty.emanata.R
import minty.emanata.data.TabView

/** Grid picker geometry and wording, free of Android types. */
object GridPick {
    /** Cells per side: Auto, then 1..[TabView.MAX_CELLS]. */
    const val SIDE = TabView.MAX_CELLS + 1

    /**
     * The cell index (0 = Auto) under [pos] along an axis of [SIDE] cells [cell] long
     * with [gap] between, a gap counting with the cell before it; -1 outside the grid.
     */
    fun cellAt(pos: Float, cell: Float, gap: Float): Int =
        if (pos < 0f || pos >= SIDE * cell + (SIDE - 1) * gap) -1
        else (pos / (cell + gap)).toInt().coerceAtMost(SIDE - 1)

    /** "4 × 3", "Auto × 3": columns first, [auto] for 0. */
    fun label(columns: Int, rows: Int, auto: String): String {
        fun part(n: Int) = if (n <= 0) auto else n.toString()
        return "${part(columns)} × ${part(rows)}"
    }
}

/**
 * A 7 x 7 table picker for grid columns x rows: the first column and row stand for
 * Auto, the rest for 1..6. The cells from the top-left to the chosen one are
 * highlighted, with the choice written below. Tap a cell, or drag and release;
 * [onPick] gets the columns and rows on release. Touches starting outside the grid
 * are ignored; releasing outside it picks nothing.
 */
class GridPicker @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var onPick: ((columns: Int, rows: Int) -> Unit)? = null

    var columns = 0
        private set
    var rows = 0
        private set

    /** The cell under the finger while dragging, or -1. */
    private var hoverColumn = -1
    private var hoverRow = -1

    private val density = resources.displayMetrics.density
    private val cell = CELL_DP * density
    private val gap = GAP_DP * density
    private val labelHeight = LABEL_DP * density
    private val corner = 4 * density
    private val autoText = context.getString(R.string.grid_size_auto)

    private val primary = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary)
    private val outline = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutline)
    private val onSurface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
    private val onSurfaceVariant = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
    }
    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 10 * resources.displayMetrics.scaledDensity
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 16 * resources.displayMetrics.scaledDensity
        color = onSurface
    }
    private val rect = RectF()

    init {
        isClickable = true
        updateDescription()
    }

    /** Shows [columns] x [rows] as the current choice. */
    fun select(columns: Int, rows: Int) {
        this.columns = columns.coerceIn(0, GridPick.SIDE - 1)
        this.rows = rows.coerceIn(0, GridPick.SIDE - 1)
        updateDescription()
        invalidate()
    }

    private fun updateDescription() {
        contentDescription = GridPick.label(columns, rows, autoText)
    }

    private val gridSize get() = GridPick.SIDE * cell + (GridPick.SIDE - 1) * gap

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = (gridSize + paddingLeft + paddingRight).toInt()
        val h = (gridSize + labelHeight + paddingTop + paddingBottom).toInt()
        setMeasuredDimension(resolveSize(w, widthMeasureSpec), resolveSize(h, heightMeasureSpec))
    }

    /** Left edge of the grid: centered in the width. */
    private val left get() = paddingLeft + (width - paddingLeft - paddingRight - gridSize) / 2f

    override fun onDraw(canvas: Canvas) {
        val shownColumns = if (hoverColumn >= 0) hoverColumn else columns
        val shownRows = if (hoverRow >= 0) hoverRow else rows
        val x0 = left
        val y0 = paddingTop.toFloat()
        val markY = (markPaint.descent() + markPaint.ascent()) / 2
        for (r in 0 until GridPick.SIDE) for (c in 0 until GridPick.SIDE) {
            val x = x0 + c * (cell + gap)
            val y = y0 + r * (cell + gap)
            rect.set(x, y, x + cell, y + cell)
            val on = c <= shownColumns && r <= shownRows
            fill.color = if (on) ColorUtils.setAlphaComponent(primary, 0x55) else 0
            if (on) canvas.drawRoundRect(rect, corner, corner, fill)
            stroke.color = if (on) primary else outline
            canvas.drawRoundRect(rect, corner, corner, stroke)
            // The first column and row stand for Auto.
            if (c == 0 || r == 0) {
                markPaint.color = if (on) primary else onSurfaceVariant
                canvas.drawText(AUTO_MARK, rect.centerX(), rect.centerY() - markY, markPaint)
            }
        }
        val label = GridPick.label(shownColumns, shownRows, autoText)
        val labelY = y0 + gridSize + labelHeight / 2 - (labelPaint.descent() + labelPaint.ascent()) / 2
        canvas.drawText(label, width / 2f, labelY, labelPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val c = GridPick.cellAt(event.x - left, cell, gap)
        val r = GridPick.cellAt(event.y - paddingTop, cell, gap)
        val inside = c >= 0 && r >= 0
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!inside) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                hover(c, r)
            }
            MotionEvent.ACTION_MOVE -> if (inside) hover(c, r) else hover(-1, -1)
            MotionEvent.ACTION_UP -> {
                hover(-1, -1)
                if (inside) {
                    select(c, r)
                    performClick()
                    onPick?.invoke(columns, rows)
                }
            }
            MotionEvent.ACTION_CANCEL -> hover(-1, -1)
        }
        return true
    }

    /** Highlights up to cell [c], [r] while dragging; -1 shows the current choice. */
    private fun hover(c: Int, r: Int) {
        if (c == hoverColumn && r == hoverRow) return
        hoverColumn = c
        hoverRow = r
        invalidate()
    }

    override fun performClick(): Boolean = super.performClick()

    private companion object {
        const val CELL_DP = 48f
        const val GAP_DP = 4f
        const val LABEL_DP = 40f
        const val AUTO_MARK = "A"
    }
}
