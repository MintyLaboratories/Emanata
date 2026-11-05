package com.infinity.comicreader.ui

import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import com.google.android.material.R as M
import com.google.android.material.color.MaterialColors
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.shape.MaterialShapeDrawable
import com.infinity.comicreader.R
import kotlin.math.roundToInt

/**
 * The view ring: over a scrim, round buttons fanned on a quarter arc from the floating
 * button toward the screen's inside ([RingGeometry]), each with its setting's name and
 * current value beside it. A tap on a button or its label runs [onTap] (the caller writes
 * the setting and calls [refresh]); a long press runs [onLongTap] where an item has a
 * [Item.longLabel]. The hub in the button's place, a tap on the scrim, or Back ([close])
 * closes it.
 */
internal class ViewRingOverlay(private val activity: AppCompatActivity) {

    /** One button: [name] and [value] show beside it; [enabled] false grays it out. */
    data class Item(
        val option: RingOption,
        @DrawableRes val icon: Int,
        val name: String,
        val value: String,
        val enabled: Boolean = true,
        val longLabel: String? = null
    )

    var onTap: (RingOption) -> Unit = {}
    var onLongTap: (RingOption) -> Unit = {}

    private var root: FrameLayout? = null
    private var hub: FloatingActionButton? = null
    private var items: List<Item> = emptyList()

    /** The hub's center in the overlay. */
    private var cx = 0f
    private var cy = 0f
    private val buttons = LinkedHashMap<RingOption, Pair<FloatingActionButton, TextView>>()

    /** The views hidden from accessibility while the ring shows, and how they were. */
    private val hidden = ArrayList<Pair<View, Int>>()

    val isOpen: Boolean get() = root != null

    private val density get() = activity.resources.displayMetrics.density

    /** Opens the ring around [fab] with [items], from the top of the arc inward. */
    fun open(fab: View, items: List<Item>) {
        if (isOpen) return
        val host = activity.findViewById<ViewGroup>(android.R.id.content)
        val root = FrameLayout(activity).apply {
            setBackgroundColor(SCRIM)
            isClickable = true
            setOnClickListener { close() }
            contentDescription = activity.getString(R.string.view_ring_close)
            ViewCompat.setAccessibilityPaneTitle(this, activity.getString(R.string.view_ring_open))
        }
        for (i in 0 until host.childCount) {
            val v = host.getChildAt(i)
            hidden += v to v.importantForAccessibility
            v.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        host.addView(root, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        this.root = root
        this.items = items

        val at = IntArray(2).also { fab.getLocationInWindow(it) }
        val origin = IntArray(2).also { host.getLocationInWindow(it) }
        cx = at[0] - origin[0] + fab.width / 2f
        cy = at[1] - origin[1] + fab.height / 2f
        hub = (fab as? FloatingActionButton).let { main ->
            FloatingActionButton(activity).apply {
                setImageResource(R.drawable.ic_close)
                contentDescription = activity.getString(R.string.view_ring_close)
                backgroundTintList = main?.backgroundTintList
                imageTintList = main?.imageTintList
                setOnClickListener { close() }
            }
        }.also { place(it, cx - fab.width / 2f, cy - fab.height / 2f, fab.width, fab.height) }

        lay(items, host.width, host.height)
        animateIn()
    }

    /** Shows [items] anew after a tap: values and grayed buttons change in place; other options are laid out again. */
    fun refresh(items: List<Item>) {
        val root = root ?: return
        if (items.map { it.option } != this.items.map { it.option }) {
            for ((button, label) in buttons.values) {
                root.removeView(button)
                root.removeView(label)
            }
            buttons.clear()
            this.items = items
            lay(items, root.width, root.height)
            return
        }
        this.items = items
        val angles = RingGeometry.angles(items.size, left = cx > root.width / 2f)
        for ((i, item) in items.withIndex()) {
            val (button, label) = buttons[item.option] ?: continue
            bind(button, label, item)
            placeLabel(label, button, angles[i], root.width, root.height)
        }
    }

    /** Closes the ring, at once or [animate]d back into the hub. */
    fun close(animate: Boolean = true) {
        val root = root ?: return
        this.root = null
        hub = null
        for ((v, was) in hidden) v.importantForAccessibility = was
        hidden.clear()
        val host = root.parent as? ViewGroup
        root.isClickable = false
        val parts = buttons.values.toList()
        buttons.clear()
        if (!animate || !ValueAnimator.areAnimatorsEnabled()) {
            host?.removeView(root)
            return
        }
        for (child in root.children()) child.isEnabled = false
        for ((button, label) in parts) {
            button.animate().translationX(-button.tag(TAG_DX)).translationY(-button.tag(TAG_DY))
                .scaleX(SCALE_FROM).scaleY(SCALE_FROM).alpha(0f).setDuration(CLOSE_MS).start()
            label.animate().alpha(0f).setDuration(CLOSE_MS / 2).start()
        }
        root.animate().alpha(0f).setDuration(CLOSE_MS).withEndAction { host?.removeView(root) }.start()
    }

    private fun ViewGroup.children() = (0 until childCount).map { getChildAt(it) }

    private fun View.tag(key: Int): Float = (getTag(key) as? Float) ?: 0f

    /** Lays [items] out around the hub's center in a [width] x [height] overlay. */
    private fun lay(items: List<Item>, width: Int, height: Int) {
        val root = root ?: return
        val size = (BUTTON_DP * density).roundToInt()
        val left = cx > width / 2f
        val angles = RingGeometry.angles(items.size, left)
        val margin = MARGIN_DP * density
        // As wide as the spacing asks, but within the room above and beside the hub.
        val room = minOf(if (left) cx else width - cx, cy) - size / 2f - margin
        val radius = RingGeometry.radius(items.size, SPACING_DP * density, MIN_RADIUS_DP * density).coerceAtMost(room.coerceAtLeast(size.toFloat()))
        for ((i, item) in items.withIndex()) {
            val (dx, dy) = RingGeometry.spot(angles[i], radius)
            val button = FloatingActionButton(activity).apply {
                setOnClickListener { if (isOpen) onTap(item.option) }
                setTag(TAG_DX, dx)
                setTag(TAG_DY, dy)
            }
            place(button, cx + dx - size / 2f, cy + dy - size / 2f, size, size)
            val label = TextView(activity).apply {
                maxWidth = (LABEL_MAX_DP * density).roundToInt()
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                val h = (12 * density).roundToInt()
                val v = (6 * density).roundToInt()
                setPadding(h, v, h, v)
                background = MaterialShapeDrawable().apply {
                    fillColor = ColorStateList.valueOf(MaterialColors.getColor(root, M.attr.colorSurfaceContainerHigh))
                    setCornerSize(12 * density)
                }
                elevation = 3 * density
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                setOnClickListener { if (isOpen) button.performClick() }
            }
            root.addView(label, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            buttons[item.option] = button to label
            bind(button, label, item)
            placeLabel(label, button, angles[i], width, height)
        }
    }

    /** Gives [button] and [label] what [item] shows. */
    private fun bind(button: FloatingActionButton, label: TextView, item: Item) {
        button.setImageResource(item.icon)
        button.backgroundTintList = ColorStateList.valueOf(MaterialColors.getColor(button, M.attr.colorSecondaryContainer))
        button.imageTintList = ColorStateList.valueOf(MaterialColors.getColor(button, M.attr.colorOnSecondaryContainer))
        button.isEnabled = item.enabled
        label.isEnabled = item.enabled
        button.alpha = if (item.enabled) 1f else DISABLED_ALPHA
        label.alpha = button.alpha
        button.contentDescription = activity.getString(R.string.view_ring_item, item.name, item.value)
        if (item.longLabel != null) {
            button.setOnLongClickListener {
                if (isOpen) onLongTap(item.option)
                true
            }
            label.setOnLongClickListener { button.performLongClick() }
            ViewCompat.replaceAccessibilityAction(button, AccessibilityActionCompat.ACTION_LONG_CLICK, item.longLabel, null)
        }
        val variant = MaterialColors.getColor(label, M.attr.colorOnSurfaceVariant)
        val strong = MaterialColors.getColor(label, M.attr.colorOnSurface)
        label.text = SpannableStringBuilder().apply {
            append(item.name, ForegroundColorSpan(variant), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(RelativeSizeSpan(0.85f), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            append('\n')
            val start = length
            append(item.value)
            setSpan(ForegroundColorSpan(strong), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(StyleSpan(Typeface.BOLD), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /** Puts [label] outward of [button] at [angle], kept within the [width] x [height] overlay. */
    private fun placeLabel(label: TextView, button: View, angle: Double, width: Int, height: Int) {
        val spec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        label.measure(spec, spec)
        val w = label.measuredWidth
        val h = label.measuredHeight
        val lp = button.layoutParams as FrameLayout.LayoutParams
        val bx = lp.leftMargin + lp.width / 2f
        val by = lp.topMargin + lp.height / 2f
        val reach = lp.width / 2f + LABEL_GAP_DP * density
        val (dx, dy) = RingGeometry.labelCenter(angle, reach, w.toFloat(), h.toFloat())
        val margin = MARGIN_DP * density
        val x = (bx + dx - w / 2f).coerceIn(margin, (width - w - margin).coerceAtLeast(margin))
        val y = (by + dy - h / 2f).coerceIn(margin, (height - h - margin).coerceAtLeast(margin))
        place(label, x, y, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun place(view: View, x: Float, y: Float, width: Int, height: Int) {
        val lp = FrameLayout.LayoutParams(width, height).apply {
            leftMargin = x.roundToInt()
            topMargin = y.roundToInt()
        }
        if (view.parent == null) root?.addView(view, lp) else view.layoutParams = lp
    }

    /** The scrim fades in; the buttons fly out of the hub one after another, their labels after them. */
    private fun animateIn() {
        val root = root ?: return
        if (!ValueAnimator.areAnimatorsEnabled()) return
        root.alpha = 0f
        root.animate().alpha(1f).setDuration(OPEN_MS).start()
        val decelerate = DecelerateInterpolator()
        for ((i, pair) in buttons.values.withIndex()) {
            val (button, label) = pair
            button.translationX = -button.tag(TAG_DX)
            button.translationY = -button.tag(TAG_DY)
            button.scaleX = SCALE_FROM
            button.scaleY = SCALE_FROM
            button.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f)
                .setStartDelay(STAGGER_MS * i).setDuration(OPEN_MS).setInterpolator(decelerate).start()
            val shown = label.alpha
            label.alpha = 0f
            label.animate().alpha(shown).setStartDelay(STAGGER_MS * i + OPEN_MS / 2).setDuration(OPEN_MS / 2).start()
        }
    }

    /** The open ring's button for [option]; for tests. */
    internal fun button(option: RingOption): FloatingActionButton? = buttons[option]?.first

    /** The open ring's label for [option]; for tests. */
    internal fun label(option: RingOption): TextView? = buttons[option]?.second

    /** The hub in the floating button's place; for tests. */
    internal fun hub(): FloatingActionButton? = hub

    /** The scrim the ring shows on; for tests. */
    internal fun scrim(): View? = root

    private companion object {
        /** Material's scrim: black at 32%. */
        val SCRIM = Color.argb(82, 0, 0, 0)
        const val BUTTON_DP = 56
        const val SPACING_DP = 112f
        const val MIN_RADIUS_DP = 136f
        const val LABEL_GAP_DP = 8f
        const val LABEL_MAX_DP = 200f
        const val MARGIN_DP = 8f
        const val DISABLED_ALPHA = 0.38f
        const val SCALE_FROM = 0.4f
        const val OPEN_MS = 200L
        const val CLOSE_MS = 150L
        const val STAGGER_MS = 20L
        val TAG_DX = R.id.view_ring_dx
        val TAG_DY = R.id.view_ring_dy
    }
}
