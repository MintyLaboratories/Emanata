package com.infinity.comicreader.ui

import android.app.Application
import android.os.Looper
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.R
import com.infinity.comicreader.data.Library
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.files.FileWork
import com.infinity.comicreader.util.FileUtils
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

    private fun settle() {
        repeat(300) {
            shadowOf(Looper.getMainLooper()).idle()
            if (FileWork.operations.value.isEmpty()) return
            Thread.sleep(10)
        }
        throw AssertionError("still running: ${FileWork.operations.value}")
    }

    private fun message(dialog: AlertDialog) = dialog.findViewById<android.widget.TextView>(android.R.id.message)!!.text.toString()

    @Test
    fun pagesReencodedAreListedWhenAnotherBookFails() {
        val summary = FileWork.Summary(0, reencoded = mapOf("Book A.cbz" to listOf("3.webp", "7.webp")))
        FileWork.start(FileWork.Kind.COMPATIBLE, "Book A.cbz", 2) { FileWork.Outcome.Failed(IOException("broken"), 1, 2, summary = summary) }
        settle()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertTrue(message(dialog).contains(context.getString(R.string.reencoded_book, "Book A.cbz", "3.webp, 7.webp")))
    }

    @Test
    fun pagesReencodedAreListedWhenCanceled() {
        val summary = FileWork.Summary(0, reencoded = mapOf("Book A.cbz" to listOf("2.webp")))
        FileWork.start(FileWork.Kind.COMPATIBLE, "Book A.cbz", 3) { FileWork.Outcome.Canceled(1, 3, summary = summary) }
        settle()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertTrue(message(dialog).contains(context.getString(R.string.reencoded_book, "Book A.cbz", "2.webp")))
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
