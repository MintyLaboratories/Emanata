package com.infinity.comicreader.ui

import com.infinity.comicreader.testing.bookId
import com.infinity.comicreader.testing.idleUntil
import android.Manifest
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.view.View
import android.widget.SeekBar
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.viewpager2.widget.ViewPager2
import com.infinity.comicreader.R
import com.infinity.comicreader.data.HistoryItem
import com.infinity.comicreader.data.Library
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.ResumeMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A book another app opens with this one, by path or by content URI, resumes where it was left, as from the library. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class OpenFromOtherAppsResumeTest {

    private lateinit var context: Application
    private lateinit var prefs: Prefs
    private lateinit var library: Library
    private lateinit var book: File
    private val bytes = cbz()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        shadowOf(context).grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE)
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        for (name in listOf("ui", "library", "favorite_kinds")) context.getSharedPreferences(name, 0).edit().clear().commit()
        prefs = Prefs(context)
        prefs.resumeMode = ResumeMode.YES
        library = Library(context)
        val dir = File(Environment.getExternalStorageDirectory(), "shelf").apply { mkdirs() }
        book = File(dir, "one.cbz").apply { writeBytes(bytes) }
    }

    /** No work of this test's screens runs on into the next. */
    @After
    fun awaitLeftovers() = awaitLeftoverWork()

    /** A comic of [PAGES] one-byte pages. */
    private fun cbz(): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip ->
            repeat(PAGES) {
                zip.putNextEntry(ZipEntry("p%02d.png".format(it)))
                zip.write(byteArrayOf(0))
                zip.closeEntry()
            }
        }
    }.toByteArray()

    private fun savePosition(page: Int) =
        library.addHistory(HistoryItem(book.absolutePath, book.name, page, PAGES, 1L, bookId(book)))

    /** Hands [uri] to the library screen as another app's "Open with" does; returns the reader it starts. */
    private fun handIn(uri: Uri): Intent {
        val list = Robolectric.buildActivity(ListActivity::class.java, Intent(Intent.ACTION_VIEW, uri)).setup().get()
        var started: Intent? = null
        idleUntil("the reader started") { started = shadowOf(list).nextStartedActivity; started != null }
        val intent = started!!
        assertEquals(ViewerActivity::class.java.name, intent.component?.className)
        return intent
    }

    /** Another app's URI named [name] for [book]'s bytes, served by its provider. */
    private fun contentUri(name: String): Uri {
        val uri = Uri.parse("content://elsewhere.documents/document/$name")
        shadowOf(context.contentResolver).registerInputStreamSupplier(uri) { bytes.inputStream() }
        return uri
    }

    /** Opens the reader [intent] asks for and waits until the book shows. */
    private fun read(intent: Intent): ActivityController<ViewerActivity> {
        val controller = Robolectric.buildActivity(ViewerActivity::class.java, intent).setup()
        val progress = controller.get().findViewById<View>(R.id.viewer_progress)
        idleUntil("the book open") { progress.visibility != View.VISIBLE }
        return controller
    }

    private fun ActivityController<ViewerActivity>.page(): Int = get().findViewById<SeekBar>(R.id.viewer_seek).progress

    @Test
    fun aBookOpenedByPathResumes() {
        savePosition(5)
        val intent = handIn(Uri.fromFile(book))
        assertEquals(book.canonicalPath, intent.getStringExtra(ViewerActivity.EXTRA_PATH))
        assertEquals(5, read(intent).page())
    }

    @Test
    fun aBookOpenedByContentUriResumesAndStaysWhereItIs() {
        savePosition(5)
        val intent = handIn(contentUri("copy.cbz"))
        val copy = File(intent.getStringExtra(ViewerActivity.EXTRA_PATH)!!)
        assertTrue("$copy is not a copy in the cache", copy.path.startsWith(context.cacheDir.path))
        val reader = read(intent)
        assertEquals(5, reader.page())

        // Reading the copy moves the book's position, never where the library keeps it.
        reader.get().findViewById<ViewPager2>(R.id.viewer_pager).setCurrentItem(6, false)
        reader.pause().stop()
        val id = bookId(book)
        assertEquals(6, library.pageFor(id))
        assertEquals(book.absolutePath, library.pathOf(id))
        assertEquals(book.absolutePath, library.historyFor(id)!!.path)
        reader.destroy()
    }

    @Test
    fun aBookmarkOnAHandedInCopyKeepsTheBookWhereItIs() {
        savePosition(5)
        val reader = read(handIn(contentUri("copy.cbz")))
        shadowOf(reader.get()).clickMenuItem(R.id.menu_bookmark)
        reader.pause().stop()
        val id = bookId(book)
        assertEquals(book.absolutePath, library.pathOf(id))
        assertEquals(book.absolutePath, library.bookmarksFor(id).single().path)
        assertEquals(book.absolutePath, library.historyFor(id)!!.path)
        reader.destroy()
    }

    @Test
    fun aBookSeenNowhereElseResumesWhenHandedInAgain() {
        val id = bookId(book)
        book.delete()
        val first = read(handIn(contentUri("only.cbz")))
        assertEquals(0, first.page())
        first.get().findViewById<ViewPager2>(R.id.viewer_pager).setCurrentItem(3, false)
        first.pause().stop().destroy()
        assertEquals(3, library.pageFor(id))

        assertEquals(3, read(handIn(contentUri("only.cbz"))).page())
    }

    @Test
    fun aBookSeenNowhereElseIsKeptAtItsLatestCopy() {
        val id = bookId(book)
        book.delete()
        read(handIn(contentUri("only.cbz"))).apply {
            get().findViewById<ViewPager2>(R.id.viewer_pager).setCurrentItem(3, false)
            pause().stop().destroy()
        }

        // Handed in again under another name: the first copy is gone, so the library follows the new one.
        val intent = handIn(contentUri("renamed.cbz"))
        val copy = File(intent.getStringExtra(ViewerActivity.EXTRA_PATH)!!)
        val reader = read(intent)
        assertEquals(3, reader.page())
        reader.get().findViewById<ViewPager2>(R.id.viewer_pager).setCurrentItem(4, false)
        reader.pause().stop().destroy()
        assertEquals(4, library.pageFor(id))
        assertEquals(copy.absolutePath, library.pathOf(id))
        assertEquals(copy.absolutePath, library.historyFor(id)!!.path)
    }

    private companion object {
        const val PAGES = 8
    }
}
