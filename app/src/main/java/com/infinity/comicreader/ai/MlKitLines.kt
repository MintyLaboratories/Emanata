package com.infinity.comicreader.ai

import com.infinity.comicreader.data.SmartCrop
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** A point in image pixels. */
data class Corner(val x: Double, val y: Double)

/**
 * A line of text ML Kit found, without what it says: its [corners] in order around it, from
 * whichever corner (null when not given), its axis-aligned bounds [left]..[bottom] in the image's
 * pixels ([right] and [bottom] exclusive) and ML Kit's [confidence].
 */
data class FoundLine(
    val corners: List<Corner>?,
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
    val confidence: Float
)

/**
 * ML Kit's lines as the Smart crop's text boxes ([SmartCrop.TextBox]). Free of Android types.
 */
object MlKitLines {
    /** The text boxes of [lines] found on a [w] x [h] image ([box]), lines that miss it left out. */
    fun boxes(lines: List<FoundLine>, w: Int, h: Int): List<SmartCrop.TextBox> = lines.mapNotNull { box(it, w, h) }

    /**
     * [line] as a text box on a [w] x [h] image: the box around its four corners (its bounds
     * without them), clamped to the image; its thickness the mean of its two shorter opposite
     * sides (a line runs along its longer ones, whichever corner its corners start from), so a
     * rotated line's letters keep their size; its score ML Kit's confidence, within 0..1.
     * Null when the line has no area inside the image or no thickness.
     */
    fun box(line: FoundLine, w: Int, h: Int): SmartCrop.TextBox? {
        val c = line.corners?.takeIf { it.size == 4 && it.all { p -> p.x.isFinite() && p.y.isFinite() } }
        val left = c?.minOf { it.x } ?: line.left
        val top = c?.minOf { it.y } ?: line.top
        val right = c?.maxOf { it.x } ?: line.right
        val bottom = c?.maxOf { it.y } ?: line.bottom
        if (!(left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite())) return null
        val thickness = c?.let { min(side(it[0], it[3]) + side(it[1], it[2]), side(it[0], it[1]) + side(it[3], it[2])) / 2 }
        if (thickness != null && !(thickness > 0)) return null
        val l = max(0.0, left)
        val t = max(0.0, top)
        val r = min(w.toDouble(), right)
        val b = min(h.toDouble(), bottom)
        if (r <= l || b <= t) return null
        val score = if (line.confidence.isNaN()) 0f else line.confidence.coerceIn(0f, 1f)
        return SmartCrop.TextBox(l, t, r, b, score, thickness)
    }

    private fun side(a: Corner, b: Corner): Double = hypot(b.x - a.x, b.y - a.y)
}
