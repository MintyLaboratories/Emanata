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
import com.google.android.material.tabs.TabLayout
import com.infinity.comicreader.R
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
import java.util.concurrent.TimeUnit
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
        FileClipboard.clip = null
        // Library screens other tests left open watch the old state, so only this test's show outcomes.
        FileWork.reset()
        Remotes.serve(emptyList()) { throw AssertionError("no shares") }
    }

    @After
    fun tearDown() {
        settle()
        launched.forEach { if (!it.get().isDestroyed) it.pause().stop().destroy() }
        FileClipboard.clip = null
        Remotes.serve(emptyList()) { throw AssertionError("no shares") }
    }

    private fun idle() = repeat(10) {
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(20)
    }

    /** Waits until [done], then for the file operation under way to end and its outcome to be shown. */
    private fun settle(done: () -> Boolean = { true }) {
        repeat(300) {
            idle()
            if (done() && FileWork.status.value == null) return
        }
        throw AssertionError("still running: ${FileWork.status.value}, toast ${ShadowToast.getTextOfLatestToast()}")
    }

    private fun AlertDialog.title(): String = findViewById<android.widget.TextView>(androidx.appcompat.R.id.alertTitle)!!.text.toString()

    private fun AlertDialog.edit(): EditText = findEdit(window!!.decorView as android.view.ViewGroup)!!

    private val launched = ArrayList<ActivityController<ListActivity>>()

    /** A library screen; each goes at the end, so only the latest shows file operations' outcomes. */
    private fun launch(): ListActivity {
        launched.forEach { if (!it.get().isDestroyed) it.pause().stop().destroy() }
        return Robolectric.buildActivity(ListActivity::class.java).also { launched += it }.setup().get().also { idle() }
    }

    private fun ListActivity.recycler(): RecyclerView = findViewById<RecyclerView>(R.id.recycler).apply {
        measure(0, 0)
        layout(0, 0, 1000, 4000)
    }

    /** The row of [path], waiting for a listing under way to show it. */
    private fun ListActivity.row(path: String): View {
        repeat(300) {
            val recycler = recycler()
            val adapter = recycler.adapter as BookAdapter
            recycler.findViewHolderForAdapterPosition(adapter.positionOf(path))?.let { return it.itemView }
            idle()
        }
        throw AssertionError("no row for $path")
    }

    /** Clicks Paste and waits for the dialog it asks with. */
    private fun ListActivity.pasteAsking(): AlertDialog {
        val before = ShadowDialog.getLatestDialog()
        paste().performClick()
        repeat(300) {
            idle()
            val now = ShadowDialog.getLatestDialog()
            if (now !== before && now.isShowing) return now as AlertDialog
        }
        throw AssertionError("nothing asked")
    }

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

    /** The ⋮ menu's items for what is selected. */
    private fun ListActivity.moreItems(): List<MoreAction> {
        bar(R.id.selection_more).performClick()
        idle()
        val menu = moreMenu ?: return emptyList()
        return (0 until menu.childCount).map { menu.getChildAt(it).tag as MoreAction }
    }

    private fun View.usable(): Boolean = visibility == View.VISIBLE && isEnabled

    private fun latest() = ShadowDialog.getLatestDialog() as AlertDialog

    private fun ListActivity.paste(): View = findViewById(R.id.paste_fab)

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
        // Read already; Peek is off until turned on; Book info for one book.
        assertEquals(listOf(MoreAction.MARK_UNREAD, MoreAction.BOOK_INFO), activity.moreItems())
        activity.select(book.absolutePath)
        activity.select(other.absolutePath)
        assertEquals(listOf(MoreAction.MARK_ALL_READ, MoreAction.MARK_ALL_UNREAD, MoreAction.SET_STARTUP), activity.moreItems())
        // Both: what applies to all of them, and nothing that takes one.
        activity.select(book.absolutePath)
        assertEquals(listOf(MoreAction.MARK_UNREAD, MoreAction.MARK_ALL_READ, MoreAction.MARK_ALL_UNREAD), activity.moreItems())

        // Settings reorder and hide items.
        Prefs(context).moreActions = com.infinity.comicreader.data.MoreActions.DEFAULT.moved(3, 0).with(MoreAction.MARK_ALL_READ, false)
        assertEquals(listOf(MoreAction.MARK_ALL_UNREAD, MoreAction.MARK_UNREAD), activity.moreItems())
    }

    @Test
    fun markingFromTheMenuMarksEveryBookSelected() {
        val two = File(shelf, "two.cbz").apply { writeBytes(ByteArray(30) { 1 }) }
        val activity = launch()
        activity.select(two.absolutePath)
        activity.select(book.absolutePath)
        assertEquals(listOf(MoreAction.MARK_READ, MoreAction.MARK_UNREAD), activity.moreItems())
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
        assertEquals(color(com.google.android.material.R.attr.colorSecondaryContainer), toggle)
        activity.select(book.absolutePath)
        assertEquals(context.getString(R.string.fab_open), fab.contentDescription)
        assertEquals(color(com.google.android.material.R.attr.colorPrimaryContainer), fab.backgroundTintList!!.defaultColor)
        activity.select(other.absolutePath)
        assertEquals(context.getString(R.string.file_op_move), fab.contentDescription)
        assertEquals(color(com.google.android.material.R.attr.colorTertiaryContainer), fab.backgroundTintList!!.defaultColor)
        fab.performClick()
        idle()
        assertEquals(listOf(book.absolutePath, other.absolutePath), FileClipboard.clip!!.items.map { it.path })
        assertTrue(FileClipboard.clip!!.move)
        assertFalse(activity.selection.active)

        // Paste never looks like the Move button beside it: its own fill, Move's colors only on its badge.
        val paste = activity.paste() as com.google.android.material.floatingactionbutton.FloatingActionButton
        val badge = activity.findViewById<android.widget.ImageView>(R.id.paste_badge)
        activity.select(book.absolutePath)
        activity.select(other.absolutePath)
        assertEquals(View.VISIBLE, paste.visibility)
        assertEquals(View.VISIBLE, badge.visibility)
        assertEquals(color(com.google.android.material.R.attr.colorSurfaceInverse), paste.backgroundTintList!!.defaultColor)
        assertNotEquals(fab.backgroundTintList!!.defaultColor, paste.backgroundTintList!!.defaultColor)
        assertEquals(fab.backgroundTintList!!.defaultColor, badge.backgroundTintList!!.defaultColor)
        // A copy's badge has Copy's colors.
        activity.bar(R.id.selection_copy).performClick()
        idle()
        assertEquals(color(com.google.android.material.R.attr.colorSecondary), badge.backgroundTintList!!.defaultColor)
        assertEquals(color(com.google.android.material.R.attr.colorSurfaceInverse), paste.backgroundTintList!!.defaultColor)

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

        activity.select(book.absolutePath)
        activity.select(two.absolutePath)
        assertEquals(context.getString(R.string.selection_unfavorite), favorite.contentDescription)
        favorite.performClick()
        settle { !library.isFavorite(bookId(two)) }
        assertFalse(library.isFavorite(bookId(book)))
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
        settle { !dialog.isShowing }

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
        BookShare.coverHold = { taps.tryAcquire(10, TimeUnit.SECONDS) }
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
        assertEquals(View.GONE, activity.paste().visibility)
        assertEquals(book.absolutePath, FileClipboard.clip?.items?.single()?.path)

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
        assertEquals(View.GONE, activity.paste().visibility)
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
        assertNull(FileClipboard.clip)
        assertEquals(View.GONE, activity.paste().visibility)
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

        activity.pasteAsking().getButton(DialogInterface.BUTTON_NEUTRAL).performClick()
        settle { File(other, "one (2).cbz").exists() }
        assertEquals(listOf("one (2).cbz", "one.cbz"), other.list()!!.sorted())
        assertArrayEquals(bytes, File(other, "one (2).cbz").readBytes())

        activity.pasteAsking().getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        settle { File(other, "one.cbz").length() == bytes.size.toLong() }
        assertArrayEquals(bytes, File(other, "one.cbz").readBytes())
        // A copy stays picked up; the source stays.
        assertTrue(book.isFile)
        assertNotNull(FileClipboard.clip)

        // A long press on Paste drops it.
        activity.paste().performLongClick()
        idle()
        assertNull(FileClipboard.clip)
        assertEquals(context.getString(R.string.paste_canceled), ShadowToast.getTextOfLatestToast())
        assertEquals(View.GONE, activity.paste().visibility)
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
        FileClipboard.clip = FileClipboard.Clip(files.map { FileClipboard.Item(it.absolutePath, it.isDirectory) }, move)
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
        assertNull(FileClipboard.clip)
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

        // Keep both names each anew.
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
        val clock = FileWork.clock
        var now = 1_000L
        FileWork.clock = { now }
        val go = java.util.concurrent.Semaphore(0)
        val at = java.util.concurrent.Semaphore(0)
        try {
            FileWork.start(FileWork.Kind.COPY, "big.cbz") { p ->
                at.release()
                go.acquire()
                p.report(5, 10)
                at.release()
                go.acquire()
                p.stoppable(false)
                at.release()
                go.acquire()
                FileWork.Outcome.Done(null)
            }
            at.acquire()
            // Long enough under way for its progress to show.
            now = 2_000L
            go.release()
            at.acquire()
            idle()
            val dialog = latest()
            assertEquals(View.VISIBLE, dialog.getButton(DialogInterface.BUTTON_NEGATIVE).visibility)
            go.release()
            at.acquire()
            idle()
            assertEquals(View.GONE, dialog.getButton(DialogInterface.BUTTON_NEGATIVE).visibility)
            go.release()
            settle()
        } finally {
            FileWork.clock = clock
        }
        assertNotNull(activity)
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
