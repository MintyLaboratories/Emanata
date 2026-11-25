package minty.emanata.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Matrix
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.color.MaterialColors
import minty.emanata.R
import minty.emanata.data.Crumb

/**
 * The location as tappable crumbs, `[app icon] / Device / Comics / Series`: the
 * app's one-color icon stays pinned at the start and opens the locations, while
 * the crumbs after it scroll, kept scrolled so the current folder is always
 * visible. The current folder is not tappable; forward crumbs after it (a deeper
 * folder browsed before going up) are gray and tappable.
 */
class CrumbBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    /** Tapped a folder crumb, the root one included. */
    var onCrumbClick: ((Crumb) -> Unit)? = null

    /** Tapped the app icon before the crumbs. */
    var onLocationsClick: (() -> Unit)? = null

    /** The app's icon in one color, opening the locations. */
    val locationsButton: ImageView = ImageView(context)
    private val scroll = HorizontalScrollView(context)
    private val row = LinearLayout(context)

    /** The crumbs shown, for tests and accessibility. */
    var crumbs: List<Crumb> = emptyList()
        private set

    /** Index of the current folder in [crumbs]; the ones after it are forward crumbs. */
    var currentIndex: Int = -1
        private set

    private var currentView: View? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        locationsButton.apply {
            setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_launcher_monochrome))
            scaleType = ImageView.ScaleType.MATRIX
            imageTintList = ColorStateList.valueOf(MaterialColors.getColor(this@CrumbBar, androidx.appcompat.R.attr.colorPrimary))
            val pad = dp(ICON_PADDING_DP)
            setPadding(pad, pad, pad, pad)
            contentDescription = context.getString(R.string.choose_root)
            val ripple = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true)
            setBackgroundResource(ripple.resourceId)
            setOnClickListener { onLocationsClick?.invoke() }
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitGlyph() }
        }
        addView(locationsButton, LayoutParams(dp(MIN_TOUCH_DP), dp(MIN_TOUCH_DP)))
        scroll.isHorizontalScrollBarEnabled = false
        scroll.isFillViewport = false
        row.orientation = HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        scroll.addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(scroll, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        // Whenever the crumbs or the space for them change, show the end.
        row.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> scrollToEnd() }
        scroll.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> scrollToEnd() }
    }

    /** Shows [list] with the current folder at [current] (default: the last crumb). */
    fun show(list: List<Crumb>, current: Int = list.lastIndex) {
        crumbs = list
        currentIndex = current
        row.removeAllViews()
        currentView = null
        for ((i, crumb) in list.withIndex()) {
            val kind = when {
                i < current -> Kind.BEHIND
                i == current -> Kind.CURRENT
                else -> Kind.AHEAD
            }
            row.addView(separatorView(ahead = kind == Kind.AHEAD))
            val view = crumbView()
            view.text = crumb.label
            style(view, kind)
            if (kind != Kind.CURRENT) view.setOnClickListener { onCrumbClick?.invoke(crumb) }
            if (kind == Kind.CURRENT) currentView = view
            row.addView(view)
        }
        scrollToEnd()
    }

    /** Scrolls the current crumb, and a little of any forward crumbs, into view. */
    private fun scrollToEnd() {
        val view = currentView
        if (view == null || currentIndex == crumbs.lastIndex) {
            // Clamped to the scroll range.
            scroll.scrollTo(row.width, 0)
            return
        }
        scroll.scrollTo((view.right + dp(FORWARD_PEEK_DP) - scroll.width).coerceAtLeast(0), 0)
    }

    /**
     * Scales the adaptive icon's monochrome layer so its safe zone, where the glyph
     * lives, fills the button's padded box.
     */
    private fun fitGlyph() {
        val icon = locationsButton.drawable ?: return
        val box = locationsButton.width - locationsButton.paddingLeft - locationsButton.paddingRight
        val full = icon.intrinsicWidth
        if (box <= 0 || full <= 0) return
        val scale = box / (full * SAFE_ZONE / ADAPTIVE_SIZE)
        val offset = full * (ADAPTIVE_SIZE - SAFE_ZONE) / 2f / ADAPTIVE_SIZE * scale
        locationsButton.imageMatrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate(-offset, -offset)
        }
    }

    private fun crumbView(): TextView = TextView(context).apply {
        setTextAppearance(R.style.TextAppearance_Emanata_Crumb)
        maxLines = 1
        minHeight = dp(MIN_TOUCH_DP)
        val h = dp(12)
        val v = dp(8)
        setPadding(h, v, h, v)
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun separatorView(ahead: Boolean): TextView = TextView(context).apply {
        setTextAppearance(R.style.TextAppearance_Emanata_Crumb)
        text = "/"
        setTextColor(MaterialColors.getColor(this@CrumbBar, com.google.android.material.R.attr.colorOnSurfaceVariant))
        if (ahead) alpha = AHEAD_ALPHA
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** A crumb before the current folder, the current folder, or a forward crumb after it. */
    private enum class Kind { BEHIND, CURRENT, AHEAD }

    private fun style(view: TextView, kind: Kind) {
        val attr = when (kind) {
            Kind.CURRENT -> com.google.android.material.R.attr.colorOnSurface
            Kind.BEHIND -> androidx.appcompat.R.attr.colorPrimary
            Kind.AHEAD -> com.google.android.material.R.attr.colorOnSurfaceVariant
        }
        view.setTextColor(MaterialColors.getColor(this, attr))
        view.alpha = if (kind == Kind.AHEAD) AHEAD_ALPHA else 1f
        if (kind == Kind.CURRENT) {
            view.background = null
        } else {
            val ripple = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
            view.setBackgroundResource(ripple.resourceId)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        /** Crumbs are at least this tall, to be easy to hit. */
        const val MIN_TOUCH_DP = 48

        /** Room around the 24 dp app icon in its 48 dp button. */
        const val ICON_PADDING_DP = 12

        /** An adaptive icon's size and its safe zone's diameter, in dp. */
        const val ADAPTIVE_SIZE = 108f
        const val SAFE_ZONE = 66f

        /** How much of the forward crumbs shows beyond the current one when they overflow. */
        const val FORWARD_PEEK_DP = 72
        const val AHEAD_ALPHA = 0.7f
    }
}
