package com.infinity.comicreader.ui

import android.app.Application
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.R
import com.infinity.comicreader.data.BookEntry
import com.infinity.comicreader.data.EntryType
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.ShareChoice
import com.infinity.comicreader.files.BookUris
import org.junit.After
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
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.io.File

/** Several items shared in one action: one dialog, one intent. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BookShareManyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var context: Application
    private lateinit var controller: ActivityController<AboutActivity>
    private lateinit var share: BookShare

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("ui", 0).edit().clear().commit()
        // Granted: asking would start an activity of its own.
        shadowOf(context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        controller = Robolectric.buildActivity(AboutActivity::class.java).create()
        share = BookShare(controller.get(), Prefs(context))
        controller.start().resume()
    }

    @After
    fun tearDown() {
        controller.pause().stop().destroy()
    }

    private fun idle() = repeat(10) {
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(10)
    }

    private fun awaitChooser() {
        var left = 300
        while (shadowOf(context).peekNextStartedActivity() == null && left-- > 0) idle()
    }

    private fun book(name: String, size: Int) = File(tmp.root, name).apply { writeBytes(ByteArray(size)) }.let {
        BookEntry(it, it.name, false, EntryType.ARCHIVE, it.length(), it.lastModified())
    }

    @Test
    fun titlesAndFilesGoInOneIntent() {
        Prefs(context).shareChoice = ShareChoice(title = true, cover = false, file = true)
        val a = book("first.cbz", 100)
        val b = book("second.cbr", 50)
        val folder = tmp.newFolder("Series").let { BookEntry(it, it.name, true, EntryType.FOLDER, 0, 0) }
        var sent = 0
        share.show(listOf(a, b, folder)) { sent++ }
        idle()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertEquals("first\nsecond\nSeries", dialog.findViewById<TextView>(R.id.share_title_text)!!.text.toString())
        assertEquals(context.resources.getQuantityString(R.plurals.share_covers, 3, 3), dialog.findViewById<TextView>(R.id.share_cover_none)!!.text.toString())
        assertTrue(dialog.findViewById<TextView>(R.id.share_file_text)!!.text.startsWith(context.resources.getQuantityString(R.plurals.share_files, 2, 2)))
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        awaitChooser()
        assertEquals(1, sent)
        val chooser = shadowOf(context).nextStartedActivity
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND_MULTIPLE, send.action)
        assertEquals("first\nsecond\nSeries", send.getStringExtra(Intent.EXTRA_TEXT))
        @Suppress("DEPRECATION")
        val streams = send.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)!!
        assertEquals(listOf(a.path, b.path), streams.map { BookUris.pathOf(context, it) })
        assertEquals(2, send.clipData!!.itemCount)
    }

    @Test
    fun foldersAloneSendNoFile() {
        Prefs(context).shareChoice = ShareChoice(title = true, cover = false, file = true)
        val folders = listOf("One", "Two").map { tmp.newFolder(it) }.map { BookEntry(it, it.name, true, EntryType.FOLDER, 0, 0) }
        share.show(folders)
        idle()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertEquals(View.GONE, dialog.findViewById<View>(R.id.share_file_row)!!.visibility)
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        awaitChooser()
        @Suppress("DEPRECATION")
        val send = shadowOf(context).nextStartedActivity.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("One\nTwo", send.getStringExtra(Intent.EXTRA_TEXT))
        // The file choice stays for books.
        assertTrue(Prefs(context).shareChoice.file)
    }
}
