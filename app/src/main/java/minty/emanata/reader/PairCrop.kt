package minty.emanata.reader

import kotlin.math.max
import kotlin.math.min

/**
 * Auto-crop for two pages shown side by side, given what each page's own auto-crop
 * found ([Box]es, the [left] and [right] page on screen): one top and one bottom for
 * both, the union of theirs, so the two show at one height. The two halves of one
 * spread ([spread]) keep their inner edges, along the gutter, whole and crop only
 * their outer sides; unrelated pages crop their sides as each found.
 */
object PairCrop {
    fun boxes(left: Box, right: Box, spread: Boolean): Pair<Box, Box> {
        val top = min(left.top, right.top)
        val bottom = max(left.bottom, right.bottom)
        return Box(left.left, top, if (spread) 1.0 else left.right, bottom) to
            Box(if (spread) 0.0 else right.left, top, right.right, bottom)
    }

    /** Width / height of [box] of a [w] x [h] image, in the whole pixels a decode crops it to. */
    fun aspect(box: Box, w: Int, h: Int): Double {
        val px = box.pixels(w, h)
        return (px[2] - px[0]).toDouble() / (px[3] - px[1])
    }
}
