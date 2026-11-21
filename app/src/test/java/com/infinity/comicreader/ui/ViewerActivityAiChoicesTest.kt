package com.infinity.comicreader.ui

import android.app.Application
import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.infinity.comicreader.R
import com.infinity.comicreader.ai.AiStep
import com.infinity.comicreader.ai.ChainKind
import com.infinity.comicreader.ai.DownloadState
import com.infinity.comicreader.ai.FileSet
import com.infinity.comicreader.ai.ModelCatalog
import com.infinity.comicreader.ai.ModelDownloads
import com.infinity.comicreader.ai.ModelStore
import com.infinity.comicreader.ai.Models
import com.infinity.comicreader.ai.OfflineDownloads
import com.infinity.comicreader.ai.PageTimes
import com.infinity.comicreader.data.BookIds
import com.infinity.comicreader.data.Library
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.ResumeMode
import com.infinity.comicreader.reader.Fixtures
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
import java.io.File
import java.io.RandomAccessFile

/** The reader sheet's AI pickers: each model with what it does, slow ones only with advanced settings shown. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ViewerActivityAiChoicesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Picking a model starts its download: kept off the network and ended with the test. */
    @get:Rule
    val downloads = OfflineDownloads()

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var prefs: Prefs

    @Before
    fun setUp() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        // Every test's book is alike: what one kept for it must not reach the next.
        context.getSharedPreferences("library", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        prefs = Prefs(context)
        prefs.resumeMode = ResumeMode.NO
        ModelStore.of(context).root.deleteRecursively()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** One book for every reader a test opens: a like book elsewhere would have them ask whether it is a copy. */
    private val book by lazy {
        val pages = (0 until 3).map { Fixtures.entry("p%02d.png".format(it), byteArrayOf(0)) }
        Fixtures.zip(File(tmp.newFolder(), "Sample v01.cbz"), *pages.toTypedArray())
    }

    /** The folder [book] is in, as the reader keeps a folder's default chain by it. */
    private val folder: String get() = book.parent!!

    private fun open(): ViewerActivity {
        val intent = Intent(context, ViewerActivity::class.java).putExtra(ViewerActivity.EXTRA_PATH, book.absolutePath)
        val viewer = Robolectric.buildActivity(ViewerActivity::class.java, intent).setup().get()
        val progress = viewer.findViewById<View>(R.id.viewer_progress)
        val deadline = System.currentTimeMillis() + 5_000
        do {
            Thread.sleep(10)
            idle()
        } while (progress.visibility == View.VISIBLE && System.currentTimeMillis() < deadline)
        return viewer
    }

    /** The upscale picker's dialog, opened from the sheet, and its rows. */
    private fun upscaleChoices(viewer: ViewerActivity): Pair<AlertDialog, List<View>> {
        ReaderSheet(viewer).tap(R.id.sheet_ai_upscale)
        val dialog = latestDialogOf(viewer) as AlertDialog
        return dialog to ModelPickerTest.rows(dialog)
    }

    @Test
    fun upscalersShowShortNamesAndTheSlowOnesWaitForAdvancedSettings() {
        val (dialog, rows) = upscaleChoices(open())
        val none = context.getString(R.string.ai_none)
        assertEquals(PickerRows.of(AiStep.UPSCALE, keepSimple = true, current = null, noneName = none).map { it.name }, rows.map(ModelPickerTest::name).map { it.removeSuffix(" (default)") })
        assertEquals("waifu2x 2× (default)", ModelPickerTest.name(rows[1]))
        // The default runs, so it is checked.
        assertTrue(ModelPickerTest.checked(rows[1]))
        assertEquals(1, rows.count(ModelPickerTest::checked))
        dialog.dismiss()

        prefs.keepSimple = false
        val (_, all) = upscaleChoices(open())
        assertEquals(PickerRows.of(AiStep.UPSCALE, keepSimple = false, current = null, noneName = none).size, all.size)
        assertTrue(all.any { ModelPickerTest.name(it) == "Real-ESRGAN 2×" })
    }

    @Test
    fun pickingAModelRunsIt() {
        val (dialog, rows) = upscaleChoices(open())
        ModelPickerTest.tap(rows.first { ModelPickerTest.name(it) == "OpenComic 2×" })
        idle()
        assertFalse(dialog.isShowing)
        assertEquals("opencomic-ai-upscale-2x-compact-v2", Prefs(context).aiModel(AiStep.UPSCALE))
        assertTrue(Prefs(context).aiEnabled)
    }

    @Test
    fun aLevelPickedOnASliderRunsForThisBookAndStaysPicked() {
        val viewer = open()
        // This book's own picks.
        val sheet = ReaderSheet(viewer).tap(R.id.btn_ai_book)
        sheet.tap(R.id.sheet_ai_upscale)
        val dialog = latestDialogOf(viewer) as AlertDialog
        val family = ModelPickerTest.rows(dialog).first { ModelPickerTest.name(it) == "Anime4K B (clean) 2×" }
        ModelPickerTest.slide(family, 3)
        idle()
        assertTrue(dialog.isShowing)
        dialog.dismiss()
        idle()
        // The book runs it; the default is untouched.
        assertEquals(ModelCatalog.DEFAULT_UPSCALE, Prefs(context).aiModel(AiStep.UPSCALE))
        sheet.tap(R.id.sheet_ai_upscale)
        val again = ModelPickerTest.rows(latestDialogOf(viewer))
        val shown = again.first { ModelPickerTest.name(it) == "Anime4K B (clean) 2×" }
        assertEquals(3f, ModelPickerTest.slider(shown).value)
        assertTrue(ModelPickerTest.checked(shown))
    }

    @Test
    fun aPickerKeepsTheChainItOpenedOnWhenTheDirectionTurnsMeanwhile() {
        val manga = prefs.aiModel(AiStep.UPSCALE, ChainKind.MANGA)
        val viewer = open()
        val sheet = ReaderSheet(viewer).tap(R.id.sheet_ai_upscale)
        val dialog = latestDialogOf(viewer) as AlertDialog
        val family = ModelPickerTest.rows(dialog).first { ModelPickerTest.name(it) == "Anime4K B (clean) 2×" }
        ModelPickerTest.slide(family, 1)
        idle()
        // The book turns right to left (as a late direction detection does) while the picker shows Other's picks.
        sheet.tap(R.id.btn_dir_rtl)
        assertEquals(ChainKind.MANGA, viewer.aiKindRun())
        ModelPickerTest.slide(family, 3)
        idle()
        dialog.dismiss()
        idle()
        assertEquals("anime4k-b-vl", Prefs(context).aiModel(AiStep.UPSCALE, ChainKind.OTHER))
        assertEquals(manga, Prefs(context).aiModel(AiStep.UPSCALE, ChainKind.MANGA))
    }

    private val store get() = ModelStore.of(context)

    private fun level(id: String) = Models.byId(id) as FileSet

    /** Puts [id]'s files in the store as a finished download would. */
    private fun install(id: String) {
        val m = level(id)
        store.dir(m).mkdirs()
        for (f in m.files) {
            RandomAccessFile(File(store.dir(m), f.name), "rw").use { it.setLength(f.bytes) }
            File(store.dir(m), f.name + ModelStore.STAMP).writeText(f.sha256)
        }
    }

    private fun asked(id: String) = ModelDownloads.state.value.states.containsKey(id)

    /**
     * Opens the upscale picker, lets the Anime4K B slider go at M, does [between], lets it go
     * at VL, does [atVl] and closes it; returns once the downloads queued as it closed ran
     * (deletes first, then VL's, which fails offline).
     */
    private fun slideMThenVl(between: () -> Unit = {}, atVl: () -> Unit = {}) {
        val (dialog, rows) = upscaleChoices(open())
        val family = rows.first { ModelPickerTest.name(it) == "Anime4K B (clean) 2×" }
        ModelPickerTest.slide(family, 1)
        idle()
        between()
        ModelPickerTest.slide(family, 3)
        idle()
        assertEquals("anime4k-b-vl", Prefs(context).aiModel(AiStep.UPSCALE))
        atVl()
        dialog.dismiss()
        idle()
        // Waits for VL's download to fail offline, its turn on the one app-wide download worker coming whenever it comes.
        runBlocking { ModelDownloads.state.first { it.states["anime4k-b-vl"] is DownloadState.Failed } }
    }

    @Test
    fun theLevelPickedLastDownloadsAsThePickerCloses() {
        slideMThenVl(atVl = { assertFalse(asked("anime4k-b-m")) })
        assertFalse(asked("anime4k-b-m"))
        assertEquals(0L, store.bytesHeld(level("anime4k-b-m")))
    }

    @Test
    fun aLevelPassedOverStopsTheDownloadItsPickBroughtAtOnce() {
        slideMThenVl(
            // The pages shown ask for M, as the reader does for a model its chain runs.
            between = {
                ModelDownloads.start(store, level("anime4k-b-m"), optional = true)
                assertTrue(asked("anime4k-b-m"))
            },
            atVl = { assertFalse(asked("anime4k-b-m")) }
        )
        assertFalse(asked("anime4k-b-m"))
    }

    @Test
    fun aLevelPassedOverIsDeletedWhenItsPickDownloadedIt() {
        slideMThenVl(between = { install("anime4k-b-m") })
        assertFalse(store.dir(level("anime4k-b-m")).exists())
    }

    @Test
    fun aLevelPassedOverStaysWhenTheOtherDefaultChainRunsIt() {
        prefs.setAiModel(AiStep.UPSCALE, "anime4k-b-m", ChainKind.MANGA)
        slideMThenVl(between = { install("anime4k-b-m") })
        assertTrue(store.installed(level("anime4k-b-m")))
    }

    @Test
    fun aLevelPassedOverKeepsTheDownloadStartedBeforeThePicker() {
        // Parts of a download the user started before, cut short.
        val m = level("anime4k-b-m")
        store.dir(m).mkdirs()
        store.part(m, m.files.first()).writeBytes(ByteArray(16))
        slideMThenVl()
        assertEquals(16L, store.bytesHeld(m))
    }

    private fun kindShown(sheet: ReaderSheet) = sheet.view<MaterialButtonToggleGroup>(R.id.sheet_ai_kind).checkedButtonId

    /** Picks OpenComic 2× in the sheet's upscale picker. */
    private fun pickOpenComic(viewer: ViewerActivity, sheet: ReaderSheet) {
        sheet.tap(R.id.sheet_ai_upscale)
        ModelPickerTest.tap(ModelPickerTest.rows(latestDialogOf(viewer)).first { ModelPickerTest.name(it) == "OpenComic 2×" })
        idle()
    }

    @Test
    fun aBookReadLeftToRightRunsEverythingElseAndAKindPickedGoesToItsFolder() {
        val viewer = open()
        val sheet = ReaderSheet(viewer)
        assertEquals(R.id.btn_ai_other, kindShown(sheet))
        assertEquals(View.VISIBLE, sheet.view<View>(R.id.sheet_ai_kind_row).visibility)
        assertTrue("whole folder by default", sheet.view<MaterialSwitch>(R.id.sheet_ai_kind_folder).isChecked)
        assertTrue(sheet.view<TextView>(R.id.sheet_ai_hint).text.contains(context.getString(R.string.ai_kind_auto_hint)))
        sheet.tap(R.id.btn_ai_manga)
        val library = Library(context)
        assertEquals(ChainKind.MANGA, library.folderChainKind(folder))
        assertNull(library.chainKind(BookIds.known(book)!!))
        assertEquals(R.id.btn_ai_manga, kindShown(sheet))
        assertFalse(sheet.view<TextView>(R.id.sheet_ai_hint).text.contains(context.getString(R.string.ai_kind_auto_hint)))
        // The pickers now change manga's default chain.
        pickOpenComic(viewer, sheet)
        assertEquals("opencomic-ai-upscale-2x-compact-v2", Prefs(context).aiModel(AiStep.UPSCALE, ChainKind.MANGA))
        assertEquals(ModelCatalog.DEFAULT_UPSCALE, Prefs(context).aiModel(AiStep.UPSCALE, ChainKind.OTHER))
        assertTrue(Prefs(context).aiEnabled)
    }

    @Test
    fun aKindPickedForThisBookAloneLeavesItsFolderAlone() {
        val viewer = open()
        val sheet = ReaderSheet(viewer)
        val library = Library(context)
        val id = BookIds.known(book)!!
        // Off: the kind shown becomes the book's own.
        sheet.tap(R.id.sheet_ai_kind_folder)
        assertFalse(sheet.view<MaterialSwitch>(R.id.sheet_ai_kind_folder).isChecked)
        assertEquals(ChainKind.OTHER, library.chainKind(id))
        sheet.tap(R.id.btn_ai_manga)
        assertEquals(ChainKind.MANGA, library.chainKind(id))
        assertNull(library.folderChainKind(folder))
        // On again: the folder takes the book's kind, which the book no longer keeps.
        sheet.tap(R.id.sheet_ai_kind_folder)
        assertNull(library.chainKind(id))
        assertEquals(ChainKind.MANGA, library.folderChainKind(folder))
        assertEquals(R.id.btn_ai_manga, kindShown(sheet))
    }

    @Test
    fun turningTheFolderSwitchOffAndOnAgainChangesNothing() {
        val viewer = open()
        val sheet = ReaderSheet(viewer)
        val library = Library(context)
        sheet.tap(R.id.sheet_ai_kind_folder).tap(R.id.sheet_ai_kind_folder)
        assertTrue(sheet.view<MaterialSwitch>(R.id.sheet_ai_kind_folder).isChecked)
        assertNull(library.chainKind(BookIds.known(book)!!))
        assertNull("its books still follow their directions", library.folderChainKind(folder))
        assertEquals(R.id.btn_ai_other, kindShown(sheet))
    }

    @Test
    fun aBookReadRightToLeftRunsTheMangaChain() {
        prefs.readingDir = "rtl"
        prefs.detectDirection = false
        val viewer = open()
        val sheet = ReaderSheet(viewer)
        assertEquals(R.id.btn_ai_manga, kindShown(sheet))
        assertEquals("the reader runs it too", ChainKind.MANGA, viewer.aiKindRun())
        pickOpenComic(viewer, sheet)
        assertEquals("opencomic-ai-upscale-2x-compact-v2", Prefs(context).aiModel(AiStep.UPSCALE, ChainKind.MANGA))
        assertEquals(ModelCatalog.DEFAULT_UPSCALE, Prefs(context).aiModel(AiStep.UPSCALE))
        // Its folder's kind wins over its direction.
        sheet.tap(R.id.btn_ai_other)
        assertEquals(R.id.btn_ai_other, kindShown(sheet))
        assertEquals(ChainKind.OTHER, Library(context).folderChainKind(folder))
        assertEquals(ChainKind.OTHER, viewer.aiKindRun())
    }

    @Test
    fun turningABookRightToLeftMakesItManga() {
        val viewer = open()
        val sheet = ReaderSheet(viewer)
        assertEquals(R.id.btn_ai_other, kindShown(sheet))
        assertEquals(ChainKind.OTHER, viewer.aiKindRun())
        sheet.tap(R.id.btn_dir_rtl)
        assertEquals(R.id.btn_ai_manga, kindShown(sheet))
        assertEquals("the reader runs it too", ChainKind.MANGA, viewer.aiKindRun())
    }

    @Test
    fun theLoadBarFollowsTheChainShown() {
        PageTimes.of(context).clear()
        val viewer = open()
        val sheet = ReaderSheet(viewer)
        val bar = sheet.view<AiLoadBar>(R.id.ai_load_bar)
        val upscale = ModelCatalog.byId(ModelCatalog.DEFAULT_UPSCALE)!!
        assertEquals(upscale.pageMs, bar.pageMs)
        assertEquals(AiLoadBar.describe(context, upscale.pageMs), bar.contentDescription)
        assertFalse("dimmed while AI processing is off", bar.isEnabled)
        pickOpenComic(viewer, sheet)
        assertTrue(bar.isEnabled)
        assertEquals(ModelCatalog.byId("opencomic-ai-upscale-2x-compact-v2")!!.pageMs, bar.pageMs)
        // Manga's chain, still the default one.
        sheet.tap(R.id.btn_ai_manga)
        assertEquals(upscale.pageMs, bar.pageMs)
        // This book's own, with nothing picked.
        Prefs(context).aiEnabled = false
        sheet.tap(R.id.btn_ai_book)
        assertEquals(0L, bar.pageMs)
        assertTrue("the book's own runs", bar.isEnabled)
    }

    @Test
    fun aBookWithItsOwnModelsHidesTheDefaultChainChoice() {
        val viewer = open()
        val sheet = ReaderSheet(viewer).tap(R.id.btn_ai_manga).tap(R.id.btn_ai_book)
        assertEquals(View.GONE, sheet.view<View>(R.id.sheet_ai_kind_row).visibility)
        // Its own picks start from the manga chain it ran.
        Prefs(context).setAiModel(AiStep.DESCREEN, "opencomic-ai-descreen-hard-compact-v2", ChainKind.MANGA)
        sheet.tap(R.id.btn_ai_default)
        assertEquals(View.VISIBLE, sheet.view<View>(R.id.sheet_ai_kind_row).visibility)
        Prefs(context).aiEnabled = true
        sheet.tap(R.id.btn_ai_book)
        assertEquals("opencomic-ai-descreen-hard-compact-v2", Library(context).aiPicks(BookIds.known(book)!!)!!.id(AiStep.DESCREEN))
    }
}
