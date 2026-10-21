package com.infinity.comicreader.ui

import android.app.Application
import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.button.MaterialButtonToggleGroup
import com.infinity.comicreader.R
import com.infinity.comicreader.data.BookFacts
import com.infinity.comicreader.data.BookFactsCache
import com.infinity.comicreader.data.FolderLayout
import com.infinity.comicreader.data.Library
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.ReaderLayout
import com.infinity.comicreader.data.ReadingDirection
import com.infinity.comicreader.data.ResumeMode
import com.infinity.comicreader.reader.DirectionDetection
import com.infinity.comicreader.reader.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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
import org.robolectric.shadows.ShadowToast
import java.io.File

/** "Detect reading direction" in the reader: what decides, and what it never overrides. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ViewerActivityDirectionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var prefs: Prefs
    private lateinit var library: Library
    private lateinit var dir: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        prefs = Prefs(context)
        prefs.resumeMode = ResumeMode.NO
        library = Library(context)
        dir = tmp.newFolder("books")
    }

    private fun book(name: String, comicInfo: String? = null): File {
        val pages = (0 until 4).map { Fixtures.entry("p%02d.png".format(it), byteArrayOf(0)) }
        val info = comicInfo?.let { listOf(Fixtures.entry("ComicInfo.xml", it.toByteArray())) }.orEmpty()
        return Fixtures.zip(File(dir, name), *(pages + info).toTypedArray())
    }

    private val rtlInfo = "<ComicInfo><Manga>YesAndRightToLeft</Manga></ComicInfo>"

    private fun launch(book: File): ActivityController<ViewerActivity> {
        val intent = Intent(ApplicationProvider.getApplicationContext(), ViewerActivity::class.java)
            .putExtra(ViewerActivity.EXTRA_PATH, book.absolutePath)
        return Robolectric.buildActivity(ViewerActivity::class.java, intent).setup()
    }

    private fun awaitOpen(viewer: ViewerActivity) {
        val progress = viewer.findViewById<View>(R.id.viewer_progress)
        val deadline = System.currentTimeMillis() + 5_000
        do {
            Thread.sleep(10)
            shadowOf(Looper.getMainLooper()).idle()
        } while (progress.visibility == View.VISIBLE && System.currentTimeMillis() < deadline)
        assertEquals(View.GONE, progress.visibility)
    }

    private fun ViewerActivity.pagerDirection() = findViewById<View>(R.id.viewer_pager).layoutDirection

    private fun ViewerActivity.hint() = findViewById<TextView>(R.id.viewer_hint)

    @Test
    fun comicInfoTurnsTheBookRightToLeftWithANote() {
        val viewer = launch(book("Series B v01.cbz", rtlInfo)).get()
        awaitOpen(viewer)
        assertEquals(View.LAYOUT_DIRECTION_RTL, viewer.pagerDirection())
        assertEquals(View.VISIBLE, viewer.hint().visibility)
        assertEquals("Right-to-left (from ComicInfo)", viewer.hint().text.toString())
        // The default stays as it was.
        assertEquals("ltr", prefs.readingDir)
    }

    @Test
    fun publisherTagInTheFileName() {
        val viewer = launch(book("Series B v01 [Kodansha Comics].cbz")).get()
        awaitOpen(viewer)
        assertEquals(View.LAYOUT_DIRECTION_RTL, viewer.pagerDirection())
        assertEquals("Right-to-left (from Kodansha)", viewer.hint().text.toString())
    }

    @Test
    fun theLibraryFactsOfABookAnswerWithoutReadingIt() {
        // Facts read for the library say right to left; the book itself carries nothing.
        val book = book("Series A 001.cbz")
        val facts = BookFacts(4, 0, DirectionDetection.Detected(ReadingDirection.RTL, DirectionDetection.Source.PUBLISHER))
        BookFactsCache(ApplicationProvider.getApplicationContext()).put(book.absolutePath, book.length(), facts)
        val viewer = launch(book).get()
        awaitOpen(viewer)
        assertEquals(View.LAYOUT_DIRECTION_RTL, viewer.pagerDirection())
    }

    @Test
    fun aComicInfoStoredAfterThePagesOfASolidArchiveTurnsTheBookOnceRead() {
        val pages = (0 until 4).map { Fixtures.entry("p%02d.png".format(it), byteArrayOf(0)) }
        val book = Fixtures.sevenZip(File(dir, "Series B v01.cb7"), *(pages + Fixtures.entry("ComicInfo.xml", rtlInfo.toByteArray())).toTypedArray())
        val viewer = launch(book).get()
        awaitOpen(viewer)
        val deadline = System.currentTimeMillis() + 5_000
        while (viewer.pagerDirection() != View.LAYOUT_DIRECTION_RTL && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertEquals(View.LAYOUT_DIRECTION_RTL, viewer.pagerDirection())
        assertEquals("Right-to-left (from ComicInfo)", viewer.hint().text.toString())
        // Kept with the library's facts, so the next open knows at once.
        val facts = BookFactsCache(ApplicationProvider.getApplicationContext()).get(book.absolutePath, book.length())
        assertEquals(DirectionDetection.Source.COMIC_INFO, facts?.detected?.source)
    }

    @Test
    fun aFolderDirectionIsNeverOverridden() {
        val book = book("Series B v01.cbz", rtlInfo)
        library.setFolderLayout(dir.absolutePath, FolderLayout.Direction("ltr"))
        val viewer = launch(book).get()
        awaitOpen(viewer)
        assertEquals(View.LAYOUT_DIRECTION_LTR, viewer.pagerDirection())
        assertNotEquals(View.VISIBLE, viewer.hint().visibility)
    }

    @Test
    fun noNoteWhereLeftAndRightDoNothing() {
        prefs.pageMode = "longstrip"
        val viewer = launch(book("Series B v01.cbz", rtlInfo)).get()
        awaitOpen(viewer)
        assertNotEquals(View.VISIBLE, viewer.hint().visibility)
    }

    @Test
    fun settingOffReadsTheDefault() {
        prefs.detectDirection = false
        val viewer = launch(book("Series B v01.cbz", rtlInfo)).get()
        awaitOpen(viewer)
        assertEquals(View.LAYOUT_DIRECTION_LTR, viewer.pagerDirection())
    }

    @Test
    fun turningTheSettingOffAppliesLive() {
        val viewer = launch(book("Series B v01.cbz", rtlInfo)).get()
        awaitOpen(viewer)
        prefs.detectDirection = false
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(View.LAYOUT_DIRECTION_LTR, viewer.pagerDirection())
    }

    @Test
    fun plainBooksKeepTheDefaultWithoutANote() {
        prefs.readingDir = "rtl"
        val viewer = launch(book("Series A 001.cbz")).get()
        awaitOpen(viewer)
        assertEquals(View.LAYOUT_DIRECTION_RTL, viewer.pagerDirection())
        assertNotEquals(View.VISIBLE, viewer.hint().visibility)
    }

    @Test
    fun changingLayoutKeepsTheDetectedDirectionOutOfTheDefaults() {
        prefs.perSeries = false
        val viewer = launch(book("Series B v01.cbz", rtlInfo)).get()
        awaitOpen(viewer)
        // Another layout option written back from the reader leaves the default direction alone.
        ReaderSheet(viewer).tap(R.id.btn_fit_width)
        assertEquals("width", prefs.fit)
        assertEquals("ltr", prefs.readingDir)
        assertEquals(View.LAYOUT_DIRECTION_RTL, viewer.pagerDirection())
    }

    @Test
    fun choosingADirectionOverADetectedOneKeepsItForTheFolder() {
        val viewer = launch(book("Series B v01.cbz", rtlInfo)).get()
        awaitOpen(viewer)
        ReaderSheet(viewer).tap(R.id.btn_dir_ltr)
        assertEquals(View.LAYOUT_DIRECTION_LTR, viewer.pagerDirection())
        assertEquals(FolderLayout.Direction("ltr"), library.folderLayout(dir.absolutePath))
        assertEquals("ltr", prefs.readingDir)
        assertEquals(viewer.getString(R.string.direction_kept_for_folder), ShadowToast.getTextOfLatestToast())
    }

    private fun folder() = library.folderLayout(dir.absolutePath)

    @Test
    fun thisFolderThenAllBooksHandsTheDirectionBackToDetection() {
        val viewer = launch(book("Series B v01.cbz", rtlInfo)).get()
        awaitOpen(viewer)
        val sheet = ReaderSheet(viewer).tap(R.id.btn_scope_folder)
        assertEquals(false, folder()?.dirChosen)
        assertEquals(View.LAYOUT_DIRECTION_RTL, viewer.pagerDirection())
        sheet.tap(R.id.btn_scope_all)
        assertNull(folder())
        assertEquals(View.LAYOUT_DIRECTION_RTL, viewer.pagerDirection())
    }

    @Test
    fun aFrozenFolderWithoutAChosenDirectionStillDetects() {
        library.setFolderLayout(dir.absolutePath, FolderLayout.Frozen(ReaderLayout(fit = "width"), dirChosen = false))
        val viewer = launch(book("Series B v01.cbz", rtlInfo)).get()
        awaitOpen(viewer)
        assertEquals(View.LAYOUT_DIRECTION_RTL, viewer.pagerDirection())
    }

    @Test
    fun aDirectionChosenInAFrozenFolderOutlastsAllBooks() {
        val viewer = launch(book("Series B v01.cbz", rtlInfo)).get()
        awaitOpen(viewer)
        val sheet = ReaderSheet(viewer).tap(R.id.btn_scope_folder).tap(R.id.btn_dir_ltr)
        assertEquals(true, folder()?.dirChosen)
        sheet.tap(R.id.btn_scope_all)
        assertEquals(FolderLayout.Direction("ltr"), folder())
        assertEquals(View.LAYOUT_DIRECTION_LTR, viewer.pagerDirection())
    }

    private fun awaitDirection(viewer: ViewerActivity, direction: Int) {
        val deadline = System.currentTimeMillis() + 5_000
        while (viewer.pagerDirection() != direction && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertEquals(direction, viewer.pagerDirection())
    }

    @Test
    fun autoIsSelectedWhileTheFolderHasNoDirection() {
        val viewer = launch(book("Series B v01.cbz", rtlInfo)).get()
        awaitOpen(viewer)
        val sheet = ReaderSheet(viewer)
        assertEquals(R.id.btn_dir_auto, sheet.view<MaterialButtonToggleGroup>(R.id.sheet_dir).checkedButtonId)
        assertEquals("Right-to-left (from ComicInfo)", sheet.view<TextView>(R.id.sheet_dir_hint).text.toString())
    }

    @Test
    fun autoHandsTheFolderDirectionBackToDetection() {
        library.setFolderLayout(dir.absolutePath, FolderLayout.Direction("ltr"))
        val viewer = launch(book("Series B v01.cbz", rtlInfo)).get()
        awaitOpen(viewer)
        val sheet = ReaderSheet(viewer)
        assertEquals(R.id.btn_dir_ltr, sheet.view<MaterialButtonToggleGroup>(R.id.sheet_dir).checkedButtonId)
        sheet.tap(R.id.btn_dir_auto)
        assertNull(folder())
        awaitDirection(viewer, View.LAYOUT_DIRECTION_RTL)
        assertEquals(R.id.btn_dir_auto, sheet.view<MaterialButtonToggleGroup>(R.id.sheet_dir).checkedButtonId)
    }

    @Test
    fun autoInAFrozenFolderKeepsTheRestFrozen() {
        library.setFolderLayout(dir.absolutePath, FolderLayout.Frozen(ReaderLayout(dir = "ltr", fit = "width")))
        val viewer = launch(book("Series B v01.cbz", rtlInfo)).get()
        awaitOpen(viewer)
        assertEquals(View.LAYOUT_DIRECTION_LTR, viewer.pagerDirection())
        ReaderSheet(viewer).tap(R.id.btn_dir_auto)
        assertEquals(FolderLayout.Frozen(ReaderLayout(dir = "ltr", fit = "width"), dirChosen = false), folder())
        awaitDirection(viewer, View.LAYOUT_DIRECTION_RTL)
    }

    @Test
    fun autoWithDetectionOffReadsTheDefault() {
        prefs.detectDirection = false
        library.setFolderLayout(dir.absolutePath, FolderLayout.Direction("rtl"))
        val viewer = launch(book("Series B v01.cbz", rtlInfo)).get()
        awaitOpen(viewer)
        assertEquals(View.LAYOUT_DIRECTION_RTL, viewer.pagerDirection())
        ReaderSheet(viewer).tap(R.id.btn_dir_auto)
        assertNull(folder())
        assertEquals(View.LAYOUT_DIRECTION_LTR, viewer.pagerDirection())
    }

    @Test
    fun choosingTheDirectionOnScreenKeepsItForTheFolder() {
        val viewer = launch(book("Series B v01.cbz", rtlInfo)).get()
        awaitOpen(viewer)
        val sheet = ReaderSheet(viewer).tap(R.id.btn_dir_rtl)
        assertEquals(FolderLayout.Direction("rtl"), folder())
        assertEquals(R.id.btn_dir_rtl, sheet.view<MaterialButtonToggleGroup>(R.id.sheet_dir).checkedButtonId)
        assertEquals(View.LAYOUT_DIRECTION_RTL, viewer.pagerDirection())
    }
}
