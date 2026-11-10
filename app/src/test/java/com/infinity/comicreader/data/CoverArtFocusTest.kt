package com.infinity.comicreader.data

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import com.infinity.comicreader.ai.CoverTextDetector
import com.infinity.comicreader.reader.Box
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A detector sees a cover's part at the input size it asks for, and its lines, in that image's pixels, become the focus. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CoverArtFocusTest {

    private val cover = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }

    /** A detector of input [size] (PP-OCR's without) seeing one line across rows a tenth to a fifth down; notes each image's size in [seen]. */
    private fun detector(seen: MutableList<List<Int>>, size: IntArray? = null) = object : CoverTextDetector {
        override val id = "test"

        override fun inputSize(w: Int, h: Int): IntArray = size ?: super.inputSize(w, h)

        override fun lines(src: Bitmap): List<SmartCrop.TextBox> {
            seen += listOf(src.width, src.height)
            return listOf(SmartCrop.TextBox(0.0, src.height / 10.0, src.width.toDouble(), src.height / 5.0, 0.9f))
        }
    }

    @Test
    fun aDetectorSeesThePartAtItsOwnInputSize() {
        val seen = ArrayList<List<Int>>()
        val focus = CoverArt.focusOf(cover, Rect(0, 0, 400, 600), detector(seen, intArrayOf(200, 300)))
        assertEquals(listOf(listOf(200, 300)), seen)
        assertEquals(Box(0.0, 0.1, 1.0, 0.2), focus.title)
    }

    @Test
    fun byDefaultADetectorSeesPpOcrsInput() {
        val seen = ArrayList<List<Int>>()
        CoverArt.focusOf(cover, Rect(0, 0, 400, 600), detector(seen))
        assertEquals(listOf(SmartCrop.inputSize(400, 600).toList()), seen)
    }
}
