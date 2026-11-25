package minty.emanata.data

import minty.emanata.ai.CoverTextDetector
import minty.emanata.ai.ScoreMap
import minty.emanata.reader.Box
import minty.emanata.reader.Wraparound
import kotlin.math.max
import kotlin.math.min

/**
 * The tallest text on part of a cover, which tells a wraparound's front ([CoverText.textFront]):
 * [title], the line holding it, as fractions of the part searched, null where no text of a
 * title's size was found; [glyph], the size of its letters ([CoverText.TextBox.glyph]) as a
 * share of the shorter side of the image searched, 0 without a title or when unknown. Found
 * once per cover part and kept ([encode]), unless not [keep]: its text was looked for but the
 * detector did not run, so it holds nothing, right for this cover made now only.
 */
class CoverFocus(val title: Box?, val glyph: Double = 0.0, val keep: Boolean = true) {
    /** A short line of text [decode] reads back: the title's box and [glyph] to 4 decimals. */
    fun encode(): String = listOf(title?.let(::box).orEmpty(), if (title == null) "" else q(glyph)).joinToString(";")

    override fun equals(other: Any?): Boolean = other is CoverFocus && title == other.title && glyph == other.glyph

    override fun hashCode(): Int = title.hashCode() * 31 + glyph.hashCode()

    override fun toString(): String = "CoverFocus(title=$title, glyph=$glyph)"

    companion object {
        /** A focus without a title. */
        val NONE = CoverFocus(null)

        /** [NONE] while the detector waits for its model ([CoverTextDetector.ready]): not kept. */
        val WAITING = CoverFocus(null, keep = false)

        private fun q(v: Double): String = "%.4f".format(java.util.Locale.ROOT, v)
        private fun box(b: Box): String = listOf(b.left, b.top, b.right, b.bottom).joinToString(",") { q(it) }

        /** What [encode] wrote; null when [s] is not that. */
        fun decode(s: String?): CoverFocus? {
            val parts = s?.split(';')?.takeIf { it.size == 2 } ?: return null
            if (parts[0].isEmpty()) return if (parts[1].isEmpty()) NONE else null
            val v = parts[0].split(',').map { it.toDoubleOrNull() ?: return null }.takeIf { it.size == 4 } ?: return null
            val glyph = parts[1].toDoubleOrNull() ?: return null
            return CoverFocus(Box(v[0], v[1], v[2], v[3]), glyph)
        }
    }
}

/**
 * Cover text found by a text detector (where text is, not what it says): the tallest text on
 * each half of a wraparound tells its front ([textFront]), which also sets reading direction
 * when its barcode agrees. Only text of a title's size counts ([MIN_GLYPH]). Free of Android
 * types.
 */
object CoverText {
    /**
     * The version of these rules, part of [version]. Raise it with any change to what a focus
     * holds or how a front follows it.
     */
    const val RULES = 5

    /**
     * The cover-focus version: of these rules ([RULES]) and of the text detector's model by its
     * [CoverTextDetector.id]. Focuses, and the covers and directions text told, found under
     * another are found again.
     */
    fun version(detector: String): String = "$RULES-$detector"

    /** The detector's input: its longer side at most this, both sides multiples of [STRIDE]. */
    const val INPUT_SIDE = 640
    const val STRIDE = 32

    /** A score map's pixels above this are text ([boxes]). */
    const val TEXT_THRESHOLD = 0.3f

    /** A text region's mean score must reach this to count. */
    const val BOX_THRESHOLD = 0.6f

    /** Text regions thinner than this many map pixels are noise. */
    const val MIN_SIDE = 3

    /** The detector marks a shrunk core of each line; its box grows by this times area / perimeter (DB's unclip). */
    const val UNCLIP = 1.5

    /**
     * Text whose letter size ([TextBox.glyph]) is less than this share of the part's shorter
     * side is not a title: creators' names, subtitles, prices. A score map's glyph is its line
     * grown by [UNCLIP].
     */
    const val MIN_GLYPH = 0.12

    /** Text beside the tallest, sharing most of its height, of at least this share of its size is on its line. */
    const val SAME_SIZE = 0.7

    /** A wraparound's half is its front by text when its title is at least this many times as tall as the other's. */
    const val FRONT_MARGIN = 1.25

    /**
     * A region of text in the pixels of what it was found on, [right] and [bottom] exclusive,
     * after growing it: a score map's ([boxes]), the image given a detector's ([CoverTextDetector.lines]).
     */
    data class TextBox(val left: Double, val top: Double, val right: Double, val bottom: Double, val score: Float) {
        val width: Double get() = right - left
        val height: Double get() = bottom - top

        /** The size of its letters: the box's, whichever way it runs. */
        val glyph: Double get() = min(width, height)
    }

    /** `[width, height]` of PP-OCR's input for a [w] x [h] cover: at most [INPUT_SIDE], multiples of [STRIDE]. */
    fun inputSize(w: Int, h: Int): IntArray {
        val scale = min(1.0, INPUT_SIDE.toDouble() / max(w, h))
        fun side(v: Int) = (Math.round(v * scale / STRIDE) * STRIDE).toInt().coerceAtLeast(STRIDE)
        return intArrayOf(side(w), side(h))
    }

    /**
     * The text regions of [map]: 8-connected pixels above [TEXT_THRESHOLD] whose mean score
     * reaches [BOX_THRESHOLD] and that are at least [MIN_SIDE] each way, as boxes grown by
     * [UNCLIP] and clamped to the map.
     */
    fun boxes(map: ScoreMap): List<TextBox> {
        val w = map.width
        val h = map.height
        val label = IntArray(w * h)
        val queue = IntArray(w * h)
        val out = ArrayList<TextBox>()
        var next = 0
        for (start in 0 until w * h) {
            if (label[start] != 0 || map.values[start] <= TEXT_THRESHOLD) continue
            next++
            label[start] = next
            var head = 0
            var tail = 0
            queue[tail++] = start
            var minX = w
            var minY = h
            var maxX = -1
            var maxY = -1
            var sum = 0.0
            while (head < tail) {
                val p = queue[head++]
                val x = p % w
                val y = p / w
                sum += map.values[p]
                minX = min(minX, x)
                maxX = max(maxX, x)
                minY = min(minY, y)
                maxY = max(maxY, y)
                for (dy in -1..1) {
                    val ny = y + dy
                    if (ny < 0 || ny >= h) continue
                    for (dx in -1..1) {
                        val nx = x + dx
                        if (nx < 0 || nx >= w) continue
                        val q = ny * w + nx
                        if (label[q] == 0 && map.values[q] > TEXT_THRESHOLD) {
                            label[q] = next
                            queue[tail++] = q
                        }
                    }
                }
            }
            val bw = maxX - minX + 1
            val bh = maxY - minY + 1
            val score = (sum / tail).toFloat()
            if (bw < MIN_SIDE || bh < MIN_SIDE || score < BOX_THRESHOLD) continue
            val grow = UNCLIP * bw * bh / (2.0 * (bw + bh))
            out += TextBox(
                max(0.0, minX - grow), max(0.0, minY - grow),
                min(w.toDouble(), maxX + 1 + grow), min(h.toDouble(), maxY + 1 + grow), score
            )
        }
        return out
    }

    /**
     * The text regions of [map] ([boxes]) in the pixels of the [w] x [h] image it was found on.
     */
    fun lines(map: ScoreMap, w: Int, h: Int): List<TextBox> {
        val found = boxes(map)
        if (found.isEmpty() || (map.width == w && map.height == h)) return found
        val sx = w.toDouble() / map.width
        val sy = h.toDouble() / map.height
        return found.map { TextBox(it.left * sx, it.top * sy, it.right * sx, it.bottom * sy, it.score) }
    }

    /**
     * The focus of a part of a cover from its text [boxes] on the [w] x [h] image searched
     * ([CoverTextDetector.lines]): the tallest text of at least [MIN_GLYPH], the line holding it
     * ([SAME_SIZE]), and its size.
     */
    fun focus(boxes: List<TextBox>, w: Int, h: Int): CoverFocus {
        val minGlyph = MIN_GLYPH * min(w, h)
        val big = boxes.filter { it.glyph >= minGlyph }
        val tallest = big.maxByOrNull { it.glyph } ?: return CoverFocus.NONE
        val line = big.filter {
            it.glyph >= SAME_SIZE * tallest.glyph && overlap(it.top, it.bottom, tallest.top, tallest.bottom) >= 0.5 * min(it.height, tallest.height)
        }
        val title = Box(line.minOf { it.left } / w, line.minOf { it.top } / h, line.maxOf { it.right } / w, line.maxOf { it.bottom } / h)
        return CoverFocus(title, tallest.glyph / min(w, h))
    }

    private fun overlap(a0: Double, a1: Double, b0: Double, b1: Double): Double = max(0.0, min(a1, b1) - max(a0, b0))

    /**
     * The front of a wraparound [height] tall from the focus of each half as shown, [left] of
     * [leftWidth] and [right] of [rightWidth]: the one whose title's letters ([CoverFocus.glyph],
     * else its box's shorter side) are at least [FRONT_MARGIN] times as tall as the other's (a
     * half without a title has none); null when neither is.
     */
    fun textFront(left: CoverFocus, leftWidth: Int, right: CoverFocus, rightWidth: Int, height: Int): Wraparound.Half? {
        fun glyph(f: CoverFocus, w: Int) = f.title?.let { if (f.glyph > 0) f.glyph * min(w, height) else min(it.width * w, it.height * height) } ?: 0.0
        val l = glyph(left, leftWidth)
        val r = glyph(right, rightWidth)
        return when {
            l > 0 && l >= FRONT_MARGIN * r -> Wraparound.Half.LEFT
            r > 0 && r >= FRONT_MARGIN * l -> Wraparound.Half.RIGHT
            else -> null
        }
    }
}
