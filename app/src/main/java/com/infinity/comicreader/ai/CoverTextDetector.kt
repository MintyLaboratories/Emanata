package com.infinity.comicreader.ai

import android.graphics.Bitmap
import com.infinity.comicreader.data.SmartCrop

/**
 * Finds the lines of text on a cover for the Smart crop anchor (where text is, not what it
 * says). Its [id] is part of the cover-focus version ([SmartCrop.version]): focuses and Smart
 * covers found by another detector are found again.
 */
interface CoverTextDetector {
    /** Short and stable; a new one for any change to what [lines] finds. */
    val id: String

    /**
     * `[width, height]` a [w] x [h] part of a cover is stretched to before [lines] looks at it
     * (and its detail is measured): PP-OCR's input by default ([SmartCrop.inputSize]).
     */
    fun inputSize(w: Int, h: Int): IntArray = SmartCrop.inputSize(w, h)

    /**
     * The lines of text on [src], in its pixels; empty where the detector never runs, null when
     * it did not run this time. Blocking; one image at a time.
     */
    fun lines(src: Bitmap): List<SmartCrop.TextBox>?

    companion object {
        /**
         * The [id]s of the app's detectors: covers' focuses found by each are kept, of others
         * deleted. Lazy: [TextDetector]'s own initialization initializes this interface first.
         */
        val IDS: Set<String> by lazy { setOf(TextDetector.ID, TextDetector.DOWNLOADED_ID) }
    }
}
