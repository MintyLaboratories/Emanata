package com.infinity.comicreader.ui

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.ai.AiNote
import com.infinity.comicreader.ai.Fallback
import com.infinity.comicreader.ai.PageClass
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Page info's AI lines: the size a page was read at and why, the models not needed there, and why it shows without AI. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PageInfoTextTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun ai(note: AiNote) = PageInfoText.ai(context, note)

    @Test
    fun readAsItFitsWithAnUpscalerNotNeeded() {
        assertEquals(
            "Reading size: 93% of its pixel size (as it fits the screen)\nNot needed at this size: Upscaler 2x",
            ai(AiNote(92.6, false, PageClass.US, listOf("Upscaler 2x"), Fallback.UNNEEDED))
        )
    }

    @Test
    fun readAtItsPrintedSize() {
        assertEquals("Reading size: 166% of its pixel size (printed size of a US comic)", ai(AiNote(165.9, true, PageClass.US, emptyList(), null)))
        assertEquals("Reading size: 250% of its pixel size (printed size of a web comic)", ai(AiNote(250.0, true, PageClass.WEB, emptyList(), null)))
        assertEquals("Reading size: 127% of its pixel size (printed size of a manga)", ai(AiNote(126.7, true, PageClass.MANGA, emptyList(), null)))
        assertEquals(
            "Reading size: 177% of its pixel size (printed size of a Franco-Belgian album)",
            ai(AiNote(176.5, true, PageClass.FRANCO_BELGIAN, emptyList(), null))
        )
    }

    @Test
    fun aModelOverTheOutputCapSaysSo() {
        assertEquals(
            "Reading size: 110% of its pixel size (as it fits the screen)\nShown without AI: too large (results stop at 32 MP)",
            ai(AiNote(110.0, false, null, emptyList(), Fallback.TOO_LARGE))
        )
    }

    @Test
    fun withNothingKnownItSaysProcessed() {
        assertEquals("Processed", ai(AiNote(null, false, null, emptyList(), null)))
        assertEquals("Shown without AI: a model would not open", ai(AiNote(null, false, null, emptyList(), Fallback.UNOPENED)))
    }
}
