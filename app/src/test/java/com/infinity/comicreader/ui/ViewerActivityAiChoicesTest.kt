package com.infinity.comicreader.ui

import android.app.Application
import android.content.Intent
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.R
import com.infinity.comicreader.ai.AiStep
import com.infinity.comicreader.ai.ModelCatalog
import com.infinity.comicreader.ai.ModelStore
import com.infinity.comicreader.ai.OfflineDownloads
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.ResumeMode
import com.infinity.comicreader.reader.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.io.File

/** The reader sheet's AI pickers: each model with what it does, slow ones only with advanced settings shown. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ViewerActivityAiChoicesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Picking a model starts its download: kept off the network and ended with the test. */
    @get:Rule
    val downloads = OfflineDownloads()

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var prefs: Prefs

    @Before
    fun setUp() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        prefs = Prefs(context)
        prefs.resumeMode = ResumeMode.NO
        ModelStore.of(context).root.deleteRecursively()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun open(): ViewerActivity {
        val pages = (0 until 3).map { Fixtures.entry("p%02d.png".format(it), byteArrayOf(0)) }
        val book = Fixtures.zip(File(tmp.newFolder(), "Sample v01.cbz"), *pages.toTypedArray())
        val intent = Intent(context, ViewerActivity::class.java).putExtra(ViewerActivity.EXTRA_PATH, book.absolutePath)
        val viewer = Robolectric.buildActivity(ViewerActivity::class.java, intent).setup().get()
        val progress = viewer.findViewById<View>(R.id.viewer_progress)
        val deadline = System.currentTimeMillis() + 5_000
        do {
            Thread.sleep(10)
            idle()
        } while (progress.visibility == View.VISIBLE && System.currentTimeMillis() < deadline)
        return viewer
    }

    /** The upscale picker's dialog, opened from the sheet, and its items' text. */
    private fun upscaleChoices(viewer: ViewerActivity): Pair<AlertDialog, List<String>> {
        ReaderSheet(viewer).tap(R.id.sheet_ai_upscale)
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        val adapter = dialog.listView.adapter
        return dialog to (0 until adapter.count).map { adapter.getItem(it).toString() }
    }

    @Test
    fun upscalersShowWhatTheyDoAndTheSlowOnesWaitForAdvancedSettings() {
        val (dialog, items) = upscaleChoices(open())
        val fast = ModelCatalog.forStep(AiStep.UPSCALE).filter { !it.slow }
        assertEquals(listOf(context.getString(R.string.ai_none)) + fast.map { ModelText.entry(context, it).toString() }, items)
        assertTrue(items[1], items[1].endsWith("\nFaithful, fast and reliable clarity improvement (default)"))
        // The default runs, so it is checked.
        assertEquals(1, dialog.listView.checkedItemPosition)
        dialog.dismiss()

        prefs.keepSimple = false
        val (_, all) = upscaleChoices(open())
        assertEquals(ModelCatalog.forStep(AiStep.UPSCALE).size + 1, all.size)
        assertTrue(all.any { it.endsWith("\nSlow (~11 s per page)") })
    }

    @Test
    fun pickingAModelRunsIt() {
        val (dialog, _) = upscaleChoices(open())
        val i = ModelCatalog.offered(AiStep.UPSCALE, true, null).indexOfFirst { it.id == "opencomic-ai-upscale-2x-compact-v2" } + 1
        dialog.listView.performItemClick(null, i, i.toLong())
        idle()
        assertEquals("opencomic-ai-upscale-2x-compact-v2", Prefs(context).aiModel(AiStep.UPSCALE))
        assertTrue(Prefs(context).aiEnabled)
    }
}
