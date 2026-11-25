package minty.emanata.ui

import android.app.Application
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.test.core.app.ApplicationProvider
import minty.emanata.R
import minty.emanata.data.BookEntry
import minty.emanata.data.EntryType
import minty.emanata.data.Prefs
import minty.emanata.data.ShareChoice
import minty.emanata.files.BookUris
import minty.emanata.testing.awaitUntil
import minty.emanata.testing.idleUntil
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        // No cover still being made runs on into the next test.
        awaitShareWork()
        controller.pause().stop().destroy()
    }

    /** Runs what is due on the main thread: what a dialog or a click posts there. */
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** Waits until the screen runs no share work: a cover made, written or sent ends only once its blocking part returns. */
    private fun awaitShareWork() = idleUntil("the share's work ended") {
        controller.get().lifecycleScope.coroutineContext[Job]!!.children.none { !it.isCompleted }
    }

    private fun awaitChooser() = idleUntil("the chooser started") { shadowOf(context).peekNextStartedActivity() != null }

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
        // Many apps drop the text beside files: the titles are on the clipboard too, and it says so.
        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
        assertEquals("first\nsecond\nSeries", clipboard.primaryClip!!.getItemAt(0).text.toString())
        assertEquals(context.getString(R.string.share_titles_copied), org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun oneBooksTitleGoingWithItsFileIsCopiedToo() {
        Prefs(context).shareChoice = ShareChoice(title = true, cover = false, file = true)
        share.show(listOf(book("single.cbz", 100)))
        idle()
        (ShadowDialog.getLatestDialog() as AlertDialog).getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        awaitChooser()
        @Suppress("DEPRECATION")
        val send = shadowOf(context).nextStartedActivity.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals("single", send.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals("single", context.getSystemService(android.content.ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString())
        assertEquals(context.getString(R.string.share_titles_copied), org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun cancelWhileCoversAreMadeSendsNothing() {
        val begun = Semaphore(0)
        val taps = Semaphore(0)
        val held = Semaphore(0)
        BookShare.coverHold = {
            begun.release()
            taps.acquire()
            held.release()
        }
        try {
            Prefs(context).shareChoice = ShareChoice(title = true, cover = true, file = false)
            val before = BookShare.coversMade
            var sent = 0
            share.show(listOf(book("first.cbz", 100), book("second.cbz", 50))) { sent++ }
            idle()
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            // The first cover under way, however long the I/O threads take to get to it.
            idleUntil("the first cover begun") { begun.tryAcquire() }
            idle()
            // Counted as they are made; still selected, and the dialog stays until they are.
            assertEquals(context.getString(R.string.share_making_covers, 1, 2), dialog.findViewById<TextView>(R.id.share_cover_none)!!.text.toString())
            assertTrue(dialog.isShowing)
            assertEquals(0, sent)
            dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
            taps.release(2)
            awaitUntil("the first cover let go") { held.tryAcquire() }
            // Canceled before the cover was let go: nothing after it runs.
            awaitShareWork()
            idle()
            assertNull(shadowOf(context).nextStartedActivity)
            assertEquals(0, sent)
            // The second cover was never begun.
            assertFalse(begun.tryAcquire())
            assertEquals(before + 1, BookShare.coversMade)
        } finally {
            BookShare.coverHold = {}
            taps.release(2)
        }
    }

    @Test
    fun cancelAfterShareOfOneSendsNothing() {
        val taps = Semaphore(0)
        BookShare.coverHold = { taps.acquire() }
        try {
            Prefs(context).shareChoice = ShareChoice(title = true, cover = true, file = false)
            var sent = 0
            share.show(listOf(book("first.cbz", 100))) { sent++ }
            idle()
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            idle()
            dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
            // Closed, and so canceled, before the cover is let go: nothing after it runs.
            idle()
            taps.release()
            awaitShareWork()
            idle()
            assertNull(shadowOf(context).nextStartedActivity)
            assertEquals(0, sent)
        } finally {
            BookShare.coverHold = {}
            taps.release()
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
        // Titles alone go as text only, the clipboard untouched.
        assertFalse(context.getSystemService(android.content.ClipboardManager::class.java).hasPrimaryClip())
        // The file choice stays for books.
        assertTrue(Prefs(context).shareChoice.file)
    }
}
