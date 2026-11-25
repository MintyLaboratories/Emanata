package minty.emanata.ui

import android.app.Application
import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FrozenBitmapTest {

    @Test
    fun aMutablePageBecomesAnImmutableCopy() {
        val page = Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888)
        page.setPixel(1, 1, 0xFF336699.toInt())
        val frozen = PageAdapter.frozen(page)
        assertFalse(frozen.isMutable)
        assertEquals(0xFF336699.toInt(), frozen.getPixel(1, 1))
        assertTrue(page.isRecycled)
    }

    @Test
    fun anImmutablePageIsKept() {
        val page = Bitmap.createBitmap(intArrayOf(1, 2, 3, 4), 2, 2, Bitmap.Config.ARGB_8888)
        assertSame(page, PageAdapter.frozen(page))
    }

    @Test
    @Config(sdk = [32])
    fun beforeApi33NothingIsCopied() {
        val page = Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888)
        assertSame(page, PageAdapter.frozen(page))
    }
}
