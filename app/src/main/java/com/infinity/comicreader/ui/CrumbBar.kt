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
 * The location as tappable crumbs, `Device / _ / Comics`: the root crumb stays
 * pinned at the start while the folders after it scroll, kept scrolled to the end
 * so the current folder is always visible. The current folder is not tappable.
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

    /** Shows [list]; [rootMenu] marks the root crumb as opening a menu of roots. */
    fun show(list: List<Crumb>, rootMenu: Boolean) {
        crumbs = list
        row.removeAllViews()
        val root = list.firstOrNull() ?: return
        val atRoot = list.size == 1
        rootCrumb.text = if (rootMenu) context.getString(R.string.crumb_root_menu, root.label) else root.label
        style(rootCrumb, current = atRoot && !rootMenu)
        rootCrumb.setOnClickListener(
            when {
                rootMenu -> View.OnClickListener { onRootMenuClick?.invoke(root) }
                atRoot -> null
                else -> View.OnClickListener { onCrumbClick?.invoke(root) }
            }
        )
        rootCrumb.isClickable = rootMenu || !atRoot
        for ((i, crumb) in list.withIndex()) {
            if (i == 0) continue
            row.addView(separatorView())
            val view = crumbView()
            view.text = crumb.label
            val current = i == list.lastIndex
            style(view, current)
            if (!current) view.setOnClickListener { onCrumbClick?.invoke(crumb) }
            row.addView(view)
        }
        scrollToEnd()
    }

    private fun scrollToEnd() {
        // Clamped to the scroll range.
        scroll.scrollTo(row.width, 0)
    }

    private fun crumbView(): TextView = TextView(context).apply {
        setTextAppearance(R.style.TextAppearance_ComicReader_Crumb)
        maxLines = 1
        val h = dp(6)
        val v = dp(10)
        setPadding(h, v, h, v)
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun separatorView(): TextView = TextView(context).apply {
        setTextAppearance(R.style.TextAppearance_ComicReader_Crumb)
        text = "/"
        setTextColor(MaterialColors.getColor(this@CrumbBar, com.google.android.material.R.attr.colorOnSurfaceVariant))
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun style(view: TextView, current: Boolean) {
        val attr = if (current) com.google.android.material.R.attr.colorOnSurface else androidx.appcompat.R.attr.colorPrimary
        view.setTextColor(MaterialColors.getColor(this, attr))
        if (current) {
            view.background = null
        } else {
            val ripple = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
            view.setBackgroundResource(ripple.resourceId)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
