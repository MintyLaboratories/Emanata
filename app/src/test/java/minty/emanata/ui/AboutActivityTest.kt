package minty.emanata.ui

import android.content.DialogInterface
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import minty.emanata.R
import minty.emanata.util.Notices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AboutActivityTest {

    private fun LinearLayout.titles() =
        (0 until childCount).map { getChildAt(it).findViewById<TextView>(R.id.title).text.toString() }

    @Test
    fun listsLinksAndEveryNotice() {
        val about = Robolectric.buildActivity(AboutActivity::class.java).setup().get()
        val links = about.findViewById<LinearLayout>(R.id.about_links).titles()
        assertTrue(links.contains(about.getString(R.string.about_upstream)))
        assertEquals(Notices.all.map { it.name }, about.findViewById<LinearLayout>(R.id.about_notices).titles())
    }

    @Test
    fun noBlurbBetweenTheVersionAndTheLinks() {
        val about = Robolectric.buildActivity(AboutActivity::class.java).setup().get()
        fun texts(v: android.view.View): List<String> = when (v) {
            is android.view.ViewGroup -> (0 until v.childCount).flatMap { texts(v.getChildAt(it)) }
            is TextView -> listOf(v.text.toString())
            else -> emptyList()
        }
        assertTrue(texts(about.window.decorView).none { it.contains("works offline") || it.startsWith("A comic & PDF reader") })
    }

    @Test
    fun versionChipShowsOnlyTheVersion() {
        val about = Robolectric.buildActivity(AboutActivity::class.java).setup().get()
        val chip = about.findViewById<TextView>(R.id.about_version).text.toString()
        assertTrue(chip.startsWith("Version "))
        assertFalse(chip.contains(about.getString(R.string.app_name)))
    }

    @Test
    fun licenseRowShowsTheLicense() {
        val about = Robolectric.buildActivity(AboutActivity::class.java).setup().get()
        val links = about.findViewById<LinearLayout>(R.id.about_links)
        val row = links.getChildAt(links.titles().indexOf(about.getString(R.string.about_license)))
        assertTrue(row.findViewById<TextView>(R.id.subtitle).text.startsWith("GPL 3.0 or later"))
        row.performClick()
        shadowOf(about.mainLooper).idle()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        val message = dialog.findViewById<TextView>(android.R.id.message)!!.text.toString()
        assertTrue(message.startsWith("Emanata Comic Reader\nCopyright (C) 2026 Emanata Comic Reader contributors"))
        val order = listOf(
            "either version 3 of the License, or (at your option) any later version.",
            "TERMS AND CONDITIONS",
            "Copyright (c) 2026 ComicReader contributors\n\nPermission is hereby granted"
        ).map { message.indexOf(it) }
        assertTrue("$order", order.all { it >= 0 } && order == order.sorted())
    }

    @Test
    fun tipButtonOpensTheTipPage() {
        val about = Robolectric.buildActivity(AboutActivity::class.java).setup().get()
        about.findViewById<android.view.View>(R.id.about_tip).performClick()
        assertEquals(Notices.TIP_URL, shadowOf(about).nextStartedActivity.dataString)
    }

    /** Taps the row of the notice named [name] and returns the dialog it opened. */
    private fun AboutActivity.tapNotice(name: String): AlertDialog {
        val notices = findViewById<LinearLayout>(R.id.about_notices)
        notices.getChildAt(notices.titles().indexOf(name)).performClick()
        shadowOf(mainLooper).idle()
        return ShadowDialog.getLatestDialog() as AlertDialog
    }

    @Test
    fun noticeRowShowsItsLicenseTexts() {
        val about = Robolectric.buildActivity(AboutActivity::class.java).setup().get()
        val dialog = about.tapNotice("Apache Commons Compress")
        val text = dialog.findViewById<TextView>(R.id.notice_text)!!.text.toString()
        assertTrue(text.contains("Copyright 2002-2024 The Apache Software Foundation"))
        assertTrue(text.contains("TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION"))
        assertFalse(dialog.findViewById<TextView>(R.id.notice_note)!!.isShown)
    }

    @Test
    fun noticeNoteShowsAboveTheText() {
        val about = Robolectric.buildActivity(AboutActivity::class.java).setup().get()
        val dialog = about.tapNotice("libpng")
        val note = dialog.findViewById<TextView>(R.id.notice_note)!!
        assertTrue(note.isShown)
        assertTrue(note.text.contains("Max Stepin"))
        assertTrue(dialog.findViewById<TextView>(R.id.notice_text)!!.text.contains("Cosmin Truta"))
    }

    @Test
    fun noticeDialogOpensTheProjectPage() {
        val about = Robolectric.buildActivity(AboutActivity::class.java).setup().get()
        about.tapNotice(Notices.all[0].name).getButton(DialogInterface.BUTTON_NEUTRAL).performClick()
        shadowOf(about.mainLooper).idle()
        val started = shadowOf(about).nextStartedActivity
        assertEquals(Notices.all[0].url, started.dataString)
    }
}
