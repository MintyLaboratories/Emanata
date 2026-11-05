package com.infinity.comicreader.data

import com.infinity.comicreader.ai.ScoreMap
import com.infinity.comicreader.reader.Box
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Where the Smart crop anchor ([CropAnchor.SMART]) looks on a cover, as fractions of the part
 * of it shown: [title], its tallest text (the line holding it), and [lines], every line of
 * about that size (a title of several lines, the title among them); null and empty where no
 * text of a title's size was found. [rows] and [columns] are its detail (edge energy) along
 * each axis in equal bins, summing to 1 (all zero on a blank cover), for covers without text.
 * Found once per cover and kept ([encode]), unless not [keep]: its text was looked for but the
 * detector did not run, so it holds detail alone, right for this cover made now only.
 */
class CoverFocus(val title: Box?, val lines: List<Box>, val rows: FloatArray, val columns: FloatArray, val keep: Boolean = true) {
    /** Every line of [lines] together; null without any. */
    val text: Box? = if (lines.isEmpty()) null else Box(lines.minOf { it.left }, lines.minOf { it.top }, lines.maxOf { it.right }, lines.maxOf { it.bottom })

    /** A short line of text [decode] reads back. */
    fun encode(): String = listOf(
        title?.let(::box).orEmpty(),
        lines.joinToString("/") { box(it) },
        rows.joinToString(",") { p(it) },
        columns.joinToString(",") { p(it) }
    ).joinToString(";")

    override fun equals(other: Any?): Boolean =
        other is CoverFocus && title == other.title && lines == other.lines && rows.contentEquals(other.rows) && columns.contentEquals(other.columns)

    override fun hashCode(): Int = ((title.hashCode() * 31 + lines.hashCode()) * 31 + rows.contentHashCode()) * 31 + columns.contentHashCode()

    override fun toString(): String = "CoverFocus(title=$title, lines=$lines)"

    companion object {
        /** A focus with nothing to go by: Smart then crops as Top does. */
        val NONE = CoverFocus(null, emptyList(), FloatArray(0), FloatArray(0))

        private fun q(v: Double): String = "%.4f".format(java.util.Locale.ROOT, v)
        private fun p(v: Float): String = "%.3f".format(java.util.Locale.ROOT, v)
        private fun box(b: Box): String = listOf(b.left, b.top, b.right, b.bottom).joinToString(",") { q(it) }

        /** What [encode] wrote; null when [s] is not that. */
        fun decode(s: String?): CoverFocus? {
            val parts = s?.split(';')?.takeIf { it.size == 4 } ?: return null
            fun box(p: String): Box? {
                val v = p.split(',').map { it.toDoubleOrNull() ?: return null }
                return if (v.size == 4) Box(v[0], v[1], v[2], v[3]) else null
            }
            fun floats(p: String): FloatArray? =
                if (p.isEmpty()) FloatArray(0) else p.split(',').map { it.toFloatOrNull() ?: return null }.toFloatArray()
            val title = if (parts[0].isEmpty()) null else box(parts[0]) ?: return null
            val lines = if (parts[1].isEmpty()) emptyList() else parts[1].split('/').map { box(it) ?: return null }
            if ((title == null) != lines.isEmpty()) return null
            return CoverFocus(title, lines, floats(parts[2]) ?: return null, floats(parts[3]) ?: return null)
        }
    }
}

/**
 * The Smart crop anchor: text found by a text detector (where text is, not what it says) and
 * the detail of a cover decide which part of it a crop keeps. The tallest text wins: when the
 * plain anchor (the top; centered across) loses every line of about its size, the crop keeps
 * them all, else the tallest's own line, whole, staying as near the plain anchor as that
 * allows. Only text of a title's size counts ([MIN_GLYPH]): credits alone never move a crop. A
 * cover without such text keeps its most detailed part, when far more detailed than the plain
 * anchor's. Free of Android types.
 */
object SmartCrop {
    /**
     * The version of these rules and of the detector: focuses and Smart covers found under
     * another are found again. Raise it with any change to what a focus holds.
     */
    const val RULES = 2

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
     * Text whose line ([TextBox.glyph], grown) is thinner than this share of the cover's shorter
     * side is not a title: creators' names, subtitles, prices.
     */
    const val MIN_GLYPH = 0.12

    /** Lines whose text is at least this share of the tallest's size belong with it. */
    const val SAME_SIZE = 0.7

    /** Room kept around kept text, as a share of the span shown. */
    const val PAD = 0.02

    /** Bins of [CoverFocus.rows] and [CoverFocus.columns]. */
    const val BINS = 32

    /** A detailed part replaces the plain anchor's only with at least this much more detail. */
    const val DETAIL_GAIN = 1.5

    /** Steps tried along an axis when looking for its most detailed part. */
    private const val STEPS = 64

    /** A region of text in score map pixels, [right] and [bottom] exclusive, after growing it. */
    data class TextBox(val left: Double, val top: Double, val right: Double, val bottom: Double, val score: Float) {
        val width: Double get() = right - left
        val height: Double get() = bottom - top

        /** The size of its letters: the line's thickness, whichever way it runs. */
        val glyph: Double get() = min(width, height)
    }

    /** `[width, height]` of the detector's input for a [w] x [h] cover: at most [INPUT_SIDE], multiples of [STRIDE]. */
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
     * The focus of a cover from its text [boxes] on a [w] x [h] map and its detail [rows] and
     * [columns] ([detail]): the tallest text of at least [MIN_GLYPH], the line holding it, and
     * every line of [SAME_SIZE] or more of its size.
     */
    fun focus(boxes: List<TextBox>, w: Int, h: Int, rows: FloatArray, columns: FloatArray): CoverFocus {
        val minGlyph = MIN_GLYPH * min(w, h)
        val big = boxes.filter { it.glyph >= minGlyph }
        val tallest = big.maxByOrNull { it.glyph } ?: return CoverFocus(null, emptyList(), rows, columns)
        val peers = big.filter { it.glyph >= SAME_SIZE * tallest.glyph }
        // The tallest text's line: its peers beside it, sharing most of its height.
        val line = peers.filter { overlap(it.top, it.bottom, tallest.top, tallest.bottom) >= 0.5 * min(it.height, tallest.height) }
        return CoverFocus(union(line, w, h), peers.map { union(listOf(it), w, h) }, rows, columns)
    }

    private fun overlap(a0: Double, a1: Double, b0: Double, b1: Double): Double = max(0.0, min(a1, b1) - max(a0, b0))

    private fun union(boxes: List<TextBox>, w: Int, h: Int): Box = Box(
        boxes.minOf { it.left } / w, boxes.minOf { it.top } / h, boxes.maxOf { it.right } / w, boxes.maxOf { it.bottom } / h
    )

    /**
     * Detail of a [w] x [h] grayscale image ([luma], row by row, 0..255 as unsigned bytes) along
     * each axis: the sum of absolute differences to the right and below, in [BINS] bins per axis,
     * each axis summing to 1 (all zero for a flat image). `[rows, columns]`.
     */
    fun detail(luma: ByteArray, w: Int, h: Int): Array<FloatArray> {
        val rows = DoubleArray(BINS)
        val columns = DoubleArray(BINS)
        for (y in 0 until h) {
            val ry = y * BINS / h
            for (x in 0 until w) {
                val v = luma[y * w + x].toInt() and 0xFF
                var e = 0
                if (x + 1 < w) e += abs(v - (luma[y * w + x + 1].toInt() and 0xFF))
                if (y + 1 < h) e += abs(v - (luma[(y + 1) * w + x].toInt() and 0xFF))
                rows[ry] += e.toDouble()
                columns[x * BINS / w] += e.toDouble()
            }
        }
        return arrayOf(normalized(rows), normalized(columns))
    }

    private fun normalized(v: DoubleArray): FloatArray {
        val sum = v.sum()
        return FloatArray(v.size) { if (sum > 0) (v[it] / sum).toFloat() else 0f }
    }

    /**
     * Where a crop [span] long starts along an axis [length] long, in the same units, for
     * [focus] ([vertical]: down from the top; else across from the left). The plain anchor is
     * the top, or centered across.
     */
    fun offset(focus: CoverFocus, length: Double, span: Double, vertical: Boolean): Double {
        val room = length - span
        if (room <= 0) return 0.0
        val plain = if (vertical) 0.0 else room / 2
        fun start(b: Box) = (if (vertical) b.top else b.left) * length
        fun end(b: Box) = (if (vertical) b.bottom else b.right) * length
        // Text of the title's size the plain crop keeps whole is reason enough to keep it.
        if (focus.lines.any { start(it) >= plain && end(it) <= plain + span }) return plain
        for (band in listOfNotNull(focus.text, focus.title)) {
            val pad = PAD * span
            val lo = max(0.0, end(band) + pad - span)
            val hi = min(room, start(band) - pad)
            if (lo <= hi) return plain.coerceIn(lo, hi)
        }
        focus.title?.let { band ->
            // Too large to keep whole even alone: its middle stays.
            return ((start(band) + end(band) - span) / 2).coerceIn(0.0, room)
        }
        val profile = if (vertical) focus.rows else focus.columns
        if (profile.isEmpty() || profile.all { it == 0f }) return plain
        var best = plain
        var bestDetail = within(profile, plain / length, (plain + span) / length)
        val plainDetail = bestDetail
        for (i in 0..STEPS) {
            val at = room * i / STEPS
            val d = within(profile, at / length, (at + span) / length)
            if (d > bestDetail + 1e-9) {
                best = at
                bestDetail = d
            }
        }
        return if (bestDetail >= DETAIL_GAIN * plainDetail) best else plain
    }

    /** The share of [profile] (equal bins over 0..1) between [from] and [to]. */
    internal fun within(profile: FloatArray, from: Double, to: Double): Double {
        val n = profile.size
        var sum = 0.0
        for (i in 0 until n) {
            val a = i.toDouble() / n
            val b = (i + 1).toDouble() / n
            val o = overlap(a, b, from, to)
            if (o > 0) sum += profile[i] * o * n
        }
        return sum
    }
}
