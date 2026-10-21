package com.infinity.comicreader.ui

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.color.MaterialColors
import com.infinity.comicreader.R
import com.infinity.comicreader.data.Crumb

/**
 * The location as tappable crumbs, `Device / Comics / Series`: the root crumb stays
 * pinned at the start while the folders after it scroll, kept scrolled so the
 * current folder is always visible. The current folder is not tappable; forward
 * crumbs after it (a deeper folder browsed before going up) are gray and tappable.
 */
class CrumbBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    /** Tapped a folder crumb, or the root crumb when it has no menu. */
    var onCrumbClick: ((Crumb) -> Unit)? = null

    /** Tapped the root crumb while it shows its menu marker. */
    var onRootMenuClick: ((Crumb) -> Unit)? = null

    private val rootCrumb: TextView
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
        rootCrumb = crumbView()
        addView(rootCrumb, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
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

    /**
     * Shows [list] with the current folder at [current] (default: the last crumb);
     * [rootMenu] marks the root crumb as opening a menu of roots.
     */
    fun show(list: List<Crumb>, rootMenu: Boolean, current: Int = list.lastIndex) {
        crumbs = list
        currentIndex = current
        row.removeAllViews()
        currentView = null
        val root = list.firstOrNull() ?: return
        val atRoot = current == 0
        rootCrumb.text = if (rootMenu) context.getString(R.string.crumb_root_menu, root.label) else root.label
        style(rootCrumb, if (atRoot && !rootMenu) Kind.CURRENT else Kind.BEHIND)
        rootCrumb.setOnClickListener(
            when {
                rootMenu -> View.OnClickListener { onRootMenuClick?.invoke(root) }
                atRoot -> null
                else -> View.OnClickListener { onCrumbClick?.invoke(root) }
            }
        )
        rootCrumb.isClickable = rootMenu || !atRoot
        if (atRoot) currentView = rootCrumb
        for ((i, crumb) in list.withIndex()) {
            if (i == 0) continue
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
        if (view == null || view === rootCrumb || currentIndex == crumbs.lastIndex) {
            // Clamped to the scroll range.
            scroll.scrollTo(row.width, 0)
            return
        }
        scroll.scrollTo((view.right + dp(FORWARD_PEEK_DP) - scroll.width).coerceAtLeast(0), 0)
    }

    private fun crumbView(): TextView = TextView(context).apply {
        setTextAppearance(R.style.TextAppearance_ComicReader_Crumb)
        maxLines = 1
        minHeight = dp(MIN_TOUCH_DP)
        val h = dp(12)
        val v = dp(8)
        setPadding(h, v, h, v)
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun separatorView(ahead: Boolean): TextView = TextView(context).apply {
        setTextAppearance(R.style.TextAppearance_ComicReader_Crumb)
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

        /** How much of the forward crumbs shows beyond the current one when they overflow. */
        const val FORWARD_PEEK_DP = 72
        const val AHEAD_ALPHA = 0.7f
    }
}
