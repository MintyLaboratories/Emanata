package com.infinity.comicreader.ui

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.R
import com.infinity.comicreader.data.Library
import com.infinity.comicreader.reader.NameCharset
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.nio.charset.Charset

/** A book's own file name charset, picked from its long-press or Book info, and how Book info says it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NameCharsetDialogTest {

    private lateinit var context: Context
    private lateinit var library: Library

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("library", Context.MODE_PRIVATE).edit().clear().commit()
        library = Library(context)
    }

    @After
    fun tearDown() {
        NameCharset.overrides = emptyMap()
    }

    @Test
    fun onlyZipAndTarBooksHaveOne() {
        assertTrue(NameCharsetDialog.applies("a.cbz"))
        assertTrue(NameCharsetDialog.applies("a.ZIP"))
        assertTrue(NameCharsetDialog.applies("a.cbt"))
        assertFalse(NameCharsetDialog.applies("a.cbr"))
        assertFalse(NameCharsetDialog.applies("a.cb7"))
        assertFalse(NameCharsetDialog.applies("a.pdf"))
    }

    @Test
    fun bookInfoSaysWhatNamesWereReadIn() {
        val sjis = Charset.forName("windows-31j")
        assertEquals("Auto (windows-31j)", NameCharsetDialog.describe(context, library, "/books/a.cbz", NameCharset.AUTO, sjis))
        assertEquals("GBK (Simplified Chinese)", NameCharsetDialog.describe(context, library, "/books/a.cbz", "GBK", Charset.forName("GBK")))
        library.setNameCharset("/books/a.cbz", "Shift_JIS")
        assertEquals("Shift_JIS (Japanese), this book only", NameCharsetDialog.describe(context, library, "/books/a.cbz", NameCharset.AUTO, sjis))
    }

    @Test
    fun pickingKeepsTheBooksOwnAndFollowingTheSettingDropsIt() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        var changes = 0
        fun pick(label: String) {
            val dialog = NameCharsetDialog.builder(activity, library, "/books/a.cbz", NameCharset.AUTO) { changes++ }.show()
            val list = dialog.listView
            val at = (0 until list.adapter.count).first { list.adapter.getItem(it).toString() == label }
            list.performItemClick(list.adapter.getView(at, null, list), at, list.adapter.getItemId(at))
            shadowOf(Looper.getMainLooper()).idle()
        }
        pick("Shift_JIS (Japanese)")
        assertEquals("Shift_JIS", library.nameCharset("/books/a.cbz"))
        assertEquals("Shift_JIS", NameCharset.forBook("/books/a.cbz", NameCharset.AUTO))
        assertEquals(1, changes)
        pick("Shift_JIS (Japanese)")
        assertEquals(1, changes)
        pick(context.getString(R.string.charset_follow_setting, context.getString(R.string.charset_auto)))
        assertNull(library.nameCharset("/books/a.cbz"))
        assertEquals(2, changes)
    }
}
