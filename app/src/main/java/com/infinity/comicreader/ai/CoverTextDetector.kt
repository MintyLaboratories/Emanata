package com.infinity.comicreader.ai

import android.graphics.Bitmap
import com.infinity.comicreader.data.SmartCrop

/**
 * Finds the lines of text on a cover for the Smart crop anchor (where text is, not what it
 * says). Its [id] is part of the cover-focus version ([SmartCrop.version]): focuses and Smart
 * covers found by another model are found again.
 */
interface CoverTextDetector {
    /** Short and stable; a new one for any change to what [lines] finds. */
    val id: String

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
}
