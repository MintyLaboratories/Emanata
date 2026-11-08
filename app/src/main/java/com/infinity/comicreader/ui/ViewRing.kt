package com.infinity.comicreader.ui

import com.infinity.comicreader.data.DetailDisplay
import com.infinity.comicreader.data.ListDensity
import com.infinity.comicreader.data.RingOption
import com.infinity.comicreader.data.RingOptions
import com.infinity.comicreader.data.TabView
import com.infinity.comicreader.data.TitleDisplay
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** What the view ring offers per tab, and what each option changes; free of Android types. */
internal object ViewRing {

    /** [tab]'s options, from the top of the ring inward: those [chosen] that apply to it. */
    fun options(tab: String, chosen: RingOptions = RingOptions.DEFAULT): List<RingOption> = chosen.forTab(tab)

    /** What the grid/list button's tap runs in [tab]: [chosen], or Layout where that does not apply. */
    fun tapAction(tab: String, chosen: RingOption): RingOption = if (chosen.appliesTo(tab)) chosen else RingOption.LAYOUT

    /** [view] with larger covers or rows: one column fewer than the [columns] shown (and one row, when set), or the next density up; null at the end. */
    fun larger(view: TabView, columns: Int): TabView? = if (view.grid) grid(view, columns, -1) else density(view, +1)

    /** [view] with smaller covers or rows: one column more than the [columns] shown (and one row, when set), or the next density down; null at the end. */
    fun smaller(view: TabView, columns: Int): TabView? = if (view.grid) grid(view, columns, +1) else density(view, -1)

    /** Columns and set rows stepped together, keeping the grid's shape; null once either would leave 1..[TabView.MAX_CELLS]. */
    private fun grid(view: TabView, columns: Int, step: Int): TabView? {
        val cells = 1..TabView.MAX_CELLS
        val c = columns + step
        val r = if (view.rows > 0) view.rows + step else 0
        if (c !in cells || (view.rows > 0 && r !in cells)) return null
        return view.copy(columns = c, rows = r)
    }

    private fun density(view: TabView, step: Int): TabView? {
        val d = ListDensity.ofStep(view.density.step + step)
        return if (d == view.density) null else view.copy(density = d)
    }

    /** The detail display after [detail], round and round. */
    fun nextDetail(detail: DetailDisplay): DetailDisplay = DetailDisplay.entries[(detail.ordinal + 1) % DetailDisplay.entries.size]

    /** The title display after [title], round and round. */
    fun nextTitle(title: TitleDisplay): TitleDisplay = TitleDisplay.entries[(title.ordinal + 1) % TitleDisplay.entries.size]

    /**
     * [current] and [next] with the words they share said once: leading words kept on the
     * current side only ("Dir. LTR", "RTL"), else trailing words on the next side only ("4",
     * "5 columns"); as they are when they share none, or are the same.
     */
    fun compact(current: String, next: String): Pair<String, String> {
        val a = current.split(' ')
        val b = next.split(' ')
        if (a == b) return current to next
        val most = min(a.size, b.size) - 1
        val lead = (0 until most).takeWhile { a[it] == b[it] }.count()
        if (lead > 0) return current to b.drop(lead).joinToString(" ")
        val trail = (0 until most).takeWhile { a[a.size - 1 - it] == b[b.size - 1 - it] }.count()
        if (trail > 0) return a.dropLast(trail).joinToString(" ") to next
        return current to next
    }
}

/** A ring label's value: [current], and what a tap makes it, [next]; null for an option at its end, which shows only [current]. */
internal data class RingValue(val current: String, val next: String?)

/**
 * A ring label's value forms, fullest first: [currents] and [nexts] pair up form by form
 * (the shorter list's last repeating), then the shortest pair said once more with shared
 * words dropped ([ViewRing.compact]); repeats are dropped. With [nexts] null, the currents alone.
 */
internal fun ringForms(currents: List<String>, nexts: List<String>?): List<RingValue> {
    if (nexts == null) return currents.distinct().map { RingValue(it, null) }
    val n = max(currents.size, nexts.size)
    val pairs = List(n) { RingValue(currents[min(it, currents.lastIndex)], nexts[min(it, nexts.lastIndex)]) }
    val last = pairs.last()
    val (c, x) = ViewRing.compact(last.current, last.next!!)
    return (pairs + RingValue(c, x)).distinct()
}

/**
 * Where the view ring's buttons go: on a quarter arc around the hub (the floating
 * button's place), from straight up to straight toward the screen's inside. Angles
 * are in degrees, 90 straight up, 180 straight left; y grows downward.
 */
internal object RingGeometry {
    const val SWEEP = 90.0

    /** The angle of each of [count] buttons, from straight up toward the left ([left]) or the right. */
    fun angles(count: Int, left: Boolean): List<Double> {
        val side = if (left) 1 else -1
        if (count == 1) return listOf(90.0 + side * SWEEP / 2)
        val step = SWEEP / (count - 1)
        return List(count) { 90.0 + side * step * it }
    }

    /** The radius at which [count] buttons sit [spacing] apart center to center over the sweep, never below [min]. */
    fun radius(count: Int, spacing: Float, min: Float): Float {
        if (count < 2) return min
        val half = Math.toRadians(SWEEP / (count - 1) / 2)
        return max(min, (spacing / (2 * sin(half))).toFloat())
    }

    /** The offset from the hub's center of a button at [angle] on a ring of [radius]. */
    fun spot(angle: Double, radius: Float): Pair<Float, Float> {
        val a = Math.toRadians(angle)
        return (radius * cos(a)).toFloat() to (-radius * sin(a)).toFloat()
    }

    /**
     * The center of a label [width] x [height] beside a button at [angle], outward from the
     * hub: its side or corner facing the button lies [reach] from the button's center.
     */
    fun labelCenter(angle: Double, reach: Float, width: Float, height: Float): Pair<Float, Float> {
        val a = Math.toRadians(angle)
        val ux = cos(a).toFloat()
        val uy = -sin(a).toFloat()
        val k = max(abs(ux), abs(uy))
        return (ux * reach + ux / k * width / 2) to (uy * reach + uy / k * height / 2)
    }

    /** An axis-aligned rectangle; y grows downward. */
    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width get() = right - left
        val height get() = bottom - top

        /** The area this shares with [other] grown by [pad] on every side. */
        fun overlap(other: Box, pad: Float = 0f): Float {
            val w = min(right, other.right + pad) - max(left, other.left - pad)
            val h = min(bottom, other.bottom + pad) - max(top, other.top - pad)
            return if (w > 0 && h > 0) w * h else 0f
        }

        /** How deep a round button of [radius] at [x], [y], grown by [pad], reaches into this; 0 when clear. */
        fun overlap(x: Float, y: Float, radius: Float, pad: Float = 0f): Float {
            val dx = x - x.coerceIn(left, right)
            val dy = y - y.coerceIn(top, bottom)
            val d = sqrt(dx * dx + dy * dy)
            val inside = x in left..right && y in top..bottom
            return if (inside) radius + pad + min(min(x - left, right - x), min(y - top, bottom - y)) else max(0f, radius + pad - d)
        }

        fun within(b: Box) = left >= b.left - EPS && top >= b.top - EPS && right <= b.right + EPS && bottom <= b.bottom + EPS

        /** Moved, not resized, to lie within [b] as far as it fits, its left and top first. */
        fun movedInto(b: Box): Box {
            val x = left.coerceAtMost(b.right - width).coerceAtLeast(b.left)
            val y = top.coerceAtMost(b.bottom - height).coerceAtLeast(b.top)
            return Box(x, y, x + width, y + height)
        }

        companion object {
            fun around(x: Float, y: Float, width: Float, height: Float) = Box(x - width / 2, y - height / 2, x + width / 2, y + height / 2)
        }
    }

    /**
     * Labels laid out on a ring of [radius]: their [boxes], how much they [overlap] the rest,
     * how many sit [aside] rather than outward, and which of them overlap another label or a
     * round button ([clashing]).
     */
    class Placement(val radius: Float, val boxes: List<Box>, val overlap: Float, val aside: Int, val clashing: Set<Int> = emptySet())

    /**
     * The largest radius at which a [width] x [height] box, centered ([ox], [oy]) off the spot
     * at [angle] on the ring, stays within [bounds] around a hub at [cx], [cy]. Only the sides
     * the radius moves toward count.
     */
    fun room(angle: Double, cx: Float, cy: Float, bounds: Box, ox: Float, oy: Float, width: Float, height: Float): Float {
        val a = Math.toRadians(angle)
        val ux = cos(a)
        val uy = -sin(a)
        var r = Double.POSITIVE_INFINITY
        if (ux < -AXIS) r = min(r, (cx + ox - width / 2 - bounds.left) / -ux)
        if (ux > AXIS) r = min(r, (bounds.right - cx - ox - width / 2) / ux)
        if (uy < -AXIS) r = min(r, (cy + oy - height / 2 - bounds.top) / -uy)
        if (uy > AXIS) r = min(r, (bounds.bottom - cy - oy - height / 2) / uy)
        return r.toFloat()
    }

    /**
     * The ring for [button]-wide buttons at [angles] around a hub at [cx], [cy], their labels
     * ([labels], width to height) [reach] off each button's center: the largest radius up to
     * [want] that keeps the buttons within [bounds] and, down to buttons [spacing] apart center
     * to center, the labels outward, clear of each other and of every button ([pad] apart).
     * Where no radius does and [grow] is above 1, the smallest radius up to [grow] times
     * [want] that keeps the labels clear, outward where one does. Else the one whose labels
     * overlap least, the fewest of them aside ([place]).
     */
    fun arrange(
        angles: List<Double>, cx: Float, cy: Float, bounds: Box, button: Float, want: Float,
        spacing: Float, reach: Float, pad: Float, labels: List<Pair<Float, Float>>, grow: Float = 1f
    ): Placement {
        val buttons = angles.minOfOrNull { room(it, cx, cy, bounds, 0f, 0f, button, button) } ?: want
        val most = min(want, max(buttons, button))
        val least = min(max(radius(angles.size, spacing, 0f), spacing), most)
        fun better(p: Placement, than: Placement) = p.overlap < than.overlap || (p.overlap == than.overlap && p.aside < than.aside)
        var best = place(angles, cx, cy, bounds, button, most, reach, pad, labels)
        if (best.overlap == 0f && best.aside == 0) return best
        for (s in 1..STEPS) {
            val p = place(angles, cx, cy, bounds, button, most - (most - least) * s / STEPS, reach, pad, labels)
            if (better(p, best)) best = p
            if (best.overlap == 0f && best.aside == 0) break
        }
        val top = min(want * grow, max(buttons, button))
        if (best.overlap == 0f || top <= most) return best
        var clear: Placement? = null
        for (s in 1..STEPS) {
            val p = place(angles, cx, cy, bounds, button, most + (top - most) * s / STEPS, reach, pad, labels)
            if (p.overlap > 0f) {
                if (clear == null && better(p, best)) best = p
                continue
            }
            if (clear == null || p.aside < clear.aside) clear = p
            if (p.aside == 0) break
        }
        return clear ?: best
    }

    /**
     * Where each label goes beside its button on a ring of [radius] (as in [arrange]), in
     * order: outward when that lies within [bounds] clear of the hub, the buttons and the
     * labels before it ([pad] apart; its own button only untouched), slid along that side up
     * to half its size where need be ([SLIDES]); else the nearest side that does; else, moved
     * within [bounds], the side where it overlaps least.
     */
    fun place(
        angles: List<Double>, cx: Float, cy: Float, bounds: Box, button: Float, radius: Float,
        reach: Float, pad: Float, labels: List<Pair<Float, Float>>
    ): Placement {
        val centers = angles.map { spot(it, radius).let { (dx, dy) -> (cx + dx) to (cy + dy) } }
        val round = listOf(cx to cy) + centers
        val taken = ArrayList<Box>()
        val boxes = ArrayList<Box>()
        var overlap = 0f
        var aside = 0
        for ((i, angle) in angles.withIndex()) {
            val (w, h) = labels[i]
            val (bx, by) = centers[i]
            var pick: Box? = null
            var least = Float.MAX_VALUE
            var out = true
            search@ for ((k, side) in sides(angle).withIndex()) {
                val (ox, oy) = labelCenter(side, reach, w, h)
                // Beside the button to its left or right, a label may slide up or down; above or below it, sideways.
                val beside = abs(cos(Math.toRadians(side))) >= abs(sin(Math.toRadians(side)))
                for (slide in SLIDES) {
                    val sx = if (beside) 0f else slide * w
                    val sy = if (beside) slide * h else 0f
                    val box = Box.around(bx + ox + sx, by + oy + sy, w, h).let { if (it.within(bounds)) it else it.movedInto(bounds) }
                    val o = taken.sumOf { box.overlap(it, pad).toDouble() }.toFloat() +
                        round.withIndex().sumOf { (j, c) -> box.overlap(c.first, c.second, button / 2, if (j == i + 1) 0f else pad).toDouble() }.toFloat()
                    if (o < least) {
                        pick = box
                        least = o
                        out = k == 0
                    }
                    if (o == 0f) break@search
                }
            }
            boxes += pick!!
            taken += pick
            overlap += least
            if (!out) aside++
        }
        val clashing = HashSet<Int>()
        for ((i, box) in boxes.withIndex()) {
            for (j in i + 1 until boxes.size) if (box.overlap(boxes[j], pad) > 0f) clashing += listOf(i, j)
            if (round.withIndex().any { (j, c) -> box.overlap(c.first, c.second, button / 2, if (j == i + 1) 0f else pad) > 0f }) clashing += i
        }
        return Placement(radius, boxes, overlap, aside, clashing)
    }

    /**
     * Picks each label's form: [forms] holds each label's sizes (width to height), fullest
     * first. All start fullest; while the labels laid out ([lay]) overlap, each one that
     * clashes takes its next shorter form, or, with [all], once those are at their shortest,
     * each other one, leaving them room. The first layout clear of overlap wins; else the
     * one that overlaps least, the fewest labels aside. Returns it with the form indexes.
     */
    fun fit(forms: List<List<Pair<Float, Float>>>, all: Boolean = false, lay: (List<Pair<Float, Float>>) -> Placement): Pair<Placement, List<Int>> {
        val pick = IntArray(forms.size)
        var best: Placement? = null
        var bestPick = pick.toList()
        while (true) {
            val p = lay(forms.indices.map { forms[it][pick[it]] })
            val b = best
            if (b == null || p.overlap < b.overlap || (p.overlap == b.overlap && p.aside < b.aside)) {
                best = p
                bestPick = pick.toList()
            }
            if (p.overlap == 0f) break
            val shorter = p.clashing.filter { pick[it] < forms[it].lastIndex }
                .ifEmpty { if (all) forms.indices.filter { pick[it] < forms[it].lastIndex } else emptyList() }
            if (shorter.isEmpty()) break
            for (i in shorter) pick[i]++
        }
        return best!! to bestPick
    }

    /** The sides a label of a button at [angle] may take: outward first, then the nearest ones, inward last. */
    fun sides(angle: Double): List<Double> {
        fun norm(a: Double) = ((a % 360) + 360) % 360
        fun apart(a: Double) = abs(norm(a - angle + 180) - 180)
        val out = norm(angle)
        return listOf(out) + listOf(0.0, 90.0, 180.0, 270.0, norm(angle + 180)).filter { apart(it) > AXIS }.distinct().sortedBy { apart(it) }
    }

    /** The index of the angle in [angles] nearest, around the circle, to the direction [dx], [dy] from the hub; ties go to the first. */
    fun nearest(angles: List<Double>, dx: Float, dy: Float): Int {
        val at = Math.toDegrees(atan2(-dy.toDouble(), dx.toDouble()))
        fun apart(a: Double) = abs(((a - at) % 360 + 540) % 360 - 180)
        return angles.indices.minByOrNull { apart(angles[it]) } ?: -1
    }

    private const val EPS = 0.01f
    private const val AXIS = 1e-6
    private const val STEPS = 32

    /** How far a label may slide along its side, as shares of its own size, nearest first. */
    private val SLIDES = floatArrayOf(0f, -0.25f, 0.25f, -0.5f, 0.5f)
}

/**
 * A finger still down after the long press that opened the ring, given as offsets from the
 * hub's center (y grows downward), starting at [x0], [y0]. It stays a tap (the ring stays
 * open on lifting) while it keeps within [slop] of that start or within the hub's [hub]
 * radius; once past both it drags for good, and then picks the button at [angles] it points
 * at, at any distance beyond the hub, if [enabled]; back on the hub it picks none.
 */
internal class RingGesture(
    private val angles: List<Double>,
    private val hub: Float,
    private val slop: Float,
    private val x0: Float,
    private val y0: Float
) {
    var dragging = false
        private set

    /** The index of the picked button, or -1. */
    var picked = -1
        private set

    /** Follows the finger to [dx], [dy]; true when [picked] changed. */
    fun move(dx: Float, dy: Float, enabled: (Int) -> Boolean): Boolean {
        val out = hypot(dx, dy) > hub
        if (!dragging && (!out || hypot(dx - x0, dy - y0) <= slop)) return false
        dragging = true
        val p = if (!out) -1 else RingGeometry.nearest(angles, dx, dy).takeIf { it >= 0 && enabled(it) } ?: -1
        if (p == picked) return false
        picked = p
        return true
    }
}
