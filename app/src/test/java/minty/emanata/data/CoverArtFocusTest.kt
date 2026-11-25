package minty.emanata.data

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import minty.emanata.ai.CoverTextDetector
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

        override fun lines(src: Bitmap): List<CoverText.TextBox> {
            seen += listOf(src.width, src.height)
            return listOf(CoverText.TextBox(0.0, src.height / 10.0, src.width.toDouble(), src.height / 5.0, 0.9f))
        }
    }

    @Test
    fun aDetectorSeesThePartAtPpOcrsInputSize() {
        val seen = ArrayList<List<Int>>()
        val focus = CoverArt.focusOf(cover, Rect(0, 0, 400, 600), detector(seen))
        assertEquals(listOf(CoverText.inputSize(400, 600).toList()), seen)
        assertEquals(0.1, focus.title!!.top, 0.02)
        assertEquals(0.2, focus.title!!.bottom, 0.02)
    }

    @Test
    fun aDetectorWaitingForItsModelFindsNothingUnkept() {
        var asked = 0
        val waiting = object : CoverTextDetector {
            override val id = "waiting"

            override fun ready(): Boolean {
                asked++
                return false
            }

            override fun lines(src: Bitmap): List<CoverText.TextBox> = throw AssertionError("never looks")
        }
        val focus = CoverArt.focusOf(cover, Rect(0, 0, 400, 600), waiting)
        assertEquals(1, asked)
        assertSame(CoverFocus.WAITING, focus)
        assertFalse(focus.keep)
        assertNull(focus.title)
    }

    @Test
    fun aDetectorThatDoesNotRunFindsNothingUnkept() {
        val failing = object : CoverTextDetector {
            override val id = "failing"

            override fun lines(src: Bitmap): List<CoverText.TextBox>? = null
        }
        val focus = CoverArt.focusOf(cover, Rect(0, 0, 400, 600), failing)
        assertFalse(focus.keep)
        assertNull(focus.title)
        // Without a detector: nothing found, as found.
        assertSame(CoverFocus.NONE, CoverArt.focusOf(cover, Rect(0, 0, 400, 600), null))
    }
}
