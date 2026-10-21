package com.infinity.comicreader.ui

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.infinity.comicreader.R
import com.infinity.comicreader.data.EntryType
import com.infinity.comicreader.data.LibraryFilter
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.ReadStatus
import com.infinity.comicreader.data.ReadingDirection

/**
 * One tab's filter: file types, reading direction, read status, year and length
 * ranges. Every change is written at once and the list follows live.
 */
object FilterDialog {

    private val TYPES = listOf(
        EntryType.FOLDER to R.string.filter_folders,
        EntryType.ARCHIVE to R.string.filter_archives,
        EntryType.PDF to R.string.filter_pdfs,
        EntryType.IMAGE to R.string.filter_images
    )

    private val STATUSES = listOf(
        ReadStatus.UNREAD to R.string.filter_unread,
        ReadStatus.IN_PROGRESS to R.string.filter_in_progress,
        ReadStatus.FINISHED to R.string.filter_finished
    )

    /** Opens [tab]'s filter. */
    fun show(context: Context, prefs: Prefs, tab: String): AlertDialog {
        val content = Content(context, prefs, tab)
        return AlertDialog.Builder(context)
            .setTitle(R.string.menu_filter)
            .setView(ScrollView(context).apply { addView(content.root) })
            .setPositiveButton(R.string.done, null)
            .setNeutralButton(R.string.filter_clear) { _, _ -> prefs.setFilter(tab, LibraryFilter.NONE) }
            .show()
    }

    private class Content(val context: Context, val prefs: Prefs, val tab: String) {
        private val density = context.resources.displayMetrics.density
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }

        /** The filter as shown; each change writes it. */
        private var filter = prefs.filter(tab)

        init {
            section(R.string.filter_types)
            root.addView(ChipGroup(context).apply {
                for ((type, label) in TYPES) addView(chip(label, type in filter.types) { on ->
                    update(filter.copy(types = if (on) filter.types + type else filter.types - type))
                })
            })

            section(R.string.sort_direction)
            root.addView(ChipGroup(context).apply {
                isSingleSelection = true
                isSelectionRequired = true
                val options = listOf(null to R.string.filter_any, ReadingDirection.LTR to R.string.filter_ltr, ReadingDirection.RTL to R.string.filter_rtl)
                for ((direction, label) in options) addView(chip(label, filter.direction == direction) { on ->
                    if (on) update(filter.copy(direction = direction))
                })
            })

            section(R.string.filter_status)
            root.addView(ChipGroup(context).apply {
                for ((status, label) in STATUSES) addView(chip(label, status in filter.statuses) { on ->
                    update(filter.copy(statuses = if (on) filter.statuses + status else filter.statuses - status))
                })
            })

            section(R.string.sort_year)
            root.addView(range(filter.minYear, filter.maxYear) { min, max -> update(filter.copy(minYear = min, maxYear = max)) })

            section(R.string.filter_length)
            root.addView(range(filter.minLength, filter.maxLength) { min, max -> update(filter.copy(minLength = min, maxLength = max)) })
        }

        private fun update(next: LibraryFilter) {
            filter = next
            prefs.setFilter(tab, next)
        }

        private fun section(@StringRes title: Int) {
            root.addView(TextView(context).apply {
                setTextAppearance(R.style.TextAppearance_ComicReader_Label)
                setTextColor(MaterialColors.getColor(root, com.google.android.material.R.attr.colorOnSurfaceVariant))
                setText(title)
                setPadding(0, dp(12), 0, dp(2))
            })
        }

        private fun chip(@StringRes label: Int, checked: Boolean, onChange: (Boolean) -> Unit) =
            Chip(context, null, com.google.android.material.R.attr.chipStyle).apply {
                setText(label)
                isCheckable = true
                isChecked = checked
                setOnCheckedChangeListener { _, on -> onChange(on) }
            }

        /** "From" and "To" number fields; a blank one is no bound. */
        private fun range(min: Int?, max: Int?, onChange: (Int?, Int?) -> Unit): View {
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            val from = numberField(R.string.filter_from, min)
            val to = numberField(R.string.filter_to, max)
            val watcher = object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    onChange(from.editText?.text?.toString()?.toIntOrNull(), to.editText?.text?.toString()?.toIntOrNull())
                }
            }
            from.editText?.addTextChangedListener(watcher)
            to.editText?.addTextChangedListener(watcher)
            row.addView(from, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(to, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
            return row
        }

        private fun numberField(@StringRes hint: Int, value: Int?) = TextInputLayout(context).apply {
            setHint(hint)
            addView(TextInputEditText(this.context).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                maxLines = 1
                setText(value?.toString().orEmpty())
            })
        }

        private fun dp(v: Int) = (v * density).toInt()
    }
}
