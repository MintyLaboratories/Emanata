package com.infinity.comicreader.ui

import android.app.Application
import android.content.Context
import android.graphics.Color
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.ai.AiThresholds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The sentence under the AI size slider, for typical handle positions: reading sizes as a share of a page's pixel size. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AiSizeTextTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun sentence(up: Int, clean: Int) =
        AiSizeText.sentence(context, AiThresholds(up, clean), Color.BLUE, Color.MAGENTA).toString()

    @Test
    fun defaults() {
        assertEquals(
            "Upscale pages whose reading size is over 100% of their pixel size. Clean up pages whose reading size is over 50% of their pixel size. Descreen, when chosen, runs at every size.",
            sentence(100, 50)
        )
    }

    @Test
    fun upscaleAtTheFloorAndCleanupAlways() {
        assertEquals(
            "Upscale pages whose reading size is over 50% of their pixel size. Clean up pages at every size. Descreen, when chosen, runs at every size.",
            sentence(50, AiThresholds.ALWAYS)
        )
    }

    @Test
    fun crossedHandles() {
        assertEquals(
            "Upscale pages whose reading size is over 60% of their pixel size. Clean up pages whose reading size is over 150% of their pixel size. Descreen, when chosen, runs at every size.",
            sentence(60, 150)
        )
    }

    @Test
    fun theEnds() {
        assertEquals(
            "Upscale pages whose reading size is over 200% of their pixel size. Clean up pages whose reading size is over 25% of their pixel size. Descreen, when chosen, runs at every size.",
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

    @Test
    fun changedNamesOnlyTheHandlesOffTheirDefaults() {
        assertNull(AiSizeText.changed(context, AiThresholds()))
        assertEquals("Models by reading size: Upscale pages whose reading size is over 60% of their pixel size.", AiSizeText.changed(context, AiThresholds(60, 50)))
        assertEquals("Models by reading size: Clean up pages at every size.", AiSizeText.changed(context, AiThresholds(100, AiThresholds.ALWAYS)))
        assertEquals(
            "Models by reading size: Upscale pages whose reading size is over 200% of their pixel size. Clean up pages whose reading size is over 25% of their pixel size.",
            AiSizeText.changed(context, AiThresholds(200, 25))
        )
    }
}
