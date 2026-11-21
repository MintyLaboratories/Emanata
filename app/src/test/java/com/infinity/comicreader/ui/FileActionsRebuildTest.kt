package com.infinity.comicreader.ui

import android.app.Application
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.R
import com.infinity.comicreader.data.Library
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.files.FileWork
import com.infinity.comicreader.testing.idleUntil
import com.infinity.comicreader.util.FileUtils
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast
import java.io.IOException

/** What a rebuild tells once it ends: the bytes saved and the pages re-encoded, also when it failed or was canceled. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FileActionsRebuildTest {
    private lateinit var context: Application
    private lateinit var controller: ActivityController<AboutActivity>

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("ui", 0).edit().clear().commit()
        FileWork.reset()
        controller = Robolectric.buildActivity(AboutActivity::class.java).create()
        FileActions(controller.get(), Library(context), Prefs(context), { "Device" }, {}, {})
        controller.start().resume()
    }

    @After
    fun tearDown() {
        controller.pause().stop().destroy()
    }

    private fun settle() = idleUntil("every operation done") { FileWork.operations.value.isEmpty() }

    private fun message(dialog: AlertDialog) = dialog.findViewById<android.widget.TextView>(android.R.id.message)!!.text.toString()

    private fun reencoded(text: String, pages: Int) = context.resources.getQuantityString(R.plurals.rebuild_reencoded, pages, text, pages)

    @Test
    fun pagesReencodedAreCountedNeverListed() {
        val summary = FileWork.Summary(0, reencoded = mapOf("Book A.cbz" to listOf("3.webp", "7.webp"), "Book B.cbz" to listOf("1.webp")))
        FileWork.start(FileWork.Kind.COMPATIBLE, "Book A.cbz", 2) { FileWork.Outcome.Done("/x/Book B.cbz", count = 2, summary = summary) }
        settle()
        assertEquals(reencoded(context.resources.getQuantityString(R.plurals.compatible_count, 2, 2), 3), ShadowToast.getTextOfLatestToast())
        assertNull(ShadowDialog.getLatestDialog())
        assertFalse(ShadowToast.getTextOfLatestToast().contains("90"))
    }

    @Test
    fun pagesReencodedAreCountedWhenAnotherBookFails() {
        val summary = FileWork.Summary(0, reencoded = mapOf("Book A.cbz" to listOf("3.webp", "7.webp")))
        FileWork.start(FileWork.Kind.COMPATIBLE, "Book A.cbz", 2) { FileWork.Outcome.Failed(IOException("broken"), 1, 2, summary = summary) }
        settle()
        val part = reencoded(context.resources.getQuantityString(R.plurals.compatible_part, 2, 1, 2), 2)
        assertEquals(context.getString(R.string.file_failed_part, part, "broken"), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun pagesReencodedAreCountedWhenCanceled() {
        val summary = FileWork.Summary(0, reencoded = mapOf("Book A.cbz" to listOf("2.webp")))
        FileWork.start(FileWork.Kind.COMPATIBLE, "Book A.cbz", 3) { FileWork.Outcome.Canceled(1, 3, summary = summary) }
        settle()
        val part = reencoded(context.resources.getQuantityString(R.plurals.compatible_part, 3, 1, 3), 1)
        assertEquals(context.getString(R.string.file_canceled_part, part), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun pagesNotConvertedAreCountedAndListed() {
        val summary = FileWork.Summary(0, failed = mapOf("Book A.cbz" to listOf("4.jxl")))
        FileWork.start(FileWork.Kind.COMPATIBLE, "Book A.cbz", 1) { FileWork.Outcome.Done("/x/Book A.cbz", count = 1, summary = summary) }
        settle()
        val made = context.resources.getQuantityString(R.plurals.compatible_count, 1, 1)
        assertEquals(context.resources.getQuantityString(R.plurals.rebuild_failed_pages, 1, made, 1), ShadowToast.getTextOfLatestToast())
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertTrue(message(dialog).startsWith(context.getString(R.string.unconverted_compact)))
        assertTrue(message(dialog).contains(context.getString(R.string.reencoded_book, "Book A.cbz", "4.jxl")))
    }

    @Test
    fun aBookHoldingArchivesIsSaidSo() {
        FileWork.start(FileWork.Kind.COMPACT, "Box.cbz", 1) { FileWork.Outcome.Done(null, count = 0, summary = FileWork.Summary(0, unchanged = 1, nested = 1)) }
        settle()
        assertEquals(context.resources.getQuantityString(R.plurals.rebuild_nested, 1, 1), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun bytesSavedAreToldWhenCompactIsCanceled() {
        val saved = 5L * 1024 * 1024
        FileWork.start(FileWork.Kind.COMPACT, "Book A.cbz", 3) { FileWork.Outcome.Canceled(2, 3, summary = FileWork.Summary(saved)) }
        settle()
        val part = context.resources.getQuantityString(R.plurals.compacted_part, 3, 2, 3)
        val text = context.getString(R.string.rebuild_saved, part, FileUtils.formatSize(context, saved))
        assertEquals(context.getString(R.string.file_canceled_part, text), ShadowToast.getTextOfLatestToast())
    }
}
