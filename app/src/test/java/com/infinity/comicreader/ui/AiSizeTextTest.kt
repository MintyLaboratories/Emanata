package com.infinity.comicreader.ui

import android.app.Application
import android.content.Context
import android.graphics.Color
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.ai.AiThresholds
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The sentence under the AI size slider, for typical handle positions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AiSizeTextTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun sentence(up: Int, clean: Int) =
        AiSizeText.sentence(context, AiThresholds(up, clean), Color.BLUE, Color.MAGENTA).toString()

    @Test
    fun defaults() {
        assertEquals(
            "Upscale pages shown larger than their pixels. Clean up pages shown larger than half size. Descreen at every size.",
            sentence(100, 50)
        )
    }

    @Test
    fun upscaleAtTheFloorAndCleanupAlways() {
        assertEquals(
            "Upscale pages shown larger than half size. Clean up pages at every size. Descreen at every size.",
            sentence(50, AiThresholds.ALWAYS)
        )
    }

    @Test
    fun crossedHandles() {
        assertEquals(
            "Upscale pages shown larger than 60% size. Clean up pages shown larger than 150% size. Descreen at every size.",
            sentence(60, 150)
        )
    }

    @Test
    fun theEnds() {
        assertEquals(
            "Upscale pages shown larger than twice their size. Clean up pages shown larger than quarter size. Descreen at every size.",
            sentence(200, 25)
        )
    }

    @Test
    fun eachHandlesClauseInItsColor() {
        val text = AiSizeText.sentence(context, AiThresholds(), Color.BLUE, Color.MAGENTA) as Spanned
        val spans = text.getSpans(0, text.length, ForegroundColorSpan::class.java).sortedBy { text.getSpanStart(it) }
        assertEquals(listOf(Color.BLUE, Color.MAGENTA), spans.map { it.foregroundColor })
        assertEquals(AiSizeText.upscale(context, AiThresholds()), text.subSequence(text.getSpanStart(spans[0]), text.getSpanEnd(spans[0])).toString())
        assertEquals(AiSizeText.cleanup(context, AiThresholds()), text.subSequence(text.getSpanStart(spans[1]), text.getSpanEnd(spans[1])).toString())
    }

    @Test
    fun handleValues() {
        assertEquals("Always", AiSizeText.value(context, AiThresholds.ALWAYS))
        assertEquals("75%", AiSizeText.value(context, 75))
    }
}
