package com.infinity.comicreader.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.color.MaterialColors
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.infinity.comicreader.R
import com.infinity.comicreader.files.FileClipboard
import com.infinity.comicreader.files.FileWork
import com.infinity.comicreader.util.FileUtils
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** A bubble of the operation stack: a pick waiting to be pasted, or a queued operation. */
internal sealed class Bubble {
    /** Its place in the stack: older above newer. */
    abstract val order: Long

    /** Tells it apart from every other bubble, of either kind. */
    abstract val key: Any

    data class Paste(val clip: FileClipboard.Clip) : Bubble() {
        override val order: Long get() = clip.id
        override val key: Any get() = keyOf(clip.id)

        companion object {
            fun keyOf(id: Long): Any = "paste" to id
        }
    }

    data class Work(val status: FileWork.Status) : Bubble() {
        override val order: Long get() = status.id
        override val key: Any get() = keyOf(status.id)

        companion object {
            fun keyOf(id: Long): Any = "work" to id
        }
    }
}

/** Which bubbles the stack shows: the newest, as many as fit, the older ones folded into a count. */
internal object StackLayout {
    /** Buttons in the stack at most, the floating button among them. */
    const val MAX_BUTTONS = 4

    /** [bubbles] from top (oldest) to bottom (newest), and how many older ones are [folded] away. */
    data class Shown(val bubbles: List<Bubble>, val folded: Int)

    /** The bubbles for [clips] waiting and [operations] not finished, under the floating button when [fab]. */
    fun of(clips: List<FileClipboard.Clip>, operations: List<FileWork.Status>, fab: Boolean): Shown {
        val all = (clips.map { Bubble.Paste(it) } + operations.filter { it.outcome == null }.map { Bubble.Work(it) }).sortedBy { it.order }
        val room = MAX_BUTTONS - (if (fab) 1 else 0)
        val shown = all.takeLast(room)
        return Shown(shown, all.size - shown.size)
    }
}

/**
 * The stack on the handed side: the floating button on top, never moving, and under it a
 * bubble for each pick waiting to be pasted ([FileClipboard]) and each operation queued
 * ([FileWork]), the newest at the bottom; without the button they rise from the bottom edge.
 * A Paste bubble pastes on a tap ([onPaste]) and is let go on a long press ([onDrop]).
 * An operation's bubble is gray while it waits, and darkens with a progress ring in its
 * action's color while it runs; a tap shows its card, with Cancel, and a long press cancels it.
 */
internal class OperationStack(
    private val activity: AppCompatActivity,
    private val column: LinearLayout,
    private val fab: View,
    private val onPaste: (Long) -> Unit,
    private val onDrop: (Long) -> Unit
) {
    private var clips: List<FileClipboard.Clip> = FileClipboard.clips.value
    private var operations: List<FileWork.Status> = FileWork.operations.value
    private var fabShown = false
    private var left = false

    /** The bubbles showing, by [Bubble.key]. */
    private val views = LinkedHashMap<Any, View>()

    /** What shows now. */
    var shown = StackLayout.Shown(emptyList(), 0)
        private set

    private var card: PopupWindow? = null
    private var cardId: Long? = null
    private var hint: PopupWindow? = null

    /** The pick whose hint shows once its bubble is there. */
    private var hintFor: Long? = null

    init {
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                FileClipboard.clips.combine(FileWork.operations) { c, o -> c to o }.collect { (c, o) ->
                    clips = c
                    operations = o
                    render()
                }
            }
        }
    }

    /** Places the stack under a floating button shown, or rising from the bottom edge without it. */
    fun setFab(shown: Boolean) {
        if (shown == fabShown) return
        fabShown = shown
        render()
    }

    /** Puts the button and the stack on the left side for [left], else on the right. */
    fun setLeftHanded(left: Boolean) {
        this.left = left
        val side = if (left) Gravity.START else Gravity.END
        for (v in listOf(fab, column)) {
            val lp = v.layoutParams as CoordinatorLayout.LayoutParams
            lp.gravity = Gravity.BOTTOM or side
            v.layoutParams = lp
        }
        hideCard()
        hideHint()
    }

    /** The button of each bubble shown, from top to bottom; for tests. */
    internal fun buttons(): List<Pair<Bubble, FloatingActionButton>> =
        shown.bubbles.mapNotNull { b -> views[b.key]?.let { b to it.findViewById(R.id.op_button) } }

    /** The card's content while it shows; for tests. */
    internal fun cardView(): View? = card?.contentView

    /** Shows the hint for Paste [id] beside its bubble as soon as it is there. */
    fun hint(id: Long) {
        hintFor = id
        render()
    }

    /** Lets go of the card and hint, as the screen goes. */
    fun release() {
        hideCard()
        hideHint()
    }

    private fun render() {
        val now = StackLayout.of(clips, operations, fabShown)
        shown = now
        val lp = column.layoutParams as CoordinatorLayout.LayoutParams
        val height = if (fabShown) activity.resources.getDimensionPixelSize(R.dimen.op_stack_room) else ViewGroup.LayoutParams.WRAP_CONTENT
        if (lp.height != height) {
            lp.height = height
            column.layoutParams = lp
        }
        column.gravity = (if (fabShown) Gravity.TOP else Gravity.BOTTOM) or Gravity.CENTER_HORIZONTAL
        val keep = now.bubbles.map { it.key }.toSet()
        val gone = views.keys.filter { it !in keep }
        for (key in gone) column.removeView(views.remove(key))
        for ((i, bubble) in now.bubbles.withIndex()) {
            val view = views[bubble.key] ?: make().also { views[bubble.key] = it }
            if (column.indexOfChild(view) != i) {
                (view.parent as? ViewGroup)?.removeView(view)
                column.addView(view, i)
            }
            bind(view, bubble, if (i == 0) now.folded else 0)
        }
        updateCard()
        hintFor?.let { id -> views[Bubble.Paste.keyOf(id)]?.let { v -> hintFor = null; v.post { showHint(v) } } }
    }

    /** A new bubble, coming out from under the button above it. */
    private fun make(): View {
        val view = LayoutInflater.from(activity).inflate(R.layout.item_op_bubble, column, false)
        view.alpha = 0f
        view.translationY = -activity.resources.getDimension(R.dimen.op_bubble_gap) * 3
        view.animate().alpha(1f).translationY(0f).setDuration(APPEAR_MS).start()
        return view
    }

    private fun color(view: View, attr: Int) = MaterialColors.getColor(view, attr)

    /**
     * What a bubble's view was last bound to: its [look] (colors, icon, actions, the count
     * folded), what its description [said], and its [ring]'s progress; each is set again
     * only once it changes, so progress reports redraw just the ring.
     */
    private data class Bound(val look: Any, val said: Any?, val ring: Int?)

    private fun bind(view: View, bubble: Bubble, folded: Int) {
        val ring = view.findViewById<CircularProgressIndicator>(R.id.op_ring)
        val was = view.getTag(R.id.op_bound) as? Bound
        val status = (bubble as? Bubble.Work)?.status
        val look: Any = if (status != null) listOf(status.id, status.kind, status.waiting, folded) else bubble to folded
        if (was?.look != look) bindLook(view, bubble, folded)
        val said: Any? = status?.let { listOf(it.waiting, it.count, it.into, it.name.takeIf { _ -> it.kind == FileWork.Kind.RENAME }, percent(it)) }
        if (status != null && (was?.look != look || was.said != said)) view.findViewById<View>(R.id.op_button).contentDescription = describe(status)
        val steps = status?.takeIf { it.running }?.fraction?.let { (it * STEPS).toInt() }
        if (status != null && status.running && (was?.look != look || was.ring != steps)) show(ring, status.fraction)
        view.setTag(R.id.op_bound, Bound(look, said, steps))
    }

    /** How far [status] is, in whole percent; null while waiting or when nothing tells. */
    private fun percent(status: FileWork.Status): Int? = if (status.waiting) null else status.fraction?.let { (it * 100).toInt() }

    /** Sets [view]'s colors, icon, actions and folded count for [bubble]. */
    private fun bindLook(view: View, bubble: Bubble, folded: Int) {
        val button = view.findViewById<FloatingActionButton>(R.id.op_button)
        val ring = view.findViewById<CircularProgressIndicator>(R.id.op_ring)
        val badge = view.findViewById<ImageView>(R.id.op_badge)
        val count = view.findViewById<TextView>(R.id.op_folded)
        count.visibility = if (folded > 0) View.VISIBLE else View.GONE
        count.text = activity.getString(R.string.op_folded_badge, folded)
        count.contentDescription = activity.getString(R.string.op_folded, folded)
        when (bubble) {
            is Bubble.Paste -> {
                val clip = bubble.clip
                paint(button, FabLook.PASTE.icon, color(button, FabLook.PASTE.background), color(button, FabLook.PASTE.foreground))
                val kind = FabLook.pasteBadge(clip.move)
                badge.setImageResource(kind.icon)
                badge.backgroundTintList = ColorStateList.valueOf(color(badge, kind.background))
                badge.imageTintList = ColorStateList.valueOf(color(badge, kind.foreground))
                badge.visibility = View.VISIBLE
                ring.visibility = View.INVISIBLE
                button.contentDescription = if (clip.count == 1) activity.getString(R.string.paste_named, clip.name)
                else activity.resources.getQuantityString(R.plurals.paste_count, clip.count, clip.count)
                button.setOnClickListener {
                    hideHint()
                    onPaste(clip.id)
                }
                button.setOnLongClickListener {
                    hideHint()
                    onDrop(clip.id)
                    true
                }
            }
            is Bubble.Work -> {
                val status = bubble.status
                val look = FabLook.of(status.kind)
                badge.visibility = View.GONE
                if (status.waiting) {
                    paint(button, look.icon, color(button, FabLook.WAITING_BACKGROUND), color(button, FabLook.WAITING_FOREGROUND))
                    ring.visibility = View.INVISIBLE
                } else {
                    paint(button, look.icon, ColorUtils.blendARGB(color(button, look.background), Color.BLACK, DARKEN), color(button, look.foreground))
                    ring.setIndicatorColor(color(ring, FabLook.ringOf(status.kind)))
                }
                val id = status.id
                button.setOnClickListener { showCard(id, view) }
                button.setOnLongClickListener {
                    hideCard()
                    FileWork.cancel(id)
                    true
                }
            }
        }
    }

    private fun paint(button: FloatingActionButton, icon: Int, background: Int, foreground: Int) {
        button.setImageResource(icon)
        button.backgroundTintList = ColorStateList.valueOf(background)
        button.imageTintList = ColorStateList.valueOf(foreground)
    }

    /** Shows [fraction] on [ring], spinning while nothing tells how far. */
    private fun show(ring: CircularProgressIndicator, fraction: Float?) {
        val spin = fraction == null
        if (ring.isIndeterminate != spin || ring.visibility != View.VISIBLE) {
            // Switching modes only while hidden: the ring draws it without a jump.
            ring.visibility = View.INVISIBLE
            ring.isIndeterminate = spin
            ring.max = STEPS
            if (fraction != null) ring.setProgressCompat((fraction * STEPS).toInt(), false)
            ring.visibility = View.VISIBLE
        } else if (fraction != null) {
            ring.setProgressCompat((fraction * STEPS).toInt(), true)
        }
    }

    /** What [status] does: "Copying 3 items into Comics". */
    private fun what(status: FileWork.Status): String {
        val res = activity.resources
        val count = status.count
        val doing = when (status.kind) {
            FileWork.Kind.COPY -> res.getQuantityString(R.plurals.op_copying, count, count)
            FileWork.Kind.MOVE -> res.getQuantityString(R.plurals.op_moving, count, count)
            FileWork.Kind.DELETE -> res.getQuantityString(R.plurals.op_deleting, count, count)
            FileWork.Kind.RENAME -> activity.getString(R.string.op_renaming, status.name)
        }
        return status.into?.let { activity.getString(R.string.op_into, doing, it) } ?: doing
    }

    private fun describe(status: FileWork.Status): String {
        val what = what(status)
        if (status.waiting) return activity.getString(R.string.op_described, what, activity.getString(R.string.op_waiting))
        val percent = percent(status) ?: return what
        return activity.getString(R.string.op_described, what, activity.getString(R.string.op_percent, percent))
    }

    // ------------------------------------------------------------------ card

    /** Shows the card of operation [id] beside its bubble [anchor]: what it does, how far, and Cancel. */
    private fun showCard(id: Long, anchor: View) {
        hideHint()
        if (cardId == id && card?.isShowing == true) return hideCard()
        hideCard()
        val content = LayoutInflater.from(activity).inflate(R.layout.card_operation, column.rootView as ViewGroup, false)
        // A popup takes no width from its content's layout: the card's own, given.
        val popup = PopupWindow(content, activity.resources.getDimensionPixelSize(R.dimen.op_card_width), ViewGroup.LayoutParams.WRAP_CONTENT)
        popup.isOutsideTouchable = true
        popup.setOnDismissListener {
            if (card === popup) {
                card = null
                cardId = null
            }
        }
        content.findViewById<Button>(R.id.op_cancel).setOnClickListener {
            it.isEnabled = false
            FileWork.cancel(id)
        }
        card = popup
        cardId = id
        fillCard()
        place(popup, content, anchor)
    }

    private fun updateCard() {
        val id = cardId ?: return
        val status = operations.firstOrNull { it.id == id }
        if (status == null || status.outcome != null || views[Bubble.Work.keyOf(status.id)] == null) return hideCard()
        fillCard()
    }

    private fun fillCard() {
        val content = card?.contentView ?: return
        val status = operations.firstOrNull { it.id == cardId } ?: return
        content.findViewById<TextView>(R.id.op_title).text = what(status)
        val item = content.findViewById<TextView>(R.id.op_item)
        item.text = when {
            status.waiting -> activity.getString(R.string.op_waiting)
            status.count > 1 -> activity.getString(R.string.op_item_named, activity.getString(R.string.op_item_of, status.index + 1, status.count), status.name)
            else -> status.name
        }
        val bar = content.findViewById<LinearProgressIndicator>(R.id.op_bar)
        val fraction = status.fraction
        if (status.waiting) {
            bar.visibility = View.GONE
        } else if (bar.isIndeterminate != (fraction == null) || bar.visibility != View.VISIBLE) {
            bar.visibility = View.INVISIBLE
            bar.isIndeterminate = fraction == null
            bar.max = STEPS
            if (fraction != null) bar.setProgressCompat((fraction * STEPS).toInt(), false)
            bar.visibility = View.VISIBLE
        } else if (fraction != null) {
            bar.setProgressCompat((fraction * STEPS).toInt(), true)
        }
        val amount = content.findViewById<TextView>(R.id.op_amount)
        if (status.running && status.total > 0) {
            amount.text = activity.getString(
                R.string.file_amount,
                FileUtils.formatSize(activity, status.done),
                FileUtils.formatSize(activity, status.total)
            )
            amount.visibility = View.VISIBLE
        } else {
            amount.visibility = View.GONE
        }
        // Cancel only while it can stop the step under way.
        content.findViewById<Button>(R.id.op_cancel).visibility = if (status.waiting || status.stoppable) View.VISIBLE else View.GONE
    }

    private fun hideCard() {
        val popup = card
        card = null
        cardId = null
        popup?.dismiss()
    }

    // ------------------------------------------------------------------ hint

    /** A short hint beside a Paste bubble: tap pastes, a long press cancels. Goes by itself. */
    private fun showHint(anchor: View) {
        hideHint()
        if (!anchor.isAttachedToWindow) return
        val text = TextView(activity).apply {
            id = R.id.paste_bubble
            setText(R.string.paste_hint)
            setBackgroundResource(R.drawable.bg_bubble)
            setTextColor(MaterialColors.getColor(anchor, com.google.android.material.R.attr.colorOnSurfaceInverse))
            val pad = resources.getDimensionPixelSize(R.dimen.space_3)
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        val popup = PopupWindow(text, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        popup.isOutsideTouchable = true
        text.setOnClickListener { hideHint() }
        hint = popup
        place(popup, text, anchor)
        anchor.postDelayed({ if (hint === popup) hideHint() }, HINT_MS)
    }

    private fun hideHint() {
        val popup = hint
        hint = null
        popup?.dismiss()
    }

    /** Shows [popup] holding [content] beside [anchor], toward the middle of the screen. */
    private fun place(popup: PopupWindow, content: View, anchor: View) {
        if (!anchor.isAttachedToWindow) return
        val width = if (popup.width > 0) View.MeasureSpec.makeMeasureSpec(popup.width, View.MeasureSpec.EXACTLY) else View.MeasureSpec.UNSPECIFIED
        content.measure(width, View.MeasureSpec.UNSPECIFIED)
        val at = IntArray(2)
        anchor.getLocationInWindow(at)
        val gap = activity.resources.getDimensionPixelSize(R.dimen.space_2)
        val x = if (left) at[0] + anchor.width + gap else at[0] - content.measuredWidth - gap
        val y = at[1] + anchor.height / 2 - content.measuredHeight / 2
        popup.showAtLocation(column, Gravity.NO_GRAVITY, x.coerceAtLeast(0), y.coerceAtLeast(0))
    }

    private companion object {
        const val STEPS = 1000
        const val APPEAR_MS = 180L

        /** How much a running bubble's fill darkens toward black. */
        const val DARKEN = 0.35f

        /** How long the hint beside Paste stays. */
        const val HINT_MS = 3_500L
    }
}
