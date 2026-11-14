package com.infinity.comicreader.data

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import com.infinity.comicreader.ai.CoverTextDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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

    /** A detector seeing one line across rows a tenth to a fifth down; notes each image's size in [seen]. */
    private fun detector(seen: MutableList<List<Int>>) = object : CoverTextDetector {
        override val id = "test"

        override fun lines(src: Bitmap): List<SmartCrop.TextBox> {
            seen += listOf(src.width, src.height)
            return listOf(SmartCrop.TextBox(0.0, src.height / 10.0, src.width.toDouble(), src.height / 5.0, 0.9f))
        }
    }

    @Test
    fun aDetectorSeesThePartAtPpOcrsInputSize() {
        val seen = ArrayList<List<Int>>()
        val focus = CoverArt.focusOf(cover, Rect(0, 0, 400, 600), detector(seen))
        assertEquals(listOf(SmartCrop.inputSize(400, 600).toList()), seen)
        assertEquals(0.1, focus.title!!.top, 0.02)
        assertEquals(0.2, focus.title!!.bottom, 0.02)
    }

    @Test
    fun aDetectorWaitingForItsModelLeavesTheCropAtTheTopUnkept() {
        var asked = 0
        val waiting = object : CoverTextDetector {
            override val id = "waiting"

            override fun ready(): Boolean {
                asked++
                return false
            }

            override fun lines(src: Bitmap): List<SmartCrop.TextBox> = throw AssertionError("never looks")
        }
        val focus = CoverArt.focusOf(cover, Rect(0, 0, 400, 600), waiting)
        assertEquals(1, asked)
        assertSame(CoverFocus.WAITING, focus)
        assertFalse(focus.keep)
        assertNull(focus.title)
        // Top's start: no text and no detail to move it.
        assertEquals(0.0, SmartCrop.offset(focus, 600.0, 400.0, vertical = true), 0.0)
    }
}
