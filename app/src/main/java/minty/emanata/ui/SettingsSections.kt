package minty.emanata.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.ReplacementSpan
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.ColorUtils
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceViewHolder
import minty.emanata.R

/** A settings section with a larger header. */
open class SectionCategory @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    PreferenceCategory(context, attrs) {
    init {
        layoutResource = R.layout.preference_section
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        holder.findViewById(R.id.section_chevron)?.visibility = View.GONE
    }
}

/** A settings section whose settings fold away under its header, which a tap toggles; [onToggle] hears each. */
class CollapsibleCategory @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    SectionCategory(context, attrs) {

    var collapsed = false
        set(value) {
            if (field == value) return
            field = value
            notifyChanged()
        }

    var onToggle: ((CollapsibleCategory) -> Unit)? = null

    init {
        isSelectable = true
    }

    /** Unlike other sections, the header takes taps. */
    override fun isEnabled(): Boolean = true

    override fun onClick() {
        collapsed = !collapsed
        onToggle?.invoke(this)
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        holder.findViewById(R.id.section_chevron)?.apply {
            visibility = View.VISIBLE
            rotation = if (collapsed) 0f else 90f
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            holder.itemView.stateDescription = context.getString(if (collapsed) R.string.section_collapsed else R.string.section_expanded)
        }
    }
}

/**
 * Settings marked advanced in the settings XML with `<extra android:name="advanced"
 * android:value="true" />`: hidden while Keep it simple is on, otherwise shown with
 * their title in the theme's tertiary color, muted, and a small "Advanced" tag.
 */
object AdvancedSettings {
    const val EXTRA = "advanced"

    fun isAdvanced(pref: Preference): Boolean = pref.peekExtras()?.getBoolean(EXTRA) == true

    /** How far the tertiary color is muted: its opacity. */
    const val MUTED_ALPHA = 0.8f

    /** [title] in [color], muted, followed by the tag [tag]. */
    fun styled(title: CharSequence, tag: String, color: Int, density: Float): CharSequence {
        val muted = ColorUtils.setAlphaComponent(color, (MUTED_ALPHA * 255).toInt())
        val out = SpannableStringBuilder(title)
        out.setSpan(ForegroundColorSpan(muted), 0, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        out.append("  ")
        val start = out.length
        out.append(tag)
        out.setSpan(TagSpan(muted, density), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return out
    }

    /**
     * Shows or hides every setting in [screen]'s sections: one in a [CollapsibleCategory.collapsed]
     * section is hidden, as is an advanced one while [simple].
     */
    fun apply(screen: PreferenceGroup, simple: Boolean) {
        for (i in 0 until screen.preferenceCount) {
            val section = screen.getPreference(i) as? PreferenceGroup ?: continue
            val collapsed = (section as? CollapsibleCategory)?.collapsed == true
            for (j in 0 until section.preferenceCount) {
                val pref = section.getPreference(j)
                pref.isVisible = !collapsed && !(simple && isAdvanced(pref))
            }
        }
    }
}

/** Its text smaller, in an outlined pill of [color]. */
class TagSpan(private val color: Int, private val density: Float) : ReplacementSpan() {
    private fun textPaint(paint: Paint) = Paint(paint).apply {
        textSize = paint.textSize * SIZE
        color = this@TagSpan.color
    }

    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        // The pill fits in the line as the text around it sizes it.
        fm?.let { paint.getFontMetricsInt(it) }
        return (textPaint(paint).measureText(text, start, end) + 2 * PAD_X * density).toInt()
    }

    override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val small = textPaint(paint)
        val width = small.measureText(text, start, end)
        val metrics = small.fontMetrics
        val height = metrics.descent - metrics.ascent
        val middle = y + (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2
        val box = RectF(x, middle - height / 2 - PAD_Y * density, x + width + 2 * PAD_X * density, middle + height / 2 + PAD_Y * density)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = STROKE * density
            color = this@TagSpan.color
        }
        val radius = box.height() / 2
        box.inset(stroke.strokeWidth / 2, stroke.strokeWidth / 2)
        canvas.drawRoundRect(box, radius, radius, stroke)
        canvas.drawText(text, start, end, x + PAD_X * density, middle - (metrics.ascent + metrics.descent) / 2, small)
    }

    private companion object {
        const val SIZE = 0.7f
        const val PAD_X = 6f
        const val PAD_Y = 1f
        const val STROKE = 1f
    }
}
