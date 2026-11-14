package com.infinity.comicreader.ui

import android.os.Looper
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.infinity.comicreader.R
import com.infinity.comicreader.ai.ModelCatalog
import com.infinity.comicreader.ai.ModelSource
import com.infinity.comicreader.util.Notices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/** About lists the AI models' sources: who made them, their license and models, and the license text. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AboutModelsTest {

    @Test
    fun everyModelSourceIsListedWithItsLicense() {
        val activity = Robolectric.buildActivity(AboutActivity::class.java).setup().get()
        val list = activity.findViewById<LinearLayout>(R.id.about_models)
        assertEquals(ModelSource.entries.size, list.childCount)
        for ((i, s) in ModelSource.entries.withIndex()) {
            val row = list.getChildAt(i)
            assertEquals(s.title, row.findViewById<TextView>(R.id.title).text.toString())
            assertEquals(s.license, row.findViewById<TextView>(R.id.subtitle).text.toString())
        }
    }

    @Test
    fun aSourceShowsItsAuthorModelsAndLicenseText() {
        val activity = Robolectric.buildActivity(AboutActivity::class.java).setup().get()
        val list = activity.findViewById<LinearLayout>(R.id.about_models)
        list.getChildAt(0).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        val note = dialog.findViewById<TextView>(R.id.notice_note)!!.text.toString()
        val s = ModelSource.entries[0]
        assertTrue(note, note.contains(s.author) && s.models.all { note.contains(it.name) })
        val text = dialog.findViewById<TextView>(R.id.notice_text)!!.text.toString()
        assertTrue(text.contains("Attribution 4.0 International"))
        assertEquals(Notices.models.size, ModelSource.entries.size)
    }

    @Test
    fun aConversionSaysWhereItAndItsSourceComeFrom() {
        val activity = Robolectric.buildActivity(AboutActivity::class.java).setup().get()
        val list = activity.findViewById<LinearLayout>(R.id.about_models)
        list.getChildAt(ModelSource.CUNNY.ordinal).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        val note = dialog.findViewById<TextView>(R.id.notice_note)!!.text.toString()
        assertTrue(note, note.contains("By funnyplanter.") && note.contains("from ${ModelCatalog.HOSTED_REPOSITORY} ") && note.contains(ModelSource.CUNNY.conversion!!))
        // A family with sizes is named once, with its sizes.
        assertTrue(note, note.contains("Models: CuNNy 2× (faster/fast/4x12/4x16/4x24/4x32/8x32)."))
        val artcnn = Notices.models.single { it.name == ModelSource.ARTCNN.title }
        assertTrue(artcnn.note, artcnn.note!!.contains("Models: ArtCNN 2× (C4F16/C4F32)."))
        val text = dialog.findViewById<TextView>(R.id.notice_text)!!.text.toString()
        assertTrue(text.contains("Copyright (c) 2024 funnyplanter") && text.contains("GNU LESSER GENERAL PUBLIC LICENSE") && text.contains("GNU GENERAL PUBLIC LICENSE"))
        val waifu2x = Notices.models.single { it.name == ModelSource.WAIFU2X.title }
        assertTrue(waifu2x.note!!.endsWith("from ${ModelCatalog.REPOSITORY} and run on this device only."))
    }
}
