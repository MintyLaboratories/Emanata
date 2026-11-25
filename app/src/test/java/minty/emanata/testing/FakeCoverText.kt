package minty.emanata.testing

import android.graphics.Bitmap
import minty.emanata.ai.CoverTextDetector
import minty.emanata.ai.ScoreMap
import minty.emanata.data.CoverText

/** A cover text detector known as [id] whose lines are the text regions of the score map [map] gives each image ([CoverText.lines]). */
fun coverText(id: String = "fake", map: (Bitmap) -> ScoreMap?): CoverTextDetector = object : CoverTextDetector {
    override val id: String = id

    override fun lines(src: Bitmap): List<CoverText.TextBox>? = map(src)?.let { CoverText.lines(it, src.width, src.height) }
}
