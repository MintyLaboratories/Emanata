package com.infinity.comicreader.ui

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.R
import com.infinity.comicreader.ai.DownloadFailure
import com.infinity.comicreader.ai.DownloadState
import com.infinity.comicreader.ai.ModelCatalog
import com.infinity.comicreader.ai.ModelStore
import com.infinity.comicreader.ui.ModelText.DetectorFiles
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
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
        val end = System.currentTimeMillis() + 10_000
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            val status = activity.findViewById<View>(R.id.cover_text_card)?.findViewById<TextView>(R.id.status)
            if (status != null && status.text.isNotEmpty()) return activity
            assertTrue("the cover text row tells nothing", System.currentTimeMillis() < end)
            Thread.sleep(10)
        }
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
    fun ppOcrWithoutItsModelSaysCoversCropAsTopAndOffersItsDownload() {
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
