package minty.emanata.ai

import android.graphics.Bitmap
import minty.emanata.data.CoverText

/**
 * Finds the lines of text on a cover (where text is, not what it says), which tell a
 * wraparound's front and reading direction. Its [id] is part of the cover-focus version
 * ([CoverText.version]): focuses, and the covers and directions they told, found by another
 * model are found again.
 */
interface CoverTextDetector {
    /** Short and stable; a new one for any change to what [lines] finds. */
    val id: String

    /**
     * False while the detector cannot look for want of its model, which it then asks for: covers
     * that need its text are made without it meanwhile and made again once it is there. Quick
     * once true.
     */
    fun ready(): Boolean = true

    /**
     * The lines of text on [src], in its pixels; empty where the detector never runs, null when
     * it did not run this time. Blocking; one image at a time.
     */
    fun lines(src: Bitmap): List<CoverText.TextBox>?
}
