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
import com.infinity.comicreader.ai.DownloadFailure
import com.infinity.comicreader.ai.DownloadState
import com.infinity.comicreader.ai.ModelCatalog
import com.infinity.comicreader.ai.ModelStore
import com.infinity.comicreader.ai.PlayServices
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.testing.coverText
import com.infinity.comicreader.ui.ModelText.DetectorFiles
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

        override fun requestModule(done: (Boolean) -> Unit): () -> Unit {
            done(true)
            return {}
        }
    }

    @Before
    fun setUp() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        val prefs = Prefs(context)
        CoverTextDetectors.standIn(CoverTextDetectors({ prefs.coverMlKit }, Play(), CoverTextDetectors.MlKit("mlkit") { coverText("mlkit") { null } }, { coverText("ppocr") { null } }))
    }

    @After
    fun tearDown() {
        CoverTextDetectors.standIn(null)
        ModelStore.of(context).delete(ModelCatalog.TEXT_DETECTOR)
    }

    /** Puts PP-OCR's model in the app's store, verified. */
    private fun placeDetector() {
        val store = ModelStore.of(context)
        val d = ModelCatalog.TEXT_DETECTOR
        store.dir(d).mkdirs()
        for (f in d.files) {
            java.io.RandomAccessFile(store.target(d, f), "rw").use { it.setLength(f.bytes) }
            store.stamped(d, f)
        }
    }

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
        placeDetector()
        val texts = shown()
        val at = texts.indexOf(context.getString(R.string.cover_text_section))
        assertTrue(texts.toString(), at >= 0)
        assertEquals(
            listOf(R.string.cover_text_ppocr, R.string.cover_text_details_ppocr, R.string.cover_text_in_use).map { context.getString(it) },
            texts.subList(at + 1, at + 4)
        )
    }

    @Test
    fun ppOcrWithoutItsModelSaysCoversCropAsTopAndOffersItsDownload() {
        val activity = Robolectric.buildActivity(ModelsActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val card = activity.findViewById<View>(R.id.cover_text_card)
        val texts = texts(card)
        val missing = context.getString(R.string.cover_text_ppocr_missing, ModelText.size(context, ModelCatalog.TEXT_DETECTOR.bytes))
        assertTrue(texts.toString(), missing in texts)
        assertTrue(texts.toString(), context.getString(R.string.ai_download) in texts)
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

    @Test
    fun ppOcrsModelHasItsWordsWhileItIsNotThere() {
        fun row(f: DetectorFiles, s: Status = Status(Use.PP_OCR, Why.OFF)) = ModelText.coverText(context, s, f)
        val missing = row(DetectorFiles(false))
        assertEquals(context.getString(R.string.cover_text_ppocr_missing, ModelText.size(context, ModelCatalog.TEXT_DETECTOR.bytes)), missing.status)
        assertTrue(missing.download)
        val running = row(DetectorFiles(false, DownloadState.Running(50, 200)))
        assertEquals(context.getString(R.string.cover_text_ppocr_downloading, 25), running.status)
        assertEquals(25, running.progress)
        assertFalse(running.download)
        val metered = row(DetectorFiles(false, DownloadState.Failed(DownloadFailure.METERED)))
        assertEquals(context.getString(R.string.cover_text_ppocr_failed, ModelText.failure(context, DownloadFailure.METERED)), metered.status)
        assertTrue(metered.download)
        // In place, or ML Kit in use: the detector's own words.
        assertEquals(context.getString(R.string.cover_text_in_use), row(DetectorFiles(true)).status)
        assertEquals(context.getString(R.string.cover_text_in_use_mlkit), row(DetectorFiles(false), Status(Use.ML_KIT, null)).status)
        assertFalse(row(DetectorFiles(true)).download)
    }
}
