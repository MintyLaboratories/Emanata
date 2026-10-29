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
import org.junit.Assert.assertNull
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
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

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
    fun cancelWhileCoversAreMadeSendsNothing() {
        val taps = Semaphore(0)
        BookShare.coverHold = { taps.tryAcquire(10, TimeUnit.SECONDS) }
        try {
            Prefs(context).shareChoice = ShareChoice(title = true, cover = true, file = false)
            val before = BookShare.coversMade
            var sent = 0
            share.show(listOf(book("first.cbz", 100), book("second.cbz", 50))) { sent++ }
            idle()
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            idle()
            // Counted as they are made; still selected, and the dialog stays until they are.
            assertEquals(context.getString(R.string.share_making_covers, 1, 2), dialog.findViewById<TextView>(R.id.share_cover_none)!!.text.toString())
            assertTrue(dialog.isShowing)
            assertEquals(0, sent)
            dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
            taps.release(2)
            repeat(20) { idle() }
            assertNull(shadowOf(context).nextStartedActivity)
            assertEquals(0, sent)
            // The second cover was never begun.
            assertEquals(before + 1, BookShare.coversMade)
        } finally {
            BookShare.coverHold = {}
        }
    }

    @Test
    fun cancelAfterShareOfOneSendsNothing() {
        val taps = Semaphore(0)
        BookShare.coverHold = { taps.tryAcquire(10, TimeUnit.SECONDS) }
        try {
            Prefs(context).shareChoice = ShareChoice(title = true, cover = true, file = false)
            var sent = 0
            share.show(listOf(book("first.cbz", 100))) { sent++ }
            idle()
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            idle()
            dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
            taps.release()
            repeat(20) { idle() }
            assertNull(shadowOf(context).nextStartedActivity)
            assertEquals(0, sent)
        } finally {
            BookShare.coverHold = {}
        }
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
