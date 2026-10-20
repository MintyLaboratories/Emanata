package com.infinity.comicreader.ui

import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.infinity.comicreader.R
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
    fun licenseRowShowsTheLicense() {
        val about = Robolectric.buildActivity(AboutActivity::class.java).setup().get()
        val links = about.findViewById<LinearLayout>(R.id.about_links)
        links.getChildAt(links.titles().indexOf(about.getString(R.string.about_license))).performClick()
        shadowOf(about.mainLooper).idle()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        val message = dialog.findViewById<TextView>(android.R.id.message)!!.text.toString()
        assertTrue(message.contains("Copyright (c) 2026 Emanata Comic Reader contributors"))
    }

    @Test
    fun linkRowsOpenTheirAddress() {
        val about = Robolectric.buildActivity(AboutActivity::class.java).setup().get()
        about.findViewById<LinearLayout>(R.id.about_notices).getChildAt(0).performClick()
        val started = shadowOf(about).nextStartedActivity
        assertEquals(Notices.all[0].url, started.dataString)
    }
}
