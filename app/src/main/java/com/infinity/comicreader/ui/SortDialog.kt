package com.infinity.comicreader.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.infinity.comicreader.R
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.SortKey
import com.infinity.comicreader.data.SortLevel
import com.infinity.comicreader.data.TabSort

/**
 * One tab's sort as a popup: "Sort by" and "Then by" side by side (stacked on
 * narrow screens), "Folders first" and the order in words. Every tap is written
 * at once and the list follows live; a tap outside or Back closes it.
 */
object SortDialog {

    @StringRes
    fun nameOf(key: SortKey): Int = when (key) {
        SortKey.NAME -> R.string.sort_name
        SortKey.YEAR -> R.string.sort_year
        SortKey.ADDED -> R.string.sort_added
        SortKey.ACCESSED -> R.string.sort_accessed
        SortKey.SIZE -> R.string.sort_size
        SortKey.LENGTH -> R.string.sort_length
        SortKey.TYPE -> R.string.sort_type
        SortKey.DIRECTION -> R.string.sort_direction
    }

    /** The direction of [level] in words: "A → Z", "newest first". */
    @StringRes
    fun directionOf(level: SortLevel): Int {
        val (first, second) = when (level.key) {
            SortKey.NAME -> R.string.sort_a_z to R.string.sort_z_a
            SortKey.YEAR, SortKey.ADDED -> R.string.sort_newest to R.string.sort_oldest
            SortKey.ACCESSED -> R.string.sort_recent to R.string.sort_oldest
            SortKey.SIZE -> R.string.sort_largest to R.string.sort_smallest
            SortKey.LENGTH -> R.string.sort_longest to R.string.sort_shortest
            SortKey.TYPE -> R.string.sort_archives_first to R.string.sort_images_first
            SortKey.DIRECTION -> R.string.sort_ltr_first to R.string.sort_rtl_first
        }
        return if (level.flipped) second else first
    }

    /** One level in words: "Name – A → Z". */
    internal fun levelText(context: Context, level: SortLevel) =
        context.getString(R.string.sort_level, context.getString(nameOf(level.key)), context.getString(directionOf(level)))

    /** The order in words: "Year – newest first, then Length – shortest first". */
    fun summary(context: Context, sort: TabSort): String {
        val first = levelText(context, sort.by)
        val then = sort.thenInEffect ?: return first
        return context.getString(R.string.sort_two_levels, first, levelText(context, then))
    }

    /** [tab]'s sort popup, not shown yet; [TabDialogFragment] shows it and keeps it across rotation. Each change is written and [onChange] hears it. */
    fun create(context: Context, prefs: Prefs, tab: String, onChange: () -> Unit = {}): AlertDialog {
        val content = Content(context, prefs, tab, onChange)
        return AlertDialog.Builder(context)
            .setTitle(R.string.menu_sort)
            .setView(ScrollView(context).apply { addView(content.root) })
            .create()
            .apply { setCanceledOnTouchOutside(true) }
    }

    private class Content(val context: Context, val prefs: Prefs, val tab: String, val onChange: () -> Unit) {
        private val density = context.resources.displayMetrics.density
        private val wide = context.resources.configuration.smallestScreenWidthDp >= WIDE_DP
        private val byColumn = column()
        private val thenColumn = column()
        private val summary = TextView(context).apply {
            setTextAppearance(R.style.TextAppearance_ComicReader_ItemSubtitle)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, dp(8), 0, 0)
        }
        private val foldersFirst = MaterialSwitch(context).apply {
            setText(R.string.sort_folders_first)
            minHeight = dp(48)
        }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }

        init {
            val columns = LinearLayout(context).apply {
                orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            }
            val params = { if (wide) LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) else wideParams() }
            columns.addView(byColumn, params())
            columns.addView(thenColumn, params().apply { if (wide) marginStart = dp(16) else topMargin = dp(12) })
            root.addView(columns, wideParams())
            root.addView(foldersFirst, wideParams().apply { topMargin = dp(8) })
            root.addView(summary, wideParams())
            foldersFirst.setOnCheckedChangeListener { _, checked ->
                val sort = prefs.sort(tab)
                if (sort.foldersFirst != checked) write(sort.copy(foldersFirst = checked))
            }
            render(prefs.sort(tab))
        }

        private fun write(sort: TabSort) {
            prefs.setSort(tab, sort)
            onChange()
            render(sort)
        }

        private fun render(sort: TabSort) {
            byColumn.removeAllViews()
            byColumn.addView(header(R.string.sort_by))
            for (key in SortKey.entries) {
                val selected = sort.by.key == key
                val level = if (selected) sort.by else SortLevel(key)
                byColumn.addView(row(key, level, selected, enabled = true) { write(sort.tapBy(key)) })
            }
            thenColumn.removeAllViews()
            thenColumn.addView(header(R.string.sort_then))
            if (sort.by.key.hasTies) {
                val then = sort.thenInEffect
                thenColumn.addView(row(null, null, then == null, enabled = true) { write(sort.tapThen(null)) })
                for (key in SortKey.entries) {
                    if (key == sort.by.key) continue
                    val selected = then?.key == key
                    val level = if (selected) then!! else SortLevel(key)
                    thenColumn.addView(row(key, level, selected, enabled = true) { write(sort.tapThen(key)) })
                }
            } else {
                thenColumn.addView(row(null, null, selected = false, enabled = false, label = R.string.sort_then_auto) {})
            }
            if (foldersFirst.isChecked != sort.foldersFirst) foldersFirst.isChecked = sort.foldersFirst
            summary.text = summary(context, sort)
        }

        private fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        private fun wideParams() =
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        private fun header(@StringRes text: Int) = TextView(context).apply {
            setTextAppearance(R.style.TextAppearance_ComicReader_Label)
            setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant))
            setText(text)
            setPadding(dp(12), dp(8), dp(12), dp(4))
        }

        /** An option row: its name, and its direction; the selected one is highlighted and flips when tapped. */
        private fun row(
            key: SortKey?,
            level: SortLevel?,
            selected: Boolean,
            enabled: Boolean,
            @StringRes label: Int = key?.let { nameOf(it) } ?: R.string.sort_none,
            onTap: () -> Unit
        ): View {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(48)
                setPadding(dp(12), 0, dp(12), 0)
                isEnabled = enabled
                alpha = if (enabled) 1f else DISABLED_ALPHA
                isSelected = selected
                contentDescription = buildString {
                    append(context.getString(label))
                    if (level != null) append(", ").append(context.getString(directionOf(level)))
                }
            }
            val fg = color(
                if (selected) com.google.android.material.R.attr.colorOnSecondaryContainer
                else com.google.android.material.R.attr.colorOnSurface
            )
            if (selected) {
                row.background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(color(com.google.android.material.R.attr.colorSecondaryContainer))
                }
            } else if (enabled) {
                val ripple = TypedValue()
                context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
                row.setBackgroundResource(ripple.resourceId)
            }
            row.addView(TextView(context).apply {
                setText(label)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextColor(fg)
                if (selected) typeface = Typeface.DEFAULT_BOLD
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (level != null) {
                row.addView(TextView(context).apply {
                    text = context.getString(directionOf(level)) + if (selected) " $FLIP_MARK" else ""
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setTextColor(if (selected) fg else color(com.google.android.material.R.attr.colorOnSurfaceVariant))
                    setPadding(dp(12), 0, 0, 0)
                })
            }
            if (enabled) row.setOnClickListener { onTap() }
            return row
        }

        private fun color(attr: Int) = MaterialColors.getColor(root, attr)

        private fun dp(v: Int) = (v * density).toInt()
    }

    /** Screens at least this wide show the two columns side by side. */
    private const val WIDE_DP = 600
    private const val DISABLED_ALPHA = 0.5f

    /** Marks the selected option as flipping when tapped again. */
    private const val FLIP_MARK = "⇅"
}
