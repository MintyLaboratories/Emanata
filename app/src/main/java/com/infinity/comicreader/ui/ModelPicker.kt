package com.infinity.comicreader.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.appcompat.widget.TooltipCompat
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.widget.NestedScrollView
import androidx.core.widget.TextViewCompat
import com.google.android.material.slider.Slider
import com.infinity.comicreader.R
import com.infinity.comicreader.ai.AiModel
import com.infinity.comicreader.ai.AiStep
import com.infinity.comicreader.ai.ModelCatalog
import com.infinity.comicreader.ai.ModelRatings
import com.infinity.comicreader.ai.RatingCategory
import java.text.NumberFormat
import kotlin.math.roundToInt

/**
 * An AI step's model picker, shared by Settings ([AiChoiceDialog]) and the reader sheet: a row
 * per choice ([PickerRows]) with a radio button, its short name (marked when it is the
 * default), its speed in stars and an info button, which shows the model's full name and size,
 * its ratings and what it does, and hides them again. A family's row has a level slider. A
 * tap on a row picks it ([onPick], done: the dialog closes); letting go of a slider (or moving
 * it by keyboard or screen reader) picks the family at that level ([onPick], not done: the
 * picker stays open, its radio moved).
 */
class ModelPicker(
    private val context: Context,
    rows: List<PickerRow>,
    current: String?,
    private val onPick: (id: String?, done: Boolean) -> Unit
) {
    /** The id picked, null for none. */
    var picked: String? = current
        private set

    private val fastest = ModelRatings.fastestMs()
    private val list = LinearLayout(context).apply {
        id = R.id.ai_model_list
        orientation = LinearLayout.VERTICAL
        setPadding(0, context.resources.getDimensionPixelSize(R.dimen.ai_model_row_padding), 0, 0)
    }

    /** The picker, scrolling. */
    val view: View = NestedScrollView(context).apply { addView(list, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) }

    private val holders: List<Holder> = rows.map { Holder(it) }

    init {
        for (h in holders) list.addView(h.item)
        // The picked row in sight as the picker opens.
        view.post { holders.firstOrNull { it.chosen }?.let { (view as NestedScrollView).scrollTo(0, (it.item.top - it.item.height).coerceAtLeast(0)) } }
    }

    private fun pick(id: String?, done: Boolean) {
        picked = id
        for (h in holders) h.bindChecked()
        onPick(id, done)
    }

    private fun stars(halves: Int): String = NumberFormat.getInstance().apply { maximumFractionDigits = 1 }.format(halves / 2.0)

    /** What a screen reader says of rating [halves] under [label]. */
    private fun said(label: String, halves: Int): String =
        if (halves == ModelRatings.WORSE) context.getString(R.string.ai_rating_worse_stars, label)
        else context.getString(R.string.ai_rating_stars, label, stars(halves))

    private inner class Holder(val row: PickerRow) {
        val item: View = LayoutInflater.from(context).inflate(R.layout.item_ai_model, list, false)
        private val main: View = item.findViewById(R.id.ai_model_row)
        private val check: RadioButton = item.findViewById(R.id.ai_model_check)
        private val name: TextView = item.findViewById(R.id.ai_model_name)
        private val speedLine: View = item.findViewById(R.id.ai_model_speed_line)
        private val speed: StarsView = item.findViewById(R.id.ai_model_speed)
        private val info: ImageButton = item.findViewById(R.id.ai_model_info)
        private val levelBox: View = item.findViewById(R.id.ai_model_level)
        private val slider: Slider = item.findViewById(R.id.ai_model_level_slider)
        private val levelName: TextView = item.findViewById(R.id.ai_model_level_name)
        private val details: View = item.findViewById(R.id.ai_model_details)
        private val fullName: TextView = item.findViewById(R.id.ai_model_full_name)
        private val ratings: TableLayout = item.findViewById(R.id.ai_model_ratings)
        private val description: TextView = item.findViewById(R.id.ai_model_description)

        /** The level shown: the picked one when picked, else the family's first ([PickerRow.firstLevel]). */
        private var level = row.indexOf(picked).takeIf { it >= 0 } ?: row.firstLevel.coerceAtLeast(0)
        private var expanded = false
        private var tracking = false

        private val model: AiModel? get() = row.models.getOrNull(level)

        val chosen: Boolean get() = if (row.models.isEmpty()) picked == null else row.indexOf(picked) >= 0

        private val title: String = row.models.singleOrNull()?.takeIf { it.id == ModelCatalog.DEFAULT_UPSCALE }
            ?.let { context.getString(R.string.ai_model_default, row.name) } ?: row.name

        init {
            name.text = title
            main.setOnClickListener { pick(model?.id, true) }
            ViewCompat.setAccessibilityDelegate(main, object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = RadioButton::class.java.name
                    info.isCheckable = true
                    @Suppress("DEPRECATION") // Its replacement, a checked state, is newer than the core library's minimum.
                    info.isChecked = chosen
                }
            })
            if (row.models.isEmpty()) {
                info.visibility = View.GONE
                speedLine.visibility = View.GONE
            } else {
                info.setOnClickListener {
                    expanded = !expanded
                    bindDetails()
                }
            }
            if (row.family) {
                levelBox.visibility = View.VISIBLE
                slider.valueTo = row.models.lastIndex.toFloat()
                slider.value = level.toFloat()
                slider.setLabelFormatter { row.levelNames[it.roundToInt().coerceIn(row.levelNames.indices)] }
                slider.contentDescription = context.getString(R.string.ai_model_level_of, row.name)
                slider.addOnChangeListener { _, value, fromUser ->
                    level = value.roundToInt()
                    bindLevel()
                    // Dragging picks on release; keys and screen readers pick at once.
                    if (fromUser && !tracking) pickLevel()
                }
                slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                    override fun onStartTrackingTouch(slider: Slider) {
                        tracking = true
                    }

                    override fun onStopTrackingTouch(slider: Slider) {
                        tracking = false
                        pickLevel()
                    }
                })
            }
            bindLevel()
            bindChecked()
        }

        /** Picks the level shown, unless it is picked already. */
        private fun pickLevel() {
            val id = model?.id ?: return
            if (id != picked) pick(id, false)
        }

        fun bindChecked() {
            check.isChecked = chosen
        }

        /** The level's name, speed and details, and what a screen reader says of the row. */
        private fun bindLevel() {
            val halves = if (row.models.isEmpty()) null else row.speed(level, fastest)
            speed.halves = halves ?: 0
            if (row.models.isNotEmpty()) speedLine.visibility = if (halves == null) View.GONE else View.VISIBLE
            if (row.family) levelName.text = row.levelNames[level]
            val speedSaid = halves?.let { said(context.getString(R.string.ai_rating_speed), it) }
            val levelSaid = if (row.family) "${context.getString(R.string.ai_model_level)} ${row.levelNames[level]}" else null
            main.contentDescription = listOfNotNull(title, levelSaid, speedSaid).joinToString(", ")
            bindDetails()
        }

        /** The details under the row, shown while expanded; the info button says which way it goes. */
        private fun bindDetails() {
            if (row.models.isEmpty()) return
            info.contentDescription = context.getString(if (expanded) R.string.ai_model_less_info else R.string.ai_model_more_info, row.name)
            TooltipCompat.setTooltipText(info, info.contentDescription)
            details.visibility = if (expanded) View.VISIBLE else View.GONE
            if (!expanded) return
            val m = model ?: return
            fullName.text = context.getString(R.string.ai_model_entry, m.name, ModelText.size(context, m.bytes))
            ratings.removeAllViews()
            // The level's own speed, then what the family offers at its largest level, or the model's ratings.
            row.speed(level, fastest)?.let { ratings.addView(ratingRow(context.getString(R.string.ai_rating_speed), null, it)) }
            val rated = row.ratings(level)
            if (row.sizes && rated.isNotEmpty()) {
                ratings.addView(TextView(context).apply {
                    text = context.getString(R.string.ai_ratings_up_to, row.levelNames.last())
                    TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                    setPadding(0, context.resources.getDimensionPixelSize(R.dimen.ai_model_row_padding), 0, 0)
                })
            }
            for ((category, halves) in rated) ratings.addView(ratingRow(category, halves))
            description.text = listOfNotNull(
                ModelText.description(context, m),
                context.getString(R.string.ai_model_slow, m.pageSeconds).takeIf { m.slow }
            ).joinToString("\n")
            description.visibility = if (description.text.isEmpty()) View.GONE else View.VISIBLE
        }

        private fun ratingRow(category: RatingCategory, halves: Int): TableRow {
            val (label, hint) = when (category) {
                RatingCategory.CRISP -> R.string.ai_rating_crisp to R.string.ai_rating_crisp_hint
                RatingCategory.COLOR -> R.string.ai_rating_color to R.string.ai_rating_color_hint
                RatingCategory.DETAIL -> R.string.ai_rating_detail to R.string.ai_rating_detail_hint
                RatingCategory.DESHARP -> R.string.ai_rating_desharp to R.string.ai_rating_desharp_hint
                RatingCategory.LINES -> R.string.ai_rating_lines to R.string.ai_rating_lines_hint
            }
            return ratingRow(context.getString(label), context.getString(hint), halves)
        }

        /** A ratings row: [label], [halves] as stars, and words for a rating that makes things worse; [hint] on long press and to screen readers. */
        private fun ratingRow(label: String, hint: String?, halves: Int): TableRow = TableRow(context).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = label
                TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                setPaddingRelative(0, 0, context.resources.getDimensionPixelSize(R.dimen.ai_model_name_start), 0)
            })
            // Stars and, for a rating that makes things worse, its words, in one cell: right after the star.
            addView(LinearLayout(context).apply {
                gravity = android.view.Gravity.CENTER_VERTICAL
                addView(StarsView(context).apply { this.halves = halves })
                if (halves == ModelRatings.WORSE) {
                    addView(TextView(context).apply {
                        text = context.getString(R.string.ai_rating_worse)
                        TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                        setPaddingRelative(context.resources.getDimensionPixelSize(R.dimen.ai_model_row_end), 0, 0, 0)
                    })
                }
            })
            val padding = context.resources.getDimensionPixelSize(R.dimen.ai_model_rating_padding)
            setPadding(0, padding, 0, padding)
            contentDescription = listOfNotNull(said(label, halves), hint).joinToString(". ")
            ViewCompat.setScreenReaderFocusable(this, true)
            for (i in 0 until childCount) getChildAt(i).importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            if (hint != null) TooltipCompat.setTooltipText(this, hint)
        }
    }

    companion object {
        /** The picker of [step]'s models, [current] picked (null: none). */
        fun of(context: Context, step: AiStep, keepSimple: Boolean, current: String?, onPick: (id: String?, done: Boolean) -> Unit) =
            ModelPicker(context, PickerRows.of(step, keepSimple, current, context.getString(R.string.ai_none)), current, onPick)
    }
}
