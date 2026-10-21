package com.infinity.comicreader.ui

import android.app.Application
import android.content.Intent
import android.os.Looper
import android.view.View
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.R
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.ResumeMode
import com.infinity.comicreader.data.StorageRoot
import com.infinity.comicreader.reader.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import org.robolectric.shadows.ShadowToast
import java.io.File

/** Pulling out the drive a book is read from closes it with a message. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ViewerActivityStorageTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var drive: File
    private lateinit var book: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        Prefs(context).resumeMode = ResumeMode.NO
        drive = tmp.newFolder("drive")
        book = Fixtures.zip(File(drive, "book.cbz"), *(0 until 3).map { Fixtures.entry("p$it.png", byteArrayOf(0)) }.toTypedArray())
    }

    private fun open(): ViewerActivity {
        val intent = Intent(ApplicationProvider.getApplicationContext(), ViewerActivity::class.java)
            .putExtra(ViewerActivity.EXTRA_PATH, book.absolutePath)
        val viewer = Robolectric.buildActivity(ViewerActivity::class.java, intent).setup().get()
        val progress = viewer.findViewById<View>(R.id.viewer_progress)
        val deadline = System.currentTimeMillis() + 5_000
        do {
            Thread.sleep(10)
            shadowOf(Looper.getMainLooper()).idle()
        } while (progress.visibility == View.VISIBLE && System.currentTimeMillis() < deadline)
        return viewer
    }

    @Test
    fun removedDriveClosesTheBook() {
        val viewer = open()
        val usb = StorageRoot(drive.absolutePath, "Drive A", StorageRoot.Kind.USB)
        viewer.bookRoots = listOf(usb)
        viewer.checkStorage(now = listOf(usb))
        assertFalse(viewer.isFinishing)
        viewer.checkStorage(now = emptyList())
        assertTrue(viewer.isFinishing)
        assertEquals("Drive A was removed, so the book was closed", ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun otherDrivesComingAndGoingChangeNothing() {
        val viewer = open()
        val usb = StorageRoot(drive.absolutePath, "Drive A", StorageRoot.Kind.USB)
        val other = StorageRoot(tmp.newFolder("other").absolutePath, "Drive B", StorageRoot.Kind.USB)
        viewer.bookRoots = listOf(usb, other)
        viewer.checkStorage(now = listOf(usb))
        assertFalse(viewer.isFinishing)
    }
}
