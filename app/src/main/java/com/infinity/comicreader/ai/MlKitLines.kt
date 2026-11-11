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
    /** The version of how lines become boxes, part of ML Kit's detector id: raise it with any change here. */
    const val VERSION = 2

    /**
     * ML Kit's line boxes hug their letters; PP-OCR's, grown ([SmartCrop.UNCLIP]), are about this
     * many times as tall. Each box grows to it, by the same room on every side, so a crop keeps
     * as much room around a title as with PP-OCR. Its letter size ([SmartCrop.TextBox.glyph]),
     * which decides what is title-sized ([SmartCrop.MIN_GLYPH]), stays the line's own letter
     * height, not grown: PP-OCR's is its grown box, so ML Kit needs taller letters to reach it.
     */
    const val GROW = 1.25

    /** The text boxes of [lines] found on a [w] x [h] image ([box]), lines that miss it left out. */
    fun boxes(lines: List<FoundLine>, w: Int, h: Int): List<SmartCrop.TextBox> = lines.mapNotNull { box(it, w, h) }

    /**
     * [line] as a text box on a [w] x [h] image: the box around its four corners (its bounds
     * without them), grown by (GROW - 1) / 2 times its thickness on every side (to [GROW] times
     * its height when upright) and clamped to the image; its thickness
     * the mean of its two shorter opposite sides (a line runs along its longer ones, whichever
     * corner its corners start from), so a rotated line's letters keep their size, else (no
     * corners) the shorter side of its bounds within the image; its score ML Kit's confidence,
     * within 0..1. Null when the line has no area inside the image or no thickness.
     */
    fun box(line: FoundLine, w: Int, h: Int): SmartCrop.TextBox? {
        val c = line.corners?.takeIf { it.size == 4 && it.all { p -> p.x.isFinite() && p.y.isFinite() } }
        val left = c?.minOf { it.x } ?: line.left
        val top = c?.minOf { it.y } ?: line.top
        val right = c?.maxOf { it.x } ?: line.right
        val bottom = c?.maxOf { it.y } ?: line.bottom
        if (!(left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite())) return null
        val corners = c?.let { min(side(it[0], it[3]) + side(it[1], it[2]), side(it[0], it[1]) + side(it[3], it[2])) / 2 }
        if (corners != null && !(corners > 0)) return null
        val r = min(w.toDouble(), right)
        val b = min(h.toDouble(), bottom)
        val l = max(0.0, left)
        val t = max(0.0, top)
        if (r <= l || b <= t) return null
        val thickness = corners ?: min(r - l, b - t)
        val pad = (GROW - 1) / 2 * thickness
        val score = if (line.confidence.isNaN()) 0f else line.confidence.coerceIn(0f, 1f)
        return SmartCrop.TextBox(
            max(0.0, left - pad), max(0.0, top - pad), min(w.toDouble(), right + pad), min(h.toDouble(), bottom + pad), score, thickness
        )
    }

    private fun side(a: Corner, b: Corner): Double = hypot(b.x - a.x, b.y - a.y)
}
