package com.infinity.comicreader.ui

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.R
import com.infinity.comicreader.data.Bookmark
import com.infinity.comicreader.data.HistoryItem
import com.infinity.comicreader.data.Library
import com.infinity.comicreader.reader.ArchiveSource
import com.infinity.comicreader.reader.HiddenPages
import com.infinity.comicreader.reader.NameCharset
import com.infinity.comicreader.reader.ZipReader
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
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A book's own file name charset, picked from its long-press or Book info, and how Book info says it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NameCharsetDialogTest {

    @get:Rule
    val tmp = TemporaryFolder()

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
    fun pickingTellsANewChoiceOnly() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        val picks = ArrayList<String?>()
        fun pick(label: String) {
            val dialog = NameCharsetDialog.builder(activity, library, "/books/a.cbz", NameCharset.AUTO) { picks += it }.show()
            val list = dialog.listView
            val at = (0 until list.adapter.count).first { list.adapter.getItem(it).toString() == label }
            list.performItemClick(list.adapter.getView(at, null, list), at, list.adapter.getItemId(at))
            shadowOf(Looper.getMainLooper()).idle()
        }
        pick("Shift_JIS (Japanese)")
        assertEquals(listOf("Shift_JIS"), picks)
        library.setNameCharset("/books/a.cbz", "Shift_JIS")
        pick("Shift_JIS (Japanese)")
        assertEquals(listOf("Shift_JIS"), picks)
        pick(context.getString(R.string.charset_follow_setting, context.getString(R.string.charset_auto)))
        assertEquals(listOf("Shift_JIS", null), picks)
    }

    @Test
    fun switchingKeepsTheBooksOwnAndFollowingTheSettingDropsIt() {
        val file = File(tmp.root, "a.cbz").apply { writeBytes(zipBytes(listOf("b.jpg", "a.jpg"), Charsets.UTF_8)) }
        val path = file.absolutePath
        NameCharsetDialog.switch(library, null, file, NameCharset.AUTO, "Shift_JIS", tmp.newFolder("cache"))
        assertEquals("Shift_JIS", library.nameCharset(path))
        assertEquals("Shift_JIS", NameCharset.forBook(path, NameCharset.AUTO))
        NameCharsetDialog.switch(library, null, file, NameCharset.AUTO, null, tmp.root)
        assertNull(library.nameCharset(path))
    }

    /** Japanese page names; each page's one byte is its place here. */
    private val japanese = listOf("表紙.jpg", "目次.jpg", "第一話.jpg", "第二話.jpg", "あとがき.jpg", "奥付.jpg", "裏表紙.jpg", "001.jpg")

    private fun zipBytes(names: List<String>, charset: Charset): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out, charset).use { zip ->
            for ((i, n) in names.withIndex()) {
                zip.putNextEntry(ZipEntry(n))
                zip.write(i)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** The place in [japanese] of each page of [file] read in [charsetName], in page order. */
    private fun order(file: File, charsetName: String): List<Int> =
        ArchiveSource(ZipReader(file, charsetName)).use { src -> (0 until src.pageCount).map { i -> src.readPage(i) { it.read() } } }

    @Test
    fun switchingMovesHiddenPagesHistoryAndBookmarksWithTheirPages() {
        val file = File(tmp.root, "book.cbz").apply { writeBytes(zipBytes(japanese, Charset.forName("Shift_JIS"))) }
        val path = file.absolutePath
        val before = order(file, NameCharset.AUTO)
        val after = order(file, "windows-1251")
        assertTrue(before != after)
        // The pages hidden, read last and bookmarked, by their place in [japanese].
        val hidden = japanese.indexOf("目次.jpg")
        val read = japanese.indexOf("第二話.jpg")
        val marked = japanese.indexOf("奥付.jpg")
        val hiddenSet = setOf(before.indexOf(hidden))
        library.setHiddenPages(path, hiddenSet, japanese.size)
        val shownBefore = HiddenPages.shown(japanese.size, hiddenSet).map { before[it] }
        library.addHistory(HistoryItem(path, file.name, shownBefore.indexOf(read), shownBefore.size, 1))
        library.addBookmark(Bookmark(path, file.name, shownBefore.indexOf(marked), "note", 1))

        NameCharsetDialog.switch(library, null, file, NameCharset.AUTO, "windows-1251", tmp.newFolder("cache"))

        val now = library.hiddenPages(path)
        assertEquals(setOf(hidden), now.map { after[it] }.toSet())
        val shownAfter = HiddenPages.shown(japanese.size, now).map { after[it] }
        assertEquals(read, shownAfter[library.pageFor(path)])
        assertEquals(marked, shownAfter[library.bookmarksFor(path).single().page])
        assertEquals("note", library.bookmarksFor(path).single().note)
    }
}
