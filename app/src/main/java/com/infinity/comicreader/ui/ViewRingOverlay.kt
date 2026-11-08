package com.infinity.comicreader.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Layout
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import com.google.android.material.R as M
import com.google.android.material.color.MaterialColors
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.shape.MaterialShapeDrawable
import com.infinity.comicreader.R
import com.infinity.comicreader.data.RingOption
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The view ring: over a scrim, round buttons fanned on a quarter arc from the floating
 * button toward the screen's inside ([RingGeometry]), each with its setting's name and
 * what a tap does, "current→next", beside it: the current part muted, the next in the label
 * color. Each label takes the fullest of its value's forms that fits on two lines, shorter
 * ones where labels would overlap ([RingGeometry.fit]); cut with "…" only when none fits. A
 * tap on a button or its label runs [onTap] (the caller writes the setting and calls
 * [refresh]); a long press runs [onLongTap] where an item has a [Item.longLabel]. The hub in
 * the button's place, a tap on the scrim, or Back ([close]) closes it.
 *
 * The finger that opened it may also drag without lifting ([follow], [drag]): the button it
 * points at lights up, lifting there runs [onTap] and closes the ring, lifting on the hub
 * only closes it, and lingering on a button with a [Item.longLabel] runs [onLongTap].
 */
internal class ViewRingOverlay(private val activity: AppCompatActivity) {

    /**
     * One button: [name] and the first of its value's [forms] that fits show beside it; TalkBack
     * hears [spoken], the value in full words. [enabled] false grays it out.
     */
    data class Item(
        val option: RingOption,
        @DrawableRes val icon: Int,
        val name: String,
        val forms: List<RingValue>,
        val enabled: Boolean = true,
        val longLabel: String? = null,
        val spoken: RingValue = forms.first()
    )

    var onTap: (RingOption) -> Unit = {}
    var onLongTap: (RingOption) -> Unit = {}

    private var root: FrameLayout? = null
    private var hub: FloatingActionButton? = null
    private var items: List<Item> = emptyList()

    /** The hub's center in the overlay. */
    private var cx = 0f
    private var cy = 0f

    /** The ring's radius, and where buttons and labels may go in the overlay. */
    private var radius = 0f
    private var bounds = RingGeometry.Box(0f, 0f, 0f, 0f)
    private val buttons = LinkedHashMap<RingOption, Pair<FloatingActionButton, TextView>>()

    /** The views hidden from accessibility while the ring shows, and how they were. */
    private val hidden = ArrayList<Pair<View, Int>>()

    /** The views whose descendants take no key focus while the ring shows, and how they were. */
    private val blocked = ArrayList<Pair<ViewGroup, Int>>()

    /** The floating button the ring opened from; key focus returns to it. */
    private var anchor: View? = null

    /** The host's place in the window, and the hub's radius. */
    private var hostX = 0
    private var hostY = 0
    private var hubRadius = 0f

    /** The finger still down from the long press that opened the ring, if any. */
    private var gesture: RingGesture? = null

    /** The button the dragging finger lights up. */
    private var lit: RingOption? = null
    private val linger = Runnable { onLinger() }

    /** The followed finger's touch slop, and where it was, from the hub, when its linger on [lit] began to count. */
    private var slop = 0f
    private var stillX = 0f
    private var stillY = 0f

    val isOpen: Boolean get() = root != null

    /** Whether [drag] follows a finger. */
    val following: Boolean get() = gesture != null

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
            if (v is ViewGroup) {
                blocked += v to v.descendantFocusability
                v.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            }
        }
        activity.currentFocus?.clearFocus()
        anchor = fab
        host.addView(root, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        this.root = root
        this.items = items

        val at = IntArray(2).also { fab.getLocationInWindow(it) }
        val origin = IntArray(2).also { host.getLocationInWindow(it) }
        cx = at[0] - origin[0] + fab.width / 2f
        cy = at[1] - origin[1] + fab.height / 2f
        hostX = origin[0]
        hostY = origin[1]
        hubRadius = fab.width / 2f
        bounds = bounds(host, origin)
        hub = (fab as? FloatingActionButton).let { main ->
            FloatingActionButton(activity).apply {
                setImageResource(R.drawable.ic_close)
                contentDescription = activity.getString(R.string.view_ring_close)
                backgroundTintList = main?.backgroundTintList
                imageTintList = main?.imageTintList
                setOnClickListener { close() }
            }
        }.also { place(it, cx - fab.width / 2f, cy - fab.height / 2f, fab.width, fab.height) }

        lay(items)
        // Keys go to the ring's first button, never to the list beneath.
        if (!root.isInTouchMode) buttons.values.firstOrNull()?.first?.requestFocus()
        animateIn()
    }

    /** Shows [items] anew after a tap: values and grayed buttons change in place; other options are laid out again. */
    fun refresh(items: List<Item>) {
        val root = root ?: return
        if (items.map { it.option } != this.items.map { it.option }) {
            unfollow()
            for ((button, label) in buttons.values) {
                root.removeView(button)
                root.removeView(label)
            }
            buttons.clear()
            this.items = items
            lay(items)
            return
        }
        this.items = items
        for (item in items) buttons[item.option]?.let { (button, label) -> bind(button, label, item) }
        // The buttons stay; a label whose text grew may take another side or a shorter form.
        val angles = angles()
        val placed = fitLabels(all = true) { RingGeometry.place(angles, cx, cy, bounds, buttonSize().toFloat(), radius, reach(), pad(), it) }
        for ((i, pair) in buttons.values.withIndex()) placeLabel(pair.second, placed.boxes[i])
    }

    /** Closes the ring, at once or [animate]d back into the hub. */
    @SuppressLint("ClickableViewAccessibility")
    fun close(animate: Boolean = true) {
        val root = root ?: return
        unfollow()
        this.root = null
        hub = null
        for ((v, was) in hidden) v.importantForAccessibility = was
        hidden.clear()
        val host = root.parent as? ViewGroup
        // While it fades, the scrim still takes every touch, so none reaches the list beneath.
        root.setOnTouchListener { _, _ -> true }
        root.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        root.isFocusable = false
        for ((v, was) in blocked) v.descendantFocusability = was
        blocked.clear()
        if (!root.isInTouchMode) anchor?.requestFocus()
        anchor = null
        val parts = buttons.values.toList()
        buttons.clear()
        if (!animate || !ValueAnimator.areAnimatorsEnabled()) {
            host?.removeView(root)
            return
        }
        for (child in root.children()) child.isEnabled = false
        for ((button, label) in parts) {
            button.animate().translationX(-button.tag(TAG_DX)).translationY(-button.tag(TAG_DY))
                .scaleX(SCALE_FROM).scaleY(SCALE_FROM).alpha(0f).setStartDelay(0).setDuration(CLOSE_MS).start()
            label.animate().alpha(0f).setStartDelay(0).setDuration(CLOSE_MS / 2).start()
        }
        root.animate().alpha(0f).setDuration(CLOSE_MS).withEndAction { host?.removeView(root) }.start()
    }

    /** Follows the finger that opened the ring, still down at [x], [y] in the window; [drag] gets its moves. */
    fun follow(x: Float, y: Float) {
        if (!isOpen) return
        slop = ViewConfiguration.get(activity).scaledTouchSlop.toFloat()
        gesture = RingGesture(angles(), hubRadius, slop, x - hostX - cx, y - hostY - cy)
    }

    /**
     * The followed finger's [action] (move, up or cancel) at [x], [y] in the window. Till it
     * drags, it is a tap and the ring stays open; once dragging, lifting runs [onTap] on the
     * lit button and closes the ring, or only closes it when none is lit.
     */
    fun drag(action: Int, x: Float, y: Float) {
        val g = gesture ?: return
        // A cancel's place is not the finger's: views pass it on unmoved into their own frame.
        if (action == MotionEvent.ACTION_CANCEL) {
            unfollow()
            if (g.dragging) close()
            return
        }
        val enabled = { i: Int -> items.getOrNull(i)?.enabled == true }
        val dx = x - hostX - cx
        val dy = y - hostY - cy
        val moved = g.move(dx, dy, enabled)
        when (action) {
            MotionEvent.ACTION_MOVE -> when {
                moved -> light(items.getOrNull(g.picked)?.option, tick = true, dx, dy)
                // As with a long press, a finger that moves past the slop has not lingered yet.
                lingers() && hypot(dx - stillX, dy - stillY) > slop -> countLinger(dx, dy)
            }
            MotionEvent.ACTION_UP -> {
                unfollow()
                if (!g.dragging) return
                items.getOrNull(g.picked)?.let { if (isOpen) onTap(it.option) }
                close()
            }
        }
    }

    /** Stops following the finger and lights nothing. */
    private fun unfollow() {
        gesture = null
        light(null, tick = false)
    }

    /**
     * Lights [option]'s button up (pressed and grown), none when null, with the finger at [dx],
     * [dy] from the hub; lingering on one with a long press counts as one.
     */
    private fun light(option: RingOption?, tick: Boolean, dx: Float = 0f, dy: Float = 0f) {
        if (option == lit) return
        root?.removeCallbacks(linger)
        lit?.let { buttons[it]?.first }?.let { grow(it, false) }
        lit = option
        option?.let { buttons[it]?.first }?.let { grow(it, true) }
        if (lingers()) countLinger(dx, dy)
        // Without the flag that ignores it, the system's touch feedback setting applies.
        if (tick) hub?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    /** Whether the lit button has a long press. */
    private fun lingers() = lit.let { o -> o != null && items.firstOrNull { it.option == o }?.longLabel != null }

    /** Counts the linger on the lit button from now, with the finger at [dx], [dy] from the hub. */
    private fun countLinger(dx: Float, dy: Float) {
        stillX = dx
        stillY = dy
        root?.removeCallbacks(linger)
        root?.postDelayed(linger, ViewConfiguration.getLongPressTimeout().toLong())
    }

    /** Grows [button] when lit, back when not; one still flying out of the hub lands on the way. */
    private fun grow(button: View, on: Boolean) {
        button.isPressed = on
        val scale = if (on) LIT_SCALE else 1f
        if (ValueAnimator.areAnimatorsEnabled()) {
            // Taking over the scale alone would leave the fly-out to move a grown button.
            button.animate().translationX(0f).translationY(0f).scaleX(scale).scaleY(scale)
                .setStartDelay(0).setDuration(LIT_MS).start()
        } else {
            button.scaleX = scale
            button.scaleY = scale
        }
    }

    /** The finger stayed on a lit button with a long press: that runs instead, and the finger is let go. */
    private fun onLinger() {
        val option = lit ?: return
        if (gesture == null || !isOpen) return
        unfollow()
        hub?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        onLongTap(option)
    }

    private fun ViewGroup.children() = (0 until childCount).map { getChildAt(it) }

    private fun View.tag(key: Int): Float = (getTag(key) as? Float) ?: 0f

    /** The overlay's room for the ring: inside the system bars and display cutout, [MARGIN_DP] in; [origin] is [host]'s place in the window. */
    private fun bounds(host: View, origin: IntArray): RingGeometry.Box {
        val m = MARGIN_DP * density
        val bars = ViewCompat.getRootWindowInsets(host)
            ?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()) ?: Insets.NONE
        val window = host.rootView
        val left = maxOf(0, bars.left - origin[0])
        val top = maxOf(0, bars.top - origin[1])
        val right = minOf(host.width, window.width - bars.right - origin[0])
        val bottom = minOf(host.height, window.height - bars.bottom - origin[1])
        return RingGeometry.Box(left + m, top + m, right - m, bottom - m)
    }

    private fun buttonSize() = (BUTTON_DP * density).roundToInt()

    /** From a button's center to its label's near side. */
    private fun reach() = buttonSize() / 2f + LABEL_GAP_DP * density

    private fun pad() = LABEL_PAD_DP * density

    /** The buttons' angles, toward the overlay's inside from the hub. */
    private fun angles() = RingGeometry.angles(buttons.size, left = cx > (bounds.left + bounds.right) / 2)

    /** The widest a label grows before it wraps: [LABEL_MAX_DP], or less on a narrow screen. */
    private fun labelMax() = minOf(LABEL_MAX_DP * density, bounds.width * LABEL_MAX_SHARE).roundToInt()

    /** [label]'s measured width and height. */
    private fun size(label: TextView): Pair<Float, Float> {
        val spec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        label.measure(spec, spec)
        return label.measuredWidth.toFloat() to label.measuredHeight.toFloat()
    }

    /** A label's look to try: value form [form] wrapped at [width], and the [size] it takes. */
    private class Shape(val form: Int, val width: Int, val size: Pair<Float, Float>)

    /**
     * The shapes [label] may take for [item], most wanted first: each value form that fits
     * ([fits]) at the widest, then wrapped onto two lines, narrower; for none, the shortest
     * form cut with "…" on its last line.
     */
    private fun shapes(label: TextView, item: Item): List<Shape> {
        val wide = labelMax()
        label.maxLines = Int.MAX_VALUE
        label.ellipsize = null
        val out = ArrayList<Shape>()
        for (k in item.forms.indices) {
            label.text = text(label, item, k)
            label.maxWidth = wide
            val size = size(label)
            if (!fits(label)) continue
            out += Shape(k, wide, size)
            val layout = label.layout ?: continue
            if (layout.lineCount != 2) continue
            // On one line: a narrower label with the value on two.
            val sides = label.totalPaddingLeft + label.totalPaddingRight
            for (share in NARROW_SHARES) {
                val width = (maxOf(layout.getLineWidth(0), layout.getLineWidth(1) * share) + sides).toInt() + 1
                if (width >= size.first) break
                label.maxWidth = width
                val narrow = size(label)
                if (fits(label) && label.layout.lineCount == 1 + VALUE_LINES) {
                    out += Shape(k, width, narrow)
                    break
                }
            }
        }
        if (out.isNotEmpty()) return out
        label.maxLines = 1 + VALUE_LINES
        label.ellipsize = TextUtils.TruncateAt.END
        label.maxWidth = wide
        label.text = text(label, item, item.forms.lastIndex)
        return listOf(Shape(item.forms.lastIndex, wide, size(label)))
    }

    /**
     * Gives each label the most wanted of its [shapes] (only its last, the shortest, when
     * [shortest]) and keeps the labels laid out by [lay] clear of each other
     * ([RingGeometry.fit], shortening [all] where need be). Returns the labels' placement.
     */
    private fun fitLabels(
        all: Boolean = false, shortest: Boolean = false, lay: (List<Pair<Float, Float>>) -> RingGeometry.Placement
    ): RingGeometry.Placement {
        val pairs = buttons.values.toList()
        val usable = pairs.mapIndexed { i, (_, label) -> shapes(label, items[i]).let { if (shortest) listOf(it.last()) else it } }
        val (placed, pick) = RingGeometry.fit(usable.map { shapes -> shapes.map { it.size } }, all, lay)
        for ((i, pair) in pairs.withIndex()) {
            val shape = usable[i][pick[i]]
            pair.second.maxWidth = shape.width
            pair.second.text = text(pair.second, items[i], shape.form)
        }
        return placed
    }

    /** True when [label], just measured, shows its name on one line and its value on at most two, no word split. */
    private fun fits(label: TextView): Boolean {
        val layout: Layout = label.layout ?: return true
        if (layout.lineCount > 1 + VALUE_LINES) return false
        val text = label.text
        for (line in 0 until layout.lineCount - 1) {
            val last = text.getOrNull(layout.getLineEnd(line) - 1) ?: continue
            // The name's line ends at the line break; the value's lines at a space, a hyphen or the arrow's break.
            if (line == 0) {
                if (last != '\n') return false
            } else if (last != ' ' && last != BREAK && last != '-') {
                return false
            }
        }
        return true
    }

    /**
     * Lays [items] out around the hub: as wide as the spacing asks, but small enough that the
     * buttons and their labels stay within [bounds], labels clear of every button and of each
     * other ([RingGeometry.arrange]), each the fullest form that lets them. Only where even
     * the shortest forms overlap does the ring grow: to the smallest radius that clears those,
     * where labels then take the fullest forms that still fit.
     */
    private fun lay(items: List<Item>) {
        val root = root ?: return
        val size = buttonSize()
        for (item in items) {
            val button = FloatingActionButton(activity).apply {
                setOnClickListener { if (isOpen) onTap(item.option) }
            }
            val label = TextView(activity).apply {
                // Its text changes between measures, which reads its layout params before it is placed.
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                maxWidth = labelMax()
                breakStrategy = Layout.BREAK_STRATEGY_BALANCED
                hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
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
            buttons[item.option] = button to label
            bind(button, label, item)
        }
        val angles = angles()
        val want = RingGeometry.radius(items.size, SPACING_DP * density, MIN_RADIUS_DP * density)
        val spacing = size + LABEL_GAP_DP * density
        fun arrange(grow: Float, shortest: Boolean = false) =
            fitLabels(all = true, shortest) { RingGeometry.arrange(angles, cx, cy, bounds, size.toFloat(), want, spacing, reach(), pad(), it, grow) }
        val placed = arrange(1f).takeIf { it.overlap == 0f } ?: run {
            val grown = arrange(GROW, shortest = true).takeIf { it.overlap == 0f } ?: arrange(Float.MAX_VALUE, shortest = true)
            fitLabels(all = true) { RingGeometry.place(angles, cx, cy, bounds, size.toFloat(), grown.radius, reach(), pad(), it) }
        }
        radius = placed.radius
        for ((i, pair) in buttons.values.withIndex()) {
            val (button, label) = pair
            val (dx, dy) = RingGeometry.spot(angles[i], radius)
            button.setTag(TAG_DX, dx)
            button.setTag(TAG_DY, dy)
            place(button, cx + dx - size / 2f, cy + dy - size / 2f, size, size)
            placeLabel(label, placed.boxes[i])
        }
    }

    /** Gives [button] and [label] what [item] shows. */
    private fun bind(button: FloatingActionButton, label: TextView, item: Item) {
        button.setImageResource(item.icon)
        button.backgroundTintList = ColorStateList.valueOf(MaterialColors.getColor(button, M.attr.colorSecondaryContainer))
        button.imageTintList = ColorStateList.valueOf(MaterialColors.getColor(button, M.attr.colorOnSecondaryContainer))
        button.isEnabled = item.enabled
        label.isEnabled = item.enabled
        // A fade still running from the opening would end at the old alpha.
        label.animate().cancel()
        button.alpha = if (item.enabled) 1f else DISABLED_ALPHA
        label.alpha = button.alpha
        val next = item.spoken.next
        button.contentDescription = if (next == null) activity.getString(R.string.view_ring_item, item.name, item.spoken.current)
        else activity.getString(R.string.view_ring_item_next, item.name, item.spoken.current, next)
        if (item.longLabel != null) {
            button.setOnLongClickListener {
                if (isOpen) onLongTap(item.option)
                true
            }
            label.setOnLongClickListener { button.performLongClick() }
            ViewCompat.replaceAccessibilityAction(button, AccessibilityActionCompat.ACTION_LONG_CLICK, item.longLabel, null)
        }
        label.text = text(label, item, 0)
    }

    /**
     * [item]'s label with value form [form]: the name small and muted, then on the next line
     * "current→" in the outline color and the next value in the label color, a line may break after the
     * arrow; only the current value for one with no next.
     */
    private fun text(label: TextView, item: Item, form: Int): CharSequence {
        val variant = MaterialColors.getColor(label, M.attr.colorOnSurfaceVariant)
        val muted = MaterialColors.getColor(label, M.attr.colorOutline)
        val strong = MaterialColors.getColor(label, M.attr.colorOnSurface)
        val value = item.forms[form]
        return SpannableStringBuilder().apply {
            append(item.name, ForegroundColorSpan(variant), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(RelativeSizeSpan(0.85f), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            append('\n')
            val start = length
            val next = value.next
            if (next == null) {
                append(value.current, ForegroundColorSpan(strong), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else {
                append(value.current + ARROW, ForegroundColorSpan(muted), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                append(BREAK)
                append(next, ForegroundColorSpan(strong), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            setSpan(StyleSpan(Typeface.BOLD), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /** Puts [label] at [box]'s left top. */
    private fun placeLabel(label: TextView, box: RingGeometry.Box) =
        place(label, box.left, box.top, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)

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

    /** The open ring's radius; for tests. */
    internal val ringRadius: Float get() = radius

    private companion object {
        /** Material's scrim: black at 32%. */
        val SCRIM = Color.argb(82, 0, 0, 0)
        const val BUTTON_DP = 56
        /** Two thirds of the first ring's 112 and 136 dp. */
        const val SPACING_DP = 75f
        const val MIN_RADIUS_DP = 91f

        /** How much larger the ring grows first where even its shortest labels cannot clear each other. */
        const val GROW = 1.5f
        const val LABEL_GAP_DP = 8f
        const val LABEL_MAX_DP = 320f

        /** The share of the room's width a label may take. */
        const val LABEL_MAX_SHARE = 0.75f

        /** The lines a value may take. */
        const val VALUE_LINES = 2

        /** A one-line value's widths, as shares, tried when wrapping it onto two lines. */
        val NARROW_SHARES = floatArrayOf(0.55f, 0.7f, 0.85f)
        const val ARROW = "→"

        /** Where a value may break: after its arrow. */
        const val BREAK = '\u200B'
        const val MARGIN_DP = 8f
        const val LABEL_PAD_DP = 4f
        const val DISABLED_ALPHA = 0.38f
        const val SCALE_FROM = 0.4f
        const val OPEN_MS = 200L
        const val CLOSE_MS = 150L
        const val STAGGER_MS = 20L
        const val LIT_SCALE = 1.15f
        const val LIT_MS = 100L
        val TAG_DX = R.id.view_ring_dx
        val TAG_DY = R.id.view_ring_dy
    }
}
