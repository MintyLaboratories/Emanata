package com.infinity.comicreader.ui

import android.app.Application
import android.content.Context
import android.content.DialogInterface
import android.os.Looper
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.R
import com.infinity.comicreader.data.Library
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.files.FileClipboard
import com.infinity.comicreader.files.FileWork
import com.infinity.comicreader.files.FileWorkService
import org.junit.After
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
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast
import java.io.File

/** Several items deleted at once: one question, one operation, one count. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FileActionsManyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var context: Application
    private lateinit var controller: ActivityController<AboutActivity>
    private lateinit var actions: FileActions
    private val finished = ArrayList<FileWork.Outcome>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("ui", 0).edit().clear().commit()
        FileWork.reset()
        controller = Robolectric.buildActivity(AboutActivity::class.java).create()
        actions = FileActions(controller.get(), Library(context), Prefs(context), { "Device" }, { finished += it }, {})
        controller.start().resume()
    }

    @After
    fun tearDown() {
        settle()
        controller.pause().stop().destroy()
    }

    private fun settle() {
        repeat(300) {
            shadowOf(Looper.getMainLooper()).idle()
            if (FileWork.status.value == null) return
            Thread.sleep(10)
        }
        throw AssertionError("still running: ${FileWork.status.value}")
    }

    private fun AlertDialog.text(id: Int): String = findViewById<android.widget.TextView>(id)!!.text.toString()

    @Test
    fun severalAreDeletedAfterOneQuestion() {
        val files = listOf("a.cbz", "b.cbz").map { File(tmp.root, it).apply { writeBytes(ByteArray(10)) } }
        val folder = tmp.newFolder("Shelf").also { File(it, "c.cbz").writeBytes(ByteArray(5)) }
        val items = files.map { FileClipboard.Item(it.absolutePath, false) } + FileClipboard.Item(folder.absolutePath, true)
        var confirmed = 0
        actions.delete(items) { confirmed++ }
        shadowOf(Looper.getMainLooper()).idle()
        val ask = ShadowDialog.getLatestDialog() as AlertDialog
        val res = context.resources
        assertEquals(res.getQuantityString(R.plurals.delete_items_title, 3, 3), ask.text(androidx.appcompat.R.id.alertTitle))
        assertEquals(res.getQuantityString(R.plurals.delete_items_folders_msg, 3, 3, "Device"), ask.text(android.R.id.message))
        assertEquals(0, confirmed)
        ask.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        settle()
        assertEquals(1, confirmed)
        assertTrue(files.none { it.exists() })
        assertFalse(folder.exists())
        assertEquals(res.getQuantityString(R.plurals.deleted_format, 3, 3), ShadowToast.getTextOfLatestToast())
        val done = finished.single() as FileWork.Outcome.Done
        assertEquals(3, done.count)
        assertEquals(items.map { it.path }, done.gone)
    }

    @Test
    fun progressNamesTheItemAndWhichOfHowMany() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val status = FileWork.Status(FileWork.Kind.DELETE, "b.cbz", 0, index = 1, count = 3)
        assertEquals("b.cbz (2 of 3)", FileWorkService.nameOf(context, status))
        assertEquals("b.cbz", FileWorkService.nameOf(context, status.copy(index = 0, count = 1)))
    }
}
