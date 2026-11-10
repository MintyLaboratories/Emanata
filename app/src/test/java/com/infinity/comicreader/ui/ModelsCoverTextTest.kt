package com.infinity.comicreader.ui

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.R
import com.infinity.comicreader.ai.CoverTextDetectors
import com.infinity.comicreader.ai.CoverTextDetectors.Status
import com.infinity.comicreader.ai.CoverTextDetectors.Use
import com.infinity.comicreader.ai.CoverTextDetectors.Why
import com.infinity.comicreader.ai.PlayServices
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.testing.coverText
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The model manager lists the cover text detector in use, and why ML Kit is not. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ModelsCoverTextTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    private class Play : PlayServices {
        override fun available() = true

        override fun moduleInstalled(): Boolean? = true

        override fun requestModule(done: (Boolean) -> Unit) = done(true)
    }

    @Before
    fun setUp() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        val prefs = Prefs(context)
        CoverTextDetectors.standIn(CoverTextDetectors({ prefs.coverMlKit }, Play(), { coverText("mlkit") { null } }, { coverText("ppocr") { null } }))
    }

    @After
    fun tearDown() = CoverTextDetectors.standIn(null)

    private fun texts(view: View): List<String> = when (view) {
        is TextView -> if (view.isShown) listOf(view.text.toString()) else emptyList()
        is ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun shown(): List<String> {
        val activity = Robolectric.buildActivity(ModelsActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        return texts(activity.findViewById(R.id.models_list))
    }

    @Test
    fun ppOcrIsListedInUseByDefault() {
        val texts = shown()
        val at = texts.indexOf(context.getString(R.string.cover_text_section))
        assertTrue(texts.toString(), at >= 0)
        assertEquals(
            listOf(R.string.cover_text_ppocr, R.string.cover_text_details_ppocr, R.string.cover_text_in_use).map { context.getString(it) },
            texts.subList(at + 1, at + 4)
        )
    }

    @Test
    fun mlKitIsListedOnceTurnedOn() {
        Prefs(context).coverMlKit = true
        val texts = shown()
        assertTrue(texts.toString(), context.getString(R.string.cover_text_mlkit) in texts)
        assertTrue(texts.toString(), context.getString(R.string.cover_text_in_use_mlkit) in texts)
    }

    @Test
    fun eachStatusHasItsWords() {
        fun row(s: Status) = ModelText.coverText(context, s)
        assertEquals(context.getString(R.string.cover_text_downloading), row(Status(Use.ML_KIT, null, downloading = true)).status)
        assertEquals(context.getString(R.string.cover_text_mlkit), row(Status(Use.ML_KIT, null, downloading = true)).title)
        assertEquals(context.getString(R.string.cover_text_in_use), row(Status(Use.PP_OCR, Why.OFF)).status)
        assertEquals(context.getString(R.string.cover_text_in_use), row(Status(Use.PP_OCR, Why.UNAVAILABLE)).status)
        assertEquals(context.getString(R.string.cover_text_held_back), row(Status(Use.PP_OCR, Why.HELD_BACK)).status)
        assertEquals(context.getString(R.string.cover_text_module_failed), row(Status(Use.PP_OCR, Why.MODULE_FAILED)).status)
        assertEquals(context.getString(R.string.cover_text_failed), row(Status(Use.PP_OCR, Why.FAILED)).status)
        assertEquals(context.getString(R.string.cover_text_ppocr), row(Status(Use.PP_OCR, Why.FAILED)).title)
    }
}
