package minty.emanata.ui

import minty.emanata.reader.Box
import minty.emanata.reader.PairCrop
import java.util.concurrent.ConcurrentHashMap

/**
 * What auto-crop found on each page of the open book, by page index, and the size of
 * the image it was found on: what two pages side by side need to share one crop and
 * one height ([PairCrop]). Outlives page adapters, which are rebuilt on layout changes;
 * thread-safe.
 */
class PageBounds {

    /** The content [box] of a [width] x [height] image; 0 x 0 when the size is unknown. */
    class Found(val box: Box, val width: Int, val height: Int) {
        /** Width / height of [shown], a crop of this page; 0 when the size is unknown. */
        fun aspect(shown: Box): Double = if (width > 0 && height > 0) PairCrop.aspect(shown, width, height) else 0.0
    }

    private val found = ConcurrentHashMap<Int, Found>()

    operator fun get(page: Int): Found? = found[page]

    operator fun set(page: Int, value: Found) {
        found[page] = value
    }
}
