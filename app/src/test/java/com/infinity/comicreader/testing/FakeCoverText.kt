package com.infinity.comicreader.testing

import android.graphics.Bitmap
import com.infinity.comicreader.ai.CoverTextDetector
import com.infinity.comicreader.ai.ScoreMap
import com.infinity.comicreader.data.SmartCrop

/** A cover text detector known as [id] whose lines are the text regions of the score map [map] gives each image ([SmartCrop.lines]). */
fun coverText(id: String = "fake", map: (Bitmap) -> ScoreMap?): CoverTextDetector = object : CoverTextDetector {
    override val id: String = id

    override fun lines(src: Bitmap): List<SmartCrop.TextBox>? = map(src)?.let { SmartCrop.lines(it, src.width, src.height) }
}
