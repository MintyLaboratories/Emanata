package com.infinity.comicreader.ui

import android.app.Application
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.R
import com.infinity.comicreader.ai.AiStep
import com.infinity.comicreader.ai.ModelCatalog
import com.infinity.comicreader.ai.ModelSource
import com.infinity.comicreader.ai.Models
import com.infinity.comicreader.ai.ShaderCatalog
import com.infinity.comicreader.data.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What pickers say about each model: what it does, which is the default, and how slow the slow ones are. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ModelTextTest {

    private val context = ApplicationProvider.getApplicationContext<Application>().apply { setTheme(R.style.Theme_ComicReader) }

    private fun blurb(id: String) = ModelText.blurb(context, ModelCatalog.byId(id)!!)

    @Test
    fun eachUpscalerSaysWhatItDoes() {
        assertEquals("Faithful, fast and reliable clarity improvement; does little for blurry art or jagged lines (default)", blurb("waifu2x-upconv7-2x"))
        for (n in 0..3) {
            assertEquals("A balance between faithful and enhanced, sharpening detail; may mildly blur textures", blurb("waifu2x-upconv7-2x-denoise$n"))
        }
        for (x in 2..4) {
            assertEquals("Sharper; may remove noise, ringing and similar flaws in the original; slower than waifu2x", blurb("opencomic-ai-upscale-${x}x-compact-v2"))
        }
        assertEquals(
            "Sharpest restoration, for vintage or low-resolution webcomics; unfaithful: reimagines fine detail, texture and color\nSlow (~11 s per page)",
            blurb("realesr-animevideov3-x2")
        )
        assertTrue(blurb("realesr-animevideov3-x4").endsWith("\nSlow (~14 s per page)"))
        assertEquals("For soft or lightly compressed pages, cleaner than ArtCNN; less faithful than waifu2x", blurb("cunny-8x32-ds-rp"))
        assertEquals("For soft or lightly compressed pages, sharper than CuNNy; less faithful than waifu2x", blurb("artcnn-c4f32-ds"))
        assertEquals("Best for restoring heavily artifacted or blurry art; slow, may reimagine detail\nSlow (~14 s per page)", blurb("4x-lsdir-compact-c3"))
    }

    @Test
    fun anAttributionNamesWhereTheFilesComeFromAndWhatAConversionIs() {
        val cunny = ModelCatalog.byId("cunny-8x32-ds-rp")!!.let { ModelText.attribution(context, it, it.source) }
        assertTrue(cunny, cunny.contains("By funnyplanter, under the LGPL 3.0 or later.") && cunny.contains("https://github.com/funnyplanter/CuNNy"))
        assertTrue(cunny, cunny.contains("Downloaded unchanged from: ${ModelCatalog.HOSTED_REPOSITORY}"))
        assertTrue(cunny, cunny.endsWith("\n\n" + ModelSource.CUNNY.conversion))
        val waifu2x = ModelCatalog.byId("waifu2x-upconv7-2x")!!.let { ModelText.attribution(context, it, it.source) }
        assertTrue(waifu2x, waifu2x.endsWith("Downloaded unchanged from: ${ModelCatalog.REPOSITORY}"))
    }

    @Test
    fun everyShaderLevelSaysWhatItsFamilyDoes() {
        // Best fit, then biggest downside.
        for (m in ShaderCatalog.all) assertEquals(m.id, 2, ModelText.blurb(context, m).split("; ").size)
        assertEquals("For clean art that needs sharper lines; sharpens compression artifacts too", ModelText.blurb(context, ShaderCatalog.byId("anime4k-a-s")!!))
        assertEquals(ModelText.blurb(context, ShaderCatalog.byId("anime4k-c-ul")!!), ModelText.blurb(context, ShaderCatalog.byId("anime4k-c-m")!!))
    }

    @Test
    fun cleanupModelsSayWhatTheyDo() {
        assertEquals("For scanned print with visible dot patterns; softens clean art", blurb("opencomic-ai-descreen-hard-compact-v2"))
        assertEquals("For scanned print with visible dot patterns\nSlow (~15 s per page)", blurb("opencomic-ai-descreen-hard-fast-v3-256"))
        assertEquals("For blocky, heavily compressed pages; no visible effect on clean ones", blurb("opencomic-ai-artifact-removal-compact-v2"))
    }

    @Test
    fun aDescriptionIsWhatTheModelDoesWithoutMarks() {
        val waifu2x = ModelCatalog.byId(ModelCatalog.DEFAULT_UPSCALE)!!
        assertEquals("Faithful, fast and reliable clarity improvement; does little for blurry art or jagged lines", ModelText.description(context, waifu2x))
        val lsdir = ModelCatalog.byId("4x-lsdir-compact-c3")!!
        assertEquals("Best for restoring heavily artifacted or blurry art; slow, may reimagine detail", ModelText.description(context, lsdir))
        // Every catalog model and shader chain has one.
        for (m in ModelCatalog.all + ShaderCatalog.all) assertTrue(m.id, !ModelText.description(context, m).isNullOrEmpty())
    }

    @Test
    fun anEntryIsTheNameAndSizeThenItsBlurbSmaller() {
        val m = ModelCatalog.byId("waifu2x-upconv7-2x-denoise1")!!
        val entry = ModelText.entry(context, m)
        val head = context.getString(R.string.ai_model_entry, m.name, ModelText.size(context, m.bytes))
        assertEquals(head + "\n" + ModelText.blurb(context, m), entry.toString())
        val spans = (entry as Spanned).getSpans(0, entry.length, RelativeSizeSpan::class.java)
        assertEquals(1, spans.size)
        assertEquals(head.length + 1, entry.getSpanStart(spans[0]))
    }

    @Test
    fun choicesStartWithNoneAndHideSlowModelsWhileKeepingItSimple() {
        val (simple, simpleIds) = ModelText.choices(context, AiStep.UPSCALE, keepSimple = true, current = null)
        assertEquals(context.getString(R.string.ai_none), simple[0].toString())
        assertEquals(Prefs.AI_NONE, simpleIds[0])
        assertEquals(listOf(Prefs.AI_NONE) + Models.offered(AiStep.UPSCALE, true, null).map { it.id }, simpleIds)
        assertFalse(simple.any { it.contains("Slow (~") })
        val (all, allIds) = ModelText.choices(context, AiStep.UPSCALE, keepSimple = false, current = null)
        assertEquals(Models.forStep(AiStep.UPSCALE).size + 1, allIds.size)
        assertTrue(all.any { it.contains("Slow (~11 s per page)") })
        // A slow model chosen stays listed.
        assertTrue("realesr-animevideov3-x3" in ModelText.choices(context, AiStep.UPSCALE, true, "realesr-animevideov3-x3").second)
    }
}
