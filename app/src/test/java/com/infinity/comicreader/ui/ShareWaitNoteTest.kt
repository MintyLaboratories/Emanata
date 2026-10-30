package com.infinity.comicreader.ui

import android.view.LayoutInflater
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The reader's note while a share answers nothing reads as a sentence, with no placeholder left in it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShareWaitNoteTest {
    @Test
    fun readerNoteSaysWaitingForNetworkLocation() {
        val context = ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.Theme_ComicReader_Viewer)
        val root = LayoutInflater.from(context).inflate(R.layout.activity_viewer, null)
        val note = root.findViewById<TextView>(R.id.viewer_share_wait_text)
        assertEquals("Waiting for network location…", note.text.toString())
    }
}
