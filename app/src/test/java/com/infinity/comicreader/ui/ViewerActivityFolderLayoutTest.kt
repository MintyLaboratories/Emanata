package com.infinity.comicreader.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.button.MaterialButtonToggleGroup
import com.infinity.comicreader.R
import com.infinity.comicreader.data.FolderLayout
import com.infinity.comicreader.data.Library
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.ReaderLayout
import com.infinity.comicreader.data.ResumeMode
import com.infinity.comicreader.data.TwoPage
import com.infinity.comicreader.reader.Fixtures
import com.infinity.comicreader.testing.idleUntil
import com.infinity.comicreader.testing.ref
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/** "Apply to: This folder / All books": what the reader keeps for a folder, and what for every book. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ViewerActivityFolderLayoutTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var context: Application
    private lateinit var prefs: Prefs
    private lateinit var library: Library
    private lateinit var dir: File
    private lateinit var book: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        context.getSharedPreferences("library", Context.MODE_PRIVATE).edit().clear().commit()
        prefs = Prefs(context)
        prefs.resumeMode = ResumeMode.NO
        library = Library(context)
        dir = tmp.newFolder("series")
        book = Fixtures.zip(File(dir, "Series A 001.cbz"), *(0 until 4).map { Fixtures.entry("p%02d.png".format(it), byteArrayOf(0)) }.toTypedArray())
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun launch(): ViewerActivity {
        val intent = Intent(context, ViewerActivity::class.java).putExtra(ViewerActivity.EXTRA_PATH, book.absolutePath)
        val viewer = Robolectric.buildActivity(ViewerActivity::class.java, intent).setup().get()
        val progress = viewer.findViewById<View>(R.id.viewer_progress)
        idleUntil("the book open") { progress.visibility != View.VISIBLE }
        return viewer
    }

    private fun ViewerActivity.paged() = findViewById<View>(R.id.viewer_pager).visibility == View.VISIBLE

    private fun folder() = library.folderLayout(dir.absolutePath)

    @Test
    fun choosingADirectionKeepsOnlyTheDirectionForTheFolder() {
        val viewer = launch()
        val sheet = ReaderSheet(viewer)
        assertEquals(View.VISIBLE, sheet.view<View>(R.id.sheet_scope_row).visibility)
        sheet.tap(R.id.btn_dir_rtl)
        assertEquals(FolderLayout.Direction("rtl"), folder())
        assertEquals("ltr", prefs.readingDir)
        assertEquals(View.LAYOUT_DIRECTION_RTL, viewer.findViewById<View>(R.id.viewer_pager).layoutDirection)
        // "All books" stays selected, so other options still change the defaults, which still reach this folder.
        assertEquals(R.id.btn_scope_all, sheet.view<MaterialButtonToggleGroup>(R.id.sheet_scope).checkedButtonId)
        sheet.tap(R.id.btn_fit_width)
        assertEquals("width", prefs.fit)
        assertEquals(FolderLayout.Direction("rtl"), folder())
        prefs.pageMode = "continuous"
        idle()
        // Left and right always page; the default layout shows once the folder reads top to bottom.
        assertEquals(true, viewer.paged())
        sheet.tap(R.id.btn_dir_ttb)
        assertEquals(FolderLayout.Direction("ttb"), folder())
        assertEquals(false, viewer.paged())
    }

    @Test
    fun thisFolderFreezesTheLayoutOnScreen() {
        prefs.fit = "height"
        val viewer = launch()
        val sheet = ReaderSheet(viewer).tap(R.id.btn_scope_folder)
        assertEquals(FolderLayout.Frozen(ReaderLayout(fit = "height"), dirChosen = false), folder())
        assertEquals(viewer.getString(R.string.scope_folder_hint), sheet.view<TextView>(R.id.sheet_scope_hint).text.toString())
        // Changes now stay with the folder.
        sheet.tap(R.id.btn_fit_width)
        assertEquals("height", prefs.fit)
        assertEquals("width", (folder() as FolderLayout.Frozen).layout.fit)
        // And the default layout no longer reaches it, though the default direction does.
        prefs.pageMode = "continuous"
        prefs.readingDir = "ttb"
        idle()
        assertEquals(true, viewer.paged())
        assertEquals(androidx.viewpager2.widget.ViewPager2.ORIENTATION_VERTICAL, viewer.findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.viewer_pager).orientation)
    }

    @Test
    fun allBooksReturnsToTheDefaultsButKeepsTheDirection() {
        library.setFolderLayout(dir.absolutePath, FolderLayout.Frozen(ReaderLayout("continuous", "ttb", "width", TwoPage.ON, true, 16)))
        val viewer = launch()
        assertEquals(false, viewer.paged())
        ReaderSheet(viewer).tap(R.id.btn_scope_all)
        assertEquals(FolderLayout.Direction("ttb"), folder())
        assertEquals(true, viewer.paged())
    }

    @Test
    fun aBooksOwnLayoutShowsAndKeepsItsChangesOverItsFolders() {
        library.setFolderLayout(dir.absolutePath, FolderLayout.Direction("ltr"))
        val own = ref(book)
        library.setBookLayout(own, FolderLayout.Direction("rtl"))
        val viewer = launch()
        assertEquals(View.LAYOUT_DIRECTION_RTL, viewer.findViewById<View>(R.id.viewer_pager).layoutDirection)
        val sheet = ReaderSheet(viewer).tap(R.id.btn_scope_folder)
        // "This folder" freezes the book's own layout, not its folder's.
        assertEquals(FolderLayout.Frozen(ReaderLayout(dir = "rtl")), library.bookLayout(own.id))
        assertEquals(FolderLayout.Direction("ltr"), folder())
        assertEquals(viewer.getString(R.string.scope_book_hint), sheet.view<TextView>(R.id.sheet_scope_hint).text.toString())
        sheet.tap(R.id.btn_dir_ttb)
        assertEquals("ttb", library.bookLayout(own.id)!!.dir)
        assertEquals(FolderLayout.Direction("ltr"), folder())
    }

    @Test
    fun withPerFolderLayoutOffADirectionIsTheDefault() {
        prefs.perSeries = false
        val viewer = launch()
        val sheet = ReaderSheet(viewer)
        assertEquals(View.GONE, sheet.view<View>(R.id.sheet_scope_row).visibility)
        assertEquals(View.GONE, sheet.view<View>(R.id.btn_dir_auto).visibility)
        sheet.tap(R.id.btn_dir_rtl)
        assertEquals("rtl", prefs.readingDir)
        assertNull(folder())
    }

    @Test
    fun allBooksIntoAColumnLaysTheNextChapterInBehind() {
        Fixtures.zip(File(dir, "Series A 002.cbz"), *(0 until 4).map { Fixtures.entry("p%02d.png".format(it), byteArrayOf(0)) }.toTypedArray())
        prefs.pageMode = "continuous"
        prefs.readingDir = "ttb"
        library.setFolderLayout(dir.absolutePath, FolderLayout.Frozen(ReaderLayout(), dirChosen = false))
        val viewer = launch()
        assertEquals(true, viewer.paged())
        ReaderSheet(viewer).tap(R.id.btn_scope_all)
        assertEquals(false, viewer.paged())
        val strip = viewer.findViewById<RecyclerView>(R.id.viewer_vertical)
        idleUntil("the next chapter laid in behind") { (strip.adapter?.itemCount ?: 0) > 4 }
        assertEquals(8, strip.adapter?.itemCount)
    }
}
