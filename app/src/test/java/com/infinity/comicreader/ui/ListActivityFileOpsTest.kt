package com.infinity.comicreader.ui

import com.infinity.comicreader.testing.toggleFavorite
import com.infinity.comicreader.testing.ref
import com.infinity.comicreader.testing.bookId
import android.Manifest
import android.app.Application
import android.content.DialogInterface
import android.os.Environment
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.tabs.TabLayout
import com.infinity.comicreader.R
import com.infinity.comicreader.testing.idleUntil
import com.infinity.comicreader.compact.OutputMarks
import com.infinity.comicreader.data.BookIds
import com.infinity.comicreader.data.Bookmark
import com.infinity.comicreader.data.HistoryItem
import com.infinity.comicreader.data.MoreAction
import com.infinity.comicreader.data.Library
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.files.FileClipboard
import com.infinity.comicreader.files.FileWork
import com.infinity.comicreader.net.AesGcmBox
import com.infinity.comicreader.net.FakeRemoteFs
import com.infinity.comicreader.net.Remotes
import com.infinity.comicreader.net.ShareStore
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
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
import java.util.concurrent.Semaphore
import javax.crypto.spec.SecretKeySpec

/** Selecting items, the selection bar's file operations, the Paste button, and the library following what they change. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ListActivityFileOpsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var context: Application
    private lateinit var library: Library
    private lateinit var shelf: File
    private lateinit var other: File
    private lateinit var book: File
    private lateinit var bytes: ByteArray

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        shadowOf(context).grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE)
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        for (name in listOf("ui", "library", "favorite_kinds", "shares")) context.getSharedPreferences(name, 0).edit().clear().commit()
        shelf = File(Environment.getExternalStorageDirectory(), "shelf").apply {
            deleteRecursively()
            mkdirs()
        }
        other = File(shelf, "Other").apply { mkdirs() }
        bytes = ByteArray(5000) { (it * 7).toByte() }
        book = File(shelf, "one.cbz").apply { writeBytes(bytes) }
        library = Library(context)
        library.toggleFavorite(book)
        library.addHistory(HistoryItem(book.absolutePath, book.name, 3, 10, 1L, bookId(book)))
        library.setRead(ref(book), true)
        Prefs(context).lastPath = shelf.absolutePath
        // No covers: one being read would hold its file open, which some test hosts refuse to rename.
        Prefs(context).thumbnails = false
        FileClipboard.clear()
        // Library screens other tests left open watch the old state, so only this test's show outcomes.
        FileWork.reset()
        Remotes.serve(emptyList()) { throw AssertionError("no shares") }
    }

    @After
    fun tearDown() {
        settle()
        awaitLeftoverWork()
        launched.forEach { if (!it.get().isDestroyed) it.pause().stop().destroy() }
        FileClipboard.clear()
        Remotes.serve(emptyList()) { throw AssertionError("no shares") }
    }

    /** True once the screen waits on nothing but the test: listed, favorites classified, facts read, presence asked, its menu looked into. */
    private fun ListActivity.quiet() =
        listJob?.isCompleted == true && listOf(classifyJob, factsJob, presenceJob, moreLook).none { it?.isActive == true }

    /** True once the screen launched last, if any, is quiet. */
    private fun quiet() = launched.lastOrNull()?.get()?.quiet() != false

    /** Runs the main looper until the screen launched last is quiet. */
    private fun idle() = idleUntil("the library screen quiet") { quiet() }

    /** Waits until [done], then for the file operation under way to end, its outcome to be shown and the screen to list what it changed. */
    private fun settle(done: () -> Boolean = { true }) =
        idleUntil("the operation done and its outcome shown") { done() && FileWork.operations.value.isEmpty() && quiet() }

    private fun AlertDialog.title(): String = findViewById<android.widget.TextView>(androidx.appcompat.R.id.alertTitle)!!.text.toString()

    private fun AlertDialog.edit(): EditText = findEdit(window!!.decorView as android.view.ViewGroup)!!

    private val launched = ArrayList<ActivityController<ListActivity>>()

    /** A library screen; each goes at the end, so only the latest shows file operations' outcomes. */
    private fun launch(): ListActivity {
        awaitLeftoverWork()
        launched.forEach { if (!it.get().isDestroyed) it.pause().stop().destroy() }
        return Robolectric.buildActivity(ListActivity::class.java).also { launched += it }.setup().get().also { idle() }
    }

    private fun ListActivity.recycler(): RecyclerView = findViewById<RecyclerView>(R.id.recycler).apply {
        measure(0, 0)
        layout(0, 0, 1000, 4000)
    }

    /** The row of [path], waiting for a listing under way to show it. */
    private fun ListActivity.row(path: String): View {
        fun find(): View? {
            val recycler = recycler()
            val adapter = recycler.adapter as BookAdapter
            return recycler.findViewHolderForAdapterPosition(adapter.positionOf(path))?.itemView
        }
        idleUntil("the row of $path") { find() != null }
        return find()!!
    }

    /** Clicks Paste and waits for the dialog it asks with, once the names taken are looked up. */
    private fun ListActivity.pasteAsking(): AlertDialog {
        val before = ShadowDialog.getLatestDialog()
        paste().performClick()
        idleUntil("asked about names taken") { ShadowDialog.getLatestDialog().let { it != null && it !== before && it.isShowing } }
        return ShadowDialog.getLatestDialog() as AlertDialog
    }

    /** Waits until a dialog shows, as one asked once a search or a plan off the main thread is done. */
    private fun awaitDialog(what: String) = idleUntil(what) { ShadowDialog.getLatestDialog()?.isShowing == true }

    /** Long-presses the row of [path]: it is selected, or let go while selecting. */
    private fun ListActivity.select(path: String) {
        row(path).performLongClick()
        idle()
    }

    /** The selection bar's button [id]. */
    private fun ListActivity.bar(id: Int): View = findViewById(id)

    /** Selects [path] unless it is, and taps the bar's button [id]. */
    private fun ListActivity.act(path: String, id: Int) {
        if (selection.entries.none { it.path == path }) select(path)
        bar(id).performClick()
        idle()
    }

    /** The ⋮ menu's items for what is selected, those under "Convert ›" in its place. */
    private fun ListActivity.moreItems(): List<MoreAction> {
        bar(R.id.selection_more).performClick()
        idle()
        val menu = moreMenu ?: return emptyList()
        return (0 until menu.childCount).flatMap { i ->
            when (val tag = menu.getChildAt(i).tag) {
                is MoreAction -> listOf(tag)
                ListActivity.MORE_CONVERT_TAG -> {
                    menu.getChildAt(i).performClick()
                    val sub = moreMenu!!
                    val inner = (0 until sub.childCount).mapNotNull { sub.getChildAt(it).tag as? MoreAction }
                    sub.getChildAt(0).performClick()
                    inner
                }
                else -> throw AssertionError("tag $tag")
            }
        }
    }

    /** The ⋮ menu's row for [action], opening "Convert ›" when it is there. */
    private fun ListActivity.moreRow(action: MoreAction): View {
        fun rows() = moreMenu!!.let { menu -> (0 until menu.childCount).map { menu.getChildAt(it) } }
        rows().firstOrNull { it.tag == action }?.let { return it }
        rows().first { it.tag == ListActivity.MORE_CONVERT_TAG }.performClick()
        return rows().first { it.tag == action }
    }

    private fun View.usable(): Boolean = visibility == View.VISIBLE && isEnabled

    private fun latest() = ShadowDialog.getLatestDialog() as AlertDialog

    /** The newest Paste bubble's button. */
    private fun ListActivity.paste(): FloatingActionButton =
        stack.buttons().lastOrNull { it.first is Bubble.Paste }?.second ?: throw AssertionError("no Paste")

    /** True while a Paste bubble shows. */
    private fun ListActivity.pasteShown(): Boolean = stack.buttons().any { it.first is Bubble.Paste }

    private fun ListActivity.open(dir: File) {
        row(dir.absolutePath).performClick()
        idle()
    }

    @Test
    fun longPressSelectsAndTheBarReplacesTheMenu() {
        val activity = launch()
        activity.select(book.absolutePath)
        assertTrue(activity.selection.active)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.toolbar).visibility)
        val top = activity.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.selection_bar)
        assertEquals(View.VISIBLE, top.visibility)
        assertEquals(context.resources.getQuantityString(R.plurals.selected_count, 1, 1), top.title.toString())
        // The size of what is selected, where known.
        assertEquals(com.infinity.comicreader.util.FileUtils.formatSize(context, bytes.size.toLong()), top.subtitle.toString())
        val row = activity.findViewById<LinearLayout>(R.id.selection_actions)
        assertEquals(
            listOf(R.id.selection_move, R.id.selection_copy, R.id.selection_delete, R.id.selection_share, R.id.selection_rename, R.id.selection_favorite, R.id.selection_more),
            (0 until row.childCount).map { row.getChildAt(it).id }
        )
        assertTrue((0 until row.childCount).all { row.getChildAt(it).usable() })

        // A tap while selecting picks another item instead of opening it; Rename takes one.
        activity.row(other.absolutePath).performClick()
        idle()
        assertEquals(2, activity.selection.size)
        assertEquals(shelf.absolutePath, Prefs(context).lastPath)
        assertEquals(View.VISIBLE, activity.bar(R.id.selection_rename).visibility)
        assertFalse(activity.bar(R.id.selection_rename).isEnabled)
        // A folder's size is not known yet.
        assertNull(top.subtitle)

        // Letting go of the last one leaves selection.
        activity.row(other.absolutePath).performClick()
        activity.row(book.absolutePath).performClick()
        idle()
        assertFalse(activity.selection.active)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.toolbar).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.selection_actions).visibility)
    }

    @Test
    fun xBackAndSelectAllWorkTheSelection() {
        File(shelf, "two.cbz").writeBytes(ByteArray(10))
        val activity = launch()
        activity.select(book.absolutePath)
        val top = activity.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.selection_bar)
        top.menu.performIdentifierAction(R.id.menu_select_all, 0)
        idle()
        assertEquals(3, activity.selection.size)
        @Suppress("DEPRECATION")
        activity.onBackPressed()
        idle()
        assertFalse(activity.selection.active)
        // Back left selection, not the folder.
        assertEquals(shelf.absolutePath, Prefs(context).lastPath)

        activity.select(book.absolutePath)
        // The X leaves it too.
        val x = (0 until top.childCount).map { top.getChildAt(it) }.first { it is android.widget.ImageButton }
        x.performClick()
        idle()
        assertFalse(activity.selection.active)
    }

    @Test
    fun theMoreMenuOffersWhatAppliesInTheSettingsOrder() {
        val activity = launch()
        activity.select(book.absolutePath)
        // Read already; Peek is off until turned on; Book info for one book; an archive on the device can be compacted.
        assertEquals(listOf(MoreAction.MARK_UNREAD, MoreAction.BOOK_INFO, MoreAction.COMPACT, MoreAction.MAKE_COMPATIBLE), activity.moreItems())
        activity.select(book.absolutePath)
        activity.select(other.absolutePath)
        assertEquals(
            listOf(
                MoreAction.CONTINUE_READING, MoreAction.MARK_ALL_READ, MoreAction.MARK_ALL_UNREAD, MoreAction.SET_STARTUP, MoreAction.COMPACT,
                MoreAction.MAKE_COMPATIBLE, MoreAction.REPACK_CBZ, MoreAction.PACK, MoreAction.PACK_COMPACTED
            ),
            activity.moreItems()
        )
        // Both: what applies to all of them, nothing that takes one, and Pack turned off saying why.
        activity.select(book.absolutePath)
        assertEquals(
            listOf(
                MoreAction.MARK_UNREAD, MoreAction.MARK_ALL_READ, MoreAction.MARK_ALL_UNREAD, MoreAction.COMPACT, MoreAction.MAKE_COMPATIBLE,
                MoreAction.REPACK_CBZ, MoreAction.PACK, MoreAction.PACK_COMPACTED
            ),
            activity.moreItems()
        )

        // Settings reorder and hide items.
        Prefs(context).moreActions = com.infinity.comicreader.data.MoreActions.DEFAULT.moved(4, 0).with(MoreAction.MARK_ALL_READ, false)
        assertEquals(
            listOf(MoreAction.MARK_ALL_UNREAD, MoreAction.MARK_UNREAD, MoreAction.COMPACT, MoreAction.MAKE_COMPATIBLE, MoreAction.REPACK_CBZ, MoreAction.PACK, MoreAction.PACK_COMPACTED),
            activity.moreItems()
        )
    }

    @Test
    fun everyActionThatWritesANewBookSitsUnderConvert() {
        val activity = launch()
        activity.select(other.absolutePath)
        activity.bar(R.id.selection_more).performClick()
        idle()
        val main = activity.moreMenu!!
        val tags = (0 until main.childCount).map { main.getChildAt(it).tag }
        for (action in MoreAction.CONVERT_GROUP) assertFalse("$action under Convert", action in tags)
        val convert = (0 until main.childCount).map { main.getChildAt(it) }.single { it.tag == ListActivity.MORE_CONVERT_TAG }
        assertEquals("Convert ›", convert.findViewById<android.widget.TextView>(R.id.more_action_label).text.toString())
        convert.performClick()
        val sub = activity.moreMenu!!
        assertEquals(
            listOf(ListActivity.MORE_BACK_TAG, MoreAction.COMPACT, MoreAction.MAKE_COMPATIBLE, MoreAction.REPACK_CBZ, MoreAction.PACK, MoreAction.PACK_COMPACTED),
            (0 until sub.childCount).map { sub.getChildAt(it).tag }
        )
    }

    @Test
    fun continueReadingOpensTheNextBookInOneSelectedFolder() {
        val inside = File(other, "inside.cbz").apply { writeBytes(ByteArray(40) { 3 }) }
        val activity = launch()
        activity.select(other.absolutePath)
        activity.moreItems()
        activity.moreRow(MoreAction.CONTINUE_READING).performClick()
        var started: android.content.Intent? = null
        // The next book is found off the main thread.
        idleUntil("the next book opened") {
            started = started ?: shadowOf(activity).nextStartedActivity
            started != null
        }
        assertEquals(inside.absolutePath, started!!.getStringExtra(ViewerActivity.EXTRA_PATH))
        assertFalse(activity.selection.active)
    }

    @Test
    fun compactAsksFirstAndRemembersInPlace() {
        val activity = launch()
        activity.select(book.absolutePath)
        activity.select(other.absolutePath)
        activity.moreItems()
        activity.moreRow(MoreAction.COMPACT).performClick()
        awaitDialog("the folder searched and Compact asked")
        val dialog = latest()
        // The books among those selected; the warning; Skip hidden pages off each time.
        assertEquals(context.getString(R.string.compact_title_one, book.name), dialog.title())
        assertEquals(context.getString(R.string.compact_warning), dialog.findViewById<android.widget.TextView>(android.R.id.message)!!.text.toString())
        val inPlace = dialog.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.compact_in_place)!!
        assertFalse(inPlace.isChecked)
        assertFalse(dialog.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.compact_skip_hidden)!!.isChecked)
        inPlace.isChecked = true
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(Prefs(context).compactInPlace)
        assertFalse(activity.selection.active)
        // Queued; with no libjxl on the JVM it fails, leaving the book as it was.
        settle()
        assertEquals(context.getString(R.string.file_failed, "JPEG XL is not available"), ShadowToast.getTextOfLatestToast())
        assertArrayEquals(bytes, book.readBytes())
    }

    @Test
    fun compactingAFolderTakesEveryBookInside() {
        File(other, "b.cbz").writeBytes(ByteArray(20))
        File(other, "Sub").mkdirs()
        File(other, "Sub/c.cbr").writeBytes(ByteArray(20))
        File(other, ".hidden").mkdirs()
        File(other, ".hidden/d.cbz").writeBytes(ByteArray(20))
        File(other, "notes.txt").writeText("x")
        val activity = launch()
        activity.select(other.absolutePath)
        activity.moreItems()
        activity.moreRow(MoreAction.MAKE_COMPATIBLE).performClick()
        awaitDialog("the folder searched and Make compatible asked")
        assertEquals(context.resources.getQuantityString(R.plurals.compatible_title, 2, 2), latest().title())
        latest().getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
        idle()
    }

    @Test
    fun searchingFoldersShowsTheSpinnerAndTakesOneTapAtATime() {
        File(other, "b.cbz").writeBytes(ByteArray(20))
        val activity = launch()
        activity.select(other.absolutePath)
        val spinner = activity.findViewById<View>(R.id.progress)
        fun tap() {
            activity.bar(R.id.selection_more).performClick()
            activity.moreRow(MoreAction.COMPACT).performClick()
        }
        // The search's end waits on the main thread, which these taps hold.
        tap()
        assertEquals(View.VISIBLE, spinner.visibility)
        tap()
        awaitDialog("the folder searched and Compact asked")
        idle()
        assertEquals(View.GONE, spinner.visibility)
        assertEquals(1, ShadowDialog.getShownDialogs().count { it.isShowing })
        latest().getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
        idle()
    }

    /** The ⋮ menu's item [action] once what it depends on is known. */
    private fun ListActivity.moreItem(action: MoreAction): View {
        bar(R.id.selection_more).performClick()
        idle()
        return moreRow(action)
    }

    @Test
    fun packIsOffSayingWhyForAFolderHoldingMoreThanImages() {
        File(other, "1.jpg").writeBytes(byteArrayOf(1, 2))
        File(other, "notes.txt").writeText("x")
        val activity = launch()
        activity.select(other.absolutePath)
        val item = activity.moreItem(MoreAction.PACK)
        assertFalse(item.isEnabled)
        assertEquals(activity.getString(R.string.pack_why_other_files), item.findViewById<android.widget.TextView>(R.id.more_action_reason).text.toString())
        item.performClick()
        idle()
        assertTrue(activity.selection.active)
        assertTrue(other.exists())
    }

    @Test
    fun packIsOffSayingWhyWithBooksSelectedToo() {
        File(other, "1.jpg").writeBytes(byteArrayOf(1, 2))
        val activity = launch()
        activity.select(other.absolutePath)
        activity.select(book.absolutePath)
        val item = activity.moreItem(MoreAction.PACK)
        assertFalse(item.isEnabled)
        assertEquals(activity.getString(R.string.pack_why_books), item.findViewById<android.widget.TextView>(R.id.more_action_reason).text.toString())
    }

    /** Packs [other] from the menu, answering the unusual files question with [button]. */
    private fun packAnswering(button: Int): ListActivity {
        val activity = launch()
        activity.select(other.absolutePath)
        activity.moreItem(MoreAction.PACK).performClick()
        idle()
        latest().getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        idleUntil("asked about unusual files") { latest().title() == activity.getString(R.string.pack_unusual_title) }
        assertEquals(activity.getString(R.string.pack_unusual_title), latest().title())
        val listed = activity.getString(R.string.pack_unusual_hidden, ".cover.png") + ", " + activity.getString(R.string.pack_unusual_folder, ".thumbs") +
            ", " + activity.getString(R.string.pack_unusual_empty, "0.png")
        assertEquals(activity.getString(R.string.pack_unusual_message, listed), latest().findViewById<android.widget.TextView>(android.R.id.message)!!.text.toString())
        latest().getButton(button).performClick()
        idle()
        return activity
    }

    private fun unusualFiles() {
        File(other, "1.png").writeBytes(ByteArray(300) { 4 })
        File(other, ".cover.png").writeBytes(ByteArray(30) { 5 })
        File(other, "0.png").writeBytes(ByteArray(0))
        File(other, ".thumbs").mkdirs()
        File(other, ".thumbs/1.png").writeBytes(ByteArray(20) { 6 })
    }

    @Test
    fun aBookWhoseMarkSaysCompactedIsNotCompactedAgainButMadeCompatible() {
        val marked = File(shelf, "marked.cbz")
        java.util.zip.ZipOutputStream(marked.outputStream()).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("1.png"))
            zip.write(byteArrayOf(1, 2, 3))
            zip.closeEntry()
        }
        assertTrue(OutputMarks.write(marked, ByteArray(0), listOf(OutputMarks.Operation.COMPACT), BookIds.xxh3))
        val activity = launch()
        activity.select(marked.absolutePath)
        val compact = activity.moreItem(MoreAction.COMPACT)
        assertFalse(compact.isEnabled)
        assertEquals(activity.getString(R.string.rebuild_why_compacted, "Emanata"), compact.findViewById<android.widget.TextView>(R.id.more_action_reason).text.toString())
        assertTrue(activity.moreRow(MoreAction.MAKE_COMPATIBLE).isEnabled)
        // Not a solid archive's: no Repack as CBZ in the submenu.
        assertTrue(activity.moreMenu!!.let { menu -> (0 until menu.childCount).none { menu.getChildAt(it).tag == MoreAction.REPACK_CBZ } })
    }

    @Test
    fun aReadOnlyLocationTurnsRepackOffSayingWhyWithoutOpeningIt() {
        val activity = launch()
        activity.canWrite = { false }
        activity.select(book.absolutePath)
        activity.bar(R.id.selection_more).performClick()
        idle()
        val menu = activity.moreMenu!!
        val repack = (0 until menu.childCount).map { menu.getChildAt(it) }.first { it.tag == ListActivity.MORE_CONVERT_TAG }
        assertFalse(repack.isEnabled)
        assertEquals(activity.getString(R.string.rebuild_why_read_only), repack.findViewById<android.widget.TextView>(R.id.more_action_reason).text.toString())
        repack.performClick()
        idle()
        assertSame("the submenu stays shut", menu, activity.moreMenu)
    }

    @Test
    fun packAsksOnceAboutUnusualFilesAndYesPacksThem() {
        unusualFiles()
        packAnswering(DialogInterface.BUTTON_POSITIVE)
        val cbz = File(shelf, "Other.cbz")
        settle { cbz.exists() && !other.exists() }
        java.util.zip.ZipFile(cbz).use { z -> assertEquals(listOf(".cover.png", ".thumbs/1.png", "0.png", "1.png"), z.entries().toList().map { it.name }) }
    }

    @Test
    fun noLeavesUnusualFilesOutAndCancelPacksNothing() {
        unusualFiles()
        packAnswering(DialogInterface.BUTTON_NEGATIVE)
        val cbz = File(shelf, "Other.cbz")
        settle { cbz.exists() && !other.exists() }
        java.util.zip.ZipFile(cbz).use { z -> assertEquals(listOf("1.png"), z.entries().toList().map { it.name }) }
        cbz.delete()
        other.mkdirs()
        unusualFiles()
        packAnswering(DialogInterface.BUTTON_NEUTRAL)
        idle()
        assertFalse(cbz.exists())
        assertTrue(File(other, ".thumbs/1.png").exists())
    }

    @Test
    fun packingAFolderOfImagesMakesABookBesideItAndTheFolderGoes() {
        val images = (1..3).map { File(other, "$it.png").apply { writeBytes(ByteArray(300) { b -> (b + it).toByte() }) } }
        File(other, "Thumbs.db").writeBytes(byteArrayOf(1))
        val first = ref(images[0])
        library.setRead(first, true)
        val activity = launch()
        activity.select(other.absolutePath)
        val item = activity.moreItem(MoreAction.PACK)
        assertTrue(item.isEnabled)
        assertEquals(View.GONE, item.findViewById<View>(R.id.more_action_reason).visibility)
        item.performClick()
        idle()
        assertEquals(activity.getString(R.string.pack_title_one, "Other"), latest().title())
        assertEquals(activity.getString(R.string.pack_message_one), latest().findViewById<android.widget.TextView>(android.R.id.message)!!.text.toString())
        latest().getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        val cbz = File(shelf, "Other.cbz")
        settle { cbz.exists() && !other.exists() }
        assertFalse(activity.selection.active)
        java.util.zip.ZipFile(cbz).use { z -> assertEquals(listOf("1.png", "2.png", "3.png"), z.entries().toList().map { it.name }) }
        assertFalse(library.isRead(bookId(cbz)))
        assertEquals(context.resources.getQuantityString(R.plurals.packed_count, 1, 1), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun packingOverABookAsksAsPasteDoes() {
        File(other, "1.png").writeBytes(ByteArray(300) { 4 })
        val there = File(shelf, "Other.cbz").apply { writeText("there") }
        val activity = launch()
        activity.select(other.absolutePath)
        activity.moreItem(MoreAction.PACK).performClick()
        idle()
        latest().getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        idleUntil("asked about the name taken") { latest().title() == activity.getString(R.string.clash_title, "Other.cbz") }
        assertEquals(activity.getString(R.string.clash_title, "Other.cbz"), latest().title())
        latest().getButton(DialogInterface.BUTTON_NEUTRAL).performClick()
        val kept = File(shelf, "Other (2).cbz")
        settle { kept.exists() && !other.exists() }
        assertEquals("there", there.readText())
    }

    @Test
    fun cancelingCompactKeepsTheSelection() {
        val activity = launch()
        activity.select(book.absolutePath)
        activity.moreItems()
        activity.moreRow(MoreAction.COMPACT).performClick()
        idle()
        latest().getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
        idle()
        assertTrue(activity.selection.active)
        assertArrayEquals(bytes, book.readBytes())
    }

    @Test
    fun markingFromTheMenuMarksEveryBookSelected() {
        val two = File(shelf, "two.cbz").apply { writeBytes(ByteArray(30) { 1 }) }
        val activity = launch()
        activity.select(two.absolutePath)
        activity.select(book.absolutePath)
        assertEquals(listOf(MoreAction.MARK_READ, MoreAction.MARK_UNREAD, MoreAction.COMPACT, MoreAction.MAKE_COMPATIBLE), activity.moreItems())
        val read = activity.moreMenu!!.getChildAt(0)
        read.performClick()
        settle { library.isRead(bookId(two)) }
        assertTrue(library.isRead(bookId(book)))
        assertFalse(activity.selection.active)
    }

    @Test
    fun theButtonOpensOneAndMovesSeveralInTheirOwnColors() {
        val activity = launch()
        val fab = activity.findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.fab)
        fun color(attr: Int) = com.google.android.material.color.MaterialColors.getColor(fab, attr)
        val toggle = fab.backgroundTintList!!.defaultColor
        assertEquals(color(com.google.android.material.R.attr.colorPrimaryContainer), toggle)
        activity.select(book.absolutePath)
        assertEquals(context.getString(R.string.fab_open), fab.contentDescription)
        assertEquals(color(com.google.android.material.R.attr.colorSecondaryContainer), fab.backgroundTintList!!.defaultColor)
        activity.select(other.absolutePath)
        assertEquals(context.getString(R.string.file_op_move), fab.contentDescription)
        assertEquals(color(com.google.android.material.R.attr.colorTertiaryContainer), fab.backgroundTintList!!.defaultColor)
        fab.performClick()
        idle()
        assertEquals(listOf(book.absolutePath, other.absolutePath), FileClipboard.clips.value.single().items.map { it.path })
        assertTrue(FileClipboard.clips.value.single().move)
        assertFalse(activity.selection.active)

        // Paste never looks like the Move button above it: its own fill, Move's colors only on its badge.
        val paste = activity.paste()
        val badge = (paste.parent as View).findViewById<android.widget.ImageView>(R.id.op_badge)
        activity.select(book.absolutePath)
        activity.select(other.absolutePath)
        assertEquals(View.VISIBLE, paste.visibility)
        assertEquals(View.VISIBLE, badge.visibility)
        assertEquals(color(com.google.android.material.R.attr.colorSurfaceInverse), paste.backgroundTintList!!.defaultColor)
        assertNotEquals(fab.backgroundTintList!!.defaultColor, paste.backgroundTintList!!.defaultColor)
        assertEquals(fab.backgroundTintList!!.defaultColor, badge.backgroundTintList!!.defaultColor)
        // A copy's Paste joins under it, its badge in Copy's colors.
        activity.bar(R.id.selection_copy).performClick()
        idle()
        val copy = activity.paste()
        assertNotEquals(paste, copy)
        assertEquals(2, activity.stack.buttons().size)
        val copyBadge = (copy.parent as View).findViewById<android.widget.ImageView>(R.id.op_badge)
        assertEquals(color(com.google.android.material.R.attr.colorSecondary), copyBadge.backgroundTintList!!.defaultColor)
        assertEquals(color(com.google.android.material.R.attr.colorSurfaceInverse), copy.backgroundTintList!!.defaultColor)

        // Opening one: the book is read.
        activity.select(book.absolutePath)
        fab.performClick()
        idle()
        assertEquals(book.absolutePath, shadowOf(activity).nextStartedActivity.getStringExtra(ViewerActivity.EXTRA_PATH))
    }

    @Test
    fun severalSelectedAreDeletedAfterOneQuestion() {
        val two = File(shelf, "two.cbz").apply { writeBytes(ByteArray(30) { 1 }) }
        val activity = launch()
        activity.select(book.absolutePath)
        activity.select(two.absolutePath)
        activity.bar(R.id.selection_delete).performClick()
        idle()
        val ask = latest()
        assertEquals(context.resources.getQuantityString(R.plurals.delete_items_title, 2, 2), ask.title())
        // Books only: no word of folders.
        assertEquals(
            context.resources.getQuantityString(R.plurals.delete_items_msg, 2, 2, "Device"),
            ask.findViewById<android.widget.TextView>(android.R.id.message)!!.text.toString()
        )
        // Still selected until it goes ahead.
        assertTrue(activity.selection.active)
        ask.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        settle { !book.exists() && !two.exists() }
        assertFalse(activity.selection.active)
        assertEquals(context.resources.getQuantityString(R.plurals.deleted_format, 2, 2), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun aFolderIsSharedToo() {
        val activity = launch()
        activity.select(other.absolutePath)
        assertTrue(activity.bar(R.id.selection_share).usable())
    }

    @Test
    fun favoriteAddsAllOrRemovesAllWhenEachAlreadyIs() {
        val two = File(shelf, "two.cbz").apply { writeBytes(ByteArray(30) { 1 }) }
        val activity = launch()
        activity.select(book.absolutePath)
        activity.select(two.absolutePath)
        val favorite = activity.bar(R.id.selection_favorite)
        assertEquals(context.getString(R.string.toolbar_button_favorite), favorite.contentDescription)
        favorite.performClick()
        settle { library.isFavorite(bookId(two)) }
        assertTrue(library.isFavorite(bookId(book)))
        settle { ShadowToast.getTextOfLatestToast() == context.getString(R.string.favorite_added) }

        activity.select(book.absolutePath)
        activity.select(two.absolutePath)
        assertEquals(context.getString(R.string.selection_unfavorite), favorite.contentDescription)
        favorite.performClick()
        settle { !library.isFavorite(bookId(two)) }
        assertFalse(library.isFavorite(bookId(book)))
        settle { ShadowToast.getTextOfLatestToast() == context.getString(R.string.favorite_removed) }
    }

    /** Opens the Share dialog of [path] from the long-press menu. */
    private fun ListActivity.shareDialog(path: String): AlertDialog {
        act(path, R.id.selection_share)
        idle()
        return latest()
    }

    private fun AlertDialog.check(id: Int) = findViewById<android.widget.CompoundButton>(id)!!

    @Test
    fun shareSendsTheChoiceAndRemembersIt() {
        val activity = launch()
        val dialog = activity.shareDialog(book.absolutePath)
        assertEquals("one", dialog.findViewById<android.widget.TextView>(R.id.share_title_text)!!.text.toString())
        assertTrue(dialog.check(R.id.share_title_check).isChecked)
        assertFalse(dialog.check(R.id.share_file_check).isChecked)
        // Not a real book: no cover, so it cannot be picked.
        settle { !dialog.check(R.id.share_cover_check).isEnabled }
        assertFalse(dialog.check(R.id.share_cover_check).isChecked)
        assertEquals(View.VISIBLE, dialog.findViewById<View>(R.id.share_cover_none)!!.visibility)

        dialog.check(R.id.share_title_check).isChecked = false
        assertFalse(dialog.getButton(DialogInterface.BUTTON_POSITIVE).isEnabled)
        dialog.findViewById<View>(R.id.share_file_row)!!.performClick()
        assertTrue(dialog.check(R.id.share_file_check).isChecked)
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        // The dialog closes, then the file's URI is made off the main thread and the chooser starts.
        settle { !dialog.isShowing && shadowOf(context).peekNextStartedActivity() != null }

        val chooser = shadowOf(context).nextStartedActivity
        assertEquals(android.content.Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<android.content.Intent>(android.content.Intent.EXTRA_INTENT)!!
        assertEquals(android.content.Intent.ACTION_SEND, send.action)
        assertEquals("application/vnd.comicbook+zip", send.type)
        assertNull(send.getStringExtra(android.content.Intent.EXTRA_TEXT))
        @Suppress("DEPRECATION")
        val uri = send.getParcelableExtra<android.net.Uri>(android.content.Intent.EXTRA_STREAM)!!
        assertEquals(book.absolutePath, com.infinity.comicreader.files.BookUris.pathOf(context, uri))
        assertTrue(send.flags and android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)

        // The app stays running while the file may be read.
        assertEquals(com.infinity.comicreader.files.SharingService::class.java.name, shadowOf(context).nextStartedService.component!!.className)

        // The choice is remembered; the cover stays as it was, unseen here.
        assertEquals(com.infinity.comicreader.data.ShareChoice(title = false, cover = true, file = true), Prefs(context).shareChoice)
        val again = activity.shareDialog(book.absolutePath)
        assertFalse(again.check(R.id.share_title_check).isChecked)
        assertTrue(again.check(R.id.share_file_check).isChecked)
        again.dismiss()
    }

    @Test
    fun aFolderIsSharedByTitleAndCoverOnly() {
        Prefs(context).shareChoice = com.infinity.comicreader.data.ShareChoice(title = true, cover = false, file = true)
        val activity = launch()
        val dialog = activity.shareDialog(other.absolutePath)
        assertEquals(View.GONE, dialog.findViewById<View>(R.id.share_file_row)!!.visibility)
        assertFalse(dialog.check(R.id.share_file_check).isChecked)
        assertEquals("Other", dialog.findViewById<android.widget.TextView>(R.id.share_title_text)!!.text.toString())
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        settle { !dialog.isShowing && shadowOf(context).peekNextStartedActivity() != null }

        @Suppress("DEPRECATION")
        val send = shadowOf(context).nextStartedActivity.getParcelableExtra<android.content.Intent>(android.content.Intent.EXTRA_INTENT)!!
        assertEquals("Other", send.getStringExtra(android.content.Intent.EXTRA_TEXT))
        @Suppress("DEPRECATION")
        assertNull(send.getParcelableExtra<android.net.Uri>(android.content.Intent.EXTRA_STREAM))
        // Nothing to read: the app is not kept running, and books keep their file choice.
        assertNull(shadowOf(context).nextStartedService)
        assertTrue(Prefs(context).shareChoice.file)
    }

    @Test
    fun anUntickedCoverIsMadeOnlyOnceTicked() {
        Prefs(context).shareChoice = com.infinity.comicreader.data.ShareChoice(title = true, cover = false, file = false)
        val activity = launch()
        val before = BookShare.coversMade
        val dialog = activity.shareDialog(book.absolutePath)
        assertEquals(before, BookShare.coversMade)
        val note = dialog.findViewById<android.widget.TextView>(R.id.share_cover_none)!!
        assertEquals(context.getString(R.string.share_cover), note.text.toString())
        assertTrue(dialog.check(R.id.share_cover_check).isEnabled)

        dialog.findViewById<View>(R.id.share_cover_row)!!.performClick()
        assertEquals(before + 1, BookShare.coversMade)
        settle { !dialog.check(R.id.share_cover_check).isEnabled }
        assertEquals(context.getString(R.string.share_no_cover), note.text.toString())
        // Ticking again makes nothing more.
        assertEquals(before + 1, BookShare.coversMade)
        dialog.dismiss()
    }

    @Test
    fun aCoverThatCannotBeMadeIsLeftOutAndSaid() {
        // Sent before the cover is found missing: each cover waits for its Share tap.
        val taps = Semaphore(0)
        BookShare.coverHold = { taps.acquire() }
        try {
            Prefs(context).shareChoice = com.infinity.comicreader.data.ShareChoice(title = true, cover = false, file = false)
            val activity = launch()
            val dialog = activity.shareDialog(book.absolutePath)
            dialog.check(R.id.share_cover_check).isChecked = true
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            taps.release()
            settle { !dialog.isShowing && shadowOf(context).peekNextStartedActivity() != null }
            assertEquals(context.getString(R.string.share_cover_left_out), ShadowToast.getTextOfLatestToast())
            @Suppress("DEPRECATION")
            val send = shadowOf(context).nextStartedActivity.getParcelableExtra<android.content.Intent>(android.content.Intent.EXTRA_INTENT)!!
            assertEquals("one", send.getStringExtra(android.content.Intent.EXTRA_TEXT))
            @Suppress("DEPRECATION")
            assertNull(send.getParcelableExtra<android.net.Uri>(android.content.Intent.EXTRA_STREAM))

            // The cover alone: nothing is sent.
            Prefs(context).shareChoice = com.infinity.comicreader.data.ShareChoice(title = false, cover = false, file = false)
            val alone = activity.shareDialog(book.absolutePath)
            alone.check(R.id.share_cover_check).isChecked = true
            alone.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            taps.release()
            settle { !alone.isShowing }
            assertEquals(context.getString(R.string.share_cover_failed), ShadowToast.getTextOfLatestToast())
            assertNull(shadowOf(context).nextStartedActivity)
        } finally {
            BookShare.coverHold = {}
            taps.release(2)
        }
    }

    @Test
    fun favoritesAndHistoryHaveFileOperations() {
        val activity = launch()
        for (tab in listOf(1, 2)) {
            activity.findViewById<TabLayout>(R.id.tabs).getTabAt(tab)!!.select()
            idle()
            activity.select(book.absolutePath)
            for (id in listOf(R.id.selection_delete, R.id.selection_rename, R.id.selection_move, R.id.selection_copy)) assertTrue(activity.bar(id).usable())
            activity.select(book.absolutePath)
            assertFalse(activity.selection.active)
        }
    }

    @Test
    fun switchingTabsEndsTheSelection() {
        val activity = launch()
        activity.select(book.absolutePath)
        activity.findViewById<TabLayout>(R.id.tabs).getTabAt(1)!!.select()
        idle()
        assertFalse(activity.selection.active)
    }

    @Test
    fun renamingInFavoritesListsItRenamed() {
        val activity = launch()
        activity.findViewById<TabLayout>(R.id.tabs).getTabAt(1)!!.select()
        idle()
        activity.act(book.absolutePath, R.id.selection_rename)
        idle()
        val ask = latest()
        ask.edit().setText("two.cbz")
        ask.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        val renamed = File(shelf, "two.cbz")
        settle { renamed.exists() }
        assertTrue(library.isFavorite(bookId(renamed)))
        activity.row(renamed.absolutePath)
    }

    @Test
    fun movePickedUpInHistorySaysToPasteInBrowse() {
        val activity = launch()
        activity.findViewById<TabLayout>(R.id.tabs).getTabAt(2)!!.select()
        idle()
        activity.act(book.absolutePath, R.id.selection_move)
        idle()
        assertEquals(context.getString(R.string.paste_hint_browse), ShadowToast.getTextOfLatestToast())
        assertEquals(book.absolutePath, FileClipboard.clips.value.single().items.single().path)
        // Its bubble shows here too, and says where it pastes.
        ShadowToast.reset()
        activity.paste().performClick()
        idle()
        assertEquals(context.getString(R.string.paste_hint_browse), ShadowToast.getTextOfLatestToast())
        assertTrue(book.exists())

        activity.findViewById<TabLayout>(R.id.tabs).getTabAt(0)!!.select()
        idle()
        activity.open(other)
        activity.paste().performClick()
        settle { !book.exists() }
        assertTrue(File(other, "one.cbz").isFile)
        assertEquals(3, library.historyFor(bookId(File(other, "one.cbz")))?.page)
    }

    @Test
    fun deletingFromHistoryForgetsIt() {
        val id = bookId(book)
        val activity = launch()
        activity.findViewById<TabLayout>(R.id.tabs).getTabAt(2)!!.select()
        idle()
        activity.act(book.absolutePath, R.id.selection_delete)
        idle()
        latest().getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        settle { !book.exists() }
        assertNull(library.historyFor(id))
        assertEquals(-1, (activity.recycler().adapter as BookAdapter).positionOf(book.absolutePath))
    }

    @Test
    fun aLocationRootCanStillBeTheStartupFolder() {
        Prefs(context).lastPath = Environment.getExternalStorageDirectory().absolutePath
        val activity = launch()
        activity.select(shelf.absolutePath)
        assertTrue(MoreAction.SET_STARTUP in activity.moreItems())
    }

    @Test
    fun bookmarksOfferNoFileOperationsAndRemoveBookmarks() {
        library.addBookmark(Bookmark(book.absolutePath, book.name, 1, "", 1L, bookId(book)))
        val activity = launch()
        activity.findViewById<TabLayout>(R.id.tabs).getTabAt(3)!!.select()
        idle()
        activity.recycler().findViewHolderForAdapterPosition(0)!!.itemView.performLongClick()
        idle()
        for (id in listOf(R.id.selection_move, R.id.selection_copy, R.id.selection_rename)) assertEquals(View.GONE, activity.bar(id).visibility)
        val remove = activity.bar(R.id.selection_delete)
        assertEquals(context.getString(R.string.selection_remove), remove.contentDescription)
        remove.performClick()
        idle()
        latest().getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        idle()
        assertTrue(library.bookmarks().isEmpty())
        assertTrue(book.isFile)
    }

    @Test
    fun renamingTakesTheLibraryAlong() {
        val activity = launch()
        activity.act(book.absolutePath, R.id.selection_rename)
        idle()
        val ask = latest()
        ask.edit().setText("two.cbz")
        ask.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        val renamed = File(shelf, "two.cbz")
        settle { renamed.exists() }
        assertTrue(renamed.isFile)
        assertFalse(book.exists())
        assertTrue(library.isFavorite(bookId(renamed)))
        assertTrue(library.isRead(bookId(renamed)))
        assertEquals(3, library.historyFor(bookId(renamed))?.page)
        assertEquals(context.getString(R.string.renamed), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun renamingChangesTheCaseOnly() {
        val activity = launch()
        activity.act(book.absolutePath, R.id.selection_rename)
        idle()
        val ask = latest()
        ask.edit().setText("One.cbz")
        ask.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        settle { "One.cbz" in shelf.list()!! }
        assertEquals(listOf("One.cbz", "Other"), shelf.list()!!.sorted())
        assertTrue(library.isFavorite(bookId(File(shelf, "One.cbz"))))
        assertEquals(context.getString(R.string.renamed), ShadowToast.getTextOfLatestToast())
    }

    private fun findEdit(group: android.view.ViewGroup): EditText? {
        for (i in 0 until group.childCount) {
            when (val c = group.getChildAt(i)) {
                is EditText -> return c
                is android.view.ViewGroup -> findEdit(c)?.let { return it }
            }
        }
        return null
    }

    @Test
    fun deletingAsksFirstAndForgetsTheItem() {
        val id = bookId(book)
        val facts = com.infinity.comicreader.data.BookFactsCache(context)
        facts.put(id, com.infinity.comicreader.data.BookFacts(3, 0))
        val activity = launch()
        activity.act(book.absolutePath, R.id.selection_delete)
        idle()
        latest().getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
        idle()
        assertTrue(book.isFile)

        activity.act(book.absolutePath, R.id.selection_delete)
        idle()
        val ask = latest()
        assertEquals(context.getString(R.string.delete_item_title, book.name), ask.title())
        ask.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        settle { !book.exists() }
        assertFalse(book.exists())
        assertFalse(library.isFavorite(id))
        assertNull(library.historyFor(id))
        // Its facts go with it; its read mark stays for copies elsewhere.
        assertNull(facts.get(id))
        assertTrue(library.isRead(id))
    }

    @Test
    fun aDeletedFolderLeavesNoForwardCrumb() {
        val activity = launch()
        activity.open(other)
        @Suppress("DEPRECATION")
        activity.onBackPressed()
        idle()
        val bar = activity.findViewById<CrumbBar>(R.id.pathbar)
        assertTrue(bar.crumbs.any { it.label == "Other" })
        activity.act(other.absolutePath, R.id.selection_delete)
        idle()
        latest().getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        settle { !other.exists() }
        assertFalse(bar.crumbs.any { it.label == "Other" })
    }

    @Test
    fun movePicksUpAndPasteMovesIntoTheFolderBrowsed() {
        val activity = launch()
        assertFalse(activity.pasteShown())
        activity.act(book.absolutePath, R.id.selection_move)
        idle()
        assertEquals(View.VISIBLE, activity.paste().visibility)
        activity.open(other)
        activity.paste().performClick()
        val moved = File(other, "one.cbz")
        settle { !book.exists() }
        assertArrayEquals(bytes, moved.readBytes())
        assertFalse(book.exists())
        assertTrue(library.isFavorite(bookId(moved)))
        assertEquals(3, library.historyFor(bookId(moved))?.page)
        // A move is pasted once.
        assertTrue(FileClipboard.clips.value.isEmpty())
        assertFalse(activity.pasteShown())
    }

    @Test
    fun aTakenNameAsksReplaceKeepBothOrSkip() {
        File(other, "one.cbz").writeBytes(ByteArray(3))
        val activity = launch()
        activity.act(book.absolutePath, R.id.selection_copy)
        idle()
        activity.open(other)

        val ask = activity.pasteAsking()
        assertEquals(context.getString(R.string.clash_title, "one.cbz"), ask.title())
        ask.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
        idle()
        assertEquals(listOf("one.cbz"), other.list()!!.sorted())
        // Skipping the only one is the pick done with.
        assertFalse(activity.pasteShown())

        pickUp(book, move = false)
        activity.pasteAsking().getButton(DialogInterface.BUTTON_NEUTRAL).performClick()
        settle { File(other, "one (2).cbz").exists() }
        assertEquals(listOf("one (2).cbz", "one.cbz"), other.list()!!.sorted())
        assertArrayEquals(bytes, File(other, "one (2).cbz").readBytes())
        // A copy is pasted once too.
        assertTrue(FileClipboard.clips.value.isEmpty())

        pickUp(book, move = false)
        activity.pasteAsking().getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        settle { File(other, "one.cbz").length() == bytes.size.toLong() }
        assertArrayEquals(bytes, File(other, "one.cbz").readBytes())
        assertTrue(book.isFile)

        // A long press on Paste drops it.
        pickUp(book, move = false)
        idle()
        activity.paste().performLongClick()
        idle()
        assertTrue(FileClipboard.clips.value.isEmpty())
        assertEquals(context.getString(R.string.paste_canceled), ShadowToast.getTextOfLatestToast())
        assertFalse(activity.pasteShown())
    }

    @Test
    fun aNameTakenInAnotherCaseAsksToo() {
        File(other, "ONE.cbz").writeBytes(ByteArray(3))
        // Only where the storage ignores case, as the device's shared storage does.
        assumeTrue(File(other, "one.cbz").exists())
        val activity = launch()
        activity.act(book.absolutePath, R.id.selection_copy)
        idle()
        activity.open(other)
        activity.pasteAsking().getButton(DialogInterface.BUTTON_NEUTRAL).performClick()
        settle { other.list()!!.size == 2 }
        assertEquals(listOf("ONE.cbz", "one (2).cbz"), other.list()!!.sorted())
    }

    @Test
    fun replacingAFolderSaysItIsReplacedWholeAndNeverWhatHoldsTheItem() {
        File(shelf, "Sub").mkdirs()
        File(other, "Sub").mkdirs()
        File(other, "Other").mkdirs()
        val activity = launch()
        activity.act(File(shelf, "Sub").absolutePath, R.id.selection_copy)
        idle()
        activity.open(other)
        val ask = activity.pasteAsking()
        assertEquals(context.getString(R.string.clash_folder), ask.findViewById<android.widget.TextView>(android.R.id.message)!!.text.toString())
        ask.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
        idle()

        // An item inside the folder its name takes: no Replace.
        activity.act(File(other, "Other").absolutePath, R.id.selection_move)
        idle()
        @Suppress("DEPRECATION")
        activity.onBackPressed()
        idle()
        val inside = activity.pasteAsking()
        assertEquals(View.GONE, inside.getButton(DialogInterface.BUTTON_POSITIVE).visibility)
        assertEquals(View.VISIBLE, inside.getButton(DialogInterface.BUTTON_NEUTRAL).visibility)
    }

    @Test
    fun aCopyBesideItselfKeepsBoth() {
        val activity = launch()
        activity.act(book.absolutePath, R.id.selection_copy)
        idle()
        activity.paste().performClick()
        settle { File(shelf, "one (2).cbz").exists() }
        assertArrayEquals(bytes, File(shelf, "one (2).cbz").readBytes())
    }

    /** Picks up [files] together, as a selection of them does. */
    private fun pickUp(vararg files: File, move: Boolean) {
        FileClipboard.add(FileClipboard.Clip(files.map { FileClipboard.Item(it.absolutePath, it.isDirectory) }, move))
    }

    @Test
    fun severalItemsMoveAsOneOperationAndTakeTheLibraryAlong() {
        val two = File(shelf, "two.cbz").apply { writeBytes(ByteArray(300) { 3 }) }
        val sub = File(shelf, "Sub").apply { mkdirs() }
        File(sub, "inner.cbz").writeBytes(ByteArray(10))
        pickUp(book, two, sub, move = true)
        val activity = launch()
        activity.open(other)
        assertEquals(context.resources.getQuantityString(R.plurals.paste_count, 3, 3), activity.paste().contentDescription)
        activity.paste().performClick()
        settle { !book.exists() && !two.exists() && !sub.exists() }
        assertEquals(listOf("Sub", "one.cbz", "two.cbz"), other.list()!!.sorted())
        assertTrue(File(other, "Sub/inner.cbz").isFile)
        assertTrue(library.isFavorite(bookId(File(other, "one.cbz"))))
        assertEquals(context.resources.getQuantityString(R.plurals.moved_count, 3, 3), ShadowToast.getTextOfLatestToast())
        // A move is pasted once.
        assertTrue(FileClipboard.clips.value.isEmpty())
    }

    @Test
    fun takenNamesAreAskedOnceAndSkippingPastesTheRest() {
        val two = File(shelf, "two.cbz").apply { writeBytes(ByteArray(300) { 3 }) }
        val three = File(shelf, "three.cbz").apply { writeBytes(ByteArray(200) { 4 }) }
        File(other, "one.cbz").writeBytes(ByteArray(3))
        File(other, "two.cbz").writeBytes(ByteArray(3))
        pickUp(book, two, three, move = false)
        val activity = launch()
        activity.open(other)
        val ask = activity.pasteAsking()
        assertEquals(context.resources.getQuantityString(R.plurals.clash_many_title, 2, 2), ask.title())
        ask.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
        settle { File(other, "three.cbz").exists() }
        assertEquals(3L, File(other, "one.cbz").length())
        assertEquals(context.getString(R.string.file_copied), ShadowToast.getTextOfLatestToast())

        // Keep both names each anew, picked up again: a copy is pasted once.
        pickUp(book, two, three, move = false)
        idle()
        activity.pasteAsking().getButton(DialogInterface.BUTTON_NEUTRAL).performClick()
        settle { File(other, "one (2).cbz").exists() && File(other, "two (2).cbz").exists() && File(other, "three (2).cbz").exists() }
        assertArrayEquals(bytes, File(other, "one (2).cbz").readBytes())
        assertEquals(context.resources.getQuantityString(R.plurals.copied_count, 3, 3), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun itemsOfOneNameFromTwoFoldersKeepBoth() {
        val twin = File(other, "one.cbz").apply { writeBytes(ByteArray(40) { 9 }) }
        val target = File(shelf, "Target").apply { mkdirs() }
        pickUp(book, twin, move = false)
        val activity = launch()
        activity.open(target)
        activity.paste().performClick()
        settle { target.list()!!.size == 2 }
        assertEquals(listOf("one (2).cbz", "one.cbz"), target.list()!!.sorted())
        assertArrayEquals(bytes, File(target, "one.cbz").readBytes())
        assertEquals(40L, File(target, "one (2).cbz").length())
    }

    @Test
    fun aFolderNeverGoesInsideItself() {
        val activity = launch()
        activity.act(other.absolutePath, R.id.selection_move)
        idle()
        activity.open(other)
        activity.paste().performClick()
        idle()
        assertEquals(context.getString(R.string.paste_into_itself), ShadowToast.getTextOfLatestToast())
        assertTrue(other.isDirectory)
    }

    @Test
    fun listingAFolderPutsBackWhatAnUnfinishedMoveSetAside() {
        File(other, ".kept.cbz.1.emanata-old").writeBytes(bytes)
        File(other, ".copy.cbz.1.emanata-new").writeBytes(ByteArray(3))
        val activity = launch()
        activity.open(other)
        activity.row(File(other, "kept.cbz").absolutePath)
        assertEquals(listOf("kept.cbz"), other.list()!!.toList())
        assertArrayEquals(bytes, File(other, "kept.cbz").readBytes())
    }

    @Test
    fun cancelShowsOnlyWhileItCanStopTheStepUnderWay() {
        val activity = launch()
        val go = Semaphore(0)
        val at = Semaphore(0)
        FileWork.start(FileWork.Kind.COPY, "big.cbz", into = "Other") { p ->
            p.report(5, 10)
            at.release()
            go.acquire()
            p.stoppable(false)
            at.release()
            go.acquire()
            FileWork.Outcome.Done(null)
        }
        at.acquire()
        idle()
        activity.stack.buttons().single().second.performClick()
        idle()
        val card = activity.stack.cardView()!!
        assertEquals(
            context.getString(R.string.op_into, context.resources.getQuantityString(R.plurals.op_copying, 1, 1), "Other"),
            card.findViewById<android.widget.TextView>(R.id.op_title).text.toString()
        )
        val cancel = card.findViewById<View>(R.id.op_cancel)
        assertEquals(View.VISIBLE, cancel.visibility)
        go.release()
        at.acquire()
        idle()
        assertEquals(View.GONE, cancel.visibility)
        go.release()
        settle()
        // The card goes with its operation.
        assertNull(activity.stack.cardView())
    }

    /** Serves a share from a folder, returned. */
    private fun serveShare(): File {
        val shareDir = tmp.newFolder("share")
        val fake = FakeRemoteFs(shareDir)
        ShareStore.defaultBox = AesGcmBox { SecretKeySpec(ByteArray(32) { it.toByte() }, "AES") }
        ShareStore(context).put(FakeRemoteFs.share())
        Remotes.init(context)
        Remotes.connector = { fake }
        return shareDir
    }

    @Test
    fun makesNewFoldersOnShares() {
        val shareDir = serveShare()
        Prefs(context).lastPath = "/net/s1"
        val activity = launch()
        val item = activity.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar).menu.findItem(R.id.menu_new_folder)
        activity.onOptionsItemSelected(item)
        idle()
        val ask = latest()
        ask.edit().setText("Fresh")
        ask.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        settle { File(shareDir, "Fresh").isDirectory }
        activity.row("/net/s1/Fresh")
    }

    @Test
    fun copiesOntoAShareAndRenamesThere() {
        val shareDir = serveShare()
        val activity = launch()
        activity.act(book.absolutePath, R.id.selection_copy)
        idle()
        Prefs(context).lastPath = "/net/s1"
        val share = launch()
        idle()
        assertEquals(View.VISIBLE, share.paste().visibility)
        share.paste().performClick()
        settle { File(shareDir, "one.cbz").exists() }
        assertArrayEquals(bytes, File(shareDir, "one.cbz").readBytes())
        // Only the finished copy is there.
        assertEquals(listOf("one.cbz"), shareDir.list()!!.toList())

        share.act("/net/s1/one.cbz", R.id.selection_rename)
        idle()
        val ask = latest()
        ask.edit().setText("renamed.cbz")
        ask.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        settle { File(shareDir, "renamed.cbz").exists() }
        assertEquals(listOf("renamed.cbz"), shareDir.list()!!.toList())
    }
}
