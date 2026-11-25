package minty.emanata.ui

import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import minty.emanata.R
import minty.emanata.ai.DownloadFailure
import minty.emanata.ai.DownloadState
import minty.emanata.ai.ModelCatalog
import minty.emanata.ai.ModelStore
import minty.emanata.testing.idleUntil
import minty.emanata.ui.ModelText.DetectorFiles
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The model manager lists the cover text detector, PP-OCR, and where its model is. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ModelsCoverTextTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @After
    fun tearDown() {
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

    /** The Models screen, once its cover text row tells where PP-OCR's model is (looked at off the main thread). */
    private fun opened(): ModelsActivity {
        val activity = Robolectric.buildActivity(ModelsActivity::class.java).setup().get()
        idleUntil("the cover text row's status") {
            val status = activity.findViewById<View>(R.id.cover_text_card)?.findViewById<TextView>(R.id.status)
            status != null && status.text.isNotEmpty()
        }
        return activity
    }

    private fun shown(): List<String> = texts(opened().findViewById(R.id.models_list))

    @Test
    fun ppOcrIsListedInUse() {
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
    fun ppOcrsRowLeavesItsLicenseToATapWhichShowsSizeLicenseAndSource() {
        placeDetector()
        val row = opened().findViewById<View>(R.id.cover_text_card).findViewById<ViewGroup>(R.id.card_rows).getChildAt(0)
        val details = row.findViewById<TextView>(R.id.subtitle).text.toString()
        assertFalse(details, details.contains("Apache"))
        val info = row.findViewById<View>(R.id.model_info)
        assertEquals(View.GONE, info.visibility)
        row.performClick()
        assertEquals(View.VISIBLE, info.visibility)
        val text = row.findViewById<TextView>(R.id.model_info_text).text.toString()
        assertEquals(ModelText.coverTextInfo(context), text)
        for (part in listOf(
            ModelText.size(context, ModelCatalog.TEXT_DETECTOR.bytes), ModelCatalog.DETECTOR_LICENSE, ModelCatalog.DETECTOR_AUTHOR,
            ModelCatalog.DETECTOR_SOURCE, ModelCatalog.DETECTOR_REPOSITORY
        )) assertTrue(part, text.contains(part))
        assertEquals(View.VISIBLE, row.findViewById<View>(R.id.model_info_link).visibility)
        row.performClick()
        assertEquals(View.GONE, info.visibility)
    }

    @Test
    fun ppOcrWithoutItsModelSaysWraparoundsShowWholeAndOffersItsDownload() {
        val card = opened().findViewById<View>(R.id.cover_text_card)
        val texts = texts(card)
        val missing = context.getString(R.string.cover_text_ppocr_missing, ModelText.size(context, ModelCatalog.TEXT_DETECTOR.bytes))
        assertTrue(texts.toString(), missing in texts)
        assertTrue(texts.toString(), context.getString(R.string.ai_download) in texts)
    }

    @Test
    fun ppOcrsModelHasItsWordsWhileItIsNotThere() {
        fun row(f: DetectorFiles) = ModelText.coverText(context, f)
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
        // Each of those says what waits for the model: a barcodeless wraparound's front and every wraparound's reading direction.
        for (status in listOf(missing, running, metered).map { it.status }) {
            assertTrue(status, status.contains("without a barcode shows whole"))
            assertTrue(status, status.contains("reading direction"))
        }
        // In place: the detector's own words.
        assertEquals(context.getString(R.string.cover_text_in_use), row(DetectorFiles(true)).status)
        assertFalse(row(DetectorFiles(true)).download)
    }

    @Test
    fun beforeItsModelIsLookedAtTheRowOffersNothing() {
        val row = ModelText.coverText(context, null)
        assertEquals("", row.status)
        assertFalse(row.download)
        assertEquals(null, row.progress)
    }
}
