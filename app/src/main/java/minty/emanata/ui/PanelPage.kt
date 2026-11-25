package minty.emanata.ui

import kotlin.math.floor
import kotlin.math.pow

/**
 * A comic page laid out as panels between straight gutters, on a unit square (x right, y
 * down): each gutter is a line whose angle is locked ([Gutter.slope]) and whose place moves
 * by an offset; each panel is the quad between four edges, each the page's frame (inset by
 * [margin]) or one side of a gutter (half a gutter, [halfGutter], from its line). Guides that
 * move from panel to panel use the same model.
 */
class PanelPage(
    val gutters: List<Gutter>,
    val panels: List<Panel>,
    val margin: Double,
    val halfGutter: Double
) {
    /**
     * A gutter line: [vertical] ones run x = base + slope·(y − ½), horizontal ones
     * y = base + slope·(x − ½), so [base] is where the line crosses the page's middle; [amp]
     * is how far it moves when it moves (positive: right or down).
     */
    data class Gutter(val vertical: Boolean, val base: Double, val slope: Double, val amp: Double)

    /** One edge of a panel: a side of the frame, or the side of gutter [index] toward [side] (−1: up or left, +1: down or right). */
    sealed class Edge {
        enum class Frame { TOP, RIGHT, BOTTOM, LEFT }

        data class Side(val frame: Frame) : Edge()
        data class Of(val index: Int, val side: Int) : Edge()
    }

    /** A panel from its [top], [right], [bottom] and [left] edges. */
    data class Panel(val top: Edge, val right: Edge, val bottom: Edge, val left: Edge)

    /** A point on the page. */
    data class Point(val x: Double, val y: Double)

    /** A line on the page: y = r + t·x when horizontal, else x = r + t·y. */
    private data class Line(val horizontal: Boolean, val r: Double, val t: Double)

    init {
        for (p in panels) {
            require(horizontal(p.top) && horizontal(p.bottom) && !horizontal(p.left) && !horizontal(p.right)) { "A panel's top and bottom must lie across, its sides upright" }
        }
    }

    private fun horizontal(e: Edge): Boolean = when (e) {
        is Edge.Side -> e.frame == Edge.Frame.TOP || e.frame == Edge.Frame.BOTTOM
        is Edge.Of -> !gutters[e.index].vertical
    }

    private fun line(e: Edge, offsets: DoubleArray): Line = when (e) {
        is Edge.Side -> when (e.frame) {
            Edge.Frame.TOP -> Line(true, margin, 0.0)
            Edge.Frame.BOTTOM -> Line(true, 1 - margin, 0.0)
            Edge.Frame.LEFT -> Line(false, margin, 0.0)
            Edge.Frame.RIGHT -> Line(false, 1 - margin, 0.0)
        }
        is Edge.Of -> {
            val g = gutters[e.index]
            val at = g.base + (offsets.getOrNull(e.index) ?: 0.0)
            Line(!g.vertical, at - g.slope * 0.5 + e.side * halfGutter, g.slope)
        }
    }

    /** Where a horizontal and an upright line cross. */
    private fun cross(a: Line, b: Line): Point {
        val h = if (a.horizontal) a else b
        val v = if (a.horizontal) b else a
        val x = (v.r + v.t * h.r) / (1 - v.t * h.t)
        return Point(x, h.r + h.t * x)
    }

    /** Each panel's corners (top left, top right, bottom right, bottom left) with each gutter moved by its [offsets]. */
    fun quads(offsets: DoubleArray = DoubleArray(gutters.size)): List<List<Point>> = panels.map { p ->
        val top = line(p.top, offsets)
        val right = line(p.right, offsets)
        val bottom = line(p.bottom, offsets)
        val left = line(p.left, offsets)
        listOf(cross(left, top), cross(top, right), cross(right, bottom), cross(bottom, left))
    }

    /** Each panel's middle at rest: the mean of its corners. */
    fun centers(): List<Point> = quads().map { q -> Point(q.sumOf { it.x } / q.size, q.sumOf { it.y } / q.size) }

    /**
     * The box each panel covers at any time while one gutter at a time moves between rest
     * and its [Gutter.amp] (each corner moves straight between the two), as
     * [left, top, right, bottom].
     */
    fun reach(): List<DoubleArray> {
        val poses = listOf(DoubleArray(gutters.size)) + gutters.indices.map { i -> DoubleArray(gutters.size).also { it[i] = gutters[i].amp } }
        val all = poses.map { quads(it) }
        return panels.indices.map { i ->
            val pts = all.flatMap { it[i] }
            doubleArrayOf(pts.minOf { it.x }, pts.minOf { it.y }, pts.maxOf { it.x }, pts.maxOf { it.y })
        }
    }

    companion object {
        /**
         * The calibration screen's page: one near-upright gutter splits it, three panels on
         * the left between two near-level gutters and two on the right across one.
         */
        val TUNER = PanelPage(
            gutters = listOf(
                Gutter(vertical = true, base = 0.505, slope = 0.12, amp = 0.07),
                Gutter(vertical = false, base = 0.33, slope = 0.09, amp = 0.06),
                Gutter(vertical = false, base = 0.66, slope = -0.07, amp = -0.06),
                Gutter(vertical = false, base = 0.47, slope = -0.11, amp = 0.08)
            ),
            panels = listOf(
                Panel(Edge.Side(Edge.Frame.TOP), Edge.Of(0, -1), Edge.Of(1, -1), Edge.Side(Edge.Frame.LEFT)),
                Panel(Edge.Of(1, 1), Edge.Of(0, -1), Edge.Of(2, -1), Edge.Side(Edge.Frame.LEFT)),
                Panel(Edge.Of(2, 1), Edge.Of(0, -1), Edge.Side(Edge.Frame.BOTTOM), Edge.Side(Edge.Frame.LEFT)),
                Panel(Edge.Side(Edge.Frame.TOP), Edge.Side(Edge.Frame.RIGHT), Edge.Of(3, -1), Edge.Of(0, 1)),
                Panel(Edge.Of(3, 1), Edge.Side(Edge.Frame.RIGHT), Edge.Side(Edge.Frame.BOTTOM), Edge.Of(0, 1))
            ),
            margin = 0.035,
            halfGutter = 0.016
        )
    }
}

/**
 * Gutters moving one at a time, each in a slot of [slotS] seconds: out to its amp over 40 %
 * of it, held 15 %, back over 40 %, at rest for the last 5 %, eased in and out (cubic).
 */
class GutterMotion(private val count: Int, private val slotS: Double = SLOT_S) {
    /** Each gutter's offset as a share of its amp, [t] seconds in. */
    fun shares(t: Double): DoubleArray {
        val out = DoubleArray(count)
        if (count == 0 || t < 0) return out
        val slot = (floor(t / slotS).toLong() % count).toInt()
        out[slot] = shape((t % slotS) / slotS)
        return out
    }

    /** Each gutter of [page]'s offset [t] seconds in. */
    fun offsets(page: PanelPage, t: Double): DoubleArray {
        val s = shares(t)
        return DoubleArray(page.gutters.size) { s.getOrElse(it) { 0.0 } * page.gutters[it].amp }
    }

    companion object {
        /** A gutter every 4 s. */
        const val SLOT_S = 4.0

        /** How far out a gutter is [u] of the way through its slot, from 0 to 1. */
        fun shape(u: Double): Double = when {
            u < 0.4 -> ease(u / 0.4)
            u < 0.55 -> 1.0
            u < 0.95 -> 1 - ease((u - 0.55) / 0.4)
            else -> 0.0
        }

        /** Cubic ease in and out on 0..1. */
        fun ease(x: Double): Double = if (x < 0.5) 4 * x * x * x else 1 - (-2 * x + 2).pow(3) / 2
    }
}
