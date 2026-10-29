package com.infinity.comicreader.ui

import android.app.Application
import android.content.Context
import android.content.DialogInterface
import android.os.Environment
import android.os.Looper
import android.widget.CheckBox
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.button.MaterialButton
import com.infinity.comicreader.R
import com.infinity.comicreader.data.BookId
import com.infinity.comicreader.data.BookRef
import com.infinity.comicreader.data.Library
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.io.File

/** Books found nowhere are listed, ticked, and forgotten only once confirmed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MissingBooksActivityTest {

    private lateinit var context: Context
    private lateinit var library: Library

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Application>()
        context.getSharedPreferences("library", Context.MODE_PRIVATE).edit().clear().commit()
        library = Library(context)
        Environment.getExternalStorageDirectory().mkdirs()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun awaitUntil(done: () -> Boolean) {
        repeat(200) {
            idle()
            if (done()) return
            Thread.sleep(20)
        }
    }

    @Test
    fun aBookFoundNowhereIsListedTickedAndForgottenOnceConfirmed() {
        val root = Environment.getExternalStorageDirectory()
        val gone = BookRef(BookId(0x0123), File(root, "Comics/Gone/gone.cbz").path)
        library.setRead(gone, true)
        library.addHistory(com.infinity.comicreader.data.HistoryItem(gone.path, "gone.cbz", 1, 5, 1, gone.id))

        val activity = Robolectric.buildActivity(MissingBooksActivity::class.java).setup().get()
        val list = activity.findViewById<RecyclerView>(R.id.cleanup_list)
        awaitUntil { list.adapter!!.itemCount == 1 && list.childCount == 1 }
        val row = list.getChildAt(0)
        assertEquals("gone.cbz", row.findViewById<TextView>(R.id.title).text.toString())
        assertTrue(row.findViewById<TextView>(R.id.subtitle).text.contains("Read"))
        assertTrue(row.findViewById<CheckBox>(R.id.check).isChecked)

        val forget = activity.findViewById<MaterialButton>(R.id.cleanup_delete)
        assertEquals(context.getString(R.string.missing_forget_n, 1), forget.text.toString())
        forget.performClick()
        idle()
        val confirm = ShadowDialog.getLatestDialog() as AlertDialog
        assertTrue(confirm.findViewById<TextView>(android.R.id.message)!!.text.startsWith("Its "))
        confirm.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
        idle()
        assertTrue(library.hasData(gone.id))

        forget.performClick()
        idle()
        (ShadowDialog.getLatestDialog() as AlertDialog).getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        awaitUntil { !library.hasData(gone.id) && list.adapter!!.itemCount == 0 }
        assertFalse(library.hasData(gone.id))
        assertTrue(library.history().isEmpty())
    }

    @Test
    fun untickingLeavesABookBe() {
        val root = Environment.getExternalStorageDirectory()
        val gone = BookRef(BookId(0x0456), File(root, "Comics/gone.cbz").path)
        library.setRead(gone, true)
        val activity = Robolectric.buildActivity(MissingBooksActivity::class.java).setup().get()
        val list = activity.findViewById<RecyclerView>(R.id.cleanup_list)
        awaitUntil { list.childCount == 1 }
        list.getChildAt(0).performClick()
        assertFalse(list.getChildAt(0).findViewById<CheckBox>(R.id.check).isChecked)
        assertFalse(activity.findViewById<MaterialButton>(R.id.cleanup_delete).isEnabled)
    }

    @Test
    fun aBookUntickedStaysUntickedAfterOthersAreForgotten() {
        val root = Environment.getExternalStorageDirectory()
        val first = BookRef(BookId(0x0a01), File(root, "Comics/a.cbz").path)
        val second = BookRef(BookId(0x0a02), File(root, "Comics/b.cbz").path)
        library.setRead(first, true)
        library.setRead(second, true)
        val activity = Robolectric.buildActivity(MissingBooksActivity::class.java).setup().get()
        val list = activity.findViewById<RecyclerView>(R.id.cleanup_list)
        awaitUntil { list.childCount == 2 }
        list.getChildAt(1).performClick()
        activity.findViewById<MaterialButton>(R.id.cleanup_delete).performClick()
        idle()
        (ShadowDialog.getLatestDialog() as AlertDialog).getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        awaitUntil { list.adapter!!.itemCount == 1 && list.childCount == 1 }
        assertFalse(library.hasData(first.id))
        assertEquals("b.cbz", list.getChildAt(0).findViewById<TextView>(R.id.title).text.toString())
        assertFalse(list.getChildAt(0).findViewById<CheckBox>(R.id.check).isChecked)
        assertFalse(activity.findViewById<MaterialButton>(R.id.cleanup_delete).isEnabled)
    }
}
