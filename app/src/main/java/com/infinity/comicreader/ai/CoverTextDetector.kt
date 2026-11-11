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
     * False while the detector cannot look for want of its model, which it then asks for: covers
     * crop as Top does meanwhile and are made again once it is there. Quick once true.
     */
    fun ready(): Boolean = true

    /**
     * The lines of text on [src], in its pixels; empty where the detector never runs, null when
     * it did not run this time. Blocking; one image at a time.
     */
    fun lines(src: Bitmap): List<SmartCrop.TextBox>?

    companion object {
        /**
         * The [id]s of the app's detectors: covers' focuses found by each are kept, of others
         * deleted. ML Kit's are kept while PP-OCR stands in for it, so a session that falls back
         * finds none of them again. Lazy: [TextDetector]'s own initialization initializes this
         * interface first.
         */
        val IDS: Set<String> by lazy { setOf(TextDetector.ID, CoverTextDetectors.ML_KIT_ID) }
    }
}
